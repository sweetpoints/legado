#!/usr/bin/env bash
set -euo pipefail

workspace_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repository_root="$(cd "$workspace_root/.." && pwd)"
task_java_home="${SOURCE_ENGINE_JDK:-${JAVA_HOME:-}}"
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
    echo 'Set ANDROID_SERIAL to the existing ARM64 device or emulator to test.' >&2
    exit 2
fi

SOURCE_ENGINE_TEST_SUFFIX=.fluttertest bash "$workspace_root/tool/build-android.sh"
cd "$repository_root"
task_test_started="$(date +%s)"
./gradlew :app:connectedAppDebugAndroidTest \
    "-Dorg.gradle.java.home=$task_java_home" \
    -PflutterSourceEngine=true \
    -PflutterSourceTestSuffix=.fluttertest \
    -Pandroid.testInstrumentationRunnerArguments.class=io.legado.app.model.FlutterSourceEngineTest \
    --console=plain --max-workers=2

# Some connected-test installation failures return Gradle exit code zero.
# Confirm every required instrumented case ran successfully.
SOURCE_ENGINE_TEST_STARTED="$task_test_started" python3 - <<'PY'
from pathlib import Path
import os
import xml.etree.ElementTree as ET

directory = Path('app/build/outputs/androidTest-results/connected/debug/flavors/app')
cases = []
for report in directory.glob('TEST-*.xml'):
    if report.stat().st_mtime < float(os.environ['SOURCE_ENGINE_TEST_STARTED']):
        continue
    cases.extend(case for case in ET.parse(report).iter('testcase')
                 if case.get('classname') == 'io.legado.app.model.FlutterSourceEngineTest')
required = {
    'composeRepositoryRunsActualDartV8AndAsyncFunctions',
    'cancellationStopsInfiniteScriptAndEngineRemainsUsable',
    'emptyDartTocFailsWithoutChangingBookMetadata',
    'synchronousSendFailureCleansTaskAndCloseWakesPendingRequest',
    'closeDuringStartupWakesReadyWaiter',
    'legacyTocFlagsKeepLegacyTruthinessAndModernFlagsRequireBoolean',
    'volumeHeadingSkipsDartContentExecution',
    'sessionVariablesSurviveEngineShutdownAndStaySourceIsolated',
    'legacyExploreUsesSelectedCategoryAndNormalizesBookFields',
    'modernBookFieldsRemainUnchangedAndMigratedFieldsUseLegacyFormatting',
    'nonUrlLegacySourceIdsSearchAbsoluteEndpointAndKeepSessionsIsolated',
    'legacyPostTemplatesEncodeFormAndRejectUnsafeInputsBeforeHttp',
}
if not required.issubset({case.get('name') for case in cases}) or any(case.find(tag) is not None
                         for case in cases for tag in ('failure', 'error', 'skipped')):
    raise SystemExit('Flutter source engine instrumentation did not pass all cases.')
print(f'Flutter source engine instrumentation: {len(cases)} cases passed.')
PY
