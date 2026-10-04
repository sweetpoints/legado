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

The default input is locally built official V8 **15.4.80.24**, pinned to
`e422f6ef0c7b877b04e4872fd0bd3a1cc2ec2eee`. This is the selected stable V8 version. The official source URLs and complete
commit pins are recorded in [`../../tool/v8/pins.json`](../../tool/v8/pins.json).
The pinned depot_tools revision is `8a5434051036b32412a2ecb10c213a72e3f3ccb9`.

The source build uses V8's DEPS-resolved Chromium compiler and custom libc++.
The bridge and V8 are compiled in the same GN graph. Dart consumes only the
`sv8_*` C ABI, rather than linking a separately built C++ bridge against an
incompatible precompiled engine. The default path does not download a
third-party precompiled V8 library and has no fallback to the former build.

From the repository root, bootstrap or build the official source:

```sh
python3 flutter/tool/v8/build.py bootstrap --target macos-arm64
python3 flutter/tool/v8/build.py build --target macos-arm64 --jobs 4
```

macOS ARM64 builds require a macOS ARM64 host. Android ARM64 requires a Linux
x86_64 build host:

```sh
python3 flutter/tool/v8/build.py build --target android-arm64 --jobs 4
```

Source and tool caches are ignored under
`flutter/packages/source_v8/.cache/v8-source/`. Output defaults to
`flutter/packages/source_v8/.cache/self-built/<V8 commit>/manifest.json`, with
per-target binaries beneath that directory. The source build emits the final
shared bridge, GN arguments, actual dependency inventory, public defines,
compiler/GN version information and collected license files. The license
collector copies V8 LICENSE/AUTHORS and third_party LICENSE/COPYING/NOTICE/AUTHORS
files into the artifact root, with individual hashes in the manifest. This is
source notice collection rather than a complete license audit. No generated
binaries are committed.

The manifest records V8/depot_tools pins, bridge ABI and source digest, target
library SHA-256/size, minimum OS/API, GN arguments and their hash, DEPS and actual
dependency-inventory hashes, public-defines hash and toolchain version. A target
built by this script is marked built, with runtime/source-compatibility testing
still false. Building a binary does not set those validation claims to true.

The native-assets hook reads these local artifacts. A custom root can be selected
through the official Pub hooks user define:

```yaml
hooks:
  user_defines:
    source_v8:
      artifact_root: /absolute/path/to/self-built/artifacts
```

That directory must contain the expected manifest and target artifact. Changing
its path does not bypass source/version/integrity requirements. A missing or
mismatched artifact is an explicit build error.

Current source-build targets are macOS ARM64 (deployment target 13.0) and Android
ARM64 (API 26). Every consuming macOS app must also set its actual Xcode
`MACOSX_DEPLOYMENT_TARGET` to **13.0 or newer**; the generated example does so for
all configurations. Flutter 3.47's native-assets tooling currently supplies a
hardcoded macOS target version of 13 rather than reading the application's Xcode
deployment target. A successful hook therefore does not verify the app's minimum
OS setting. The hook also does not reject standalone Dart's default target of 12
when executing locally: the supported runtime requirement remains macOS 13+.

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

Intl and Temporal are disabled by the GN configuration; external
startup data is disabled, so the snapshot is embedded. Other targets do not
become supported merely because Flutter's generated example contains platform
folders. The configurable 64 MiB old-generation limit is not a process memory hard
cap.

License provenance is described in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Use the collected notices from the actual official source build for distribution.

## Verification

The official source-built macOS ARM64 library has linked successfully and was
loaded as V8 **15.4.80.24**. Its final library contains 10 `sv8_*` exports, is
46,032,368 bytes (43.90 MiB), and has SHA-256
`c704139a9965577130dedc8262170d13c119a0281ca15d719960f89fcbb0f8c7`.
The source_v8 package's 21 tests passed; the workspace's eight static analyses
were clean and all 139 tests passed (19/27/7/21/10/48/1/6). This evidence is in
`tmp/flutter-source-check-stable-final.log` relative to the repository root. The
build manifest's initial validation flags remain build-only; executed-test
evidence is recorded separately.

The official Flutter FFI example also built in Debug and Release and displayed
`V8 15.4.80.24: 42` through actual runtime evaluation. `file` confirmed that the
Release main executable and bundled source_v8 framework were ARM64 only; local
codesign inspection confirmed the allow-jit entitlement. This is a local
build/run/signature configuration check. Notarization and Hardened Runtime
distribution acceptance have not been completed.

The official source-built Android ARM64 library also linked successfully.
Debug AAR and APK contain the same 26,306,760-byte library (25.09 MiB), SHA-256
`0a2874dcf11c44213b10fe208bac6130bc00121b4433fdac1e19ac984053a593`,
matching the manifest. ELF inspection confirmed ARM64, 16 KiB PT_LOAD alignment
and only c/dl/log/m system-library dependencies. The three engine instrumentation
cases passed on the Android API 36 emulator; evidence is in
`tmp/flutter-android-test-stable-v8.log`. These are three test cases, not three
different device configurations.

The earlier 136-test and Release/R8 results belong to the V8 14.3 baseline.
New Android Debug packaging and device execution are verified independently;
the new Android Release/R8 build is still in progress and is not claimed passed.

After producing the correct local artifact, run `dart run test:test test` from
this package to execute the native-assets hook before tests. Bare `dart test`
does not build the hook's native assets. Tests exercise actual V8 async/sync host
calls, script errors, watchdog, cancellation, lifecycle and serialization.
Android compilation and on-device acceptance remain separate checks.
