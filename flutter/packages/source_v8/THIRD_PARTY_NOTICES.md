# Source and license provenance

The bridge follows this repository's GPL-3.0 license (`LICENSE`). V8 and its
upstream dependencies retain their own licenses. Application distributors must
include the applicable license texts and attribution from the actual source
checkout used to produce their native library.

The selected engine is official V8 **15.4.80.25**, pinned to
`c45871fec706a6e7b715e607065bb4578b23ce9f`, from
[the V8 source repository](https://chromium.googlesource.com/v8/v8/).
Build tools are pinned to depot_tools
`8a5434051036b32412a2ecb10c213a72e3f3ccb9`, from
[the Chromium depot_tools repository](https://chromium.googlesource.com/chromium/tools/depot_tools.git).

| Component | Authoritative provenance |
|---|---|
| V8 engine and bundled headers | [Pinned V8 LICENSE](https://chromium.googlesource.com/v8/v8/+/c45871fec706a6e7b715e607065bb4578b23ce9f/LICENSE) and AUTHORS in that checkout |
| V8 third-party dependencies | The [pinned DEPS](https://chromium.googlesource.com/v8/v8/+/c45871fec706a6e7b715e607065bb4578b23ce9f/DEPS), resolved checkouts and their license files |
| Chromium build configuration and C++ runtime | The same DEPS-resolved source/toolchain used by the V8 GN build |
| Build tooling | Pinned depot_tools source and its own licenses; downloaded toolchain packages remain identified by that build's provenance |

The engine input is the SHA-pinned pure SDK from
[sweetpoints/v8-prebuilt v8-15.4.80.25](https://github.com/sweetpoints/v8-prebuilt/releases/tag/v8-15.4.80.25),
built there from official sources. Legado compiles only its own GPL bridge and
links the SDK's authenticated static libraries and matching headers. The SDK
itself contains no Legado bridge. License files from the actual SDK archive are
copied into the bridge artifact and individually indexed by SHA-256.

Legacy copies under the tracked package licenses directory originated with an
earlier validation baseline; use the downloaded SDK's license inventory for the
actual distribution. License collection and provenance checks do not establish
a complete independent legal audit or functional/device acceptance.
