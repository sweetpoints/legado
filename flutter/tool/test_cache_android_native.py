import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('native_cache', Path(__file__).with_name('cache-android-native.py'))
cache = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cache)


class NativeCacheTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def fixture(self, target, license_bytes=b'official license'):
        root = self.root / target
        binary = root / target / 'libsource_v8.so'
        binary.parent.mkdir(parents=True)
        binary.write_bytes(target.encode())
        notice = root / 'licenses/LICENSE'
        notice.parent.mkdir()
        notice.write_bytes(license_bytes)
        pins = cache.verifier.builder.read_pins()
        release_pin = json.loads((cache.verifier.HERE / 'v8/release-pin.json').read_text())
        manifest = {'schemaVersion': 1, 'v8': pins['v8'], 'depotTools': pins['depotTools'],
                    'bridge': {'abi': 1, 'sourceSha256': cache.verifier.builder.bridge_digest()},
                    'targets': {target: {'binary': f'{target}/libsource_v8.so', 'minApi': 26,
                                        'size': binary.stat().st_size, 'sha256': cache.verifier.builder.sha(binary),
                                        'sdkProvenance': {'target': target, 'manifestSha256': release_pin['sdkManifestSha256'][target],
                                                          'releaseManifestSha256': release_pin['releaseManifestSha256']}}},
                    'licenses': [{'path': 'licenses/LICENSE', 'sha256': cache.verifier.builder.sha(notice)}]}
        (root / 'manifest.json').write_text(json.dumps(manifest))
        return root

    def test_separate_cache_import_preserves_both_targets(self):
        output = self.root / 'combined'
        for target in ['android-x64', 'android-arm64']:
            cache.transfer(self.fixture(target), output, target)
        manifest, targets = cache.verifier.native_provenance(['android-arm64', 'android-x64'], output)
        self.assertEqual(set(targets), {'android-arm64', 'android-x64'})
        self.assertEqual(len(manifest['licenses']), 1)

    def test_corrupt_binary_is_rejected_before_copy(self):
        source = self.fixture('android-x64')
        (source / 'android-x64/libsource_v8.so').write_bytes(b'corrupt')
        with self.assertRaisesRegex(ValueError, 'checksum or size'):
            cache.transfer(source, self.root / 'combined', 'android-x64')
        self.assertFalse((self.root / 'combined').exists())

    def test_cache_without_reviewed_sdk_origin_is_rejected(self):
        source = self.fixture('android-x64')
        manifest_path = source / 'manifest.json'
        data = json.loads(manifest_path.read_text())
        data['targets']['android-x64'].pop('sdkProvenance')
        manifest_path.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, 'pinned released V8 SDK'):
            cache.transfer(source, self.root / 'combined', 'android-x64')
        self.assertFalse((self.root / 'combined').exists())

    def test_license_conflict_does_not_replace_manifest(self):
        output = self.root / 'combined'
        cache.transfer(self.fixture('android-arm64'), output, 'android-arm64')
        original = (output / 'manifest.json').read_bytes()
        with self.assertRaisesRegex(ValueError, 'license checksum conflict'):
            cache.transfer(self.fixture('android-x64', b'changed'), output, 'android-x64')
        self.assertEqual((output / 'manifest.json').read_bytes(), original)
        self.assertFalse((output / 'android-x64').exists())

    def test_incompatible_depot_revision_is_rejected(self):
        output = self.root / 'combined'
        cache.transfer(self.fixture('android-arm64'), output, 'android-arm64')
        manifest_path = output / 'manifest.json'
        old = json.loads(manifest_path.read_text())
        old['depotTools']['revision'] = '0' * 40
        manifest_path.write_text(json.dumps(old))
        with self.assertRaisesRegex(ValueError, 'different source provenance'):
            cache.transfer(self.fixture('android-x64'), output, 'android-x64')
