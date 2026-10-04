"""Offline tests: no source checkout, downloads, or compiler execution."""
import hashlib
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('official_v8_builder', Path(__file__).with_name('build.py'))
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)


class BuildContractTests(unittest.TestCase):
    def test_pins_are_official_fixed_revisions(self):
        pins = builder.read_pins()
        self.assertEqual(pins['v8']['version'], '15.4.80.24')
        self.assertEqual(pins['v8']['revision'], 'e422f6ef0c7b877b04e4872fd0bd3a1cc2ec2eee')
        self.assertEqual(len(pins['depotTools']['revision']), 40)

    def test_minimal_official_depot_initialization_avoids_extra_venvs(self):
        with patch.object(builder, 'run') as execute:
            depot = Path('/isolated/.cache/depot_tools')
            env = {'DEPOT_TOOLS_UPDATE': '0'}
            builder.initialize_depot(depot, env)
            command, working, passed_env = execute.call_args.args
            self.assertEqual(command[:2], ['bash', '-c'])
            self.assertIn('source "$1/bootstrap_python3"; bootstrap_python3', command[2])
            self.assertIn('source "$1/cipd_bin_setup.sh"; cipd_bin_setup', command[2])
            self.assertNotIn('ensure_bootstrap', command[2])
            self.assertNotIn('gsutil', command[2])
            self.assertNotIn('pylint', command[2])
            self.assertEqual(command[-1], depot)
            self.assertEqual(working, depot)
            self.assertEqual(passed_env['DEPOT_TOOLS_UPDATE'], '0')

    def test_android_refuses_mac_and_linux_arm_host(self):
        for host in [('Darwin', 'arm64'), ('Linux', 'aarch64')]:
            with patch.object(builder.platform, 'system', return_value=host[0]), patch.object(builder.platform, 'machine', return_value=host[1]):
                with self.assertRaisesRegex(ValueError, 'Linux x86_64'):
                    builder.require_host('android-arm64')
        with patch.object(builder.platform, 'system', return_value='Linux'), patch.object(builder.platform, 'machine', return_value='x86_64'):
            builder.require_host('android-arm64')

    def test_bridge_digest_uses_sorted_length_prefixed_labels_and_contents(self):
        with tempfile.TemporaryDirectory() as directory:
            a = Path(directory) / 'a'; a.write_bytes(b'first')
            b = Path(directory) / 'b'; b.write_bytes(b'second')
            expected = hashlib.sha256()
            for label, data in [(b'one', b'first'), (b'two', b'second')]:
                expected.update(struct.pack('>Q', len(label)) + label + struct.pack('>Q', len(data)) + data)
            with patch.object(builder, 'bridge_files', return_value={'two': b, 'one': a}):
                self.assertEqual(builder.bridge_digest(), expected.hexdigest())

    def test_shared_bridge_and_v8_use_same_libcxx_no_external_snapshot(self):
        args = builder.gn_arguments('android-arm64')
        self.assertIn('use_custom_libcxx = true', args)
        self.assertIn('v8_monolithic_for_shared_library = true', args)
        self.assertIn('v8_use_external_startup_data = false', args)
        self.assertIn('android_ndk_api_level = 26', args)
        mac_args = builder.gn_arguments('macos-arm64')
        self.assertIn('mac_deployment_target = "13.0"', mac_args)
        self.assertIn('use_lld = false', mac_args)
        self.assertNotIn('use_lld', args)

    def test_version_zero_patch_and_license_notice_copy(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'v8'; (source / 'include').mkdir(parents=True)
            (source / 'include/v8-version.h').write_text('#define V8_MAJOR_VERSION 15\n#define V8_MINOR_VERSION 7\n#define V8_BUILD_NUMBER 36\n#define V8_PATCH_LEVEL 0\n')
            self.assertEqual(builder.source_version(source), '15.7.36')
            (source / 'LICENSE').write_text('license'); (source / 'AUTHORS').write_text('authors')
            dependency = source / 'third_party/example'; dependency.mkdir(parents=True)
            (dependency / 'NOTICE').write_text('notice')
            output = Path(directory) / 'artifact'
            entries = builder.package_licenses(source, output)
            self.assertEqual({entry['path'] for entry in entries}, {'licenses/LICENSE', 'licenses/AUTHORS', 'licenses/third_party/example/NOTICE'})
            for entry in entries:
                self.assertEqual(builder.sha(output / entry['path']), entry['sha256'])

    def test_license_conflicts_reject_before_replacing_any_existing_file(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'source'; source.mkdir()
            (source / 'third_party').mkdir()
            destination = Path(directory) / 'artifact'
            (source / 'LICENSE').write_text('original license')
            entries = builder.package_licenses(source, destination)
            (source / 'LICENSE').write_text('different license')
            (source / 'AUTHORS').write_text('new authors')
            with self.assertRaisesRegex(ValueError, 'License provenance conflict'):
                builder.package_licenses(source, destination, entries)
            self.assertEqual((destination / 'licenses/LICENSE').read_text(), 'original license')
            self.assertFalse((destination / 'licenses/AUTHORS').exists())

    def test_manifest_records_binaries_and_refuses_provenance_collision(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'checkout/v8'
            (source / 'include').mkdir(parents=True)
            (source / 'include/v8-version.h').write_text('#define V8_MAJOR_VERSION 15\n#define V8_MINOR_VERSION 4\n#define V8_BUILD_NUMBER 80\n#define V8_PATCH_LEVEL 24\n')
            (source / 'DEPS').write_text('official pinned dependencies')
            (source / 'LICENSE').write_text('BSD')
            (source / 'third_party').mkdir()
            out = source / 'out/source_v8'; out.mkdir(parents=True)
            (out / 'libsource_v8.dylib').write_bytes(b'mac binary')
            (out / 'libsource_v8.so').write_bytes(b'android binary')
            bridge = root / 'bridge'; bridge.mkdir()
            files = {}
            for name in ['source_v8.cpp', 'source_v8.h', 'android_exports.map', 'source_v8.gni']:
                path = bridge / name; path.write_text(name)
                files[name] = path
            def mocked_run(args, cwd, env=None, capture=False):
                if 'gen' in args:
                    self.assertIn('--root-target=//source_v8:source_v8', args)
                if not capture:
                    return None
                if 'revinfo' in args:
                    return 'dependency@fixedsha'
                if 'desc' in args:
                    self.assertIn('--root-target=//source_v8:source_v8', args)
                    return '{"defines": ["feature"]}'
                return 'official toolchain version'
            pins = builder.read_pins()
            package = root / 'package'
            with patch.object(builder, 'PACKAGE', package), patch.object(builder, 'bridge_files', return_value=files), patch.object(builder, 'run', side_effect=mocked_run):
                linux_notice = source / 'third_party/LinuxOnly/NOTICE'
                linux_notice.parent.mkdir()
                linux_notice.write_text('Linux dependency notice')
                builder.build(source, root / 'depot', {}, 'android-arm64', 1, pins)
                linux_notice.unlink()
                builder.build(source, root / 'depot', {}, 'macos-arm64', 1, pins)
                artifact = package / '.cache/self-built' / pins['v8']['revision']
                manifest = __import__('json').loads((artifact / 'manifest.json').read_text())
                self.assertEqual(set(manifest['targets']), {'android-arm64', 'macos-arm64'})
                self.assertIn('appleLinker', manifest['targets']['macos-arm64']['toolchain'])
                self.assertIn('xcode', manifest['targets']['macos-arm64']['toolchain'])
                self.assertIn('macSdk', manifest['targets']['macos-arm64']['toolchain'])
                self.assertNotIn('appleLinker', manifest['targets']['android-arm64']['toolchain'])
                self.assertIn('licenses/third_party/LinuxOnly/NOTICE', {entry['path'] for entry in manifest['licenses']})
                self.assertEqual((artifact / 'licenses/third_party/LinuxOnly/NOTICE').read_text(), 'Linux dependency notice')
                for target in manifest['targets'].values():
                    self.assertEqual(builder.sha(artifact / target['binary']), target['sha256'])
                    self.assertFalse(target['validation']['runtimeTested'])
                    self.assertFalse(target['validation']['sourceCompatibilityTested'])
                old = (artifact / 'macos-arm64/libsource_v8.dylib').read_bytes()
                files['source_v8.cpp'].write_text('changed bridge')
                (out / 'libsource_v8.dylib').write_bytes(b'new binary')
                with self.assertRaisesRegex(ValueError, 'provenance differs'):
                    builder.build(source, root / 'depot', {}, 'macos-arm64', 1, pins)
                self.assertEqual((artifact / 'macos-arm64/libsource_v8.dylib').read_bytes(), old)
                files['source_v8.cpp'].write_text('source_v8.cpp')
                changed_tools = {**pins, 'depotTools': {**pins['depotTools'], 'revision': 'a' * 40}}
                with self.assertRaisesRegex(ValueError, 'provenance differs'):
                    builder.build(source, root / 'depot', {}, 'macos-arm64', 1, changed_tools)
                self.assertEqual((artifact / 'macos-arm64/libsource_v8.dylib').read_bytes(), old)
                for mutate_overlay in [False, True]:
                    files['source_v8.cpp'].write_text('source_v8.cpp')
                    def mutation_run(args, cwd, env=None, capture=False):
                        if 'autoninja' in str(args[0]):
                            changed = source / 'source_v8/source_v8.cpp' if mutate_overlay else files['source_v8.cpp']
                            changed.write_text('edited while compiler was running')
                        return mocked_run(args, cwd, env, capture)
                    with patch.object(builder, 'run', side_effect=mutation_run):
                        with self.assertRaisesRegex(ValueError, 'changed during build'):
                            builder.build(source, root / 'depot', {}, 'macos-arm64', 1, pins)
                    self.assertEqual((artifact / 'macos-arm64/libsource_v8.dylib').read_bytes(), old)


if __name__ == '__main__':
    unittest.main()
