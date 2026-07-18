"""Standalone tests for storage logic (no FastAPI / network needed).

Run: python3 tests/test_storage.py
"""

import hashlib
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.storage import (  # noqa: E402
    Storage,
    ValidationError,
    sanitize_filename,
    validate_device,
    validate_reldate,
)

passed = 0


def check(name, cond):
    global passed
    assert cond, f"FAILED: {name}"
    passed += 1
    print(f"  ok  {name}")


def expect_error(name, fn):
    try:
        fn()
    except ValidationError:
        check(name, True)
    else:
        raise AssertionError(f"FAILED (expected ValidationError): {name}")


def main():
    print("validation:")
    check("device lowercased", validate_device("Myphone-GalaxyS25Ultra") == "myphone-galaxys25ultra")
    check("device ok", validate_device("myphone-galaxys25ultra") == "myphone-galaxys25ultra")
    expect_error("device rejects slash", lambda: validate_device("a/b"))
    expect_error("device rejects traversal", lambda: validate_device(".."))
    check("reldate ok", validate_reldate("2025/2025_11_28") == "2025/2025_11_28")
    expect_error("reldate rejects mismatch year", lambda: validate_reldate("2025/2024_11_28"))
    expect_error("reldate rejects traversal", lambda: validate_reldate("../2025_11_28"))
    expect_error("reldate rejects bad month", lambda: validate_reldate("2025/2025_13_28"))
    check("filename basename only", sanitize_filename("/etc/passwd") == "passwd")
    check("filename strips backslash path", sanitize_filename("a\\b\\c.jpg") == "c.jpg")
    expect_error("filename rejects dotdot", lambda: sanitize_filename(".."))

    print("storage:")
    with tempfile.TemporaryDirectory() as d:
        data = Path(d)
        store = Storage(data, dir_mode=0o777, file_mode=0o644)

        content = b"hello-photo-bytes" * 1000
        digest = hashlib.sha256(content).hexdigest()

        # simulate a streamed upload: write temp then finalize
        def do_upload(name, body):
            dig = hashlib.sha256(body).hexdigest()
            target = store.target_dir("myphone-galaxys25ultra", "2025/2025_11_28")
            store._ensure_dir(target)
            fd, tmp = tempfile.mkstemp(dir=target, suffix=".part")
            with os.fdopen(fd, "wb") as fh:
                fh.write(body)
            return store.finalize_from_temp(
                device="myphone-galaxys25ultra",
                reldate="2025/2025_11_28",
                filename=name,
                temp_path=Path(tmp),
                digest=dig,
                size=len(body),
            )

        r1 = do_upload("IMG_1.jpg", content)
        check("first store status stored", r1.status == "stored")
        check(
            "correct nested path",
            r1.relative_path == "myphone-galaxys25ultra/2025/2025_11_28/IMG_1.jpg",
        )
        stored_file = data / r1.relative_path
        check("file written", stored_file.exists() and stored_file.read_bytes() == content)

        # dir mode 777
        date_dir = data / "myphone-galaxys25ultra" / "2025" / "2025_11_28"
        mode = oct(date_dir.stat().st_mode & 0o777)
        check(f"date dir mode 777 (got {mode})", (date_dir.stat().st_mode & 0o777) == 0o777)

        # re-upload identical -> exists, no duplicate
        r2 = do_upload("IMG_1.jpg", content)
        check("duplicate detected", r2.status == "exists")
        check("no _suffix duplicate created", not (date_dir / "IMG_1_" ).exists())

        # same name, different content -> side-by-side with hash suffix
        other = b"totally different bytes"
        r3 = do_upload("IMG_1.jpg", other)
        check("collision stored separately", r3.status == "stored" and r3.relative_path != r1.relative_path)
        check("collision keeps original intact", stored_file.read_bytes() == content)

        # exists() check by hash
        check("exists() true by hash", store.exists("myphone-galaxys25ultra", "2025/2025_11_28", "IMG_1.jpg", digest))
        check("exists() false wrong hash", not store.exists("myphone-galaxys25ultra", "2025/2025_11_28", "IMG_1.jpg", "deadbeef"))
        check("exists() false missing", not store.exists("myphone-galaxys25ultra", "2025/2025_11_28", "nope.jpg", None))

    print(f"\nAll {passed} checks passed.")


if __name__ == "__main__":
    main()
