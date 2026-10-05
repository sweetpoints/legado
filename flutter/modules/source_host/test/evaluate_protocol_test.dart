import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_host/source_host.dart';
import 'package:source_host/session_store.dart';

class _Store implements SourceSessionStore {
  final values = <String, Map<String, Object?>>{};
  int reads = 0;
  int writes = 0;
  @override
  Future<Map<String, Object?>?> read(String id, bool legacy) async {
    reads++;
    return values['$id:$legacy'];
  }

  @override
  Future<void> write(
    String id,
    bool legacy,
    Map<String, Object?> session,
  ) async {
    writes++;
    values['$id:$legacy'] = session;
  }
}

class _Runtime implements ScriptRuntime {
  final contexts = <ScriptContext>[];
  final codes = <String>[];
  final entered = Completer<void>();
  bool block = false;
  bool fail = false;
  bool closed = false;
  int closes = 0;
  @override
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  }) async {
    contexts.add(context);
    codes.add(code);
    if (!entered.isCompleted) entered.complete();
    if (block) {
      await cancellation!.whenCancelled;
      cancellation.throwIfCancelled();
    }
    if (fail) throw const EngineException('script_error', 'fixture failure');
    if (context.variables.containsKey('save')) {
      await context.host.call('variables.put', [
        'saved',
        context.variables['save'],
      ]);
    }
    return [
      {
        'value':
            await context.host.call('variables.get', ['saved']) ??
            context.variables['result'],
      },
    ];
  }

  @override
  Future<void> close() async {
    closed = true;
    closes++;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final legacy = {
    'bookSourceUrl': 'https://source.example',
    'bookSourceName': 'Legacy',
    'loginUrl': 'unsupported stage feature ignored for auxiliary script',
  };
  final modern = {
    'schemaVersion': 1,
    'id': 'modern',
    'name': 'Modern',
    'baseUrl': 'https://source.example',
    'script': 'async function search(input){return [{value:null}]}',
  };
  MethodCall evaluate(
    String id,
    Object source, {
    String script = 'java.get("saved")',
    Map<Object?, Object?> bindings = const {},
  }) => MethodCall('evaluate', {
    'protocolVersion': 1,
    'taskId': id,
    'sourceJson': jsonEncode(source),
    'script': script,
    'bindings': bindings,
  });
  test('legacy evaluate passes JSON bindings and uses legacy runtime identity without importing stage issues', () async {
    final runtime = _Runtime();
    final store = _Store();
    final created = <SourceDefinition>[];
    final host = SourceHost((source) {
      created.add(source);
      return SourceEngine(runtime: runtime);
    }, sessionStore: store);
    try {
      final result = await host.handle(
        evaluate(
          'aux',
          legacy,
          script: 'result',
          bindings: {
            'result': {
              'items': [1, true, null],
            },
            'taskId': 'forged',
          },
        ),
      );
      expect(result, {
        'value': {
          'items': [1, true, null],
        },
      });
      expect(created.single.metadata['legacy'], true);
      expect(created.single.stages, isEmpty);
      expect(runtime.contexts.single.variables['taskId'], 'aux');
      expect(
        runtime.contexts.single.variables['baseUrl'],
        'https://source.example',
      );
      expect(runtime.codes.single, contains('await eval("result")'));
      expect(store.values.keys, ['https://source.example:true']);
      const StandardMethodCodec().encodeSuccessEnvelope(result);
    } finally {
      await host.close();
    }
  });
  test('auxiliary script does not replace cached configuration or lose variable state', () async {
    final runtimes = <_Runtime>[];
    final host = SourceHost((source) {
      final runtime = _Runtime();
      runtimes.add(runtime);
      return SourceEngine(runtime: runtime);
    });
    Future<Object?> execute(String task, Map<String, Object?> input) =>
        host.handle(
          MethodCall('execute', {
            'protocolVersion': 1,
            'taskId': task,
            'sourceJson': jsonEncode(modern),
            'operation': 'search',
            'input': input,
          }),
        );
    try {
      await execute('first', {'save': 'before'});
      expect(
        await host.handle(
          evaluate(
            'aux',
            modern,
            script: 'source.variables.put("saved","after")',
            bindings: {'save': 'after'},
          ),
        ),
        {'value': 'after'},
      );
      expect(await execute('last', {}), [
        {'value': 'after'},
      ]);
      expect(runtimes, hasLength(1));
      expect(runtimes.single.closed, false);
    } finally {
      await host.close();
    }
  });
  test(
    'evaluate cancellation and duplicate task use existing task protocol',
    () async {
      final runtime = _Runtime()..block = true;
      final host = SourceHost((_) => SourceEngine(runtime: runtime));
      final pending = host.handle(evaluate('pending', modern));
      await runtime.entered.future;
      await expectLater(
        host.handle(evaluate('pending', modern)),
        throwsA(
          isA<PlatformException>().having(
            (e) => e.code,
            'code',
            'duplicate_task',
          ),
        ),
      );
      final cancelled = expectLater(
        pending,
        throwsA(
          isA<PlatformException>().having((e) => e.code, 'code', 'cancelled'),
        ),
      );
      await host.handle(const MethodCall('cancel', {'taskId': 'pending'}));
      await cancelled;
      await host.close();
    },
  );
  test('invalid args, unsafe legacy identity and jsLib fail without creating engine', () async {
    final host = SourceHost((_) => throw StateError('must not create engine'));
    try {
      for (final raw in [
        legacy..['jsLib'] = 'function oldLibrary(){}',
        {
          'bookSourceUrl': 'non-http-id',
          'searchUrl': 'https://inferred.example',
        },
      ]) {
        await expectLater(
          host.handle(evaluate('bad', raw)),
          throwsA(
            isA<PlatformException>().having(
              (e) => e.code,
              'code',
              'legacy_requires_migration',
            ),
          ),
        );
      }
      for (final args in [
        {'protocolVersion': 2},
        {'protocolVersion': 1, 'taskId': 42},
        {
          'protocolVersion': 1,
          'taskId': 'bad',
          'sourceJson': jsonEncode(modern),
          'script': '1',
          'bindings': {'x': Object()},
        },
      ]) {
        await expectLater(
          host.handle(MethodCall('evaluate', args)),
          throwsA(
            isA<PlatformException>().having(
              (e) => e.code,
              'code',
              'invalid_request',
            ),
          ),
        );
      }
    } finally {
      await host.close();
    }
  });
  test('engine script errors remain explicit protocol failures', () async {
    final host = SourceHost(
      (_) => SourceEngine(runtime: _Runtime()..fail = true),
    );
    try {
      await expectLater(
        host.handle(evaluate('failure', modern)),
        throwsA(
          isA<PlatformException>().having(
            (e) => e.code,
            'code',
            'script_error',
          ),
        ),
      );
    } finally {
      await host.close();
    }
  });
  MethodCall ephemeral(String task, {Object? flag = true}) =>
      MethodCall('evaluate', {
        'protocolVersion': 1,
        'taskId': task,
        'sourceJson': jsonEncode(modern),
        'script': 'result',
        'bindings': {'result': 'configuration'},
        'ephemeral': flag,
      });
  test(
    'ephemeral evaluate never persists or caches and closes each engine once',
    () async {
      final store = _Store();
      final runtimes = <_Runtime>[];
      final host = SourceHost((_) {
        final r = _Runtime();
        runtimes.add(r);
        return SourceEngine(runtime: r);
      }, sessionStore: store);
      expect(await host.handle(ephemeral('one')), {'value': 'configuration'});
      expect(await host.handle(ephemeral('two')), {'value': 'configuration'});
      expect(runtimes, hasLength(2));
      expect(runtimes.map((r) => r.closes), [1, 1]);
      expect(store.reads, 0);
      expect(store.writes, 0);
      await host.close();
      expect(runtimes.map((r) => r.closes), [1, 1]);
    },
  );
  test('ephemeral script failure and cancellation always close engine without session writes', () async {
    final store = _Store();
    final failed = _Runtime()..fail = true;
    final cancelled = _Runtime()..block = true;
    var count = 0;
    final host = SourceHost(
      (_) => SourceEngine(runtime: count++ == 0 ? failed : cancelled),
      sessionStore: store,
    );
    await expectLater(
      host.handle(ephemeral('failure')),
      throwsA(
        isA<PlatformException>().having((e) => e.code, 'code', 'script_error'),
      ),
    );
    expect(failed.closes, 1);
    final pending = host.handle(ephemeral('cancelled'));
    await cancelled.entered.future;
    final assertion = expectLater(
      pending,
      throwsA(
        isA<PlatformException>().having((e) => e.code, 'code', 'cancelled'),
      ),
    );
    await host.handle(const MethodCall('cancel', {'taskId': 'cancelled'}));
    await assertion;
    expect(cancelled.closes, 1);
    expect(store.reads, 0);
    expect(store.writes, 0);
    await host.close();
    expect(failed.closes, 1);
    expect(cancelled.closes, 1);
  });
  test('ephemeral must be strictly boolean when present', () async {
    final host = SourceHost((_) => throw StateError('must not create engine'));
    for (final flag in [null, 1, 'true']) {
      await expectLater(
        host.handle(ephemeral('invalid', flag: flag)),
        throwsA(
          isA<PlatformException>().having(
            (e) => e.code,
            'code',
            'invalid_request',
          ),
        ),
      );
    }
    await host.close();
  });
}
