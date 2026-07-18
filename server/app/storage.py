"""Filesystem layout, validation, and safe/atomic writes.

Target layout (per project spec):

    <data_dir>/<phonename-phonemodel>/YYYY/YYYY_mm_dd/<filename>

Example:

    /data/photos/myphone-galaxys25ultra/2025/2025_11_28/IMG_20251128_101500.jpg

Directories are created with mode 0o777. Writes are atomic: the body is
streamed to a temporary ``.part`` file in the destination directory, then
renamed into place. This service never deletes or overwrites existing media
with different content (one-way backup); a content collision on the same name
is stored side-by-side with a short hash suffix.
"""

from __future__ import annotations

import hashlib
import os
import re
from dataclasses import dataclass
from pathlib import Path

# phonename-phonemodel, lowercase per spec. Allow a-z, 0-9, dot, underscore, hyphen.
_DEVICE_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,127}$")
# Exactly YYYY/YYYY_mm_dd
_RELDATE_RE = re.compile(r"^(\d{4})/(\d{4})_(\d{2})_(\d{2})$")


class ValidationError(ValueError):
    """Raised when client-supplied path components are unsafe or malformed."""


def validate_device(device: str) -> str:
    device = (device or "").strip().lower()
    if not _DEVICE_RE.match(device):
        raise ValidationError(
            "device must match phonename-phonemodel (lowercase a-z0-9._-)"
        )
    return device


def validate_reldate(reldate: str) -> str:
    reldate = (reldate or "").strip()
    m = _RELDATE_RE.match(reldate)
    if not m:
        raise ValidationError("capture date path must be YYYY/YYYY_mm_dd")
    year_a, year_b, month, day = m.groups()
    if year_a != year_b:
        raise ValidationError("year mismatch in capture date path")
    if not (1 <= int(month) <= 12) or not (1 <= int(day) <= 31):
        raise ValidationError("invalid month/day in capture date path")
    return reldate


def sanitize_filename(name: str) -> str:
    """Strip any path components and disallow traversal/hidden control chars."""
    name = (name or "").strip()
    # Keep only the basename; reject separators entirely.
    name = name.replace("\\", "/").split("/")[-1]
    if not name or name in {".", ".."}:
        raise ValidationError("invalid filename")
    # Remove control characters and NUL.
    name = "".join(ch for ch in name if ch.isprintable())
    if not name:
        raise ValidationError("invalid filename")
    # Cap length to keep well under filesystem limits.
    if len(name) > 200:
        stem, dot, ext = name.rpartition(".")
        name = (stem[:180] + dot + ext[:16]) if dot else name[:200]
    return name


@dataclass
class StoreResult:
    status: str  # "stored" | "exists"
    relative_path: str
    sha256: str
    size: int


def _dedup_target(directory: Path, filename: str, digest: str) -> tuple[Path, bool]:
    """Choose a final path for ``filename`` in ``directory``.

    Returns (path, already_present). If a file with the same name AND same
    sha256 already exists, ``already_present`` is True. If the name exists but
    the content differs, a ``name_<sha8>.ext`` sibling path is returned.
    """
    candidate = directory / filename
    if not candidate.exists():
        return candidate, False

    if _file_sha256(candidate) == digest:
        return candidate, True

    # Name collision, different content -> store side-by-side.
    stem, dot, ext = filename.rpartition(".")
    short = digest[:8]
    alt_name = f"{stem}_{short}{dot}{ext}" if dot else f"{filename}_{short}"
    alt = directory / alt_name
    if alt.exists() and _file_sha256(alt) == digest:
        return alt, True
    return alt, False


def _file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


class Storage:
    def __init__(self, data_dir: Path, dir_mode: int, file_mode: int) -> None:
        self.data_dir = data_dir
        self.dir_mode = dir_mode
        self.file_mode = file_mode

    def _ensure_dir(self, directory: Path) -> None:
        # Create device / year / date levels beneath data_dir, forcing the
        # requested mode on each level we create (mkdir honours umask, so we
        # chmod explicitly to guarantee 0o777 per the project spec).
        relative = directory.relative_to(self.data_dir)
        current = self.data_dir
        for part in relative.parts:
            current = current / part
            if not current.exists():
                current.mkdir()
                try:
                    os.chmod(current, self.dir_mode)
                except PermissionError:
                    pass

    def target_dir(self, device: str, reldate: str) -> Path:
        return self.data_dir / device / reldate

    def exists(self, device: str, reldate: str, filename: str, sha256: str | None) -> bool:
        directory = self.target_dir(device, reldate)
        candidate = directory / filename
        if not candidate.exists():
            return False
        if sha256:
            return _file_sha256(candidate) == sha256.lower()
        return True

    def finalize_from_temp(
        self,
        *,
        device: str,
        reldate: str,
        filename: str,
        temp_path: Path,
        digest: str,
        size: int,
    ) -> StoreResult:
        directory = self.target_dir(device, reldate)
        self._ensure_dir(directory)

        final_path, present = _dedup_target(directory, filename, digest)
        rel = str(final_path.relative_to(self.data_dir))

        if present:
            temp_path.unlink(missing_ok=True)
            return StoreResult("exists", rel, digest, size)

        os.replace(temp_path, final_path)  # atomic within same filesystem
        try:
            os.chmod(final_path, self.file_mode)
        except PermissionError:
            pass
        return StoreResult("stored", rel, digest, size)
