import 'legacy_backup.dart';

class ReaderChapter {
  const ReaderChapter({
    required this.url,
    required this.title,
    required this.index,
    this.isVolume = false,
  });
  final String url;
  final String title;
  final int index;
  final bool isVolume;
  Map<String, Object?> toJson() => {
    'url': url,
    'title': title,
    'index': index,
    'isVolume': isVolume,
  };
  factory ReaderChapter.fromJson(Map<String, Object?> json) => ReaderChapter(
    url: json['url'] as String,
    title: json['title'] as String,
    index: json['index'] as int,
    isVolume: json['isVolume'] as bool? ?? false,
  );
}

/// Shared storage boundary. Implementations commit before emitting changes.
/// Book/source identities remain opaque and are never inferred from names.
abstract interface class LibraryRepository {
  Stream<void> get changes;
  Future<List<LegacyBookRecord>> books();
  Future<LegacyBookRecord?> book(String url);
  Future<void> putBook(LegacyBookRecord book);
  Future<void> removeBook(String url);
  Future<void> updateProgress(
    String url,
    int chapterIndex,
    int characterOffset,
  );
  Future<List<ReaderChapter>> chapters(String bookUrl);
  Future<void> putChapters(String bookUrl, List<ReaderChapter> chapters);
  Future<String?> content(String bookUrl, String chapterUrl);
  Future<void> putContent(String bookUrl, String chapterUrl, String content);
  Future<List<Map<String, Object?>>> sources();
  Future<void> putSource(Map<String, Object?> source);
  Future<void> removeSource(String sourceId);
  Future<String?> setting(String key);
  Future<void> setSetting(String key, String? value);
  Future<Map<String, String>> backupFiles();
  Future<void> restoreBackup(LegacyBackupDocument document);
  Future<void> close();
}

/// Text and opaque archive entries are read in one consistent snapshot.
class LibraryBackupSnapshot {
  LibraryBackupSnapshot({
    required Map<String, String> files,
    required Map<String, List<int>> entries,
  }) : files = Map.unmodifiable(files),
       entries = Map.unmodifiable(
         entries.map(
           (key, value) => MapEntry(key, List<int>.unmodifiable(value)),
         ),
       );
  final Map<String, String> files;
  final Map<String, List<int>> entries;
}

/// An optional capability; a text-only repository must not discard media.
abstract interface class ArchiveLibraryRepository {
  Future<LibraryBackupSnapshot> backupSnapshot();
  Future<void> restoreArchive(
    LegacyBackupDocument document,
    Map<String, List<int>> retainedEntries,
  );
}

/// Optional access to one preserved legacy entry without exporting caches.
/// Names are opaque archive keys; implementations never read filesystem paths.
/// Live books/sources use their dedicated repository methods instead.
abstract interface class LegacyBackupFileReader {
  Future<String?> backupFile(String name);
}
