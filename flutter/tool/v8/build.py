#!/usr/bin/env python3
"""Read-only provenance helpers. V8 source builds are forbidden in Legado."""
import hashlib
import json
from pathlib import Path
import re
import struct

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
PACKAGE = ROOT / 'flutter/packages/source_v8'
TARGETS = ('macos-arm64', 'android-arm64', 'android-x64')

def read_pins():
    pins = json.loads((HERE / 'pins.json').read_text())
    for key in ('v8', 'depotTools'):
        if not re.fullmatch(r'[a-f0-9]{40}', pins[key]['revision']):
            raise ValueError('Pins require complete lowercase commit SHA')
        if not pins[key]['repository'].startswith('https://chromium.googlesource.com/'):
            raise ValueError('Only official Chromium source repositories permitted')
    if set(pins.get('targets', {})) != set(TARGETS):
        raise ValueError('Pinned target contract does not match supported platforms')
    return pins


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def bridge_files():
    return {name: PACKAGE / name for name in ('src/source_v8.cpp', 'src/source_v8.h', 'src/android_exports.map')} | {
        'tool/v8/source_v8.gni': HERE / 'source_v8.gni',
        'tool/v8/link_sdk.py': HERE / 'link_sdk.py',
        'tool/v8/toolchain-pins.json': HERE / 'toolchain-pins.json'}


def bridge_digest(files=None):
    digest = hashlib.sha256()
    for label, path in sorted((bridge_files() if files is None else files).items()):
        name = label.encode('utf-8')
        data = path.read_bytes()
        digest.update(struct.pack('>Q', len(name)))
        digest.update(name)
        digest.update(struct.pack('>Q', len(data)))
        digest.update(data)
    return digest.hexdigest()


if __name__ == '__main__':
    raise SystemExit('V8 source builds are disabled in Legado. Run '
                     'python3 flutter/tool/v8/prepare_sdk.py --target TARGET; '
                     'this downloads the pinned released SDK and compiles only the application bridge.')
