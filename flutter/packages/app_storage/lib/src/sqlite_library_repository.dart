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
class SqliteLibraryRepository
    implements
        LibraryRepository,
        ArchiveLibraryRepository,
        LegacyBackupFileReader,
        BookshelfLibraryRepository {
  SqliteLibraryRepository._(this._db);

  static const backupEntry = 'legado-library.json';
  static const schemaVersion = 3;

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
          ..['durChapterPos'] = characterOffset
          ..['durChapterTime'] = DateTime.now().millisecondsSinceEpoch;
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
  Future<Map<String, String>> backupFiles() =>
      _run(() => _db.transaction(_backupFiles));

  @override
  Future<String?> backupFile(String name) => _run(() async {
    final row = await (_db.select(
      _db.storedBackupFiles,
    )..where((t) => t.id.equals(name))).getSingleOrNull();
    return row?.value;
  });

  Future<Map<String, String>> _backupFiles() async {
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
    files['bookGroup.json'] = jsonEncode(
      (await _bookGroups()).map((g) => g.toJson()).toList(),
    );
    files[backupEntry] = jsonEncode(extension);
    return files;
  }

  @override
  Future<void> restoreBackup(LegacyBackupDocument document) => _run(
    () => _db.transaction(() async {
      final binaries = await _db.select(_db.storedArchiveEntries).get();
      if (binaries.any((e) => document.files.containsKey(e.id))) {
        throw const FormatException(
          'Text entry collides with retained archive data',
        );
      }
      await _restoreDocument(document);
    }),
    change: true,
  );

  Future<void> _restoreDocument(LegacyBackupDocument document) async {
    final files = document.files;
    if (files.containsKey('bookshelf.json')) {
      await _db.delete(_db.storedBooks).go();
      for (final book in document.books) {
        await _putBook(book);
      }
    }
    if (files.containsKey('bookGroup.json')) {
      await _db.delete(_db.storedBookGroups).go();
      final seen = <int>{};
      for (final entry in jsonDecode(files['bookGroup.json']!) as List) {
        final group = LegacyBookGroup(Map<String, Object?>.from(entry as Map));
        if (!seen.add(group.id)) {
          throw const FormatException('Duplicate group identity');
        }
        await _putGroup(group);
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
          StoredBackupFilesCompanion.insert(id: entry.key, value: entry.value),
          mode: InsertMode.insertOrReplace,
        );
      }
    });
  }

  @override
  Future<LibraryBackupSnapshot> backupSnapshot() => _run(
    () => _db.transaction(() async {
      final files = await _backupFiles();
      final binary = await (_db.select(
        _db.storedArchiveEntries,
      )..orderBy([(t) => OrderingTerm.asc(t.id)])).get();
      return LibraryBackupSnapshot(
        files: files,
        entries: {for (final entry in binary) entry.id: entry.value},
      );
    }),
  );

  @override
  Future<void> restoreArchive(
    LegacyBackupDocument document,
    Map<String, List<int>> retainedEntries,
  ) {
    final snapshot = {
      for (final entry in retainedEntries.entries)
        entry.key: List<int>.from(entry.value),
    };
    return _run(
      () => _db.transaction(() async {
        await _db.delete(_db.storedArchiveEntries).go();
        await _restoreDocument(document);
        await _db.batch((batch) {
          for (final entry in snapshot.entries) {
            final name = entry.key;
            final parts = name.split('/');
            if (name.isEmpty ||
                name.startsWith('/') ||
                name.contains('\\') ||
                name.contains(':') ||
                parts.any((p) => p.isEmpty || p == '.' || p == '..') ||
                document.files.containsKey(name) ||
                LegacyBackupDocument.listFiles.contains(name) ||
                name == backupEntry ||
                entry.value.any((b) => b < 0 || b > 255)) {
              throw const FormatException('Invalid retained archive entry');
            }
            batch.deleteWhere(_db.storedBackupFiles, (t) => t.id.equals(name));
            batch.insert(
              _db.storedArchiveEntries,
              StoredArchiveEntriesCompanion.insert(
                id: name,
                value: Uint8List.fromList(entry.value),
              ),
            );
          }
        });
      }),
      change: true,
    );
  }

  Future<List<LegacyBookGroup>> _bookGroups() async {
    final rows =
        await (_db.select(_db.storedBookGroups)..orderBy([
              (t) => OrderingTerm.asc(t.position),
              (t) => OrderingTerm.asc(t.id),
            ]))
            .get();
    return rows.map((r) => LegacyBookGroup(_decode(r.data))).toList();
  }

  @override
  Future<List<LegacyBookGroup>> bookGroups() => _run(_bookGroups);

  Future<void> _putGroup(LegacyBookGroup group) async {
    await _db
        .into(_db.storedBookGroups)
        .insertOnConflictUpdate(
          StoredBookGroupsCompanion.insert(
            id: Value(group.id),
            position: group.order,
            data: jsonEncode(group.toJson()),
          ),
        );
  }

  String _groupName(String name) {
    if (name.trim().isEmpty) throw ArgumentError('Empty group name');
    return name.trim();
  }

  Future<List<LegacyBookRecord>> _allBooks() async =>
      (await _db.select(_db.storedBooks).get())
          .map((r) => LegacyBookRecord(_decode(r.data)))
          .toList();

  @override
  Future<LegacyBookGroup> createBookGroup(String name) => _run(
    () => _db.transaction(() async {
      final label = _groupName(name);
      final groups = await _bookGroups();
      if (groups.any((g) => g.name == label)) {
        throw StateError('Duplicate group name');
      }
      var used = groups
          .where((g) => g.id > 0)
          .fold(0, (mask, g) => mask | g.id);
      for (final book in await _allBooks()) {
        used |= bookInteger(book, 'group');
      }
      for (var bit = 0; bit < 63; bit++) {
        final id = 1 << bit;
        if ((used & id) != 0) continue;
        final order =
            groups.fold(-1, (max, g) => g.order > max ? g.order : max) + 1;
        final group = LegacyBookGroup({
          'groupId': id,
          'groupName': label,
          'order': order,
          'show': true,
          'bookSort': -1,
          'enableRefresh': true,
          'onlyUpdateRead': false,
        });
        await _putGroup(group);
        return group;
      }
      throw StateError('No free group bit');
    }),
    change: true,
  );

  @override
  Future<void> updateBookGroup(LegacyBookGroup group) => _run(
    () => _db.transaction(() async {
      final groups = await _bookGroups();
      final existing = groups.where((g) => g.id == group.id).firstOrNull;
      if (existing == null) throw StateError('Missing group');
      final label = group.isAssignable ? _groupName(group.name) : group.name;
      if (label.isNotEmpty &&
          groups.any((g) => g.id != group.id && g.name == label)) {
        throw StateError('Duplicate group name');
      }
      final json = existing.toJson()
        ..addAll(group.toJson())
        ..['groupName'] = label;
      await _putGroup(LegacyBookGroup(json));
    }),
    change: true,
  );

  @override
  Future<void> removeBookGroup(int id) => _run(
    () => _db.transaction(() async {
      final groups = await _bookGroups();
      final group = groups.where((g) => g.id == id).firstOrNull;
      if (group == null || !group.isAssignable) {
        throw StateError('Invalid group');
      }
      for (final book in await _allBooks()) {
        final mask = bookInteger(book, 'group');
        if ((mask & id) == 0) continue;
        await _putBook(LegacyBookRecord(book.toJson()..['group'] = mask & ~id));
      }
      await (_db.delete(
        _db.storedBookGroups,
      )..where((t) => t.id.equals(id))).go();
      final selected =
          await (_db.select(_db.storedSettings)
                ..where((t) => t.id.equals('bookshelf.selectedGroup')))
              .getSingleOrNull();
      if (selected?.value == '$id') {
        await _db
            .into(_db.storedSettings)
            .insertOnConflictUpdate(
              StoredSettingsCompanion.insert(
                id: 'bookshelf.selectedGroup',
                value: '-1',
              ),
            );
      }
    }),
    change: true,
  );

  @override
  Future<void> setBookGroups(String bookUrl, List<int> groupIds) {
    final selected = groupIds.toSet();
    return _run(
      () => _db.transaction(() async {
        final book = await _book(bookUrl);
        if (book == null) throw StateError('Missing book');
        final groups = (await _bookGroups())
            .where((g) => g.isAssignable)
            .toList();
        if (selected.any((id) => !groups.any((g) => g.id == id))) {
          throw StateError('Missing group');
        }
        final known = groups.fold(0, (mask, g) => mask | g.id);
        var mask = bookInteger(book, 'group') & ~known;
        for (final id in selected) {
          mask |= id;
        }
        await _putBook(LegacyBookRecord(book.toJson()..['group'] = mask));
      }),
      change: true,
    );
  }

  @override
  Future<void> reorderBooks(List<String> orderedUrls) {
    final requested = List<String>.from(orderedUrls);
    return _run(
      () => _db.transaction(() async {
        if (requested.toSet().length != requested.length) {
          throw ArgumentError('Duplicate book');
        }
        final books = selectBookshelfBooks(
          await _allBooks(),
          [],
          sort: BookshelfSort.manual,
        );
        if (requested.any((url) => !books.any((book) => book.url == url))) {
          throw StateError('Missing book');
        }
        final replacements = requested.iterator;
        final selected = requested.toSet();
        final lookup = {for (final book in books) book.url: book};
        for (var i = 0; i < books.length; i++) {
          var book = books[i];
          if (selected.contains(book.url)) {
            replacements.moveNext();
            book = lookup[replacements.current]!;
          }
          await _putBook(LegacyBookRecord(book.toJson()..['order'] = i));
        }
      }),
      change: true,
    );
  }

  @override
  Future<void> close() => _closing ??= _close();

  Future<void> _close() async {
    _closed = true;
    await _queue;
    await _db.close();
    await _changes.close();
  }
}
