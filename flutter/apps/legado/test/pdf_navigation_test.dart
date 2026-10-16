import 'dart:io';

import 'package:app_storage/app_storage.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/app/providers.dart';
import 'package:legado_app/features/pdf/pdf_importer.dart';
import 'package:legado_app/features/pdf/pdf_reader_page.dart';
import 'package:legado_app/main.dart';
import 'package:pdfrx/pdfrx.dart';

import 'app_flow_test.dart' show cleanup, databaseCall, waitFor;
import 'features/pdf/pdf_fixture.dart';
import 'features/pdf/pdf_native_test_support.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late Directory cache;
  setUpAll(() async {
    cache = await Directory.systemTemp.createTemp('legado-pdf-navigation-');
    configurePdfiumForFlutterTest();
    Pdfrx.cacheDirectoryPath = cache.path;
    await pdfrxFlutterInitialize();
  });
  tearDownAll(() => cache.delete(recursive: true));

  testWidgets('bookshelf opens a durable PDF in its real viewer route', (
    tester,
  ) async {
    final repository = (await tester.runAsync(
      SqliteLibraryRepository.inMemory,
    ))!;
    final book = (await tester.runAsync(() async {
      await repository.setSetting('app.locale', 'en');
      return PdfImporter.importBytes(
        repository,
        name: 'Route PDF.pdf',
        bytes: createPdfFixture(),
      );
    }))!;
    final container = ProviderContainer(
      overrides: [
        persistentLibraryProvider.overrideWith((ref) async => repository),
      ],
    );
    addTearDown(() => cleanup(tester, container, repository));
    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const LegadoApp()),
    );
    await waitFor(tester, () => find.text('Route PDF').evaluate().isNotEmpty);
    expect(find.textContaining('Page 1 of 2'), findsOneWidget);
    await tester.tap(find.text('Route PDF'));
    await waitFor(
      tester,
      () => find.byType(PdfReaderPage).evaluate().isNotEmpty,
    );
    await waitFor(tester, () => find.text('Page 1 of 2').evaluate().isNotEmpty);
    expect(find.byType(PdfViewer), findsOneWidget);
    await tester.tap(find.byTooltip('Next page'));
    await waitFor(tester, () => find.text('Page 2 of 2').evaluate().isNotEmpty);
    final restored = await databaseCall(
      tester,
      () => repository.book(book.url),
    );
    expect(restored!.chapterIndex, 1);
    expect(tester.takeException(), isNull);
  });
}
