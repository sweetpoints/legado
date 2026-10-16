import 'dart:async';
import 'dart:convert';
import 'dart:isolate';

import 'package:app_core/app_core.dart';
import 'package:sqlite3/sqlite3.dart';

/// SQLite-backed library, with all database work confined to a worker isolate.
///
/// The caller chooses an application-private path. No book/source identity is
/// normalized as a URL: legacy source identifiers can be arbitrary text.
class SqliteLibraryRepository implements LibraryRepository {
  SqliteLibraryRepository._(this._port, this._replies, this._subscription);

  static const backupEntry = 'legado-library.json';
  static const schemaVersion = 1;

  final SendPort _port;
  final ReceivePort _replies;
  final StreamSubscription<Object?> _subscription;
  final _changes = StreamController<void>.broadcast();
  final _pending = <int, Completer<Object?>>{};
  int _sequence = 0;
  bool _closed = false;

  static Future<SqliteLibraryRepository> open(String path) async {
    if (path.isEmpty) throw ArgumentError.value(path, 'path');
    final replies = ReceivePort();
    final ready = Completer<SendPort>();
    SqliteLibraryRepository? repository;
    final subscription = replies.cast<Object?>().listen((message) {
      final data = message as List<Object?>;
      if (!ready.isCompleted) {
        if (data[0] == 'ready') {
          ready.complete(data[1] as SendPort);
        } else {
          ready.completeError(StateError(data[1] as String));
        }
        return;
      }
      final pending = repository!._pending.remove(data[0]);
      if (pending == null) return;
      if (data[1] == true) {
        pending.complete(data[2]);
      } else {
        // Do not forward SQLite bindings, source JSON or database paths in errors.
        pending.completeError(StateError(data[2] as String));
      }
    });
    try {
      await Isolate.spawn(_serve, [replies.sendPort, path]);
      final port = await ready.future;
      return repository = SqliteLibraryRepository._(
        port,
        replies,
        subscription,
      );
    } catch (_) {
      await subscription.cancel();
      replies.close();
      rethrow;
    }
  }

  static Future<SqliteLibraryRepository> inMemory() => open(':memory:');

  @override
  Stream<void> get changes => _changes.stream;

  Future<Object?> _call(String operation, [Object? arguments]) {
    if (_closed) throw StateError('Library is closed');
    final id = ++_sequence;
    final completion = Completer<Object?>();
    _pending[id] = completion;
    _port.send([id, operation, arguments]);
    return completion.future;
  }

  Future<void> _write(String operation, Object? arguments) async {
    await _call(operation, arguments);
    _changes.add(null);
  }

  @override
  Future<List<LegacyBookRecord>> books() async => (await _call('books') as List)
      .map((e) => LegacyBookRecord(_map(e)))
      .toList();

  @override
  Future<LegacyBookRecord?> book(String url) async {
    final result = await _call('book', url);
    return result == null ? null : LegacyBookRecord(_map(result));
  }

  @override
  Future<void> putBook(LegacyBookRecord book) =>
      _write('putBook', book.toJson());

  @override
  Future<void> removeBook(String url) => _write('removeBook', url);

  @override
  Future<void> updateProgress(
    String url,
    int chapterIndex,
    int characterOffset,
  ) {
    if (chapterIndex < 0 || characterOffset < 0) {
      throw ArgumentError('Reading positions must be non-negative');
    }
    return _write('updateProgress', [url, chapterIndex, characterOffset]);
  }

  @override
  Future<List<ReaderChapter>> chapters(String bookUrl) async =>
      (await _call('chapters', bookUrl) as List)
          .map((e) => ReaderChapter.fromJson(_map(e)))
          .toList();

  @override
  Future<void> putChapters(String bookUrl, List<ReaderChapter> chapters) =>
      _write('putChapters', [
        bookUrl,
        chapters.map((e) => e.toJson()).toList(),
      ]);

  @override
  Future<String?> content(String bookUrl, String chapterUrl) async =>
      await _call('content', [bookUrl, chapterUrl]) as String?;

