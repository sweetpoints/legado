import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/services/book_source_service.dart';

import 'source_test_repository.dart';

void main() {
  test('original fengqing source discovers searches initializes DOM details and reads catalogue/content with V8', () async {
    final original = Map<String, Object?>.from(
      jsonDecode(
        File('test/features/sources/fixtures/fengqing-source.json')
            .readAsStringSync(),
      ) as Map,
    );
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final base = 'http://${server.address.address}:${server.port}';
    final paths = <String>[];
    final cookies = <String?>[];
    final userAgents = <String?>[];
    server.listen((request) async {
      paths.add(request.uri.path);
      cookies.add(request.headers.value('cookie'));
      userAgents.add(request.headers.value('user-agent'));
      request.response.cookies.add(Cookie('automatic', 'must-not-save'));
      request.response.headers.contentType = ContentType.html;
      final body = switch (request.uri.path) {
        '/bootstrap' =>
          '<script>\nurl[0]="$base";\nurl[1]="$base";\nurl[2]="$base";\n</script>',
        '/category.html' => '<table><tbody><tr><td><a href="book-1.html">Book one</a></td><td>[Genre]</td><td></td><td>Author</td><td>12</td><td>Serial</td></tr></tbody></table>',
        '/search.html' => '<div class="sousuojieguo"><ul><li><a href="book-1.html"><span class="biaoti">Book one</span></a><span class="zuozhe">Author</span></li></ul></div>',
        '/book-1.html' => '<div class="book_name"><a>Book one</a></div><div class="author">Author</div><div class="xinxi_content"><span>Genre 连载中 12字</span></div><span class="dt_r">2026-10-17</span><div class="new_zhangjie1"><div class="dt_l"><a>Latest</a></div><div class="dt_r">2026-10-17</div></div><div class="jianjieneirong">Introduction</div><div class="book_info_top_l"><img src="img-1.jpg"></div>',
        '/chapterList-1.html' => '<ul class="section_list"><li><a href="read-1.html">Chapter one</a></li><li><a href="read-2.html">Chapter two</a></li></ul>',
        '/read-1.html' => '<script>var url="chapter&v=fixture-token";</script>',
        '/_getcontent.php' => 'First chapter content',
        _ => null,
      };
      if (body == null) request.response.statusCode = 404;
      request.response.write(body ?? 'Unexpected fixture request');
      await request.response.close();
    });
    final repo = SourceTestRepository();
    var service = BookSourceService(repo);
    try {
      // Replace the bootstrap transport literal only. Identity, selectors, scripts,
      // templates and menu data are otherwise the user's original source.
      final source = {
        ...original,
        'bookSourceComment': (original['bookSourceComment'] as String)
            .replaceAll(
              'https://myaavideonet.neocities.org/aabook.html',
              '$base/bootstrap',
            ),
      };
      final id = BookSourceService.sourceId(source);
      await service.importJson(jsonEncode(source));
      final menu = await service.exploreMenu(id);
      expect(menu.length, greaterThan(20));
      expect(
        menu.every((item) => (item['url'] as String).startsWith(base)),
        true,
      );
      final explored = await service.explore(id, menu.first['url'] as String);
      expect(explored.single.name, 'Book one');
      expect(explored.single.url, '$base/book-1.html');
      final found = await service.search(id, 'Book');
      expect(found.single.name, 'Book one');
      final preview = await service.loadDetail(found.single);
      expect(preview.book.name, 'Book one');
      expect(preview.book.author, 'Author');
      expect(preview.book.metadata['coverUrl'], '$base/img-1.jpg');
      expect(preview.chapters.map((chapter) => chapter.url), [
        '$base/read-1.html',
        '$base/read-2.html',
      ]);
      expect(preview.chapters.map((chapter) => chapter.title), [
        'Chapter one',
        'Chapter two',
      ]);
      expect(await service.fetchChapter(preview, 0), 'First chapter content');
      expect(await repo.books(), isEmpty);
      expect(await repo.chapters(preview.book.url), isEmpty);
      await service.close();
      service = BookSourceService(repo);
      final reopened = await service.loadDetail(found.single);
      expect(reopened.chapters.length, 2);
      final state = jsonDecode(repo.settings.values.single) as Map;
      expect((state['sourceState'] as Map)['variable'], '设置源站[1-3]：1');
      expect(cookies, everyElement(isNull));
      expect((state['engine'] as Map)['cookies'], isEmpty);
      final configuredHeaders = jsonDecode(original['header'] as String) as Map;
      expect(userAgents, everyElement(configuredHeaders['User-Agent']));
      for (final key in [
        'ruleSearch',
        'ruleExplore',
        'ruleBookInfo',
        'ruleToc',
        'ruleContent',
        'exploreUrl',
        'searchUrl',
      ]) {
        expect((await repo.sources()).single[key], original[key]);
      }
      expect(
        paths,
        containsAll([
          '/category.html',
          '/search.html',
          '/book-1.html',
          '/chapterList-1.html',
          '/read-1.html',
          '/_getcontent.php',
        ]),
      );
    } finally {
      await service.close();
      await repo.close();
      await server.close(force: true);
    }
  });
}
