#!/usr/bin/env python3
"""Bind migrated V8 instrumentation results to every method in the current source."""
import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = Path('app/src/androidTest/java/io/legado/app')
TEST_FILES = (
    'V8ApplicationScriptTest.kt',
    'model/CryptoJsV8CompatibilityTest.kt',
    'help/book/BookExportFileNameV8Test.kt',
    'model/analyzeRule/AnalyzeUrlV8TemplateGoldenTest.kt',
    'model/analyzeRule/AnalyzeRuleElementsNormalizationTest.kt',
    'ui/replace/edit/ReplacePreviewV8Test.kt',
    'model/analyzeRule/ReviewRuleParserV8Test.kt',
    'data/repository/AutoTaskDebugRepositoryTest.kt',
    'data/repository/MainRssRepositoryTest.kt',
    'ui/book/source/edit/JsSourceEditAcceptedIoTest.kt',
    'model/sourceEngine/LegacyPipelineHooksV8IntegrationTest.kt',
    'model/sourceEngine/LegacySourceAppEntryTest.kt',
    'help/source/ExploreScriptV8IntegrationTest.kt',
    'model/sourceEngine/LegacyBookVariableScopeIntegrationTest.kt',
    'model/sourceEngine/LegacyWebJsAppIntegrationTest.kt',
    'model/sourceEngine/LegacyRuleV8IntegrationTest.kt',
    'model/sourceEngine/LegacyCookieV8IntegrationTest.kt',
    'model/sourceEngine/LegacyDomV8IntegrationTest.kt',
    'model/sourceEngine/LegacyHostFailureV8IntegrationTest.kt',
    'model/sourceEngine/LegacyHttpDispatcherContextIntegrationTest.kt',
    'model/sourceEngine/LegacyAuxiliaryFacadeV8IntegrationTest.kt',
    'model/sourceEngine/LegacyDynamicHttpHeaderV8IntegrationTest.kt',
    'model/sourceEngine/LegacyNestedHttpScriptV8IntegrationTest.kt',
    'model/sourceEngine/LegacyCacheV8IntegrationTest.kt',
    'model/sourceEngine/LegacyOrgJsoupV8IntegrationTest.kt',
    'model/sourceEngine/LegacyOrgConnectionV8IntegrationTest.kt',
    'model/sourceEngine/LegacySourceRuntimeClearV8IntegrationTest.kt',
    'help/source/LegacyOrgExploreMenuV8IntegrationTest.kt',
    'model/sourceEngine/LegacyCoverImageV8IntegrationTest.kt',
    'model/sourceEngine/LegacyTocPaginationV8IntegrationTest.kt',
)
# Deliberately reviewed cardinalities: regeneration must not silently bless lost cases.
EXPECTED_CASE_COUNTS = {
    'V8ApplicationScriptTest.kt': 16,
    'model/CryptoJsV8CompatibilityTest.kt': 10,
    'help/book/BookExportFileNameV8Test.kt': 5,
    'model/analyzeRule/AnalyzeUrlV8TemplateGoldenTest.kt': 5,
    'model/analyzeRule/AnalyzeRuleElementsNormalizationTest.kt': 1,
    'ui/replace/edit/ReplacePreviewV8Test.kt': 3,
    'model/analyzeRule/ReviewRuleParserV8Test.kt': 11,
    'data/repository/AutoTaskDebugRepositoryTest.kt': 5,
    'data/repository/MainRssRepositoryTest.kt': 6,
    'ui/book/source/edit/JsSourceEditAcceptedIoTest.kt': 4,
    'model/sourceEngine/LegacyPipelineHooksV8IntegrationTest.kt': 3,
    'model/sourceEngine/LegacySourceAppEntryTest.kt': 4,
    'help/source/ExploreScriptV8IntegrationTest.kt': 3,
    'model/sourceEngine/LegacyBookVariableScopeIntegrationTest.kt': 3,
    'model/sourceEngine/LegacyWebJsAppIntegrationTest.kt': 2,
    'model/sourceEngine/LegacyRuleV8IntegrationTest.kt': 4,
    'model/sourceEngine/LegacyCookieV8IntegrationTest.kt': 2,
    'model/sourceEngine/LegacyDomV8IntegrationTest.kt': 2,
    'model/sourceEngine/LegacyHostFailureV8IntegrationTest.kt': 1,
    'model/sourceEngine/LegacyHttpDispatcherContextIntegrationTest.kt': 2,
    'model/sourceEngine/LegacyAuxiliaryFacadeV8IntegrationTest.kt': 3,
    'model/sourceEngine/LegacyDynamicHttpHeaderV8IntegrationTest.kt': 3,
    'model/sourceEngine/LegacyNestedHttpScriptV8IntegrationTest.kt': 4,
    'model/sourceEngine/LegacyCacheV8IntegrationTest.kt': 2,
    'model/sourceEngine/LegacyOrgJsoupV8IntegrationTest.kt': 7,
    'model/sourceEngine/LegacyOrgConnectionV8IntegrationTest.kt': 9,
    'model/sourceEngine/LegacySourceRuntimeClearV8IntegrationTest.kt': 2,
    'help/source/LegacyOrgExploreMenuV8IntegrationTest.kt': 3,
    'model/sourceEngine/LegacyCoverImageV8IntegrationTest.kt': 3,
    'model/sourceEngine/LegacyTocPaginationV8IntegrationTest.kt': 2,
    'model/FlutterSourceEngineTest.kt': 21,
    'ui/book/source/edit/BookSourceMigrationUiTest.kt': 5,
    'model/jsSource/JsSourceReviewV8Test.kt': 13,
    'model/login/FlutterLoginUiV2Test.kt': 6,
    'model/JsSourceV8ExecutionTest.kt': 14,
    'model/CryptoMigrationContractTest.kt': 5,
    'web/mcp/FlutterMcpSourceTest.kt': 2,
}
DEFAULT_MANIFEST = Path(__file__).with_name('v8-migration-instrumentation.json')
spec = importlib.util.spec_from_file_location('original_instrumentation', Path(__file__).with_name('verify-instrumentation.py'))
original = importlib.util.module_from_spec(spec)
spec.loader.exec_module(original)


