import 'dart:convert';
import 'dart:io';

import 'package:enough_convert/gbk.dart';
import 'package:source_engine/source_engine.dart';
import 'package:test/test.dart';

import 'engine_test.dart' show NoScripts;

void main() {
  test('fixed POST search with body template, explicit headers and full content chain', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((req) async {
      if (req.uri.path == '/search') {
        expect(req.method, 'POST');
        expect(req.headers.value('x-stage'), 'yes');
        expect(req.headers.value('x-global'), null);
        expect(req.uri.queryParameters['q'], '中 文');
        expect(await utf8.decoder.bind(req).join(), 'q=中 文');
        req.response.write(
          '<article><h2>A</h2><a href="/chapter">read</a></article>',
        );
      } else {
        expect(req.method, 'GET');
        expect(req.headers.value('x-global'), 'yes');
        req.response.write('<p>正文</p>');
      }
      await req.response.close();
    });
    final source = SourceDefinition(
      id: 'post',
      name: 'POST',
      baseUrl: Uri.parse('http://127.0.0.1:${server.port}'),
      headers: {'x-global': 'yes'},
      stages: {
        'search': SourceStage(
          url: '/search?q={{key}}',
          method: 'POST',
          body: 'q={{key}}',
          headers: {
            'x-stage': 'yes',
            'Content-Type': 'text/plain; charset=utf-8',
          },
          list: '@css:article',
          fields: {'name': '@css:h2@text', 'chapterUrl': '@css:a@href'},
        ),
        'content': SourceStage(
          url: '{{chapterUrl}}',
          fields: {'content': '@css:p@text'},
        ),
      },
    );
    final engine = SourceEngine(runtime: NoScripts());
    try {
      final books = await engine.execute(
        source,
        'search',
        input: {'key': '中 文'},
      );
      expect(books.single['name'], 'A');
      expect(await engine.execute(source, 'content', input: books.single), [
        {'content': '正文'},
      ]);
      expect(
        SourceDefinition.fromJson(source.toJson()).toJson(),
        source.toJson(),
      );
    } finally {
      await engine.close();
      await server.close(force: true);
    }
  });
  test(
    'raw body charset encoding and explicit response charset override',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      server.listen((req) async {
        final bytes = await req.fold<List<int>>([], (a, b) => a..addAll(b));
        expect(bytes, gbk.encode('中文'));
        req.response.headers.set('content-type', 'text/plain; charset=utf-8');
        req.response.add(gbk.encode('正文'));
        await req.response.close();
      });
      final client = NetworkClient();
      try {
        expect(
          (await client.request(
            Uri.parse('http://127.0.0.1:${server.port}/'),
            method: 'POST',
            body: '中文',
            charset: 'gbk',
          )).body,
          '正文',
        );
      } finally {
        client.close();
        await server.close(force: true);
      }
    },
  );
  test('HTML meta charset decoding and UTF8 BOM', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((req) async {
      req.response.headers.set('content-type', 'text/html');
      req.response.add(
        req.uri.path == '/meta'
            ? [...ascii.encode('<meta charset="gbk">'), ...gbk.encode('中文')]
            : [0xef, 0xbb, 0xbf, ...utf8.encode('中文')],
      );
      await req.response.close();
    });
    final client = NetworkClient();
    final base = 'http://127.0.0.1:${server.port}';
    try {
      expect(
        (await client.request(Uri.parse('$base/meta'))).body,
        '<meta charset="gbk">中文',
      );
      expect((await client.request(Uri.parse('$base/bom'))).body, '中文');
    } finally {
      client.close();
      await server.close(force: true);
    }
  });
}
