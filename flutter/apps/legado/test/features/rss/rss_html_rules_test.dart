import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/features/rss/rss_html_rules.dart';
import 'package:legado_app/features/rss/rss_models.dart';
import 'package:legado_app/features/rss/rss_service.dart';

void main() {
  test('legacy HTML source follows two pages and extracts safe template gallery with request headers', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final paths = <String>[];
    server.listen((request) async {
      paths.add(request.uri.path);
      expect(
        request.headers.value('referer'),
        'http://127.0.0.1:${server.port}${request.uri.path}',
      );
      request.response.headers.contentType = ContentType.html;
      request.response.write(switch (request.uri.path) {
        '/Articles' => '<div class="index-group-content"><div class="index-imgs"><a href="/article/1"><img data-original="/thumb.jpg"/><span class="index-imgcontent-title">One</span></a></div></div><a aria-label="Next" href="/page2">Next</a>',
        '/page2' => '<div class="index-group-content"><div class="index-imgs"><a href="/article/2"><span class="index-imgcontent-title">Two</span></a></div></div>',
        '/article/1' =>
          '<h1>Article</h1><meta name="description" content="Intro"/><section class="gallery"><img src="../images/one.jpg"/><img data-original="//127.0.0.1:${server.port}/images/two.jpg"/><img src="javascript:bad()"/></section>',
        _ => '',
      });
      await request.response.close();
    });
    final dio = Dio();
    final source = RssSource.fromJson({
      'sourceUrl': 'http://127.0.0.1:${server.port}/',
      'sourceName': 'HTML fixture',
      'header': '{"referer":"{{baseUrl}}"}',
      'sortUrl': 'Latest::/Articles\nAnother::/page2',
      'ruleArticles': '.index-group-content > .index-imgs',
      'ruleLink': 'a.0@href',
      'ruleTitle': '.index-imgcontent-title@text',
      'ruleImage': 'img@data-original',
      'rulePubDate': 'ownText##\\?\\..*',
      'ruleNextPage': '[aria-label="Next"]@href',
      'ruleContent': '<div>{{@@h1@all}}<p>{{@@[name="description"]@content}}</p>{{@@.gallery@all}}</div>',
    });
    try {
      final client = RssFeedClient(dio);
      final articles = await client.fetch(source);
      expect(articles.map((e) => e.title), ['One', 'Two']);
      expect(articles.first.original['rssImages'], [
        'http://127.0.0.1:${server.port}/thumb.jpg',
      ]);
      final detail = await client.fetchArticle(source, articles.first);
      expect(detail.content, contains('Intro'));
      expect(detail.original['rssDetailPending'], false);
      expect(detail.original['rssImages'], [
        'http://127.0.0.1:${server.port}/images/one.jpg',
        'http://127.0.0.1:${server.port}/images/two.jpg',
      ]);
      expect(paths, ['/Articles', '/page2', '/article/1']);
    } finally {
      dio.close(force: true);
      await server.close(force: true);
    }
  });

  test('script template and unknown expressions fail explicitly instead of returning fake content', () async {
    final rules = RssHtmlRules();
    const article = RssArticle(
      id: 'x',
      title: 'x',
      link: 'https://example.org/a',
      content: '',
    );
    final source = RssSource.fromJson({
      'sourceUrl': 'https://example.org',
      'ruleContent': '<script>data="{{@@#imgData@value}}";imgs=data.split("|");atob(imgs[0]);</script>',
    });
    await expectLater(
      rules.content(
        source,
        article,
        '<input id="imgData" value="a"/>',
        Uri.parse('https://example.org/a'),
      ),
      throwsA(
        isA<FormatException>().having(
          (e) => e.message,
          'code',
          'rssScriptCapability',
        ),
      ),
    );
    await expectLater(rules.evaluate('@js:evil()', ''), throwsFormatException);
    expect(
      () => rules.categoryUrl(source, '/search?wd={{String(source.variable)}}'),
      throwsFormatException,
    );
    expect(
      rules.categoryUrl(source, '/list/{{page}}', page: 3),
      'https://example.org/list/3',
    );
  });

  test('cyclic next-page links produce an actionable error', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((request) async {
      request.response.write(
        '<div class="item"><a href="/book">Book</a></div><a class="next" href="/">next</a>',
      );
      await request.response.close();
    });
    final dio = Dio();
    try {
      final source = RssSource.fromJson({
        'sourceUrl': 'http://127.0.0.1:${server.port}/',
        'ruleArticles': '.item',
        'ruleLink': 'a@href',
        'ruleTitle': 'a@text',
        'ruleNextPage': '.next@href',
      });
      await expectLater(
        RssFeedClient(dio).fetch(source),
        throwsA(
          isA<FormatException>().having(
            (e) => e.message,
            'code',
            'rssPaginationCycle',
          ),
        ),
      );
    } finally {
      dio.close(force: true);
      await server.close(force: true);
    }
  });
}
