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
    if len(provenance["notices"]) != 16:
        raise ValueError("The complete PDFium native notice inventory is required")


def check_binary(path, target):
    if sha256(path) != PINS["targets"][target]["binarySha256"]:
        raise ValueError(f"Unverified PDFium consumer binary: {target}")
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hooks-dir", type=Path)
    parser.add_argument("--library", type=Path)
    parser.add_argument("--target", choices=PINS["targets"])
    args = parser.parse_args()
    check_notices()
    checked = set()
    if args.library:
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
        parser.error("Provide --hooks-dir or --library and --target")
    print("Verified native PDFium: " + ", ".join(sorted(checked)))
    print("Verified 16 exact upstream license payloads (PDFium and third parties)")


if __name__ == "__main__":
    main()
