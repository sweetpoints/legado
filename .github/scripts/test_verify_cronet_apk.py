import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location('cronet_apk', Path(__file__).with_name('verify-cronet-apk.py'))
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)


class ApkContractTest(unittest.TestCase):
    def verify(self, names):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'release.apk'
            with zipfile.ZipFile(path, 'w') as apk:
                for name in names:
                    apk.writestr(name, b'fixture native binary')
            return verifier.verify_apk(path)

    def valid(self):
        return [f'lib/{abi}/{library}' for abi in verifier.EXPECTED_ABIS
                for library in (*verifier.MANDATORY_LIBRARIES, 'libother.so')]

    def test_complete_mandatory_dual_abi_release_is_accepted(self):
        self.assertGreater(self.verify(self.valid()), 0)

    def test_each_missing_flutter_abi_fails_even_when_union_is_complete(self):
        for library in verifier.MANDATORY_LIBRARIES:
            for abi in verifier.EXPECTED_ABIS:
                with self.subTest(library=library, abi=abi):
                    names = [name for name in self.valid() if name != f'lib/{abi}/{library}']
                    with self.assertRaisesRegex(ValueError, 'incomplete ABI'):
                        self.verify(names)

    def test_missing_or_extra_packaged_abi_is_rejected(self):
        for names in ([name for name in self.valid() if '/x86_64/' not in name],
                      self.valid() + ['lib/armeabi-v7a/libother.so']):
            with self.assertRaisesRegex(ValueError, 'Unexpected native ABI'):
                self.verify(names)

    def test_bundled_cronet_is_rejected_in_lib_or_asset(self):
        for name in ('lib/x86_64/libcronet.1.so', 'assets/libcronet.1.so'):
            with self.assertRaisesRegex(ValueError, 'device ABI'):
                self.verify(self.valid() + [name])
