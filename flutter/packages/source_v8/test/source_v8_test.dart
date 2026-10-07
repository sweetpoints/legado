import 'dart:async';
import 'dart:convert';

import 'package:test/test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_v8/source_v8.dart';

class NestedHost implements ScriptHost {
  @override
  Future<Object?> call(String method, List<Object?> args) async =>
      throw const EngineException(
        'nested_script_requires_migration',
        'Nested script must not enter the waiting source VM',
      );
}

class ReentrantHost implements ScriptHost {
  ReentrantHost(this.runtime);
  final V8Runtime runtime;
  @override
  Future<Object?> call(String method, List<Object?> args) =>
      runtime.evaluateAuxiliary('42', ScriptContext(host: Host()));
}

class Host implements ScriptHost {
  @override
  Future<Object?> call(String method, List<Object?> args) async {
    await Future<void>.delayed(const Duration(milliseconds: 10));
    return '${args.first}!';
  }
}

class ConcurrentHost implements ScriptHost {
  @override
  Future<Object?> call(String method, List<Object?> args) async {
    await Future<void>.delayed(
      Duration(milliseconds: args.first == 'late' ? 60 : 10),
    );
    return '${args.first}!';
  }
}

void main() {
  test('actual recursive callback rejects before queueing a second script VM entry', () async {
    final session = V8Runtime(persistent: true);
    try {
      await expectLater(
        session.evaluateAuxiliary(
          '__sourceHostSync("parse",[])',
          ScriptContext(host: ReentrantHost(session)),
        ),
        throwsA(
          isA<EngineException>().having(
            (e) => e.code,
            'code',
            'nested_script_requires_migration',
          ),
        ),
      );
      expect(
        await session.evaluateAuxiliary('42', ScriptContext(host: Host())),
        42,
      );
    } finally {
      await session.close();
    }
  });
  test(
    'same-source nested sync callback rejects with typed migration code',
    () async {
      final session = V8Runtime(persistent: true);
      try {
        await expectLater(
          session.evaluateAuxiliary(
            '__sourceHostSync("analyze.getString",["rule"])',
            ScriptContext(host: NestedHost()),
          ),
          throwsA(
            isA<EngineException>().having(
              (e) => e.code,
              'code',
              'nested_script_requires_migration',
            ),
          ),
        );
        expect(
          await session.evaluateAuxiliary('42', ScriptContext(host: Host())),
          42,
        );
      } finally {
        await session.close();
      }
    },
  );
  late V8Runtime runtime;
  setUp(() => runtime = V8Runtime());
  tearDown(() => runtime.close());
  test('actual pinned V8 executes JavaScript', () async {
    expect(runtime.version, equals('15.4.80.25'));
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
  test(
    'same-context synchronous await assimilates Promise and thenable',
    () async {
      expect(
        await runtime.evaluate(
          '''globalThis.sameVm = {count: 0};
        const first = __sourceAwaitSync(Promise.resolve(20).then(value => {
          sameVm.count++; return value + 1;
        }));
        const second = __sourceAwaitSync({then(resolve) {resolve(first * 2)}});
        return [second, sameVm.count, __sourceAwaitSync("string")];''',
          ScriptContext(host: Host()),
        ),
        [42, 1, 'string'],
      );
    },
  );
  test(
    'same-context await services async host and preserves sync strings',
    () async {
      expect(
        await runtime.evaluate(
          '''globalThis.sameVm = "same";
        const value = __sourceAwaitSync((async () => {
          const one = await source.net.get("async");
          return one + ":" + await source.net.get(sameVm);
        })());
        const syncString = __sourceHostSync("net.get", ["sync"]);
        return [value + ":" + syncString, typeof syncString, sameVm];''',
          ScriptContext(host: Host()),
        ),
        ['async!:same!:sync!', 'string', 'same'],
      );
    },
  );
  test(
    'await preserves root Promise and late concurrent host responses',
    () async {
      expect(
        await runtime.evaluate(
          '''const late = source.net.get("late");
        const immediate = __sourceAwaitSync(source.net.get("immediate"));
        return immediate + ":" + await late;''',
          ScriptContext(host: ConcurrentHost()),
        ),
        'immediate!:late!',
      );
    },
  );
  test('await rejects without replacing the original JS exception', () async {
    expect(
      await runtime.evaluate(
        '''try { __sourceAwaitSync(Promise.reject(new Error("original"))); }
        catch (error) { return error.message; }''',
        ScriptContext(host: Host()),
      ),
      'original',
    );
  });
  test(
    'pending synchronous await inside microtask rejects and VM recovers',
    () async {
      final session = V8Runtime(persistent: true);
      try {
        await expectLater(
          session.evaluate(
            'Promise.resolve().then(() => __sourceAwaitSync(Promise.resolve(42).then(x => x)))',
            ScriptContext(host: Host()),
          ),
          throwsA(
            isA<EngineException>().having(
              (error) => error.message,
              'message',
              contains('inside a microtask'),
            ),
          ),
        );
        expect(await session.evaluate('42', ScriptContext(host: Host())), 42);
      } finally {
        await session.close();
      }
    },
  );
  test('same-context await keeps original watchdog deadline', () async {
    await expectLater(
      runtime.evaluate(
        '''const started = Date.now();
        while (Date.now() - started < 50) {}
        __sourceAwaitSync(new Promise(() => {}));''',
        ScriptContext(host: Host(), timeout: const Duration(milliseconds: 80)),
      ),
      throwsA(
        isA<EngineException>().having(
          (error) => error.code,
          'code',
          'script_timeout',
        ),
      ),
    );
  });
  test('cancellation interrupts same-context await', () async {
    final token = CancellationToken();
    final pending = runtime.evaluate(
      '__sourceAwaitSync(new Promise(() => {}))',
      ScriptContext(host: Host()),
      cancellation: token,
    );
    Timer(const Duration(milliseconds: 60), token.cancel);
    await expectLater(
      pending,
      throwsA(
        isA<EngineException>().having(
          (error) => error.code,
          'code',
          'cancelled',
        ),
      ),
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
      if (n.isEven) {
        token.cancel();
      } else {
        Timer(const Duration(milliseconds: 1), token.cancel);
      }
      await expectLater(
        pending,
        throwsA(
          isA<EngineException>().having((e) => e.code, 'code', 'cancelled'),
        ),
      );
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
  test(
    'compile-only parses original script without running loop or host calls',
    () async {
      expect(
        await runtime.checkSyntax(
          'globalThis.sideEffect = true; while(true) {}',
        ),
        isNull,
      );
      expect(
        await runtime.checkSyntax('__sourceHostSync("forbidden", []);'),
        isNull,
      );
      final error = await runtime.checkSyntax(
        'const ok = 1;\nconst broken = ;',
      );
      expect(error, isNotNull);
      expect(error!.lineNumber, 2);
      expect(error.columnNumber, greaterThan(0));
      expect(error.message, contains('SyntaxError'));
    },
  );
  test(
    'retained source context preserves declarations and runs library once',
    () async {
      final session = V8Runtime(persistent: true);
      try {
        const library =
            'globalThis.libraryCount=(globalThis.libraryCount||0)+1;';
        expect(
          await session.evaluateAuxiliary(
            'var shared=41; shared',
            ScriptContext(host: Host()),
            prelude: library,
          ),
          41,
        );
        expect(
          await session.evaluateAuxiliary(
            '++shared',
            ScriptContext(host: Host()),
            prelude: library,
          ),
          42,
        );
        expect(
          await session.evaluateAuxiliary(
            'libraryCount',
            ScriptContext(host: Host()),
            prelude: library,
          ),
          1,
        );
      } finally {
        await session.close();
      }
    },
  );
  test(
    'retained VM survives cancellation without losing source globals',
    () async {
      final session = V8Runtime(persistent: true);
      try {
        await session.evaluateAuxiliary(
          'globalThis.saved=42',
          ScriptContext(host: Host()),
        );
        final token = CancellationToken();
        final pending = session.evaluateAuxiliary(
          'while(true) {}',
          ScriptContext(host: Host()),
          cancellation: token,
        );
        Timer(const Duration(milliseconds: 40), token.cancel);
        await expectLater(
          pending,
          throwsA(
            isA<EngineException>().having((e) => e.code, 'code', 'cancelled'),
          ),
        );
        expect(
          await session.evaluateAuxiliary('saved', ScriptContext(host: Host())),
          42,
        );
      } finally {
        await session.close();
      }
    },
  );
  test('source sessions isolate globals and removed task bindings', () async {
    final first = V8Runtime(persistent: true),
        second = V8Runtime(persistent: true);
    try {
      await first.evaluateAuxiliary(
        'globalThis.onlyFirst=42; input',
        ScriptContext(host: Host(), variables: {'input': 7}),
      );
      expect(
        await second.evaluateAuxiliary(
          'typeof onlyFirst',
          ScriptContext(host: Host()),
        ),
        'undefined',
      );
      expect(
        await first.evaluateAuxiliary(
          'typeof input',
          ScriptContext(host: Host()),
        ),
        'undefined',
      );
    } finally {
      await first.close();
      await second.close();
    }
  });
  test(
    'JSON bindings cannot override native execution mode or source host',
    () async {
      for (final key in ['__sv8Protocol', '__sourceHost', 'source', 'java']) {
        await expectLater(
          runtime.evaluateAuxiliary(
            '42',
            ScriptContext(host: Host(), variables: {key: 'syntax'}),
          ),
          throwsA(
            isA<EngineException>().having(
              (e) => e.code,
              'code',
              'invalid_bindings',
            ),
          ),
        );
      }
    },
  );
}
