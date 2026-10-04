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
ARM64 (API 26). Intl and Temporal are disabled by the GN configuration; external
startup data is disabled, so the snapshot is embedded. Other targets do not
become supported merely because Flutter's generated example contains platform
folders. The configurable 64 MiB old-generation limit is not a process memory hard
cap.

License provenance is described in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Use the collected notices from the actual official source build for distribution.

## Verification

The earlier 136 tests, three Android device tests and Release/R8 result
were obtained with the V8 14.3 baseline. They are historical evidence and do not
verify this 15.4 source-built binary. New source-built runtime, cross-package,
Android and Release validation must be reported separately after execution.

After producing the correct local artifact, run `dart run test:test test` from
this package to execute the native-assets hook before tests. Bare `dart test`
does not build the hook's native assets. Tests exercise actual V8 async/sync host
calls, script errors, watchdog, cancellation, lifecycle and serialization.
Android compilation and on-device acceptance remain separate checks.
