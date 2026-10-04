# Third-party components

The bridge follows this repository's GPL-3.0 license (LICENSE). The embedded
components retain their own licenses. Application distributors must preserve
these notices and license texts with the distributed application.

| Component | Pinned source | License text |
|---|---|---|
| V8 | v8/v8 tag 14.3.92; binaries from haroel/v8-build release v14.3.92-15 | licenses/V8.txt, licenses/V8-authors.txt (BSD) |
| Abseil | V8 DEPS Chromium commit 5b92b04a2ed98498a7f03f9234bbad7752445a43 | licenses/Abseil.txt (Apache-2.0) |
| FP16 | V8 DEPS commit 3d2de1816307bac63c16a297e8c4dc501b4076df | licenses/FP16.txt (MIT) |
| FastFloat | V8 DEPS commit cb1d42aaa1e14b09e1452cfdef373d051b8c02a4 | licenses/FastFloat.txt (MIT option) |
| Highway | V8 DEPS commit 84379d1c73de9681b54fbe1c035a23c7bd5d272d | licenses/Highway.txt (Apache-2.0) |
| simdutf | V8 DEPS Chromium commit acd71a451c1bcb808b7c3a77e0242052909e381e | licenses/SimdUtf.txt (MIT option) |
| Wasm C API | V8 14.3.92 bundled headers | licenses/WasmApi.txt (Apache-2.0) |
| LLVM libc++ | Chromium mirror commit 89b5f99ebd15557154c4d718717173d6fa7b13d3 | licenses/LLVM.txt (Apache-2.0 WITH LLVM-exception) |
| LLVM libc++abi | Chromium mirror commit 8e720a3a3ae30fcfffe436aae418c91acacc34d0 | licenses/LLVM.txt |
| LLVM libc headers | Chromium mirror commit 27b37b761098b8a3af5a4b2e6de04a1473c9544f | licenses/LLVM.txt |
| Chromium C++ configuration | buildtools commit 356dd5473fd88c2bb110fbcfad7354605a5b9fe4, third_party/libc++/__config_site and __assertion_handler | licenses/Chromium.txt; assertion header also carries LLVM notice |

The downloaded V8 archive contains bundled third-party object code; corresponding
V8 source notices are available at
https://chromium.googlesource.com/v8/v8/+/refs/tags/14.3.92/LICENSE
and the individual third_party dependencies pinned by that tag's DEPS.
Binary ZIP archives use byte SHA-256 pins. Source trees use canonical SHA-256
(sorted UTF-8 file paths and complete contents, each length-prefixed by unsigned
64-bit big-endian length). Gitiles request timestamps are excluded. Duplicate
paths, traversal and symbolic links are rejected before extraction.

Android archives require Chromium's std::__Cr ABI version 2 and relative
vtables. The hook compiles the exact V8 DEPS runtime sources with those settings.
The linker rejects unresolved symbols and an export map exposes only sv8_*,
keeping the embedded C++ runtime local to this library.
