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
