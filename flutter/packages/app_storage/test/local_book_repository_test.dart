import 'dart:collection';
import 'dart:convert';
import 'dart:io';

import 'package:app_core/app_core.dart';
import 'package:app_storage/app_storage.dart';
import 'package:sqlite3/sqlite3.dart';
import 'package:test/test.dart';

final digest = 'a' * 64;
final url = 'local:pdf:$digest';
final assetKey = '${LocalBookAssets.prefix}$digest/document.pdf';
LegacyBookRecord record() => LegacyBookRecord({
  'bookUrl': url,
  'name': 'Local book',
  'origin': 'local',
  'format': 'pdf',
  'assetKey': assetKey,
  'unknown': {'future': true},
});
ReaderChapter chapter() =>
    ReaderChapter(url: '$url#0', title: 'Chapter', index: 0);

/// Fails if rejected oversize data is copied before its length is validated.
class _UnmaterializedBytes extends ListBase<int> {
  _UnmaterializedBytes(this.length);
  @override
  int length;
  @override
  int operator [](int index) => throw StateError('Must not materialize');
  @override
  void operator []=(int index, int value) =>
      throw UnsupportedError('read-only');
}

void main() {
  late Directory directory;
  late String path;
  late SqliteLibraryRepository repository;
  setUp(() async {
    directory = await Directory.systemTemp.createTemp('legado-local-import-');
    path = '${directory.path}/library.sqlite';
    repository = await SqliteLibraryRepository.open(path);
  });
  tearDown(() async {
    await repository.close();
    await directory.delete(recursive: true);
  });

  test('atomic import copies inputs and retains text/assets through reopen and restore', () async {
    final bytes = [0x25, 0x50, 0x44, 0x46, 0, 255];
    final chapters = [chapter()];
    final contents = {chapter().url: '正文\r\n😀'};
    final importing = repository.importLocalBook(
      record(),
      chapters,
      contents,
      assetsByKey: {assetKey: bytes},
    );
    chapters.clear();
    contents.clear();
    bytes[0] = 0;
    await importing;
    await repository.close();
    repository = await SqliteLibraryRepository.open(path);
    expect((await repository.book(url))!.toJson()['unknown'], {'future': true});
    expect((await repository.chapters(url)).single.url, chapter().url);
    expect(await repository.content(url, chapter().url), '正文\r\n😀');
    expect(await repository.localAsset(assetKey), [
      0x25,
      0x50,
      0x44,
      0x46,
      0,
      255,
    ]);
    final exported = await repository.backupSnapshot();
    final restored = await SqliteLibraryRepository.inMemory();
    try {
      await restored.restoreArchive(
        LegacyBackupDocument.parse(exported.files),
        exported.entries,
      );
      expect(await restored.localAsset(assetKey), [
        0x25,
        0x50,
        0x44,
        0x46,
        0,
        255,
      ]);
      expect(await restored.content(url, chapter().url), '正文\r\n😀');
      expect(
        (await restored.book(url))!.toJson(),
        (await repository.book(url))!.toJson(),
      );
    } finally {
      await restored.close();
    }
  });

  test('late SQL publication failure rolls back contents, chapters and assets', () async {
    await repository.restoreArchive(
      LegacyBackupDocument.parse({'future.txt': 'retain'}),
      {
        'servers': [1, 0, 255],
      },
    );
    final before = await repository.backupSnapshot();
    final raw = sqlite3.open(path);
    raw.execute(
      "CREATE TRIGGER reject_local_book BEFORE INSERT ON books WHEN NEW.id = '$url' BEGIN SELECT RAISE(ABORT, 'simulated write failure'); END",
    );
    raw.close();
    var changes = 0;
    final subscription = repository.changes.listen((_) => changes++);
    await expectLater(
      repository.importLocalBook(
        record(),
        [chapter()],
        {chapter().url: 'text'},
        assetsByKey: {
          assetKey: [7, 8],
        },
      ),
      throwsStateError,
    );
    expect(await repository.book(url), isNull);
    expect(await repository.chapters(url), isEmpty);
    expect(await repository.content(url, chapter().url), isNull);
    expect(await repository.localAsset(assetKey), isNull);
    final after = await repository.backupSnapshot();
    expect(after.files, before.files);
    expect(after.entries, before.entries);
    await Future<void>.delayed(Duration.zero);
    expect(changes, 0);
    await subscription.cancel();
  });

  test('import never overwrites a colliding unknown retained entry', () async {
    await repository.restoreArchive(LegacyBackupDocument.parse({}), {
      assetKey: [9, 9],
      'servers': [3],
    });
    await expectLater(
      repository.importLocalBook(
        record(),
        [chapter()],
        {chapter().url: 'new'},
        assetsByKey: {
          assetKey: [1, 2],
        },
      ),
      throwsStateError,
    );
    expect(await repository.book(url), isNull);
    expect(await repository.chapters(url), isEmpty);
    expect((await repository.backupSnapshot()).entries, {
      assetKey: [9, 9],
      'servers': [3],
    });
    await repository.restoreArchive(
      LegacyBackupDocument.parse({assetKey: 'old opaque text'}),
      {
        'servers': [3],
      },
    );
    await expectLater(
      repository.importLocalBook(
        record(),
        [],
        {},
        assetsByKey: {
          assetKey: [1],
        },
      ),
      throwsStateError,
    );
    expect(await repository.backupFile(assetKey), 'old opaque text');
  });

  test('re-import preserves existing reading progress and content', () async {
    await repository.importLocalBook(
      record(),
      [chapter()],
      {chapter().url: 'original'},
      assetsByKey: {
        assetKey: [1],
      },
    );
    await repository.updateProgress(url, 2, 34);
    await repository.importLocalBook(
      LegacyBookRecord(record().toJson()..['name'] = 'replacement'),
      [chapter()],
      {chapter().url: 'replacement'},
      assetsByKey: {
        assetKey: [2],
      },
    );
    expect((await repository.book(url))!.name, 'Local book');
    expect((await repository.book(url))!.characterOffset, 34);
    expect(await repository.content(url, chapter().url), 'original');
    expect(await repository.localAsset(assetKey), [1]);
  });

  test('rejects incomplete contents, unsafe keys, wrong namespace and invalid bytes', () async {
    await expectLater(
      repository.importLocalBook(record(), [chapter()], {}),
      throwsStateError,
    );
    await expectLater(
      repository.importLocalBook(record(), [], {'orphan': 'bad'}),
      throwsStateError,
    );
    for (final key in [
      'covers/old.png',
      '${LocalBookAssets.prefix}$digest/../bad',
      '${LocalBookAssets.prefix}$digest/%2e%2e/bad',
      '${LocalBookAssets.prefix}${'b' * 64}/document.pdf',
    ]) {
      await expectLater(
        repository.importLocalBook(
          record(),
          [],
          {},
          assetsByKey: {
            key: [1],
          },
        ),
        throwsStateError,
      );
    }
    await expectLater(
      repository.importLocalBook(
        record(),
        [],
        {},
        assetsByKey: {
          assetKey: [-1],
        },
      ),
      throwsStateError,
    );
    await expectLater(repository.localAsset('../servers'), throwsArgumentError);
    expect(await repository.books(), isEmpty);
    expect((await repository.backupSnapshot()).entries, isEmpty);
  });

  test('asset limits are checked before copying buffers', () async {
    await expectLater(
      repository.importLocalBook(
        record(),
        [],
        {},
        assetsByKey: {
          assetKey: _UnmaterializedBytes(LocalBookAssets.maximumAssetBytes + 1),
        },
      ),
      throwsArgumentError,
    );
    await expectLater(
      repository.importLocalBook(
        record(),
        [],
        {},
        assetsByKey: {
          for (var i = 0; i < 3; i++)
            '${LocalBookAssets.prefix}$digest/$i.pdf': _UnmaterializedBytes(
              LocalBookAssets.maximumAssetBytes,
            ),
        },
      ),
      throwsArgumentError,
    );
    await expectLater(
      repository.importLocalBook(
        record(),
        [],
        {},
        assetsByKey: {
          for (var i = 0; i <= LocalBookAssets.maximumAssetCount; i++)
            '${LocalBookAssets.prefix}$digest/$i.pdf': <int>[],
        },
      ),
      throwsArgumentError,
    );
    expect(await repository.books(), isEmpty);
  });

  test('single-asset read rejects oversize restored BLOB in SQLite', () async {
    final raw = sqlite3.open(path);
    raw.execute('INSERT INTO archive_entries VALUES (?, zeroblob(?))', [
      assetKey,
      LocalBookAssets.maximumAssetBytes + 1,
    ]);
    raw.close();
    await expectLater(repository.localAsset(assetKey), throwsStateError);
  });

  test(
    'deleting an imported book reclaims only its owned asset after reopen',
    () async {
      await repository.restoreArchive(LegacyBackupDocument.parse({}), {
        'servers': [9],
      });
      await repository.importLocalBook(
        record(),
        [chapter()],
        {chapter().url: 'text'},
        assetsByKey: {
          assetKey: [1, 2],
        },
      );
      await repository.close();
      repository = await SqliteLibraryRepository.open(path);
      await repository.removeBook(url);
      expect(await repository.book(url), isNull);
      expect(await repository.chapters(url), isEmpty);
      expect(await repository.localAsset(assetKey), isNull);
      final snapshot = await repository.backupSnapshot();
      expect(snapshot.entries, {
        'servers': [9],
      });
      expect(
        jsonDecode(
          snapshot.files[SqliteLibraryRepository.backupEntry]!,
        )['localAssets'],
        isEmpty,
      );
    },
  );

  test(
    'shared managed asset survives until its last book reference is removed',
    () async {
      final secondUrl = 'local:epub:$digest';
      await repository.importLocalBook(
        record(),
        [],
        {},
        assetsByKey: {
          assetKey: [1, 2],
        },
      );
      await repository.importLocalBook(
        LegacyBookRecord(record().toJson()..['bookUrl'] = secondUrl),
        [],
        {},
        assetsByKey: {
          assetKey: [1, 2],
        },
      );
      await repository.removeBook(url);
      expect(await repository.localAsset(assetKey), [1, 2]);
      expect(await repository.book(secondUrl), isNotNull);
      await repository.removeBook(secondUrl);
      expect(await repository.localAsset(assetKey), isNull);
    },
  );

  test(
    'same-byte unknown legacy asset is reused without claiming ownership',
    () async {
      await repository.restoreArchive(LegacyBackupDocument.parse({}), {
        assetKey: [1, 2],
        'servers': [9],
      });
      await repository.importLocalBook(
        record(),
        [],
        {},
        assetsByKey: {
          assetKey: [1, 2],
        },
      );
      await repository.removeBook(url);
      expect((await repository.backupSnapshot()).entries, {
        assetKey: [1, 2],
        'servers': [9],
      });
    },
  );

  test(
    'ownership and unknown reference fields survive archive restore',
    () async {
      await repository.importLocalBook(
        record(),
        [],
        {},
        assetsByKey: {
          assetKey: [1, 2],
        },
      );
      final snapshot = await repository.backupSnapshot();
      final extension = jsonDecode(
        snapshot.files[SqliteLibraryRepository.backupEntry]!,
      ) as Map<String, dynamic>;
      (extension['localAssets'] as List).single['futureRef'] = {'keep': true};
      final files = {
        ...snapshot.files,
        SqliteLibraryRepository.backupEntry: jsonEncode(extension),
      };
      final other = await SqliteLibraryRepository.inMemory();
      try {
        await other.restoreArchive(
          LegacyBackupDocument.parse(files),
          snapshot.entries,
        );
        final exported = await other.backupFiles();
        expect(
          jsonDecode(
            exported[SqliteLibraryRepository.backupEntry]!,
          )['localAssets'].single['futureRef'],
          {'keep': true},
        );
        await other.removeBook(url);
        expect(await other.localAsset(assetKey), isNull);
      } finally {
        await other.close();
      }
    },
  );

  test('replacement archive without ownership cannot inherit stale deletion rights', () async {
    await repository.importLocalBook(
      record(),
      [],
      {},
      assetsByKey: {
        assetKey: [1, 2],
      },
    );
    await repository.restoreArchive(
      LegacyBackupDocument.parse({
        'bookshelf.json': jsonEncode([record().toJson()]),
      }),
      {
        assetKey: [8, 9],
      },
    );
    await repository.removeBook(url);
    expect(await repository.localAsset(assetKey), [8, 9]);
  });

  test('late delete failure rolls back book removal and owned asset reclamation', () async {
    await repository.importLocalBook(
      record(),
      [],
      {},
      assetsByKey: {
        assetKey: [1, 2],
      },
    );
    final before = await repository.backupSnapshot();
    final raw = sqlite3.open(path);
    raw.execute(
      "CREATE TRIGGER reject_removal BEFORE DELETE ON books WHEN OLD.id = '$url' BEGIN SELECT RAISE(ABORT, 'simulated delete failure'); END",
    );
    raw.close();
    await expectLater(repository.removeBook(url), throwsStateError);
    final after = await repository.backupSnapshot();
    expect(after.files, before.files);
    expect(after.entries, before.entries);
    expect(await repository.localAsset(assetKey), [1, 2]);
  });

  test(
    'schema3 upgrade preserves old bytes without inferring asset ownership',
    () async {
      await repository.importLocalBook(
        record(),
        [],
        {},
        assetsByKey: {
          assetKey: [1, 2],
        },
      );
      await repository.close();
      final raw = sqlite3.open(path);
      raw.execute('DROP TABLE local_book_assets');
      raw.userVersion = 3;
      raw.close();
      repository = await SqliteLibraryRepository.open(path);
      expect(await repository.localAsset(assetKey), [1, 2]);
      await repository.removeBook(url);
      expect(await repository.localAsset(assetKey), [1, 2]);
    },
  );

  test(
    'schema3 ownership migration failure preserves version and original rows',
    () async {
      final oldPath = '${directory.path}/ownership-conflict.sqlite';
      final raw = sqlite3.open(oldPath);
      raw.execute('CREATE TABLE sentinel (value TEXT)');
      raw.execute('CREATE INDEX local_book_assets ON sentinel(value)');
      raw.execute("INSERT INTO sentinel VALUES ('preserved')");
      raw.userVersion = 3;
      raw.close();
      await expectLater(
        SqliteLibraryRepository.open(oldPath),
        throwsStateError,
      );
      final check = sqlite3.open(oldPath);
      expect(check.userVersion, 3);
      expect(
        check.select('SELECT value FROM sentinel').single['value'],
        'preserved',
      );
      expect(
        check.select(
          "SELECT name FROM sqlite_master WHERE type='table' AND name='local_book_assets'",
        ),
        isEmpty,
      );
      check.close();
    },
  );

  test(
    'invalid ownership claims roll back text and retained asset replacement',
    () async {
      await repository.importLocalBook(
        record(),
        [],
        {},
        assetsByKey: {
          assetKey: [1, 2],
        },
      );
      final before = await repository.backupSnapshot();
      for (final refs in [
        [
          {'bookUrl': url, 'assetKey': 'servers', 'managed': true},
        ],
        [
          {'bookUrl': url, 'assetKey': assetKey, 'managed': true},
          {
            'bookUrl': 'local:epub:$digest',
            'assetKey': assetKey,
            'managed': false,
          },
        ],
      ]) {
        await expectLater(
          repository.restoreArchive(
            LegacyBackupDocument.parse({
              'bookshelf.json': '[]',
              SqliteLibraryRepository.backupEntry: jsonEncode({
                'version': 1,
                'localAssets': refs,
              }),
            }),
            {
              'servers': [7],
            },
          ),
          throwsStateError,
        );
        final after = await repository.backupSnapshot();
        expect(after.files, before.files);
        expect(after.entries, before.entries);
      }
    },
  );

  test('idempotent reimport never leaves new unreferenced resources', () async {
    final extra = '${LocalBookAssets.prefix}$digest/new.pdf';
    await repository.importLocalBook(
      record(),
      [],
      {},
      assetsByKey: {
        assetKey: [1],
      },
    );
    await repository.importLocalBook(
      record(),
      [],
      {},
      assetsByKey: {
        extra: [2],
      },
    );
    expect(await repository.localAsset(extra), isNull);
    await repository.removeBook(url);
    expect((await repository.backupSnapshot()).entries, isEmpty);
  });

  test(
    'text-only restore cannot claim deletion rights over old unknown bytes',
    () async {
      await repository.restoreArchive(LegacyBackupDocument.parse({}), {
        assetKey: [9],
      });
      await repository.restoreBackup(
        LegacyBackupDocument.parse({
          'bookshelf.json': jsonEncode([record().toJson()]),
          SqliteLibraryRepository.backupEntry: jsonEncode({
            'version': 1,
            'localAssets': [
              {'bookUrl': url, 'assetKey': assetKey, 'managed': true},
            ],
          }),
        }),
      );
      await repository.removeBook(url);
      expect(await repository.localAsset(assetKey), [9]);
    },
  );
}
