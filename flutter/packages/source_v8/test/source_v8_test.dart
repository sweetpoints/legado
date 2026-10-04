import 'dart:async';
import 'dart:convert';

import 'package:test/test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_v8/source_v8.dart';

class Host implements ScriptHost {
  @override
  Future<Object?> call(String method, List<Object?> args) async {
    await Future<void>.delayed(const Duration(milliseconds: 10));
    return '${args.first}!';
  }
}

void main() {
  late V8Runtime runtime;
  setUp(() => runtime = V8Runtime());
  tearDown(() => runtime.close());
  test('actual pinned V8 executes JavaScript', () async {
    expect(runtime.version, startsWith('14.3.92'));
    expect(await runtime.evaluate('6*7', ScriptContext(host: Host())), 42);
  });
  test('awaits asynchronous host promise', () async {
    expect(
      await runtime.evaluate(
        'await source.net.get("book")',
        ScriptContext(host: Host()),
      ),
      'book!',
    );
  });
  test('synchronous compatibility host roundtrip', () async {
    expect(
      await runtime.evaluate(
        '__sourceHostSync("net.get",["legacy"])',
        ScriptContext(host: Host()),
      ),
      'legacy!',
    );
  });
  test('watchdog terminates busy script', () async {
    await expectLater(
      runtime.evaluate(
        'while(true) {}',
        ScriptContext(host: Host(), timeout: const Duration(milliseconds: 80)),
      ),
      throwsA(
        isA<EngineException>().having((e) => e.code, 'code', 'script_timeout'),
      ),
    );
  });
  test('cancellation terminates busy script', () async {
    final token = CancellationToken();
    final pending = runtime.evaluate(
      'while(true) {}',
      ScriptContext(host: Host()),
      cancellation: token,
    );
    Timer(const Duration(milliseconds: 60), token.cancel);
    await expectLater(
      pending,
      throwsA(
        isA<EngineException>().having((e) => e.code, 'code', 'cancelled'),
      ),
    );
  });
  test('script error is recoverable', () async {
    await expectLater(
      runtime.evaluate('throw new Error("bad")', ScriptContext(host: Host())),
      throwsA(isA<EngineException>()),
    );
    expect(await runtime.evaluate('42', ScriptContext(host: Host())), 42);
  });
  test('prototype setters do not abort native bridge', () async {
    expect(
      await runtime.evaluate(
        'Object.defineProperty(Object.prototype,"status",{set(){throw new Error("boom")}}); return 1;',
        ScriptContext(host: Host()),
      ),
      1,
    );
  });
  test('cyclic host arguments reject script', () async {
    await expectLater(
      runtime.evaluate(
        'const x={};x.x=x;return await source.call("bad",[x]);',
        ScriptContext(host: Host()),
      ),
      throwsA(isA<EngineException>()),
    );
  });
  test('watchdog terminates prelude', () async {
    final looping = V8Runtime(prelude: 'while(true){}');
    try {
      await expectLater(
        looping.evaluate(
          '42',
          ScriptContext(
            host: Host(),
            timeout: const Duration(milliseconds: 20),
          ),
        ),
        throwsA(isA<EngineException>()),
      );
    } finally {
      await looping.close();
    }
  });
  test('invalid variables fail without hanging lifecycle', () async {
    final variables = <String, Object?>{};
    variables['cycle'] = variables;
    await expectLater(
      runtime.evaluate('42', ScriptContext(host: Host(), variables: variables)),
      throwsA(isA<JsonUnsupportedObjectError>()),
    );
  });
  test('early cancellation remains safe across repeated evaluations', () async {
    for (var n = 0; n < 20; n++) {
      final token = CancellationToken();
      final pending = runtime.evaluate(
        'while(true){}',
        ScriptContext(host: Host()),
        cancellation: token,
      );
      Timer(const Duration(milliseconds: 1), token.cancel);
      await expectLater(pending, throwsA(isA<EngineException>()));
    }
  });
  test('protocol ignores inherited toJSON', () async {
    expect(
      await runtime.evaluate(
        'Object.prototype.toJSON=()=>null;return await source.net.get("book");',
        ScriptContext(host: Host()),
      ),
      'book!',
    );
  });
  test('close cancels pending host work', () async {
    final pending = runtime.evaluate(
      'await new Promise(()=>{})',
      ScriptContext(host: Host()),
    );
    await Future<void>.delayed(const Duration(milliseconds: 20));
    final assertion = expectLater(pending, throwsA(isA<EngineException>()));
    await runtime.close();
    await assertion;
  });
}
