#!/usr/bin/env python3
"""Safe Linux resource counters for builds and UI tests, without process args/env.

Only kernel counters and recognized Java/emulator process numeric metadata are read.
The positional output interface used by run-ui-regression.sh remains compatible.
"""
import argparse
import json
import os
from pathlib import Path
import re
import signal
import threading
import time


def read(path):
    try:
        return path.read_text(encoding='utf-8').strip()
    except (OSError, UnicodeError):
        return None


def numeric_status(text):
    return {key: int(value) for key, value in re.findall(
        r'^(VmRSS|VmHWM|VmSwap|Threads):\s*(\d+)', text or '', re.M)}


def start_ticks(proc, pid):
    text = read(proc / str(pid) / 'stat')
    # comm can contain spaces and parentheses; fields after its final ')' are numeric.
    try:
        return int(text.rsplit(')', 1)[1].split()[19])
    except (AttributeError, IndexError, ValueError):
        return None


def snapshot(proc=Path('/proc'), cgroup=Path('/sys/fs/cgroup'), disk_paths=None):
    sample = {'time': time.time(), 'cpuCount': os.cpu_count()}
    for name in ('meminfo', 'loadavg', 'pressure/cpu', 'pressure/memory', 'pressure/io'):
        value = read(proc / name)
        if value is not None:
            sample['/proc/' + name] = value
    vmstat = read(proc / 'vmstat') or ''
    sample['vmCounters'] = {name: int(value) for name, value in re.findall(
        r'^(oom_kill|pgmajfault|pswpin|pswpout) (\d+)$', vmstat, re.M)}
    java = []
    emulator_rss = 0
    for process in proc.glob('[0-9]*'):
        name = read(process / 'comm')
        if name != 'java' and not (name or '').startswith(('qemu', 'emulator')):
            continue
        status = numeric_status(read(process / 'status'))
        if name == 'java':
            java.append({'pid': int(process.name), **status,
                         'startTicks': start_ticks(proc, process.name)})
        else:
            emulator_rss += status.get('VmRSS', 0)
    sample['javaProcesses'] = sorted(java, key=lambda item: item['pid'])
    sample['javaRssKiB'] = sum(item.get('VmRSS', 0) for item in java)
    sample['emulatorRssKiB'] = emulator_rss
    # Read the sampler's own cgroup when available, without exposing its path/name.
    relative = next((line[3:] for line in (read(proc / 'self/cgroup') or '').splitlines()
                     if line.startswith('0::')), '/')
    own_cgroup = (cgroup / relative.lstrip('/')).resolve()
    root = cgroup.resolve()
    if own_cgroup.is_relative_to(root):
        counters = {}
        for name in ('memory.current', 'memory.peak', 'memory.max', 'memory.events',
                     'memory.swap.current', 'memory.swap.max', 'cpu.stat', 'io.stat'):
            value = read(own_cgroup / name)
            if value is not None:
                counters[name] = value
        sample['cgroupCounters'] = counters
    sample['disk'] = {}
    for label, path in (disk_paths or {'workspace': Path.cwd()}).items():
        try:
            stats = os.statvfs(path)
            sample['disk'][label] = {
                'totalBytes': stats.f_blocks * stats.f_frsize,
                'availableBytes': stats.f_bavail * stats.f_frsize,
                'availableInodes': stats.f_favail,
            }
        except OSError:
            pass
    return sample


def stop_sampler(pid_file, proc=Path('/proc')):
    try:
        identity = json.loads(pid_file.read_text())
        pid = int(identity['pid'])
        expected = identity['startTicks']
        if pid > 1 and expected is not None and start_ticks(proc, pid) == expected:
            os.kill(pid, signal.SIGTERM)
    except (OSError, ValueError, KeyError, TypeError):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path, nargs='?')
    parser.add_argument('--interval', type=float, default=10)
    parser.add_argument('--pid-file', type=Path)
    parser.add_argument('--stop', type=Path)
    parser.add_argument('--once', action='store_true')
    args = parser.parse_args()
    if args.stop:
        stop_sampler(args.stop)
        return
    if args.output is None or args.interval <= 0:
        parser.error('output and positive interval required')
    stop = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.pid_file:
        args.pid_file.write_text(json.dumps({'pid': os.getpid(),
                                             'startTicks': start_ticks(Path('/proc'), os.getpid())}))
    disk_paths = {'workspace': Path.cwd(), 'output': args.output.parent}
    with args.output.open('w', encoding='utf-8') as output:
        while True:
            output.write(json.dumps(snapshot(disk_paths=disk_paths)) + '\n')
            output.flush()
            if args.once or stop.wait(args.interval):
                break


if __name__ == '__main__':
    main()
