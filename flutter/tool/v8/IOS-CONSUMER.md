# iOS V8 consumer: Flutter native assets and static XCFramework

`ios_sdk.py` compiles Legado's unchanged C ABI against the fixed published iOS SDK,
sets the reviewed `--jitless` flag before V8 initialization, and packages device
and simulator static libraries as `SourceV8.xcframework`. It never builds V8.

The SDK pins live in `ios-pins.json` and `ios-release-pin.json` to keep the iOS
jitless feature profile separate from Android/macOS. The Flutter hook now supports
ARM64 device and simulator via the real `code.iOS.targetSdk` environment. It links
a dylib containing the static SDK and bridge with exactly ten exports; Flutter
packages, adjusts the install name, and signs that dylib as an embedded framework.
No Podfile or app Xcode project modification is needed for this native-assets path.
The static XCFramework command remains available as an alternative native package.

Python 3.12+ is required. Xcode can hide the shell's package-manager PATH and select
Apple's old Python. The hook checks the interpreter **before** SDK preparation,
trying `python3`, `/opt/homebrew/bin/python3`, and `/usr/local/bin/python3`. An explicit
`SOURCE_V8_PYTHON` absolute path takes precedence and does not fall back on failure.

```sh
python3 flutter/tool/v8/ios_sdk.py \
  --output /absolute/path/to/new-ios-package
```

Requires macOS ARM64, Xcode with iPhoneOS/iPhoneSimulator SDKs, and the existing
SHA-pinned Chromium Clang compiler cache/download. SDK archives, internal manifests,
all SDK files and notices, compiler identity, bridge sources, and packaging inputs
are checked. An existing output is refused. The result contains:

- `SourceV8.xcframework`: iOS ARM64 device and ARM64 simulator static slices plus
  public `source_v8.h` headers.
- Per-target static libraries and linked native probe executables.
- `provenance.json`: SDK/bridge/packager hashes, exact compile/link commands,
  platform inspection and explicit unverified runtime markers.
- `licenses/<target>/`: each authenticated SDK's notices.

Mach-O probe inspection enforces device/simulator platform identity, iOS 15 minimum,
and **weak** BrowserEngineKit linkage. XCFramework inspection verifies both platform
slices and hashes the actual packaged library bytes against the linked input.

To explicitly execute the already built simulator probe on a dedicated **booted**
simulator UUID:

```sh
python3 flutter/tool/v8/ios_sdk.py \
  --output /absolute/path/to/existing-ios-package \
  --verify-existing --run-simulator EXPLICIT-SIMULATOR-UUID
```

The tool checks the probe hash, requires process exit 0, verifies exact structured
outputs, and writes `simulator-runtime.json`. It does not create/boot/delete a
simulator or modify personal simulator state. The probe covers JavaScript,
Intl/Temporal, absent WebAssembly under jitless, retained globals, asynchronous and
synchronous host replies, cancellation and continued use of the retained context.
It does not claim old-source compatibility or full Flutter application acceptance.

## Verified local result (2026-10-17)

Both real static SDKs were installed using their reviewed fixed release hashes.
Device and simulator bridge archives and native probes linked successfully with
Chromium Clang 24 and Xcode iOS 27 SDKs. `xcodebuild -create-xcframework` produced the
actual two-slice static XCFramework. The simulator probe executed on a disposable
iOS 27 ARM64 simulator and exited 0; all nine checks above passed. Device output
has link/platform inspection evidence only; no physical-device execution occurred.

The existing macOS ARM64 runtime remains a separate regression requirement because
the shared C++ bridge gained only a guarded `SV8_IOS_JITLESS` initialization branch.

## Real Flutter application verification (2026-10-17)

The official Flutter-scaffolded `packages/source_v8/example` was built and run on a
dedicated iOS 27 ARM64 simulator using the normal native-assets hook:

```sh
flutter test integration_test/v8_ios_test.dart -d EXPLICIT-SIMULATOR-UUID
```

All three integration tests passed against actual Dart FFI and the packaged V8
framework: exact V8 version and jitless/Intl/Temporal profile; asynchronous and
synchronous Dart hosts; and persistent-context recovery after cancellation.
The installed simulator framework was separately inspected after Flutter's lipo,
install-name and signing transformations: correct simulator platform, iOS 15
minimum, weak BrowserEngineKit dependency and exactly ten ABI exports. Strict
framework signature verification passed.

The same example also completed a real device **Release/AOT** build:

```sh
flutter build ios --release --no-codesign
```

This produced `build/ios/iphoneos/Runner.app` (34.3 MB). It is unsigned and was not
installed on a physical device. No physical-device runtime or complete old-source
compatibility acceptance is claimed by either result. The full migrated Legado
application still needs its own integration tests, signed device execution and
source compatibility acceptance.

## Dynamic native-asset packaging details

`ios_sdk.py --prepare-dynamic ios-arm64` (or `ios-simulator-arm64`) authenticates the
static package provenance, retains each C ABI entry point as a linker root, links
and inspects the actual dylib, and publishes a target-specific manifest. A shared
publication lock serializes hooks, so device and simulator builds cannot race to
publish a partial common package. The Dart hook verifies the release/environment,
bridge digest, packager digest and binary hash before and after copying the asset.
The original pure static SDK remains unchanged; no V8 source compilation occurs.

Flutter 3.47's native-assets implementation converts a bundled dylib into a signed
embedded framework. `StaticLinking` is not supported by its iOS asset linker;
`LookupInExecutable` would require separate app-target link configuration. The
consumer therefore uses the framework path already implemented by Flutter instead
of claiming that an unlinked static library is available to Dart FFI.

## If consuming the static package manually

Manual static integration remains possible, but requires selecting the proper
XCFramework slice, retaining all ten symbols (`-Wl,-u,_sv8_create` and the remaining
ABI entries), linking libc++/Objective-C/Security/Foundation and weak BrowserEngineKit,
and arranging Dart FFI executable lookup. This alternative has no automatic Flutter
hook integration; the standard hook uses the tested dynamic framework path above.
