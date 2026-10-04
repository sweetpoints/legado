#!/usr/bin/env bash
set -euo pipefail

python3 .github/scripts/sample-ci-resources.py app/build/ui-regression/host-resources.jsonl &
resource_sampler_pid=$!
cleanup() {
  local result=$?
  kill "$resource_sampler_pid" 2>/dev/null || true
  wait "$resource_sampler_pid" 2>/dev/null || true
  adb pull /sdcard/Android/data/com.legado.app.debug/files/ui-regression app/build || true
  exit "$result"
}
trap cleanup EXIT

# APKs were built before the emulator started. Keep AGP's native result collection,
# while bounding the separate connected-test JVM instead of reserving the build heap.
# --no-daemon still creates a single-use JVM which lives until the device suite ends.
connected_status=0
./gradlew :app:connectedAppDebugAndroidTest \
  --init-script .github/scripts/source-browser-test.init.gradle \
  -Pandroid.testInstrumentationRunnerArguments.class=io.legado.app.ci.UiRegressionSuite \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-XX:+UseParallelGC -Xmx2g -Xms256m -XX:MaxMetaspaceSize=768m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8' \
  --build-cache --no-daemon --max-workers=2 || connected_status=$?

# Check actual native reports even after failures, so missing class coverage is visible.
python3 .github/scripts/verify-ui-test-coverage.py || connected_status=1
exit "$connected_status"
