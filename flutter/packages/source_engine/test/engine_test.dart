import 'dart:io';

import 'package:source_engine/source_engine.dart';
import 'package:test/test.dart';

class NoScripts implements ScriptRuntime {
  @override
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  }) => throw UnsupportedError('No scripts in extraction fixture');
  @override
  Future<void> close() async {}
}

class NoHost implements ScriptHost {
  @override
  Future<Object?> call(String method, List<Object?> arguments) =>
      throw UnsupportedError(method);
}

class BrowserScripts implements ScriptRuntime {
  @override
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  }) async {
    await context.host.call('browser.open', ['/login']);
    final body = await context.host.call('net.get', ['/chapter']);
    return [
      {'content': body},
    ];
  }

  @override
  Future<void> close() async {}
}

class BrowserHost implements ScriptHost {
  BrowserHost(this.url);
  final String url;
  @override
  Future<Object?> call(String method, List<Object?> arguments) async => {
    'url': url,
    'body': 'logged in',
    'cookie': 'session=ok',
  };
}

void main() {
  final context = ScriptContext(host: NoHost());
  test('source rejects unknown version and round-trips', () {
    expect(
      () => SourceDefinition(
        id: 'a',
        name: 'A',
        baseUrl: Uri.parse('https://example.org'),
        schemaVersion: 2,
      ),
      throwsA(isA<EngineException>()),
    );
    final source = SourceDefinition(
      id: 'a',
      name: 'A',
      baseUrl: Uri.parse('https://example.org'),
      stages: {
        'search': SourceStage(
          url: '/q',
          list: '@css:.book',
          fields: {'name': '@css:h2@text'},
        ),
      },
    );
    expect(
      SourceDefinition.fromJson(source.toJson()).toJson(),
      source.toJson(),
    );
  });
  test('CSS nodes, fallback, concatenation and captures', () async {
    final rules = RuleEvaluator(NoScripts());
    const input =
        '<div class="book"><h2> Book 123 </h2><a href="/book/1">open</a></div>';
    final nodes = await rules.evaluate('@css:.book', input, context);
    expect(
      await rules.evaluate('@css:h2@text##[0-9]+##X', nodes.first, context),
      [' Book X '],
    );
    expect(
      await rules.evaluate('@css:.missing@text||@css:a@href', input, context),
      ['/book/1'],
    );
    expect(await rules.evaluate('@css:a@href&&@css:a@text', input, context), [
      '/book/1',
      'open',
    ]);
    expect(await rules.evaluate(r'@regex:Book (\d+)', input, context), ['123']);
  });
  test('JSONPath and XPath', () async {
    final rules = RuleEvaluator(NoScripts());
    expect(
      await rules.evaluate(
        r'@json:$.items[*].name',
        '{"items":[{"name":"A"},{"name":"B"}]}',
        context,
      ),
      ['A', 'B'],
    );
    expect(await rules.evaluate('@xpath://h2/text()', '<h2>A</h2>', context), [
      'A',
    ]);
  });
  test('real HTTP pipeline cookies, redirects and relative URLs', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((req) async {
      if (req.uri.path == '/search') {
        req.response.cookies.add(Cookie('session', 'yes')..path = '/');
        req.response.write(
          '<article><h2>A</h2><a href="/book">detail</a></article>',
        );
      } else if (req.uri.path == '/redirect') {
        req.response.statusCode = 302;
        req.response.headers.set('location', '/book');
      } else {
        expect(req.cookies.any((c) => c.name == 'session'), true);
        req.response.write('<p>chapter</p>');
      }
      await req.response.close();
    });
    final base = Uri.parse('http://127.0.0.1:${server.port}');
    final engine = SourceEngine(runtime: NoScripts());
    final source = SourceDefinition(
      id: 'a',
      name: 'A',
      baseUrl: base,
      stages: {
        'search': SourceStage(
          url: '/search?key={{key}}',
          list: '@css:article',
          fields: {'name': '@css:h2@text', 'bookUrl': '@css:a@href'},
        ),
        'info': SourceStage(
          url: '/redirect',
          fields: {'content': '@css:p@text'},
        ),
      },
    );
    try {
      expect(await engine.execute(source, 'search', input: {'key': 'a b'}), [
        {'name': 'A', 'bookUrl': '$base/book'},
      ]);
      expect(await engine.execute(source, 'info'), [
        {'content': 'chapter'},
      ]);
    } finally {
      await engine.close();
      await server.close(force: true);
    }
  });
  test('pagination accumulates toc, joins content, rejects cycles and limits', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((req) async {
      req.response.write(
        '<p>${req.uri.path}</p>${req.uri.path == '/1' ? '<a href="/2">next</a>' : ''}',
      );
      await req.response.close();
    });
    final engine = SourceEngine(runtime: NoScripts());
    final base = Uri.parse('http://127.0.0.1:${server.port}');
    SourceDefinition source({int pages = 20, String next = '@css:a@href'}) =>
        SourceDefinition(
          id: 'p',
          name: 'P',
          baseUrl: base,
          stages: {
            'content': SourceStage(
              url: '/1',
              fields: {'content': '@css:p@text'},
              nextPage: next,
              maxPages: pages,
            ),
            'toc': SourceStage(
              url: '/1',
              list: '@css:p',
              fields: {'title': '@css:@text'},
              nextPage: next,
              maxPages: pages,
            ),
          },
        );
    try {
      expect(await engine.execute(source(), 'content'), [
        {'content': '/1\n/2'},
      ]);
      expect(await engine.execute(source(), 'toc'), [
        {'title': '/1'},
        {'title': '/2'},
      ]);
      await expectLater(
        engine.execute(source(pages: 1), 'content'),
        throwsA(
          isA<EngineException>().having(
            (e) => e.code,
            'code',
            'pagination_limit',
          ),
        ),
      );
    } finally {
      await engine.close();
      await server.close(force: true);
    }
  });
  test('request scheduler bounds concurrency and spaces starts', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    var active = 0;
    var peak = 0;
    final starts = <DateTime>[];
    server.listen((req) async {
      active++;
      if (active > peak) peak = active;
      starts.add(DateTime.now());
      await Future<void>.delayed(const Duration(milliseconds: 15));
      active--;
      req.response.write('ok');
      await req.response.close();
    });
    final network = NetworkClient(
      maxConcurrentRequests: 1,
      minRequestInterval: const Duration(milliseconds: 30),
    );
    try {
      await Future.wait(
        List.generate(
          3,
          (_) => network.request(Uri.parse('http://127.0.0.1:${server.port}/')),
        ),
      );
      expect(peak, 1);
      expect(
        starts.last.difference(starts.first).inMilliseconds,
        greaterThanOrEqualTo(40),
      );
    } finally {
      network.close();
      await server.close(force: true);
    }
  });
  test(
    'browser cookies feed following HTTP request and stay host scoped',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      final base = 'http://127.0.0.1:${server.port}';
      server.listen((req) async {
        expect(
          req.cookies.any((c) => c.name == 'session' && c.value == 'ok'),
          true,
        );
        req.response.write('authenticated');
        await req.response.close();
      });
      final engine = SourceEngine(
        runtime: BrowserScripts(),
        platform: BrowserHost('$base/login'),
      );
      try {
        expect(
          await engine.execute(
            SourceDefinition(
              id: 'browser',
              name: 'Browser',
              baseUrl: Uri.parse(base),
              script: 'test',
            ),
            'content',
          ),
          [
            {'content': 'authenticated'},
          ],
        );
      } finally {
        await engine.close();
        await server.close(force: true);
      }
      final cookieServer = await HttpServer.bind(
        InternetAddress.loopbackIPv4,
        0,
      );
      cookieServer.listen((req) async {
        req.response.write(req.cookies.map((c) => c.name).join(','));
        await req.response.close();
      });
      final network = NetworkClient();
      final origin = Uri.parse(
        'http://127.0.0.1:${cookieServer.port}/account/login',
      );
      network.importBrowserCookieHeader(origin, 'session=ok');
      network.importCookies(origin, [
        Cookie('token', 'bad')..domain = 'unrelated.test',
      ]);
      try {
        expect(
          (await network.request(origin.resolve('/account/chapter'))).body,
          'session',
        );
        expect((await network.request(origin.resolve('/other'))).body, '');
        expect(
          (await network.request(
            Uri.parse('http://localhost:${cookieServer.port}/account/chapter'),
          )).body,
          '',
        );
      } finally {
        network.close();
        await cookieServer.close(force: true);
      }
    },
  );
  test('GBK response decoding and explicit unsupported charset', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((req) async {
      req.response.headers.set(
        'content-type',
        "text/plain; charset=${req.uri.path == '/gbk' ? 'gbk' : 'gb18030'}",
      );
      req.response.add([0xd6, 0xd0]);
      await req.response.close();
    });
    final network = NetworkClient();
    final base = 'http://127.0.0.1:${server.port}';
    try {
      expect((await network.request(Uri.parse('$base/gbk'))).body, '中');
      await expectLater(
        network.request(Uri.parse('$base/gb18030')),
        throwsA(
          isA<EngineException>().having(
            (e) => e.code,
            'code',
            'unsupported_charset',
          ),
        ),
      );
    } finally {
      network.close();
      await server.close(force: true);
    }
  });
  test('cancel aborts pending request; timeout distinct', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((req) async {
      await Future<void>.delayed(const Duration(milliseconds: 100));
      try {
        req.response.write('late');
        await req.response.close();
      } catch (_) {}
    });
    final network = NetworkClient();
    final uri = Uri.parse('http://127.0.0.1:${server.port}/');
    try {
      final token = CancellationToken();
      final pending = network.request(uri, cancellation: token);
      token.cancel();
      await expectLater(
        pending,
        throwsA(
          isA<EngineException>().having((e) => e.code, 'code', 'cancelled'),
        ),
      );
      await expectLater(
        network.request(uri, timeout: const Duration(milliseconds: 10)),
        throwsA(
          isA<EngineException>().having((e) => e.code, 'code', 'timeout'),
        ),
      );
    } finally {
      network.close();
      await server.close(force: true);
    }
  });
}
