#!/usr/bin/env python3
"""Reject legacy JavaScript engine dependencies in a built Android APK."""
import argparse
from pathlib import Path
import struct
import zipfile

FORBIDDEN = (b'Lorg/htmlunit/', b'Lorg/mozilla/javascript/', b'Lcom/script/')


def dex_types(data):
    if len(data) < 112 or not data.startswith(b'dex\n'):
        raise ValueError('Invalid DEX header')
    strings, strings_offset = struct.unpack_from('<II', data, 56)
    types, types_offset = struct.unpack_from('<II', data, 64)
    if strings_offset + strings * 4 > len(data) or types_offset + types * 4 > len(data):
        raise ValueError('DEX identifier tables escape file')
    for index in range(types):
        string_index = struct.unpack_from('<I', data, types_offset + index * 4)[0]
        if string_index >= strings:
            raise ValueError('DEX type references an invalid string')
        offset = struct.unpack_from('<I', data, strings_offset + string_index * 4)[0]
        for _ in range(5):
            if offset >= len(data):
                raise ValueError('Truncated DEX string length')
            byte = data[offset]
            offset += 1
            if not byte & 128:
                break
        else:
            raise ValueError('Invalid DEX string length')
        end = data.find(b'\0', offset)
        if end == -1:
            raise ValueError('Unterminated DEX descriptor')
        yield data[offset:end]


def verify(apk):
    dex_count = 0
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if name.startswith('assets/licenses/htmlunit-core-js/'):
                raise ValueError('Removed engine license assets remain in APK')
            if name.endswith('.jar') and any(word in name.lower() for word in ('rhino', 'htmlunit')):
                raise ValueError('Removed engine JAR remains in APK')
            if '/' not in name and name.startswith('classes') and name.endswith('.dex'):
                dex_count += 1
                for descriptor in dex_types(archive.read(name)):
                    if descriptor.lstrip(b'[').startswith(FORBIDDEN):
                        raise ValueError('Removed engine type remains: ' + descriptor.decode('utf-8', errors='replace'))
        if not dex_count:
            raise ValueError('APK contains no DEX to verify')
    return dex_count


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    args = parser.parse_args()
    print(f'V8-only APK verified: {verify(args.apk)} DEX files contain no legacy engine types')
