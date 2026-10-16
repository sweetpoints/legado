import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('ios_bridge_sdk', Path(__file__).with_name('ios_sdk.py'))
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def macho(platform=7, minimum=15 << 16, weak=True):
    version = struct.pack('<IIIIII', 0x32, 24, platform, minimum, 27 << 16, 0)
    name = b'/System/Library/Frameworks/BrowserEngineKit.framework/BrowserEngineKit\x00'
    size = (24 + len(name) + 7) // 8 * 8
    dylib = struct.pack('<IIIIII', 0x80000018 if weak else 0xc, size, 24, 0, 0, 0) + name
    dylib += b'\x00' * (size - len(dylib))
    header = struct.pack('<IIIIIIII', 0xfeedfacf, 0x100000c, 0, 2, 2, len(version + dylib), 0, 0)
    return header + version + dylib


class IosSdkTests(unittest.TestCase):
    def test_package_pins_are_separate_from_unfinished_flutter_hook_targets(self):
        pins = json.loads((module.HERE / 'ios-pins.json').read_text())
        release = json.loads((module.HERE / 'ios-release-pin.json').read_text())
        module.prebuilt.validate_pin(release, pins)
        self.assertEqual(set(pins['targets']), set(module.PROFILES))
        self.assertEqual(set(release['sdkManifestSha256']), set(module.PROFILES))
        existing = json.loads((module.HERE / 'pins.json').read_text())
        self.assertFalse(set(existing['targets']) & set(module.PROFILES))
        self.assertEqual(existing['v8'], pins['v8'])
        self.assertEqual(existing['depotTools'], pins['depotTools'])

    def test_actual_platform_kind_and_weak_dependency_are_enforced(self):
        self.assertEqual(module.inspect_macho(macho(), 'ios-simulator-arm64')['platform'], 7)
        self.assertEqual(module.inspect_macho(macho(platform=2), 'ios-arm64')['platform'], 2)
        for data in (macho(platform=2), macho(minimum=17 << 16), macho(weak=False),
                     macho()[:35], bytes(64)):
            with self.assertRaises(ValueError):
                module.inspect_macho(data, 'ios-simulator-arm64')

    def test_malformed_load_command_cannot_escape_binary_bounds(self):
        data = bytearray(macho())
        struct.pack_into('<I', data, 36, 0xffffffff)
        with self.assertRaises(ValueError):
            module.inspect_macho(data, 'ios-simulator-arm64')
        data = bytearray(macho())
        struct.pack_into('<I', data, 64, 0xffffffff)
        with self.assertRaises(ValueError):
            module.inspect_macho(data, 'ios-simulator-arm64')

    def test_existing_output_is_not_overwritten_or_used_as_a_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / 'marker').write_text('preserve')
            with patch.object(module.toolchains, 'install') as install:
                with self.assertRaisesRegex(ValueError, 'new directory'):
                    module.build(path)
                install.assert_not_called()
            self.assertEqual((path / 'marker').read_text(), 'preserve')

    def test_simulator_requires_explicit_uuid_and_authenticated_probe_bytes(self):
        with patch.object(module, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'UUID'):
                module.run_simulator(Path('/missing'), 'booted')
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                target = 'ios-simulator-arm64'
                (root / target).mkdir()
                (root / target / 'source_v8_probe').write_bytes(b'changed')
                (root / 'provenance.json').write_text(json.dumps({
                    'targets': {target: {'probeSha256': '0' * 64}}}))
                with self.assertRaisesRegex(ValueError, 'checksum'):
                    module.run_simulator(root, 'a' * 8 + '-' + 'a' * 27)
            run.assert_not_called()

    def test_ios_macro_does_not_change_other_platform_initialization(self):
        text = (module.PACKAGE / 'src/source_v8.cpp').read_text()
        self.assertIn('#if defined(SV8_IOS_JITLESS)', text)
        self.assertIn('V8::SetFlagsFromString("--jitless");\n#endif', text)
        self.assertEqual(text.count('V8::SetFlagsFromString("--jitless")'), 1)
