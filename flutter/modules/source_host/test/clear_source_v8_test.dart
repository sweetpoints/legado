import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_host/main.dart' show createSourceEngine;
import 'package:source_host/source_host.dart';

class _SignalHost implements ScriptHost {
  final entered = Completer<void>();
  int queuedCalls = 0;
  @override
  Future<Object?> call(String method, List<Object?> arguments) async {
    if (method == 'fixture.entered') {
      if (!entered.isCompleted) entered.complete();
      return null;
    }
    if (method == 'fixture.queued') {
      queuedCalls++;
      return null;
    }
    throw const EngineException('unsupported_host_api', 'Fixture method');
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late SourceHost host;
  late _SignalHost signals;
  var sequence = 0;
  const owner = 'https://fixture.invalid/source';
  const counter =
      'async function search(input){globalThis.count=(globalThis.count||0)+1;return [{value:count}];}';
  setUp(() {
    signals = _SignalHost();
    host = SourceHost(
      (source) => createSourceEngine(source, platform: signals),
    );
  });
  tearDown(() => host.close());

  SourceDefinition definition(String id, bool legacy, String script) =>
      SourceDefinition(
        id: id,
        name: 'fixture',
        baseUrl: Uri.parse('https://fixture.invalid/'),
        metadata: {'legacy': legacy},
        script: script,
      );
  Future<Object?> execute({
    String id = owner,
    bool legacy = false,
    String script = counter,
    Map<String, Object?> input = const {},
  }) => host.handle(
    MethodCall('execute', {
      'protocolVersion': 1,
      'taskId': 'execute-${sequence++}',
      'sourceJson': jsonEncode(definition(id, legacy, script).toJson()),
      'operation': 'search',
      'input': input,
    }),
  );
  Future<Object?> aux(String script, {String id = owner}) => host.handle(
    MethodCall('evaluateAuxiliary', {
      'protocolVersion': 1,
      'taskId': 'aux-${sequence++}',
      'sourceId': id,
      'script': script,
      'bindings': <String, Object?>{},
      'timeoutMs': 10000,
    }),
  );
  Future<Object?> clear() => host.handle(
    MethodCall('clearSourceState', {
      'protocolVersion': 1,
      'taskId': 'clear-${sequence++}',
      'sourceId': owner,
    }),
  );
  Matcher cancelled() => throwsA(
    isA<PlatformException>().having((e) => e.code, 'code', 'cancelled'),
  );

  test('clear closes exact legacy modern and auxiliary VMs while prefix neighbour survives', () async {
    for (final legacy in [true, false]) {
      expect(await execute(legacy: legacy), [
        {'value': 1},
      ]);
      expect(await execute(legacy: legacy), [
        {'value': 2},
      ]);
    }
    expect(await aux('globalThis.saved=42; saved'), {'value': 42});
    expect(await execute(id: '$owner:other'), [
      {'value': 1},
    ]);
    await clear();
    for (final legacy in [true, false]) {
      expect(await execute(legacy: legacy), [
        {'value': 1},
      ]);
    }
    expect(await aux('typeof saved'), {'value': 'undefined'});
    expect(await execute(id: '$owner:other'), [
      {'value': 2},
    ]);
  });

  test(
    'clear cancels active Promise and queued old tasks without rebuilding them',
    () async {
      final active = aux(
        '__sourceHostSync("fixture.entered",[]);new Promise(()=>{})',
      );
      final activeCheck = expectLater(active, cancelled());
      await signals.entered.future.timeout(const Duration(seconds: 5));
      final queued = aux('globalThis.stale=42');
      final queuedCheck = expectLater(queued, cancelled());
      expect(await aux('globalThis.neighbour=7', id: '$owner:other'), {
        'value': 7,
      });
      await clear().timeout(const Duration(seconds: 3));
      await Future.wait([activeCheck, queuedCheck]);
      expect(await aux('typeof stale'), {'value': 'undefined'});
      expect(await aux('neighbour', id: '$owner:other'), {'value': 7});
    },
  );

  test(
    'clear cancels main execution and prevents queued host side effects',
    () async {
      const script =
          'async function search(input){'
          'if(input.block){__sourceHostSync("fixture.entered",[]);await new Promise(()=>{});}'
          'if(input.queued)__sourceHostSync("fixture.queued",[]);'
          'globalThis.count=(globalThis.count||0)+1;return [{value:count}];}';
      final active = execute(script: script, input: {'block': true});
      final activeCheck = expectLater(active, cancelled());
      await signals.entered.future.timeout(const Duration(seconds: 5));
      final queued = execute(script: script, input: {'queued': true});
      final queuedCheck = expectLater(queued, cancelled());
      expect(await execute(id: '$owner:other'), [
        {'value': 1},
      ]);
      await clear().timeout(const Duration(seconds: 3));
      await Future.wait([activeCheck, queuedCheck]);
      expect(signals.queuedCalls, 0);
      expect(await execute(script: script), [
        {'value': 1},
      ]);
      expect(await execute(id: '$owner:other'), [
        {'value': 2},
      ]);
    },
  );

  test('clear cancels ephemeral evaluation by source identity', () async {
    final pending = host.handle(
      MethodCall('evaluate', {
        'protocolVersion': 1,
        'taskId': 'ephemeral-${sequence++}',
        'ephemeral': true,
        'sourceJson': jsonEncode(definition(owner, false, '').toJson()),
        'script': '__sourceHostSync("fixture.entered",[]);new Promise(()=>{})',
        'bindings': <String, Object?>{},
      }),
    );
    final checked = expectLater(pending, cancelled());
    await signals.entered.future.timeout(const Duration(seconds: 5));
    await clear().timeout(const Duration(seconds: 3));
    await checked;
    expect(await execute(), [
      {'value': 1},
    ]);
  });
}
