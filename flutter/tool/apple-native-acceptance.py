#!/usr/bin/env python3
"""Run main-app acceptance with explicit, bounded iOS simulator phases.

Does not edit the application, erase simulators, cancel GitHub jobs or retry tests.
An acceptance-owned CI device may reboot once after terminal OS migration failure.
Automatic device selection is allowed only on GitHub's disposable runner; local
execution requires an explicit --device. --list-only is entirely read-only.
"""
import argparse
import datetime
import json
import os
from pathlib import Path
import platform
import plistlib
import re
import signal
import subprocess
import sys
import selectors
import time

ROOT = Path(__file__).resolve().parents[2]
APP_BUNDLE = 'io.legado.legadoApp'


class PhaseFailure(RuntimeError):
    pass


class PhaseTimeout(PhaseFailure):
    pass


def version(identifier):
    match = re.search(r'\.iOS-(\d+(?:-\d+)*)$', identifier)
    return tuple(map(int, match.group(1).split('-'))) if match else None


def choose_device(snapshot, sdk_version, explicit=None):
    sdk = tuple(map(int, sdk_version.split('.')))
    candidates = []
    for runtime, devices in snapshot['devices'].items():
        current = version(runtime)
        if current is None or current[0] < 15 or current > sdk:
            continue
        for device in devices:
            if not device.get('isAvailable'):
                continue
            if explicit is not None:
                if device['udid'] != explicit:
                    continue
            elif 'iPhone' not in device['name']:
                continue
            candidates.append((current, device.get('state') == 'Booted',
                               device['name'], device['udid'], runtime, device))
    if not candidates:
        raise PhaseFailure('No available iOS 15+ simulator compatible with the selected Xcode SDK')
    _, _, _, _, runtime, device = max(candidates)
    return {'runtime': runtime, 'sdkVersion': sdk_version, **device}


