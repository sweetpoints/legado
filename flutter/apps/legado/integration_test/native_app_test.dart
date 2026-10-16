import 'dart:convert';
import 'dart:io';

import 'package:app_storage/app_storage.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:legado_app/app/providers.dart';
import 'package:legado_app/app/router.dart';
import 'package:legado_app/features/reader/local_text_importer.dart';
import 'package:legado_app/main.dart';
import 'package:legado_app/services/book_source_service.dart';
import 'package:material_ui/material_ui.dart';

Future<void> waitFor(WidgetTester tester, bool Function() ready) async {
  for (var attempt = 0; attempt < 160; attempt++) {
    await tester.pump(const Duration(milliseconds: 50));
    if (ready()) {
      // Route contents exist before their slide animation becomes hittable.
      // Wait for the native transition and confirm the state stayed ready.
      await tester.pump(const Duration(milliseconds: 400));
      if (ready()) return;
    }
    await Future<void>.delayed(const Duration(milliseconds: 20));
  }
  expect(
    ready(),
    isTrue,
    reason: 'Native application did not reach expected state',
  );
}

Finder textContent(String text) => find.byWidgetPredicate(
  (widget) =>
      widget is SelectableText && (widget.data?.contains(text) ?? false),
);

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  WidgetController.hitTestWarningShouldBeFatal = true;

  testWidgets(
    'native file-backed Drift closes and reopens book and UTF-16 progress',
    (tester) async {
      expect(
        Platform.isIOS ||
            Platform.isMacOS ||
            Platform.isLinux ||
            Platform.isWindows,
        isTrue,
        reason: 'Run on the actual native application, not flutter_tester',
      );
      final directory = await Directory.systemTemp.createTemp(
        'legado-native-drift-',
      );
      final path = '${directory.path}/library.sqlite';
      var repository = await SqliteLibraryRepository.open(path);
      try {
        final book = await LocalTextImporter.importText(
          repository,
          name: 'Native durable book',
          text: '第一章 文件\n第一段😀正文\n第二章 恢复\n第二段正文\n',
        );
        final chapters = await repository.chapters(book.url);
        expect(chapters, hasLength(2));
        await repository.updateProgress(book.url, 1, 8);
        await repository.setSetting('app.locale', 'zh');
        await repository.close();
        expect(await File(path).length(), greaterThan(0));
        repository = await SqliteLibraryRepository.open(path);
        final restored = (await repository.book(book.url))!;
        expect(restored.chapterIndex, 1);
        expect(restored.characterOffset, 8);
        expect(await repository.setting('app.locale'), 'zh');
        expect(
          await repository.content(book.url, chapters[1].url),
          '第二章 恢复\n第二段正文\n',
        );
      } finally {
        await repository.close();
        await directory.delete(recursive: true);
      }
    },
  );

  testWidgets(
    'native V8 book-source HTTP pipeline persists sessions in real Drift',
    (tester) async {
      final directory = await Directory.systemTemp.createTemp(
        'legado-native-source-',
      );
      final path = '${directory.path}/library.sqlite';
      var repository = await SqliteLibraryRepository.open(path);
      var service = BookSourceService(repository);
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      final base = 'http://${server.address.address}:${server.port}';
      final requests = <({String path, String? header, String? cookie})>[];
      server.listen((request) async {
        requests.add((
          path: request.uri.path,
          header: request.headers.value('x-native-source'),
          cookie: request.headers.value('cookie'),
        ));
        request.response.headers.contentType = ContentType.json;
        switch (request.uri.path) {
          case '/search':
            request.response.cookies.add(Cookie('nativeSession', 'kept'));
            request.response.write(
              jsonEncode([
                {'name': 'Native HTTP book', 'bookUrl': '$base/book'},
              ]),
            );
          case '/book':
            request.response.write(
              jsonEncode({
                'name': 'Native detail book',
                'bookUrl': '$base/book',
                'tocUrl': '$base/toc',
                'intro': 'Actual HTTP and actual V8',
              }),
            );
          case '/toc':
            request.response.write(
              jsonEncode([
                {'title': 'Native chapter one', 'url': '$base/chapter/1'},
                {'title': 'Native chapter two', 'url': '$base/chapter/2'},
              ]),
            );
          case '/chapter/1':
            request.response.headers.contentType = ContentType.text;
            request.response.write('Native network chapter one. 实际正文😀');
          default:
            request.response.statusCode = HttpStatus.notFound;
        }
        await request.response.close();
      });
      try {
        await service.importJson(
          jsonEncode({
            'bookSourceUrl': base,
            'bookSourceName': 'Native acceptance fixture',
            'enabledCookieJar': true,
            'header': jsonEncode({'X-Native-Source': 'actual-v8'}),
            'mainJs': r'''
function search(key, page) {
  const counter = String(Number(java.get('nativeCounter') || '0') + 1);
  java.put('nativeCounter', counter);
  return JSON.parse(java.ajax(baseUrl + '/search')).map(book => ({...book, author:counter}));
}
function getBookInfo(book) { return {...book, ...JSON.parse(java.ajax(baseUrl + '/book'))}; }
function getChapters(book) { return JSON.parse(java.ajax(book.tocUrl)); }
function getContent(chapter) { return java.ajax(chapter.url); }
''',
          }),
        );
        final first = (await service.search(base, 'native')).single;
        expect(first.author, '1');
        final preview = await service.loadDetail(first);
        expect(preview.book.name, 'Native detail book');
        expect(preview.chapters, hasLength(2));
        expect(await repository.books(), isEmpty);
        final content = await service.fetchChapter(preview, 0);
        expect(content, 'Native network chapter one. 实际正文😀');
        final added = await service.addToShelf(preview);
        await repository.updateProgress(added.url, 0, 12);
        await service.close();
        await repository.close();
        repository = await SqliteLibraryRepository.open(path);
        service = BookSourceService(repository);
        expect((await service.search(base, 'restored')).single.author, '2');
        expect((await repository.book(added.url))!.characterOffset, 12);
        expect(
          await repository.content(added.url, preview.chapters.first.url),
          content,
        );
        expect(requests.map((request) => request.path), [
          '/search',
          '/book',
          '/toc',
          '/chapter/1',
          '/search',
        ]);
        expect(
          requests.every((request) => request.header == 'actual-v8'),
          isTrue,
        );
        expect(requests.first.cookie, isNull);
        expect(
          requests
              .skip(1)
              .every(
                (request) =>
                    request.cookie?.contains('nativeSession=kept') ?? false,
              ),
          isTrue,
        );
      } finally {
        await service.close();
        await repository.close();
        await server.close(force: true);
        await directory.delete(recursive: true);
      }
    },
  );

  testWidgets('native app shelf reader and language survive durable reopen', (
    tester,
  ) async {
    final directory = await Directory.systemTemp.createTemp(
      'legado-native-ui-',
    );
    final path = '${directory.path}/library.sqlite';
    var repository = await SqliteLibraryRepository.open(path);
    ProviderContainer? container;
    try {
      final book = await LocalTextImporter.importText(
        repository,
        name: '原生验收书',
        text:
            '第一章 起点\n这里是第一段真实正文\n第二章 持久化\n'
            '${List.generate(120, (index) => '第$index行持久化正文😀。').join('\n')}',
      );
      expect(await repository.chapters(book.url), hasLength(2));
      await repository.setSetting('app.locale', 'zh');
      container = ProviderContainer(
        overrides: [
          persistentLibraryProvider.overrideWith((ref) async => repository),
        ],
      );
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: const LegadoApp(),
        ),
      );
      await waitFor(tester, () => find.text('原生验收书').evaluate().isNotEmpty);
      await tester.tap(find.text('原生验收书'));
      await waitFor(
        tester,
        () => textContent('这里是第一段真实正文').evaluate().isNotEmpty,
      );
      await tester.tap(find.byTooltip('下一章'));
      await waitFor(
        tester,
        () => textContent('第119行持久化正文').evaluate().isNotEmpty,
      );
      await tester.drag(
        find.byKey(const Key('reader-scroll')),
        const Offset(0, -700),
      );
      await tester.pump(const Duration(milliseconds: 700));
      final progress = (await repository.book(book.url))!;
      expect(progress.chapterIndex, 1);
      expect(progress.characterOffset, greaterThan(0));
      container.read(appRouterProvider).pop();
      await waitFor(tester, () => find.text('设置').evaluate().isNotEmpty);
      await tester.tap(find.text('设置').last);
      await waitFor(tester, () => find.text('语言').evaluate().isNotEmpty);
      await tester.tap(find.byType(DropdownButton<String>).first);
      await tester.pumpAndSettle();
      await tester.tap(find.text('English').last);
      await waitFor(tester, () => find.text('Language').evaluate().isNotEmpty);
      expect(await repository.setting('app.locale'), 'en');
      await tester.pumpWidget(const SizedBox.shrink());
      container.dispose();
      container = null;
      await repository.close();
      repository = await SqliteLibraryRepository.open(path);
      expect((await repository.book(book.url))!.chapterIndex, 1);
      expect(
        (await repository.book(book.url))!.characterOffset,
        progress.characterOffset,
      );
      expect(await repository.setting('app.locale'), 'en');
      container = ProviderContainer(
        overrides: [
          persistentLibraryProvider.overrideWith((ref) async => repository),
        ],
      );
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: const LegadoApp(),
        ),
      );
      await waitFor(tester, () => find.text('Bookshelf').evaluate().isNotEmpty);
      await tester.tap(find.text('原生验收书'));
      await waitFor(
        tester,
        () => textContent('第119行持久化正文').evaluate().isNotEmpty,
      );
      expect(find.byTooltip('Next chapter'), findsOneWidget);
      expect(tester.takeException(), isNull);
    } finally {
      await tester.pumpWidget(const SizedBox.shrink());
      container?.dispose();
      await repository.close();
      await directory.delete(recursive: true);
    }
  });
}
