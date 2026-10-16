import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:app_core/app_core.dart';
import 'package:source_engine/source_engine.dart';
import 'package:legado_app/services/book_source_service.dart';

import 'source_test_repository.dart';

void main() {
  test(
    'native org and URL WebView options remain explicit unsupported boundaries',
    () async {
      final repository = SourceTestRepository();
      final service = BookSourceService(
        repository,
        engineFactory: (_) =>
            throw StateError('Unsupported requests must not start a runtime'),
      );
      const id = 'https://fixture.invalid/';
      try {
        await service.importJson(
          jsonEncode({
            'bookSourceUrl': id,
            'bookSourceName': 'Native org boundary',
            'ruleSearch': {'bookList': '@js:org.jsoup.Jsoup.connect(baseUrl)'},
            'searchUrl': '/search',
          }),
        );
        await expectLater(
          service.search(id, 'query'),
          throwsA(
            isA<EngineException>().having(
              (error) => error.code,
              'code',
              'legacy_requires_migration',
            ),
          ),
        );
        final preview = BookPreview(
          const NetworkBook(
            sourceId: id,
            metadata: {
              'bookUrl': 'https://fixture.invalid/book',
              'name': 'Preview',
            },
          ),
          const [
            ReaderChapter(
              url: "https://fixture.invalid/chapter,{'webView':true}",
              title: 'Chapter',
              index: 0,
            ),
          ],
        );
        await expectLater(
          service.fetchChapter(preview, 0),
          throwsA(
            isA<EngineException>().having(
              (error) => error.code,
              'code',
              'legacy_request_requires_migration',
            ),
          ),
        );
        expect(repository.settings, isEmpty);
      } finally {
        await service.close();
        await repository.close();
      }
    },
  );
  test('original backup xs520 rules search detail three TOC pages and content through production V8', () async {
    final original = Map<String, Object?>.from(
      jsonDecode(
        File('test/features/sources/fixtures/xs520-source.json')
            .readAsStringSync(),
      ) as Map,
    );
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final base = 'http://${server.address.address}:${server.port}';
    final paths = <String>[];
    final cookies = <String?>[];
    final pages = <String, String>{
      '/search.php': '<div class="box"><div class="col-md-6"><a href="/book/"><h3>Fixture [玄幻]</h3></a><div class="book_other">Author</div></div></div>',
      '/book/': '<div class="info"><h1>Fixture</h1><ul><li><a>Author</a></li></ul></div><div id="intro_pc">简介：Fixture introduction</div><div class="page-link">1/3</div><ul class="book_list2"><li><a href="/chapter/1">Chapter one</a></li></ul>',
      '/book/index_2.html': '<ul class="book_list2"><li><a href="/chapter/2">Chapter two</a></li></ul>',
      '/book/index_3.html': '<ul class="book_list2"><li><a href="/chapter/3">Chapter three</a></li></ul>',
      '/chapter/1':
          '<h1>Chapter one</h1><div class="font_max">Start 第(1/3)页 end</div>',
    };
    server.listen((request) async {
      paths.add(request.uri.path);
      cookies.add(request.headers.value('cookie'));
      request.response.cookies.add(Cookie('session', 'ignored'));
      request.response.headers.contentType = ContentType.html;
      final html = pages[request.uri.path];
      if (html == null) request.response.statusCode = 404;
      request.response.write(html ?? 'Missing fixture');
      await request.response.close();
    });
    final repository = SourceTestRepository();
    final service = BookSourceService(repository);
    try {
      // Only transport origin is replaced. All original legacy rules stay intact.
      final local = {...original, 'bookSourceUrl': base};
      await service.importJson(jsonEncode(local));
      final found = await service.search(base, 'Fixture');
      expect(found.single.name, 'Fixture ');
      final preview = await service.loadDetail(found.single);
      expect(paths, [
        '/search.php',
        '/book/',
        '/book/',
        '/book/index_2.html',
        '/book/index_3.html',
      ]);
      expect(preview.book.name, 'Fixture');
      expect(preview.book.metadata['intro'], 'Fixture introduction');
      expect(preview.chapters.map((chapter) => chapter.title), [
        'Chapter one',
        'Chapter two',
        'Chapter three',
      ]);
      expect(preview.chapters.map((chapter) => chapter.url), [
        '$base/chapter/1',
        '$base/chapter/2',
        '$base/chapter/3',
      ]);
      expect(await service.fetchChapter(preview, 0), 'Start  end');
      expect(paths, [
        '/search.php',
        '/book/',
        '/book/',
        '/book/index_2.html',
        '/book/index_3.html',
        '/chapter/1',
      ]);
      expect(cookies, everyElement(isNull));
      expect(await repository.books(), isEmpty);
      expect(await repository.chapters(preview.book.url), isEmpty);
      final stored = (await repository.sources()).single;
      for (final key in [
        'ruleSearch',
        'ruleBookInfo',
        'ruleToc',
        'ruleContent',
      ]) {
        expect(stored[key], original[key]);
      }
    } finally {
      await service.close();
      await repository.close();
      await server.close(force: true);
    }
  });
}
