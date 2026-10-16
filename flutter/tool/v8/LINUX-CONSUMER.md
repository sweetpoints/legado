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

## Verification and remaining allocator boundary

Locally verified on 2026-10-17:

- Both real released SDKs were downloaded and authenticated.
- Both bridges cross-linked using pinned Chromium Clang/LLD and target sysroots.
- Both outputs are the correct ELF64 architecture, export exactly the ten `sv8`
  ABI functions, contain no unexpected dynamic dependency and require at most
  GLIBC 2.27 (below the SDK's GLIBC 2.31 compatibility ceiling).
- Linux ARM64 was loaded inside an isolated, network-disabled Linux ARM64
  container. JavaScript, Intl, Temporal, WebAssembly availability, retained
  sessions, asynchronous/synchronous host calls, cancellation and recovery all
  ran, but **normal process teardown failed with SIGTRAP**.

This is not a successful runtime acceptance. The pinned SDK contains a local
PartitionAlloc `free`, while V8's default tracing controller obtains category
names using system `strdup`. The controller's destructor then passes a glibc
allocation to PartitionAlloc. The pinned [V8 tracing controller source](https://chromium.googlesource.com/v8/v8/+/c45871fec706a6e7b715e607065bb4578b23ce9f/src/libplatform/tracing/tracing-controller.cc)
contains the `strdup`/`free` pair, and the crash was symbolized to
`v8::platform::tracing::TracingController::~TracingController`.

The consumer does not hide that failure by omitting the destructor, calling
`_Exit`, disabling SDK validation, changing the published V8 static library or
marking Linux support ready. The bridge allocator boundary must be resolved and
retested before Flutter hook registration or application acceptance.

An explicit same-architecture native probe is available:

```sh
python3 flutter/tool/v8/linux_sdk.py \
  --output build/linux-v8-bridge --target linux-arm64 \
  --verify-existing --run-native
```

The probe runs in a child process. A receipt is written only after normal child
exit with status zero. Passing JavaScript assertions followed by an allocator
crash remains a failure. An x64 execution under emulation must be reported as
emulation, not native x64 validation. No real Flutter application or old-source
compatibility acceptance is implied by this probe.

```sh
python3 -m unittest discover -s flutter/tool/v8 -p test_linux_sdk.py -v
```
