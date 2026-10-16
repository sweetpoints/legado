# V8 SDK consumer migration status

Verified 2026-10-17 against the immutable
[sweetpoints/v8-prebuilt v8-15.4.80.25 release](https://github.com/sweetpoints/v8-prebuilt/releases/tag/v8-15.4.80.25).
The authenticated release manifest has SHA-256
`d01f58c45b713edcccf3fdc3bc5969d2eb417773d895522e3ae330df8c71f0f2`.
SDK publication, Legado bridge linking, and application acceptance are separate results.
Legado downloads the published pure SDK and compiles **only its own bridge**.

| Target | Published pure SDK | Legado consumer | Remaining acceptance or implementation |
| --- | --- | --- | --- |
| Android ARM64 / x64 | Yes | Existing fixed SDK + bridge + Flutter hook | Existing Android runtime coverage; full migrated app acceptance still required |
| macOS ARM64 | Yes | Existing fixed SDK + bridge + Flutter hook | Native V8 regression runs locally; migrated app Release acceptance still required |
| macOS x64 | Yes | Added fixed SDK + bridge + Flutter hook | Real x86_64 bridge linked and ten ABI exports inspected; no x64 runtime result on this ARM64 host without Rosetta |
| Linux x64 / ARM64 | Yes | Not yet enabled | Fixed Chromium sysroot, ELF GNU/Linux linker profile, rescan library grouping, export validation, and native runtime CI |
| Windows x64 / ARM64 | Yes | Not yet enabled | Fixed Clang/clang-cl and MSVC SDK, DLL naming, `__declspec(dllexport)` ABI exports, PE validation, Windows-compatible atomic publish lock, native runtime CI |
| iOS ARM64 device / simulator | Yes, jitless profile | Not yet enabled | iOS framework/native-asset packaging, platform-specific target selection, signing, jitless runtime and source compatibility acceptance |

`consumerConfigured` means a pinned SDK and consumer target exist. It does not mean
that a full application Release has passed on that platform. To inspect the fixed
release without downloading any SDK, use an already downloaded manifest:

```sh
python3 flutter/tool/v8/platform_readiness.py \
  --release-manifest /absolute/path/to/release-manifest.json
```

The audit authenticates the complete manifest before inspecting its targets. It
rejects local source/target/asset pin disagreement and unexpected iOS feature flags.
`--require-all-consumers` exits nonzero until all requested targets have consumer
configuration; it deliberately does not assert application runtime acceptance.

## macOS x64 implementation evidence

The SDK archive hash is
`81db90fbe699202637b57f4bc517e52d9a94fb75f9612044629604328dd50723`
and its internal manifest hash is
`29c551f5235375eeda6dc016d23004b620fdeaaf22db005cdfab6f1e56c5cce4`.
Both are pinned in `release-pin.json`. The SDK retains the release's embedded ICU,
Temporal and upstream-default JIT/WebAssembly profile. Its Chromium libc++ ABI and
compiler identity checks remain enforced.

A real bridge was linked locally with the fixed Chromium Clang, using
`--target=x86_64-apple-macos13.0` and the Apple macOS SDK. The generated artifact
passed thin Mach-O CPU/type inspection and exact ten-symbol ABI inspection.
The existing ARM64-host Clang can cross-link this target. This does **not** add
Intel-host toolchain download support: only existing verified macOS ARM64 and Linux
x64 compiler host archives are pinned. A native Intel build host still requires a
reviewed toolchain pin before automatic preparation can work there.

No Rosetta installation, V8 source build, release mutation or runtime fallback was
performed. Hardened Runtime application packaging still requires its existing JIT
entitlement and a separate signed Release execution test for each architecture.

## iOS feature and packaging boundary

The published iOS device and simulator SDKs set all of the following:

```text
v8_jitless = true
v8_enable_turbofan = false
v8_enable_maglev = false
v8_enable_sparkplug = false
v8_enable_webassembly = false
v8_enable_i18n_support = true
v8_enable_temporal_support = true
```

This is an explicit target feature difference. Desktop JIT/Wasm tests must not be
reported as iOS results. The SDK release records device and simulator link smoke
success, **not runtime or source compatibility acceptance**. Its link smoke records
a weak `BrowserEngineKit` dependency, with no required undefined framework symbols;
the consumer must retain and inspect that weak dependency rather than converting it
to a strong link or making the whole app require that framework at launch.

The existing consumer selects targets only by OS and architecture, which cannot
separate iOS device ARM64 from simulator ARM64. It also only emits bundled `.so`
or macOS `.dylib` artifacts. iOS must select the actual build environment, package
an appropriate signed framework/native asset, retain required exported symbols,
and test cancellation, synchronous host replies, microtasks and persistent sessions
with the jitless runtime. Merely adding `OS.iOS` to the target selector would skip
these requirements and remains intentionally rejected.
