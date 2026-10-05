import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('aar_verifier', Path(__file__).with_name('verify-android-aar.py'))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class AarContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.library = b'fixed official binary'
        self.digest = hashlib.sha256(self.library).hexdigest()

    def fixture(self, *, library=None, omit=None, other_abi=False, x64=False, mode="debug"):
        aar = self.repo / f'io/legado/source/source_host/flutter_{mode}/1.0/flutter_{mode}-1.0.aar'
        aar.parent.mkdir(parents=True, exist_ok=True)
        aar.with_suffix('.pom').write_text('<project/>')
        files = {'jni/arm64-v8a/libsource_v8.so': self.library if library is None else library,
                 'assets/flutter_assets/NativeAssetsManifest.json': b'{}',
                 'assets/flutter_assets/AssetManifest.bin': b'assets',
                 'assets/flutter_assets/kernel_blob.bin': b'kernel'}
        if x64:
            files['jni/x86_64/libsource_v8.so'] = self.library
        if mode == 'release':
            files.pop('assets/flutter_assets/kernel_blob.bin')
            files['jni/arm64-v8a/libapp.so'] = b'arm aot'
            if x64:
                files['jni/x86_64/libapp.so'] = b'x64 aot'
        if omit:
            files.pop(omit)
        if other_abi:
            files['jni/x86_64/libsource_v8.so'] = b'unsupported'
        with zipfile.ZipFile(aar, 'w') as output:
            for name, data in files.items():
                output.writestr(name, data)
        return aar

    def test_valid_arm64_aar_has_reproducible_hash(self):
        aar = self.fixture()
        result = verifier.check_aar(self.repo, 'debug', self.digest)
        self.assertEqual(result['sha256'], hashlib.sha256(aar.read_bytes()).hexdigest())
        self.assertEqual(result['nativeSha256ByAbi']['arm64-v8a'], self.digest)

    def test_stale_native_binary_rejected(self):
        self.fixture(library=b'stale native')
        with self.assertRaisesRegex(ValueError, 'stale or unverified'):
            verifier.check_aar(self.repo, 'debug', self.digest)

    def test_missing_dart_assets_rejected(self):
        self.fixture(omit='assets/flutter_assets/kernel_blob.bin')
        with self.assertRaisesRegex(ValueError, 'assets/AOT'):
            verifier.check_aar(self.repo, 'debug', self.digest)

    def test_unsupported_abi_rejected(self):
        self.fixture(other_abi=True)
        with self.assertRaisesRegex(ValueError, 'requested Android ABIs'):
            verifier.check_aar(self.repo, 'debug', self.digest)

    def test_missing_maven_metadata_rejected(self):
        aar = self.fixture()
        aar.with_suffix('.pom').unlink()
        with self.assertRaisesRegex(ValueError, 'Maven POM'):
            verifier.check_aar(self.repo, 'debug', self.digest)

    def test_fat_aar_validates_both_native_binaries(self):
        self.fixture(x64=True)
        result = verifier.check_aar(self.repo, 'debug', {'arm64-v8a': self.digest, 'x86_64': self.digest})
        self.assertEqual(result['abis'], ['arm64-v8a', 'x86_64'])

    def test_missing_x64_native_rejected_for_default_fat_build(self):
        self.fixture()
        with self.assertRaisesRegex(ValueError, 'missing required x86_64'):
            verifier.check_aar(self.repo, 'debug', {'arm64-v8a': self.digest, 'x86_64': self.digest})

    def test_wrong_x64_digest_rejected(self):
        self.fixture(x64=True)
        with self.assertRaisesRegex(ValueError, 'stale or unverified'):
            verifier.check_aar(self.repo, 'debug', {'arm64-v8a': self.digest, 'x86_64': 'wrong'})

    def test_release_requires_aot_for_every_requested_abi(self):
        self.fixture(x64=True, mode='release', omit='jni/x86_64/libapp.so')
        with self.assertRaisesRegex(ValueError, 'assets/AOT'):
            verifier.check_aar(self.repo, 'release', {'arm64-v8a': self.digest, 'x86_64': self.digest})

    def test_release_dual_abi_aot_is_valid(self):
        self.fixture(x64=True, mode='release')
        result = verifier.check_aar(self.repo, 'release', {'arm64-v8a': self.digest, 'x86_64': self.digest})
        self.assertEqual(result['abis'], ['arm64-v8a', 'x86_64'])

    def test_invalid_target_selection_rejected(self):
        for value in ['android-x86', 'android-arm64,', 'android-x64,android-x64']:
            with self.assertRaises(ValueError):
                verifier.configured_targets(value)

    def test_stamp_requires_precompile_source_digest(self):
        with patch('sys.argv', ['verify', '--write-stamp', 'debug']):
            with self.assertRaises(SystemExit) as error:
                verifier.main()
        self.assertEqual(error.exception.code, 2)

    def test_source_change_before_validation_rejects_stamp(self):
        with patch('sys.argv', ['verify', '--write-stamp', '--expected-source-sha', 'before', 'debug']), \
                patch.object(verifier, 'source_digest', return_value='after'), \
                patch.object(verifier, 'native_provenance') as native:
            with self.assertRaisesRegex(ValueError, 'during AAR compilation'):
                verifier.main()
            native.assert_not_called()

    def test_source_change_during_validation_rejects_stamp(self):
        self.fixture()
        manifest = {'v8': {}, 'depotTools': {}, 'bridge': {}}
        with patch('sys.argv', ['verify', '--write-stamp', '--expected-source-sha', 'before', 'debug']), \
                patch.object(verifier, 'source_digest', side_effect=['before', 'before', 'after']), \
                patch.object(verifier, 'native_provenance', return_value=(manifest, {'android-arm64': {'sha256': self.digest}})), \
                patch.object(verifier, 'REPO', self.repo):
            with self.assertRaisesRegex(ValueError, 'before AAR publication'):
                verifier.main()
        self.assertFalse((self.repo / 'source-engine-artifacts.json').exists())
        self.assertFalse((self.repo / 'source-engine-artifacts.json.publishing').exists())


if __name__ == '__main__':
    unittest.main()
