#!/usr/bin/env bash
set -euo pipefail

workspace_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repository_root="$(cd "$workspace_root/.." && pwd)"
task_java_home="${SOURCE_ENGINE_JDK:-${JAVA_HOME:-}}"
if [[ -z "$task_java_home" || ! -x "$task_java_home/bin/java" ]]; then
    echo 'Set SOURCE_ENGINE_JDK to a JDK 21 installation.' >&2
    exit 2
fi
if ! "$task_java_home/bin/java" -version 2>&1 | head -n 1 | grep -Eq 'version "21\.'; then
    echo 'AAR preparation requires JDK 21.' >&2
    exit 2
fi
task_flutter_version="$(flutter --version --machine | python3 -c 'import json,sys; print(json.load(sys.stdin)["frameworkVersion"])')"
if [[ "$task_flutter_version" != '3.47.6' ]]; then
    echo 'AAR preparation requires Flutter 3.47.6.' >&2
    exit 2
fi

case "${1:-all}" in
    all) task_flutter_arguments=(--no-profile); task_modes=(debug release) ;;
    debug) task_flutter_arguments=(--no-profile --no-release); task_modes=(debug) ;;
    --release) task_flutter_arguments=(--no-debug --no-profile); task_modes=(release) ;;
    *) echo 'Usage: prepare-android-aar.sh [all|debug|--release]' >&2; exit 2 ;;
esac
task_android_targets="${SOURCE_ENGINE_ANDROID_TARGETS:-android-arm64,android-x64}"
# The verifier rejects unknown, duplicate and empty target names before compilation.
SOURCE_ENGINE_ANDROID_TARGETS="$task_android_targets" python3 "$workspace_root/tool/verify-android-aar.py" --native-only
task_source_sha="$(python3 "$workspace_root/tool/verify-android-aar.py" --print-source-digest)"
# Use Gradle's supported init.d mechanism without changing generated .android
# files or the user's global initialization scripts/properties.
source_gradle_home="$workspace_root/.gradle-source-host"
mkdir -p "$source_gradle_home/init.d"
# Kotlin DSL/instrumented caches are Gradle-user-home sensitive. Sharing the whole
# caches tree through a symlink makes Flutter's included Kotlin plugin lose its
# plugin classpath/accessors. Keep all dependency and generated caches physical
# and private to this workspace; reuse only the downloaded Gradle distributions.
# Upgrade the previous script-created symlink without deleting its target.
if [[ -L "$source_gradle_home/caches" ]]; then
    unlink "$source_gradle_home/caches"
fi
mkdir -p "$source_gradle_home/caches"
# Never copy properties, init scripts or credentials.
source_default_gradle_home="${GRADLE_USER_HOME:-$HOME/.gradle}"
for source_cache_directory in wrapper; do
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

task_aar_gradle_opts="${GRADLE_OPTS:-} -Dorg.gradle.java.home=$task_java_home"
if [[ -n "${SOURCE_ENGINE_AAR_GRADLE_JVMARGS:-}" ]]; then
    # Gradle's documented system property overrides generated gradle.properties.
    # Keep the complete daemon argument list in one quoted client JVM option.
    task_aar_gradle_opts+=" -Dorg.gradle.jvmargs=\"$SOURCE_ENGINE_AAR_GRADLE_JVMARGS\""
fi

(
    cd "$workspace_root/modules/source_host"
    GRADLE_USER_HOME="$source_gradle_home" \
        GRADLE_OPTS="$task_aar_gradle_opts" \
        flutter build aar "${task_flutter_arguments[@]}" --target-platform "$task_android_targets"
)
python3 "$workspace_root/tool/verify-android-aar.py" --write-stamp \
    --targets "$task_android_targets" --expected-source-sha "$task_source_sha" "${task_modes[@]}"
