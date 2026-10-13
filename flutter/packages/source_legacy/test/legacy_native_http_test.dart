import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:test/test.dart';

class Host implements ScriptHost {
  final calls = <(String, List<Object?>)>[];
  bool fail = false;
  @override
  Future<Object?> call(String method, List<Object?> args) async {
    calls.add((method, args));
    if (fail) throw const EngineException('network_error', 'native failure');
    return 'native';
  }
}

void main() {
  test(
    'Android legacy native path preserves raw request overload arguments',
    () async {
      final delegate = Host();
      final host = LegacyScriptHost(delegate, useNativeHttp: true);
      final headers = {'X-Fixture': 'exact'};
      for (final call in <(String, List<Object?>)>[
        ('ajax', ['/relative,{"method":"POST"}', 4000]),
        ('connect', ['url', '{"x":"y"}', 5000]),
        ('get', ['url', headers]),
        ('post', ['url', 'body', headers, 1000]),
        ('head', ['url', null]),
        (
          'ajaxAll',
          [
            ['first', 'second'],
            true,
          ],
        ),
      ]) {
        expect(await host.call('java.${call.$1}', call.$2), 'native');
        expect(delegate.calls.last.$1, 'javaHttp.${call.$1}');
        expect(delegate.calls.last.$2, same(call.$2));
      }
      await host.call('java.put', ['saved', 'value']);
      expect(await host.call('java.get', ['saved']), 'value');
      expect(delegate.calls.length, 6);
    },
  );
  test(
    'native transport errors propagate without retry through net.request',
    () async {
      final delegate = Host()..fail = true;
      await expectLater(
        LegacyScriptHost(
          delegate,
          useNativeHttp: true,
        ).call('java.ajax', ['url']),
        throwsA(
          isA<EngineException>().having((e) => e.code, 'code', 'network_error'),
        ),
      );
      expect(delegate.calls.map((c) => c.$1), ['javaHttp.ajax']);
    },
  );
  test(
    'other platforms retain explicit Dart legacy transport fallback',
    () async {
      final delegate = Host();
      await expectLater(
        LegacyScriptHost(delegate)
            .call('java.ajax', ['https://fixture.invalid']),
        throwsStateError,
      );
      expect(delegate.calls.single.$1, 'net.request');
    },
  );
}