def kotlin_code(source):
    """Mask Kotlin comments and literals so examples cannot declare fake tests."""
    pattern = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')
    return pattern.sub(lambda match: ''.join('\n' if character == '\n' else ' ' for character in match[0]), source)


def class_record(relative, root):
    path = SOURCE_ROOT / relative
    data = (root / path).read_bytes()
    code = kotlin_code(data.decode('utf-8'))
    package = re.search(r'^package\s+([\w.]+)', code, re.MULTILINE)
    classes = re.findall(r'^\s*(?:internal\s+)?class\s+(\w+)', code, re.MULTILINE)
    methods = re.findall(r'@Test\s+(?:public\s+)?fun\s+([A-Za-z_]\w*)\s*\(\s*\)', code)
    annotations = re.findall(r'@Test\b', code)
    if not package or len(classes) != 1 or not methods or len(methods) != len(annotations):
        raise ValueError(f'Every @Test must be an explicit zero-argument DEX-safe method: {path}')
    if len(set(methods)) != len(methods) or re.search(r'@Ignore\b', code):
        raise ValueError(f'Duplicate or ignored instrumentation declaration: {path}')
    if len(methods) != EXPECTED_CASE_COUNTS.get(relative):
        raise ValueError(f'Instrumentation case count differs from reviewed contract: {path}')
    if classes[0] != Path(relative).stem:
        raise ValueError(f'Instrumentation class does not match reviewed source filename: {path}')
    return {'name': f'{package[1]}.{classes[0]}', 'source': path.as_posix(),
            'sourceSha256': hashlib.sha256(data).hexdigest(), 'methods': sorted(methods)}


def manifest(include_existing=False, root=ROOT):
    files = TEST_FILES + (original.TEST_FILES if include_existing else ())
    records = [class_record(path, root) for path in files]
    names = [record['name'] for record in records]
    if len(set(names)) != len(names):
        raise ValueError('Duplicate class in instrumentation source inventory')
    return {'schemaVersion': 1, 'includeExisting': include_existing,
            'caseCount': sum(len(record['methods']) for record in records), 'classes': records}


