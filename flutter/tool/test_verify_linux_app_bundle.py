import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('verify_linux_app_bundle', Path(__file__).with_name('verify-linux-app-bundle.py'))
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def elf(machine=62, kind=3):
    header = bytearray(64)
    header[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<HH', header, 16, kind, machine)
    return header


class LinuxAppBundleTests(unittest.TestCase):
    def test_requires_real_target_elf_and_release_aot(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'legado_app').write_bytes(elf(machine=183))
            with patch.object(module.linux_sdk, 'prepare_dynamic') as prepare:
                with self.assertRaisesRegex(ValueError, 'architecture/type'):
                    module.verify(root, root / 'receipt.json')
                prepare.assert_not_called()
            (root / 'legado_app').write_bytes(elf())
            (root / 'legado_app').chmod(0o755)
            with patch.object(module.linux_sdk, 'prepare_dynamic') as prepare:
                with self.assertRaises(FileNotFoundError):
                    module.verify(root, root / 'receipt.json')
                prepare.assert_not_called()

    def test_refuses_external_bundle_symlinks_before_sdk_work(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle = root / 'bundle'
            bundle.mkdir()
            (root / 'outside').write_bytes(b'private')
            (bundle / 'link').symlink_to(root / 'outside')
            with patch.object(module.linux_sdk, 'prepare_dynamic') as prepare:
                with self.assertRaisesRegex(ValueError, 'outside'):
                    module.verify(bundle, root / 'receipt.json')
                prepare.assert_not_called()

    def test_bundled_native_asset_must_equal_authenticated_hook_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle = root / 'bundle'
            (bundle / 'lib').mkdir(parents=True)
            (bundle / 'legado_app').write_bytes(elf())
            (bundle / 'legado_app').chmod(0o755)
            (bundle / 'lib/libapp.so').write_bytes(elf())
            (bundle / 'lib/libsource_v8.so').write_bytes(elf())
            artifact = root / 'artifact'
            artifact.mkdir()
            (artifact / 'manifest.json').write_text(json.dumps({'targets': {'linux-x64': {
                'sha256': '0' * 64, 'size': 64}}}))
            with patch.object(module.linux_sdk, 'prepare_dynamic', return_value=artifact):
                with self.assertRaisesRegex(ValueError, 'differs from the authenticated'):
                    module.verify(bundle, root / 'receipt.json')
            self.assertFalse((root / 'receipt.json').exists())

    def test_success_copies_verified_notices_without_claiming_gui_acceptance(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle = root / 'bundle'
            (bundle / 'lib').mkdir(parents=True)
            for relative in ('legado_app', 'lib/libapp.so', 'lib/libsource_v8.so'):
                (bundle / relative).write_bytes(elf())
            (bundle / 'legado_app').chmod(0o755)
            artifact = root / 'artifact'
            artifact.mkdir()
            entry = {'sha256': module.prebuilt.sha(bundle / 'lib/libsource_v8.so'), 'size': 64,
                     'sdkProvenance': {'target': 'linux-x64'}, 'linuxConsumerInputsSha256': {},
                     'linuxUndefinedSymbolsSha256': 'a' * 64}
            (artifact / 'manifest.json').write_text(json.dumps({'targets': {'linux-x64': entry}}))
            sdk = root / 'sdk'
            (sdk / 'licenses').mkdir(parents=True)
            (sdk / 'licenses/LICENSE').write_text('V8 test license')
            (sdk / 'manifest.json').write_text(json.dumps({'licenses': [{
                'path': 'licenses/LICENSE', 'sha256': module.prebuilt.sha(sdk / 'licenses/LICENSE')}]}))
            with patch.object(module.linux_sdk, 'prepare_dynamic', return_value=artifact), \
                    patch.object(module.prebuilt, 'install', return_value=sdk):
                receipt = module.verify(bundle, root / 'receipt.json')
            self.assertEqual((bundle / 'THIRD_PARTY_NOTICES/v8/LICENSE').read_text(), 'V8 test license')
            self.assertEqual(receipt['buildMode'], 'release-aot')
            self.assertIs(receipt['v8BuiltLocally'], False)
            self.assertIs(receipt['guiRuntimeAccepted'], False)
            self.assertEqual(len(receipt['files']), 4)


if __name__ == '__main__':
    unittest.main()
