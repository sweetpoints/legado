import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

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

    def test_preflight_failure_still_publishes_non_sensitive_diagnostics(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(module.platform, 'system', return_value='Linux'), \
                 patch.object(module.sys, 'argv', ['runner', '--output', directory]), \
                 patch.object(module.Runner, 'command') as command:
                with self.assertRaisesRegex(module.PhaseFailure, 'macOS ARM64'):
                    module.main()
                command.assert_not_called()
            root = Path(directory)
            self.assertTrue((root / 'host.json').is_file())
            self.assertTrue((root / 'phases.json').is_file())
            self.assertIn('macOS ARM64', (root / 'failure.txt').read_text())

    def test_failing_child_does_not_get_retried_or_reported_as_timeout(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = module.Runner(directory, directory)
            with self.assertRaisesRegex(module.PhaseFailure, 'exited 7'):
                runner.command('flutter-test', [sys.executable, '-c', 'print("fixture failure"); raise SystemExit(7)'], 5)
            result = json.loads((Path(directory) / 'phases.json').read_text())
            self.assertEqual(len(result), 1)
            self.assertEqual(result[0]['exitCode'], 7)
            self.assertNotIn('timedOut', result[0])

    def test_process_snapshot_retains_only_exact_app_container(self):
        path = '/sim/Containers/Bundle/Application/ABC/Runner.app'
        raw = '12 /other/Runner.app/Runner\n13 ' + path + '/Runner\n14 ' + path + '.other/Runner\n'
        self.assertEqual(module.app_processes(raw, path),
                         [{'pid': 13, 'executable': path + '/Runner'}])

    def test_crash_snapshot_requires_exact_bundle_and_omits_unrelated_data(self):
        report = {'bundleInfo': {'CFBundleIdentifier': module.APP_BUNDLE}, 'pid': 19,
                  'exception': {'type': 'EXC_CRASH'}, 'environment': {'sensitive': 'not retained'}}
        parsed = module.crash_summary('{}\n' + json.dumps(report))
        self.assertEqual(parsed['pid'], 19)
        self.assertNotIn('environment', parsed)
        report['bundleInfo']['CFBundleIdentifier'] = 'another.app'
        self.assertIsNone(module.crash_summary(json.dumps(report)))
        self.assertIsNone(module.crash_summary('invalid json'))

    def test_progress_snapshots_run_once_each_before_timeout(self):
        with patch.object(module, 'app_diagnostics') as capture:
            tick = module.progress_diagnostics(object(), 'selected-uuid', 1200)
            tick(179)
            capture.assert_not_called()
            tick(180)
            tick(181)
            tick(1140)
            tick(1141)
            self.assertEqual([call.args[2] for call in capture.call_args_list],
                             ['progress', 'before-timeout'])

    def test_live_child_progress_callback_executes_before_timeout(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = module.Runner(directory, directory)
            calls = []
            with self.assertRaises(module.PhaseFailure):
                runner.command('probe', [sys.executable, '-c', 'import time; time.sleep(10)'],
                               0.2, on_tick=calls.append)
            self.assertTrue(calls)
            self.assertLess(calls[-1], 1)
