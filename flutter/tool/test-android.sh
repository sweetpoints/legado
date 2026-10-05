#!/usr/bin/env bash
set -euo pipefail

workspace_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repository_root="$(cd "$workspace_root/.." && pwd)"
task_android_targets="${SOURCE_ENGINE_ANDROID_TARGETS:-android-arm64,android-x64}"
task_android_abis="$(python3 - "$workspace_root/tool" "$task_android_targets" <<'PYTHON'
import sys
sys.path.insert(0, sys.argv[1])
import importlib.util
from pathlib import Path
spec = importlib.util.spec_from_file_location('verifier', Path(sys.argv[1]) / 'verify-android-aar.py')
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)
print(','.join(verifier.ANDROID_ABIS[target] for target in verifier.configured_targets(sys.argv[2])))
PYTHON
)"
task_java_home="${SOURCE_ENGINE_JDK:-${JAVA_HOME:-}}"
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
    echo 'Set ANDROID_SERIAL to the existing ARM64 or x86_64 device/emulator to test.' >&2
    exit 2
fi

SOURCE_ENGINE_TEST_SUFFIX=.fluttertest bash "$workspace_root/tool/build-android.sh"
cd "$repository_root"
task_test_started="$(python3 -c 'import time; print(time.time())')"
task_test_regex="$(python3 "$workspace_root/tool/verify-instrumentation.py" --tests-regex)"
./gradlew :app:connectedAppDebugAndroidTest \
    "-Dorg.gradle.java.home=$task_java_home" \
    "-PflutterSourceAbis=$task_android_abis" \
    -PflutterSourceTestSuffix=.fluttertest \
    "-Pandroid.testInstrumentationRunnerArguments.tests_regex='$task_test_regex'" \
    --console=plain --max-workers=2

# Reject Gradle's occasional successful exit after installation failures, stale
# reports, and any incomplete/unknown/skipped instrumentation result.
python3 "$workspace_root/tool/verify-instrumentation.py" --since "$task_test_started"
