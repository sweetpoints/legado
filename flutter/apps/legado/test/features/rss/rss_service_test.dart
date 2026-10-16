import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:app_core/app_core.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/features/rss/rss_models.dart';
import 'package:legado_app/features/rss/rss_service.dart';

class MemoryLibrary implements LibraryRepository {
  final settings = <String, String>{};
  @override
  Stream<void> get changes => const Stream.empty();
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
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

const rss =
    '''<?xml version="1.0"?><rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel><title>中文</title><item><guid>opaque-1</guid><title>标题 &amp; 内容</title><link>/articles/1</link><content:encoded><![CDATA[<p>Hello</p>]]></content:encoded><pubDate>Mon, 01 Jun 2026 10:00:00 GMT</pubDate></item></channel></rss>''';
void main() {
  test(
    'RSS2 CDATA, entities, opaque ids, relative links and Atom xml:base',
    () {
      final articles = parseFeed(rss, Uri.parse('https://example.org/feed'));
      expect(articles.single.id, 'opaque-1');
      expect(articles.single.title, '标题 & 内容');
      expect(articles.single.link, 'https://example.org/articles/1');
      expect(articles.single.content, '<p>Hello</p>');
      final atom = parseFeed(
        '''<feed xmlns="http://www.w3.org/2005/Atom" xml:base="/blog/"><entry xml:base="2026/"><id>tag:opaque</id><title>Atom</title><link rel="self" href="entry.xml"/><link href="article.html"/><summary>summary</summary></entry></feed>''',
        Uri.parse('https://example.org/feed'),
      );
      expect(atom.single.link, 'https://example.org/blog/2026/article.html');
      expect(atom.single.content, 'summary');
      expect(
        () => parseFeed(
          '<html>Not a feed</html>',
          Uri.parse('https://example.org'),
        ),
        throwsFormatException,
      );
      expect(
        () => parseFeed('<rss><bad>', Uri.parse('https://example.org')),
        throwsA(anything),
      );
    },
  );

  test(
    'batch import is atomic, preserves unknown source fields and legacy rules',
    () async {
      final repo = MemoryLibrary();
      final store = RssStore(repo);
      await store.importSources(
        '[{"sourceUrl":"https://example.org/feed","sourceName":"My feed","vendor":{"flag":true}}]',
      );
      final before = repo.settings[RssStore.storageKey];
      await expectLater(
        store.importSources('[{"sourceUrl":"https://valid.org"},null]'),
        throwsFormatException,
      );
      expect(repo.settings[RssStore.storageKey], before);
      await store.importSources(
        '{"sourceUrl":"https://example.org/feed","sourceName":"Updated"}',
      );
      final source = (await store.sources()).single;
      expect(source.name, 'Updated');
      expect(source.original['vendor'], {'flag': true});
      await store.importSources(
        '{"sourceUrl":"https://example.org/html","ruleArticles":".entry"}',
      );
      final legacy = (await store.sources()).last;
      expect(legacy.requiresRuleEngine, true);
      final dio = Dio();
      await expectLater(
        RssFeedClient(dio).fetch(legacy),
        throwsFormatException,
      );
      dio.close(force: true);
    },
  );

  test('refresh plus concurrent read/star updates survive reopen without field loss', () async {
    final repo = MemoryLibrary();
    repo.settings[RssStore.storageKey] = jsonEncode({
      'future': {'unknown': 42},
      'sources': [],
      'articles': {},
    });
    final store = RssStore(repo);
    const article = RssArticle(
      id: 'a',
      title: 'One',
      link: '',
      content: 'Offline',
      original: {'extension': 7},
    );
    await store.saveArticles('s', [article]);
    await Future.wait([
      store.mark('s', 'a', read: true),
      store.mark('s', 'a', starred: true),
    ]);
    await store.saveArticles('s', [article.copyWith(title: 'Updated')]);
    final reopened = RssStore(repo);
    final saved = (await reopened.articles('s')).single;
    expect(saved.read, true);
    expect(saved.starred, true);
    expect(saved.title, 'Updated');
    expect(saved.original['extension'], 7);
    expect((jsonDecode(repo.settings[RssStore.storageKey]!) as Map)['future'], {
      'unknown': 42,
    });
    await reopened.saveArticles('s', []);
    expect((await reopened.articles('s')).single.starred, true);
  });

  test('corrupt storage is reported and never overwritten', () async {
    final repo = MemoryLibrary()..settings[RssStore.storageKey] = '{broken';
    await expectLater(
      RssStore(repo).importSources('{"sourceUrl":"https://example.org"}'),
      throwsFormatException,
    );
    expect(repo.settings[RssStore.storageKey], '{broken');
  });

  test('Dio local fixture fetches XML and aborts a pending request', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final requested = Completer<void>();
    server.listen((request) async {
      if (request.uri.path == '/slow') {
        requested.complete();
        return;
      }
      if (request.uri.path == '/large') {
        request.response.add(List.filled(RssFeedClient.maxBytes + 1, 65));
        await request.response.close();
        return;
      }
      expect(request.headers.value('x-feed-test'), 'header');
      request.response.headers.contentType = ContentType(
        'application',
        'rss+xml',
        charset: 'utf-8',
      );
      request.response.write(rss);
      await request.response.close();
    });
    final dio = Dio(BaseOptions(connectTimeout: const Duration(seconds: 2)));
    try {
      final origin = 'http://127.0.0.1:${server.port}';
      final client = RssFeedClient(dio);
      final result = await client.fetch(
        RssSource(
          url: '$origin/feed',
          name: 'Fixture',
          original: {'header': '{"x-feed-test":"header"}'},
        ),
      );
      expect(result.single.title, '标题 & 内容');
      await expectLater(
        client.fetch(RssSource(url: '$origin/large', name: 'Large')),
        throwsFormatException,
      );
      final token = CancelToken();
      final slow = client.fetch(
        RssSource(url: '$origin/slow', name: 'Slow'),
        cancelToken: token,
      );
      final assertion = expectLater(
        slow,
        throwsA(
          isA<DioException>().having(
            (e) => e.type,
            'type',
            DioExceptionType.cancel,
          ),
        ),
      );
      await requested.future;
      token.cancel('test');
      await assertion;
    } finally {
      dio.close(force: true);
      await server.close(force: true);
    }
  });
}
