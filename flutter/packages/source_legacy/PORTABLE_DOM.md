# Standalone read-only Jsoup host

`PortableLegacyDomHost` consumes the existing `orgJsoup.*` and
`javaHost.domCall` JSON protocol. HTML is parsed with Dart `package:html`.
Supported static entry points are `Jsoup.parse(String[, baseUri])` and
`parseBodyFragment(String[, baseUri])`. XML parsers, constructors, connection
and response leases remain typed unsupported operations.

Supported read members are declared in `readMembers`: selection, normalized
text, own text, HTML/outer HTML, attributes, node/tag/class IDs, child/parent/
sibling traversal, and document head/body/title. Standard CSS selectors use
the HTML package; unimplemented Jsoup selector extensions report errors.
Selections preserve document order and stable schema-2 IDs, so repeated queries
refer to the same V8 wrapper. Attribute/HTML mutation overloads are rejected.
Serialization is deterministic HTML and is not guaranteed byte-identical to
Jsoup's pretty-print output.

Each RPC reconstructs a temporary forest; no native document, connection or
response handle is retained. V8's existing weak wrapper/finalizer protocol owns
the snapshots and alias identities. There is no synthetic connection lease to
release: those APIs fail explicitly. Cyclic/multiply parented/oversized tables
and invalid node IDs are rejected; the node limit matches the native codec's
100000-node bound. This adapter does not grant filesystem or browser access.
