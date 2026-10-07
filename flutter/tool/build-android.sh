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
if [[ -z "$task_java_home" || ! -x "$task_java_home/bin/java" ]]; then
    echo 'Set SOURCE_ENGINE_JDK to a JDK 21 installation.' >&2
    exit 2
fi

case "${1:-debug}" in
    debug)
        task_gradle_tasks=(:app:assembleAppDebug :app:assembleAppDebugAndroidTest)
        ;;
    --release)
        task_gradle_tasks=(:app:assembleAppRelease)
        ;;
    *)
        echo 'Usage: build-android.sh [--release]' >&2
        exit 2
        ;;
esac

bash "$workspace_root/tool/prepare-android-aar.sh" "${1:-debug}"
(
    cd "$repository_root"
    ./gradlew "${task_gradle_tasks[@]}" \
        "-Dorg.gradle.java.home=$task_java_home" \
    "-PflutterSourceAbis=$task_android_abis" \
        "-PflutterSourceTestSuffix=${SOURCE_ENGINE_TEST_SUFFIX:-.debug}" \
        --console=plain --max-workers=2
)
