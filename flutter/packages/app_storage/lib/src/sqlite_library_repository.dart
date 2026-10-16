import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:app_core/app_core.dart';
import 'package:drift/drift.dart';
import 'package:drift/native.dart';
import 'package:drift/isolate.dart';

import 'library_database.dart';

/// Drift-backed library with SQLite work in a Drift-managed worker isolate.
/// The historical class name is retained as the public storage boundary.
class SqliteLibraryRepository implements LibraryRepository {
  SqliteLibraryRepository._(this._db);

  static const backupEntry = 'legado-library.json';
  static const schemaVersion = 1;

  final LibraryDatabase _db;
  final _changes = StreamController<void>.broadcast();
  Future<void> _queue = Future.value();
  Future<void>? _closing;
  bool _closed = false;

  static Future<SqliteLibraryRepository> open(String path) async {
    if (path.isEmpty) throw ArgumentError.value(path, 'path');
    if (path == ':memory:') return inMemory();
    final executor = NativeDatabase.createInBackground(File(path));
    return _initialize(LibraryDatabase(executor));
  }

  static Future<SqliteLibraryRepository> _initialize(LibraryDatabase db) async {
    try {
      // Eagerly run migrations before returning a usable repository.
      await db.customSelect('SELECT 1').get();
      return SqliteLibraryRepository._(db);
    } catch (_) {
      await db.close();
      throw StateError('Could not open library database');
    }
  }

  static Future<SqliteLibraryRepository> inMemory() async {
    final isolate = await DriftIsolate.spawn(() => NativeDatabase.memory());
    final connection = await isolate.connect(singleClientMode: true);
    return _initialize(LibraryDatabase(connection.executor));
  }

  @override
  Stream<void> get changes => _changes.stream;

  Future<T> _run<T>(Future<T> Function() action, {bool change = false}) {
    if (_closed) return Future<T>.error(StateError('Library is closed'));
    final result = _queue.then((_) async {
      try {
        final value = await action();
        if (change) _changes.add(null);
        return value;
      } catch (_) {
        // Do not expose SQLite bindings, source JSON, or database paths.
        throw StateError('Library database operation failed');
      }
    });
    _queue = result.then<void>((_) {}, onError: (Object _, StackTrace _) {});
    return result;
  }

  Map<String, Object?> _decode(String json) =>
      Map<String, Object?>.from(jsonDecode(json) as Map);

  @override
  Future<List<LegacyBookRecord>> books() => _run(() async {
    final rows = await (_db.select(
      _db.storedBooks,
    )..orderBy([(t) => OrderingTerm.asc(t.id)])).get();
    return rows.map((e) => LegacyBookRecord(_decode(e.data))).toList();
  });

  Future<LegacyBookRecord?> _book(String url) async {
    final row = await (_db.select(
      _db.storedBooks,
    )..where((t) => t.id.equals(url))).getSingleOrNull();
    return row == null ? null : LegacyBookRecord(_decode(row.data));
  }

  @override
  Future<LegacyBookRecord?> book(String url) async => _run(() => _book(url));

  Future<void> _putBook(LegacyBookRecord book) => _db
      .into(_db.storedBooks)
      .insertOnConflictUpdate(
        StoredBooksCompanion.insert(
          id: book.url,
          data: jsonEncode(book.toJson()),
        ),
      );

  @override
  Future<void> putBook(LegacyBookRecord book) =>
      _run(() => _putBook(book), change: true);

  @override
  Future<void> removeBook(String url) => _run(
    () => _db.transaction(() async {
      await (_db.delete(
        _db.storedChapters,
      )..where((t) => t.bookId.equals(url))).go();
      await (_db.delete(
        _db.storedContent,
      )..where((t) => t.bookId.equals(url))).go();
      await (_db.delete(_db.storedBooks)..where((t) => t.id.equals(url))).go();
    }),
    change: true,
  );

  @override
  Future<void> updateProgress(
    String url,
    int chapterIndex,
    int characterOffset,
  ) {
    if (chapterIndex < 0 || characterOffset < 0) {
      throw ArgumentError('Reading positions must be non-negative');
    }
    return _run(
      () => _db.transaction(() async {
        final book = await _book(url);
        if (book == null) throw StateError('Missing book');
        final json = book.toJson()
          ..['durChapterIndex'] = chapterIndex
          ..['durChapterPos'] = characterOffset;
        await _putBook(LegacyBookRecord(json));
      }),
      change: true,
    );
  }

