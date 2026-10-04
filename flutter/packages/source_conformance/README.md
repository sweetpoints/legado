# Source conformance

Created with the official Flutter package scaffold and converted to a pure Dart workspace package. Tests exercise the independently implemented source engine, legacy import, migration, and real V8 runtime against a loopback HTTP server. No public book-source service or user credentials are required.

Run from this package using `dart run test:test`. This entry point invokes native V8 build hooks; a plain `dart test` invocation does not prepare the required native assets.

Coverage includes:

- Search, book detail, table of contents and正文 over a session-protected fixture site.
- Relative URL resolution and Chinese response content.
- Promise-based HTTP host calls executed by the real V8 engine.
- Synchronous legacy `java.ajax`, encoding and variable access backed by Dart host calls.
- Legacy source import and migration result equivalence.
- Migrated asynchronous script execution and unsupported Java interop reporting.
- HTTP cancellation, timeout distinction, and post-cancellation runtime recovery.

These fixed cases prove only their covered contracts. They do not constitute complete compatibility acceptance for arbitrary public Legado sources or Android device validation.

## Legacy compatibility expansion

Real V8 tests also cover `connect`, HTTP/variable overloads of `get`, `post`, `head`, ordered `ajaxAll`, response method facades, byte arrays, GBK/UTF-16 round trips, Base64 flags, rejected overloads and cancellation after both batched requests start.

`test/support/jsoup_golden.json` contains nine fixed extraction expectations generated with JSoup 1.23.2. `JsoupGolden.java` reproduces the selected `AnalyzeByJSoup.getResultLast` operations and explicit index selection from the old implementation. Regenerate with `java -cp <jsoup-1.23.2.jar> test/support/JsoupGolden.java > test/support/jsoup_golden.json` from this package. The test does not require Java or that JAR at runtime.

These cases compare text, own text, text nodes, nonblank deduplicated attributes, inclusive ranges, reverse order, selected indices and exclusions. HTML pretty-print serialization is outside these golden assertions; they are not a complete JVM rule-engine execution oracle.
