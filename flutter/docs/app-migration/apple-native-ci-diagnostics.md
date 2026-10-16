# Bounded iOS native application acceptance

The initial Apple workflow combined simulator selection, boot and Flutter execution
in one shell step. Run `38946559159` had completed its macOS branch while its iOS
branch remained in that combined step. While a GitHub job is running its completed
log archive can be unavailable, so this state alone does not identify an App bug,
a boot stall, an Xcode build problem or a Flutter device connection failure.
The helper below improves phase evidence and bounds without changing App behavior
or cancelling the existing hosted run.

`flutter/tool/apple-native-acceptance.py`:

- Records current SDK and simulator runtime/UUID/state before boot.
- Chooses the newest available iOS 15+ runtime compatible with the selected Xcode
  SDK. Within that runtime it prefers a booted iPhone over a shutdown device.
- Requires an explicit UUID for local execution; automatic selection is limited
  to the disposable GitHub runner. `--list-only` is read-only on either host.
- Separately bounds boot command (60s), full boot readiness (180s), Flutter device
  discovery (60s) and native application tests (1200s). Boot and test limits can be
  adjusted explicitly with `--boot-timeout` and `--test-timeout`.
- Streams stdout/stderr while preserving distinct machine stdout for JSON parsing.
  Commands that exit while a descendant retains their pipe cannot hang the helper.
- Writes host metadata, selected runtime, phase timestamps, command exit/timeout
  and command logs under `--output`, including failure records and extra simulator
  device/runtime snapshots when a phase fails. It does not dump environment values,
  read user databases or collect account contents.
- Terminates only its own new child process group on a phase deadline; no retries,
  App patches, simulator erases or hosted-run cancellation are performed.
- Passes the current Python 3.12+ executable to the V8 hook through
  `SOURCE_V8_PYTHON`, preserving setup-python through Xcode's reduced PATH.

## Workflow integration (root maintains flutter-app.yml)

Replace only the iOS shell step with a repository-root invocation:

```yaml
- name: Run native application on ARM64 iOS simulator
  if: matrix.platform == 'ios'
  run: python3 flutter/tool/apple-native-acceptance.py --output build/apple-native-ios
- name: Upload iOS native acceptance diagnostics
  if: always() && matrix.platform == 'ios'
  uses: actions/upload-artifact@v7.0.1
  with:
    name: ios-native-acceptance-diagnostics
    path: build/apple-native-ios/
    if-no-files-found: error
```

Keep the existing macOS 26 ARM64 runner, setup-python and fixed Flutter steps.
The helper sets its own application working directory; do not retain the shell
step's `working-directory: flutter/apps/legado` when using the command above.
No App, Podfile or Xcode project setting is changed.

`phases.json` identifies whether a failure occurred in simulator listing, boot,
boot readiness, device discovery or actual Flutter testing. A successful simulator
boot is not application acceptance; only the unchanged actual integration test's
successful exit is reported as successful test execution.

## Verification of the helper

Critical tests cover old-first runtime ordering, SDK compatibility, unavailable
and explicit devices, silent child deadlines, persisted failure phase, stderr JSON
isolation, descendant-pipe EOF and preservation of a real failing exit code.
A local `--list-only` run read the actual Xcode iOS 27 SDK and selected an available
iOS 27 simulator without booting, installing or changing it.

The helper is diagnostic and has not itself been claimed as a successful hosted
job. The older job's final logs remain the evidence for its actual root cause.

## Completed hosted-run evidence and scoped follow-up

The final log of job `117127054458` in run `38946559159` records simulator boot
finishing after 1 minute 47 seconds. Xcode then completed its build in 260 seconds
at `2026-10-16T18:33:24Z`. No integration test case started before cancellation
at `18:56:22Z`. The check annotation explicitly states: `The job has exceeded the
maximum execution time of 35m0s`. This was GitHub's automatic job deadline; the
investigation did not cancel or retry the job. The remaining unknown is the phase
after compilation: installation, application launch, or Flutter VM service
attachment. These logs do not establish a Dart, Drift or V8 test failure.

The runner image was `macos-26-arm64` version `20261013.0424.1`, on macOS 26.6.2
build 25G83. The old step selected an iPhone 17 Pro, but did not log its actual
runtime or selected Xcode version. A device model alone does not establish the
iOS runtime version. The local successful run used iOS 27.0 with SDK 27.1; those
versions must not be attributed to the hosted job.

The helper now logs `xcodebuild -version`, installed runtime versions/builds and
Flutter's `--verbose` execution. It samples application state after three minutes
and again sixty seconds before its test deadline, and collects state after test
failure. Each sampling command is bounded. Diagnostic launch messages match only
`io.legado.legadoApp`; process records match the exact selected simulator App
container path. Host process listings are filtered in memory before persistence,
with executable paths and PIDs only, without command arguments. Recent host crash
reports are inspected in memory and only summaries whose bundle identifier
matches this application are retained. A matching host crash summary is not
proof that it came from the selected simulator; its timestamp and PID must be
correlated with the selected App process and Flutter log. Other applications'
crash reports and simulator system logs are not retained.
