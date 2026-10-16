#!/usr/bin/env python3
"""Run main-app acceptance with explicit, bounded iOS simulator phases.

Does not edit the application, erase simulators, cancel GitHub jobs or retry tests.
Automatic device selection is allowed only on GitHub's disposable runner; local
execution requires an explicit --device. --list-only is entirely read-only.
"""
import argparse
import datetime
import json
import os
from pathlib import Path
import platform
import re
import signal
import subprocess
import sys
import selectors
import time

ROOT = Path(__file__).resolve().parents[2]


class PhaseFailure(RuntimeError):
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

    def command(self, phase, argv, seconds):
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
        deadline = time.monotonic() + seconds
        selector = selectors.DefaultSelector()
        for pipe, stdout in ((process.stdout, True), (process.stderr, False)):
            os.set_blocking(pipe.fileno(), False)
            selector.register(pipe, selectors.EVENT_READ, stdout)
        try:
            with log.open('wb') as stream:
                while selector.get_map():
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
            raise PhaseFailure(f'{phase} timed out after {seconds}s; see {log}')
        if result['exitCode'] != 0:
            raise PhaseFailure(f'{phase} exited {result["exitCode"]}; see {log}')
        return b''.join(output).decode('utf-8')

    def save(self):
        (self.output / 'phases.json').write_text(json.dumps(self.phases, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--device')
    parser.add_argument('--flutter', default='flutter')
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
    runner.save()
    try:
        if platform.system() != 'Darwin' or platform.machine().lower() not in ('arm64', 'aarch64'):
            raise PhaseFailure('iOS native acceptance requires a macOS ARM64 host')
        if args.boot_timeout < 1 or args.test_timeout < 1:
            raise PhaseFailure('Phase timeouts must be positive')
        if not args.list_only and not args.device and os.environ.get('GITHUB_ACTIONS') != 'true':
            raise PhaseFailure('Local simulator execution requires an explicit --device UUID')
        if sys.version_info < (3, 12):
            raise PhaseFailure('Python 3.12+ is required for the fixed SDK consumer')
        # Preserve setup-python's actual executable through Xcode's reduced PATH.
        os.environ.setdefault('SOURCE_V8_PYTHON', sys.executable)
        sdk = runner.command('xcode-sdk', ['xcrun', '--sdk', 'iphonesimulator', '--show-sdk-version'], 30).strip()
        snapshot = json.loads(runner.command('available-devices',
                                            ['xcrun', 'simctl', 'list', 'devices', 'available', '--json'], 30))
        selected = choose_device(snapshot, sdk, args.device)
        (runner.output / 'selected-device.json').write_text(json.dumps(selected, indent=2) + '\n')
        print('Selected simulator: ' + json.dumps(selected), flush=True)
        if args.list_only:
            return
        uuid = selected['udid']
        if selected['state'] not in ('Booted', 'Booting'):
            runner.command('boot', ['xcrun', 'simctl', 'boot', uuid], 60)
        runner.command('boot-ready', ['xcrun', 'simctl', 'bootstatus', uuid, '-b'], args.boot_timeout)
        listing = json.loads(runner.command('flutter-devices',
            [args.flutter, 'devices', '--machine', '--device-timeout', '30'], 60))
        if not any(item.get('id') == uuid and item.get('isSupported') is True for item in listing):
            raise PhaseFailure('Booted simulator is not discoverable as a supported Flutter device')
        runner.command('flutter-test', [args.flutter, 'test', 'integration_test/native_app_test.dart',
                                      '-d', uuid], args.test_timeout)
    except (PhaseFailure, OSError, ValueError) as error:
        (runner.output / 'failure.txt').write_text(str(error) + '\n')
        for name, command in ([] if platform.system() != 'Darwin' else [
            ('diagnostic-devices', ['xcrun', 'simctl', 'list', 'devices', '--json']),
            ('diagnostic-runtimes', ['xcrun', 'simctl', 'list', 'runtimes', '--json']),
        ]):
            try:
                runner.command(name, command, 20)
            except (PhaseFailure, OSError):
                pass
        raise


if __name__ == '__main__':
    try:
        main()
    except (PhaseFailure, OSError, ValueError) as error:
        raise SystemExit('iOS native acceptance failed: ' + str(error))
