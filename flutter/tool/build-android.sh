#!/usr/bin/env bash
set -euo pipefail

workspace_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repository_root="$(cd "$workspace_root/.." && pwd)"
task_java_home="${SOURCE_ENGINE_JDK:-${JAVA_HOME:-}}"
if [[ -z "$task_java_home" || ! -x "$task_java_home/bin/java" ]]; then
    echo 'Set SOURCE_ENGINE_JDK to a JDK 21 installation.' >&2
    exit 2
fi

case "${1:-debug}" in
    debug)
        task_flutter_arguments=(--no-profile --no-release)
        task_gradle_tasks=(:app:assembleAppDebug :app:assembleAppDebugAndroidTest)
        ;;
    --release)
        task_flutter_arguments=(--no-debug --no-profile)
        task_gradle_tasks=(:app:assembleAppRelease)
        ;;
    *)
        echo 'Usage: build-android.sh [--release]' >&2
        exit 2
        ;;
esac

# Use Gradle's supported init.d mechanism without changing generated .android
# files or the user's global initialization scripts/properties.
source_gradle_home="$workspace_root/.gradle-source-host"
mkdir -p "$source_gradle_home/init.d"
# Reuse only public build artifacts. Never copy properties, init scripts or credentials.
source_default_gradle_home="${GRADLE_USER_HOME:-$HOME/.gradle}"
for source_cache_directory in caches wrapper; do
    if [[ "$source_default_gradle_home" != "$source_gradle_home" && \
          -d "$source_default_gradle_home/$source_cache_directory" && \
          ! -e "$source_gradle_home/$source_cache_directory" && \
          ! -L "$source_gradle_home/$source_cache_directory" ]]; then
        ln -s "$source_default_gradle_home/$source_cache_directory" \
            "$source_gradle_home/$source_cache_directory"
    fi
done
cp "$workspace_root/tool/source-host-min-sdk.gradle" \
    "$source_gradle_home/init.d/source-host-min-sdk.gradle"

(
    cd "$workspace_root/modules/source_host"
    GRADLE_USER_HOME="$source_gradle_home" \
        GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.java.home=$task_java_home" \
        flutter build aar "${task_flutter_arguments[@]}" --target-platform android-arm64
)
(
    cd "$repository_root"
    ./gradlew "${task_gradle_tasks[@]}" \
        "-Dorg.gradle.java.home=$task_java_home" \
        -PflutterSourceEngine=true \
        "-PflutterSourceTestSuffix=${SOURCE_ENGINE_TEST_SUFFIX:-.debug}" \
        --console=plain --max-workers=2
)