  @override
  Future<List<ReaderChapter>> chapters(String bookUrl) => _run(() async {
    final rows =
        await (_db.select(_db.storedChapters)
              ..where((t) => t.bookId.equals(bookUrl))
              ..orderBy([(t) => OrderingTerm.asc(t.position)]))
            .get();
    return rows.map((e) => ReaderChapter.fromJson(_decode(e.data))).toList();
  });

  Future<void> _putChapters(
    String bookUrl,
    List<Map<String, Object?>> items,
  ) async {
    if (bookUrl.isEmpty) throw const FormatException('Empty book identity');
    await (_db.delete(
      _db.storedChapters,
    )..where((t) => t.bookId.equals(bookUrl))).go();
    await _db.batch((batch) {
      for (final json in items) {
        final chapter = ReaderChapter.fromJson(json);
        if (chapter.url.isEmpty || chapter.index < 0) {
          throw const FormatException('Invalid chapter identity or index');
        }
        batch.insert(
          _db.storedChapters,
          StoredChaptersCompanion.insert(
            bookId: bookUrl,
            id: chapter.url,
            position: chapter.index,
            data: jsonEncode(json),
          ),
        );
      }
    });
  }

  @override
  Future<void> putChapters(String bookUrl, List<ReaderChapter> chapters) {
    final snapshot = chapters.map((e) => e.toJson()).toList();
    return _run(
      () => _db.transaction(() => _putChapters(bookUrl, snapshot)),
      change: true,
    );
  }

  @override
  Future<String?> content(String bookUrl, String chapterUrl) async =>
      _run(() async {
        final row =
            await (_db.select(_db.storedContent)..where(
                  (t) =>
                      t.bookId.equals(bookUrl) & t.chapterId.equals(chapterUrl),
                ))
                .getSingleOrNull();
        return row?.value;
      });

  @override
  Future<void> putContent(String bookUrl, String chapterUrl, String content) =>
      _run(() async {
        if (bookUrl.isEmpty || chapterUrl.isEmpty) {
          throw const FormatException('Empty content identity');
        }
        await _db
            .into(_db.storedContent)
            .insertOnConflictUpdate(
              StoredContentCompanion.insert(
                bookId: bookUrl,
                chapterId: chapterUrl,
                value: content,
              ),
            );
      }, change: true);

  @override
  Future<List<Map<String, Object?>>> sources() => _run(_sources);

  Future<List<Map<String, Object?>>> _sources() async {
    final rows = await (_db.select(
      _db.storedSources,
    )..orderBy([(t) => OrderingTerm.asc(t.id)])).get();
    return rows.map((e) => _decode(e.data)).toList();
  }

  String _sourceId(Map<String, Object?> source) {
    final id = source['bookSourceUrl'] ?? source['id'];
    if (id is! String || id.isEmpty) {
      throw const FormatException('Missing source identity');
    }
    return id;
  }

  Future<void> _putSource(Map<String, Object?> source) async {
    await _db
        .into(_db.storedSources)
        .insertOnConflictUpdate(
          StoredSourcesCompanion.insert(
            id: _sourceId(source),
            data: jsonEncode(source),
          ),
        );
  }

  @override
  Future<void> putSource(Map<String, Object?> source) {
    final snapshot = _decode(jsonEncode(source));
    return _run(() => _putSource(snapshot), change: true);
  }

  @override
  Future<void> removeSource(String sourceId) => _run(() async {
    await (_db.delete(
      _db.storedSources,
    )..where((t) => t.id.equals(sourceId))).go();
  }, change: true);

  @override
  Future<String?> setting(String key) async => _run(() async {
    final row = await (_db.select(
      _db.storedSettings,
    )..where((t) => t.id.equals(key))).getSingleOrNull();
    return row?.value;
  });

  @override
  Future<void> setSetting(String key, String? value) => _run(() async {
    if (value == null) {
      await (_db.delete(
        _db.storedSettings,
      )..where((t) => t.id.equals(key))).go();
    } else {
      await _db
          .into(_db.storedSettings)
          .insertOnConflictUpdate(
            StoredSettingsCompanion.insert(id: key, value: value),
          );
    }
  }, change: true);

