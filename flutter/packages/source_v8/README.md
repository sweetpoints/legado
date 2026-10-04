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

The build hook fetches release `v14.3.92-15` from
https://github.com/haroel/v8-build/releases/tag/v14.3.92-15.
Binary archives are SHA-256 verified byte-for-byte. Gitiles source archives
are verified by a canonical SHA-256 over sorted file paths and complete contents
because Gitiles writes request timestamps into tar metadata. Pins are in
`hook/build.dart`. Archives/extracted files are cached in ignored `.cache/`;
no binaries are committed. Cache publication uses a cross-process file lock and
atomic directory rename, so parallel ABI builds cannot observe partially
extracted headers or libraries. The release includes its V8 headers/build arguments.
These are third-party builds, not binaries published by the V8 project itself.
Android also builds the exact V8 DEPS libc++/libc++abi sources and LLVM libc headers,
with Chromium ABI2/relative vtables. A version script keeps their symbols local;
--no-undefined prevents packaging a library with unresolved runtime symbols.
Pinned archives, licenses and attribution are listed in THIRD_PARTY_NOTICES.md.
V8 is BSD licensed; distributors must include its license and applicable third-party notices.

Supported build targets: macOS arm64 and Android arm, arm64, x64. Other targets
fail explicitly. The current Android integration targets API 26 or newer.
The V8 archive was built with a newer NDK API, but this bridge links strictly
against Flutter's configured API stubs; the inspected ARM64 output identifies
API 24 and has no newer strong system imports. Its optional weak memfd_create
import comes from the NDK CPU-feature resolver and is null-checked before use.
Android needs Flutter's Android NDK toolchain. The pinned build
has internationalization disabled; `Intl` is not part of this runtime contract.
The configurable 64 MiB old-generation limit is not a process memory hard cap:
V8 out-of-memory can be fatal. This is not an untrusted-code security sandbox.

## Verification

From this package, run `dart run test:test test` to build native assets before
running tests. Bare `dart test` does not build the hook's native assets.
Tests execute actual V8 and cover async/sync host calls, script errors, watchdog,
cancellation, lifecycle and adversarial serialization. Android compilation and
on-device behavior are separate validation steps.
