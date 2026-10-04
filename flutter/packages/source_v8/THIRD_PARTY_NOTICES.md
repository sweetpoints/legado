# Source and license provenance

The bridge follows this repository's GPL-3.0 license (`LICENSE`). V8 and its
upstream dependencies retain their own licenses. Application distributors must
include the applicable license texts and attribution from the actual source
checkout used to produce their native library.

The selected engine is official V8 **15.4.80.24**, pinned to
`e422f6ef0c7b877b04e4872fd0bd3a1cc2ec2eee`, from
[the V8 source repository](https://chromium.googlesource.com/v8/v8/).
Build tools are pinned to depot_tools
`8a5434051036b32412a2ecb10c213a72e3f3ccb9`, from
[the Chromium depot_tools repository](https://chromium.googlesource.com/chromium/tools/depot_tools.git).

| Component | Authoritative provenance |
|---|---|
| V8 engine and bundled headers | [Pinned V8 LICENSE](https://chromium.googlesource.com/v8/v8/+/e422f6ef0c7b877b04e4872fd0bd3a1cc2ec2eee/LICENSE) and AUTHORS in that checkout |
| V8 third-party dependencies | The [pinned DEPS](https://chromium.googlesource.com/v8/v8/+/e422f6ef0c7b877b04e4872fd0bd3a1cc2ec2eee/DEPS), resolved checkouts and their license files |
| Chromium build configuration and C++ runtime | The same DEPS-resolved source/toolchain used by the V8 GN build |
| Build tooling | Pinned depot_tools source and its own licenses; downloaded toolchain packages remain identified by that build's provenance |

No third-party precompiled V8 distribution is the default build input. The
library is built locally from the official source graph; a produced manifest
identifies the selected source, target, GN arguments and final native-library
hash. A manifest and checksum establish provenance/integrity of that artifact,
not proof of functional compatibility or successful device acceptance.

License copies under `licenses/` originated with the earlier V8 14.3 validation
baseline. They must not be treated as a complete, verified notice bundle for
15.4 or any later build. The source build collects V8 LICENSE/AUTHORS and third_party LICENSE*, COPYING*,
NOTICE* and AUTHORS* into artifact/licenses and records each hash in the manifest.
This collection does not establish a complete legal audit of the built dependency
set. Use the resolved checkout and manifest for the actual distribution; do not
reuse the former 14.3 DEPS revisions as 15.4 dependency pins.
