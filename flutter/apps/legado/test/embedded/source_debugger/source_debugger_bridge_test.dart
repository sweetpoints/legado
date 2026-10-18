import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/embedded/source_debugger/source_debugger_bridge.dart';
import 'package:legado_app/embedded/source_debugger/source_debugger_state.dart';

import 'debugger_fixture.dart';

void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('legado/source_debugger');
  const events = MethodChannel('legado/source_debugger/state');
  late Map<String, Object?> native;
  late List<Map> calls;
  Future<void> Function(Map)? handler;
  setUp(() {
    native = debuggerFixture();
    calls = [];
    handler = null;
    binding.defaultBinaryMessenger.setMockMethodCallHandler(
      events,
      (_) async => null,
    );
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if (call.method == 'snapshot') return native;
      final args = call.arguments as Map;
      calls.add(args);
      await handler?.call(args);
      return null;
    });
  });
  tearDown(() {
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
    binding.defaultBinaryMessenger.setMockMethodCallHandler(events, null);
  });
  Future<void> emit(Map<String, Object?> value) async {
    await binding.defaultBinaryMessenger.handlePlatformMessage(
      events.name,
      const StandardMethodCodec().encodeSuccessEnvelope(value),
      (_) {},
    );
    await Future<void>.delayed(Duration.zero);
  }

  test(
    'snapshot validates kind, schema, bounds, stages and UTF16 selection',
    () {
      expect(SourceDebuggerSnapshot.fromMap(native).enabled, true);
      for (final patch in <Map<String, Object?>>[
        {'schemaVersion': 2},
        {'generation': 0},
        {'kind': 'other'},
        {'queryStart': -1},
        {'queryEnd': 1},
        {'output': 'x' * 20001},
        {'selectedSort': 3},
        {'issue': 'other'},
        {
          'htmlStages': ['list'],
        },
      ]) {
        expect(
          () => SourceDebuggerSnapshot.fromMap({...native, ...patch}),
          throwsFormatException,
        );
      }
      expect(
        SourceDebuggerSnapshot.fromMap({
          ...native,
          'query': '😀a',
          'queryStart': 2,
          'queryEnd': 3,
        }).queryEnd,
        3,
      );
      expect(
        SourceDebuggerSnapshot.fromMap(debuggerFixture(kind: 'rss')).stages,
        ['list', 'content'],
      );
    },
  );
  test('query then run are serial and argument maps frozen', () async {
    final bridge = SourceDebuggerBridge();
    final subscription = bridge.snapshots().listen((_) {});
    await Future<void>.delayed(Duration.zero);
    final barrier = Completer<void>();
    handler = (args) async {
      if (args['op'] == 'query') await barrier.future;
    };
    final args = <String, Object?>{'text': 'new', 'start': 3, 'end': 3};
    final query = bridge.command('query', 1, args);
    final run = bridge.command('run', 1);
    args['text'] = 'mutated';
    await Future<void>.delayed(Duration.zero);
    expect(calls.length, 1);
    barrier.complete();
    await query;
    await run;
    expect(calls.map((c) => c['op']), ['query', 'run']);
    expect(calls.first['text'], 'new');
    expect(calls.every((c) => c['generation'] == 1), true);
    await subscription.cancel();
    await bridge.close();
  });
  test('a rejected query prevents queued run until corrected input', () async {
    final bridge = SourceDebuggerBridge();
    final subscription = bridge.snapshots().listen((_) {});
    await Future<void>.delayed(Duration.zero);
    handler = (args) async {
      if (args['op'] == 'query') throw PlatformException(code: 'rejected');
    };
    final query = expectLater(
      bridge.command('query', 1, {'text': 'bad', 'start': 3, 'end': 3}),
      throwsA(isA<PlatformException>()),
    );
    final run = expectLater(bridge.command('run', 1), throwsStateError);
    await query;
    await run;
    expect(calls.length, 1);
    handler = null;
    await bridge.command('query', 1, {'text': 'ok', 'start': 2, 'end': 2});
    await bridge.command('run', 1);
    expect(calls.last['op'], 'run');
    await subscription.cancel();
    await bridge.close();
  });
  test('generation changes invalidate queued old commands and close rejects new commands', () async {
    final bridge = SourceDebuggerBridge();
    final subscription = bridge.snapshots().listen((_) {});
    await Future<void>.delayed(Duration.zero);
    await emit({...native, 'generation': 2});
    await expectLater(bridge.command('run', 1), throwsStateError);
    await bridge.command('run', 2);
    expect(calls.single['generation'], 2);
    await subscription.cancel();
    await bridge.close();
    await expectLater(bridge.command('run', 2), throwsStateError);
  });
  test('a stream event wins over a late bootstrap snapshot', () async {
    final pending = Completer<Object?>();
    binding.defaultBinaryMessenger.setMockMethodCallHandler(
      channel,
      (call) => pending.future,
    );
    final bridge = SourceDebuggerBridge();
    final snapshots = <SourceDebuggerSnapshot>[];
    final subscription = bridge.snapshots().listen(snapshots.add);
    await Future<void>.delayed(Duration.zero);
    await emit({...native, 'generation': 2});
    pending.complete(native);
    await Future<void>.delayed(Duration.zero);
    expect(snapshots.map((s) => s.generation), [2]);
    await subscription.cancel();
    await bridge.close();
  });
}
