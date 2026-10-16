# Linux main application acceptance

The independent `Flutter Linux app` workflow uses **native x64 Ubuntu 26.04**.
It consumes the fixed published V8 SDK; it never builds V8 sources. The system
Clang/GCC packages build Flutter's GTK runner. V8's bridge separately uses the
SHA-pinned Chromium Clang/LLD, Chromium libc++ and Debian sysroot consumer.

The packages follow Flutter 3.47's [official Linux setup guide](https://docs.flutter.dev/platform-integration/linux/setup):
Clang, CMake, Ninja, pkg-config, GTK 3 development headers and the distribution's
C++ development toolchain. The guide lists `libstdc++-12-dev`; Ubuntu 26's default
`g++` selects its supported newer libstdc++ development package. Xvfb, Xauthority,
a session D-Bus, software Mesa and CJK fonts provide the headless desktop test
environment. Whole-machine `apt upgrade` is unnecessary.

The job keeps these claims separate:

1. The `source_v8` package's native hook actually prepares, authenticates, bundles
   and executes its V8 runtime tests on the native runner.
2. `flutter build linux --release --no-pub` compiles the main GTK application and
   Flutter AOT output. The bundle verifier checks the executable, AOT library,
   and bundled V8 ELF types/architectures, then compares the exact bundled V8
   hash with the reauthenticated native-hook artifact. It includes SDK-verified
   V8 notices and a SHA-256 inventory in the archived bundle.
3. Xvfb and a real session D-Bus run
   `flutter test integration_test/native_app_test.dart -d linux`. These tests
   launch the actual GTK main application rather than `flutter_tester`. They
   exercise file-backed Drift reopen and UTF-16 progress, the real HTTP/V8
   source pipeline with cookies/sessions, and the bookshelf/reader/language UI.

The integration suite uses Flutter's normal debug integration configuration;
the separate Release compilation and bundle integrity check do **not** claim
that the UI integration suite ran in Release mode. The bundle verifier records
`guiRuntimeAccepted=false`; successful native UI tests are independent evidence.

The job saves native toolchain details, V8 logs, Release build logs, native app
test logs, bridge provenance and manifest, the complete Release bundle inventory
and a tarball. Failures preserve available logs with `if: always()`. Existing
successful runs are not cancelled when another revision arrives.

Native Ubuntu 26 CI must finish successfully before reporting Linux native app
acceptance. A local amd64 Docker process on an ARM64 machine runs through
emulation and is only supporting reproduction evidence. A local GTK result,
Dart-only result or a green workflow from another commit is not that acceptance.
The five-platform application migration remains broader than this suite.
