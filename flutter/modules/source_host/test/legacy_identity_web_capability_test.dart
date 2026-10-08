import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_host/source_host.dart';

class _Runtime implements ScriptRuntime {
  @override
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  }) async => [
    {'value': 'ok'},
  ];
  @override
  Future<void> close() async {}
}

class _Pages extends NetworkClient {
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
  }) async => NetworkResponse(uri, 200, {}, '<p>Web fixture</p>');
}

class _Host implements ScriptHost {
  @override
  Future<Object?> call(String method, List<Object?> args) async => {
    'value': 'Web fixture',
    'variables': <String, String>{},
    'variableScope': (args.first as Map)['variableScope'],
  };
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('URL-only legacy auxiliary falls back internally without modifying its raw label', () async {
    for (final name in ['', '   ']) {
      final raw = {
        'bookSourceUrl': 'https://fixture.invalid/',
        'bookSourceName': name,
      };
      final host = SourceHost((source) {
        expect(source.name, 'https://fixture.invalid/');
        return SourceEngine(runtime: _Runtime());
      });
      try {
        expect(
          await host.handle(
            MethodCall('evaluate', {
              'protocolVersion': 1,
              'taskId': 'aux',
              'sourceJson': jsonEncode(raw),
              'script': '1',
              'bindings': <String, Object?>{},
            }),
          ),
          {'value': 'ok'},
        );
        expect(raw['bookSourceName'], name);
      } finally {
        await host.close();
      }
    }
  });
  test('modern empty source name still rejects instead of inheriting legacy fallback', () async {
    final host = SourceHost((_) => throw StateError('must not create engine'));
    try {
      await expectLater(
        host.handle(
          MethodCall('evaluate', {
            'protocolVersion': 1,
            'taskId': 'modern',
            'sourceJson': jsonEncode({
              'schemaVersion': 1,
              'id': 'https://fixture.invalid/',
              'name': '',
              'baseUrl': 'https://fixture.invalid/',
            }),
            'script': '1',
            'bindings': <String, Object?>{},
          }),
        ),
        throwsA(
          isA<PlatformException>().having(
            (e) => e.code,
            'code',
            'invalid_source',
          ),
        ),
      );
    } finally {
      await host.close();
    }
  });
  for (final enabled in [false, true]) {
    test(
      'WebView extraction requires explicit native WebJS capability ($enabled)',
      () async {
        final host = SourceHost(
          (_) => SourceEngine(
            runtime: _Runtime(),
            network: _Pages(),
            platform: _Host(),
            legacyRuleEvaluator: HostLegacyRuleEvaluator(
              allowWebScripts: enabled,
            ),
          ),
          legacyRuleHostEnabled: true,
          legacyWebRuleHostEnabled: enabled,
        );
        final call = MethodCall('execute', {
          'protocolVersion': 1,
          'taskId': 'web',
          'operation': 'info',
          'sourceJson': jsonEncode({
            'bookSourceUrl': 'https://fixture.invalid/',
            'ruleBookInfo': {'name': '@webjs:document.body.textContent'},
          }),
          'input': {'bookUrl': 'https://fixture.invalid/info'},
        });
        try {
          if (enabled) {
            expect(await host.handle(call), [
              {'name': 'Web fixture'},
            ]);
          } else {
            await expectLater(
              host.handle(call),
              throwsA(
                isA<PlatformException>().having(
                  (e) => e.code,
                  'code',
                  'legacy_requires_migration',
                ),
              ),
            );
          }
        } finally {
          await host.close();
        }
      },
    );
  }
}
