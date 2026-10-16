# Linux pure V8 SDK consumer

`linux_sdk.py` consumes the already published **15.4.80.25** pure SDK release
for Linux x64 and ARM64. It builds only Legado's existing C bridge. It does not
check out V8, run GN/Ninja, modify a published SDK, push a release or register
Linux as a ready Flutter platform.

## Build inputs and authentication

- `linux-release-pin.json` fixes the public `sweetpoints/v8-prebuilt` tag,
  release manifest, both archive sizes/SHA-256 values, SDK manifests and source
  pins. The existing pure SDK validator checks every indexed SDK file and its
  license inventory, as well as source revisions and target configuration.
- `toolchain-pins.json` supplies the same reviewed Chromium Clang 24 revision as
  the released SDK. Both Clang and LLD are selected by absolute verified paths;
  neither the host C++ standard library nor host linker is used.
- `linux-pins.json` records the official Chromium Debian bullseye sysroots at
  build revision `fdd530b37c6cd6c8645cdfa296d209643ab2dc26`. These hashes come
  from the immutable [official sysroots.json](https://chromium.googlesource.com/chromium/src/build/+/fdd530b37c6cd6c8645cdfa296d209643ab2dc26/linux/sysroot_scripts/sysroots.json).
  Archive SHA-256, size and an independent extracted-file inventory are checked.
  Subsequent cache use rechecks all file bytes and internal symlink destinations.
- SDK `linking.json` is enforced: matching `__Cr` libc++ headers, Rust runtime,
  compiler builtins, ABI defines/options, static archive rescanning, ICU embedded
  data, Intl/Temporal, default JIT and WebAssembly. Cross-architecture metadata or
  an additional compiler/linker flag is rejected.
- The target sysroot's exact `libgcc_s.so.1` provides Linux unwind entry points
  required by Rust/libc++abi. This makes Clang's normally implicit unwind
  dependency explicit without discovering an unrelated host GCC installation.

Chromium's [Linux sysroot documentation](https://chromium.googlesource.com/chromium/src/+/main/docs/linux/sysroot.md)
explains why these target images are used instead of host-installed libraries.

Python 3.12 or newer is required for the supported safe tar extraction API.
A macOS ARM64 or Linux x64 host can use the existing verified Clang installer.
Both Linux targets can be cross-linked on either host. Native Linux ARM64
compiler-package installation remains separate from this consumer.

```sh
python3 flutter/tool/v8/linux_sdk.py \
  --output build/linux-v8-bridge \
  --target linux-x64 --target linux-arm64 \
  --sdk-cache build/verified-v8-sdks \
  --compiler-cache build/verified-clang \
  --sysroot-cache build/verified-linux-sysroots
```

The output must be a new directory. Each target contains `libsource_v8.so`;
`licenses/<target>` carries the released SDK licenses. `provenance.json` records
commands, input hashes, SDK and sysroot identities, bridge hashes, architecture,
dynamic dependencies and the highest required glibc version. It explicitly
records `v8BuiltLocally=false`, `flutterHookEnabled=false` and
`runtimeTested=false`.

## Linux allocator boundary and verification

The original SDK consumer cross-linked, and its JavaScript assertions passed,
but normal process teardown failed with SIGTRAP. The released SDK contains a
hidden PartitionAlloc `free`, while V8's default tracing controller obtains
category names using system `strdup`; its destructor passed that glibc allocation
to PartitionAlloc. The pinned [V8 tracing controller source](https://chromium.googlesource.com/v8/v8/+/c45871fec706a6e7b715e607065bb4578b23ce9f/src/libplatform/tracing/tracing-controller.cc)
contains this `strdup`/`free` pair. The failure was symbolized to
`v8::platform::tracing::TracingController::~TracingController`.

`linux_allocator_boundary.cpp` now adapts the finite allocation-returning libc
interfaces imported by these fixed SDKs. It is Linux-only bridge code and does
not modify the V8 archives, replace process allocation, disable tracing, skip
teardown, or change JIT/Intl/Temporal/WebAssembly settings.

| Interface | Ownership handling |
| --- | --- |
| `strdup`, `strndup` | Copy directly into the bridge's own allocator; preserve truncation and terminator semantics. |
| `realpath`, `getcwd` | Keep caller-provided buffers unchanged; copy libc-allocated results and preserve explicit `getcwd(NULL, size)` capacity. |
| `vasprintf` | Copy the returned byte count plus terminator, including embedded NUL bytes. |
| `backtrace_symbols` | Copy the pointer table and all strings into one allocation, preserving single-free semantics. |

For the latter four functions, only the original libc-returned buffer is freed
through the **versioned** system `__libc_free` symbol: GLIBC 2.17 on ARM64 and
GLIBC 2.2.5 on x64. The explicit ELF version is essential because the SDK also
contains an unversioned PartitionAlloc alias named `__libc_free`.

The complete undefined dynamic-symbol inventory was reviewed for both targets
and pinned by SHA-256. A changed import set fails the build and requires renewed
ownership review. In particular:

- V8's fixed `src/` tree calls `strdup` in tracing and `backtrace_symbols` in
  stack-trace and external-reference diagnostics. Its `d8` CLI's `getcwd` call
  uses a caller-supplied buffer; `d8` is not linked into this bridge.
- Rust/libc++/ICU dependencies add `getcwd`, `realpath` and `vasprintf` to the
  final SDK imports. These receive the same ownership adapters.
- `FILE*`, `DIR*`, locale and message-catalog handles remain owned by libc and
  use the matching libc `fclose`, `closedir`, `freelocale` and `catclose`
  functions. Borrowed environment/locale/error strings are not freed.
- Mapped memory keeps its `mmap`/`munmap` ownership. Compiler unwind runtime
  calls do not return buffers for the bridge allocator to free.
- No `malloc`, `free`, C++ allocation operator or boundary-test function is
  exported by the production shared library; its ABI remains the ten `sv8`
  functions.

The ownership semantics are documented by the [GNU libc backtrace manual](https://sourceware.org/glibc/manual/latest/html_node/Backtraces.html)
(single allocation for the table and strings) and exercised against the actual
pinned SDK allocator in a separate test-only shared library.

Locally verified on 2026-10-17:

- Both real released SDKs and target sysroots were downloaded and authenticated.
- Both bridges cross-linked using pinned Chromium Clang/LLD; the target sysroot
  is also selected explicitly for GCC CRT/unwind discovery.
- Both outputs are the correct ELF64 architecture, export exactly ten `sv8`
  functions, have no unexpected dynamic dependencies, and require at most
  GLIBC 2.27 (below the SDK's GLIBC 2.31 compatibility ceiling).
- Linux ARM64 executed inside a native ARM64, network-disabled container.
- Linux x64 executed in an emulated amd64, network-disabled container on the
  same ARM64 host. This is not native x64 machine acceptance.
- Both executions passed all six allocation adapters' buffer/errno/string
  semantics over 2,000 cycles, JavaScript, Intl, Temporal, actual WebAssembly
  validation, retained sessions, asynchronous/synchronous host calls,
  cancellation/recovery, and 100 V8 memory/feature stress cycles.
- Both child processes exited normally, including the unchanged default tracing
  controller's destructor. A receipt is only saved after zero process exit.

Flutter hooks, final application runtime and old-source compatibility acceptance
remain separate integration work. `runtimeTested=false` in build provenance
means build-only evidence; the separate runtime receipt reports the executed
checks and whether the execution environment was native or emulated.

An explicit same-architecture native probe is available:

```sh
python3 flutter/tool/v8/linux_sdk.py \
  --output build/linux-v8-bridge --target linux-arm64 \
  --verify-existing --run-native
```

The probe runs in a child process. A receipt is written only after normal child
exit with status zero. Passing JavaScript assertions followed by an allocator
crash remains a failure. Pass `--execution-mode emulated` when the Linux process
runs under emulation; this is recorded in the receipt. No real Flutter application or old-source
compatibility acceptance is implied by this probe.

```sh
python3 -m unittest discover -s flutter/tool/v8 -p test_linux_sdk.py -v
```
