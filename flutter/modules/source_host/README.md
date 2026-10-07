# source_host

Official Flutter module hosting the Dart source engine inside the existing Android application. It runs a Dart entry point without displaying Flutter UI; Compose continues to use the Kotlin repository.

The module combines `source_engine`, `source_v8`, `source_legacy` and `source_platform`. A real V8 startup self-check precedes the protocol v1 ready handshake. Requests carry task IDs for cancellation, and cached source engines preserve isolated HTTP sessions while bounding idle cache size.

Build from the repository with [build-android.sh](../../tool/build-android.sh). See the [Android Reference](../../docs/reference/v1/android.md) for AAR enablement, source selection and protocol. The module does not migrate the application UI.

Run `flutter analyze` and `flutter test` from this module. Android native execution is covered by the application's `FlutterSourceEngineTest` instrumentation class.
