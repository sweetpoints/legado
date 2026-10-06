# source_v8

Official Flutter `package_ffi` scaffold, extended with an embedded V8 C++ bridge.
No Node subprocess, JSC or QuickJS fallback is used.

`V8Runtime` implements `source_engine.ScriptRuntime`. `evaluate` accepts an
expression (including `await`) or an async function body with explicit `return`.
Context variables are JSON globals. `source.net.get(url)` and
`source.call('net.get', [url])` dispatch Promise-based host operations.
`__sourceHostSync(method, args)` is reserved for the legacy adapter: its native
worker waits on a bounded queue while the parent Dart isolate services the host.
Host values and script results must be JSON serializable.

Every evaluation owns a separate Dart worker, V8 Isolate and Context. It does not
preserve JS globals across evaluations. Persistent variables belong to the host.
The watchdog interrupts busy JS and pending synchronous calls; cancellation
also terminates execution. Context disposal is acknowledged by the parent before
freeing the native handle. V8 Locker guards calls as Dart worker threads migrate.
A host Future already executing is not forcibly stopped by this runtime; host
implementations must cooperate with task cancellation.

## Build provenance

Legado consumes the published pure V8 SDK release
[`v8-15.4.80.25`](https://github.com/sweetpoints/v8-prebuilt/releases/tag/v8-15.4.80.25),
with upstream revision `c45871fec706a6e7b715e607065bb4578b23ce9f`.
[`release-pin.json`](../../tool/v8/release-pin.json) fixes the release manifest,
each SDK archive and extracted SDK manifest by SHA-256. Headers, static libraries,
feature definitions, linking metadata and license inventories are authenticated.
Legado does not build V8, fetch a source checkout, run GN/Ninja or fall back to
self-building the engine. The retired build.py source-build command fails closed.

The native hook prepares a verified SDK and compiles only Legado's own
`source_v8.cpp` bridge against the SDK's matching headers/static libraries.
The minimal official Chromium Clang package is pinned by URL, archive size/SHA,
file inventory and exact compiler version; its fixed source metadata is recorded
in [`toolchain-pins.json`](../../tool/v8/toolchain-pins.json). There is no
V8/depot_tools bootstrap to obtain this compiler.

```sh
python3 flutter/tool/v8/prepare_sdk.py --target macos-arm64
python3 flutter/tool/v8/prepare_sdk.py --target android-arm64
python3 flutter/tool/v8/prepare_sdk.py --target android-x64
```

macOS ARM64 uses the Xcode SDK with deployment target 13.0. Android requires
API26 and installed NDK30.0.15729638, selected through ANDROID_SDK_ROOT or
ANDROID_HOME. Both macOS ARM64 and Linux x64 hosts can link the Android bridge
with the fixed compiler. The SDK supplies matching libc++, compiler-rt and unwind
archives; the linker retains NDK CRT objects and avoids adding an unrelated
implicit compiler runtime. All ten sv8 exports and Android16KiB LOAD alignment
are checked on the resulting bridge.

Verified SDKs cache under `.cache/source_sdk/`; only application bridge outputs
use the historical `.cache/self-built/<V8 revision>/` path. The name does not
mean V8 was built here. Source changes invalidate all old bridge targets;
publication remains atomic. Generated files are ignored, and no binary is committed.
A user override `hooks.user_defines.source_v8.artifact_root` still requires the
exact SDK and bridge provenance; it does not permit a source-build fallback.

The supported Legado targets remain macOS ARM64 and Android ARM64/x86_64.
Every macOS consumer must set its actual Xcode deployment target to13.0 or newer;
a successful native hook does not verify the application's Xcode setting.

For a macOS app signed with Hardened Runtime, the app's release entitlements
must include `com.apple.security.cs.allow-jit = true`: this V8 build uses
`MAP_JIT` for generated code, even when Flutter itself uses AOT compilation.
The example includes this entitlement in Debug/Profile and Release.
[Apple's JIT entitlement documentation](https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.security.cs.allow-jit)
and [Apple silicon JIT guidance](https://developer.apple.com/documentation/apple-silicon/porting-just-in-time-compilers-to-apple-silicon)
explain the requirement when Hardened Runtime is enabled. The example does not
add unsigned executable memory or disable library validation. Its current V8
write protection uses `pthread_jit_write_protect_np()`, so do not add
`com.apple.security.cs.jit-write-allowlist`, which Apple documents as incompatible
with that API. This configuration change is separate from signed release
acceptance and notarization.

The selected full-feature SDK includes Intl, embedded ICU, default Temporal,
JIT and WebAssembly. External startup data is disabled; snapshots are embedded.
The configurable64MiB old-generation limit is not a process memory hard cap.
License provenance is described in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Use the notices from the authenticated SDK archive for distribution.

## Historical verification

The following acceptance snapshot predates the15.4.80.25 SDK-consumer switch.
It does not establish acceptance of the current bridge or application.

The official source-built macOS ARM64 library has linked successfully and was
loaded as V8 **15.4.80.24**. Its final library contains 10 `sv8_*` exports, is
46,032,368 bytes (43.90 MiB), and has SHA-256
`c704139a9965577130dedc8262170d13c119a0281ca15d719960f89fcbb0f8c7`.
The current Flutter workspace passed 378 tests (64/60/15/23/22/173/1/20)
and eight clean analyses. Evidence is in
`tmp/flutter-source-check-legacy-scalars.log` relative to the repository root. The
build manifest's initial validation flags remain build-only; executed-test
evidence is recorded separately.

Earlier validation built the official Flutter FFI example in Debug and Release and displayed
`V8 15.4.80.24: 42` through actual runtime evaluation. `file` confirmed that the
Release main executable and bundled source_v8 framework were ARM64 only; local
codesign inspection confirmed the allow-jit entitlement. This is a local
build/run/signature configuration check. Notarization and Hardened Runtime
distribution acceptance have not been completed.

The official source-built Android ARM64 library also linked successfully.
Debug AAR and APK contain the same 26,306,760-byte library (25.09 MiB), SHA-256
`0a2874dcf11c44213b10fe208bac6130bc00121b4433fdac1e19ac984053a593`,
matching the manifest. ELF inspection confirmed ARM64, 16 KiB PT_LOAD alignment
and only c/dl/log/m system-library dependencies. The thirteen engine instrumentation
cases passed in the preceding acceptance round on the Android API 36 emulator; evidence is in
`tmp/flutter-android-test-page-requests-final.log`. These are thirteen test cases, not thirteen
different device configurations.

After producing the correct local artifact, run `dart run test:test test` from
this package to execute the native-assets hook before tests. Bare `dart test`
does not build the hook's native assets. Tests exercise actual V8 async/sync host
calls, script errors, watchdog, cancellation, lifecycle and serialization.
Android compilation and on-device acceptance remain separate checks.

The previous legacy form round passed four JVM golden tests across sixteen fixed inputs, recorded in
`tmp/flutter-legacy-form-jvm-golden.log`. This is fixed-input legacy form evidence,
not an entire-source or complete JVM-engine equivalence claim.

The legacy AnalyzeUrlPageTemplateGoldenTest also passed seven JVM tests;
`tmp/flutter-legacy-page-jvm-golden.log` records this fixed-input page-template
evidence. It does not establish all source or dynamic JavaScript compatibility.

Current scalar acceptance passed 378 Flutter tests, eight analyses and 42 Python
contract tests (12 native and 30 tooling). Default dual-ABI Debug/Release AARs
built with a 2 GiB heap and 1 GiB metaspace; native hashes passed validation
in `tmp/flutter-legacy-scalars-prepare-dual-2g.log`. The full ARM64 API 36
runner passed 65 unique tests across seven classes with no failures, errors or
skips (`tmp/flutter-legacy-scalars-device-final-65.log`, BUILD SUCCESSFUL in 13s).

The full App Release build passed with an 8 GiB heap: 3,949 JVM tests with zero
failures, errors or skips, lint, R8 and assembly succeeded in 3m28s, recorded
in `tmp/flutter-legacy-scalars-release-final-8g.log`. The initial 4 GiB full
Release attempt stopped under GC thrashing protection; the successful 4 GiB
App Debug and 2 GiB AAR checks do not establish 4 GiB Release acceptance.
Final APK content/hash-chain verification passed in
`tmp/flutter-final-release-apk-evidence.json`: APK 112,607,581 bytes, SHA-256
`eb76a5454a4f2baca369f5ba0413e31ecf7c46e783264a6a5520b58171666c1e`;
Release AAR 22,595,466 bytes, SHA-256
`283857c85b48c9b5506f5ea78c91a4d4b5f53db18ba67810b14136b754a6ef77`.
Source digest/stamp, dual-ABI V8 manifest/AAR libraries, current AOT libraries,
both NativeAssets mappings and the Flutter SDK-to-APK library chain matched. The APK is unsigned;
x86_64 device and remote CI acceptance remain pending. Fixed tests do not
establish full historical-source compatibility or complete Flutter UI migration.
