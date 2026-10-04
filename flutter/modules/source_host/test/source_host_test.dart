import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:source_host/source_host.dart';
import 'package:source_engine/source_engine.dart';

class _NoScripts implements ScriptRuntime {
  @override
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  }) async => throw UnsupportedError('fixture has no scripts');
  @override
  Future<void> close() async {}
}

class _FixtureNetwork extends NetworkClient {
  final requests = <Uri>[];
  @override
  Future<NetworkResponse> request(
    Uri uri, {
    String method = 'GET',
    Map<String, String> headers = const {},
    String? body,
    String? charset,
    Duration timeout = const Duration(seconds: 30),
    CancellationToken? cancellation,
    int maxRedirects = 5,
    bool followRedirects = true,
  }) async {
    requests.add(uri);
    return NetworkResponse(
      uri,
      200,
      {},
      '<article><h2>Fixture</h2><a href="/book">Book</a></article><p>Content</p>',
    );
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('startup failures are reported before ready', () async {
    const channel = MethodChannel('legado/source_engine');
    final calls = <MethodCall>[];
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return null;
    });
    final host = SourceHost(
      (_) => throw StateError('unused'),
      channel: channel,
    );
    await host.attach(
      initialize: () async => throw StateError('native library unavailable'),
    );
    expect(calls.map((call) => call.method), ['startupError']);
    expect(
      (calls.single.arguments as Map)['code'],
      'runtime_initialization_failed',
    );
    await host.close();
    messenger.setMockMethodCallHandler(channel, null);
  });
  test(
    'base-only legacy identity review delegates enforcement to engine',
    () async {
      final network = _FixtureNetwork();
      var created = 0;
      final host = SourceHost((source) {
        created++;
        expect(source.id, 'non-http-identity');
        expect(source.metadata['legacyBaseUrlUnavailable'], true);
        return SourceEngine(runtime: _NoScripts(), network: network);
      });
      final raw = {
        'bookSourceUrl': 'non-http-identity',
        'bookSourceName': 'Fixture',
        'searchUrl': 'https://requests.example/search',
        'ruleSearch': {
          'bookList': 'tag.article',
          'name': 'tag.h2@text',
          'bookUrl': 'tag.a@href',
        },
        'ruleContent': {'content': 'tag.p@text'},
      };
      MethodCall execute(
        String id,
        String operation, {
        Map<String, Object?> input = const {},
      }) => MethodCall('execute', {
        'protocolVersion': 1,
        'taskId': id,
        'operation': operation,
        'sourceJson': jsonEncode(raw),
        'input': input,
      });
      try {
        expect(await host.handle(execute('absolute', 'search')), [
          {'name': 'Fixture', 'bookUrl': 'https://requests.example/book'},
        ]);
        expect(
          network.requests.single.toString(),
          'https://requests.example/search',
        );
        await expectLater(
          host.handle(
            execute('relative', 'content', input: {'chapterUrl': '/chapter'}),
          ),
          throwsA(
            isA<PlatformException>().having(
              (e) => e.code,
              'code',
              'legacy_base_url_required',
            ),
          ),
        );
        expect(created, 1);
        expect(network.requests, hasLength(1));
      } finally {
        await host.close();
      }
    },
  );
  test(
    'base identity exception does not bypass mixed unsupported capability',
    () async {
      var created = 0;
      final host = SourceHost((_) {
        created++;
        throw StateError('blocked source must not create engine');
      });
      try {
        await expectLater(
          host.handle(
            MethodCall('execute', {
              'protocolVersion': 1,
              'taskId': 'mixed',
              'operation': 'search',
              'sourceJson': jsonEncode({
                'bookSourceUrl': 'non-http-identity',
                'bookSourceName': 'Fixture',
                'searchUrl': 'https://requests.example/search',
                'loginUrl': 'https://requests.example/login',
                'ruleSearch': {
                  'bookList': 'tag.article',
                  'name': 'tag.h2@text',
                },
              }),
            }),
          ),
          throwsA(
            isA<PlatformException>()
                .having((e) => e.code, 'code', 'legacy_requires_migration')
                .having(
                  (e) => (e.details as List).map((i) => (i as Map)['code']),
                  'blocking codes',
                  contains('legacy.capability_requires_review'),
                ),
          ),
        );
        expect(created, 0);
      } finally {
        await host.close();
      }
    },
  );
  test(
    'host validates protocol and reports unsupported legacy capabilities',
    () async {
      final host = SourceHost(
        (_) => throw StateError('must not execute invalid source'),
      );
      await expectLater(
        host.handle(const MethodCall('execute', {'taskId': 'a'})),
        throwsA(
          isA<PlatformException>().having(
            (e) => e.code,
            'code',
            'invalid_request',
          ),
        ),
      );
      await expectLater(
        host.handle(
          MethodCall('execute', {
            'protocolVersion': 1,
            'taskId': 'b',
            'operation': 'search',
            'sourceJson': jsonEncode({
              'bookSourceUrl': 'https://example.org',
              'bookSourceName': 'Example',
              'loginUrl': 'https://example.org/login',
              'searchUrl': '/search',
              'ruleSearch': {'bookList': '.book', 'name': '.name@text'},
            }),
          }),
        ),
        throwsA(
          isA<PlatformException>().having(
            (e) => e.code,
            'code',
            'legacy_requires_migration',
          ),
        ),
      );
      expect(
        await host.handle(const MethodCall('cancel', {'taskId': 'unknown'})),
        isNull,
      );
      await host.close();
    },
  );
}
