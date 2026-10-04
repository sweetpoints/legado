# source_tools

Official Flutter package scaffold converted to a pure Dart command-line package.
Run from the `flutter/` workspace after `flutter pub get`:

```sh
dart run packages/source_tools/bin/source_tools.dart validate source.json
dart run packages/source_tools/bin/source_tools.dart migrate old-source.json --output candidate.json
dart run packages/source_tools/bin/source_tools.dart execute source.json search --variables '{"key":"book","page":1}'
dart run packages/source_tools/bin/source_tools.dart execute source.json info --variables '{"url":"https://example.org/book"}'
dart run packages/source_tools/bin/source_tools.dart execute source.json toc --variables '{"url":"https://example.org/toc"}'
dart run packages/source_tools/bin/source_tools.dart execute source.json content --variables '{"url":"https://example.org/chapter"}'
```

Execution uses the embedded V8 runtime and the real network client. Only run
sources you trust. JSON responses are printed to stdout; errors use the same JSON
format. `--help` lists exit codes. Native V8 build/download output may be emitted
by Dart before the executable starts.

Migration preserves the original file and writes a separate candidate plus
`candidate.json.report.json` (or `--report PATH`). Existing destinations are
rejected. Generated candidates are unverified and return exit code 3; successful
conversion does not establish old-source compatibility. Review the report and
run source-specific conformance checks before enabling a candidate. Execution
accepts the versioned schema. A candidate marked `metadata.legacy: true` enables
the documented `java.*` compatibility prelude. Unsupported legacy features are
reported by migration rather than silently executed with different behavior.
