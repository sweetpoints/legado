import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import time
import unittest

SPEC = importlib.util.spec_from_file_location('ios_ci_runner', Path(__file__).with_name('apple-native-acceptance.py'))
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class DeviceSelectionTests(unittest.TestCase):
    def devices(self):
        return {'devices': {
            'com.apple.CoreSimulator.SimRuntime.iOS-18-5': [
                {'name': 'iPhone old', 'udid': 'old', 'isAvailable': True, 'state': 'Booted'}],
            'com.apple.CoreSimulator.SimRuntime.iOS-26-0': [
                {'name': 'iPhone current', 'udid': 'current', 'isAvailable': True, 'state': 'Shutdown'},
                {'name': 'iPhone unavailable', 'udid': 'bad', 'isAvailable': False, 'state': 'Shutdown'}],
            'com.apple.CoreSimulator.SimRuntime.iOS-27-0': [
                {'name': 'iPhone future', 'udid': 'future', 'isAvailable': True, 'state': 'Shutdown'}],
        }}

    def test_current_xcode_runtime_wins_over_first_old_booted_device(self):
        self.assertEqual(module.choose_device(self.devices(), '26.5')['udid'], 'current')

    def test_booted_phone_is_preferred_within_newest_compatible_runtime(self):
        snapshot = self.devices()
        snapshot['devices']['com.apple.CoreSimulator.SimRuntime.iOS-26-0'].append(
            {'name': 'iPhone A booted', 'udid': 'booted', 'isAvailable': True, 'state': 'Booted'})
        snapshot['devices']['com.apple.CoreSimulator.SimRuntime.iOS-26-5'] = [
            {'name': 'iPhone too new for SDK', 'udid': 'minor-future', 'isAvailable': True, 'state': 'Booted'}]
        self.assertEqual(module.choose_device(snapshot, '26.0')['udid'], 'booted')

    def test_newer_unavailable_or_non_ios_runtime_cannot_be_selected(self):
        snapshot = self.devices()
        snapshot['devices']['com.apple.CoreSimulator.SimRuntime.tvOS-26-0'] = [
            {'name': 'iPhone fake', 'udid': 'tv', 'isAvailable': True, 'state': 'Booted'}]
        self.assertEqual(module.choose_device(snapshot, '18.5')['udid'], 'old')
        with self.assertRaises(module.PhaseFailure):
            module.choose_device(snapshot, '26.0', 'bad')

    def test_explicit_device_is_preserved_and_missing_device_fails(self):
        self.assertEqual(module.choose_device(self.devices(), '26.0', 'old')['udid'], 'old')
        with self.assertRaises(module.PhaseFailure):
            module.choose_device(self.devices(), '26.0', 'missing')

    def test_silent_child_is_bounded_and_failure_phase_is_persisted(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = module.Runner(directory, directory)
            start = time.monotonic()
            with self.assertRaisesRegex(module.PhaseFailure, 'timed out'):
                runner.command('boot-ready', [sys.executable, '-c', 'import time; time.sleep(30)'], 0.1)
            self.assertLess(time.monotonic() - start, 5)
            result = json.loads((Path(directory) / 'phases.json').read_text())
            self.assertTrue(result[0]['timedOut'])
            self.assertEqual(result[0]['phase'], 'boot-ready')
            self.assertIn('finishedAt', result[0])

    def test_stderr_is_logged_without_polluting_machine_json(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = module.Runner(directory, directory)
            output = runner.command('flutter-devices', [sys.executable, '-c',
                'import sys; print("warning", file=sys.stderr); print("[]")'], 5)
            self.assertEqual(json.loads(output), [])
            self.assertIn('warning', (Path(directory) / 'flutter-devices.log').read_text())

    def test_parent_exit_does_not_wait_for_inherited_descendant_pipe(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = module.Runner(directory, directory)
            started = time.monotonic()
            runner.command('probe', [sys.executable, '-c',
                'import subprocess,sys; subprocess.Popen([sys.executable,"-c","import time; time.sleep(1)"])'], 5)
            self.assertLess(time.monotonic() - started, 1)

    def test_failing_child_does_not_get_retried_or_reported_as_timeout(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = module.Runner(directory, directory)
            with self.assertRaisesRegex(module.PhaseFailure, 'exited 7'):
                runner.command('flutter-test', [sys.executable, '-c', 'print("fixture failure"); raise SystemExit(7)'], 5)
            result = json.loads((Path(directory) / 'phases.json').read_text())
            self.assertEqual(len(result), 1)
            self.assertEqual(result[0]['exitCode'], 7)
            self.assertNotIn('timedOut', result[0])
