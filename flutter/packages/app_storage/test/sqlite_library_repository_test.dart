import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:app_core/app_core.dart';
import 'package:app_storage/app_storage.dart';
import 'package:sqlite3/sqlite3.dart';
import 'package:test/test.dart';

LegacyBookRecord record(String id) => LegacyBookRecord({
  'bookUrl': id,
  'name': 'Book $id',
  'author': 'Author',
  'unknown': {
    'nested': [1, null, 'preserved'],
  },
});

void main() {
  late Directory directory;
  late String path;
  late SqliteLibraryRepository repository;

  setUp(() async {
    directory = await Directory.systemTemp.createTemp('legado-sqlite-');
    path = '${directory.path}/library.sqlite';
    repository = await SqliteLibraryRepository.open(path);
  });
  tearDown(() async {
    await repository.close();
    await directory.delete(recursive: true);
  });

  test(
    'file reopen retains progress, sources, chapters, content and settings',
    () async {
      await repository.putBook(record('opaque book'));
      await repository.putSource({
        'bookSourceUrl': '不是网址的书源身份',
        'bookSourceName': 'Source',
        'unknown': {'x': 4},
      });
      await repository.putChapters('opaque book', [
        const ReaderChapter(
          url: 'relative/chapter',
          title: 'Chapter',
          index: 0,
        ),
      ]);
      await repository.putContent('opaque book', 'relative/chapter', '正文😀');
      await repository.updateProgress('opaque book', 3, 42);
      await repository.setSetting('theme', 'dark');
      await repository.close();
      repository = await SqliteLibraryRepository.open(path);
      final book = (await repository.book('opaque book'))!;
      expect(book.chapterIndex, 3);
      expect(book.characterOffset, 42);
      expect(book.toJson()['unknown'], {
        'nested': [1, null, 'preserved'],
      });
      expect((await repository.sources()).single['unknown'], {'x': 4});
      expect(
        (await repository.chapters('opaque book')).single.url,
        'relative/chapter',
      );
      expect(
        await repository.content('opaque book', 'relative/chapter'),
        '正文😀',
      );
      expect(await repository.setting('theme'), 'dark');
    },
  );

  test('restore is atomic when a late chapter constraint fails', () async {
    await repository.putBook(record('original'));
    await repository.putSource({'id': 'old-source', 'unknown': true});
    await repository.setSetting('keep', 'yes');
    final before = await repository.backupFiles();
    var changes = 0;
    final subscription = repository.changes.listen((_) => changes++);
    final invalid = LegacyBackupDocument.parse({
      'bookshelf.json': jsonEncode([record('replacement').toJson()]),
      'bookSource.json': '[{"id":"new-source"}]',
      'unknown.txt': 'must roll back',
      SqliteLibraryRepository.backupEntry: jsonEncode({
        'version': 1,
        'chapters': [
          for (var i = 0; i < 2; i++)
            {
              'bookUrl': 'replacement',
              'chapter': const ReaderChapter(
                url: 'duplicate',
                title: 'bad',
                index: 0,
              ).toJson(),
            },
        ],
      }),
    });
    await expectLater(repository.restoreBackup(invalid), throwsStateError);
    expect(await repository.backupFiles(), before);
    await Future<void>.delayed(Duration.zero);
    expect(changes, 0);
    await subscription.cancel();
  });

  test(
    'missing tables preserve, explicit empties replace, unknown files survive',
    () async {
      await repository.putBook(record('a'));
      await repository.putSource({'id': 'source'});
      await repository.putContent('a', 'chapter', 'keep');
      await repository.setSetting('keep', 'value');
      await repository.restoreBackup(
        LegacyBackupDocument.parse({
          'bookMemo.json': '[{"unknown":true}]',
          'nested/unknown.json': '{"future":true}',
        }),
      );
      expect((await repository.books()).length, 1);
      expect((await repository.sources()).length, 1);
      await repository.restoreBackup(
        LegacyBackupDocument.parse({
          'bookshelf.json': '[]',
          SqliteLibraryRepository.backupEntry: '{"version":1,"settings":{}}',
        }),
      );
      expect(await repository.books(), isEmpty);
      expect((await repository.sources()).length, 1);
      expect(await repository.setting('keep'), isNull);
      expect(await repository.content('a', 'chapter'), 'keep');
      final files = await repository.backupFiles();
      expect(files['bookMemo.json'], '[{"unknown":true}]');
      expect(files['nested/unknown.json'], '{"future":true}');
    },
  );

  test(
    'backup exports current edits and can restore into another database',
    () async {
      await repository.restoreBackup(
        LegacyBackupDocument.parse({
          'bookshelf.json': jsonEncode([record('a').toJson()]),
          'bookSource.json': '[{"id":"source","future":123}]',
          SqliteLibraryRepository.backupEntry: '{"version":1,"future":{"x":4}}',
          'future.txt': 'untouched',
        }),
      );
      await repository.updateProgress('a', 7, 11);
      await repository.putSource({'id': 'source', 'future': 456});
      await repository.putChapters('a', [
        const ReaderChapter(url: 'chapter', title: 'Name', index: 0),
      ]);
      await repository.putContent('a', 'chapter', 'text');
      await repository.setSetting('font', '18');
      final files = await repository.backupFiles();
      expect(
        (jsonDecode(files['bookshelf.json']!) as List).single['durChapterPos'],
        11,
      );
      expect(
        (jsonDecode(files['bookSource.json']!) as List).single['future'],
        456,
      );
      expect(
        jsonDecode(files[SqliteLibraryRepository.backupEntry]!)['future'],
        {'x': 4},
      );
      final other = await SqliteLibraryRepository.inMemory();
      try {
        await other.restoreBackup(LegacyBackupDocument.parse(files));
        expect((await other.book('a'))!.chapterIndex, 7);
        expect((await other.chapters('a')).single.title, 'Name');
        expect(await other.content('a', 'chapter'), 'text');
        expect(await other.setting('font'), '18');
        expect(await other.backupFiles(), files);
      } finally {
        await other.close();
      }
    },
  );

  test(
    'chapter replacement rolls back on duplicates and preserves cached content',
    () async {
      const first = ReaderChapter(url: 'same', title: 'One', index: 0);
      await repository.putChapters('preview', [first]);
      await repository.putContent('preview', 'same', 'cached');
      await expectLater(
        repository.putChapters('preview', [first, first]),
        throwsStateError,
      );
      expect((await repository.chapters('preview')).single.title, 'One');
      await repository.putChapters('preview', [
        const ReaderChapter(url: 'same', title: 'Renamed', index: 1),
      ]);
      expect(await repository.content('preview', 'same'), 'cached');
      expect(await repository.book('preview'), isNull);
    },
  );

  test(
    'opaque identities distinguish same names and book-scoped chapter URLs',
    () async {
      await repository.putBook(record('b'));
      await repository.putBook(record('a'));
      await repository.putContent('a', 'relative', 'A');
      await repository.putContent('b', 'relative', 'B');
      await repository.putSource({'id': 'modern'});
      await repository.putSource({'bookSourceUrl': 'legacy opaque'});
      expect((await repository.books()).map((e) => e.url), ['a', 'b']);
      expect(await repository.content('a', 'relative'), 'A');
      expect(await repository.content('b', 'relative'), 'B');
      await repository.removeBook('a');
      expect(await repository.content('a', 'relative'), isNull);
      expect(await repository.content('b', 'relative'), 'B');
      await repository.removeSource('legacy opaque');
      expect((await repository.sources()).single['id'], 'modern');
    },
  );

  test(
    'changes are emitted after a durable commit and omit failed writes',
    () async {
      final events = <Future<bool>>[];
      final subscription = repository.changes.listen((_) {
        events.add(
          Future.sync(() {
            final observer = sqlite3.open(path, mode: OpenMode.readOnly);
            try {
              return observer.select('SELECT id FROM books').length == 1;
            } finally {
              observer.close();
            }
          }),
        );
      });
      await repository.putBook(record('a'));
      await expectLater(
        repository.updateProgress('missing', 0, 0),
        throwsStateError,
      );
      await Future<void>.delayed(Duration.zero);
      expect(await Future.wait(events), [true]);
      expect(() => repository.updateProgress('a', -1, 0), throwsArgumentError);
      expect(() => repository.updateProgress('a', 0, -1), throwsArgumentError);
      await subscription.cancel();
    },
  );

  test('malformed or duplicate sources roll back shelf replacement', () async {
    await repository.putBook(record('a'));
    for (final source in [
      '[{"id":"same"},{"id":"same"}]',
      '[{"bookSourceName":"missing identity"}]',
    ]) {
      await expectLater(
        repository.restoreBackup(
          LegacyBackupDocument.parse({
            'bookshelf.json': '[]',
            'bookSource.json': source,
          }),
        ),
        throwsStateError,
      );
      expect((await repository.books()).single.url, 'a');
    }
  });

  test(
    'schema version is persisted and future schema is refused intact',
    () async {
      await repository.putBook(record('a'));
      await repository.close();
      final raw = sqlite3.open(path);
      expect(raw.userVersion, SqliteLibraryRepository.schemaVersion);
      raw.userVersion = 999;
      raw.close();
      await expectLater(SqliteLibraryRepository.open(path), throwsStateError);
      final check = sqlite3.open(path);
      expect(check.userVersion, 999);
      expect(check.select('SELECT id FROM books').single['id'], 'a');
      check.userVersion = SqliteLibraryRepository.schemaVersion;
      check.close();
      repository = await SqliteLibraryRepository.open(path);
    },
  );

  test('close drains accepted writes and rejects later operations', () async {
    final write = repository.putBook(record('a'));
    await repository.close();
    await write;
    await expectLater(repository.books(), throwsStateError);
    repository = await SqliteLibraryRepository.open(path);
    expect((await repository.books()).single.url, 'a');
  });
}
