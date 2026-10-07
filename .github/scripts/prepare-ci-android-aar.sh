#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source_gradle_home="$repository_root/flutter/.gradle-source-host"
source_wrapper_directory="$repository_root/flutter/modules/source_host/.android"

# Flutter's generated module otherwise reserves 8 GiB heap + 4 GiB metaspace.
# Keep the separately-owned AAR daemon within the dual-ABI budget already used by CI.
export SOURCE_ENGINE_AAR_GRADLE_JVMARGS='-Xmx2g -Xms256m -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8'

cleanup() {
  local result=$?
  trap - EXIT
  # Use the same generated wrapper/version and private home as AAR preparation.
  # Gradle --stop only discovers matching daemons in this home; never kill Java broadly.
  if [[ -f "$source_wrapper_directory/gradlew" && -d "$source_gradle_home" ]]; then
    if ! (
      cd "$source_wrapper_directory"
      GRADLE_USER_HOME="$source_gradle_home" bash ./gradlew --stop --console=plain
    ); then
      echo 'Failed to stop the CI-owned Flutter AAR Gradle daemon.' >&2
      if (( result == 0 )); then result=1; fi
    fi
  fi
  exit "$result"
}
trap cleanup EXIT

bash "$repository_root/flutter/tool/prepare-android-aar.sh" all
