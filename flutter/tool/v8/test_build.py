"""Legado is an SDK consumer; source build entry points are explicitly disabled."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import unittest

SPEC = importlib.util.spec_from_file_location('legacy_provenance', Path(__file__).with_name('build.py'))
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)


class SdkOnlyBuildTests(unittest.TestCase):
    def test_production_pins_match_reviewed_release(self):
        import json
        pin = json.loads(Path(__file__).with_name('release-pin.json').read_text())
        local = builder.read_pins()
        self.assertEqual(local['v8'], pin['v8'])
        self.assertEqual(local['depotTools'], pin['depotTools'])
        self.assertEqual(local['v8']['version'], '15.4.80.25')
        self.assertEqual({a['target'] for a in pin['assets']}, set(local['targets']))

    def test_both_old_source_build_commands_fail_closed(self):
        for action in ['bootstrap', 'build']:
            result = subprocess.run([sys.executable, str(Path(__file__).with_name('build.py')), action,
                                     '--target', 'android-arm64'], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('source builds are disabled', result.stderr)
            self.assertIn('prepare_sdk.py', result.stderr)

    def test_provenance_helpers_keep_name_content_boundaries(self):
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = root / 'first'; first.write_bytes(b'bc')
            second = root / 'second'; second.write_bytes(b'c')
            self.assertNotEqual(builder.bridge_digest({'a': first}), builder.bridge_digest({'ab': second}))

    def test_ci_has_no_v8_source_build_command(self):
        for file in ['.github/workflows/flutter-source-engine.yml', '.github/actions/setup-android/action.yml']:
            script = (builder.ROOT / file).read_text()
            self.assertNotIn('v8/build.py bootstrap', script)
            self.assertNotIn('v8/build.py build', script)
            self.assertNotIn('install-build-deps.sh', script)
            self.assertIn('prepare_sdk.py', script)
