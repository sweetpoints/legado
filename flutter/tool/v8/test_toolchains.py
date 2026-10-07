import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('clang_download', Path(__file__).with_name('toolchains.py'))
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class ToolchainTests(unittest.TestCase):
    def fixture(self, root):
        (root / 'bin').mkdir()
        for name in ['clang++', 'ld.lld']:
            (root / 'bin' / name).write_text(name)
        item = {'url': 'fixed', 'sha256': 'a' * 64, 'size': 12,
                'filesSha256': module.hashlib.sha256(json.dumps(module.inventory(root), sort_keys=True, separators=(',', ':')).encode()).hexdigest()}
        (root / 'toolchain-receipt.json').write_text(json.dumps({'archive': item, 'files': module.inventory(root)}))
        return item

    def test_cached_compiler_tampering_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d); item = self.fixture(root)
            module.verify(root, item)
            (root / 'bin/clang++').write_text('changed')
            with self.assertRaisesRegex(ValueError, 'differs'):
                module.verify(root, item)

    def test_forged_local_receipt_cannot_authenticate_changed_compiler(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d); item = self.fixture(root)
            (root / 'bin/clang++').write_text('different compiler')
            (root / 'toolchain-receipt.json').write_text(json.dumps({'archive': item, 'files': module.inventory(root)}))
            with self.assertRaisesRegex(ValueError, 'reviewed pin'):
                module.verify(root, item)

    def test_cached_extra_input_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d); item = self.fixture(root)
            (root / 'extra').write_text('unreviewed')
            with self.assertRaises(ValueError): module.verify(root, item)

    def test_compiler_symlink_cannot_leave_cache(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d); item = self.fixture(root)
            (root / 'outside-link').symlink_to('/etc/hosts')
            with self.assertRaises(ValueError): module.verify(root, item)

    def test_unsupported_host_fails_before_download(self):
        with patch.object(module.platform, 'system', return_value='Windows'):
            with self.assertRaisesRegex(ValueError, 'requires'):
                module.host_key()

    def test_official_compiler_pins_are_exact(self):
        pins = json.loads(Path(__file__).with_name('toolchain-pins.json').read_text())
        self.assertEqual(pins['metadata']['v8Revision'], 'c45871fec706a6e7b715e607065bb4578b23ce9f')
        for item in pins['hosts'].values():
            self.assertTrue(item['url'].startswith('https://commondatastorage.googleapis.com/chromium-browser-clang/'))
            self.assertEqual(len(item['sha256']), 64)
            self.assertGreater(item['size'], 0)