  @override
  Future<Map<String, String>> backupFiles() => _run(
    () => _db.transaction(() async {
      final rows = await (_db.select(
        _db.storedBackupFiles,
      )..orderBy([(t) => OrderingTerm.asc(t.id)])).get();
      final files = {for (final row in rows) row.id: row.value};
      final extension = files.containsKey(backupEntry)
          ? _decode(files[backupEntry]!)
          : <String, Object?>{};
      final chapters =
          await (_db.select(_db.storedChapters)..orderBy([
                (t) => OrderingTerm.asc(t.bookId),
                (t) => OrderingTerm.asc(t.position),
              ]))
              .get();
      final content =
          await (_db.select(_db.storedContent)..orderBy([
                (t) => OrderingTerm.asc(t.bookId),
                (t) => OrderingTerm.asc(t.chapterId),
              ]))
              .get();
      final settings = await (_db.select(
        _db.storedSettings,
      )..orderBy([(t) => OrderingTerm.asc(t.id)])).get();
      final books = await (_db.select(
        _db.storedBooks,
      )..orderBy([(t) => OrderingTerm.asc(t.id)])).get();
      extension.addAll({
        'version': 1,
        'chapters': [
          for (final row in chapters)
            {'bookUrl': row.bookId, 'chapter': _decode(row.data)},
        ],
        'content': [
          for (final row in content)
            {
              'bookUrl': row.bookId,
              'chapterUrl': row.chapterId,
              'content': row.value,
            },
        ],
        'settings': {for (final row in settings) row.id: row.value},
      });
      files['bookshelf.json'] = jsonEncode(
        books.map((e) => _decode(e.data)).toList(),
      );
      files['bookSource.json'] = jsonEncode(await _sources());
      files[backupEntry] = jsonEncode(extension);
      return files;
    }),
  );

  @override
  Future<void> restoreBackup(LegacyBackupDocument document) => _run(
    () => _db.transaction(() async {
      final files = document.files;
      if (files.containsKey('bookshelf.json')) {
        await _db.delete(_db.storedBooks).go();
        for (final book in document.books) {
          await _putBook(book);
        }
      }
      if (files.containsKey('bookSource.json')) {
        await _db.delete(_db.storedSources).go();
        final seen = <String>{};
        for (final entry in jsonDecode(files['bookSource.json']!) as List) {
          final source = Map<String, Object?>.from(entry as Map);
          if (!seen.add(_sourceId(source))) {
            throw const FormatException('Duplicate source identity');
          }
          await _putSource(source);
        }
      }
      if (files.containsKey(backupEntry)) {
        final extension = _decode(files[backupEntry]!);
        if (extension['version'] != 1) {
          throw const FormatException('Unsupported library backup version');
        }
        if (extension.containsKey('chapters')) {
          await _db.delete(_db.storedChapters).go();
          final grouped = <String, List<Map<String, Object?>>>{};
          for (final entry in extension['chapters'] as List) {
            final item = Map<String, Object?>.from(entry as Map);
            grouped
                .putIfAbsent(item['bookUrl'] as String, () => [])
                .add(Map<String, Object?>.from(item['chapter'] as Map));
          }
          for (final entry in grouped.entries) {
            await _putChapters(entry.key, entry.value);
          }
        }
        if (extension.containsKey('content')) {
          await _db.delete(_db.storedContent).go();
          await _db.batch((batch) {
            for (final entry in extension['content'] as List) {
              final item = Map<String, Object?>.from(entry as Map);
              final book = item['bookUrl'];
              final chapter = item['chapterUrl'];
              final content = item['content'];
              if (book is! String ||
                  book.isEmpty ||
                  chapter is! String ||
                  chapter.isEmpty ||
                  content is! String) {
                throw const FormatException('Invalid content');
              }
              batch.insert(
                _db.storedContent,
                StoredContentCompanion.insert(
                  bookId: book,
                  chapterId: chapter,
                  value: content,
                ),
              );
            }
          });
        }
        if (extension.containsKey('settings')) {
          await _db.delete(_db.storedSettings).go();
          final settings = Map<String, Object?>.from(
            extension['settings'] as Map,
          );
          await _db.batch((batch) {
            for (final entry in settings.entries) {
              if (entry.value is! String) {
                throw const FormatException('Invalid setting');
              }
              batch.insert(
                _db.storedSettings,
                StoredSettingsCompanion.insert(
                  id: entry.key,
                  value: entry.value! as String,
                ),
              );
            }
          });
        }
      }
      await _db.batch((batch) {
        for (final entry in files.entries) {
          batch.insert(
            _db.storedBackupFiles,
            StoredBackupFilesCompanion.insert(
              id: entry.key,
              value: entry.value,
            ),
            mode: InsertMode.insertOrReplace,
          );
        }
      });
    }),
    change: true,
  );

  @override
  Future<void> close() => _closing ??= _close();

  Future<void> _close() async {
    _closed = true;
    await _queue;
    await _db.close();
    await _changes.close();
  }
}
