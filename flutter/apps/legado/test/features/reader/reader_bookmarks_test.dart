import 'dart:convert';
import 'dart:io';

import 'package:app_core/app_core.dart';
import 'package:app_storage/app_storage.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:legado_app/features/reader/local_text_importer.dart';
import 'package:legado_app/features/reader/reader_bookmarks.dart';
import 'package:legado_app/features/reader/reader_page.dart';
import 'package:legado_app/l10n/generated/app_localizations.dart';

import 'reader_test.dart' show MemoryLibrary, app;

final class LegacyMemoryLibrary extends MemoryLibrary
    implements LegacyBackupFileReader {
  String? legacy;
  @override
  Future<String?> backupFile(String name) async =>
      name == 'bookmark.json' ? legacy : null;
  @override
  Future<Map<String, String>> backupFiles() async =>
      throw StateError('must not export all cached content');
}

void main() {
  test(
    'Drift reopen persists exact chapter identity, UTF16 offset and deletes',
    () async {
      final dir = await Directory.systemTemp.createTemp('legado-bookmarks-');
      final path = '${dir.path}/library.sqlite';
      var repository = await SqliteLibraryRepository.open(path);
      try {
        final book = await LocalTextImporter.importText(
          repository,
          name: '本地书',
          text: '第一章 开始\n正文😀尾部\n第二章 继续\n其他内容',
        );
        final chapter = (await repository.chapters(book.url)).first;
        final text = (await repository.content(book.url, chapter.url))!;
        final offset = text.indexOf('尾部');
        await ReaderBookmarks(repository)
            .add(book, chapter, offset, text, note: '跨平台备注');
        await repository.close();
        repository = await SqliteLibraryRepository.open(path);
        final rows = await ReaderBookmarks(repository).list(book);
        expect(rows.single.position, offset);
        expect(rows.single.note, '跨平台备注');
        expect(rows.single.toJson()['chapterUrl'], chapter.url);
        expect(
          rows.single.chapterIn([
            ReaderChapter(url: 'different', title: chapter.title, index: 0),
          ]),
          isNull,
        );
        expect(
          rows.single.chapterIn([
            ReaderChapter(url: chapter.url, title: '改名', index: 8),
          ]),
          0,
        );
        await ReaderBookmarks(repository).remove(rows.single);
        await repository.close();
        repository = await SqliteLibraryRepository.open(path);
        expect(await ReaderBookmarks(repository).list(book), isEmpty);
      } finally {
        await repository.close();
        await dir.delete(recursive: true);
      }
    },
  );

  test(
    'legacy fields and envelope unknown fields survive matching and deletion',
    () async {
      final library = LegacyMemoryLibrary();
      final book = await LocalTextImporter.importText(
        library,
        name: '旧书',
        text: '第一章 开始\n正文😀',
      );
      final chapter = (await library.chapters(book.url)).first;
      final raw = {
        'time': 42,
        'bookName': book.name,
        'bookAuthor': book.author,
        'chapterIndex': 0,
        'chapterPos': 3,
        'chapterName': chapter.title,
        'bookText': '原文',
        'content': '旧备注',
        'future': {
          'nested': [1, null, '保留'],
        },
      };
      library.legacy = jsonEncode([raw]);
      final original = library.legacy;
      await library.setSetting(
        ReaderBookmarks.settingKey,
        jsonEncode({
          'version': 1,
          'items': [],
          'deletedLegacyIds': [],
          'unknownEnvelope': {'data': true},
        }),
      );
      final store = ReaderBookmarks(library);
      final rows = await store.list(book);
      expect(rows.single.toJson(), raw);
      expect(rows.single.chapterIn([chapter]), 0);
      expect(
        rows.single.chapterIn([
          ReaderChapter(url: 'other', title: '不匹配章节', index: 0),
        ]),
        isNull,
      );
      await store.remove(rows.single);
      expect(await ReaderBookmarks(library).list(book), isEmpty);
      expect(library.legacy, original);
      expect(
        jsonDecode(
          (await library.setting(ReaderBookmarks.settingKey))!,
        )['unknownEnvelope'],
        {'data': true},
      );
    },
  );

  test(
    'ambiguous legacy name/author never jumps into another source book',
    () async {
      final library = LegacyMemoryLibrary();
      final a = LegacyBookRecord({
        'bookUrl': 'source-a',
        'name': '同名',
        'author': '作者',
      });
      final b = LegacyBookRecord({
        'bookUrl': 'source-b',
        'name': '同名',
        'author': '作者',
      });
      await library.putBook(a);
      await library.putBook(b);
      library.legacy = jsonEncode([
        {
          'time': 1,
          'bookName': '同名',
          'bookAuthor': '作者',
          'chapterIndex': 0,
          'chapterPos': 0,
          'chapterName': '章节',
        },
      ]);
      final rows = await ReaderBookmarks(library).list(a);
      expect(rows.single.ambiguous, true);
      expect(
        rows.single.chapterIn([
          const ReaderChapter(url: 'a', title: '章节', index: 0),
        ]),
        isNull,
      );
    },
  );

  test('UTF16 mid-surrogate offsets normalize and concurrent mutations preserve both rows', () async {
    final library = MemoryLibrary();
    final book = LegacyBookRecord({'bookUrl': 'preview', 'name': '预览'});
    const chapter = ReaderChapter(url: 'chapter', title: '章节', index: 0);
    expect(ReaderBookmarks.safeOffset('a😀b', 2), 1);
    await Future.wait([
      ReaderBookmarks(library).add(book, chapter, 2, 'a😀b'),
      ReaderBookmarks(library).add(book, chapter, 3, 'a😀b'),
    ]);
    final rows = await ReaderBookmarks(library).list(book);
    expect(rows.map((r) => r.position), [1, 3]);
    expect(library.records, isEmpty);
    final data = jsonDecode(
      (await library.setting(ReaderBookmarks.settingKey))!,
    ) as Map<String, dynamic>;
    (data['items'] as List).first['future'] = {'keep': null};
    await library.setSetting(ReaderBookmarks.settingKey, jsonEncode(data));
    await ReaderBookmarks(library).remove(rows.last);
    expect(
      (await ReaderBookmarks(library).list(book)).single.toJson()['future'],
      {'keep': null},
    );
  });

  test('unknown bookmark document version rejects mutation without replacement', () async {
    final library = MemoryLibrary();
    final book = LegacyBookRecord({'bookUrl': 'book', 'name': '书'});
    const original =
        '{"version":2,"items":[],"deletedLegacyIds":[],"future":{"preserve":true}}';
    await library.setSetting(ReaderBookmarks.settingKey, original);
    await expectLater(
      ReaderBookmarks(library).add(
        book,
        const ReaderChapter(url: 'chapter', title: '章', index: 0),
        0,
        '正文',
      ),
      throwsFormatException,
    );
    expect(await library.setting(ReaderBookmarks.settingKey), original);
  });

  test(
    'adding the same position updates its note and preserves opaque fields',
    () async {
      final library = MemoryLibrary();
      final book = LegacyBookRecord({'bookUrl': 'book', 'name': '书'});
      const chapter = ReaderChapter(url: 'chapter', title: '章', index: 0);
      await ReaderBookmarks(library).add(book, chapter, 0, '正文', note: '初始');
      final document = jsonDecode(
        (await library.setting(ReaderBookmarks.settingKey))!,
      );
      document['items'][0]['future'] = [null, '保留'];
      await library.setSetting(
        ReaderBookmarks.settingKey,
        jsonEncode(document),
      );
      await ReaderBookmarks(library).add(book, chapter, 0, '正文', note: '更新');
      final rows = await ReaderBookmarks(library).list(book);
      expect(rows.length, 1);
      expect(rows.single.note, '更新');
      expect(rows.single.toJson()['future'], [null, '保留']);
    },
  );

  testWidgets('reader adds, reopens, jumps and deletes a persisted bookmark', (
    tester,
  ) async {
    final library = MemoryLibrary();
    final book = await LocalTextImporter.importText(
      library,
      name: '书签图书',
      text: '第一章 开始\n开篇正文\n第二章 继续\n后续正文',
    );
    await tester.pumpWidget(app(library, book.url));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('书签'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('添加当前位置'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '重要位置');
    await tester.tap(find.text('保存'));
    await tester.pumpAndSettle();
    expect(find.textContaining('重要位置'), findsOneWidget);
    await tester.tapAt(const Offset(10, 10));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('下一章'));
    await tester.pumpAndSettle();
    expect(find.textContaining('后续正文'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    await tester.pumpWidget(app(library, book.url));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('书签'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('第一章 开始'));
    await tester.pumpAndSettle();
    expect(find.textContaining('开篇正文'), findsOneWidget);
    await tester.tap(find.byTooltip('书签'));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('删除书签'));
    await tester.pumpAndSettle();
    expect(find.text('暂无书签'), findsOneWidget);
    expect(await ReaderBookmarks(library).list(book), isEmpty);
  });

  testWidgets(
    'preview bookmarks persist without adding preview book or progress',
    (tester) async {
      final library = MemoryLibrary();
      final book = LegacyBookRecord({
        'bookUrl': 'opaque-preview',
        'name': '预览图书',
      });
      const chapter = ReaderChapter(url: 'chapter', title: '预览章节', index: 0);
      await library.putContent(book.url, chapter.url, '预览正文😀');
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
              previewChapters: const [chapter],
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('书签'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('添加当前位置'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('保存'));
      await tester.pumpAndSettle();
      expect((await ReaderBookmarks(library).list(book)).length, 1);
      expect(library.records, isEmpty);
      expect(library.progress, isEmpty);
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
      expect((await ReaderBookmarks(library).list(book)).length, 1);
    },
  );
}
