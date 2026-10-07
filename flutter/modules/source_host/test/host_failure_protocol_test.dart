import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_host/source_host.dart';

class _FailureRuntime implements ScriptRuntime {
  _FailureRuntime(this.error);
  final PlatformException error;
  @override
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  }) async => throw error;
  @override
  Future<void> close() async {}
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  for (final code in ['network_error', 'HOST_CALL_FAILED', 'script_error']) {
    test('outer protocol preserves $code and bounded host details', () async {
      final details = {
        'schemaVersion': 1,
        'exceptionTypes': ['javax.net.ssl.SSLHandshakeException'],
      };
      final failure = PlatformException(
        code: code,
        message: 'fixture',
        details: details,
      );
      final host = SourceHost(
        (_) => SourceEngine(runtime: _FailureRuntime(failure)),
      );
      try {
        await expectLater(
          host.handle(
            MethodCall('execute', {
              'protocolVersion': 1,
              'taskId': 'failure-$code',
              'operation': 'search',
              'sourceJson': jsonEncode({
                'schemaVersion': 1,
                'id': 'fixture',
                'name': 'Fixture',
                'baseUrl': 'https://fixture.invalid/',
                'script': 'async function search(input) { return []; }',
              }),
              'input': <String, Object?>{},
            }),
          ),
          throwsA(
            isA<PlatformException>()
                .having((e) => e.code, 'code', code)
                .having((e) => e.details, 'details', details),
          ),
        );
      } finally {
        await host.close();
      }
    });
  }
}
