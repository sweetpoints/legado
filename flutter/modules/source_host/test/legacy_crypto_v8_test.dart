import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_host/task_host.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:source_platform/source_platform.dart';
import 'package:source_v8/source_v8.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('legado/source_host_platform');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));
  test(
    'actual V8 bounded crypto facade and log preserve object identity',
    () async {
      final methods = <String>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        final request = call.arguments as Map;
        expect(request['taskId'], 'crypto-task');
        expect(request['sourceId'], 'book:fixture');
        final method = request['method'] as String;
        methods.add(method);
        if (method == 'javaHost.cryptoCreate') {
          return {
            '__legacyCryptoState': {'generation': 1},
          };
        }
        if (method == 'javaHost.cryptoCall') {
          final args = request['arguments'] as List;
          expect(args[0], isA<Map>());
          return {
            'value': args[1] == 'setIv' ? null : 'encrypted',
            'state': args[0],
          };
        }
        return (request['arguments'] as List).single;
      });
      final runtime = V8Runtime(prelude: legacyScriptPrelude);
      try {
        final host = LegacyScriptHost(
          TaskScriptHost(
            const SourcePlatform(sourceId: 'book:fixture'),
            'crypto-task',
          ),
        );
        expect(
          await runtime.evaluate(
            '(()=>{const object={n:1};const crypto=java.createSymmetricCrypto("AES","key");'
            'return {identity:java.log(object)===object,chain:crypto.setIv([0])===crypto,'
            'value:crypto.encryptBase64("data"),unknown:typeof crypto.getCipher};})()',
            ScriptContext(host: host),
          ),
          {
            'identity': true,
            'chain': true,
            'value': 'encrypted',
            'unknown': 'undefined',
          },
        );
        expect(methods, [
          'javaHost.cryptoCreate',
          'javaHost.log',
          'javaHost.cryptoCall',
          'javaHost.cryptoCall',
        ]);
      } finally {
        await runtime.close();
      }
    },
  );
  test(
    '600 chapter entries do not expire jsLib retained crypto closure',
    () async {
      var created = 0;
      messenger.setMockMethodCallHandler(channel, (call) async {
        final request = call.arguments as Map;
        expect(request['sourceId'], 'book:fixture');
        if (request['method'] == 'javaHost.cryptoCreate') {
          return {
            '__legacyCryptoState': {'generation': ++created},
          };
        }
        final args = request['arguments'] as List;
        return {'value': (args[0] as Map)['generation'], 'state': args[0]};
      });
      final runtime = V8Runtime(prelude: legacyScriptPrelude, persistent: true);
      final host = LegacyScriptHost(
        TaskScriptHost(
          const SourcePlatform(sourceId: 'book:fixture'),
          'chapter-task',
        ),
      );
      try {
        await runtime.evaluateAuxiliary(
          'var savedCrypto=java.createSymmetricCrypto("AES",null);',
          ScriptContext(host: host),
        );
        for (var chapter = 0; chapter < 600; chapter++) {
          await runtime.evaluateAuxiliary(
            '(()=>{java.createSymmetricCrypto("AES",null).encryptHex("chapter");})()',
            ScriptContext(host: host),
          );
        }
        expect(
          await runtime.evaluateAuxiliary(
            'savedCrypto.encryptHex("retained")',
            ScriptContext(host: host),
          ),
          1,
        );
        expect(created, 601);
      } finally {
        await runtime.close();
      }
    },
  );
}
