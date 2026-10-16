# Legacy source regression fixture

`xs520-source.json` is the complete `小说520 / https://www.xs520.com/`
record extracted from the user-provided `backup.zip`, `bookSource.json`.
The workflow test substitutes only `bookSourceUrl` with its loopback transport
origin. It preserves every search/info/TOC/content rule and all other fields.
The HTML pages are synthetic technical fixtures; tests do not query the live
website or contain third-party novel text.

The production `BookSourceService` and V8 runtime execute the original selectors,
regex removal, regex capture replacement, and `@js:` catalogue program. The test
covers search, detail, three ordered TOC pages, content, disabled cookies and
preview isolation. It does not replace these rules with `mainJs`.

The fixture established three independent gaps: absent replacement text after
`##pattern`, CSS/regex/JavaScript pipelines, and legacy program completion values
(`list;`). It also covers the catalogue fan-out that previously required a native
page fetcher, despite an otherwise portable request.

An explicit negative test keeps `org.jsoup` native leases and URL `webView`
options unsupported. Those test assertions establish diagnostics, not successful
source execution. The separate `fengqing_source_workflow_test.dart` now executes the user-supplied
疯情 source through the portable read-only DOM/source-state host. 全本 WebView
and 笔趣阁² connection/raw-request capabilities still require implementation
before claiming complete compatibility.
