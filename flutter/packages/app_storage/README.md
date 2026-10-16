# app_storage

Drift implementation of `app_core.LibraryRepository`, generated with the
`dart create -t package` official scaffold. Database operations run through generated typed Drift tables in a
Drift-managed native worker isolate; Flutter callers use the asynchronous repository interface.

```dart
final library = await SqliteLibraryRepository.open(applicationPrivatePath);
await library.putBook(book);
final files = await library.backupFiles();
await library.close();
```

`inMemory()` creates an isolated temporary database for tests. The caller supplies
an application-private file path with an existing parent directory. Platform
application directories, backup ZIP extraction and UI are intentionally outside
this package. Always close a repository before deleting or moving its database.

## Durability and identity

Schema version 4 is stored using SQLite `user_version`. Schema installation and
restores are transactions. Opening a future schema refuses it without rewriting
its data. SQLite serializes writes and a 5-second busy timeout permits short
concurrent accesses; each repository processes its own requests in FIFO order.
Notifications follow successful commits, and failed writes emit no notification.

Books use their exact `bookUrl`; legacy sources use the exact `bookSourceUrl`,
while modern sources use `id`. Identifiers need not be URLs. Chapter and content
identities include the book identity. Chapter ordering uses its stable index;
replacing a chapter list preserves cached content for unchanged URLs. Removing a
book removes its associated chapters/content. Preview chapters may be cached
without inserting a book into the shelf. Books and sources retain their complete
JSON including unknown nested fields. Progress is non-negative and retains the
other book fields. `setSetting(key, null)` deletes that setting.

## Restore and export

`restoreBackup` takes the validated, already-extracted `LegacyBackupDocument`.
All present supported tables replace their corresponding stored tables in **one
transaction**. Missing entries preserve existing tables; a present empty list or
map clears that table. Invalid identities, duplicate sources/chapters, unknown
extension versions and invalid data roll back the entire restore. Omitted
chapter/content sections do not clear cached chapters/content when restoring a
legacy shelf-only backup.

Export regenerates `bookshelf.json` and `bookSource.json` from current data, so
post-import progress edits, book changes and source edits are reflected. Other
legacy files are preserved as opaque text, including unknown files and settings
formats not yet interpreted by Flutter. `legado-library.json` adds:

- `version: 1`
- `chapters`: `[{bookUrl, chapter: {url, title, index, isVolume}}]`
- `content`: `[{bookUrl, chapterUrl, content}]`
- `settings`: a string-to-string map

Unknown extension-level fields and chapter JSON fields survive export. The
backup entry names are parsed by `LegacyBackupDocument`, never treated as paths
to read. This library does not log source data or error parameter bindings.
Database files and exports can contain source variables and private reading
information; callers must store them in private directories and only expose an
export through an explicit user action. Encryption and cross-device sync are not
implemented here.

## Complete archive retention

The repository also implements `ArchiveLibraryRepository`. `backupSnapshot()`
reads the current text export and opaque BLOB entries in one SQLite transaction.
`restoreArchive(document, retainedEntries)` atomically restores text data and
replaces the complete retained-entry set. An empty retained-entry map explicitly
clears that set. Text-only `restoreBackup` preserves existing retained entries.
Byte buffers are copied on entry and snapshots are immutable. Images, unknown
binary files and opaque encrypted `servers` data are preserved byte-for-byte;
this package never decrypts or interprets them.

Retained archive names must be safe relative entry names and cannot collide with
this restore's text files or managed JSON entries. Invalid names, invalid byte
values or a late text/SQL error roll back text and BLOB changes together. A
text-only restore colliding with an existing opaque entry is rejected rather than
silently replacing its bytes. In a full archive restore, a retained entry replaces
an older opaque text entry of the same name. Callers should use the snapshot API
when constructing ZIP exports; `backupFiles()` intentionally returns only text.

## Drift schema and generation

`LibraryDatabase` extends the generated Drift `GeneratedDatabase` implementation.
Nine typed tables and generated companions implement every repository query and
write. `MigrationStrategy` creates the schema transactionally, adopts the
pre-Drift version-1 layout without rewriting records, adds the opaque BLOB
entry table in the 1-to-2 migration, and refuses unsupported
schema transitions. No custom hand-written SQLite RPC layer remains.

Generate the checked-in table implementation with:

```sh
dart run build_runner build
```

`drift: ^2.35.2` uses `drift_dev: ^2.35.1` (the latest published generator;
there is no 2.35.2 generator release). The generator explicitly supports drift
2.35.x and analyzer 13.x/14.x; this package resolves with analyzer 13.3.0 and
build_runner 2.16.1 without overrides. File databases use
`NativeDatabase.createInBackground`; temporary memory databases use
`DriftIsolate.spawn` plus a single-client connection. Closing the repository
drains accepted work and terminates its database connection.