  @override
  Future<void> putContent(String bookUrl, String chapterUrl, String content) =>
      _write('putContent', [bookUrl, chapterUrl, content]);

  @override
  Future<List<Map<String, Object?>>> sources() async =>
      (await _call('sources') as List).map(_map).toList();

  @override
  Future<void> putSource(Map<String, Object?> source) =>
      _write('putSource', jsonDecode(jsonEncode(source)));

  @override
  Future<void> removeSource(String sourceId) =>
      _write('removeSource', sourceId);

  @override
  Future<String?> setting(String key) async =>
      await _call('setting', key) as String?;

  @override
  Future<void> setSetting(String key, String? value) =>
      _write('setSetting', [key, value]);

  @override
  Future<Map<String, String>> backupFiles() async =>
      Map<String, String>.from(await _call('backup') as Map);

  @override
  Future<void> restoreBackup(LegacyBackupDocument document) =>
      _write('restore', document.files);

  @override
  Future<void> close() async {
    if (_closed) return;
    // Stop accepting new calls before awaiting; preceding writes drain in FIFO.
    final completion = _call('close');
    _closed = true;
    await completion;
    await _subscription.cancel();
    _replies.close();
    await _changes.close();
  }
}

Map<String, Object?> _map(Object? value) =>
    Map<String, Object?>.from(value as Map);

void _serve(List<Object?> arguments) {
  final reply = arguments[0] as SendPort;
  final input = ReceivePort();
  late final _Store store;
  try {
    store = _Store(arguments[1] as String);
    reply.send(['ready', input.sendPort]);
  } catch (_) {
    input.close();
    reply.send(['error', 'Could not open library database']);
    return;
  }
  input.listen((message) {
    final request = message as List<Object?>;
    try {
      final result = store.run(request[1] as String, request[2]);
      reply.send([request[0], true, result]);
    } on FormatException {
      reply.send([request[0], false, 'Invalid library or backup data']);
    } on ArgumentError {
      reply.send([request[0], false, 'Invalid library operation']);
    } on StateError {
      reply.send([
        request[0],
        false,
        'Library record is missing or incompatible',
      ]);
    } catch (_) {
      reply.send([request[0], false, 'Library database operation failed']);
    }
    if (request[1] == 'close') input.close();
  });
}

class _Store {
  _Store(String path) : db = sqlite3.open(path) {
    try {
      db.execute('PRAGMA busy_timeout = 5000');
      final version = db.userVersion;
      if (version > SqliteLibraryRepository.schemaVersion) {
        throw StateError('Newer database schema');
      }
      if (version == 0) {
        _transaction(() {
          db.execute('''
CREATE TABLE books (id TEXT PRIMARY KEY NOT NULL, data TEXT NOT NULL);
CREATE TABLE sources (id TEXT PRIMARY KEY NOT NULL, data TEXT NOT NULL);
CREATE TABLE settings (id TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
CREATE TABLE chapters (
  book_id TEXT NOT NULL, id TEXT NOT NULL, position INTEGER NOT NULL,
  data TEXT NOT NULL, PRIMARY KEY (book_id, id), UNIQUE(book_id, position)
);
CREATE TABLE content (
  book_id TEXT NOT NULL, chapter_id TEXT NOT NULL, value TEXT NOT NULL,
  PRIMARY KEY (book_id, chapter_id)
);
CREATE TABLE backup_files (id TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
''');
          db.userVersion = SqliteLibraryRepository.schemaVersion;
        });
      }
    } catch (_) {
      db.close();
      rethrow;
    }
  }

  final Database db;

  T _transaction<T>(T Function() action) {
    db.execute('BEGIN IMMEDIATE');
    try {
      final result = action();
      db.execute('COMMIT');
      return result;
    } catch (_) {
      db.execute('ROLLBACK');
      rethrow;
    }
  }

  List<Object?> _records(String table) => db
      .select('SELECT data FROM $table ORDER BY id COLLATE BINARY')
      .map((row) => jsonDecode(row['data'] as String))
      .toList();

