#!/usr/bin/env python3
"""Contract and malformed-input tests; not Windows runtime acceptance."""
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest

import link_sdk
import prebuilt
import windows_sdk as consumer


def pe_fixture(target='windows-x64'):
    data = bytearray(4096)
    def put(fmt, offset, *values):
        struct.pack_into(fmt, data, offset, *values)
    data[:2] = b'MZ'
    put('<I', 0x3c, 0x80)
    data[0x80:0x84] = b'PE\0\0'
    put('<HH', 0x84, consumer.MACHINES[target], 1)
    put('<HH', 0x94, 240, 0x2022)
    put('<H', 0x98, 0x20b)
    put('<I', 0x98 + 108, 16)
    put('<II', 0x98 + 112, 0x1000, 0x400)
    # One section: RVA 0x1000 maps to file offset 0x200.
    put('<IIII', 0x98 + 240 + 8, 0xe00, 0x1000, 0xe00, 0x200)
    names = sorted(consumer.WINDOWS_EXPORTS)
    put('<IIIII', 0x200 + 20, len(names), len(names), 0x1040, 0x1080, 0x10c0)
    cursor = 0x300
    for index, name in enumerate(names):
        put('<I', 0x240 + 4 * index, 0x1500 + index)
        put('<I', 0x280 + 4 * index, 0x1000 + cursor - 0x200)
        put('<H', 0x2c0 + 2 * index, index)
        encoded = name.encode() + b'\0'
        data[cursor:cursor + len(encoded)] = encoded
        cursor += len(encoded)
    return data


class PeInspectionTest(unittest.TestCase):
    def test_both_architectures_require_exact_nonforwarded_c_exports(self):
        for target in consumer.MACHINES:
            actual = consumer.inspect_pe(pe_fixture(target), target)
            self.assertEqual(actual['machine'], consumer.MACHINES[target])
            self.assertEqual(set(actual['exports']), consumer.WINDOWS_EXPORTS)

    def test_machine_mismatch(self):
        with self.assertRaisesRegex(ValueError, 'architecture'):
            consumer.inspect_pe(pe_fixture(), 'windows-arm64')

    def test_truncations_never_escape_as_struct_errors(self):
        data = pe_fixture()
        for size in (0, 2, 60, 130, 150, 240, 550, 800):
            with self.subTest(size=size), self.assertRaises(ValueError):
                consumer.inspect_pe(data[:size], 'windows-x64')

    def test_reject_executable_instead_of_dll(self):
        data = pe_fixture()
        struct.pack_into('<H', data, 0x96, 0x22)
        with self.assertRaisesRegex(ValueError, 'DLL'):
            consumer.inspect_pe(data, 'windows-x64')

    def test_forwarded_export_rejected(self):
        data = pe_fixture()
        struct.pack_into('<I', data, 0x240, 0x1100)
        with self.assertRaisesRegex(ValueError, 'forwarded'):
            consumer.inspect_pe(data, 'windows-x64')

    def test_bad_ordinal_and_wrong_name_rejected(self):
        data = pe_fixture()
        struct.pack_into('<H', data, 0x2c0, 65535)
        with self.assertRaisesRegex(ValueError, 'ordinal'):
            consumer.inspect_pe(data, 'windows-x64')
        data = pe_fixture()
        data[0x300] = ord('x')
        with self.assertRaisesRegex(ValueError, 'ABI exports differ'):
            consumer.inspect_pe(data, 'windows-x64')

    def test_unmapped_directory_and_extra_exports_rejected(self):
        data = pe_fixture()
        struct.pack_into('<I', data, 0x98 + 112, 0xffffff00)
        with self.assertRaisesRegex(ValueError, 'unmapped'):
            consumer.inspect_pe(data, 'windows-x64')
        data = pe_fixture()
        struct.pack_into('<I', data, 0x200 + 20, 12)
        with self.assertRaisesRegex(ValueError, 'only the reviewed'):
            consumer.inspect_pe(data, 'windows-x64')


class ToolchainTest(unittest.TestCase):
    def fixture(self, directory):
        root = Path(directory) / 'toolchain'
        for name in ('clang/bin/clang-cl.exe', 'clang/bin/lld-link.exe', 'msvc/include/stdint.h',
                     'msvc/lib/x64/libcmt.lib', 'sdk/include/windows.h', 'sdk/lib/x64/kernel32.lib'):
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(('test-fixture:' + name).encode())
        lock = {'schemaVersion': 1, 'target': 'windows-x64', 'clangVersion': 'clang version 24-test',
                'msvcVersion': '14.44.35207', 'windowsSdkVersion': '10.0.26100.0',
                'compiler': 'clang/bin/clang-cl.exe', 'linker': 'clang/bin/lld-link.exe',
                'msvcRoot': 'msvc', 'windowsSdkRoot': 'sdk',
                'includeDirs': ['msvc/include', 'sdk/include'],
                'libraryDirs': ['msvc/lib/x64', 'sdk/lib/x64'],
                'files': {p.relative_to(root).as_posix(): prebuilt.sha(p) for p in root.rglob('*') if p.is_file()}}
        path = Path(directory) / 'toolchain-lock.json'
        path.write_text(json.dumps(lock))
        return root, path, prebuilt.sha(path)

    def test_complete_authenticated_inventory(self):
        with tempfile.TemporaryDirectory() as temp:
            root, lock, sha = self.fixture(temp)
            self.assertEqual(consumer.verify_toolchain(root, lock, sha, 'windows-x64')['target'], 'windows-x64')
            with self.assertRaisesRegex(ValueError, 'lock SHA256'):
                consumer.verify_toolchain(root, lock, '0' * 64, 'windows-x64')
            with self.assertRaisesRegex(ValueError, 'target/schema'):
                consumer.verify_toolchain(root, lock, sha, 'windows-arm64')

    def test_tampering_and_unindexed_files_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root, lock, sha = self.fixture(temp)
            (root / 'sdk/include/windows.h').write_text('tampered')
            with self.assertRaisesRegex(ValueError, 'file hash'):
                consumer.verify_toolchain(root, lock, sha, 'windows-x64')
        with tempfile.TemporaryDirectory() as temp:
            root, lock, sha = self.fixture(temp)
            (root / 'rogue.lib').write_text('rogue')
            with self.assertRaisesRegex(ValueError, 'file hash'):
                consumer.verify_toolchain(root, lock, sha, 'windows-x64')

    def test_reviewed_release_pins_match_supported_windows_targets(self):
        here = Path(__file__).resolve().parent
        pins = json.loads((here / 'windows-pins.json').read_text())
        release = json.loads((here / 'windows-release-pin.json').read_text())
        prebuilt.validate_pin(release, pins)
        self.assertEqual(set(pins['targets']), set(consumer.MACHINES))
        self.assertEqual({v['target'] for v in release['assets']}, set(consumer.MACHINES))


if __name__ == '__main__':
    unittest.main()
