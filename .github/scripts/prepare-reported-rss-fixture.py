"""Prepare the reporter's exact RSS JSON bytes in the test APK before device tests."""

import hashlib
from pathlib import Path
from urllib.error import URLError
from urllib.request import Request, urlopen


URL = "https://github.com/user-attachments/files/32066159/shareRssSource.json"
SHA256 = "0893a7bf2af946735d331d1e37acdf3aa3bf05f3b5beb45181f736e15509f9a9"
TARGET = Path(__file__).resolve().parents[2] / "app/src/androidTest/assets/reported_rss_source.json"


def prepare() -> None:
    if TARGET.is_file() and hashlib.sha256(TARGET.read_bytes()).hexdigest() == SHA256:
        print(f"Verified cached reported RSS fixture: {SHA256}")
        return
    for attempt in range(3):
        try:
            with urlopen(Request(URL, headers={"User-Agent": "Legado-test-fixture"}), timeout=60) as response:
                data = response.read()
            break
        except (URLError, TimeoutError):
            if attempt == 2:
                raise
            print(f"Retrying reported RSS fixture download after attempt {attempt + 1}")
    if hashlib.sha256(data).hexdigest() != SHA256:
        raise RuntimeError("Reported RSS fixture does not match the pinned original bytes")
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    temporary = TARGET.with_suffix(".json.part")
    try:
        temporary.write_bytes(data)
        temporary.replace(TARGET)
    finally:
        temporary.unlink(missing_ok=True)
    print(f"Verified reported RSS fixture: {len(data)} bytes, {SHA256}")


if __name__ == "__main__":
    prepare()
