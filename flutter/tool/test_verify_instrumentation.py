import importlib.util
import os
import re
import shlex
import subprocess
import sys
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('instrumentation', Path(__file__).with_name('verify-instrumentation.py'))
contract = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contract)


class InstrumentationContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.required = {'example.First': {'first', 'second'}, 'example.Second': {'third'}}

    def report(self, cases, failure=None, suffix='current'):
        suite = ET.Element('testsuite')
        for identity in cases:
            case = ET.SubElement(suite, 'testcase', classname=identity[0], name=identity[1])
            if failure:
                ET.SubElement(case, failure)
        path = self.directory / f'TEST-{suffix}.xml'
        ET.ElementTree(suite).write(path)
        return path

    def cases(self):
        return [(name, method) for name, methods in self.required.items() for method in methods]

    def test_runner_regex_preserves_classes_through_agp_argument_transport(self):
        value = contract.runner_regex(self.required)
        self.assertNotIn(',', value)
        transported = dict(pair.split('=', 1) for pair in f'tests_regex={value}'.split(',') if '=' in pair)
        pattern = re.compile(transported['tests_regex'])
        for name, method in self.cases():
            self.assertTrue(pattern.search(f'{name}#{method}'))
        for unknown in ['exampleXFirst#first', 'example.FirstExtra#first', 'other.Class#first']:
            self.assertFalse(pattern.search(unknown))

    def test_literal_quotes_survive_agp_and_protect_device_shell_regex(self):
        value = contract.runner_regex(self.required)
        # test-android passes literal quotes inside the Gradle property. The
        # official engine preserves them when constructing adb shell argv.
        arguments = f"tests_regex='{value}'"
        transported = dict(pair.split('=', 1) for pair in arguments.split(',') if '=' in pair)
        command = shlex.quote(sys.executable) + ' -c ' + shlex.quote('import sys; print(sys.argv[1])')
        result = subprocess.run(['/bin/sh', '-c', command + ' ' + transported['tests_regex']],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.rstrip('\n'), value)
        for name, method in self.cases():
            self.assertTrue(re.search(result.stdout.rstrip('\n'), f'{name}#{method}'))

    def test_exact_all_classes_pass(self):
        self.report(self.cases())
        self.assertEqual(contract.verify_reports(self.directory, 0, self.required), {'example.First': 2, 'example.Second': 1})

    def test_missing_case_rejected(self):
        self.report(self.cases()[:-1])
        with self.assertRaises(ValueError):
            contract.verify_reports(self.directory, 0, self.required)

    def test_unknown_case_or_class_rejected(self):
        for unknown in [('example.First', 'unknown'), ('other.Class', 'third')]:
            self.report(self.cases() + [unknown])
            with self.assertRaises(ValueError):
                contract.verify_reports(self.directory, 0, self.required)

    def test_duplicate_case_rejected(self):
        self.report(self.cases() + self.cases()[:1])
        with self.assertRaises(ValueError):
            contract.verify_reports(self.directory, 0, self.required)

    def test_failure_error_and_skip_rejected(self):
        for tag in ['failure', 'error', 'skipped']:
            self.report(self.cases(), failure=tag)
            with self.assertRaises(ValueError):
                contract.verify_reports(self.directory, 0, self.required)

    def test_suite_failure_without_failed_testcase_rejected(self):
        path = self.report(self.cases())
        tree = ET.parse(path)
        tree.getroot().set('errors', '1')
        tree.write(path)
        with self.assertRaises(ValueError):
            contract.verify_reports(self.directory, 0, self.required)

    def test_stale_success_cannot_satisfy_current_run(self):
        path = self.report(self.cases())
        os.utime(path, (1, 1))
        with self.assertRaises(ValueError):
            contract.verify_reports(self.directory, 2, self.required)

    def test_declared_source_cases_all_have_unique_class_names(self):
        required = contract.required_cases()
        self.assertEqual(len(required), len(contract.TEST_FILES))
        self.assertTrue(all(methods for methods in required.values()))


if __name__ == '__main__':
    unittest.main()
