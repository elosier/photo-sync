"""Runtime configuration, sourced from environment variables.

All settings can be provided via a .env file (see .env.example) or the
process environment. Nothing here is secret at rest except PHOTOSYNC_TOKEN,
which must be supplied and should be a long random string.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _get_bool(name: str, default: bool) -> bool:
    val = os.environ.get(name)
    if val is None:
        return default
    return val.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    # Shared secret the Android app must present as `Authorization: Bearer <token>`.
    token: str
    # Root directory under which per-device folders are created.
    data_dir: Path
    # Bind address / port for uvicorn (the reverse proxy forwards to this).
    host: str
    port: int
    # Path prefix when mounted behind a reverse proxy, e.g. "/photos".
    # Leave empty if the service owns the whole host.
    root_path: str
    # Reject uploads larger than this many bytes (0 = unlimited). Default 8 GiB.
    max_upload_bytes: int
    # chmod applied to created directories (default 0o777 per project spec).
    dir_mode: int
    # chmod applied to stored files.
    file_mode: int

    @staticmethod
    def load() -> "Settings":
        token = os.environ.get("PHOTOSYNC_TOKEN", "").strip()
        if not token:
            raise RuntimeError(
                "PHOTOSYNC_TOKEN is not set. Generate one with "
                "`python -c \"import secrets; print(secrets.token_urlsafe(48))\"` "
                "and put it in the server .env AND the Android app settings."
            )
        if len(token) < 24:
            raise RuntimeError("PHOTOSYNC_TOKEN is too short; use >= 24 characters.")

        data_dir = Path(os.environ.get("PHOTOSYNC_DATA_DIR", "/data/photos")).resolve()

        root_path = os.environ.get("PHOTOSYNC_ROOT_PATH", "").rstrip("/")
        if root_path and not root_path.startswith("/"):
            root_path = "/" + root_path

        return Settings(
            token=token,
            data_dir=data_dir,
            host=os.environ.get("PHOTOSYNC_HOST", "127.0.0.1"),
            port=int(os.environ.get("PHOTOSYNC_PORT", "8080")),
            root_path=root_path,
            max_upload_bytes=int(os.environ.get("PHOTOSYNC_MAX_UPLOAD_BYTES", str(8 * 1024**3))),
            # Octal strings like "777" / "0o777" both accepted.
            dir_mode=int(os.environ.get("PHOTOSYNC_DIR_MODE", "777").replace("0o", ""), 8),
            file_mode=int(os.environ.get("PHOTOSYNC_FILE_MODE", "644").replace("0o", ""), 8),
        )
