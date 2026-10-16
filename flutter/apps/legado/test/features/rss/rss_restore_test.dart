import 'dart:convert';
import 'dart:io';

import 'package:app_core/app_core.dart';
import 'package:app_storage/app_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/features/rss/rss_models.dart';
import 'package:legado_app/features/rss/rss_service.dart';

void main() {
  late Directory directory;
  late String path;
  late SqliteLibraryRepository repository;
  setUp(() async {
    directory = await Directory.systemTemp.createTemp('legado-rss-restore-');
    path = '${directory.path}/library.sqlite';
    repository = await SqliteLibraryRepository.open(path);
  });
  tearDown(() async {
    await repository.close();
    await directory.delete(recursive: true);
  });
  Future<void> reopen() async {
    await repository.close();
    repository = await SqliteLibraryRepository.open(path);
  }

  test('real Drift restore immediately exposes legacy RSS and keeps native state and original bytes across reopen', () async {
    const legacy = ''' [
  {"sourceUrl":"https://feed.example/legacy","sourceName":"Legacy","sourceGroup":"Archive","enabled":false,"customOrder":-7,"ruleArticles":".entry","ruleTitle":"a@text","vendor":{"nested":[1,null,true]}},
  {"sourceUrl":"https://feed.example/conflict","sourceName":"Old configuration","header":"{\\"x-old\\":\\"yes\\"}"}
]\n''';
    final store = RssStore(repository);
    await store.importSources(
      '{"sourceUrl":"https://feed.example/conflict","sourceName":"Current configuration","localUnknown":{"keep":true}}',
    );
    await store.saveArticles('https://feed.example/conflict', [
      const RssArticle(
        id: 'one',
        title: 'Offline',
        link: '',
        content: 'Cached content',
        original: {'futureArticle': 5},
      ),
    ]);
    await store.mark(
      'https://feed.example/conflict',
      'one',
      read: true,
      starred: true,
    );
    final nativeBefore = await repository.setting(RssStore.storageKey);
    await repository.restoreBackup(
      LegacyBackupDocument.parse({
        'rssSources.json': legacy,
        'vendor.json': '{"raw":"retained"}',
      }),
    );
    for (var attempt = 0; attempt < 2; attempt++) {
      final current = RssStore(repository);
      final view = await current.sourceView();
      expect(view.restoredCount, 1);
      expect(view.conflictCount, 1);
      expect(view.sources.map((e) => e.name), [
        'Current configuration',
        'Legacy',
      ]);
      expect(view.sources.first.original['header'], isNull);
      final restored = view.sources.last;
      expect(restored.original['enabled'], false);
      expect(restored.original['customOrder'], -7);
      expect(restored.original['vendor'], {
        'nested': [1, null, true],
      });
      final article = (await current.articles('https://feed.example/conflict'))
          .single;
      expect(article.read, true);
      expect(article.starred, true);
      expect(article.content, 'Cached content');
      expect(article.original['futureArticle'], 5);
      expect(await repository.setting(RssStore.storageKey), nativeBefore);
      expect((await repository.backupFiles())['rssSources.json'], legacy);
      expect(
        (await repository.backupFiles())['vendor.json'],
        '{"raw":"retained"}',
      );
      if (attempt == 0) await reopen();
    }
  });

  test('legacy deletion stays deleted, does not discard articles, and explicit import clears tombstone', () async {
    const identity = 'https://feed.example/legacy';
    const legacy =
        '[{"sourceUrl":"$identity","sourceName":"Legacy","unknown":9}]';
    await repository.restoreBackup(
      LegacyBackupDocument.parse({'rssSources.json': legacy}),
    );
    final store = RssStore(repository);
    expect((await store.sources()).single.name, 'Legacy');
    await store.saveArticles(identity, [
      const RssArticle(id: 'keep', title: 'Offline', link: '', content: 'Keep'),
    ]);
    await store.mark(identity, 'keep', starred: true);
    await store.removeSource(identity);
    expect(await store.sources(), isEmpty);
    await reopen();
    final reopened = RssStore(repository);
    expect(await reopened.sources(), isEmpty);
    expect((await reopened.articles(identity)).single.starred, true);
    expect((await repository.backupFiles())['rssSources.json'], legacy);
    final deletedState =
        jsonDecode((await repository.setting(RssStore.storageKey))!) as Map;
    expect(deletedState['legacyRestore'], {
      'version': 1,
      'deletedSourceUrls': [identity],
    });
    final exported = await repository.backupFiles();
    final clone = await SqliteLibraryRepository.open(
      '${directory.path}/clone.sqlite',
    );
    try {
      await clone.restoreBackup(LegacyBackupDocument.parse(exported));
      expect(await RssStore(clone).sources(), isEmpty);
      expect((await RssStore(clone).articles(identity)).single.starred, true);
      expect((await clone.backupFiles())['rssSources.json'], legacy);
    } finally {
      await clone.close();
    }
    await reopened.importSources(
      '{"sourceUrl":"$identity","sourceName":"Explicitly added","newField":true}',
    );
    final restored = await reopened.sourceView();
    expect(restored.sources.single.name, 'Explicitly added');
    expect(restored.conflictCount, 1);
    expect(restored.restoredCount, 0);
    await reopen();
    expect(
      (await RssStore(repository).sources()).single.original['newField'],
      true,
    );
    expect(
      (await RssStore(repository).articles(identity)).single.content,
      'Keep',
    );
    expect((await repository.backupFiles())['rssSources.json'], legacy);
  });

  test('duplicate identity is explicit and never mutates either source of truth', () async {
    const original =
        '[{"sourceUrl":"https://example.org/rss","sourceName":"One"},{"sourceUrl":"https://example.org/rss","sourceName":"Two"}]';
    await repository.restoreBackup(
      LegacyBackupDocument.parse({'rssSources.json': original}),
    );
    final store = RssStore(repository);
    await expectLater(
      store.sources(),
      throwsA(
        isA<FormatException>().having(
          (e) => e.message,
          'code',
          'rssDuplicateRestoreSource',
        ),
      ),
    );
    expect(await repository.setting(RssStore.storageKey), isNull);
    expect((await repository.backupFiles())['rssSources.json'], original);
  });

  test('exact identities stay distinct and an explicitly empty restored list does not delete current app configuration', () async {
    final store = RssStore(repository);
    await store.importSources(
      '{"sourceUrl":"https://example.org/rss","sourceName":"Current"}',
    );
    await repository.restoreBackup(
      LegacyBackupDocument.parse({
        'rssSources.json': '[{"sourceUrl":"https://example.org/rss/","sourceName":"Different identity"}]',
      }),
    );
    expect((await store.sources()).map((e) => e.url), [
      'https://example.org/rss',
      'https://example.org/rss/',
    ]);
    expect((await store.sourceView()).conflictCount, 0);
    await repository.restoreBackup(
      LegacyBackupDocument.parse({'rssSources.json': ' []\n'}),
    );
    expect((await store.sources()).single.name, 'Current');
    expect((await repository.backupFiles())['rssSources.json'], ' []\n');
  });
}
