#!/usr/bin/env python3
"""Verify every declared Flutter/V8 Android case without accepting partial runs."""
import argparse
from collections import Counter
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
TEST_FILES = (
    'model/FlutterSourceEngineTest.kt',
    'ui/book/source/edit/BookSourceMigrationUiTest.kt',
    'model/jsSource/JsSourceReviewV8Test.kt',
    'model/login/FlutterLoginUiV2Test.kt',
    'model/JsSourceV8ExecutionTest.kt',
    'model/CryptoMigrationContractTest.kt',
    'web/mcp/FlutterMcpSourceTest.kt',
)


def required_cases(root=ROOT):
    result = {}
    for relative in TEST_FILES:
        source = (root / 'app/src/androidTest/java/io/legado/app' / relative).read_text()
        package = re.search(r'^package\s+([\w.]+)', source, re.MULTILINE)
        name = re.search(r'\bclass\s+(\w+)', source)
        methods = re.findall(r'@Test\s+fun\s+(\w+)\s*\(', source)
        if not package or not name or not methods or len(set(methods)) != len(methods):
            raise ValueError('Instrumentation source must declare a class and unique @Test methods')
        result[f'{package[1]}.{name[1]}'] = set(methods)
    return result


def runner_regex(required):
    # AGP 9.4's official Android test engine serializes runner options as a
    # comma-separated map. A comma-separated class value is therefore truncated.
    # AndroidJUnitRunner supports tests_regex matching ClassName#methodName.
    return '^(?:' + '|'.join(re.escape(name) for name in required) + ')#.*$'


def verify_reports(directory, since, required):
    expected = {(name, method) for name, methods in required.items() for method in methods}
    found = Counter()
    failed = False
    for report in directory.rglob('TEST-*.xml'):
        if report.stat().st_mtime < since:
            continue
        tree = ET.parse(report)
        for suite in tree.iter('testsuite'):
            if any(int(suite.get(field, '0')) != 0 for field in ('errors', 'failures', 'skipped')):
                failed = True
        for case in tree.iter('testcase'):
            identity = (case.get('classname'), case.get('name'))
            found[identity] += 1
            if any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
                failed = True
    if failed or set(found) != expected or any(count != 1 for count in found.values()):
        raise ValueError('Instrumentation must pass each declared case exactly once; missing, unknown, duplicate or unsuccessful cases rejected')
    return {name: len(methods) for name, methods in required.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classes', action='store_true')
    parser.add_argument('--tests-regex', action='store_true')
    parser.add_argument('--since', type=float)
    parser.add_argument('--reports', type=Path, default=ROOT / 'app/build/outputs/androidTest-results/connected/debug/flavors/app')
    args = parser.parse_args()
    required = required_cases()
    if args.tests_regex:
        print(runner_regex(required))
        return
    if args.classes:
        print(','.join(required))
        return
    if args.since is None:
        parser.error('--since is required to exclude stale reports')
    counts = verify_reports(args.reports, args.since, required)
    print(f'Flutter/V8 instrumentation: {sum(counts.values())} cases passed across {len(counts)} classes.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, ET.ParseError) as error:
        raise SystemExit(str(error))
