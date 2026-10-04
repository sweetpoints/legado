# source_migration

Conservative legacy book-source migration into schema v1. Generated with the official Flutter package template and converted to pure Dart.

Migration preserves the original, emits a candidate and structured report, and marks unsafe conversions `manualRequired`. Supported straight-line legacy calls can be converted to async `source.*`; dynamic Java, unsupported overloads and complex scripts are retained for manual handling. A generated candidate remains unverified.

See the [migration Reference](../../docs/migration/README.md) and use the [CLI](../source_tools/README.md) for file conversion. Original and candidate execution should be compared before enabling the new source.

From this package, run `dart analyze` and `dart run test:test`. Cross-package equivalence examples are in `source_conformance`.
