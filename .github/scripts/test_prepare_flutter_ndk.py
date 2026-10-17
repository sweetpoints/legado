import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('prepare_flutter_ndk', Path(__file__).with_name('prepare-flutter-ndk.py'))
ndk = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ndk)
VERSION = '28.2.13676358'


class FlutterNdkPreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.sdk = self.root / 'android sdk'
        self.flutter = self.root / 'flutter sdk'
        extension = self.flutter / 'packages/flutter_tools/gradle/src/main/kotlin/FlutterExtension.kt'
        extension.parent.mkdir(parents=True)
        extension.write_text(f'val ndkVersion: String = "{VERSION}"')
        self.calls = []
        self.sleeps = []

    def complete(self, version=VERSION):
        base = self.sdk / 'ndk' / version
        base.mkdir(parents=True, exist_ok=True)
        (base / 'source.properties').write_text(f'Pkg.Revision = {version}\n')
        for relative in ['ndk-build', 'toolchains/llvm/prebuilt/linux-x86_64/bin/clang',
                         'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip']:
            tool = base / relative
            tool.parent.mkdir(parents=True, exist_ok=True)
            tool.write_text('#!/bin/sh\n')
            tool.chmod(0o755)

    def invoke(self, results, install_at=None):
        def run(command, **options):
            self.calls.append(command)
            self.assertEqual(options, dict(stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=600))
            if len(self.calls) == install_at:
                self.complete()
            code, output = results[len(self.calls) - 1]
            return subprocess.CompletedProcess(command, code, output)
        ndk.prepare(self.sdk, self.flutter, '/configured/22.0/bin/sdkmanager', run=run, sleep=self.sleeps.append)

    def test_installed_ndk_is_reused_without_installation(self):
        self.complete()
        self.invoke([])
        self.assertEqual(self.calls, [])

    def test_missing_packaging_strip_tool_requires_repair_even_when_clang_exists(self):
        self.complete()
        (self.sdk / 'ndk' / VERSION / 'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip').unlink()
        self.assertFalse(ndk.installed(self.sdk, VERSION))
        self.invoke([(0, 'repaired')], install_at=1)
        self.assertEqual(len(self.calls), 1)
        self.assertTrue(ndk.installed(self.sdk, VERSION))

    def test_install_uses_configured_cli_and_flutter_sdk_version(self):
        self.invoke([(0, 'installed\n')], install_at=1)
        self.assertEqual(self.calls, [['/configured/22.0/bin/sdkmanager', f'--sdk_root={self.sdk}', f'ndk;{VERSION}']])

    def test_observed_archive_error_is_retried_then_verified(self):
        self.invoke([(1, 'Error on ZipFile unknown archive'), (0, 'installed')], install_at=2)
        self.assertEqual(len(self.calls), 2)
        self.assertEqual(self.sleeps, [5])

    def test_archive_failures_are_bounded_and_propagated(self):
        with self.assertRaises(RuntimeError):
            self.invoke([(1, 'Error on ZipFile unknown archive')] * 3)
        self.assertEqual(len(self.calls), 3)
        self.assertEqual(self.sleeps, [5, 10])

    def test_unrelated_failure_is_not_retried(self):
        with self.assertRaises(RuntimeError):
            self.invoke([(1, 'licenses not accepted')])
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(self.sleeps, [])

    def test_success_without_complete_package_is_fatal(self):
        base = self.sdk / 'ndk' / VERSION
        base.mkdir(parents=True)
        (base / 'source.properties').write_text(f'Pkg.Revision = {VERSION}\n')
        with self.assertRaises(RuntimeError):
            self.invoke([(0, 'success without tools')])
        self.assertEqual(len(self.calls), 1)

    def test_bad_sdk_version_fails_before_invoking_manager(self):
        extension = self.flutter / 'packages/flutter_tools/gradle/src/main/kotlin/FlutterExtension.kt'
        extension.write_text('not an SDK extension')
        with self.assertRaises(RuntimeError):
            self.invoke([])
        self.assertEqual(self.calls, [])


if __name__ == '__main__':
    unittest.main()
