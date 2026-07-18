"""FastAPI application: the photo-sync receiver.

Endpoints (all under the configured root_path, e.g. /photos):

    GET  /health                      -> liveness probe (no auth)
    GET  /v1/exists                   -> ask whether a file is already stored
    POST /v1/upload                   -> stream one photo/video to disk

Auth: every /v1 endpoint requires `Authorization: Bearer <PHOTOSYNC_TOKEN>`.

The upload body is the raw file bytes (application/octet-stream). Metadata is
passed in headers so we can stream the body straight to disk without buffering
whole videos in memory.
"""

from __future__ import annotations

import hashlib
import logging
import tempfile
import time
from pathlib import Path
from urllib.parse import unquote

from fastapi import Depends, FastAPI, Header, HTTPException, Request, status
from fastapi.responses import JSONResponse

from . import __version__
from .auth import make_auth_dependency
from .config import Settings
from .storage import Storage, ValidationError, sanitize_filename, validate_device, validate_reldate

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
log = logging.getLogger("photosync")

settings = Settings.load()
storage = Storage(settings.data_dir, settings.dir_mode, settings.file_mode)
require_auth = make_auth_dependency(settings.token)

# Make sure the root data dir exists (do not force-chmod an existing root).
settings.data_dir.mkdir(parents=True, exist_ok=True)


def _sweep_stale_parts(max_age_hours: int = 24) -> None:
    """Remove .part temp files orphaned by a crash mid-upload."""
    cutoff = time.time() - max_age_hours * 3600
    removed = 0
    for part in settings.data_dir.rglob("*.part"):
        try:
            if part.stat().st_mtime < cutoff:
                part.unlink()
                removed += 1
        except OSError:
            pass
    if removed:
        log.info("swept %d stale .part file(s)", removed)


_sweep_stale_parts()

app = FastAPI(title="Photo Sync", version=__version__, root_path=settings.root_path)


@app.get("/health")
async def health() -> dict:
    # Unauthenticated liveness probe: deliberately no server paths or config.
    return {"status": "ok", "version": __version__}


@app.get("/v1/exists", dependencies=[Depends(require_auth)])
async def exists(
    device: str,
    reldate: str,
    filename: str,
    sha256: str | None = None,
) -> dict:
    try:
        device = validate_device(device)
        reldate = validate_reldate(reldate)
        filename = sanitize_filename(filename)
    except ValidationError as exc:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, str(exc))
    present = storage.exists(device, reldate, filename, sha256)
    return {"exists": present}


@app.post("/v1/upload", dependencies=[Depends(require_auth)])
async def upload(
    request: Request,
    x_device: str = Header(...),
    x_capture_date: str = Header(...),  # "YYYY/YYYY_mm_dd"
    x_filename: str = Header(...),
    x_sha256: str | None = Header(default=None),
    x_file_size: int | None = Header(default=None),
) -> JSONResponse:
    try:
        device = validate_device(x_device)
        reldate = validate_reldate(x_capture_date)
        # X-Filename is percent-encoded by the client to stay ASCII-safe.
        filename = sanitize_filename(unquote(x_filename))
    except ValidationError as exc:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, str(exc))

    if x_file_size is not None and settings.max_upload_bytes and x_file_size > settings.max_upload_bytes:
        raise HTTPException(status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, "file too large")

    # Fast path: if the client told us the hash and we already have it, skip
    # reading the body entirely.
    if x_sha256 and storage.exists(device, reldate, filename, x_sha256):
        rel = str(storage.target_dir(device, reldate).relative_to(settings.data_dir) / filename)
        return JSONResponse({"status": "exists", "relative_path": rel, "sha256": x_sha256.lower()})

    # Stream the body to a temp file in the destination dir's filesystem so the
    # final rename is atomic. We hash as we go and enforce the size cap.
    storage_dir = storage.target_dir(device, reldate)
    storage._ensure_dir(storage_dir)  # noqa: SLF001 - internal helper reused intentionally

    hasher = hashlib.sha256()
    written = 0
    tmp_fd, tmp_name = tempfile.mkstemp(dir=storage_dir, suffix=".part")
    tmp_path = Path(tmp_name)
    try:
        with open(tmp_fd, "wb") as out:
            async for chunk in request.stream():
                if not chunk:
                    continue
                written += len(chunk)
                if settings.max_upload_bytes and written > settings.max_upload_bytes:
                    raise HTTPException(
                        status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, "file too large"
                    )
                hasher.update(chunk)
                out.write(chunk)
    except HTTPException:
        tmp_path.unlink(missing_ok=True)
        raise
    except Exception:  # noqa: BLE001 - map any I/O failure to 500 after cleanup
        tmp_path.unlink(missing_ok=True)
        log.exception("upload failed for %s/%s/%s", device, reldate, filename)
        raise HTTPException(status.HTTP_500_INTERNAL_SERVER_ERROR, "write failed")

    if written == 0:
        tmp_path.unlink(missing_ok=True)
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "empty body")

    digest = hasher.hexdigest()
    if x_sha256 and x_sha256.lower() != digest:
        tmp_path.unlink(missing_ok=True)
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY,
            "sha256 mismatch (transfer corrupted)",
        )

    result = storage.finalize_from_temp(
        device=device,
        reldate=reldate,
        filename=filename,
        temp_path=tmp_path,
        digest=digest,
        size=written,
    )
    log.info("%s %s (%d bytes)", result.status, result.relative_path, result.size)
    return JSONResponse(
        {
            "status": result.status,
            "relative_path": result.relative_path,
            "sha256": result.sha256,
            "size": result.size,
        }
    )
