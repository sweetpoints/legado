import 'dart:async';

import 'package:app_core/app_core.dart';
import 'package:legado_app/services/book_source_service.dart';

class SourceTestRepository implements LibraryRepository {
  final sourceRows = <String, Map<String, Object?>>{};
  final bookRows = <String, LegacyBookRecord>{};
  final chapterRows = <String, List<ReaderChapter>>{};
  final contentRows = <String, String>{};
  final settings = <String, String>{};
  final controller = StreamController<void>.broadcast();
  @override
  Stream<void> get changes => controller.stream;
  @override
  Future<List<LegacyBookRecord>> books() async => bookRows.values.toList();
  @override
  Future<LegacyBookRecord?> book(String url) async => bookRows[url];
  @override
  Future<void> putBook(LegacyBookRecord book) async {
    bookRows[book.url] = book;
    controller.add(null);
  }

  @override
  Future<void> removeBook(String url) async => bookRows.remove(url);
  @override
  Future<void> updateProgress(String url, int index, int offset) async {
    final b = bookRows[url]!;
    bookRows[url] = LegacyBookRecord({
      ...b.toJson(),
      'durChapterIndex': index,
      'durChapterPos': offset,
    });
  }

  @override
  Future<List<ReaderChapter>> chapters(String bookUrl) async =>
      chapterRows[bookUrl] ?? [];
  @override
  Future<void> putChapters(String bookUrl, List<ReaderChapter> chapters) async {
    chapterRows[bookUrl] = chapters;
  }

  @override
  Future<String?> content(String bookUrl, String chapterUrl) async =>
      contentRows['$bookUrl|$chapterUrl'];
  @override
  Future<void> putContent(
    String bookUrl,
    String chapterUrl,
    String content,
  ) async {
    contentRows['$bookUrl|$chapterUrl'] = content;
  }

  @override
  Future<List<Map<String, Object?>>> sources() async =>
      sourceRows.values.toList();
  @override
  Future<void> putSource(Map<String, Object?> source) async {
    sourceRows[BookSourceService.sourceId(source)] = source;
    controller.add(null);
  }

  @override
  Future<void> removeSource(String id) async {
    sourceRows.remove(id);
    controller.add(null);
  }

  @override
  Future<String?> setting(String key) async => settings[key];
  @override
  Future<void> setSetting(String key, String? value) async {
    if (value == null) {
      settings.remove(key);
    } else {
      settings[key] = value;
    }
  }

  @override
  Future<Map<String, String>> backupFiles() async => {};
  @override
  Future<void> restoreBackup(LegacyBackupDocument document) async =>
      throw UnimplementedError();
  @override
  Future<void> close() => controller.close();
}