def required_cases(document):
    return {record['name']: set(record['methods']) for record in document['classes']}


def runner_regex(document):
    # No commas: AGP serializes runner options as a comma-separated map. Only
    # exact Class#method identities are selected, including every declared case.
    return '^(?:' + '|'.join(re.escape(record['name']) + '#(?:' +
                           '|'.join(re.escape(method) for method in record['methods']) + ')'
                           for record in document['classes']) + ')$'


def validate_manifest(document, root=ROOT):
    if not isinstance(document, dict):
        raise ValueError('Instrumentation manifest must be a JSON object')
    if type(document.get('includeExisting')) is not bool:
        raise ValueError('Manifest requires an explicit includeExisting Boolean')
    if document != manifest(document['includeExisting'], root):
        raise ValueError('Instrumentation manifest differs from current source; regenerate before building/running')


def verify_compiled_methods(directory, document):
    """JUnit4 requires public instance void methods; invalid classes may be filtered out."""
    checked = 0
    for record in document['classes']:
        compiled = directory / (record['name'].replace('.', '/') + '.class')
        if not compiled.is_file():
            raise ValueError('Compiled instrumentation class missing: ' + record['name'])
        result = subprocess.run(['javap', '-p', str(compiled)], capture_output=True, text=True, timeout=15)
        if result.returncode:
            raise ValueError('Cannot inspect compiled instrumentation class: ' + record['name'])
        signatures = set(re.findall(r'^\s+public\s+(?:final\s+)?void\s+(\w+)\(\);$', result.stdout, re.MULTILINE))
        for method in record['methods']:
            if method not in signatures:
                raise ValueError('JUnit4 test must compile to a public instance void method: ' + record['name'] + '#' + method)
            checked += 1
    return checked


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write-manifest', type=Path)
    parser.add_argument('--include-existing', action='store_true', help='Generate a combined inventory with the original engine/UI classes')
    parser.add_argument('--manifest', type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument('--compiled-classes', type=Path, help='Verify every declared test is a compiled public instance void method')
    parser.add_argument('--verify-compiled-only', action='store_true')
    parser.add_argument('--classes', action='store_true')
    parser.add_argument('--tests-regex', action='store_true')
    parser.add_argument('--since', type=float)
    parser.add_argument('--reports', type=Path, default=ROOT / 'app/build/outputs/androidTest-results/connected/debug/flavors/app')
    args = parser.parse_args()
    if args.write_manifest:
        document = manifest(args.include_existing)
        args.write_manifest.parent.mkdir(parents=True, exist_ok=True)
        args.write_manifest.write_text(json.dumps(document, indent=2) + '\n')
        print(f"Wrote {document['caseCount']} cases across {len(document['classes'])} source-bound classes to {args.write_manifest}.")
        return
    if args.include_existing:
        parser.error('--include-existing is only used with --write-manifest; execute with the generated --manifest')
    document = json.loads(args.manifest.read_text())
    validate_manifest(document)
    compiled_count = verify_compiled_methods(args.compiled_classes, document) if args.compiled_classes else None
    if args.verify_compiled_only:
        if args.compiled_classes is None:
            parser.error('--verify-compiled-only requires --compiled-classes')
        print(json.dumps({'compiledMethodCount': compiled_count, 'deviceExecuted': False}, indent=2))
        return
    if args.tests_regex:
        print(runner_regex(document))
        return
    if args.classes:
        print(','.join(record['name'] for record in document['classes']))
        return
    if args.since is None or not math.isfinite(args.since) or args.since <= 0:
        parser.error('--since requires a positive finite run-start Unix timestamp to reject stale reports')
    counts = original.verify_reports(args.reports, args.since, required_cases(document))
    print(json.dumps({'verified': True, 'caseCount': sum(counts.values()), 'classes': counts,
                      'manifestSha256': hashlib.sha256(args.manifest.read_bytes()).hexdigest()}, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, ET.ParseError, subprocess.TimeoutExpired) as error:
        raise SystemExit(str(error))
