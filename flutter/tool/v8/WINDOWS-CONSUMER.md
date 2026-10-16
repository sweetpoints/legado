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

## Collecting a reviewable lock on GitHub Windows runners

`windows_toolchain_receipt.py` closes the missing-input gap without trusting an
arbitrary runner toolchain automatically. Its operations are separated:

1. `fetch-clang` downloads only the official Chromium Windows archive pinned in
   `windows-pins.json`, validates its 51,467,544-byte size and SHA-256, extracts
   with bounded safe paths, and validates the complete 346-file inventory.
2. `collect` reads only the explicitly supplied Clang, MSVC and Windows SDK roots,
   hashes the relevant target headers/libraries, checks required CRT inputs and
   the already authenticated compiler version, and writes `candidate-lock.json`
   and `receipt.json`. It does not modify the installed toolchain, enumerate the
   environment or credentials, compile a bridge, or approve its own candidate.
3. After independent review, `stage` copies only the approved lock's files into a
   new task directory and verifies the exact inventory. Changed source files,
   changed lock bytes and existing output directories are rejected. The consumer
   then receives that independent reviewed digest, as before.

The fixed Release production record is materially more specific than a moving
`windows-2025` label:

- [Production run 37449592798, Windows x64 job 112223133670](https://github.com/sweetpoints/v8-prebuilt/actions/runs/37449592798/job/112223133670)
  identifies `windows-2025-vs2026`, image `20260925.250.1`.
- The [matching official image metadata](https://github.com/actions/runner-images/blob/win25-vs2026/20260925.250/images/windows/Windows2025-VS2026-Readme.md)
  identifies VS Enterprise 2026 `18.10.12217.157`. The Release manifest does **not**
  record the exact MSVC tools subdirectory version; collect its explicit version
  file and hashes instead of inferring it from the Visual Studio product version.
- The [producer's fixed workflow](https://github.com/sweetpoints/v8-prebuilt/blob/32c52a8fafcee88e8ad989810cdeb24310597c14/.github/workflows/release.yml#L140)
  requires Windows SDK `10.0.28000.0` and pins Microsoft's installer SHA-256 to
  `e8e264aca8bc34abdf592352bf09f986a85cdb96db0efba405ca9e149181fe61`,
  with a valid Microsoft Authenticode signature. Installing that SDK is a separate
  runner setup action; the receipt collector never performs system installation.
- The ordinary Windows 2025 [metadata at reviewed commit ee1ff4569e381a9649b400338bdc8ceb7e18a80e](https://github.com/actions/runner-images/blob/ee1ff4569e381a9649b400338bdc8ceb7e18a80e/images/windows/Windows2025-Readme.md)
  identifies image `20261013.288.2`, VS 2022 `17.14.37710.0`, and installed SDK
  `10.0.26100.0`. This is a candidate **compatibility toolchain**, not proof of an
  identical producer environment.

The following steps can be added to the existing Windows storage CI job. They
collect its installed SDK `10.0.26100.0` for review and upload only JSON metadata.
They do not write a trusted lock back to the repository or claim V8 compatibility.
Select `10.0.28000.0` explicitly instead if the separately reviewed producer SDK
installation step has been used; missing versions fail without choosing another.
Reuse the workflow's already reviewed upload-artifact action pin.

```yaml
- name: Collect candidate Windows V8 toolchain receipt
  shell: pwsh
  run: |
    $ErrorActionPreference = 'Stop'
    $collector = 'flutter/tool/v8/windows_toolchain_receipt.py'
    $clangRoot = Join-Path $env:RUNNER_TEMP 'legado-reviewed-windows-clang'
    python $collector fetch-clang --output $clangRoot
    if ($LASTEXITCODE -ne 0) { throw 'Fixed Clang download verification failed' }
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
    $vsRoot = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if ([string]::IsNullOrWhiteSpace($vsRoot)) { throw 'No candidate MSVC installation' }
    $versionFile = Join-Path $vsRoot 'VC/Auxiliary/Build/Microsoft.VCToolsVersion.default.txt'
    $msvcVersion = (Get-Content $versionFile -Raw).Trim()
    $msvcRoot = Join-Path $vsRoot "VC/Tools/MSVC/$msvcVersion"
    $sdkRoot = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits/10'
    python $collector collect --target windows-x64 `
      --clang-root $clangRoot --msvc-root $msvcRoot --msvc-version $msvcVersion `
      --msvc-version-file $versionFile `
      --sdk-root $sdkRoot --sdk-version '10.0.26100.0' `
      --image-version "$env:ImageVersion" --source-sha '${{ github.sha }}' `
      --output 'build/windows-v8-toolchain-candidate'
    if ($LASTEXITCODE -ne 0) { throw 'Candidate toolchain receipt failed' }
- name: Upload candidate Windows toolchain metadata
  # Use the workflow's existing SHA-pinned actions/upload-artifact.
  uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a
  with:
    name: windows-v8-toolchain-candidate
    path: build/windows-v8-toolchain-candidate/
    if-no-files-found: error
```

Independent review must check the receipt against that exact CI run/commit and
its official runner image record, confirm the numeric MSVC tools and SDK versions,
confirm the fixed Clang archive/version, inspect the selected root mappings and
expected target CRT/import library families, and compare the complete inventory
for unexpected changes. Record the exact reviewed `candidate-lock.json` SHA-256
as an immutable consumer input. A candidate from SDK 26100 must pass real linkage
and native runtime acceptance before treating it as compatible with the producer's
SDK 28000. Do not change the consumer to accept `receipt.approved=false` as proof.

After reviewing an exact lock, materialization and compilation are explicit:

```powershell
$reviewedSha = '<independently-reviewed-candidate-lock-sha256>'
python flutter/tool/v8/windows_toolchain_receipt.py stage `
  --receipt-dir build/windows-v8-toolchain-candidate `
  --expected-lock-sha256 $reviewedSha --output C:\legado-toolchain-staged
python flutter/tool/v8/windows_sdk.py --target windows-x64 `
  --toolchain-root C:\legado-toolchain-staged `
  --toolchain-lock build/windows-v8-toolchain-candidate/candidate-lock.json `
  --toolchain-lock-sha256 $reviewedSha --output C:\legado-output\windows-x64
python flutter/tool/v8/windows_sdk.py --verify-runtime C:\legado-output\windows-x64
```

A future job running on a different image must revalidate every approved hash;
image drift causes failure and a new candidate review, not silent adoption.

## Reviewed x64 compatibility build and native CI

The candidate captured by run `38945901818`, job `117124818232`, has now been
independently reviewed for **compilation and compatibility testing**. Its exact
lock is committed as `windows-reviewed-x64-lock.json`, SHA-256
`36c9e96bc7121600be930cb7700599a9864a4e6d5a5a4e5288357cee0c8d3fb5`.
The review record is `windows-reviewed-toolchain.json`. The artifact's source SHA
`167e259e2d9d674e4fe45d049426cd34fa65595c` is the GitHub PR merge commit whose
second parent is reviewed branch HEAD `475093507764b233397e7574a721beebec0e24f1`;
the API parent record and job checkout log were checked independently.

The review covered all **6,123 files**: official Clang 346, target MSVC 517,
Windows SDK 5,260, including exact lowercase SHA-256 format, relative-path safety,
case-insensitive name uniqueness, architecture-specific CRT directories, and all
27 system libraries recorded by the fixed Windows V8 SDK. The actual MSVC tools
version is `14.51.36231`; the SDK is `10.0.26100.0`. Approval authorizes attempting
the real build. It does not claim ABI compatibility, runtime or release acceptance
before those tests execute; the producer used SDK `10.0.28000.0`.

`windows_ci.py --prepare-dynamic windows-x64` authenticates that exact installed
payload, materializes it without changing the installed toolchain, and builds only
the bridge. Any image drift fails against the reviewed lock. The Dart native hook
can consume the resulting DLL with current source digest, consumer inputs,
reviewed toolchain hash, fixed SDK provenance and DLL checksum validation. Windows
path containment uses URI directory boundaries, preserving checks on native
backslash paths. ARM64 remains excluded from the hook until its separate target
library inventory has been reviewed and actually cross-linked.

The separate `.github/workflows/flutter-windows-v8.yml` job performs x64 DLL
compilation, the real native FFI probe with normal process exit, all Dart
`source_v8` tests and the complete `source_conformance` suite, preserving exact
native artifact/evidence. A second job collects ARM64 target libraries on the x64
host for independent review. It does not claim ARM64 execution or compilation.
