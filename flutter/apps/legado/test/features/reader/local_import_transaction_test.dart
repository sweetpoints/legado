import 'dart:io';
import 'dart:typed_data';

import 'package:app_backup/app_backup.dart';
import 'package:app_storage/app_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/features/reader/epub_importer.dart';
import 'package:legado_app/features/reader/local_text_importer.dart';
import 'package:sqlite3/sqlite3.dart';

import 'epub_importer_test.dart' show fixture, zip;

void main() {
  late Directory directory;
  late String path;
  late SqliteLibraryRepository repository;
  setUp(() async {
    directory = await Directory.systemTemp.createTemp('legado-importer-');
    path = '${directory.path}/library.sqlite';
    repository = await SqliteLibraryRepository.open(path);
  });
  tearDown(() async {
    await repository.close();
    await directory.delete(recursive: true);
  });

  test('TXT importer commits complete original text and keeps progress on reimport', () async {
    const text = '第一章 开始\r\n春日正文😀\r\n第二章 继续\r\n夜晚正文。';
    final imported = await LocalTextImporter.importText(
      repository,
      name: 'Book',
      text: text,
    );
    final chapters = await repository.chapters(imported.url);
    expect(chapters.length, 2);
    expect(
      (await Future.wait(
        chapters.map((c) => repository.content(imported.url, c.url)),
      )).join(),
      text,
    );
    await repository.updateProgress(imported.url, 1, 8);
    final reimported = await LocalTextImporter.importText(
      repository,
      name: 'Renamed file',
      text: text,
    );
    expect(reimported.characterOffset, 8);
    await repository.close();
    repository = await SqliteLibraryRepository.open(path);
    expect((await repository.book(imported.url))!.chapterIndex, 1);
    expect((await repository.chapters(imported.url)).length, 2);
  });

  for (final format in ['TXT', 'EPUB']) {
    test(
      '$format importer late publication failure leaves no orphan rows',
      () async {
        final before = await repository.backupSnapshot();
        final raw = sqlite3.open(path);
        raw.execute(
          "CREATE TRIGGER reject_import BEFORE INSERT ON books WHEN NEW.id LIKE 'local:%' BEGIN SELECT RAISE(ABORT, 'simulated'); END",
        );
        raw.close();
        final importing = format == 'TXT'
            ? LocalTextImporter.importText(
                repository,
                name: 'Book',
                text: '第一章 开始\n正文。',
              )
            : EpubImporter.importBytes(
                repository,
                name: 'Book.epub',
                bytes: zip(fixture()),
              );
        await expectLater(importing, throwsStateError);
        final after = await repository.backupSnapshot();
        expect(after.files, before.files);
        expect(after.entries, before.entries);
        expect(await repository.books(), isEmpty);
      },
    );
  }

  test(
    'small original EPUB bytes survive actual ZIP export, restore and reopen',
    () async {
      final sourceBytes = zip(fixture());
      final book = await EpubImporter.importBytes(
        repository,
        name: 'Book.epub',
        bytes: sourceBytes,
      );
      final key = book.toJson()['originalAssetKey'] as String;
      expect(await repository.localAsset(key), sourceBytes);
      final snapshot = await repository.backupSnapshot();
      final exportedZip = LegacyBackupArchive.encodeFiles(
        snapshot.files,
        retainedEntries: {
          for (final entry in snapshot.entries.entries)
            entry.key: Uint8List.fromList(entry.value),
        },
      );
      final decoded = LegacyBackupArchive.decode(exportedZip);
      expect(decoded.retainedEntries[key], sourceBytes);
      final targetPath = '${directory.path}/restored.sqlite';
      var restored = await SqliteLibraryRepository.open(targetPath);
      try {
        await restored.restoreArchive(
          decoded.document,
          decoded.retainedEntries,
        );
        await restored.close();
        restored = await SqliteLibraryRepository.open(targetPath);
        expect(await restored.localAsset(key), sourceBytes);
        expect((await restored.chapters(book.url)).length, 2);
        expect(
          (await restored.book(book.url))!.toJson()['originalAssetKey'],
          key,
        );
        await restored.removeBook(book.url);
        expect(await restored.localAsset(key), isNull);
      } finally {
        await restored.close();
      }
    },
  );
}
