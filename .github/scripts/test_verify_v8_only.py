import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('verify_v8_only', Path(__file__).with_name('verify-v8-only.py'))
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


def dex(descriptors):
    count = len(descriptors)
    strings_offset = 112
    types_offset = strings_offset + count * 4
    data = bytearray(types_offset + count * 4)
    data[:8] = b'dex\n035\0'
    struct.pack_into('<II', data, 56, count, strings_offset)
    struct.pack_into('<II', data, 64, count, types_offset)
    for index, descriptor in enumerate(descriptors):
        struct.pack_into('<I', data, strings_offset + index * 4, len(data))
        struct.pack_into('<I', data, types_offset + index * 4, index)
        data.extend(bytes([len(descriptor)]) + descriptor + b'\0')
    return bytes(data)


class V8OnlyApkTests(unittest.TestCase):
    def apk(self, directory, descriptors):
        path = Path(directory) / 'app.apk'
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('classes.dex', dex(descriptors))
        return path

    def test_v8_app_and_unrelated_mozilla_charset_library_are_allowed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.apk(directory, [b'Lio/legado/app/model/sourceEngine/V8ScriptExecutor;', b'Lorg/mozilla/universalchardet/UniversalDetector;'])
            self.assertEqual(1, check.verify(path))

    def test_legacy_engine_type_references_and_arrays_are_rejected(self):
        for descriptor in [b'Lorg/htmlunit/corejs/javascript/Context;', b'Lorg/mozilla/javascript/Context;', b'Lcom/script/ScriptBindings;', b'[[Lcom/script/rhino/RhinoContext;']:
            with self.subTest(descriptor=descriptor), tempfile.TemporaryDirectory() as directory:
                with self.assertRaisesRegex(ValueError, 'Removed engine type'):
                    check.verify(self.apk(directory, [descriptor]))

    def test_missing_or_malformed_dex_cannot_claim_success(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'app.apk'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('classes.dex', b'not a DEX')
            with self.assertRaisesRegex(ValueError, 'DEX'):
                check.verify(path)
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('AndroidManifest.xml', b'fixture')
            with self.assertRaisesRegex(ValueError, 'no DEX'):
                check.verify(path)

    def test_invalid_type_index_is_rejected(self):
        data = bytearray(dex([b'Ljava/lang/Object;']))
        struct.pack_into('<I', data, 116, 8)
        with self.assertRaisesRegex(ValueError, 'invalid string'):
            list(check.dex_types(data))


if __name__ == '__main__':
    unittest.main()
