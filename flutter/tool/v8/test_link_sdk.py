"""SDK bridge-link tests, including a real native compiler/archive/link pipeline.

The native fixture is a tiny artificial SDK, not a V8 runtime acceptance test.
"""
import ctypes
import hashlib
import importlib.util
import json
from pathlib import Path
import platform
import shutil
import struct
import subprocess
import tempfile
import io
import tarfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('v8_sdk_linker', Path(__file__).with_name('link_sdk.py'))
linker = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(linker)


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data if isinstance(data, bytes) else data.encode())
    return path


def elf(machine, alignment=16384):
    data = bytearray(120)
    data[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<HH', data, 16, 3, machine)
    struct.pack_into('<Q', data, 32, 64)
    struct.pack_into('<HH', data, 54, 56, 1)
    struct.pack_into('<I', data, 64, 1)
    struct.pack_into('<Q', data, 112, alignment)
    return data


def tiny_sdk(root, target, compiler, sysroot):
    directory = root / target
    triple = linker.PROFILES[target][0]
    compiler_version = subprocess.check_output([compiler, '--version'], text=True).strip()
    write(directory / 'include/v8.h', '#define SDK_HEADER_VALUE 7\nextern "C" int sdk_value();\n')
    write(directory / 'runtime/include/runtime.h', '#define SDK_RUNTIME_HEADER 35\n')
    for name, code in [('monolith', 'extern "C" int runtime_value(); extern "C" int sdk_value(){ return runtime_value(); }'),
                       ('runtime', 'extern "C" int runtime_value(){return 35;}')]:
        source = write(root / (name + '.cpp'), code)
        obj = root / (name + '.o')
        subprocess.run([compiler, '--target=' + triple, '--sysroot=' + str(sysroot), '-c', '-fPIC', source, '-o', obj], check=True, capture_output=True)
        library = directory / 'lib' / ('lib' + name + '.a'); library.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(['/usr/bin/ar', 'rcs', library, obj], check=True, capture_output=True)
        source.unlink(); obj.unlink()
    contract = {'schemaVersion': 1, 'includeDirs': ['include', 'runtime/include'],
                'defines': ['SDK_REQUIRED=7'], 'compileOptions': ['-std=c++20', '-fPIC', '-fno-rtti', '-fno-exceptions', '-nostdinc++', '--target=' + triple],
                'libraries': ['lib/libmonolith.a', 'lib/libruntime.a'], 'linkOptions': ['-nostdlib++', '--target=' + triple, '-framework', 'Foundation', '-framework', 'CoreFoundation'], 'systemLibraries': []}
    write(directory / 'linking.json', json.dumps(contract))
    write(directory / 'args.gn', 'v8_monolithic = true\n')
    write(directory / 'defines.json', json.dumps(contract['defines']))
    write(directory / 'dependencies.txt', 'fixed official dependency revisions\n')
    write(root / 'licenses/LICENSE', 'fixture license\n'); write(root / 'licenses/AUTHORS', 'fixture authors\n')
    pins = {'schemaVersion': 1,
            'v8': {'repository': 'https://chromium.googlesource.com/v8/v8.git', 'revision': 'a' * 40, 'version': '15.4.80.24'},
            'depotTools': {'repository': 'https://chromium.googlesource.com/chromium/tools/depot_tools.git', 'revision': 'b' * 40},
            'targets': {target: {'cpu': 'arm64' if target.endswith('arm64') else 'x64', 'minMacOS': '13.0'}}}
    write(root / 'pins.json', json.dumps(pins))
    binary = directory / 'lib/libmonolith.a'
    entry = {'artifactKind': 'v8-static-sdk', 'binary': binary.relative_to(root).as_posix(),
             'sha256': linker.consumer.sha(binary), 'size': binary.stat().st_size,
             'targetConfig': pins['targets'][target], 'toolchain': {'clang': compiler_version},
             'gnArgs': (directory / 'args.gn').read_text(), 'validation': {'built': True},
             'files': [{'path': p.relative_to(root).as_posix(), 'sha256': linker.consumer.sha(p), 'size': p.stat().st_size} for p in sorted(directory.rglob('*')) if p.is_file()]}
    entry['targetFiles'] = entry['files']
    for basename, key in [('args.gn', 'gnArgsSha256'), ('dependencies.txt', 'dependencyInventorySha256'), ('defines.json', 'definesSha256')]:
        entry[key] = linker.consumer.sha(directory / basename)
    manifest = {'schemaVersion': 1, 'v8': pins['v8'], 'depotTools': pins['depotTools'], 'targets': {target: entry},
                'licenses': [{'path': p.relative_to(root).as_posix(), 'sha256': linker.consumer.sha(p)} for p in sorted((root / 'licenses').iterdir())]}
    write(root / 'manifest.json', json.dumps(manifest))
    return pins, contract


def tiny_bridge(root):
    header = write(root / 'source_v8.h', 'extern "C" int sv8_poll();\n')
    source = '#include <v8.h>\n#include <runtime.h>\n#include "source_v8.h"\n'
    source += '#if SDK_REQUIRED != SDK_HEADER_VALUE\n#error missing SDK define\n#endif\n'
    for name in sorted(linker.EXPORTS):
        source += 'extern "C" __attribute__((visibility("default"))) int ' + name + '(){return sdk_value()+SDK_REQUIRED;}\n'
    cpp = write(root / 'source_v8.cpp', source)
    exports = write(root / 'android_exports.map', '{ global: sv8_*; local: *; };\n')
    gn = write(root / 'source_v8.gni', 'fixture unchanged bridge source labels\n')
    return {'src/source_v8.cpp': cpp, 'src/source_v8.h': header, 'src/android_exports.map': exports, 'tool/v8/source_v8.gni': gn}


class LinkContractTests(unittest.TestCase):
    def test_android_flags_and_profiles_enforce_symbol_map_no_undefined_and_16k(self):
        files = {k: Path('/fixture') / Path(k).name for k in linker.source_files()}
        contract = {'includeDirs': ['include', 'runtime/include'], 'defines': ['V8_COMPRESS_POINTERS'],
                    'compileOptions': ['-nostdinc++', '-fexperimental-relative-c++-abi-vtables'], 'libraries': ['lib/monolith.a', 'lib/runtime.a'],
                    'linkOptions': ['-nostdlib++'], 'systemLibraries': ['dl', 'log', 'm']}
        for target, triple in [('android-arm64', 'aarch64-linux-android26'), ('android-x64', 'x86_64-linux-android26')]:
            args = linker.command(Path('/sdk'), target, contract, Path('/clang++'), Path('/ndk/sysroot'), Path('/out/libsource_v8.so'), files)
            self.assertIn('--target=' + triple, args)
            self.assertIn('--sysroot=/ndk/sysroot', args)
            self.assertIn('-nostdinc++', args)
            self.assertIn('-fexperimental-relative-c++-abi-vtables', args)
            self.assertIn('-nostdlib++', args)
            self.assertIn('-Wl,--no-undefined', args)
            self.assertIn('-Wl,--version-script=/fixture/android_exports.map', args)
            self.assertIn('-Wl,--exclude-libs,ALL', args)
            self.assertIn('-Wl,-z,max-page-size=16384', args)
            self.assertLess(args.index('/sdk/' + target + '/lib/monolith.a'), args.index('/sdk/' + target + '/lib/runtime.a'))

    def test_android_sdk_contract_supports_exact_unwind_and_archive_rescan(self):
        fixture_spec = importlib.util.spec_from_file_location('sdk_download_fixture', Path(__file__).with_name('test_prebuilt.py'))
        fixture_module = importlib.util.module_from_spec(fixture_spec)
        fixture_spec.loader.exec_module(fixture_module)
        fixture = fixture_module.Fixture()
        target = 'android-arm64'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = fixture.payloads[fixture.pin['assets'][0]['name']]
            with tarfile.open(fileobj=io.BytesIO(archive), mode='r:gz') as stream:
                for member in stream:
                    write(root / member.name, stream.extractfile(member).read())
            path = root / target / 'linking.json'
            contract = {'schemaVersion': 1, 'includeDirs': ['include', 'stdlib/include'], 'defines': [],
                        'compileOptions': ['-std=c++20'], 'libraries': ['libv8_monolith.a', 'stdlib/libc++.a'],
                        'linkOptions': ['-nostdlib++', '--unwindlib=none'], 'systemLibraries': ['dl', 'log', 'm'],
                        'staticLibraryGrouping': 'rescan'}
            manifest_path = root / 'manifest.json'
            manifest = json.loads(manifest_path.read_text())
            entry = manifest['targets'][target]
            def update_contract():
                path.write_text(json.dumps(contract))
                for inventory in (entry['files'], entry['targetFiles']):
                    for item in inventory:
                        if item['path'].endswith('/linking.json'):
                            item.update(sha256=linker.consumer.sha(path), size=path.stat().st_size)
                manifest_path.write_text(json.dumps(manifest))
            update_contract()
            _, validated = linker.verified_sdk(root, target, fixture.local)
            args = linker.command(root, target, validated, Path('/clang++'), Path('/sysroot'), Path('/bridge.so'), linker.source_files())
            self.assertEqual(args.count('--unwindlib=none'), 1)
            self.assertEqual(args.count('-Wl,--start-group'), 1)
            self.assertEqual(args.count('-Wl,--end-group'), 1)
            start, end = args.index('-Wl,--start-group'), args.index('-Wl,--end-group')
            self.assertEqual(args[start + 1:end], [str(root / target / library) for library in contract['libraries']])
            self.assertLess(end, args.index('--unwindlib=none'))
            self.assertLess(end, args.index('-ldl'))
            for unsupported in (None, 'none', 'wholeArchive', True, [], {}):
                contract['staticLibraryGrouping'] = unsupported
                update_contract()
                with self.assertRaisesRegex(ValueError, 'Android rescan only'):
                    linker.verified_sdk(root, target, fixture.local)
            contract['staticLibraryGrouping'] = 'rescan'
            for unsupported in ('--unwindlib=libunwind', '--unwindlib=/untrusted/archive'):
                contract['linkOptions'][-1] = unsupported
                update_contract()
                with self.assertRaisesRegex(ValueError, 'outside the supported'):
                    linker.verified_sdk(root, target, fixture.local)

    def test_macos_rejects_android_archive_group_and_missing_marker_stays_ungrouped(self):
        contract = {'includeDirs': [], 'defines': [], 'compileOptions': [],
                    'libraries': ['lib/monolith.a', 'lib/runtime.a'], 'linkOptions': [], 'systemLibraries': []}
        args = linker.command(Path('/sdk'), 'macos-arm64', contract, Path('/clang++'), Path('/sysroot'), Path('/bridge.dylib'), linker.source_files())
        self.assertNotIn('-Wl,--start-group', args)
        self.assertNotIn('-Wl,--end-group', args)
        contract['staticLibraryGrouping'] = 'rescan'
        with self.assertRaisesRegex(ValueError, 'Android rescan only'):
            linker.command(Path('/sdk'), 'macos-arm64', contract, Path('/clang++'), Path('/sysroot'), Path('/bridge.dylib'), linker.source_files())

    def test_android_inspects_actual_elf_machine_and_alignment(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'bridge.so'
            for target, machine in [('android-arm64', 183), ('android-x64', 62)]:
                path.write_bytes(elf(machine))
                self.assertEqual(linker.inspect_binary(path, target)['elfMachine'], machine)
                path.write_bytes(elf(machine, 4096))
                with self.assertRaisesRegex(ValueError, '16 KiB'): linker.inspect_binary(path, target)
            path.write_bytes(elf(62))
            with self.assertRaises(ValueError): linker.inspect_binary(path, 'android-arm64')

    def test_unexpected_or_missing_bridge_exports_fail_closed(self):
        exports = '\n'.join(name + ' T 0 1' for name in linker.EXPORTS)
        with patch.object(linker, '_run', return_value=exports):
            self.assertEqual(set(linker.inspect_exports(Path('/binary'), 'android-arm64', Path('/nm'))), linker.EXPORTS)
        for bad in (exports + '\nunexpected T 0 1', exports.replace('sv8_version', 'bad_version')):
            with patch.object(linker, '_run', return_value=bad):
                with self.assertRaises(ValueError): linker.inspect_exports(Path('/binary'), 'android-arm64', Path('/nm'))


@unittest.skipUnless(platform.system() == 'Darwin' and shutil.which('xcrun'), 'actual macOS compiler/SDK required')
class ActualMacSdkLinkTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.root = Path(self.temp.name)
        self.target = 'macos-arm64' if platform.machine() == 'arm64' else 'macos-x64'
        self.compiler = Path(subprocess.check_output(['xcrun', '-f', 'clang++'], text=True).strip())
        self.nm = Path(subprocess.check_output(['xcrun', '-f', 'nm'], text=True).strip())
        self.sysroot = Path(subprocess.check_output(['xcrun', '--sdk', 'macosx', '--show-sdk-path'], text=True).strip())
        self.sdk = self.root / 'sdk'; self.sdk.mkdir()
        self.pins, self.contract = tiny_sdk(self.sdk, self.target, str(self.compiler), self.sysroot)
        self.files = tiny_bridge(self.root / 'bridge')
        self.cache = self.root / 'self-built'

    def tearDown(self):
        self.temp.cleanup()

    def build(self):
        return linker.link(self.sdk, self.target, compiler=self.compiler, sysroot=self.sysroot,
                           nm=self.nm, pins=self.pins, cache_root=self.cache, files=self.files)

    def test_actual_headers_defines_static_library_order_link_and_dylib_abi(self):
        original = {label: path.read_bytes() for label, path in self.files.items()}
        output = self.build()
        manifest = json.loads((output / 'manifest.json').read_text())
        entry = manifest['targets'][self.target]
        library = ctypes.CDLL(str(output / entry['binary']))
        self.assertEqual(library.sv8_poll(), 42)
        self.assertEqual(set(entry['binaryInspection']['exports']), linker.EXPORTS)
        self.assertFalse(entry['validation']['runtimeTested'])
        self.assertEqual(entry['sdkProvenance']['manifestSha256'], linker.consumer.sha(self.sdk / 'manifest.json'))
        self.assertEqual(manifest['bridge']['sourceSha256'], linker.source_digest(self.files))
        self.assertEqual(original, {label: path.read_bytes() for label, path in self.files.items()})

    def test_compile_error_does_not_replace_existing_binary_or_manifest(self):
        output = self.build()
        old_manifest = (output / 'manifest.json').read_bytes()
        binary = output / self.target / 'libsource_v8.dylib'; old_binary = binary.read_bytes()
        self.files['src/source_v8.cpp'].write_text('#error intentional compiler failure\n')
        with self.assertRaises(subprocess.CalledProcessError): self.build()
        self.assertEqual((output / 'manifest.json').read_bytes(), old_manifest)
        self.assertEqual(binary.read_bytes(), old_binary)

    def test_publication_failure_rolls_back_existing_cache(self):
        output = self.build()
        manifest_before = (output / 'manifest.json').read_bytes()
        binary = output / self.target / 'libsource_v8.dylib'
        binary_before = binary.read_bytes()
        replace = linker.os.replace
        def fail_publish(source, target):
            if Path(source).name == 'staged':
                raise OSError('intentional publication failure')
            return replace(source, target)
        with patch.object(linker.os, 'replace', side_effect=fail_publish):
            with self.assertRaisesRegex(OSError, 'publication failure'): self.build()
        self.assertEqual((output / 'manifest.json').read_bytes(), manifest_before)
        self.assertEqual(binary.read_bytes(), binary_before)

    def test_sdk_corruption_fails_before_compile(self):
        write(self.sdk / self.target / 'include/v8.h', 'corrupt')
        with patch.object(linker, '_run') as execute:
            with self.assertRaises(ValueError): self.build()
            execute.assert_not_called()

    def test_verified_metadata_cannot_add_arbitrary_compiler_file_or_plugin_flags(self):
        path = self.sdk / self.target / 'linking.json'
        contract = json.loads(path.read_text())
        contract['compileOptions'].append('-include=/untrusted/secret')
        path.write_text(json.dumps(contract))
        manifest_path = self.sdk / 'manifest.json'
        manifest = json.loads(manifest_path.read_text())
        entry = manifest['targets'][self.target]
        for index in (entry['files'], entry['targetFiles']):
            for item in index:
                if item['path'].endswith('/linking.json'):
                    item['sha256'] = linker.consumer.sha(path); item['size'] = path.stat().st_size
        manifest_path.write_text(json.dumps(manifest))
        with patch.object(linker, '_run') as execute:
            with self.assertRaisesRegex(ValueError, 'outside the supported'): self.build()
            execute.assert_not_called()

    def test_matching_clang_commit_accepts_different_installed_dir_only(self):
        actual = subprocess.check_output([self.compiler, '--version'], text=True).strip()
        recorded = actual.splitlines()[0] + '\nInstalledDir: /producer/fixed/clang/bin'
        self.assertEqual(linker.compiler_identity(recorded), linker.compiler_identity(actual))
        output = self.build()
        self.assertTrue((output / 'manifest.json').is_file())

    def test_framework_options_are_typed_pairs_without_paths_or_missing_names(self):
        path = self.sdk / self.target / 'linking.json'
        for framework in ('/untrusted/Foundation', None):
            contract = json.loads(path.read_text())
            contract['linkOptions'] = ['-framework'] + ([] if framework is None else [framework])
            path.write_text(json.dumps(contract))
            manifest_path = self.sdk / 'manifest.json'
            manifest = json.loads(manifest_path.read_text())
            for index in (manifest['targets'][self.target]['files'], manifest['targets'][self.target]['targetFiles']):
                for item in index:
                    if item['path'].endswith('/linking.json'):
                        item['sha256'] = linker.consumer.sha(path); item['size'] = path.stat().st_size
            manifest_path.write_text(json.dumps(manifest))
            with patch.object(linker, '_run') as execute:
                with self.assertRaisesRegex(ValueError, 'framework option'): self.build()
                execute.assert_not_called()

    def test_compiler_must_match_sdk_toolchain_evidence(self):
        manifest_path = self.sdk / 'manifest.json'
        manifest = json.loads(manifest_path.read_text()); manifest['targets'][self.target]['toolchain']['clang'] = 'clang version different-commit'
        manifest_path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, 'Compiler differs'): self.build()
        self.assertFalse(self.cache.exists())


if __name__ == '__main__':
    unittest.main()
