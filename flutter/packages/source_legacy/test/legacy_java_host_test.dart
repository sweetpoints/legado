import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:test/test.dart';

class Delegate implements ScriptHost {
  final calls = <(String, List<Object?>)>[];
  @override
  Future<Object?> call(String method, List<Object?> arguments) async {
    calls.add((method, arguments));
    return method == 'javaHost.log' ? arguments.single : 'native-result';
  }
}

void main() {
  test('approved Java helpers delegate JSON with original overloads', () async {
    final delegate = Delegate();
    final host = LegacyScriptHost(delegate);
    final value = {
      'message': ['x', null],
    };
    expect(await host.call('java.log', [value]), same(value));
    for (final method in ['logType', 'toast', 'longToast']) {
      await host.call('java.$method', [null]);
    }
    for (final method in ['t2s', 's2t']) {
      expect(await host.call('java.$method', ['繁體']), 'native-result');
    }
    await host.call('java.timeFormat', [1234]);
    await host.call('java.timeFormatUTC', [1234, 'yyyy-MM-dd', 28800000]);
    await host.call('java.getCookie', ['opaque-cookie-tag']);
    await host.call('java.getCookie', ['opaque-cookie-tag', null]);
    await host.call('java.getCookie', ['opaque-cookie-tag', 'key']);
    expect(delegate.calls.map((c) => c.$1), [
      'javaHost.log',
      'javaHost.logType',
      'javaHost.toast',
      'javaHost.longToast',
      'javaHost.t2s',
      'javaHost.s2t',
      'javaHost.timeFormat',
      'javaHost.timeFormatUTC',
      'javaHost.getCookie',
      'javaHost.getCookie',
      'javaHost.getCookie',
    ]);
    expect(delegate.calls[7].$2, [1234, 'yyyy-MM-dd', 28800000]);
  });
  test(
    'exact HMac spelling and chapter null delegate without reinterpretation',
    () async {
      final delegate = Delegate();
      final host = LegacyScriptHost(delegate);
      await host.call('java.HMacHex', ['data', 'HmacSHA256', 'key']);
      await host.call('java.HMacBase64', ['data', 'HmacSHA1', 'key']);
      await host.call('java.toNumChapter', [null]);
      await host.call('java.androidId', []);
      await host.call('java.randomUUID', []);
      expect(delegate.calls.map((c) => c.$1), [
        'javaHost.HMacHex',
        'javaHost.HMacBase64',
        'javaHost.toNumChapter',
        'javaHost.androidId',
        'javaHost.randomUUID',
      ]);
      expect(delegate.calls.first.$2, ['data', 'HmacSHA256', 'key']);
      expect(delegate.calls[2].$2, [null]);
      await expectLater(
        host.call('java.HMacHex', ['data', 'algo']),
        throwsA(anything),
      );
      await expectLater(host.call('java.androidId', ['x']), throwsA(anything));
      await expectLater(host.call('java.deviceID', []), throwsA(anything));
      expect(delegate.calls.length, 5);
    },
  );
  test(
    'invalid overloads and unapproved Java names never reach native',
    () async {
      final delegate = Delegate();
      final host = LegacyScriptHost(delegate);
      for (final call in <(String, List<Object?>)>[
        ('toast', []),
        ('t2s', [1]),
        ('getCookie', ['tag', 3]),
        ('timeFormat', [1.5]),
        ('timeFormatUTC', [1, 'yyyy', 2147483648]),
        ('getClass', []),
      ]) {
        await expectLater(
          host.call('java.${call.$1}', call.$2),
          throwsA(anything),
        );
      }
      expect(delegate.calls, isEmpty);
    },
  );
}
