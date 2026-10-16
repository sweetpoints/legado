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

Schema version 1 is stored using SQLite `user_version`. Schema installation and
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

## Drift schema and generation

`LibraryDatabase` extends the generated Drift `GeneratedDatabase` implementation.
Six typed tables and generated companions implement every repository query and
write. `MigrationStrategy` creates the schema transactionally, adopts the
pre-Drift version-1 layout without rewriting records, and refuses unsupported
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

`dart analyze` and `dart test` exercise real SQLite files, reopen, worker FIFO,
commit visibility from another connection, pre-Drift schema adoption, atomic
schema/restore rollback, source identities,
preview caches, unknown-field preservation, version refusal and backup roundtrip.
