# RSS restore semantics

Android `RssSource` uses `sourceUrl` as its primary key. `Backup.kt` writes
`rssSources.json` as a list, and `Restore.kt` reads that list into the RSS table.
Flutter retains that original text entry and exposes it through `RssStore`.

`sourceView()` is a read-only overlay:

- App RSS settings in `rss.library.v1` remain the writable source of truth.
- A retained `rssSources.json` adds sources which have no exact `sourceUrl`
  identity in the app settings. URLs are not normalized for identity matching.
- On an identity conflict, the complete current app record wins. No partial
  mixing of old request headers, rules or unknown fields occurs. The returned
  report and UI disclose the conflict count.
- Original backup text, unknown fields, offline articles, read markers and
  bookmarks are not rewritten by reading the overlay.
- Missing backup entry means no additional sources. An explicit empty list adds
  none and does not delete current app records. Malformed entries and duplicate
  identities are explicit errors; no ambiguous record is chosen silently.
- Deleting a source records its exact identity in the versioned
  `legacyRestore.deletedSourceUrls` state. It therefore stays deleted after
  rereading, restarting, or exporting/restoring a Flutter backup. Offline articles
  and bookmarks remain intact. Explicit import of that identity clears its
  tombstone and adds the supplied configuration.
- New backups preserve the original legacy entry and carry app RSS settings and
  tombstones in the existing Flutter extension. A full Flutter settings restore
  remains governed by the repository's explicit restore contract; this overlay
  does not intercept or reinterpret generic settings replacement.

Real repositories implement `LegacyBackupFileReader` so rendering subscriptions
reads only the retained RSS entry rather than exporting all cached book content.
The fallback `backupFiles()` path supports text-only/mock repositories.
