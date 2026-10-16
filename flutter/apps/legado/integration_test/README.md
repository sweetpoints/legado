# Main application native acceptance

Run this suite against the real Flutter application, with its native V8 and SQLite
assets. These are device/desktop integration tests, not `flutter_tester` widget
acceptance. The app needs the Flutter SDK's `integration_test` dev dependency.

From `flutter/apps/legado`:

```sh
flutter test integration_test/native_app_test.dart -d macos
flutter test integration_test/native_app_test.dart -d EXPLICIT-ARM64-IOS-SIMULATOR-UUID
```

For iOS, select and boot an explicit ARM64 simulator before running the command.
This selects the simulator ARM64 SDK through the normal native-assets hook; it
avoids requesting an unpublished Intel iOS simulator SDK. A general universal
simulator build additionally needs the app project's existing exclusion of x86_64
and i386. No test-specific Podfile, entitlement, release signing or V8 source build
is used by this suite.

The macOS app's normal Debug entitlements must allow JIT, network client and network
server. The HTTP fixture listens only on loopback and uses an ephemeral port. Its
content and credentials are generated test fixtures; no public book site is called.
Python 3.12+ and Xcode SDKs are required by the fixed V8 consumer. The source_v8 hook
supports `SOURCE_V8_PYTHON` for Xcode environments hiding package-manager Python.

## What executes

1. Open a real **file-backed** Drift database in the app sandbox's temporary
   directory, import TXT content, save a reading location, close the background
   database and reopen its file. Verify book, chapter, cached text and settings.
2. Use `BookSourceService`'s production default V8 runtime and real loopback HTTP
   for legacy mainJs search, details, catalogue and chapter content. Verify cached
   chapter content, explicit bookshelf insertion, request headers, cookies and
   Java variables after closing/reopening both the service and the Drift file.
   No engine factory, script runtime, HTTP client or repository is replaced.
3. Launch the real `LegadoApp` under a Riverpod container overriding only its
   persistent library location with another real file-backed Drift repository.
   Tap the actual book, read text, navigate to the next chapter, scroll to save a
   UTF-16 location, change language using the settings dropdown, dispose the app
   and reopen the database. Re-launch the application and verify English UI and
   the resumed chapter/position. No OS file chooser or `FileExchange` fake is used.

The UI fixture explicitly has two chapters. Body text must not itself match the
local TXT heading recognizer. Route contents can exist while their incoming slide
animation remains outside the viewport; the readiness helper waits for a stable
render before interacting. Missed pointer hit tests are fatal, not suppressed.

Every test creates and cleans up only its own temporary database directory. It
does not open, clear or migrate the user's application-support database. Provider
state, database workers, source sessions and HTTP server are closed before cleanup.

## Verified local execution (2026-10-17)

The suite passed **3/3 on macOS ARM64** and **3/3 on an iOS 27 ARM64 simulator**,
using actual compiled main application bundles. iOS Xcode build took 18.1 seconds;
the tests completed in approximately 7 seconds. The macOS suite completed in
approximately 6 seconds after building the application. Integration-test static
analysis passed without issues.

This validates the listed native baseline. It does not establish physical iPhone
Release signing, all legacy book sources, OS file chooser behavior or completion
of every page in the application migration.
