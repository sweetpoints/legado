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
format. `--help` lists exit codes. Dart may emit native asset hook output before
the executable starts; the hook requires the verified local source-built V8
artifact and does not download a fallback binary.

Migration preserves the original file and writes a separate candidate plus
`candidate.json.report.json` (or `--report PATH`). Existing destinations are
rejected. Generated candidates are unverified and return exit code 3; successful
conversion does not establish old-source compatibility. Review the report and
run source-specific conformance checks before enabling a candidate. Execution
accepts the versioned schema. A candidate marked `metadata.legacy: true` enables
the documented `java.*` compatibility prelude. Unsupported legacy features are
reported by migration rather than silently executed with different behavior.

Legacy exports containing JSON arrays are accepted by `audit` and `migrate`:

```sh
dart run packages/source_tools/bin/source_tools.dart audit exported-sources.json --report audit.json
dart run packages/source_tools/bin/source_tools.dart migrate exported-sources.json --output migration-batch
```

Batch migration requires a new output directory. Every array position has an
indexed report, including invalid entries; a candidate is written only when
import succeeds. `audit.json` records SHA-256 of the exact input bytes, source
count, status counts, per-capability issue counts, and indexed metadata. The
stdout summary contains no original source bodies or URLs. Audit executes no
scripts or requests. `unverified`, `manualRequired`, and `noExecution` describe
analysis only; neither audit nor migration establishes compatibility. Both
commands return exit code 3 to require review. Preserve the original export to
reproduce the exact input hash.

CLI engine assembly provides the versioned `encoding.*` and `crypto.*` utility
host for modern sources and migrated candidates. Legacy sources additionally
receive the `java.*` host adapter. Modern sources never receive that adapter or
its JavaScript prelude. Browser and interactive platform capabilities remain
unavailable in the CLI and fail with an explicit error.

## Compare a migration candidate

```sh
dart run packages/source_tools/bin/source_tools.dart compare old.json candidate.json content --variables '{"url":"https://example.org/chapter"}' --report comparison.json
```

This imports the original through the Flutter legacy compatibility layer and
executes each side in a separate engine. Object key order is ignored; array order
and value types matter. A candidate still using legacy mode is allowed and
identified. Exit code `0` means this case produced equivalent results, `4` means
different results, and `1` means an execution failed. Reports contain input/result
hashes and stable error codes without result bodies or source content. Existing
reports are never replaced.

`caseEquivalent` applies only to this stage and these variables. `sourceVerified`
and `verified` remain false; `jvmCompared` is false because the original JVM engine
is not run. Network calls are live and can produce different responses; time,
randomness, cookies, and service-side changes can also affect comparison.
