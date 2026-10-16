import json
from pathlib import Path
import tempfile
import unittest

import link_sdk
import prebuilt
from test_windows_sdk import pe_fixture
import windows_ci


class ReviewedConsumerTests(unittest.TestCase):
    def test_review_is_hash_bound_to_independent_ci_inventory_and_not_runtime_approval(self):
        reviewed, path = windows_ci.review()
        self.assertEqual(prebuilt.sha(path), '36c9e96bc7121600be930cb7700599a9864a4e6d5a5a4e5288357cee0c8d3fb5')
        self.assertEqual(reviewed['sourceRun'], 38945901818)
        self.assertTrue(reviewed['review']['sourceCommitParentVerified'])
        self.assertFalse(reviewed['review']['nativeAbiCompatible'])
        self.assertFalse(reviewed['review']['runtimeAccepted'])

    def fixture(self, directory):
        output = Path(directory)
        dll = output / 'source_v8.dll'
        dll.write_bytes(pe_fixture())
        reviewed, _ = windows_ci.review()
        release = json.loads((windows_ci.HERE / 'windows-release-pin.json').read_text())
        target = {'binary': dll.name, 'sha256': prebuilt.sha(dll), 'size': dll.stat().st_size,
                  'windowsConsumerInputsSha256': {name: prebuilt.sha(windows_ci.HERE / name) for name in windows_ci.INPUTS},
                  'sdkProvenance': {'target': 'windows-x64', 'releaseManifestSha256': release['releaseManifestSha256'],
                                    'manifestSha256': release['sdkManifestSha256']['windows-x64']}}
        manifest = {'bridge': {'abi': 1, 'sourceSha256': link_sdk.source_digest()},
                    'target': 'windows-x64', 'toolchainLockSha256': reviewed['lockSha256'],
                    'v8': release['v8'], 'depotTools': release['depotTools'],
                    'binary': {'name': dll.name, 'sha256': prebuilt.sha(dll), 'size': dll.stat().st_size},
                    'targets': {'windows-x64': target}}
        (output / 'manifest.json').write_text(json.dumps(manifest))
        return output, manifest

    def test_cached_dll_requires_current_consumer_source_sdk_and_real_pe_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            output, manifest = self.fixture(directory)
            self.assertEqual(windows_ci.verify_existing(output), output)
            manifest['targets']['windows-x64']['windowsConsumerInputsSha256']['windows_sdk.py'] = '0' * 64
            (output / 'manifest.json').write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, 'consumer input changed'):
                windows_ci.verify_existing(output)
        with tempfile.TemporaryDirectory() as directory:
            output, manifest = self.fixture(directory)
            manifest['v8'] = dict(manifest['v8'], version='0.0.0')
            (output / 'manifest.json').write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, 'source versions differ'):
                windows_ci.verify_existing(output)

    def test_cache_cannot_relabel_unreviewed_lock_or_modified_dll(self):
        with tempfile.TemporaryDirectory() as directory:
            output, manifest = self.fixture(directory)
            manifest['toolchainLockSha256'] = '0' * 64
            (output / 'manifest.json').write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, 'provenance differs'):
                windows_ci.verify_existing(output)
        with tempfile.TemporaryDirectory() as directory:
            output, _ = self.fixture(directory)
            (output / 'source_v8.dll').write_bytes(b'wrong')
            with self.assertRaisesRegex(ValueError, 'DLL checksum differs'):
                windows_ci.verify_existing(output)


if __name__ == '__main__':
    unittest.main()
