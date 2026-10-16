import 'dart:convert';
import 'dart:io';

import 'package:app_core/app_core.dart';
import 'package:app_storage/app_storage.dart';
import 'package:sqlite3/sqlite3.dart';
import 'package:test/test.dart';

LegacyBookRecord book(String id, {int mask = 0, int order = 0}) =>
    LegacyBookRecord({
      'bookUrl': id,
      'name': id,
      'group': mask,
      'order': order,
      'unknown': {'keep': true},
    });

void main() {
  late Directory directory;
  late String path;
  late SqliteLibraryRepository repository;
  setUp(() async {
    directory = await Directory.systemTemp.createTemp('legado-groups-');
    path = '${directory.path}/library.sqlite';
    repository = await SqliteLibraryRepository.open(path);
  });
  tearDown(() async {
    await repository.close();
    await directory.delete(recursive: true);
  });

  test(
    'legacy groups restore, edit, membership and sorting survive reopen/export',
    () async {
      await repository.restoreBackup(
        LegacyBackupDocument.parse({
          'bookGroup.json': '[{"groupId":1,"groupName":"旧组","order":2,"bookSort":5,"enableRefresh":false,"future":{"x":1}}, {"groupId":-2,"groupName":"本地","show":false,"future":true}]',
          'bookshelf.json': jsonEncode([book('a', mask: 65).toJson()]),
        }),
      );
      final old = (await repository.bookGroups()).firstWhere((g) => g.id == 1);
      await repository.updateBookGroup(
        LegacyBookGroup(old.toJson()..['groupName'] = '新名称'),
      );
      final created = await repository.createBookGroup('新分组');
      expect(created.id, 2); // Orphan bit 64 must not be reallocated.
      await repository.setBookGroups('a', [created.id]);
      await repository.setSetting('bookshelf.selectedGroup', '${created.id}');
      await repository.setSetting('bookshelf.sort', '3');
      await repository.close();
      repository = await SqliteLibraryRepository.open(path);
      expect((await repository.book('a'))!.toJson()['group'], 66);
      expect((await repository.book('a'))!.toJson()['unknown'], {'keep': true});
      final groups = await repository.bookGroups();
      expect(groups.firstWhere((g) => g.id == 1).name, '新名称');
      expect(groups.firstWhere((g) => g.id == 1).toJson()['future'], {'x': 1});
      expect(groups.firstWhere((g) => g.id == -2).show, false);
      expect(await repository.setting('bookshelf.selectedGroup'), '2');
      final exported = jsonDecode(
        (await repository.backupFiles())['bookGroup.json']!,
      ) as List;
      expect(
        exported.firstWhere((g) => g['groupId'] == 1)['enableRefresh'],
        false,
      );
      expect(exported.length, 3);
      await repository.removeBookGroup(created.id);
      expect((await repository.book('a'))!.toJson()['group'], 64);
      expect(await repository.setting('bookshelf.selectedGroup'), '-1');
      expect(await repository.book('a'), isNotNull);
    },
  );

  test(
    'missing group file preserves and explicit empty replaces groups only',
    () async {
      await repository.putBook(book('a', mask: 1));
      await repository.createBookGroup('One');
      await repository.restoreBackup(
        LegacyBackupDocument.parse({'future.txt': 'keep'}),
      );
      expect((await repository.bookGroups()).length, 1);
      await repository.restoreBackup(
        LegacyBackupDocument.parse({'bookGroup.json': '[]'}),
      );
      expect(await repository.bookGroups(), isEmpty);
      expect((await repository.book('a'))!.toJson()['group'], 1);
      expect((await repository.backupFiles())['future.txt'], 'keep');
    },
  );

  test(
    'invalid group restore rolls back replacement shelf and groups',
    () async {
      await repository.putBook(book('original'));
      await repository.createBookGroup('Keep');
      final before = await repository.backupFiles();
      for (final list in [
        '[{"groupId":1},{"groupId":1}]',
        '[{"groupName":"missing id"}]',
      ]) {
        await expectLater(
          repository.restoreBackup(
            LegacyBackupDocument.parse({
              'bookshelf.json': '[]',
              'bookGroup.json': list,
            }),
          ),
          throwsStateError,
        );
        expect(await repository.backupFiles(), before);
      }
      await expectLater(
        repository.setBookGroups('original', [-1]),
        throwsStateError,
      );
      expect((await repository.book('original'))!.toJson()['group'], 0);
    },
  );

  test(
    'manual subset reorder preserves other slots and unknown book fields',
    () async {
      for (var i = 0; i < 3; i++) {
        await repository.putBook(book(['a', 'b', 'c'][i], order: i));
      }
      await repository.reorderBooks(['c', 'a']);
      await repository.close();
      repository = await SqliteLibraryRepository.open(path);
      final rows = selectBookshelfBooks(
        await repository.books(),
        [],
        sort: BookshelfSort.manual,
      );
      expect(rows.map((b) => b.url), ['c', 'b', 'a']);
      expect(rows.every((b) => b.toJson()['unknown'] != null), true);
      await expectLater(
        repository.reorderBooks(['a', 'missing']),
        throwsStateError,
      );
      expect(
        selectBookshelfBooks(
          await repository.books(),
          [],
          sort: BookshelfSort.manual,
        ).map((b) => b.url),
        ['c', 'b', 'a'],
      );
    },
  );

  test('schema2 migrates opaque bookGroup backup into typed rows losslessly', () async {
    await repository.putBook(book('a', mask: 8));
    await repository.close();
    final raw = sqlite3.open(path);
    raw.execute('DROP TABLE book_groups');
    raw.execute('INSERT OR REPLACE INTO backup_files VALUES (?, ?)', [
      'bookGroup.json',
      '[{"groupId":8,"groupName":"Migrated","cover":"opaque","future":[1,2]}]',
    ]);
    raw.userVersion = 2;
    raw.close();
    repository = await SqliteLibraryRepository.open(path);
    final group = (await repository.bookGroups()).single;
    expect(group.id, 8);
    expect(group.toJson()['future'], [1, 2]);
    expect(
      selectBookshelfBooks(await repository.books(), [
        group,
      ], groupId: 8).single.url,
      'a',
    );
  });

  test(
    'failed group schema migration rolls back schema and old file bytes',
    () async {
      await repository.close();
      final raw = sqlite3.open(path);
      raw.execute('DROP TABLE book_groups');
      raw.execute('INSERT INTO backup_files VALUES (?, ?)', [
        'bookGroup.json',
        '[{"groupId":1},{"groupId":1}]',
      ]);
      raw.userVersion = 2;
      raw.close();
      await expectLater(SqliteLibraryRepository.open(path), throwsStateError);
      final check = sqlite3.open(path);
      expect(check.userVersion, 2);
      expect(
        check.select(
          "SELECT name FROM sqlite_master WHERE name = 'book_groups'",
        ),
        isEmpty,
      );
      expect(
        check
            .select("SELECT value FROM backup_files WHERE id='bookGroup.json'")
            .single['value'],
        '[{"groupId":1},{"groupId":1}]',
      );
      check.execute("DELETE FROM backup_files WHERE id='bookGroup.json'");
      check.close();
      repository = await SqliteLibraryRepository.open(path);
    },
  );
}
