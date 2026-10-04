#!/usr/bin/env python3
"""Fail CI if its UI suite silently omits any configured test class."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

suite = Path('app/src/androidTest/java/io/legado/app/ci/UiRegressionSuite.kt')
expected = set(re.findall(r'(io\.legado\.[\w.]+)::class', suite.read_text()))
assert expected, 'UI suite has no test classes'
reports = list(Path('app/build/outputs/androidTest-results/connected').rglob('TEST-*.xml'))
assert reports, 'No instrumentation XML reports produced'
cases = [case for report in reports for case in ET.parse(report).iter('testcase')]
actual = {case.attrib['classname'] for case in cases}
missing = expected - actual
assert not missing, f'UI classes not executed: {sorted(missing)}'
assert not any(list(case.iter('failure')) or list(case.iter('error')) for case in cases), 'UI tests failed'
print(f'UI_COVERAGE_PASSED: {len(expected)} configured classes, {len(cases)} test results')
