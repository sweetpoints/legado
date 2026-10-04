import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:source_platform/source_platform.dart';
import 'package:source_engine/source_engine.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'storage is scoped by source and unsupported capabilities fail',
    () async {
      const channel = MethodChannel('legado/source_platform');
      final calls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            calls.add(call);
            return 'saved';
          });
      const adapter = SourcePlatform(sourceId: 'https://example.org');
      expect(await adapter.call('storage.read', ['token']), 'saved');
      expect(calls.single.arguments, {
        'sourceId': 'https://example.org',
        'key': 'token',
      });
      await expectLater(
        adapter.call('arbitrary.unknown', []),
        throwsA(isA<EngineException>()),
      );
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    },
  );
}
