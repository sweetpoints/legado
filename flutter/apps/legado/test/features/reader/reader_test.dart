import 'dart:async';

import 'package:app_core/app_core.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/features/reader/local_text_importer.dart';
import 'package:legado_app/features/reader/reader_page.dart';
import 'package:legado_app/l10n/generated/app_localizations.dart';

class MemoryLibrary implements LibraryRepository {
  final records = <String, LegacyBookRecord>{};
  final toc = <String, List<ReaderChapter>>{};
  final texts = <String, String>{};
  final settings = <String, String>{};
  final progress = <({int chapter, int offset})>[];
  bool failContentWrite = false;
  Completer<String?>? delayedContent;
  @override
  Stream<void> get changes => const Stream.empty();
  @override
  Future<List<LegacyBookRecord>> books() async => records.values.toList();
  @override
  Future<LegacyBookRecord?> book(String url) async => records[url];
  @override
  Future<void> putBook(LegacyBookRecord book) async {
    records[book.url] = book;
  }

  @override
  Future<void> removeBook(String url) async {
    records.remove(url);
  }

  @override
  Future<void> updateProgress(
    String url,
    int chapterIndex,
    int characterOffset,
  ) async {
    progress.add((chapter: chapterIndex, offset: characterOffset));
    records[url] = LegacyBookRecord({
      ...records[url]!.toJson(),
      'durChapterIndex': chapterIndex,
      'durChapterPos': characterOffset,
    });
  }

  @override
  Future<List<ReaderChapter>> chapters(String url) async => toc[url] ?? [];
  @override
  Future<void> putChapters(String url, List<ReaderChapter> chapters) async {
    toc[url] = chapters;
  }

  @override
  Future<String?> content(String url, String chapter) async =>
      delayedContent == null ? texts[chapter] : delayedContent!.future;
  @override
  Future<void> putContent(String url, String chapter, String content) async {
    if (failContentWrite) throw StateError('disk full');
    texts[chapter] = content;
  }

  @override
  Future<String?> setting(String key) async => settings[key];
  @override
  Future<void> setSetting(String key, String? value) async {
    if (value == null) {
      settings.remove(key);
    } else {
      settings[key] = value;
    }
  }

  @override
  Future<List<Map<String, Object?>>> sources() async => [];
  @override
  Future<void> putSource(Map<String, Object?> source) async {}
  @override
  Future<void> removeSource(String id) async {}
  @override
  Future<Map<String, String>> backupFiles() async => {};
  @override
  Future<void> restoreBackup(LegacyBackupDocument document) async {}
  @override
  Future<void> close() async {}
}

Widget app(MemoryLibrary repository, String url) => ProviderScope(
  child: MaterialApp(
    locale: const Locale('zh'),
    localizationsDelegates: const [
      AppLocalizations.delegate,
      ...GlobalMaterialLocalizations.delegates,
    ],
    supportedLocales: AppLocalizations.supportedLocales,
    home: ReaderPage(repository: repository, bookUrl: url),
  ),
);

