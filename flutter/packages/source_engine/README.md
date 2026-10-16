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

The automatic cookie jar defaults to enabled. Set `metadata.cookieJarEnabled`
to `false` to disable automatic cookie sending, response capture, browser-cookie
imports and session-cookie restore/export. A legacy original snapshot's explicit
`enabledCookieJar` flag remains authoritative. Explicit `Cookie` request headers
are retained for same-origin requests; cross-origin redirects keep stripping
sensitive headers. Response cookie metadata remains readable without saving it.

Cookie policy is immutable for a source identity inside one engine. Recreate the
engine after changing the policy. A disabled source receives its own jar-free
network client even when an enabled shared client was injected; its charset
adapter and scheduling limits are retained, and its stored cookies are not
installed into that shared client. Source variables still restore normally.

See `../../docs/reference/v1/` for the normative interface reference and
`../../docs/reference/legacy/` for compatibility boundaries.

Run from this package:

```sh
dart analyze
dart test
```

Tests use fixed HTML/JSON and loopback HTTP fixtures. They do not establish
compatibility with every public source or acceptance on Android/iOS devices.
