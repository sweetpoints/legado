#!/usr/bin/env python3
"""Sample Linux host memory/pressure and aggregate JVM/emulator RSS during UI tests.

Read host counters only: never process arguments, environments, application content,
credentials, or private configuration. These observations distinguish resource pressure
from functional failures without changing test assertions or retrying failed tests.
"""
import json
from pathlib import Path
import re
import signal
import sys
import threading
import time

stop = threading.Event()
signal.signal(signal.SIGTERM, lambda *_: stop.set())
signal.signal(signal.SIGINT, lambda *_: stop.set())
path = Path(sys.argv[1])
path.parent.mkdir(parents=True, exist_ok=True)
with path.open('w', encoding='utf-8') as output:
    while not stop.is_set():
        sample = {'time': time.time()}
        for source in ('/proc/meminfo', '/proc/pressure/cpu', '/proc/pressure/memory', '/proc/pressure/io'):
            file = Path(source)
            if file.is_file():
                sample[source] = file.read_text()
        java_rss = emulator_rss = 0
        for process in Path('/proc').glob('[0-9]*'):
            try:
                name = (process / 'comm').read_text().strip()
                rss = re.search(r'^VmRSS:\s*(\d+)', (process / 'status').read_text(), re.M)
                if rss and name == 'java':
                    java_rss += int(rss[1])
                elif rss and (name.startswith('qemu') or name.startswith('emulator')):
                    emulator_rss += int(rss[1])
            except OSError:
                pass
        sample['javaRssKiB'] = java_rss
        sample['emulatorRssKiB'] = emulator_rss
        output.write(json.dumps(sample) + '\n')
        output.flush()
        stop.wait(10)
