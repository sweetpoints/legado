import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_host/task_host.dart';
import 'package:source_host/main.dart' show createSourceEngine;
import 'package:source_platform/source_platform.dart';

class _NativeHttp implements ScriptHost {
  final calls = <Map>[];
  @override
  Future<Object?> call(String method, List<Object?> args) async {
    calls.add({'method': method, 'arguments': args});
    if (method == 'javaHttp.ajax') return 'Native text';
    throw StateError('Unexpected native method $method');
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('legado/source_host_platform');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));
  test('all six legacy HTTP methods preserve raw arguments and the registered task/source', () async {
    final calls = <Map>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call.arguments as Map);
      return 'native';
    });
    final host = TaskScriptHost(
      const SourcePlatform(sourceId: 'trusted-source'),
      'trusted-task',
    );
    for (final method in [
      'ajax',
      'get',
      'post',
      'head',
      'connect',
      'ajaxAll',
    ]) {
      await host.call('javaHttp.$method', [
        'https://fixture.invalid/, {"method":"POST"}',
        {'X': 'value'},
      ]);
    }
    expect(calls, hasLength(6));
    for (final call in calls) {
      expect(call['sourceId'], 'trusted-source');
      expect(call['taskId'], 'trusted-task');
      expect(call['fromScript'], true);
      expect(call['arguments'], [
        'https://fixture.invalid/, {"method":"POST"}',
        {'X': 'value'},
      ]);
    }
    await expectLater(
      host.call('javaHttp.unknown', []),
      throwsA(isA<EngineException>()),
    );
  });
  test('native transport failures retain code and safe type metadata instead of fallback', () async {
    final details = {
      'exceptionTypes': ['javax.net.ssl.SSLHandshakeException'],
    };
    messenger.setMockMethodCallHandler(
      channel,
      (_) async =>
          throw PlatformException(code: 'network_error', details: details),
    );
    await expectLater(
      TaskScriptHost(
        const SourcePlatform(sourceId: 'source'),
        'task',
      ).call('javaHttp.ajax', ['https://fixture.invalid/']),
      throwsA(
        isA<PlatformException>()
            .having((e) => e.code, 'code', 'network_error')
            .having((e) => e.details, 'details', details),
      ),
    );
  });
  test('production auxiliary runtime enables Native legacy HTTP explicitly while modern Java stays absent', () async {
    final source = SourceDefinition(
      id: 'fixture',
      name: 'Fixture',
      baseUrl: Uri.parse('https://fixture.invalid/'),
      metadata: {'legacy': true},
    );
    final native = _NativeHttp(),
        engine = createSourceEngine(source, platform: _NativeHttp());
    final enabled = createSourceEngine(
      source,
      platform: native,
      useNativeLegacyHttp: true,
    );
    try {
      expect(
        await enabled.evaluateAuxiliary(
          source,
          "java.ajax('https://fixture.invalid/raw,{\"method\":\"POST\"}')",
          bindings: {'taskId': 'bound-task'},
        ),
        'Native text',
      );
      expect(native.calls.single['method'], 'javaHttp.ajax');
      expect(
        (native.calls.single['arguments'] as List).first,
        'https://fixture.invalid/raw,{"method":"POST"}',
      );
      expect((native.calls.single['arguments'] as List).last, {
        '__sourceTaskId': 'bound-task',
        '__sourceHostCallback': true,
      });
    } finally {
      await enabled.close();
      await engine.close();
    }
    final modern = SourceDefinition(
      id: 'modern',
      name: 'Modern',
      baseUrl: Uri.parse('https://fixture.invalid/'),
    );
    final modernEngine = createSourceEngine(
      modern,
      platform: native,
      useNativeLegacyHttp: true,
    );
    try {
      expect(
        await modernEngine.evaluateAuxiliary(
          modern,
          'typeof java',
          bindings: {'taskId': 'modern-task'},
        ),
        'undefined',
      );
      expect(native.calls, hasLength(1));
    } finally {
      await modernEngine.close();
    }
  });
}
