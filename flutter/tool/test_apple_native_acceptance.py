import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

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

    def test_ci_device_is_new_and_does_not_inherit_runner_device_data(self):
        identifier = '12345678-1234-1234-1234-123456789ABC'
        runner = Mock()
        runner.command.return_value = identifier + '\n'
        selected = {'udid': 'runner-existing', 'runtime': 'ios-26', 'sdkVersion': '26.5',
                    'deviceTypeIdentifier': 'iphone', 'dataPath': '/runner/existing-data'}
        created = module.create_ci_device(runner, selected)
        self.assertEqual(created['udid'], identifier)
        self.assertNotIn('dataPath', created)
        self.assertEqual(selected['udid'], 'runner-existing')
        self.assertEqual(runner.command.call_args.args[1][-2:], ['iphone', 'ios-26'])
        self.assertTrue(created['createdForAcceptance'])

    def test_invalid_created_uuid_cannot_be_used_for_boot_or_cleanup(self):
        runner = Mock()
        runner.command.return_value = 'unexpected output'
        with self.assertRaisesRegex(module.PhaseFailure, 'valid owned device UUID'):
            module.create_ci_device(runner, {'deviceTypeIdentifier': 'iphone', 'runtime': 'ios'})
        self.assertEqual(runner.command.call_count, 1)

    def test_known_uuid_runs_real_app_tests_without_redundant_global_discovery(self):
        runner = Mock()
        runner.command.return_value = 'Status=4, isTerminal=YES, Finished'
        module.run_selected_device(runner, {'udid': 'owned', 'state': 'Shutdown'}, 'flutter', 180, 1200)
        self.assertEqual([x.args[0] for x in runner.command.call_args_list], ['boot', 'boot-ready', 'flutter-test'])
        self.assertIn('integration_test/native_app_test.dart', runner.command.call_args.args[1])
        self.assertEqual(runner.command.call_args.args[1][-3:], ['-d', 'owned', '--verbose'])

    def test_terminal_migration_failure_with_exit_zero_never_reaches_app_test(self):
        for output in ['Data Migration Failed', 'Status=3, isTerminal=YES']:
            runner = Mock()
            runner.command.return_value = output
            with self.subTest(output=output), self.assertRaisesRegex(module.PhaseFailure, 'failed data migration'):
                module.run_selected_device(runner, {'udid': 'owned', 'state': 'Booted'}, 'flutter', 180, 1200)
            self.assertEqual([x.args[0] for x in runner.command.call_args_list], ['boot-ready'])

    def test_ci_owned_migration_failure_reboots_same_uuid_before_running_tests_once(self):
        runner = Mock()
        runner.command.side_effect = [
            'Status=3, isTerminal=YES, Data Migration Failed', '', '',
            'Status=4294967295, isTerminal=YES, Finished', 'All tests passed']
        module.run_selected_device(runner, {'udid': 'owned', 'state': 'Booted',
            'createdForAcceptance': True}, 'flutter', 180, 1200)
        calls = runner.command.call_args_list
        self.assertEqual([x.args[0] for x in calls], ['boot-ready',
            'migration-recovery-shutdown', 'migration-recovery-boot',
            'migration-recovery-ready', 'flutter-test'])
        for call in calls[:-1]:
            self.assertIn('owned', call.args[1])
            self.assertNotIn('all', call.args[1])
            self.assertNotIn('erase', call.args[1])
        self.assertEqual(calls[3].args[2], 180)
        self.assertEqual(calls[-1].args[2], 1200)

    def test_timed_out_owned_shutdown_requires_exact_device_postcondition_before_boot(self):
        runner = Mock()
        snapshot = {'devices': {'ios': [{'udid': 'owned', 'state': 'Shutdown'}]}}
        runner.command.side_effect = ['Data Migration Failed', module.PhaseTimeout('shutdown timed out'),
            json.dumps(snapshot), '', 'Finished', 'All tests passed']
        module.run_selected_device(runner, {'udid': 'owned', 'state': 'Booted',
            'createdForAcceptance': True}, 'flutter', 180, 1200)
        calls = runner.command.call_args_list
        self.assertEqual([x.args[0] for x in calls], ['boot-ready',
            'migration-recovery-shutdown', 'migration-recovery-state',
            'migration-recovery-boot', 'migration-recovery-ready', 'flutter-test'])
        self.assertEqual(calls[2].args[2], 30)
        self.assertEqual(calls[3].args[1][-1], 'owned')

    def test_shutdown_timeout_does_not_boot_a_missing_different_or_not_shutdown_device(self):
        for devices in [[], [{'udid': 'other', 'state': 'Shutdown'}],
                        [{'udid': 'owned', 'state': 'Booting'}],
                        [{'udid': 'owned', 'state': 'Shutting Down'}],
                        [{'udid': 'owned', 'state': 'Shutdown'}] * 2]:
            runner = Mock()
            runner.command.side_effect = ['Data Migration Failed', module.PhaseTimeout('shutdown timed out'),
                json.dumps({'devices': {'ios': devices}})]
            with self.subTest(devices=devices), self.assertRaisesRegex(module.PhaseTimeout, 'shutdown timed out'):
                module.run_selected_device(runner, {'udid': 'owned', 'state': 'Booted',
                    'createdForAcceptance': True}, 'flutter', 180, 1200)
            self.assertEqual(runner.command.call_count, 3)

    def test_still_booted_owned_device_gets_one_confirmed_shutdown_before_one_reboot(self):
        runner = Mock()
        snapshot = lambda state: json.dumps({'devices': {'ios': [{'udid': 'owned', 'state': state}]}})
        runner.command.side_effect = ['Data Migration Failed', module.PhaseTimeout('shutdown timed out'),
            snapshot('Booted'), '', snapshot('Shutdown'), '', 'Finished', 'All tests passed']
        module.run_selected_device(runner, {'udid': 'owned', 'state': 'Booted',
            'createdForAcceptance': True}, 'flutter', 180, 1200)
        calls = runner.command.call_args_list
        self.assertEqual([x.args[0] for x in calls], ['boot-ready',
            'migration-recovery-shutdown', 'migration-recovery-state',
            'migration-recovery-shutdown-confirm', 'migration-recovery-state-confirm',
            'migration-recovery-boot', 'migration-recovery-ready', 'flutter-test'])
        self.assertEqual(calls[3].args[1], ['xcrun', 'simctl', 'shutdown', 'owned'])
        self.assertEqual(calls[3].args[2], 30)
        self.assertEqual(calls[4].args[2], 30)
        self.assertEqual(sum(call.args[0] == 'flutter-test' for call in calls), 1)

    def test_compensating_shutdown_requires_success_before_its_state_probe(self):
        for failure in [module.PhaseTimeout('confirm timed out'), module.PhaseFailure('confirm exited 7')]:
            runner = Mock()
            runner.command.side_effect = [module.PhaseTimeout('shutdown timed out'),
                json.dumps({'devices': {'ios': [{'udid': 'owned', 'state': 'Booted'}]}}), failure]
            with self.subTest(failure=failure), self.assertRaisesRegex(module.PhaseFailure, 'confirm'):
                module.shutdown_for_migration_recovery(runner, 'owned')
            self.assertEqual(runner.command.call_count, 3)

    def test_compensating_shutdown_never_boots_without_exact_shutdown_postcondition(self):
        for devices in [[], [{'udid': 'other', 'state': 'Shutdown'}],
                        [{'udid': 'owned', 'state': 'Booted'}],
                        [{'udid': 'owned', 'state': 'Shutting Down'}],
                        [{'udid': 'owned', 'state': 'Shutdown'}] * 2]:
            runner = Mock()
            runner.command.side_effect = ['Data Migration Failed', module.PhaseTimeout('shutdown timed out'),
                json.dumps({'devices': {'ios': [{'udid': 'owned', 'state': 'Booted'}]}}), '',
                json.dumps({'devices': {'ios': devices}})]
            with self.subTest(devices=devices), self.assertRaisesRegex(module.PhaseTimeout, 'shutdown timed out'):
                module.run_selected_device(runner, {'udid': 'owned', 'state': 'Booted',
                    'createdForAcceptance': True}, 'flutter', 180, 1200)
            self.assertEqual(runner.command.call_count, 5)

    def test_compensating_shutdown_probe_errors_remain_fatal(self):
        for result in [module.PhaseTimeout('probe timed out'),
                       module.PhaseFailure('probe exited 7'), '{broken']:
            runner = Mock()
            runner.command.side_effect = [module.PhaseTimeout('shutdown timed out'),
                json.dumps({'devices': {'ios': [{'udid': 'owned', 'state': 'Booted'}]}}), '', result]
            with self.subTest(result=result), self.assertRaises((module.PhaseFailure, ValueError)):
                module.shutdown_for_migration_recovery(runner, 'owned')
            self.assertEqual(runner.command.call_count, 4)

    def test_shutdown_exit_error_never_becomes_a_timeout_recovery(self):
        runner = Mock()
        runner.command.side_effect = module.PhaseFailure('shutdown exited 7')
        with self.assertRaisesRegex(module.PhaseFailure, 'exited 7'):
            module.shutdown_for_migration_recovery(runner, 'owned')
        self.assertEqual(runner.command.call_count, 1)

    def test_shutdown_state_probe_failure_stays_fatal_without_reboot(self):
        for failure in (module.PhaseTimeout('state probe timed out'),
                        module.PhaseFailure('state probe exited 7')):
            runner = Mock()
            runner.command.side_effect = [module.PhaseTimeout('shutdown timed out'), failure]
            with self.subTest(failure=failure), self.assertRaises(module.PhaseFailure):
                module.shutdown_for_migration_recovery(runner, 'owned')
            self.assertEqual(runner.command.call_count, 2)

    def test_corrupt_shutdown_state_probe_stays_fatal_without_reboot(self):
        runner = Mock()
        runner.command.side_effect = [module.PhaseTimeout('shutdown timed out'), '{broken']
        with self.assertRaises(ValueError):
            module.shutdown_for_migration_recovery(runner, 'owned')
        self.assertEqual(runner.command.call_count, 2)

    def test_second_owned_migration_failure_stays_red_without_running_app(self):
        runner = Mock()
        runner.command.side_effect = ['Data Migration Failed', '', '', 'Status=3, isTerminal=YES']
        with self.assertRaisesRegex(module.PhaseFailure, 'failed data migration'):
            module.run_selected_device(runner, {'udid': 'owned', 'state': 'Booted',
                'createdForAcceptance': True}, 'flutter', 180, 1200)
        self.assertEqual(runner.command.call_count, 4)
        self.assertNotIn('flutter-test', [x.args[0] for x in runner.command.call_args_list])

    def test_owned_boot_timeout_is_not_treated_as_recoverable_migration(self):
        runner = Mock()
        runner.command.side_effect = module.PhaseFailure('boot-ready timed out')
        with self.assertRaisesRegex(module.PhaseFailure, 'timed out'):
            module.run_selected_device(runner, {'udid': 'owned', 'state': 'Booted',
                'createdForAcceptance': True}, 'flutter', 180, 1200)
        self.assertEqual(runner.command.call_count, 1)

    def test_app_failure_after_boot_recovery_is_not_retried(self):
        runner = Mock()
        runner.command.side_effect = ['Data Migration Failed', '', '', 'Finished',
            module.PhaseFailure('flutter-test exited 1')]
        with self.assertRaisesRegex(module.PhaseFailure, 'flutter-test exited 1'):
            module.run_selected_device(runner, {'udid': 'owned', 'state': 'Booted',
                'createdForAcceptance': True}, 'flutter', 180, 1200)
        self.assertEqual([x.args[0] for x in runner.command.call_args_list].count('flutter-test'), 1)

    def test_only_the_acceptance_owned_device_is_shutdown_and_deleted(self):
        runner = Mock()
        module.dispose_ci_device(runner, 'owned')
        self.assertEqual([x.args[1] for x in runner.command.call_args_list], [
            ['xcrun', 'simctl', 'shutdown', 'owned'], ['xcrun', 'simctl', 'delete', 'owned']])

    def test_cleanup_error_does_not_replace_original_test_failure(self):
        runner = Mock()
        runner.command.side_effect = module.PhaseFailure('cleanup failed')
        with self.assertRaisesRegex(module.PhaseFailure, 'original test failed'):
            try:
                raise module.PhaseFailure('original test failed')
            finally:
                module.dispose_ci_device(runner, 'owned')
        self.assertEqual(runner.command.call_count, 2)
        with self.assertRaisesRegex(module.PhaseFailure, 'Could not clean up'):
            module.dispose_ci_device(runner, 'owned')

    def exercise_main(self, directory, extra, ci):
        identifier = '12345678-1234-1234-1234-123456789ABC'
        snapshot = self.devices()
        for devices in snapshot['devices'].values():
            for device in devices:
                device['deviceTypeIdentifier'] = 'iphone'
        def command(phase, *_args, **_kwargs):
            return {'installed-runtimes': '{"runtimes": []}', 'xcode-sdk': '26.5',
                    'available-devices': json.dumps(snapshot), 'create-ci-device': identifier,
                    'boot-ready': 'Status=4, isTerminal=YES, Finished'}.get(phase, '')
        with patch.object(module.platform, 'system', return_value='Darwin'), \
             patch.object(module.platform, 'machine', return_value='arm64'), \
             patch.object(module.sys, 'version_info', (3, 13)), \
             patch.object(module.sys, 'argv', ['runner', '--output', directory, *extra]), \
             patch.dict(os.environ, {'GITHUB_ACTIONS': 'true' if ci else 'false'}), \
             patch.object(module.Runner, 'command', side_effect=command) as calls:
            module.main()
        return calls.call_args_list, identifier

    def test_ci_main_creates_and_cleans_only_its_new_device(self):
        with tempfile.TemporaryDirectory() as directory:
            calls, identifier = self.exercise_main(directory, [], True)
            phases = [call.args[0] for call in calls]
            self.assertIn('create-ci-device', phases)
            self.assertIn('flutter-test', phases)
            self.assertEqual(phases[-2:], ['shutdown-owned-device', 'delete-owned-device'])
            self.assertEqual(calls[-1].args[1][-1], identifier)
            self.assertEqual(json.loads((Path(directory) / 'selected-device.json').read_text())['udid'], identifier)

    def test_local_explicit_device_is_never_created_or_deleted(self):
        with tempfile.TemporaryDirectory() as directory:
            calls, _ = self.exercise_main(directory, ['--device', 'old'], False)
            phases = [call.args[0] for call in calls]
            self.assertIn('flutter-test', phases)
            self.assertFalse(any('owned' in name or name == 'create-ci-device' for name in phases))

    def test_list_only_on_ci_never_creates_boots_tests_or_deletes(self):
        with tempfile.TemporaryDirectory() as directory:
            calls, _ = self.exercise_main(directory, ['--list-only'], True)
            phases = [call.args[0] for call in calls]
            self.assertEqual(phases, ['xcode-version', 'installed-runtimes', 'xcode-sdk', 'available-devices'])

    def test_silent_child_is_bounded_and_failure_phase_is_persisted(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = module.Runner(directory, directory)
            start = time.monotonic()
            with self.assertRaisesRegex(module.PhaseTimeout, 'timed out'):
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

    def test_first_inventory_has_cold_budget_without_retrying_app_tests(self):
        with tempfile.TemporaryDirectory() as directory:
            def reply(phase, argv, seconds, **kwargs):
                return {'xcode-version': 'Xcode 26.6\n', 'installed-runtimes': '{}',
                        'xcode-sdk': '26.5', 'available-devices': json.dumps(self.devices())}[phase]
            with patch.object(module.platform, 'system', return_value='Darwin'), \
                 patch.object(module.platform, 'machine', return_value='arm64'), \
                 patch.object(module.sys, 'argv', ['runner', '--output', directory, '--list-only']), \
                 patch.object(module.Runner, 'command', side_effect=reply) as command:
                module.main()
            calls = [call for call in command.call_args_list if call.args[0] == 'installed-runtimes']
            self.assertEqual(len(calls), 1)
            self.assertEqual(calls[0].args[2], 120)
            self.assertTrue(callable(calls[0].kwargs['on_tick']))
            self.assertFalse(any(call.args[0] == 'flutter-test' for call in command.call_args_list))

    def test_cold_inventory_allows_delayed_real_child_once(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = module.Runner(directory, directory)
            execute = runner.command
            def fixture(phase, argv, seconds, **kwargs):
                return execute(phase, [sys.executable, '-c',
                    'import time; time.sleep(0.15); print("{\\"runtimes\\": []}")'], seconds, **kwargs)
            with patch.object(runner, 'command', side_effect=fixture) as command:
                self.assertEqual(module.installed_runtimes(runner, 1), {'runtimes': []})
                self.assertEqual(command.call_count, 1)
            self.assertEqual(json.loads((Path(directory) / 'phases.json').read_text())[0]['exitCode'], 0)

    def test_cold_inventory_timeout_remains_failure_without_retry(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = module.Runner(directory, directory)
            execute = runner.command
            def fixture(phase, argv, seconds, **kwargs):
                return execute(phase, [sys.executable, '-c', 'import time; time.sleep(5)'], seconds, **kwargs)
            with patch.object(runner, 'command', side_effect=fixture) as command:
                with self.assertRaisesRegex(module.PhaseFailure, 'installed-runtimes timed out'):
                    module.installed_runtimes(runner, 1)
                self.assertEqual(command.call_count, 1)
            self.assertTrue(json.loads((Path(directory) / 'phases.json').read_text())[0]['timedOut'])

    def test_discovery_override_remains_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = module.Runner(directory, directory)
            with patch.object(runner, 'command') as command:
                for invalid in (0, -1, 301):
                    with self.assertRaises(module.PhaseFailure):
                        module.installed_runtimes(runner, invalid)
                command.assert_not_called()

    def test_service_diagnostics_exclude_other_host_executables(self):
        output = ('101 00:45 S /Library/PrivateFrameworks/CoreSimulator.framework/com.apple.CoreSimulator.CoreSimulatorService\n'
                  '102 00:10 S /Library/PrivateFrameworks/CoreSimulator.framework/simdiskimaged\n'
                  '103 01:00 R /Applications/PrivateApp.app/PrivateApp\n')
        self.assertEqual([item['pid'] for item in module.simulator_service_processes(output)], [101, 102])

    def test_cold_service_progress_samples_once_without_extra_simctl_queries(self):
        with patch.object(module, 'simulator_service_diagnostics') as diagnostic:
            tick = module.cold_service_progress(object())
            tick(15)
            diagnostic.assert_not_called()
            tick(30)
            tick(45)
            self.assertEqual(diagnostic.call_count, 1)
            self.assertEqual(diagnostic.call_args.args[1], 'simulator-service-startup')
