"""Verify AGP-compatible stripping without any V8 source build."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('sdk_strip_linker', HERE / 'link_sdk.py')
linker = importlib.util.module_from_spec(spec); spec.loader.exec_module(linker)
spec = importlib.util.spec_from_file_location('sdk_strip_tools', HERE / 'toolchains.py')
tools = importlib.util.module_from_spec(spec); spec.loader.exec_module(tools)


class StripFailureTests(unittest.TestCase):
    def test_missing_strip_tool_fails_closed(self):
        with tempfile.TemporaryDirectory() as d:
            binary = Path(d) / 'library'; binary.write_bytes(b'original')
            with self.assertRaisesRegex(ValueError, 'llvm-strip'):
                linker.strip_android(binary, Path(d) / 'missing')
            self.assertEqual(binary.read_bytes(), b'original')

    def test_non_idempotent_transform_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d); binary = root / 'library'; binary.write_bytes(b'original')
            strip = root / 'llvm-strip'; strip.write_bytes(b'tool')
            def mutate(args):
                binary.write_bytes(binary.read_bytes() + b'changed'); return ''
            with patch.object(linker, '_run', side_effect=mutate):
                with self.assertRaisesRegex(ValueError, 'idempotent'):
                    linker.strip_android(binary, strip)


class ActualNdkStripPipelineTests(unittest.TestCase):
    def setUp(self):
        try:
            key = tools.host_key()
            pins = json.loads((HERE / 'toolchain-pins.json').read_text())
            self.compiler = tools.PACKAGE / '.cache/toolchains' / (key + '-' + pins['hosts'][key]['sha256']) / 'bin/clang++'
            self.sysroot = tools.sysroot('android-arm64')
        except (OSError, ValueError):
            self.skipTest('Pinned compiler and NDK must already be prepared; tests never download')
        if not self.compiler.is_file():
            self.skipTest('Pinned compiler must already be prepared')

    def test_two_real_abi_libraries_match_agp_output_and_keep_ten_exports(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d); code = root / 'probe.cpp'
            code.write_text('\n'.join('extern "C" __attribute__((visibility("default"))) int ' + name + '() { return 42; }' for name in sorted(linker.EXPORTS)))
            strip = self.sysroot.parent / 'bin/llvm-strip'
            nm = self.sysroot.parent / 'bin/llvm-nm'
            agp_strip = (self.sysroot.parents[4].parent / '28.2.13676358/toolchains/llvm/prebuilt' /
                         self.sysroot.parent.name / 'bin/llvm-strip')
            if not agp_strip.is_file():
                self.skipTest('Flutter 3.47 default NDK 28.2 AGP strip tool not installed')
            for target in ('android-arm64', 'android-x64'):
                with self.subTest(target=target):
                    binary = root / (target + '.so')
                    subprocess.run([str(self.compiler), '--target=' + linker.PROFILES[target][0],
                                    '--sysroot=' + str(self.sysroot), '-g', '-fPIC', '-shared', '-nostdlib',
                                    '-Wl,-z,max-page-size=16384', str(code), '-o', str(binary)], check=True, capture_output=True)
                    unstripped_size = binary.stat().st_size
                    evidence = linker.strip_android(binary, strip)
                    self.assertTrue(evidence['idempotent'])
                    self.assertLess(binary.stat().st_size, unstripped_size)
                    self.assertEqual(set(linker.inspect_exports(binary, target, nm)), linker.EXPORTS)
                    linker.inspect_binary(binary, target)
                    packaged = root / (target + '-agp.so')
                    # Exact invocation from official AGP StripDebugSymbolsTask.kt.
                    subprocess.run([str(agp_strip), '--strip-unneeded', '-o', str(packaged), str(binary)], check=True, capture_output=True)
                    self.assertEqual(linker.consumer.sha(binary), linker.consumer.sha(packaged))
