# source_legacy

Legacy book-source import and the versioned `java.*` compatibility host for the independent Dart engine. Generated with the official Flutter package template and converted to pure Dart.

`LegacySourceImporter` preserves the original input, maps supported fields and reports unsupported capabilities. `legacyScriptPrelude` and `LegacyScriptHost` provide the documented synchronous legacy methods through V8; async methods belong to the new `source.*` API.

See the [legacy Reference](../../docs/reference/legacy/README.md) for exact methods, overloads and rule limits. Unsupported Java class interop and JSoup-specific rules require migration rather than silent reinterpretation.

From this package, run `dart analyze` and `dart run test:test`. Real V8 and network compatibility are exercised by `source_conformance`.

`PortableLegacyRuleEvaluator` is an explicit standalone capability adapter for
legacy CSS → regex replacement → pure V8 transformation pipelines. It preserves
program completion values using auxiliary runtime evaluation, and exposes the
legacy catalogue fan-out marker without requiring Android network callbacks.
The importer continues to retain original rules and review issues; the standalone
App removes only operation-local `legacy.rule_requires_review` gates whose exact
rule is accepted by this adapter. Unsupported browser/native-host tokens retain
review or typed errors. Source compatibility remains unverified, not globally
certified by the adapter.

The regex path implements numeric Java replacement references and missing/empty
replacement strings for the tested common subset. Java inline flags, atomic or
possessive constructs, named groups and Java-specific escapes are rejected.
Dart's [ECMAScript regexp semantics](https://api.dart.dev/dart-core/RegExp-class.html)
are not a general replacement for Java Pattern; Unicode-sensitive dialect parity
outside the tested subset still needs independent verification. See Java's
[Matcher replacement contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/regex/Matcher.html)
for group and escaping semantics.