The [official native platform documentation](https://drift.simonbinder.eu/platforms/vm/)
and [isolate documentation](https://drift.simonbinder.eu/isolates/) describe this
five-platform setup. The [table documentation](https://drift.simonbinder.eu/dart_api/tables/)
describes generated Dart tables. Verified on 2026-10-17.

## Native dependency

`sqlite3: ^3.7.1` uses package build hooks and bundles its own SQLite native
assets. No Android-specific database plugin or system SQLite assumption is used.
The upstream package documents Android ARM/x64, iOS device/simulator, macOS
ARM64/x64, Linux and Windows assets. This was verified against the official
[sqlite3 package documentation](https://pub.dev/packages/sqlite3) on 2026-10-17.
The [upstream repository](https://github.com/simolus3/sqlite3.dart) owns the native
asset build and source. Five-platform app builds still require their respective
CI runners; local package tests are macOS ARM64 evidence only.

## Verification

42 tests cover the repository, including BLOB reopen, version-1-to-2 migration,
migration rollback, and atomic text/BLOB rollback.

`dart analyze` and `dart test` exercise real SQLite files, reopen, worker FIFO,
commit visibility from another connection, pre-Drift schema adoption, atomic
schema/restore rollback, source identities,
preview caches, unknown-field preservation, version refusal and backup roundtrip.

## Bookshelf groups

`BookshelfLibraryRepository` is an optional capability implemented by this
repository. Schema 3 adds typed `book_groups`, migrating previously retained
`bookGroup.json` in the same migration transaction. Both schema 1 and 2 upgrade;
a malformed or duplicate legacy group rolls back installation and version changes.
Export always regenerates the current `bookGroup.json`. Missing group files
preserve groups; an explicit empty list replaces groups without modifying book
bit masks. Negative Android built-in ids and all unknown group fields survive.

Custom groups use positive single-bit ids; allocation avoids bits already used
by any book, including orphan membership bits. Removing a group clears only that
bit from books and does not remove books or caches. Setting membership preserves
unknown/orphan bits. Names, custom order, per-group bookSort and private selected
filter/global sort settings survive reopening. Manual reorder merges a visible
subset into its current slots and leaves other books in place. Shared projections
implement the six Android sort ids using numeric timestamps, and filter known
built-in ids. Name and author ordering currently use deterministic Unicode order;
Android's locale-specific Chinese collation is not replicated here.

## Atomic local books

`LocalBookRepository.importLocalBook` commits a complete book, its chapter list,
its chapter-content map and optional small native assets in one Drift transaction.
A late SQLite failure rolls back every row. Re-importing an existing book identity
is idempotent and preserves its user edits/progress. Caller maps, lists and bytes
are snapshotted. Non-volume chapters require corresponding content; extra content
keys are rejected. Asset-only PDF books may use empty chapter/content collections.

New local identities use `local:<format>:<sha256-lowercase64>`. Assets use
`flutter/local-books/<same-sha256>/<safe-relative-path>`. At most 1024 assets,
32 MiB per asset and 64 MiB in one import are accepted, with length checks before
buffer copies. Traversal, URI punctuation, control characters and wrong identity
namespaces are rejected. Existing opaque text/BLOB entries are never overwritten
with different bytes; a collision aborts the entire import. No legacy archive key
is renamed, interpreted or deleted by this capability.

`localAsset(key)` retrieves a single BLOB using a typed Drift expression. SQLite
checks its length with a `CASE` expression before returning its bytes, so a large
restored opaque entry cannot bypass the 32 MiB read limit. Assets are preserved by
`backupSnapshot`/`restoreArchive` byte-for-byte. Schema 4 adds explicit local asset
ownership and reference tracking for safe book removal.

## Owned asset lifecycle

Schema 4 adds `local_book_assets` with book identity, asset key, managed flag and
lossless reference JSON. A newly imported BLOB is marked managed. Same-byte reuse
of an existing opaque archive entry is unmanaged unless another explicit managed
reference already proves it belongs to Flutter. Import metadata alone never grants
ownership. Re-importing an existing book is idempotent and never stages new assets.

`removeBook` removes that book's references together with its book, chapters and
content in one transaction. Only a managed BLOB with no other book reference is
removed. Shared assets remain until the last owner is removed. Legacy unknown
entries and same-byte unmanaged reuse remain untouched. A late SQL delete failure
rolls asset and reference removal back with the book.

The managed backup extension remains version 1 and adds `localAssets`, a list of
`{bookUrl, assetKey, managed}` records. Unknown fields on these records survive.
Full archive restores atomically replace old ownership with the incoming ownership
claims and bytes. An archive without that field clears prior ownership claims so
new unknown bytes cannot inherit stale deletion rights. Text-only restores retain
existing claims when the field is absent, and cannot promote previously unmanaged
bytes to managed ownership merely by introducing a metadata claim. Invalid keys,
duplicate references or conflicting managed flags roll back the restore.

Schemas 1, 2 and 3 upgrade transactionally to schema 4. Older schema-3 assets lack
explicit ownership evidence and remain unmanaged after upgrade; no filename-based
inference or migration-time deletion occurs. Newly imported assets have complete
ownership tracking. Asset cleanup for historical unowned bytes requires a future
explicit user action, rather than risking loss of retained unknown data.
