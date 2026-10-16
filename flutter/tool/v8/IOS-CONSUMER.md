# Experimental iOS static V8 bridge consumer

`ios_sdk.py` compiles Legado's unchanged C ABI against the fixed published iOS SDK,
sets the reviewed `--jitless` flag before V8 initialization, and packages device
and simulator static libraries as `SourceV8.xcframework`. It never builds V8.

The SDK pins deliberately live in `ios-pins.json` and `ios-release-pin.json` until
the Flutter app integration is complete. **No `OS.iOS` hook target is enabled by
this tool.** Android/macOS hook pins and behavior remain separate.

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

## Flutter application integration still required

A static XCFramework is a usable native package, not a completed Flutter app
integration. The integration must:

1. Link the correct platform slice in the app target and preserve the SDK's system
   libraries/frameworks: libc++, Objective-C, Security, Foundation and weak
   BrowserEngineKit. Do not strengthen the last dependency.
2. Retain all ten `sv8_*` ABI entry points when dead stripping; use explicit linker
   undefined-symbol roots (`-Wl,-u,_sv8_create`, and the remaining exported entries)
   or equivalent tested retention. Merely shipping a static `.a` does not make
   Dart FFI symbols available.
3. Map `source_v8_bindings_generated.dart` to lookup in the signed executable (or
   implement and test a correctly embedded/signed dynamic framework alternative).
   The current Android/macOS bundled dynamic-library hook cannot publish this
   static library using its existing `DynamicLoadingBundled` path.
4. Derive **device vs simulator** from the actual build environment. Both are ARM64;
   OS/architecture alone is insufficient.
5. Build and run the real Flutter app in simulator Release/Debug as available,
   test full source conformance, then sign/install/run a physical-device Release.

No signing entitlement, published SDK or CI workflow is changed by this package.
