# Standalone Flutter Android Release acceptance

This builds `flutter/apps/legado`, package `io.legado.legado_app`, not the legacy
Android host under `app/`. Its current Release signing uses the scaffold's test
key configuration; production signing is a separate delivery requirement.

## Reproduced failure and cause

On 2026-10-17, a fresh isolated checkout of `dd196737e` reproduced the reported
missing `IntegrationTestPlugin` compile error **without concurrent commands**:

```sh
# Working directory: flutter/apps/legado
flutter pub get
flutter build apk --release --no-pub \
  --target-platform android-arm64,android-x64 --split-per-abi
```

The bad build failed at `:app:compileReleaseJavaWithJavac` after 41 seconds. Pub get
had generated a Java registrant containing the dev-only `integration_test` plugin.
With Flutter 3.47.6, `--no-pub` skips the framework's release-mode tooling
regeneration as well as dependency resolution, so that registrant remains while
Gradle correctly omits the dev-only plugin from Release dependencies.

This was verified in the installed official Flutter SDK at framework revision
`5fc346839b5d0eef006ed8404392afb4dfae428d`:

- [`flutter_command.dart`, `regeneratePlatformSpecificToolingIfApplicable`](https://github.com/flutter/flutter/blob/5fc346839b5d0eef006ed8404392afb4dfae428d/packages/flutter_tools/lib/src/runner/flutter_command.dart#L2052)
  returns immediately when `shouldRunPub` is false.
- [`flutter_plugins.dart`, `injectPlugins`](https://github.com/flutter/flutter/blob/5fc346839b5d0eef006ed8404392afb4dfae428d/packages/flutter_tools/lib/src/flutter_plugins.dart#L1387)
  filters dev dependencies only for release-mode registration.
- [`PluginHandler.kt`](https://github.com/flutter/flutter/blob/5fc346839b5d0eef006ed8404392afb4dfae428d/packages/flutter_tools/gradle/src/main/kotlin/plugins/PluginHandler.kt#L111)
  omits dev-only plugin dependencies from the Release configuration.

Concurrent pub/analyze/test commands can also rewrite registrants, so the build
checkout remains exclusive to one Flutter command at a time. Concurrency was not
needed to reproduce this particular failure. Neither generated Java nor Flutter
SDK source was patched, and `integration_test` remains a dev dependency.

## Correct serial command and verified result

```sh
# Working directory: flutter/apps/legado
flutter build apk --release \
  --target-platform android-arm64,android-x64 --split-per-abi

# From the repository root:
python3 flutter/tool/verify-flutter-app-release.py \
  --output build/flutter-app-release-verification.json
```

Omit `--no-pub` so Flutter performs official Release plugin regeneration. The
normal command succeeded in the same checkout after 14.1 seconds, with no business
code, Gradle, SDK or registrant change:

| ABI | Actual APK bytes | SHA-256 |
| --- | ---: | --- |
| ARM64 | 63,319,939 | `c446d4f0a270bd02b01eb428ddf4d6ef2eca0d29b062e1f0b270eefd3926a07a` |
| x86_64 | 66,902,304 | `d20c1a1030ff6e0a523aabca908d1a1af9cadc55e0d8ef1bfdd73fe7f6a72b51` |

Both actual APKs passed package ID, non-debuggable Release manifest, API 26 floor,
signature verification, single-ABI packaging, 16-KiB ZIP alignment and native ELF
LOAD alignment checks. Their packaged V8 bytes equal the authenticated SDK-linked
bridge cache exactly; original V8 static SDK archives were not rebuilt or changed.
These are build/package checks, not a claim of physical-device execution.

## CI regression protection

`.github/workflows/flutter-app-android-release.yml` owns a fresh checkout on
Ubuntu 26.04, installs fixed Flutter 3.47.6, Java 21 and Python 3.14, installs the
Flutter NDK 28.2 and bridge sysroot NDK 30.0, and runs the serial normal build.
An explicit preceding App pub get exercises the previously failing dev-plugin
registrant state. The actual double-ABI APK build and artifact verification must
pass; the verifier also rejects a registrant retaining `IntegrationTestPlugin`.

No pub/analyze/test command shares that checkout while the Release build runs.
The job uploads both test-signed APKs and the machine-readable verification report.
The V8 cache is host/pin keyed and reauthenticated by the normal consumer before
bundling. No V8 source checkout/build or runtime fallback is introduced.
