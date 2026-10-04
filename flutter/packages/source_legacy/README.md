# source_legacy

Legacy book-source import and the versioned `java.*` compatibility host for the independent Dart engine. Generated with the official Flutter package template and converted to pure Dart.

`LegacySourceImporter` preserves the original input, maps supported fields and reports unsupported capabilities. `legacyScriptPrelude` and `LegacyScriptHost` provide the documented synchronous legacy methods through V8; async methods belong to the new `source.*` API.

See the [legacy Reference](../../docs/reference/legacy/README.md) for exact methods, overloads and rule limits. Unsupported Java class interop and JSoup-specific rules require migration rather than silent reinterpretation.

From this package, run `dart analyze` and `dart run test:test`. Real V8 and network compatibility are exercised by `source_conformance`.
