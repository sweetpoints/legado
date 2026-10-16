import 'dart:io';

import 'package:source_engine/source_engine.dart';
import 'package:test/test.dart';

class _Runtime implements AuxiliaryScriptRuntime {
  @override
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  }) async => throw StateError('CSS fixture must not evaluate scripts');
  @override
  Future<Object?> evaluateAuxiliary(
    String code,
    ScriptContext context, {
    String prelude = '',
    CancellationToken? cancellation,
  }) => context.host.call('net.get', ['/echo']);
  @override
  Future<ScriptDiagnostic?> checkSyntax(
    String code, {
    CancellationToken? cancellation,
  }) async => null;
  @override
  Future<void> close() async {}
}

void main() {
  late HttpServer server;
  late Uri base;
  late HttpServer other;
  final seen = <String>[];
  setUp(() async {
    seen.clear();
    other = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    other.listen((request) async {
      request.response.write(request.headers.value('cookie') ?? 'none');
      await request.response.close();
    });
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    base = Uri.parse('http://${server.address.address}:${server.port}');
    server.listen((request) async {
      final cookie = request.headers.value('cookie') ?? 'none';
      seen.add('${request.uri.path}:$cookie');
      if (request.uri.path == '/set' || request.uri.path == '/redirect') {
        request.response.cookies.add(Cookie('automatic', 'saved')..path = '/');
      }
      if (request.uri.path == '/redirect' ||
          request.uri.path == '/cross-origin') {
        request.response.statusCode = HttpStatus.found;
        request.response.headers.set(
          'location',
          request.uri.path == '/redirect'
              ? '/echo'
              : 'http://${other.address.address}:${other.port}/echo',
        );
      } else {
        request.response.headers.contentType = ContentType.html;
        request.response.write('<p>$cookie</p>');
      }
      await request.response.close();
    });
  });
  tearDown(() async {
    await server.close(force: true);
    await other.close(force: true);
  });

  test('disabled jar ignores restore/browser imports and Set-Cookie, preserves explicit header and redirect policy', () async {
    final enabled = NetworkClient();
    final disabled = NetworkClient(cookieJarEnabled: false);
    try {
      await enabled.request(base.resolve('/set'));
      final saved = enabled.exportCookies();
      expect(saved, hasLength(1));
      disabled.restoreCookies(saved);
      disabled.importCookies(base, [Cookie('direct', 'ignored')]);
      disabled.importBrowserCookieHeader(base, 'browser=ignored');
      expect(disabled.cookieHeader(base), isEmpty);
      final response = await disabled.request(
        base.resolve('/redirect'),
        headers: {'Cookie': 'manual=explicit'},
      );
      expect(response.body, '<p>manual=explicit</p>');
      expect(disabled.exportCookies(), isEmpty);
      expect(
        (await disabled.request(base.resolve('/echo'))).body,
        '<p>none</p>',
      );
      final cross = await disabled.request(
        base.resolve('/cross-origin'),
        headers: {'cOoKiE': 'manual=explicit'},
      );
      expect(cross.body, 'none');
      expect(seen, contains('/redirect:manual=explicit'));
      expect(
        seen.where((entry) => entry.contains('automatic=saved')).isEmpty,
        true,
      );
    } finally {
      enabled.close();
      disabled.close();
    }
  });

  test('default jar still captures redirect cookies and persists across client restore', () async {
    final client = NetworkClient();
    final restored = NetworkClient();
    try {
      expect(
        (await client.request(base.resolve('/redirect'))).body,
        '<p>automatic=saved</p>',
      );
      restored.restoreCookies(client.exportCookies());
      expect(
        (await restored.request(base.resolve('/echo'))).body,
        '<p>automatic=saved</p>',
      );
    } finally {
      client.close();
      restored.close();
    }
  });

  SourceDefinition source(
    String id, {
    bool? enabled,
    bool oldSnapshot = false,
  }) => SourceDefinition(
    id: id,
    name: id,
    baseUrl: base,
    metadata: oldSnapshot
        ? {
            'legacyOriginal': {'enabledCookieJar': false},
          }
        : {'cookieJarEnabled': ?enabled},
    stages: {
      'content': const SourceStage(
        url: '{{chapterUrl}}',
        fields: {'content': '@css:p@text'},
      ),
    },
  );

  test('source policy isolates injected/shared jar and ignores restored cookies while retaining variables', () async {
    final shared = NetworkClient();
    await shared.request(base.resolve('/set'));
    final engine = SourceEngine(runtime: _Runtime(), network: shared);
    try {
      engine.importSession('disabled', {
        'variables': {'keep': 'value'},
        'cookies': shared
            .exportCookies()
            .map((cookie) => {...cookie, 'value': 'foreign'})
            .toList(),
      });
      expect(shared.cookieHeader(base), 'automatic=saved');
      final disabled = source('disabled', enabled: false);
      expect(
        await engine.execute(
          disabled,
          'content',
          input: {'chapterUrl': '/echo'},
        ),
        [
          {'content': 'none'},
        ],
      );
      expect(engine.exportSession('disabled')['cookies'], isEmpty);
      expect(engine.exportSession('disabled')['variables'], {'keep': 'value'});
      expect(
        await engine.evaluateAuxiliary(disabled, 'fixture'),
        '<p>none</p>',
      );
      expect(
        await engine.execute(
          source('enabled'),
          'content',
          input: {'chapterUrl': '/echo'},
        ),
        [
          {'content': 'automatic=saved'},
        ],
      );
      expect(
        await engine.execute(
          source('old', oldSnapshot: true),
          'content',
          input: {'chapterUrl': '/set'},
        ),
        [
          {'content': 'none'},
        ],
      );
      expect(engine.exportSession('old')['cookies'], isEmpty);
      engine.importSession('disabled', {
        'variables': {},
        'cookies': shared.exportCookies(),
      });
      expect(
        await engine.execute(
          disabled,
          'content',
          input: {'chapterUrl': '/echo'},
        ),
        [
          {'content': 'none'},
        ],
      );
      expect(shared.exportCookies(), hasLength(1));
    } finally {
      await engine.close();
    }
  });

  test('immutable policy change requires new engine instead of sharing an active jar', () async {
    final engine = SourceEngine(runtime: _Runtime());
    try {
      await engine.execute(
        source('identity', enabled: true),
        'content',
        input: {'chapterUrl': '/set'},
      );
      await expectLater(
        engine.execute(
          source('identity', enabled: false),
          'content',
          input: {'chapterUrl': '/echo'},
        ),
        throwsA(
          isA<EngineException>().having(
            (e) => e.code,
            'code',
            'source_policy_changed',
          ),
        ),
      );
      expect(
        () => SourceDefinition(
          id: 'bad',
          name: 'Bad',
          baseUrl: base,
          metadata: {'cookieJarEnabled': 'false'},
        ),
        throwsA(isA<EngineException>()),
      );
    } finally {
      await engine.close();
    }
  });
}
