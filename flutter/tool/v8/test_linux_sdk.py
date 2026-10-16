import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('linux_bridge_sdk', Path(__file__).with_name('linux_sdk.py'))
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def elf(machine=183, kind=3):
    data = bytearray(120)
    data[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<HH', data, 16, kind, machine)
    struct.pack_into('<Q', data, 32, 64)
    struct.pack_into('<HH', data, 54, 56, 1)
    struct.pack_into('<I', data, 64, 1)
    return data


class LinuxSdkTests(unittest.TestCase):
    def test_linux_release_pins_are_fixed_and_separate_from_flutter_hook(self):
        pins = json.loads((module.HERE / 'linux-pins.json').read_text())
        release = json.loads((module.HERE / 'linux-release-pin.json').read_text())
        module.prebuilt.validate_pin(release, pins)
        self.assertEqual(set(pins['targets']), set(module.PROFILES))
        self.assertEqual(set(release['sdkManifestSha256']), set(module.PROFILES))
        self.assertEqual(set(pins['sysroots']), set(module.PROFILES))
        current = json.loads((module.HERE / 'pins.json').read_text())
        self.assertEqual(pins['v8'], current['v8'])
        self.assertFalse(set(current['targets']) & set(module.PROFILES))
        for target, pin in pins['sysroots'].items():
            self.assertEqual(pin['url'], 'https://commondatastorage.googleapis.com/chrome-linux-sysroot/' + pin['sha256'])
            module.prebuilt._digest(pin['filesSha256'])
            module.prebuilt._digest(pin['metadataSha256'])

    def test_elf_architecture_and_shared_library_kind_are_required(self):
        self.assertEqual(module.inspect_elf(elf(), 'linux-arm64')['machine'], 183)
        self.assertEqual(module.inspect_elf(elf(machine=62), 'linux-x64')['machine'], 62)
        for data in (elf(machine=62), elf(kind=2), bytes(120), elf()[:65]):
            with self.assertRaises(ValueError):
                module.inspect_elf(data, 'linux-arm64')

    def test_elf_program_headers_cannot_escape_file(self):
        for offset, length, count in ((120, 56, 1), (64, 4, 1), (64, 56, 0), (64, 56, 100)):
            data = elf()
            struct.pack_into('<Q', data, 32, offset)
            struct.pack_into('<HH', data, 54, length, count)
            with self.assertRaises(ValueError):
                module.inspect_elf(data, 'linux-arm64')
        data = elf()
        struct.pack_into('<I', data, 64, 2)
        with self.assertRaises(ValueError):
            module.inspect_elf(data, 'linux-arm64')

    def test_existing_output_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'marker').write_text('preserve')
            with patch.object(module.toolchains, 'install') as install:
                with self.assertRaisesRegex(ValueError, 'new directory'):
                    module.build(root, ['linux-arm64'])
                install.assert_not_called()
            self.assertEqual((root / 'marker').read_text(), 'preserve')

    def test_invalid_targets_fail_before_downloading(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(module.toolchains, 'install') as install:
            for targets in ([], ['linux-arm64', 'linux-arm64'], ['android-arm64']):
                with self.assertRaisesRegex(ValueError, 'unique Linux targets'):
                    module.build(Path(directory) / 'new', targets)
            install.assert_not_called()

    def test_inventory_fingerprints_bytes_and_link_destinations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'file').write_text('before')
            (root / 'link').symlink_to('file')
            before = module.tree_inventory(root)
            (root / 'file').write_text('after')
            self.assertNotEqual(module.inventory_digest(before), module.inventory_digest(module.tree_inventory(root)))
            (root / 'link').unlink()
            (root / 'link').symlink_to('../outside')
            with self.assertRaisesRegex(ValueError, 'escapes'):
                module.tree_inventory(root)

    def test_sysroot_install_refuses_untrusted_origin_before_download(self):
        pins = json.loads((module.HERE / 'linux-pins.json').read_text())
        pins['sysroots']['linux-arm64']['url'] = 'https://example.invalid/sysroot'
        with tempfile.TemporaryDirectory() as directory, patch.object(module.urllib.request, 'urlopen') as download:
            with self.assertRaisesRegex(ValueError, 'official content hash'):
                module.install_sysroot('linux-arm64', directory, pins)
            download.assert_not_called()

    def test_changed_cached_sysroot_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(module.urllib.request, 'urlopen') as download:
            cache = Path(directory)
            root = cache / ('linux-arm64-' + 'a' * 64)
            root.mkdir()
            (root / 'file').write_text('original')
            pin = {'url': 'https://commondatastorage.googleapis.com/chrome-linux-sysroot/' + 'a' * 64,
                   'sha256': 'a' * 64, 'size': 1,
                   'filesSha256': module.inventory_digest(module.tree_inventory(root))}
            (root / '.sysroot-receipt.json').write_text(json.dumps({'pin': pin}))
            pins = {'sysroots': {'linux-arm64': pin}}
            self.assertEqual(module.install_sysroot('linux-arm64', cache, pins), root)
            (root / 'file').write_text('changed')
            with self.assertRaisesRegex(ValueError, 'differs'):
                module.install_sysroot('linux-arm64', cache, pins)
            download.assert_not_called()

    def test_sysroot_linker_cannot_fall_back_to_host_unwind_runtime(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            linking = {'compileOptions': [], 'includeDirs': [], 'defines': [], 'libraries': [],
                       'linkOptions': [], 'systemLibraries': ['m']}
            with self.assertRaisesRegex(ValueError, 'unwind runtime missing'):
                module.command(root, 'linux-arm64', linking, root, root, root / 'output')
            unwind = root / 'lib/aarch64-linux-gnu/libgcc_s.so.1'
            unwind.parent.mkdir(parents=True)
            unwind.write_bytes(b'fixture')
            args = module.command(root, 'linux-arm64', linking, root, root, root / 'output')
            self.assertIn(unwind, args)
            self.assertIn('-nodefaultlibs', args)
            self.assertIn('--ld-path=' + str(root / 'bin/ld.lld'), args)
            self.assertNotIn('-lstdc++', args)

    def test_probe_exit_failure_cannot_write_success_receipt(self):
        import subprocess
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(module.platform, 'system', return_value='Linux'), \
                patch.object(module.platform, 'machine', return_value='aarch64'), \
                patch.object(module.subprocess, 'run', return_value=subprocess.CompletedProcess(
                    [], -5, '{"target":"linux-arm64","applicationRuntimeAccepted":false}', 'allocator teardown failed')):
            with self.assertRaisesRegex(ValueError, 'process teardown'):
                module.run_native(directory, 'linux-arm64')
            self.assertFalse((Path(directory) / 'linux-arm64-runtime.json').exists())

    def test_runtime_requires_matching_host_and_authenticated_library(self):
        with patch.object(module.platform, 'system', return_value='Darwin'):
            with self.assertRaisesRegex(ValueError, 'matching Linux'):
                module.run_native('/missing', 'linux-arm64')


if __name__ == '__main__':
    unittest.main()
