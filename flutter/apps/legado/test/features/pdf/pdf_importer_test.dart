import 'dart:io';
import 'dart:typed_data';

import 'package:app_storage/app_storage.dart';
import 'package:app_core/app_core.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/features/pdf/pdf_importer.dart';
import 'package:legado_app/features/pdf/pdf_reader_page.dart';
import 'package:pdfrx/pdfrx.dart';

import 'pdf_fixture.dart';
import 'pdf_native_test_support.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late Directory directory;
  setUpAll(() async {
    directory = await Directory.systemTemp.createTemp('legado-pdf-tests-');
    Pdfrx.cacheDirectoryPath = directory.path;
    configurePdfiumForFlutterTest();
    await pdfrxFlutterInitialize();
  });
  tearDownAll(() async => directory.delete(recursive: true));

  test('real PDFium validates PDF; atomic storage reopens with original bytes and page', () async {
    final path = '${directory.path}/library.sqlite';
    var repository = await SqliteLibraryRepository.open(path);
    final bytes = createPdfFixture();
    final book = await PdfImporter.importBytes(
      repository,
      name: 'Original.pdf',
      bytes: bytes,
    );
    expect(book.name, 'Original');
    expect(book.url, startsWith('local:pdf:'));
    final metadata = PdfBookDocument.fromBook(book)!;
    expect(metadata.pageCount, 2);
    expect(await repository.localAsset(metadata.assetKey), bytes);
    final session = await PdfReadingSession.open((
      repository: repository,
      bookUrl: book.url,
    ));
    expect(session.document.pages.length, 2);
    final image = await session.document.pages[0].render(
      fullWidth: 150,
      fullHeight: 200,
    );
    expect(image, isNotNull);
    expect(image!.pixels.any((pixel) => pixel != 255), isTrue);
    image.dispose();
    await session.savePage(2);
    await session.close();
    await repository.close();

    repository = await SqliteLibraryRepository.open(path);
    final reopened = await PdfReadingSession.open((
      repository: repository,
      bookUrl: book.url,
    ));
    expect(reopened.initialPage, 2);
    expect(await repository.localAsset(metadata.assetKey), bytes);
    final same = await PdfImporter.importBytes(
      repository,
      name: '/private/device/renamed.pdf',
      bytes: bytes,
    );
    expect(same.url, book.url);
    expect(same.chapterIndex, 1);
    expect((await repository.books()).length, 1);
    expect(same.toJson().toString(), isNot(contains('/private/device')));
    await reopened.close();
    await repository.removeBook(book.url);
    expect(await repository.localAsset(metadata.assetKey), isNull);
    await repository.close();
  });

  test(
    'invalid, truncated and over-limit input never publishes a book',
    () async {
      final repository = await SqliteLibraryRepository.inMemory();
      for (final bytes in [
        Uint8List.fromList([1, 2, 3]),
        Uint8List.fromList('%PDF-1.4\ninvalid'.codeUnits),
      ]) {
        await expectLater(
          PdfImporter.importBytes(repository, name: 'bad.pdf', bytes: bytes),
          throwsA(
            isA<PdfImportException>().having(
              (e) => e.failure,
              'failure',
              PdfFailure.invalid,
            ),
          ),
        );
      }
      await expectLater(
        PdfImporter.importBytes(
          repository,
          name: 'huge.pdf',
          bytes: Uint8List(PdfImporter.maximumBytes + 1),
        ),
        throwsA(
          isA<PdfImportException>().having(
            (e) => e.failure,
            'failure',
            PdfFailure.tooLarge,
          ),
        ),
      );
      expect(await repository.books(), isEmpty);
      await repository.close();
    },
  );

  test('reimport repairs a missing restored asset without resetting page or unknown fields', () async {
    final first = await SqliteLibraryRepository.inMemory();
    final bytes = createPdfFixture();
    final book = await PdfImporter.importBytes(
      first,
      name: 'Original.pdf',
      bytes: bytes,
    );
    final restored = await SqliteLibraryRepository.inMemory();
    await restored.putBook(
      LegacyBookRecord({
        ...book.toJson(),
        'durChapterIndex': 1,
        'futureField': {'keep': true},
      }),
    );
    final metadata = PdfBookDocument.fromBook(book)!;
    expect(await restored.localAsset(metadata.assetKey), isNull);
    final repaired = await PdfImporter.importBytes(
      restored,
      name: 'renamed.pdf',
      bytes: bytes,
    );
    expect(await restored.localAsset(metadata.assetKey), bytes);
    expect(repaired.chapterIndex, 1);
    expect(repaired.toJson()['futureField'], {'keep': true});
    expect(repaired.name, 'Original');
    await first.close();
    await restored.close();
  });

  test('password-protected and empty-password encrypted PDFs are rejected atomically', () async {
    final repository = await SqliteLibraryRepository.inMemory();
    for (final name in ['password.pdf', 'empty-password.pdf']) {
      final bytes = await File('test/fixtures/pdf/$name').readAsBytes();
      await expectLater(
        PdfImporter.importBytes(repository, name: name, bytes: bytes),
        throwsA(
          isA<PdfImportException>().having(
            (error) => error.failure,
            'failure',
            PdfFailure.encrypted,
          ),
        ),
      );
    }
    expect(await repository.books(), isEmpty);
    await repository.close();
  });

  test(
    'PDF metadata rejects traversal, non-PDF assets and invalid page counts',
    () async {
      final repository = await SqliteLibraryRepository.inMemory();
      final book = await PdfImporter.importBytes(
        repository,
        name: 'valid.pdf',
        bytes: createPdfFixture(),
      );
      final json = book.toJson();
      for (final document in [
        {'format': 'pdf', 'assetKey': '../document.pdf', 'pageCount': 2},
        {
          'format': 'pdf',
          'assetKey': (json['flutterDocument'] as Map)['assetKey'],
          'pageCount': 0,
        },
        {
          'format': 'epub',
          'assetKey': (json['flutterDocument'] as Map)['assetKey'],
          'pageCount': 2,
        },
      ]) {
        // Persisted metadata must be checked again after restoring an archive.
        json['flutterDocument'] = document;
        await repository.putBook(LegacyBookRecord(json));
        expect(
          PdfBookDocument.fromBook((await repository.book(book.url))!),
          isNull,
        );
      }
      await repository.close();
    },
  );
}