  Map<String, Object?>? _book(String id) {
    final rows = db.select('SELECT data FROM books WHERE id = ?', [id]);
    return rows.isEmpty
        ? null
        : _map(jsonDecode(rows.single['data'] as String));
  }

  void _put(String table, String id, Map<String, Object?> data) {
    if (id.isEmpty) throw const FormatException('Empty identity');
    db.execute('INSERT OR REPLACE INTO $table (id, data) VALUES (?, ?)', [
      id,
      jsonEncode(data),
    ]);
  }

  String _sourceId(Map<String, Object?> source) {
    final id = source['bookSourceUrl'] ?? source['id'];
    if (id is! String || id.isEmpty) {
      throw const FormatException('Missing source identity');
    }
    return id;
  }

  void _putChapters(String book, List<Object?> items) {
    if (book.isEmpty) throw const FormatException('Empty book identity');
    db.execute('DELETE FROM chapters WHERE book_id = ?', [book]);
    for (final item in items) {
      final json = _map(item);
      final chapter = ReaderChapter.fromJson(json);
      if (chapter.url.isEmpty || chapter.index < 0) {
        throw const FormatException('Invalid chapter identity or index');
      }
      db.execute(
        'INSERT INTO chapters (book_id, id, position, data) VALUES (?, ?, ?, ?)',
        [book, chapter.url, chapter.index, jsonEncode(json)],
      );
    }
  }

  Object? run(String operation, Object? arguments) {
    switch (operation) {
      case 'books':
        return _records('books');
      case 'book':
        return _book(arguments as String);
      case 'putBook':
        final json = _map(arguments);
        _put('books', LegacyBookRecord(json).url, json);
      case 'removeBook':
        _transaction(() {
          for (final table in ['chapters', 'content']) {
            db.execute('DELETE FROM $table WHERE book_id = ?', [arguments]);
          }
          db.execute('DELETE FROM books WHERE id = ?', [arguments]);
        });
      case 'updateProgress':
        final args = arguments as List;
        _transaction(() {
          final json = _book(args[0] as String);
          if (json == null) throw StateError('Missing book');
          json['durChapterIndex'] = args[1];
          json['durChapterPos'] = args[2];
          _put('books', LegacyBookRecord(json).url, json);
        });
      case 'chapters':
        return db
            .select(
              'SELECT data FROM chapters WHERE book_id = ? ORDER BY position',
              [arguments],
            )
            .map((row) => jsonDecode(row['data'] as String))
            .toList();
      case 'putChapters':
        final args = arguments as List;
        _transaction(() => _putChapters(args[0] as String, args[1] as List));
      case 'content':
        final rows = db.select(
          'SELECT value FROM content WHERE book_id = ? AND chapter_id = ?',
          arguments as List,
        );
        return rows.isEmpty ? null : rows.single['value'];
      case 'putContent':
        final args = arguments as List;
        if ((args[0] as String).isEmpty || (args[1] as String).isEmpty) {
          throw const FormatException('Empty content identity');
        }
        db.execute('INSERT OR REPLACE INTO content VALUES (?, ?, ?)', args);
      case 'sources':
        return _records('sources');
      case 'putSource':
        final json = _map(arguments);
        _put('sources', _sourceId(json), json);
      case 'removeSource':
        db.execute('DELETE FROM sources WHERE id = ?', [arguments]);
      case 'setting':
        final rows = db.select('SELECT value FROM settings WHERE id = ?', [
          arguments,
        ]);
        return rows.isEmpty ? null : rows.single['value'];
      case 'setSetting':
        final args = arguments as List;
        if (args[1] == null) {
          db.execute('DELETE FROM settings WHERE id = ?', [args[0]]);
        } else {
          db.execute('INSERT OR REPLACE INTO settings VALUES (?, ?)', args);
        }
      case 'backup':
        return _backup();
      case 'restore':
        _restore(Map<String, String>.from(arguments as Map));
      case 'close':
        db.close();
      default:
        throw ArgumentError('Unknown operation');
    }
    return null;
  }

