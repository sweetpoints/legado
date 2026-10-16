import 'legacy_backup.dart';
import 'library_repository.dart';

/// Optional durable capability for all-or-nothing local book imports.
/// Existing identities preserve progress, metadata, chapters and contents. For a
/// known PDF/EPUB document, assets already referenced by its stored metadata may
/// be checked or repaired atomically; different existing bytes are rejected.
/// New unreferenced asset keys on an existing book are ignored.
abstract interface class LocalBookRepository {
  Future<void> importLocalBook(
    LegacyBookRecord book,
    List<ReaderChapter> chapters,
    Map<String, String> contentsByChapterUrl, {
    Map<String, List<int>> assetsByKey = const {},
  });

  /// Reads one bounded Flutter-owned asset; never interprets [key] as a path.
  Future<List<int>?> localAsset(String key);
}

/// Local assets use a content identity namespace, distinct from retained legacy
/// archive entries. These limits bound the bytes accepted by one import.
abstract final class LocalBookAssets {
  static const prefix = 'flutter/local-books/';
  static const maximumAssetBytes = 32 * 1024 * 1024;
  static const maximumImportBytes = 64 * 1024 * 1024;
  static const maximumAssetCount = 1024;

  static bool isValidKey(String key) {
    if (!key.startsWith(prefix) || key.length > 1024) return false;
    final parts = key.substring(prefix.length).split('/');
    return parts.length >= 2 &&
        RegExp(r'^[0-9a-f]{64}$').hasMatch(parts.first) &&
        parts
            .skip(1)
            .every(
              (part) =>
                  part.isNotEmpty &&
                  part != '.' &&
                  part != '..' &&
                  !RegExp(r'[\x00-\x1f\x7f\\:%?#]').hasMatch(part),
            );
  }
}
