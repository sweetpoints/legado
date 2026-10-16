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
  'name': 'Original PDF',
  'origin': 'loc_book',
  'durChapterIndex': 1,
  'durChapterPos': 34,
  'unknown': {'keep': true},
  'flutterDocument': {'format': 'pdf', 'assetKey': assetKey, 'pageCount': 2},
});
ReaderChapter chapter() =>
    ReaderChapter(url: '$url#0', title: 'Chapter', index: 0);

void main() {
  late Directory directory;
  late String path;
  late SqliteLibraryRepository repository;
  setUp(() async {
    directory = await Directory.systemTemp.createTemp('legado-local-repair-');
    path = '${directory.path}/library.sqlite';
    repository = await SqliteLibraryRepository.open(path);
  });
  tearDown(() async {
    await repository.close();
    await directory.delete(recursive: true);
  });

  test('existing PDF repairs its referenced missing asset and preserves all reading data', () async {
    final original = record();
    await repository.putBook(original);
    await repository.putChapters(url, [chapter()]);
    await repository.putContent(url, chapter().url, 'old content');
    await repository.importLocalBook(
      LegacyBookRecord(
        original.toJson()..['name'] = 'different incoming title',
      ),
      [ReaderChapter(url: chapter().url, title: 'New title', index: 0)],
      {chapter().url: 'new content'},
      assetsByKey: {
        assetKey: [0x25, 0x50, 0x44, 0x46],
      },
    );
    expect((await repository.book(url))!.toJson(), original.toJson());
    expect((await repository.chapters(url)).single.title, 'Chapter');
    expect(await repository.content(url, chapter().url), 'old content');
    expect(await repository.localAsset(assetKey), [0x25, 0x50, 0x44, 0x46]);
    await repository.close();
    repository = await SqliteLibraryRepository.open(path);
    expect((await repository.book(url))!.characterOffset, 34);
    await repository.removeBook(url);
    expect(await repository.localAsset(assetKey), isNull);
  });

  test('existing PDF rejects conflicting retained bytes without changing metadata or ownership', () async {
    await repository.restoreArchive(
      LegacyBackupDocument.parse({
        'bookshelf.json': jsonEncode([record().toJson()]),
      }),
      {
        assetKey: [9],
        'servers': [7],
      },
    );
    final before = await repository.backupSnapshot();
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
    final after = await repository.backupSnapshot();
    expect(after.files, before.files);
    expect(after.entries, before.entries);
  });

  test('PDF repair rolls back staged BLOB on the final ownership write failure', () async {
    await repository.putBook(record());
    final before = await repository.backupSnapshot();
    final raw = sqlite3.open(path);
    raw.execute(
      "CREATE TRIGGER reject_repair_owner BEFORE INSERT ON local_book_assets WHEN NEW.book_id = '$url' BEGIN SELECT RAISE(ABORT, 'simulated owner failure'); END",
    );
    raw.close();
    var changes = 0;
    final subscription = repository.changes.listen((_) => changes++);
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
    final after = await repository.backupSnapshot();
    expect(after.files, before.files);
    expect(after.entries, before.entries);
    expect(await repository.localAsset(assetKey), isNull);
    await Future<void>.delayed(Duration.zero);
    expect(changes, 0);
    await subscription.cancel();
  });

  test('incoming metadata cannot inject an asset the stored PDF does not reference', () async {
    await repository.putBook(record());
    final unrelated = '${LocalBookAssets.prefix}$digest/unrelated.pdf';
    final incoming = LegacyBookRecord(
      record().toJson()
        ..['flutterDocument'] = {
          'format': 'pdf',
          'assetKey': unrelated,
          'pageCount': 2,
        },
    );
    await repository.importLocalBook(
      incoming,
      [],
      {},
      assetsByKey: {
        unrelated: [1],
      },
    );
    expect(await repository.localAsset(unrelated), isNull);
    expect((await repository.book(url))!.toJson(), record().toJson());
    expect((await repository.backupSnapshot()).entries, isEmpty);
  });

  test(
    'dangling ownership is repaired without losing unknown reference fields',
    () async {
      await repository.restoreArchive(
        LegacyBackupDocument.parse({
          'bookshelf.json': jsonEncode([record().toJson()]),
          SqliteLibraryRepository.backupEntry: jsonEncode({
            'version': 1,
            'localAssets': [
              {
                'bookUrl': url,
                'assetKey': assetKey,
                'managed': true,
                'future': {'keep': 1},
              },
            ],
          }),
        }),
        {},
      );
      await repository.importLocalBook(
        record(),
        [],
        {},
        assetsByKey: {
          assetKey: [1],
        },
      );
      final files = await repository.backupFiles();
      final refs =
          jsonDecode(files[SqliteLibraryRepository.backupEntry]!)['localAssets']
              as List;
      expect(refs.single['future'], {'keep': 1});
      expect(refs.single['managed'], true);
      await repository.removeBook(url);
      expect(await repository.localAsset(assetKey), isNull);
    },
  );

  test(
    'same-byte legacy asset checked during repair remains unmanaged',
    () async {
      await repository.restoreArchive(
        LegacyBackupDocument.parse({
          'bookshelf.json': jsonEncode([record().toJson()]),
        }),
        {
          assetKey: [9],
        },
      );
      await repository.importLocalBook(
        record(),
        [],
        {},
        assetsByKey: {
          assetKey: [9],
        },
      );
      await repository.removeBook(url);
      expect(await repository.localAsset(assetKey), [9]);
    },
  );
}