  Map<String, String> _backup() {
    return _transaction(() {
      final files = {
        for (final row in db.select(
          'SELECT id, value FROM backup_files ORDER BY id',
        ))
          row['id'] as String: row['value'] as String,
      };
      final extension = files.containsKey(SqliteLibraryRepository.backupEntry)
          ? _map(jsonDecode(files[SqliteLibraryRepository.backupEntry]!))
          : <String, Object?>{};
      extension.addAll({
        'version': 1,
        'chapters': [
          for (final row in db.select(
            'SELECT book_id, data FROM chapters ORDER BY book_id, position',
          ))
            {
              'bookUrl': row['book_id'],
              'chapter': jsonDecode(row['data'] as String),
            },
        ],
        'content': [
          for (final row in db.select(
            'SELECT * FROM content ORDER BY book_id, chapter_id',
          ))
            {
              'bookUrl': row['book_id'],
              'chapterUrl': row['chapter_id'],
              'content': row['value'],
            },
        ],
        'settings': {
          for (final row in db.select('SELECT * FROM settings ORDER BY id'))
            row['id'] as String: row['value'],
        },
      });
      files['bookshelf.json'] = jsonEncode(_records('books'));
      files['bookSource.json'] = jsonEncode(_records('sources'));
      files[SqliteLibraryRepository.backupEntry] = jsonEncode(extension);
      return files;
    });
  }

  void _restore(Map<String, String> files) {
    final document = LegacyBackupDocument.parse(files);
    _transaction(() {
      if (files.containsKey('bookshelf.json')) {
        db.execute('DELETE FROM books');
        for (final book in document.books) {
          _put('books', book.url, book.toJson());
        }
      }
      if (files.containsKey('bookSource.json')) {
        db.execute('DELETE FROM sources');
        final seen = <String>{};
        for (final entry in jsonDecode(files['bookSource.json']!) as List) {
          final source = _map(entry);
          final id = _sourceId(source);
          if (!seen.add(id)) {
            throw const FormatException('Duplicate source identity');
          }
          _put('sources', id, source);
        }
      }
      if (files.containsKey(SqliteLibraryRepository.backupEntry)) {
        final extension = _map(
          jsonDecode(files[SqliteLibraryRepository.backupEntry]!),
        );
        if (extension['version'] != 1) {
          throw const FormatException('Unsupported library backup version');
        }
        if (extension.containsKey('chapters')) {
          db.execute('DELETE FROM chapters');
          final grouped = <String, List<Object?>>{};
          for (final entry in extension['chapters'] as List) {
            final item = _map(entry);
            final bookUrl = item['bookUrl'] as String;
            grouped.putIfAbsent(bookUrl, () => []).add(item['chapter']);
          }
          for (final entry in grouped.entries) {
            _putChapters(entry.key, entry.value);
          }
        }
        if (extension.containsKey('content')) {
          db.execute('DELETE FROM content');
          for (final entry in extension['content'] as List) {
            final item = _map(entry);
            if (item['bookUrl'] is! String ||
                (item['bookUrl'] as String).isEmpty ||
                item['chapterUrl'] is! String ||
                (item['chapterUrl'] as String).isEmpty ||
                item['content'] is! String) {
              throw const FormatException('Invalid content');
            }
            db.execute('INSERT INTO content VALUES (?, ?, ?)', [
              item['bookUrl'],
              item['chapterUrl'],
              item['content'],
            ]);
          }
        }
        if (extension.containsKey('settings')) {
          db.execute('DELETE FROM settings');
          for (final entry in _map(extension['settings']).entries) {
            if (entry.value is! String) {
              throw const FormatException('Invalid setting');
            }
            db.execute('INSERT INTO settings VALUES (?, ?)', [
              entry.key,
              entry.value,
            ]);
          }
        }
      }
      for (final entry in files.entries) {
        db.execute('INSERT OR REPLACE INTO backup_files VALUES (?, ?)', [
          entry.key,
          entry.value,
        ]);
      }
    });
  }
}