void main() {
  test(
    'TXT keeps CRLF, emoji UTF-16 positions and deterministic identity',
    () async {
      final library = MemoryLibrary();
      const text = '前言😀\r\n第一章 开始\r\n正文一\r\n第二章 继续\n正文二';
      final book = await LocalTextImporter.importText(
        library,
        name: '书名',
        text: text,
      );
      final chapters = await library.chapters(book.url);
      expect(chapters.map((c) => c.title), ['前言', '第一章 开始', '第二章 继续']);
      expect(chapters[1].url, '${book.url}#${text.indexOf('第一章')}');
      expect(chapters.map((c) => library.texts[c.url]).join(), text);
      await library.updateProgress(book.url, 1, 7);
      final duplicate = await LocalTextImporter.importText(
        library,
        name: '另一个文件名',
        text: text,
      );
      expect(duplicate.url, book.url);
      expect(duplicate.characterOffset, 7);
      expect(library.records.length, 1);
    },
  );

  test(
    'failed content write never exposes partially imported shelf book',
    () async {
      final library = MemoryLibrary()..failContentWrite = true;
      await expectLater(
        LocalTextImporter.importText(library, name: '书名', text: '正文'),
        throwsStateError,
      );
      expect(library.records, isEmpty);
    },
  );

  testWidgets('next chapter and catalogue persist chapter identity', (
    tester,
  ) async {
    final library = MemoryLibrary();
    final book = await LocalTextImporter.importText(
      library,
      name: '测试图书',
      text: '第一章 开始\n内容一\n第二章 继续\n内容二\n第三章 结束\n内容三',
    );
    await tester.pumpWidget(app(library, book.url));
    await tester.pumpAndSettle();
    expect(find.textContaining('内容一'), findsOneWidget);
    await tester.tap(find.byTooltip('下一章'));
    await tester.pumpAndSettle();
    expect(find.textContaining('内容二'), findsOneWidget);
    expect(library.records[book.url]!.chapterIndex, 1);
    await tester.tap(find.text('2/3 · 第二章 继续'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('第三章 结束'));
    await tester.pumpAndSettle();
    expect(find.textContaining('内容三'), findsOneWidget);
    expect(library.records[book.url]!.chapterIndex, 2);
  });

  testWidgets('scroll position survives recreation and window resizing', (
    tester,
  ) async {
    final library = MemoryLibrary();
    final text = List.generate(
      200,
      (i) => '第$i行：持续阅读的位置😀，不要用屏幕百分比保存。',
    ).join('\n');
    final book = await LocalTextImporter.importText(
      library,
      name: '长文',
      text: text,
    );
    await tester.pumpWidget(app(library, book.url));
    await tester.pumpAndSettle();
    await tester.drag(
      find.byKey(const Key('reader-scroll')),
      const Offset(0, -900),
    );
    await tester.pumpAndSettle();
    await tester.pump(const Duration(milliseconds: 400));
    final saved = library.records[book.url]!.characterOffset;
    expect(saved, greaterThan(0));
    await tester.pumpWidget(const SizedBox());
    await tester.pumpWidget(app(library, book.url));
    await tester.pumpAndSettle();
    final initialScroll = tester
        .state<ScrollableState>(
          find
              .descendant(
                of: find.byKey(const Key('reader-scroll')),
                matching: find.byType(Scrollable),
              )
              .first,
        )
        .position
        .pixels;
    expect(initialScroll, greaterThan(0));
    tester.view.physicalSize = const Size(600, 900);
    addTearDown(tester.view.resetPhysicalSize);
    await tester.pumpAndSettle();
    expect(library.records[book.url]!.characterOffset, saved);
    await tester.tap(find.byTooltip('阅读设置'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('跟随系统'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('深色').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('完成'));
    await tester.pumpAndSettle();
    expect(library.settings['reader.theme'], 'dark');
    expect(library.records[book.url]!.characterOffset, saved);
  });

  testWidgets('preview selected chapter never adds a book or writes progress', (
    tester,
  ) async {
    final library = MemoryLibrary();
    final book = await LocalTextImporter.importText(
      library,
      name: '未收藏预览',
      text: '第一章 开始\n内容一\n第二章 继续\n内容二',
    );
    final chapters = await library.chapters(book.url);
    library.records.clear();
    library.toc.clear();
    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          locale: const Locale('zh'),
          localizationsDelegates: const [
            AppLocalizations.delegate,
            ...GlobalMaterialLocalizations.delegates,
          ],
          supportedLocales: AppLocalizations.supportedLocales,
          home: ReaderPage(
            repository: library,
            bookUrl: book.url,
            previewBook: book,
            previewChapters: chapters,
            initialChapterIndex: 1,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.textContaining('内容二'), findsOneWidget);
    await tester.tap(find.byTooltip('上一章'));
    await tester.pumpAndSettle();
    expect(find.textContaining('内容一'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
    expect(library.records, isEmpty);
    expect(library.progress, isEmpty);
  });

  testWidgets(
    'explicit chapter resets offset and shelf metadata wins over preview',
    (tester) async {
      final library = MemoryLibrary();
      final book = await LocalTextImporter.importText(
        library,
        name: '收藏书籍',
        text: '第一章 开始\n内容一\n第二章 继续\n内容二',
      );
      await library.updateProgress(book.url, 0, 8);
      library.progress.clear();
      final preview = LegacyBookRecord({...book.toJson(), 'name': '预览元数据'});
      await tester.pumpWidget(
        ProviderScope(
          child: MaterialApp(
            locale: const Locale('zh'),
            localizationsDelegates: const [
              AppLocalizations.delegate,
              ...GlobalMaterialLocalizations.delegates,
            ],
            supportedLocales: AppLocalizations.supportedLocales,
            home: ReaderPage(
              repository: library,
              bookUrl: book.url,
              previewBook: preview,
              initialChapterIndex: 1,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('收藏书籍'), findsOneWidget);
      expect(find.textContaining('内容二'), findsOneWidget);
      await tester.tap(find.byTooltip('上一章'));
      await tester.pumpAndSettle();
      expect(library.progress.first, (chapter: 1, offset: 0));
      expect(library.records[book.url]!.name, '收藏书籍');
    },
  );

  testWidgets(
    'uncached chapter retries and late result never saves after disposal',
    (tester) async {
      final library = MemoryLibrary();
      final book = await LocalTextImporter.importText(
        library,
        name: '测试',
        text: '内容',
      );
      library.texts.clear();
      await tester.pumpWidget(app(library, book.url));
      await tester.pumpAndSettle();
      expect(find.textContaining('本章正文尚未缓存'), findsOneWidget);
      library.delayedContent = Completer<String?>();
      await tester.tap(find.text('重试'));
      await tester.pump();
      await tester.pumpWidget(const SizedBox());
      library.delayedContent!.complete('迟到的正文');
      await tester.pumpAndSettle();
      expect(library.progress, isEmpty);
      expect(tester.takeException(), isNull);
    },
  );
}
