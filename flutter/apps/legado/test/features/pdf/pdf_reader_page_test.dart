import 'dart:io';

import 'package:app_core/app_core.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/features/pdf/pdf_import_page.dart';
import 'package:legado_app/features/pdf/pdf_importer.dart';
import 'package:legado_app/features/pdf/pdf_reader_page.dart';
import 'package:legado_app/l10n/generated/app_localizations.dart';
import 'package:material_ui/material_ui.dart';
import 'package:pdfrx/pdfrx.dart';

import '../sources/source_test_repository.dart';
import 'pdf_fixture.dart';
import 'pdf_native_test_support.dart';

class PdfWidgetRepository extends SourceTestRepository
    implements LocalBookRepository {
  final assets = <String, List<int>>{};
  @override
  Future<List<int>?> localAsset(String key) async => assets[key];
  @override
  Future<void> importLocalBook(
    LegacyBookRecord book,
    List<ReaderChapter> chapters,
    Map<String, String> contentsByChapterUrl, {
    Map<String, List<int>> assetsByKey = const {},
  }) async {
    assets.addAll(assetsByKey);
    await putBook(book);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late Directory cache;
  setUpAll(() async {
    cache = await Directory.systemTemp.createTemp('legado-pdf-widgets-');
    configurePdfiumForFlutterTest();
    Pdfrx.cacheDirectoryPath = cache.path;
    await pdfrxFlutterInitialize();
  });
  tearDownAll(() async => cache.delete(recursive: true));

  Widget app(Widget page, {Locale locale = const Locale('en')}) => MaterialApp(
    key: ValueKey(locale.languageCode),
    locale: locale,
    supportedLocales: AppLocalizations.supportedLocales,
    localizationsDelegates: [
      AppLocalizations.delegate,
      ...GlobalMaterialLocalizations.delegates,
    ],
    home: page,
  );

  Future<void> until(WidgetTester tester, bool Function() condition) async {
    for (var index = 0; index < 150; index++) {
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 20)),
      );
      await tester.pump(const Duration(milliseconds: 30));
      if (condition()) return;
    }
    throw StateError(
      'Native PDF widget did not settle. Texts: ${tester.widgetList<Text>(find.byType(Text)).map((text) => text.data).join('|')}',
    );
  }

  testWidgets(
    'real PDF viewer renders, navigates and persists the selected page',
    (tester) async {
      final repository = PdfWidgetRepository();
      late LegacyBookRecord book;
      await tester.runAsync(() async {
        book = await PdfImporter.importBytes(
          repository,
          name: 'Own fixture.pdf',
          bytes: createPdfFixture(),
        );
      });
      final container = ProviderContainer();
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: app(PdfReaderPage(repository: repository, bookUrl: book.url)),
        ),
      );
      await until(tester, () => find.byType(PdfViewer).evaluate().isNotEmpty);
      await until(tester, () => find.text('Page 1 of 2').evaluate().isNotEmpty);
      // Let native page-image rendering complete before navigating.
      for (var index = 0; index < 12; index++) {
        await tester.runAsync(
          () => Future<void>.delayed(const Duration(milliseconds: 20)),
        );
        await tester.pump(const Duration(milliseconds: 30));
      }
      expect(tester.takeException(), isNull);
      await tester.tap(find.byTooltip('Next page'));
      await until(
        tester,
        () => repository.bookRows[book.url]?.chapterIndex == 1,
      );
      await until(tester, () => find.text('Page 2 of 2').evaluate().isNotEmpty);
      expect(find.text('Page 2 of 2'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
      container.dispose();
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 100)),
      );
      await repository.close();
    },
  );

  testWidgets('missing document and import guidance are localized', (
    tester,
  ) async {
    final repository = PdfWidgetRepository();
    final container = ProviderContainer();
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: app(
          PdfReaderPage(repository: repository, bookUrl: 'local:pdf:missing'),
          locale: const Locale('zh'),
        ),
      ),
    );
    await until(
      tester,
      () => find.text('PDF 缺失、损坏或格式不受支持。').evaluate().isNotEmpty,
    );
    await tester.pumpWidget(
      ProviderScope(
        child: app(PdfImportPage(repository: repository, onImported: (_) {})),
      ),
    );
    await tester.pump();
    expect(find.text('Choose PDF'), findsOneWidget);
    expect(find.textContaining('32 MiB'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
    container.dispose();
    await repository.close();
  });
}
