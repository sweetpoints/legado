"""Run the shell orchestrator with fake build/Gradle processes, never real Gradle."""
import fnmatch
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('combined_inventory', HERE / 'verify-v8-migration-instrumentation.py')
combined = importlib.util.module_from_spec(spec); spec.loader.exec_module(combined)


class CombinedRunnerTests(unittest.TestCase):
    def fixture(self, directory, mode):
        root = Path(directory); tools = root / 'flutter/tool'; tools.mkdir(parents=True)
        for name in ['test-android.sh', 'verify-v8-migration-instrumentation.py', 'verify-instrumentation.py', 'verify-android-aar.py']:
            shutil.copyfile(HERE / name, tools / name)
        # Only target/ABI parsing is needed; the fake build never prepares native artifacts.
        (tools / 'v8').mkdir(); shutil.copyfile(HERE / 'v8/build.py', tools / 'v8/build.py')
        for record in combined.manifest(True)['classes']:
            source = root / record['source']; source.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(combined.ROOT / record['source'], source)
        original = set(combined.original.required_cases())
        records = combined.manifest(True)['classes']
        cases = [(r['name'], m) for r in records for m in r['methods']]
        (root / 'compiled-cases.json').write_text(json.dumps(cases))
        classes = root / 'app/build/intermediates/built_in_kotlinc/appDebugAndroidTest/compileAppDebugAndroidTestKotlin/classes'
        for record in records:
            compiled = classes / (record['name'].replace('.', '/') + '.class')
            compiled.parent.mkdir(parents=True, exist_ok=True)
            compiled.write_bytes(b'fixture-only; not executable bytecode')
        binaries = root / 'fixture-bin'; binaries.mkdir()
        javap = binaries / 'javap'
        javap.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
root=pathlib.Path(os.environ['FIXTURE_ROOT'])
classes=root/'app/build/intermediates/built_in_kotlinc/appDebugAndroidTest/compileAppDebugAndroidTestKotlin/classes'
name='.'.join(pathlib.Path(sys.argv[-1]).relative_to(classes).with_suffix('').parts)
cases=json.loads((root/'compiled-cases.json').read_text())
for class_name, method in cases:
    if class_name == name:
        result='java.lang.Exception' if os.environ['FIXTURE_MODE']=='non-void' and (class_name,method)==tuple(cases[0]) else 'void'
        print('  public final '+result+' '+method+'();')
''')
        javap.chmod(0o755)
        if mode == 'old-only': cases = [(name, method) for name, method in cases if name in original]
        (root / 'cases.json').write_text(json.dumps(cases))
        (tools / 'build-android.sh').write_text('#!/usr/bin/env bash\nexit 0\n')
        wrapper = root / 'gradlew'
        wrapper.write_text('''#!/usr/bin/env python3
import json, os, pathlib, re, sys, xml.etree.ElementTree as ET
root=pathlib.Path(__file__).parent
(root/'device-run-started').write_text('fixture only')
option=next(a for a in sys.argv if a.startswith('-Pandroid.testInstrumentationRunnerArguments.tests_regex='))
value=option.split('=',1)[1]
assert value.startswith("'") and value.endswith("'")
pattern=re.compile(value[1:-1])
cases=json.loads((root/'cases.json').read_text())
suite=ET.Element('testsuite')
for name, method in cases:
    if pattern.fullmatch(name+'#'+method): ET.SubElement(suite,'testcase',classname=name,name=method)
reports=root/'app/build/outputs/androidTest-results/connected/debug/flavors/app'
reports.mkdir(parents=True)
ET.ElementTree(suite).write(reports/'TEST-current.xml')
if os.environ['FIXTURE_MODE']=='changed-source':
    path=root/os.environ['FIXTURE_FIRST_SOURCE']
    path.write_text(path.read_text()+'\\n// changed after runner selection\\n')
''')
        wrapper.chmod(0o755)
        env = dict(os.environ, ANDROID_SERIAL='fixture-only', FIXTURE_MODE=mode,
                   FIXTURE_FIRST_SOURCE=records[0]['source'], FIXTURE_ROOT=str(root),
                   PATH=str(binaries)+os.pathsep+os.environ['PATH'])
        return subprocess.run(['bash', str(tools / 'test-android.sh')], env=env,
                              capture_output=True, text=True)

    def test_combined_runner_selects_and_verifies_every_declared_case(self):
        with tempfile.TemporaryDirectory() as d:
            result = self.fixture(d, 'complete')
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('"verified": true', result.stdout)
            self.assertIn(f'"caseCount": {combined.manifest(True)["caseCount"]}', result.stdout)

    def test_old_engine_only_success_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            result = self.fixture(d, 'old-only')
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('each declared case exactly once', result.stderr)

    def test_source_change_during_run_invalidates_complete_xml(self):
        with tempfile.TemporaryDirectory() as d:
            result = self.fixture(d, 'changed-source')
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('manifest differs from current source', result.stderr)

    def test_non_void_compiled_test_is_rejected_before_device_execution(self):
        with tempfile.TemporaryDirectory() as d:
            result = self.fixture(d, 'non-void')
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('public instance void method', result.stderr)
            self.assertFalse((Path(d) / 'device-run-started').exists())

    def test_reviewed_case_counts_and_real_webjs_class_are_locked(self):
        document = combined.manifest(True)
        self.assertEqual(document['caseCount'], 163)
        self.assertEqual(len(document['classes']), 29)
        self.assertEqual(sum(combined.EXPECTED_CASE_COUNTS.values()), 163)
        self.assertIn('io.legado.app.model.sourceEngine.LegacyWebJsAppIntegrationTest',
                      {record['name'] for record in document['classes']})
        self.assertFalse(any(Path(record['source']).name == 'PublicSourceCorpusTest.kt'
                             for record in document['classes']))

    def test_removing_test_method_cannot_be_blessed_by_regeneration(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            relative = combined.TEST_FILES[0]
            source = root / combined.SOURCE_ROOT / relative
            source.parent.mkdir(parents=True)
            text = (combined.ROOT / combined.SOURCE_ROOT / relative).read_text()
            source.write_text(text.replace('@Test', '@NotATest', 1))
            with self.assertRaisesRegex(ValueError, 'case count differs'):
                combined.class_record(relative, root)

    def test_workflow_trigger_covers_combined_tests_and_script_helpers(self):
        text = (combined.ROOT / '.github/workflows/flutter-source-engine.yml').read_text()
        patterns = re.findall(r"^      - '([^']+)'$", text, re.MULTILINE)
        paths = [r['source'] for r in combined.manifest(True)['classes']] + [
            'app/src/main/java/io/legado/app/help/JsExtensions.kt',
            'app/src/main/java/io/legado/app/help/book/BookExtensions.kt',
            'app/src/main/java/io/legado/app/help/source/RssSourceExtensions.kt',
            'app/src/main/java/io/legado/app/model/SharedJsScope.kt',
            'app/src/main/java/io/legado/app/model/analyzeRule/ReviewRuleParser.kt',
            'app/src/main/java/io/legado/app/ui/replace/edit/ReplacePreview.kt',
        ]
        for path in paths:
            with self.subTest(path=path):
                self.assertTrue(any(fnmatch.fnmatchcase(path, pattern) for pattern in patterns))
