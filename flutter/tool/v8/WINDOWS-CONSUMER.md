# Windows pure V8 SDK consumer

This standalone consumer compiles **only Legado's unchanged C bridge**, using
`sweetpoints/v8-prebuilt`'s reviewed `v8-15.4.80.25` pure SDK archives. It does not
check out or build V8, modify a release, or register an unfinished Flutter target.
The bridge exports the existing ten `sv8_*` C entry points through a generated
module definition file, without changing the cross-platform header. The genuine
V8 archive also explicitly exports `CrashForExceptionInNonABICompliantCodeRange`;
this reviewed upstream exception handler is preserved for generated-code unwind
metadata. Any other export is rejected. See the
[pinned official V8 implementation](https://github.com/v8/v8/blob/c45871fec706a6e7b715e607065bb4578b23ce9f/src/diagnostics/unwinding-info-win64.cc#L50).

## Reviewed inputs

- `windows-pins.json`: complete official V8/depot_tools revisions and x64/ARM64
  target configurations.
- `windows-release-pin.json`: exact release manifest, archive and SDK manifest
  SHA-256 values, archive sizes, and upstream pins hash.
- Existing `prebuilt.py` validates every SDK payload file, licenses, GN arguments,
  archive identity and provenance, with safe extraction and fail-closed caching.
- `windows_sdk.py` checks the SDK's `clang-cl`, static MSVC `/MT`, Chromium libc++
  `__Cr` ABI, target machine and first monolith contract. It preserves the existing
  `link_sdk.source_digest()` and C ABI version 1, with separate consumer hashes.

Both published SDKs have four required archives: V8 monolith, Rust runtime,
Chromium libc++ runtime, and target Clang builtins. Do not substitute MSVC's C++
standard library, a MinGW SDK, an arbitrary host compiler, or a dynamically linked
CRT. Clang's Windows C++ compatibility is explicitly an ABI concern; see
[Clang MSVC compatibility](https://clang.llvm.org/docs/MSVCCompatibility.html).
The DLL build and exports follow [Microsoft's DLL linker contract](https://learn.microsoft.com/en-us/cpp/build/reference/dll-build-a-dll?view=msvc-170).

## Windows compiler and SDK authentication

A Windows x64 build host can produce x64 or ARM64 DLLs. A separately reviewed
Windows toolchain inventory is **required**; this repository currently does not
have a reviewed MSVC/Windows SDK distribution lock. The consumer deliberately
does not select whatever Visual Studio happens to be on a runner.

Prepare a new immutable directory containing the matching Chromium Clang package,
its resource headers and sibling `lld-link.exe`, MSVC C runtime headers/libraries,
and Windows SDK headers/import libraries. A complete lock, stored outside that
directory, has this shape (all placeholders must be replaced with reviewed values):

```json
{
  "schemaVersion": 1,
  "target": "windows-arm64",
  "clangVersion": "clang version 24.0.0git (https://chromium.googlesource.com/a/external/github.com/llvm/llvm-project 75adca17d7f5f488193fdf76cd07500e2a12081a)",
  "msvcVersion": "<reviewed exact version>",
  "windowsSdkVersion": "<reviewed exact version>",
  "compiler": "clang/bin/clang-cl.exe",
  "linker": "clang/bin/lld-link.exe",
  "msvcRoot": "msvc",
  "windowsSdkRoot": "windows-sdk",
  "includeDirs": ["msvc/include", "windows-sdk/Include/<version>/ucrt", "windows-sdk/Include/<version>/shared", "windows-sdk/Include/<version>/um"],
  "libraryDirs": ["msvc/lib/arm64", "windows-sdk/Lib/<version>/ucrt/arm64", "windows-sdk/Lib/<version>/um/arm64"],
  "files": {
    "<every regular file relative to toolchain root>": "<lowercase SHA-256>"
  }
}
```

The lock's SHA-256 must be approved independently and passed explicitly. Hashing
an arbitrary runner installation and immediately trusting the resulting lock is
**not** an authenticated toolchain. The source distribution/license and actual
MSVC/Windows SDK versions must be reviewed when introducing this missing lock.
Every file is verified before executing even `clang-cl --version`; extra files,
missing files, links and changed hashes are rejected. Version checks also match
the compiler identity embedded in the pinned SDK manifest.

In PowerShell, from the repository root:

```powershell
python flutter/tool/v8/windows_sdk.py --target windows-x64 `
  --toolchain-root C:\reviewed-toolchain-x64 `
  --toolchain-lock C:\reviewed-locks\windows-x64.json `
  --toolchain-lock-sha256 <independently-reviewed-sha256> `
  --sdk-cache C:\legado-cache\source_sdk `
  --output C:\legado-output\windows-x64
```

Use `windows-arm64` and ARM64 CRT/SDK library directories for the cross-build.
The compiler runs with explicit headers/libraries and cleaned ambient compiler
variables. The output must be a new directory; existing artifacts are never
silently overwritten. A bounds-checked PE parser requires PE32+ DLL architecture,
the ten named ABI exports plus the single pinned V8 unwind handler, mapped function addresses and no forwarded
exports. Static SDK/source/toolchain inputs are verified again after compilation.
The output includes the DLL, import library, licenses and a provenance manifest.
It is a standalone artifact, **not** an entry in the production native hook cache.

## Real Windows runtime acceptance

On a matching Windows Python process, in the same checked-out source revision:

```powershell
python flutter/tool/v8/windows_sdk.py --verify-runtime C:\legado-output\windows-x64
```

This loads the hash-verified real DLL through `ctypes` and exercises the exact V8
version, Intl/Temporal/WebAssembly availability, JavaScript evaluation, persistent
context, Promise completion, asynchronous host request/reply, timeout and recovery.
It writes `runtime-result.json` only when all checks pass. An ARM64 DLL must also
execute in an ARM64 Windows process; cross-link success is not runtime proof.
The original build manifest remains `runtimeTested=false`; retain the separate
hash-bound runtime result as execution evidence.

Next acceptance gates are integration with the Dart native-asset hook, worker
transport and complete source-engine conformance suite, then Windows application
Release startup and actual source requests. This consumer intentionally cannot
turn those gates green merely by recognizing a target name.

## Evidence and present limitations

On the development macOS host, both real published Windows SDK archives were
downloaded and fully checked against the committed release pins. The SDK linking
contract and complete inventories were validated. Ten Python tests cover genuine
release pin shape, toolchain tampering/unindexed payloads, and PE architecture,
truncation, ordinal, export-name, forwarding and DLL requirements.

**No Windows DLL has been built or run on this host.** A reviewed Windows
toolchain inventory and Windows execution capacity are still required. Synthetic
PE fixture tests establish parser behavior, not a successful V8 Windows build.
The runtime probe is ready for a real runner, but cannot replace that runner.

```text
python -m unittest discover -s flutter/tool/v8 -p test_windows_sdk.py -v
```
