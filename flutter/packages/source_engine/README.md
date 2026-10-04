# source_engine

Independent Dart source engine generated from the official Flutter package
scaffold. It has no Flutter SDK dependency.

`SourceDefinition` schema version 1 supports rule-based stages and JavaScript
programs. `SourceEngine.execute` runs search, explore, info, toc and content.
Inject a `ScriptRuntime` implementation from `source_v8`; platform operations
are delegated to an optional `ScriptHost`. Legacy APIs are provided externally
by `source_legacy`, never installed into the new API by default.

Implemented extraction adapters are CSS, JSONPath, XPath and regular expressions.
The new rule dialect supports fallback, concatenation and capture replacement.
Configured pagination tracks visited URLs and raises an error on cycles or limits.
HTTP sessions isolate source cookies, follow redirects, bound concurrency and
support cancellation and request spacing. UTF-8, Latin-1, ASCII, GBK/GB2312/CP936
are available; other charsets need `NetworkClient.charsetDecoder`.

See `../../docs/reference/v1/` for the normative interface reference and
`../../docs/reference/legacy/` for compatibility boundaries.

Run from this package:

```sh
dart analyze
dart test
```

Tests use fixed HTML/JSON and loopback HTTP fixtures. They do not establish
compatibility with every public source or acceptance on Android/iOS devices.
