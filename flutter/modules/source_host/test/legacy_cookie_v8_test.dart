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
    'actual V8 legacy CookieStore namespace uses task-bound JSON callbacks',
    () async {
      final calls = <String>[];
      String? stored = 'fixture=before';
      messenger.setMockMethodCallHandler(channel, (call) async {
        final request = call.arguments as Map;
        expect(request['sourceId'], 'book:fixture');
        expect(request['taskId'], 'cookie-task');
        final method = request['method'] as String;
        final args = request['arguments'] as List;
        calls.add(method);
        switch (method) {
          case 'cookieHost.getCookie':
            return stored ?? '';
          case 'cookieHost.removeCookie':
            stored = null;
            return null;
          case 'cookieHost.cookieToMap':
            return {'first': 'one'};
          case 'cookieHost.mapToCookie':
            expect(args.single, {'first': 'one', 'second': 'two'});
            return 'first=one; second=two';
          default:
            return null;
        }
      });
      final runtime = V8Runtime(prelude: legacyScriptPrelude);
      final host = TaskScriptHost(
        const SourcePlatform(sourceId: 'book:fixture'),
        'cookie-task',
      );
      try {
        expect(
          await runtime.evaluate(
            '(()=>{const before=cookie.getCookie("fixture.invalid");'
            'cookie.removeCookie("fixture.invalid");const after=cookie.getCookie("fixture.invalid");'
            'const values=cookie.cookieToMap("first=one");values.put("second","two");'
            'return {before,after,map:cookie.mapToCookie(values),size:values.size(),'
            'first:values.get("first"),reflection:typeof cookie.getClass};})()',
            ScriptContext(host: host),
          ),
          {
            'before': 'fixture=before',
            'after': '',
            'map': 'first=one; second=two',
            'size': 2,
            'first': 'one',
            'reflection': 'undefined',
          },
        );
        expect(calls, [
          'cookieHost.getCookie',
          'cookieHost.removeCookie',
          'cookieHost.getCookie',
          'cookieHost.cookieToMap',
          'cookieHost.mapToCookie',
        ]);
      } finally {
        await runtime.close();
      }
    },
  );
  test('modern runtime does not acquire the legacy cookie namespace', () async {
    final runtime = V8Runtime();
    try {
      expect(
        await runtime.evaluate(
          'typeof cookie',
          ScriptContext(host: const SourcePlatform(sourceId: 'modern')),
        ),
        'undefined',
      );
    } finally {
      await runtime.close();
    }
  });
}
