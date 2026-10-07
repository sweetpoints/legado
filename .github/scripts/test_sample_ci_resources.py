import importlib.util
import json
from pathlib import Path
import signal
import subprocess
import sys
import time
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('resources', Path(__file__).with_name('sample-ci-resources.py'))
resources = importlib.util.module_from_spec(spec)
spec.loader.exec_module(resources)


class ResourcesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.proc = self.root / 'proc'
        self.proc.mkdir()
        self.cgroup = self.root / 'cgroup'
        self.cgroup.mkdir()

    def write(self, relative, text):
        file = self.proc / relative
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text)

    def test_safe_metadata_never_reads_arguments_environments_or_unrecognized_processes(self):
        self.write('12/comm', 'java')
        self.write('12/status', 'Name: java\nVmRSS: 512 kB\nVmSwap: 32 kB\nThreads: 6\n')
        self.write('13/comm', 'private-process')
        self.write('13/status', 'VmRSS: 999 kB')
        self.write('14/comm', 'qemu-system')
        self.write('14/status', 'VmRSS: 256 kB')
        real_read = resources.read
        def safe_read(path):
            self.assertNotIn(path.name, ['cmdline', 'environ'])
            self.assertNotEqual(path, self.proc / '13/status')
            return real_read(path)
        with patch.object(resources, 'read', side_effect=safe_read):
            result = resources.snapshot(self.proc, self.cgroup, {'test': self.root})
        self.assertEqual(result['javaRssKiB'], 512)
        self.assertEqual(result['emulatorRssKiB'], 256)
        self.assertEqual(result['javaProcesses'][0]['Threads'], 6)
        self.assertNotIn('private-process', json.dumps(result))
        self.assertGreater(result['disk']['test']['availableBytes'], 0)

    def test_cgroup_oom_and_swap_counters_follow_own_scope_without_exposing_scope_name(self):
        self.write('self/cgroup', '0::/private-scope')
        own = self.cgroup / 'private-scope'
        own.mkdir()
        (own / 'memory.events').write_text('oom 2\noom_kill 1\n')
        (own / 'memory.current').write_text('1234')
        self.write('vmstat', 'oom_kill 4\npgmajfault 8\nprivate_counter 10\n')
        result = resources.snapshot(self.proc, self.cgroup)
        self.assertEqual(result['cgroupCounters']['memory.events'], 'oom 2\noom_kill 1')
        self.assertEqual(result['vmCounters'], {'oom_kill': 4, 'pgmajfault': 8})
        self.assertNotIn('private-scope', json.dumps(result))

    def test_cgroup_path_cannot_escape_counter_root(self):
        self.write('self/cgroup', '0::/../../outside')
        self.assertNotIn('cgroupCounters', resources.snapshot(self.proc, self.cgroup))

    def test_disappearing_process_and_missing_kernel_files_are_tolerated(self):
        self.write('12/comm', 'java')
        result = resources.snapshot(self.proc, self.cgroup, {'gone': self.root / 'missing'})
        self.assertEqual(result['javaRssKiB'], 0)
        self.assertEqual(result['disk'], {})

    def test_stop_does_not_signal_reused_or_unidentified_pid(self):
        pidfile = self.root / 'identity.json'
        pidfile.write_text(json.dumps({'pid': 123, 'startTicks': 100}))
        with patch.object(resources, 'start_ticks', return_value=200), patch.object(resources.os, 'kill') as kill:
            resources.stop_sampler(pidfile, self.proc)
            kill.assert_not_called()
        with patch.object(resources, 'start_ticks', return_value=100), patch.object(resources.os, 'kill') as kill:
            resources.stop_sampler(pidfile, self.proc)
            kill.assert_called_once_with(123, signal.SIGTERM)

    def test_cli_flushes_periodic_samples_and_closes_cleanly_on_signal(self):
        output = self.root / 'samples.jsonl'
        process = subprocess.Popen([
            sys.executable, str(Path(__file__).with_name('sample-ci-resources.py')),
            str(output), '--interval', '0.05',
        ], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        try:
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                if output.exists() and len(output.read_text().splitlines()) >= 2:
                    break
                time.sleep(0.02)
            process.terminate()
            _, stderr = process.communicate(timeout=5)
            self.assertEqual(process.returncode, 0, stderr.decode())
            samples = [json.loads(line) for line in output.read_text().splitlines()]
            self.assertGreaterEqual(len(samples), 2)
            self.assertTrue(all('javaRssKiB' in sample for sample in samples))
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()

    def test_proc_stat_with_parentheses_keeps_correct_process_identity(self):
        self.write('12/stat', '12 (odd (process) name) S ' + ' '.join(str(n) for n in range(4, 24)))
        self.assertEqual(resources.start_ticks(self.proc, 12), 22)


if __name__ == '__main__':
    unittest.main()
