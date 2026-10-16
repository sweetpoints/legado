# app_backup

Legacy Android ZIP backup staging for Legado's Android, iOS, macOS, Windows and
Linux Flutter app. Generated with the official `dart create --template=package`
scaffold. Uses Dart's native zlib stream converter and the `archive` ZIP encoder.
There are no filesystem writes, database changes, script execution, or HTTP calls.

## Public contract

```dart
final staged = LegacyBackupArchive.decode(zipBytes);
final document = staged.document; // LegacyBackupDocument for restore preview
final preserved = staged.retainedEntries; // opaque media/future payloads

// A byte-for-byte round trip of each file's payload (ZIP metadata may change).
final copy = LegacyBackupArchive.encode(staged);

// After a separate transactional repository restore/edit/export:
final fresh = LegacyBackupArchive.encodeFiles(
  await repository.backupFiles(),
  retainedEntries: preserved,
);
```

`entries` contains **all original file payloads**. `retainedEntries` contains files
not in the known legacy-text allowlist. Both map and byte buffers are immutable.
`document.files` contains root-level known legacy collections plus config XML,
cookies and runtime-source-cache text. Unknown JSON files and encrypted
`servers.json` stay opaque and are never decoded or logged.

Known text uses strict UTF-8; a leading BOM is removed for parsing only. Original
bytes remain intact in `entries`. A missing collection stays missing. A present
empty/blank/null/malformed collection is rejected; use `[]` for an explicit empty
collection. Invalid input throws `FormatException`, without returning a partial
staging result. Invalid configured limits throw `ArgumentError`.

The codec validates every entry, size, CRC and known collection before returning.
It does not itself authorize, merge or commit a restore. The caller must preview
the staged document, select restore semantics and use the repository's atomic
restore operation. Opaque entries must be durably retained by that layer before
claiming full media/config round-trip support. Retaining opaque bytes alone does
not apply Android preferences, import cookies, decrypt server credentials, or
make media available to the reader.

## Supported containers and limits

- Single-disk ZIP32, stored or deflated entries, optional signed/unsigned data
  descriptors, UTF-8 names (ASCII names also accepted without a UTF-8 flag).
- Default input limit 128 MiB; 10,000 entries; 64 MiB per file; 256 MiB total
  uncompressed bytes. `BackupArchiveLimits` allows explicit adjustment.
- Declared limits are checked before inflation; actual output is streamed into
  a bounded sink, so lying size headers cannot bypass the per-entry limit.
- Rejects duplicate names, case and canonical Unicode aliases, conflicting
  file/directory paths, traversal, absolute/Windows/UNC paths, control characters,
  Windows device names, symlinks and special Unix file types. Paths are limited
  to 64 components, 255 UTF-8 bytes per component and 4096 UTF-8 bytes total.
- Rejects truncated/overlapping/aliased local records, bad CRCs, mismatched
  local/central names or sizes, and broken data descriptors.
- ZIP-level encryption, ZIP64, multi-disk, legacy non-UTF-8 non-ASCII filenames,
  unsupported compression methods and executable/self-extracting ZIP preambles
  are explicitly rejected. An encrypted **file payload** such as `servers.json`
  inside an ordinary ZIP is retained unchanged.
- Empty directory records and ZIP timestamp/comment/permissions metadata are not
  preserved during export; every accepted regular file payload is preserved.
- A ZIP with no recognized root-level legacy text file is not a backup.

## Implementation evidence

The `archive` public API and source were checked before integration:
[package documentation](https://pub.dev/packages/archive),
[ZipDecoder API](https://pub.dev/documentation/archive/latest/archive/ZipDecoder-class.html),
[ArchiveFile API](https://pub.dev/documentation/archive/latest/archive/ArchiveFile-class.html),
[upstream source](https://github.com/brendan-duncan/archive).

The reviewed decoder coalesces repeated names and its `verify` argument is not a
reliable CRC validation boundary. This package consequently performs its own
strict ZIP directory/header preflight and explicit CRC validation. `archive` is
used for encoding and CRC32; native `ZLibDecoder.startChunkedConversion` delivers
immediate bounded output. This package deliberately targets the five native
platforms, not web.

Tests build synthetic archives in memory, including legacy JSON, opaque encrypted
payloads, media, and hostile ZIPs. No real user source scripts, credentials or
novel content are included in fixtures or logs.

Run from the package after the workspace resolves dependencies:

```sh
dart analyze
dart test
```
