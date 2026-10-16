#!/usr/bin/env python3
"""Verify the consumed PDFium hook binary and complete shipped native notices.

The pinned upstream hook downloads archives without authenticating their digest.
These hashes were extracted only after checking each release archive's SHA256
against GitHub's immutable asset digest. This consumer check never builds PDFium.
Apple application builds additionally use SwiftPM's fixed XCFramework checksum.
"""
import argparse
import hashlib
import json
import io
import os
import tarfile
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PINS = json.loads((ROOT / "tool/pdfium-pins.json").read_text())
NOTICE_ROOT = ROOT / "apps/legado/assets/notices/pdfium"


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check_notices():
    provenance = json.loads((NOTICE_ROOT / "provenance.json").read_text())
    if provenance["release"] != PINS["release"]:
        raise ValueError("PDFium binary and notice releases differ")
    for name, value in provenance["notices"].items():
        if sha256(NOTICE_ROOT / name) != value["sha256"]:
            raise ValueError(f"PDFium native license mismatch: {name}")
    if len(provenance["notices"]) != 29:
        raise ValueError("The complete PDFium native notice inventory is required")
    if set(provenance["targetNotices"]) != set(PINS["targets"]):
        raise ValueError("Every supported native PDFium target requires a notice mapping")
    for mapping in provenance["targetNotices"].values():
        for name in mapping.values():
            if name not in provenance["notices"]:
                raise ValueError("A platform-specific PDFium native notice is missing")


def check_binary(path, target):
    if sha256(path) != PINS["targets"][target]["binarySha256"]:
        raise ValueError(f"Unverified PDFium consumer binary: {target}")
    return target


def prepare(target):
    pin = PINS["targets"][target]
    cache = ROOT / ".dart_tool/hooks_runner/shared/pdfium_dart/build/chromium_7811" / target
    destination = cache / Path(pin["member"]).name
    if destination.exists():
        return check_binary(destination, target)
    with urllib.request.urlopen(pin["archive"], timeout=60) as response:
        archive = response.read(40 * 1024 * 1024 + 1)
    if len(archive) > 40 * 1024 * 1024:
        raise ValueError("PDFium archive exceeds the bounded download limit")
    if hashlib.sha256(archive).hexdigest() != pin["archiveSha256"]:
        raise ValueError("PDFium release archive SHA256 mismatch")
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as bundle:
        member = bundle.getmember(pin["member"])
        if not member.isfile() or member.size > 100 * 1024 * 1024:
            raise ValueError("Invalid PDFium release binary member")
        binary = bundle.extractfile(member).read()
    if hashlib.sha256(binary).hexdigest() != pin["binarySha256"]:
        raise ValueError("PDFium release binary SHA256 mismatch")
    cache.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=cache, delete=False) as output:
        temporary = Path(output.name)
        output.write(binary)
    os.replace(temporary, destination)
    return check_binary(destination, target)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hooks-dir", type=Path)
    parser.add_argument("--library", type=Path)
    parser.add_argument("--target", choices=PINS["targets"])
    parser.add_argument("--prepare-target", choices=PINS["targets"])
    args = parser.parse_args()
    check_notices()
    checked = set()
    if args.prepare_target:
        checked.add(prepare(args.prepare_target))
    elif args.library:
        if not args.target:
            parser.error("--library requires --target")
        checked.add(check_binary(args.library, args.target))
    elif args.hooks_dir:
        for output in args.hooks_dir.glob("**/output.json"):
            data = json.loads(output.read_text())
            for asset in data.get("assets", []):
                encoding = asset.get("encoding", {})
                if encoding.get("id") != "package:pdfium_dart/libpdfium":
                    continue
                path = Path(encoding["file"])
                target = path.parent.name
                if target not in PINS["targets"]:
                    raise ValueError("Unknown PDFium target in build-hook output")
                checked.add(check_binary(path, target))
        if not checked:
            raise ValueError("No consumed PDFium build-hook binary found")
    else:
        parser.error("Provide --prepare-target, --hooks-dir, or --library and --target")
    print("Verified native PDFium: " + ", ".join(sorted(checked)))
    print("Verified 29 exact upstream license payloads covering all 8 native targets")


if __name__ == "__main__":
    main()