class Runner:
    def __init__(self, output, cwd):
        self.output = Path(output)
        self.output.mkdir(parents=True, exist_ok=True)
        self.cwd = Path(cwd)
        self.phases = []

    def command(self, phase, argv, seconds, on_tick=None):
        timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
        print(f'[{timestamp}] phase={phase} deadlineSeconds={seconds} command={argv}', flush=True)
        log = self.output / (phase + '.log')
        result = {'phase': phase, 'startedAt': timestamp, 'timeoutSeconds': seconds,
                  'command': [str(value) for value in argv], 'log': str(log)}
        self.phases.append(result)
        self.save()
        try:
            process = subprocess.Popen(argv, cwd=self.cwd, stdout=subprocess.PIPE,
                                       stderr=subprocess.PIPE, start_new_session=True)
        except OSError as error:
            result['launchError'] = str(error)
            result['finishedAt'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
            log.write_text(str(error) + '\n')
            self.save()
            raise PhaseFailure(f'{phase} could not start; see {log}') from error
        output = []
        started = time.monotonic()
        deadline = started + seconds
        selector = selectors.DefaultSelector()
        for pipe, stdout in ((process.stdout, True), (process.stderr, False)):
            os.set_blocking(pipe.fileno(), False)
            selector.register(pipe, selectors.EVENT_READ, stdout)
        try:
            with log.open('wb') as stream:
                while selector.get_map():
                    if on_tick is not None and process.poll() is None:
                        on_tick(time.monotonic() - started)
                    if time.monotonic() >= deadline and process.poll() is None:
                        result['timedOut'] = True
                        break
                    events = selector.select(timeout=0.1)
                    for key, _ in events:
                        chunk = os.read(key.fd, 65536)
                        if not chunk:
                            selector.unregister(key.fileobj)
                            continue
                        stream.write(chunk)
                        stream.flush()
                        print(chunk.decode('utf-8', errors='replace'), end='', flush=True)
                        if key.data:
                            output.append(chunk)
                    # A spawned simulator app may inherit a pipe after Flutter
                    # exits. Never block forever waiting for a descendant EOF.
                    if not events and process.poll() is not None:
                        break
            remaining = max(0, deadline - time.monotonic())
            try:
                process.wait(timeout=remaining)
            except subprocess.TimeoutExpired:
                result['timedOut'] = True
            if result.get('timedOut'):
                # Only this helper's newly created process group is terminated.
                # Existing hosted jobs and unrelated simulators are untouched.
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=5)
            result['exitCode'] = process.returncode
        finally:
            selector.close()
            process.stdout.close()
            process.stderr.close()
            result['finishedAt'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
            self.save()
        if result.get('timedOut'):
            raise PhaseTimeout(f'{phase} timed out after {seconds}s; see {log}')
        if result['exitCode'] != 0:
            raise PhaseFailure(f'{phase} exited {result["exitCode"]}; see {log}')
        return b''.join(output).decode('utf-8')

    def save(self):
        (self.output / 'phases.json').write_text(json.dumps(self.phases, indent=2) + '\n')



def app_processes(output, app_path):
    """Filter in memory: never persist the host's other applications or arguments."""
    expected = str(Path(app_path)) + '/'
    result = []
    for line in output.splitlines():
        fields = line.strip().split(None, 1)
        if len(fields) == 2 and fields[0].isdigit() and fields[1].startswith(expected):
            result.append({'pid': int(fields[0]), 'executable': fields[1]})
    return result


def crash_summary(text):
    # Apple .ips files contain a metadata JSON line followed by a JSON report.
    decoder = json.JSONDecoder()
    objects = []
    while text.strip():
        try:
            value, end = decoder.raw_decode(text.lstrip())
        except ValueError:
            return None
        objects.append(value)
        text = text.lstrip()[end:]
    report = next((item for item in objects if isinstance(item, dict)
                   and isinstance(item.get('bundleInfo'), dict)
                   and item['bundleInfo'].get('CFBundleIdentifier') == APP_BUNDLE), None)
    if report is None:
        return None
    return {key: report[key] for key in ('procName', 'pid', 'captureTime', 'bundleInfo',
                                        'exception', 'termination') if key in report}


def app_diagnostics(runner, uuid, label):
    """Read only this bundle's installation, processes, launch messages and crashes."""
    summary = {'bundleIdentifier': APP_BUNDLE, 'device': uuid}
    try:
        app = runner.command(label + '-container',
            ['xcrun', 'simctl', 'get_app_container', uuid, APP_BUNDLE, 'app'], 15).strip()
        summary['appPath'] = app
        # Capture all executable paths privately, then retain exact-container matches only.
        processes = subprocess.run(['ps', '-axo', 'pid=,comm='], capture_output=True,
                                   text=True, timeout=5, check=False)
        summary['processes'] = app_processes(processes.stdout, app)
        summary['processQueryExitCode'] = processes.returncode
        info = Path(app) / 'Info.plist'
        if info.is_file():
            with info.open('rb') as stream:
                summary['executableName'] = plistlib.load(stream).get('CFBundleExecutable')
    except (PhaseFailure, OSError, ValueError, subprocess.TimeoutExpired) as error:
        summary['installationOrProcessQuery'] = str(error)
    # Match messages naming our exact bundle; never collect all Runner processes,
    # SpringBoard messages, simulator system logs or other applications' reports.
    predicate = 'eventMessage CONTAINS "' + APP_BUNDLE + '"'
    for process in summary.get('processes', []):
        predicate += ' OR processIdentifier == ' + str(process['pid'])
    try:
        runner.command(label + '-launch', ['xcrun', 'simctl', 'spawn', uuid,
            'log', 'show', '--last', '5m', '--style', 'compact', '--predicate',
            predicate], 15)
    except (PhaseFailure, OSError):
        pass
    summary['crashes'] = []
    reports = Path.home() / 'Library/Logs/DiagnosticReports'
    try:
        candidates = sorted(reports.glob('*.ips'), key=lambda item: item.stat().st_mtime,
                            reverse=True)[:40]
        for report in candidates:
            if report.stat().st_size > 4 * 1024 * 1024 or time.time() - report.stat().st_mtime > 1800:
                continue
            parsed = crash_summary(report.read_text(errors='replace'))
            if parsed is not None:
                summary['crashes'].append(parsed)
    except OSError as error:
        summary['crashQueryError'] = str(error)
    (runner.output / (label + '-app.json')).write_text(json.dumps(summary, indent=2) + '\n')


def progress_diagnostics(runner, uuid, timeout):
    sampled = set()
    def tick(elapsed):
        for label, threshold in [('progress', min(180, timeout / 2)),
                                 ('before-timeout', max(0, timeout - 60))]:
            if elapsed >= threshold and label not in sampled:
                sampled.add(label)
                app_diagnostics(runner, uuid, label)
    return tick



def simulator_service_processes(text):
    allowed = {'com.apple.CoreSimulator.CoreSimulatorService', 'simdiskimaged'}
    result = []
    for line in text.splitlines():
        fields = line.strip().split(None, 3)
        if len(fields) == 4 and fields[0].isdigit() and Path(fields[3]).name in allowed:
            result.append({'pid': int(fields[0]), 'elapsed': fields[1],
                           'state': fields[2], 'executable': fields[3]})
    return result


def simulator_service_diagnostics(runner, label):
    # Do not start another simctl request while its first inventory is pending.
    # Filter host executable metadata in memory, without arguments/environment.
    summary = {}
    try:
        result = subprocess.run(['ps', '-axo', 'pid=,etime=,stat=,comm='],
                                capture_output=True, text=True, timeout=5, check=False)
        summary = {'exitCode': result.returncode,
                   'processes': simulator_service_processes(result.stdout)}
    except (OSError, subprocess.TimeoutExpired) as error:
        summary['queryError'] = type(error).__name__
    (runner.output / (label + '.json')).write_text(json.dumps(summary, indent=2) + '\n')


def cold_service_progress(runner):
    previous = 0
    sampled = False
    def tick(elapsed):
        nonlocal previous, sampled
        if elapsed - previous >= 15:
            previous = elapsed
            print(f'Waiting for initial CoreSimulator inventory: elapsedSeconds={int(elapsed)}', flush=True)
        if elapsed >= 30 and not sampled:
            sampled = True
            simulator_service_diagnostics(runner, 'simulator-service-startup')
    return tick


def installed_runtimes(runner, seconds):
    if not 1 <= seconds <= 300:
        raise PhaseFailure('Discovery timeout must be between 1 and 300 seconds')
    # The first simctl IPC also initializes CoreSimulator and enumerates mounted
    # runtime images. Hosted run 38958152603 was usable after ~44s, beyond the old
    # 30s budget. Allow one bounded cold discovery; do not retry failed commands.
    return json.loads(runner.command('installed-runtimes',
        ['xcrun', 'simctl', 'list', 'runtimes', '--json'], seconds,
        on_tick=cold_service_progress(runner)))



def create_ci_device(runner, selected):
    # Runner-provisioned device data can require a broken OS migration (run
    # 39010987456). Create our own empty device; never erase an existing one.
    name = 'LegadoCI-' + str(os.getpid())
    identifier = runner.command('create-ci-device', ['xcrun', 'simctl', 'create', name,
        selected['deviceTypeIdentifier'], selected['runtime']], 60).strip()
    if not re.fullmatch(r'[0-9A-Fa-f]{8}(?:-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}', identifier):
        raise PhaseFailure('simctl create did not return a valid owned device UUID')
    return {'udid': identifier, 'runtime': selected['runtime'],
            'sdkVersion': selected['sdkVersion'], 'deviceTypeIdentifier': selected['deviceTypeIdentifier'],
            'name': name, 'state': 'Shutdown', 'isAvailable': True, 'createdForAcceptance': True}


def shutdown_for_migration_recovery(runner, identifier):
    def device_state(phase):
        snapshot = json.loads(runner.command(phase,
            ['xcrun', 'simctl', 'list', 'devices', '--json'], 30))
        matches = [device for devices in snapshot['devices'].values() for device in devices
                   if device.get('udid') == identifier]
        return matches[0].get('state') if len(matches) == 1 else None

    try:
        runner.command('migration-recovery-shutdown', ['xcrun', 'simctl', 'shutdown', identifier], 60)
    except PhaseTimeout:
        # Hosted job 117350249917 timed out waiting for shutdown's process even
        # though that same device had reached Shutdown. Preserve the timeout,
        # then confirm the postcondition once; never assume a timeout succeeded.
        state = device_state('migration-recovery-state')
        if state == 'Booted':
            # Job 117620727771 remained Booted after its timed-out shutdown,
            # but the same UUID's cleanup shutdown then succeeded in 4.8s.
            # Move that one bounded compensation before the reboot. Require
            # both command success and the exact owned device's postcondition;
            # a second timeout or ambiguous state still fails this acceptance.
            runner.command('migration-recovery-shutdown-confirm',
                ['xcrun', 'simctl', 'shutdown', identifier], 30)
            state = device_state('migration-recovery-state-confirm')
        if state != 'Shutdown':
            raise
        print('Timed-out shutdown confirmed Shutdown for the same owned CI device', flush=True)


def run_selected_device(runner, selected, flutter, boot_timeout, test_timeout):
    identifier = selected['udid']
    if selected['state'] not in ('Booted', 'Booting'):
        runner.command('boot', ['xcrun', 'simctl', 'boot', identifier], 60)
    boot = runner.command('boot-ready', ['xcrun', 'simctl', 'bootstatus', identifier, '-b', '-d'], boot_timeout)
    # simctl can exit zero even when its terminal boot status is failure.
    def migration_failed(output):
        return 'Data Migration Failed' in output or re.search(r'Status=3\b', output)

    if migration_failed(boot) and selected.get('createdForAcceptance'):
        # Hosted run 39012704277 failed OS migration even on a newly created
        # device, before any app was installed. Preserve boot-ready.log, then
        # allow one bounded reboot of this owned UUID. Never retry app tests,
        # choose an older runtime, erase data or restart another simulator.
        print('Owned CI simulator migration failed; attempting one bounded reboot before app tests', flush=True)
        shutdown_for_migration_recovery(runner, identifier)
        runner.command('migration-recovery-boot', ['xcrun', 'simctl', 'boot', identifier], 60)
        boot = runner.command('migration-recovery-ready',
            ['xcrun', 'simctl', 'bootstatus', identifier, '-b', '-d'], boot_timeout)
    if migration_failed(boot):
        raise PhaseFailure('Selected iOS simulator failed data migration during boot')
    # The UUID is already selected and booted. Avoid an extra all-platform
    # Flutter scan (including physical/wireless devices); the actual test below
    # still has to discover this UUID, launch the app and execute every test.
    runner.command('flutter-test', [flutter, 'test', 'integration_test/native_app_test.dart',
        '-d', identifier, '--verbose'], test_timeout,
        on_tick=progress_diagnostics(runner, identifier, test_timeout))


def dispose_ci_device(runner, identifier):
    errors = []
    for phase, action in [('shutdown-owned-device', 'shutdown'), ('delete-owned-device', 'delete')]:
        try:
            runner.command(phase, ['xcrun', 'simctl', action, identifier], 30)
        except (PhaseFailure, OSError) as error:
            errors.append(str(error))
    if errors and sys.exc_info()[0] is None:
        raise PhaseFailure('Could not clean up the acceptance-owned simulator: ' + '; '.join(errors))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--device')
    parser.add_argument('--flutter', default='flutter')
    parser.add_argument('--discovery-timeout', type=int, default=120,
                        help='First CoreSimulator inventory budget in seconds (1..300)')
    parser.add_argument('--boot-timeout', type=int, default=180)
    parser.add_argument('--test-timeout', type=int, default=1200)
    parser.add_argument('--list-only', action='store_true')
    args = parser.parse_args()
    runner = Runner(args.output.resolve(), ROOT / 'flutter/apps/legado')
    (runner.output / 'host.json').write_text(json.dumps({
        'os': platform.system(), 'architecture': platform.machine(),
        'macOSVersion': platform.mac_ver()[0],
        'githubActions': os.environ.get('GITHUB_ACTIONS') == 'true',
    }, indent=2) + '\n')
    selected = None
    owned_device = None
    runner.save()
    try:
        if platform.system() != 'Darwin' or platform.machine().lower() not in ('arm64', 'aarch64'):
            raise PhaseFailure('iOS native acceptance requires a macOS ARM64 host')
        if not 1 <= args.discovery_timeout <= 300:
            raise PhaseFailure('Discovery timeout must be between 1 and 300 seconds')
        if args.boot_timeout < 1 or args.test_timeout < 1:
            raise PhaseFailure('Phase timeouts must be positive')
        if not args.list_only and not args.device and os.environ.get('GITHUB_ACTIONS') != 'true':
            raise PhaseFailure('Local simulator execution requires an explicit --device UUID')
        if sys.version_info < (3, 12):
            raise PhaseFailure('Python 3.12+ is required for the fixed SDK consumer')
        # Preserve setup-python's actual executable through Xcode's reduced PATH.
        os.environ.setdefault('SOURCE_V8_PYTHON', sys.executable)
        runner.command('xcode-version', ['xcodebuild', '-version'], 30)
        installed_runtimes(runner, args.discovery_timeout)
        sdk = runner.command('xcode-sdk', ['xcrun', '--sdk', 'iphonesimulator', '--show-sdk-version'], 30).strip()
        snapshot = json.loads(runner.command('available-devices',
                                            ['xcrun', 'simctl', 'list', 'devices', 'available', '--json'], 30))
        selected = choose_device(snapshot, sdk, args.device)
        if not args.list_only and not args.device and os.environ.get('GITHUB_ACTIONS') == 'true':
            selected = create_ci_device(runner, selected)
            owned_device = selected['udid']
        (runner.output / 'selected-device.json').write_text(json.dumps(selected, indent=2) + '\n')
        print('Selected simulator: ' + json.dumps(selected), flush=True)
        if args.list_only:
            return
        run_selected_device(runner, selected, args.flutter, args.boot_timeout, args.test_timeout)
    except (PhaseFailure, OSError, ValueError) as error:
        (runner.output / 'failure.txt').write_text(str(error) + '\n')
        if platform.system() == 'Darwin':
            simulator_service_diagnostics(runner, 'simulator-service-failure')
        if selected is not None:
            app_diagnostics(runner, selected['udid'], 'failure')
        for name, command in ([] if platform.system() != 'Darwin' else [
            ('diagnostic-devices', ['xcrun', 'simctl', 'list', 'devices', '--json']),
            ('diagnostic-runtimes', ['xcrun', 'simctl', 'list', 'runtimes', '--json']),
        ]):
            try:
                runner.command(name, command, 20)
            except (PhaseFailure, OSError):
                pass
        raise
    finally:
        if owned_device is not None:
            dispose_ci_device(runner, owned_device)


if __name__ == '__main__':
    try:
        main()
    except (PhaseFailure, OSError, ValueError) as error:
        raise SystemExit('iOS native acceptance failed: ' + str(error))
