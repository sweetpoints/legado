#!/usr/bin/env bash
set -euo pipefail

trap 'adb pull /sdcard/Android/data/com.legado.app.debug/files/ui-regression app/build/ui-regression || true' EXIT

./gradlew :app:connectedAppDebugAndroidTest \
  --init-script .github/scripts/source-browser-test.init.gradle \
  -Pandroid.testInstrumentationRunnerArguments.class=io.legado.app.ci.UiRegressionSuite \
  --build-cache --no-daemon --max-workers=2

python3 .github/scripts/verify-ui-test-coverage.py
