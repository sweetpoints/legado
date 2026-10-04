import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:source_host/source_host.dart';

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
