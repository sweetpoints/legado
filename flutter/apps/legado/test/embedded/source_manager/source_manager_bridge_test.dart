import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/embedded/source_manager/source_manager_bridge.dart';
import 'package:legado_app/embedded/source_manager/source_manager_state.dart';

import 'manager_fixture.dart';

void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('legado/source_manager');
  const events = MethodChannel('legado/source_manager/state');
  var listening = false;
  Future<void> emit(Map<String, Object?> state) =>
      binding.defaultBinaryMessenger.handlePlatformMessage(
        events.name,
        const StandardMethodCodec().encodeSuccessEnvelope(jsonEncode(state)),
        (_) {},
      );
  setUp(() {
    binding.defaultBinaryMessenger.setMockMethodCallHandler(events, (
      call,
    ) async {
      listening = call.method == 'listen';
      return null;
    });
  });
  tearDown(() {
    binding.defaultBinaryMessenger.setMockMethodCallHandler(events, null);
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });
  test('snapshot preserves hidden selections, badges, and all seven dialog variants', () {
    final json = managerState()..['selected'] = ['a', 'hidden'];
    final state = SourceManagerSnapshot.fromJson(json);
    expect(state.selectedCount, 1);
    expect(state.selected.contains('hidden'), true);
    expect(state.rows.first.hasJs, true);
    expect(state.rows.last.hasJs, false);
    for (final dialog in SourceManagerSnapshot.dialogs) {
      expect(
        SourceManagerSnapshot.fromJson({...json, 'dialog': dialog}).dialog,
        dialog,
      );
    }
    expect(
      () => SourceManagerSnapshot.fromJson({...json, 'schemaVersion': 2}),
      throwsFormatException,
    );
  });
  test(
    'commands serialize every draft and continue after a failed operation',
    () async {
      final first = Completer<void>();
      final started = Completer<void>();
      final received = <Map>[];
      binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
        call,
      ) async {
        expect(call.method, 'command');
        final args = call.arguments as Map;
        received.add(args);
        if (received.length == 1) {
          started.complete();
          await first.future;
        }
        return null;
      });
      final bridge = SourceManagerBridge();
      final a = bridge.command('draft', {'value': 'first'});
      final failed = expectLater(a, throwsA(isA<PlatformException>()));
      final b = bridge.command('draft', {'value': 'second'});
      final confirm = bridge.command('confirm');
      await started.future;
      expect(received.length, 1);
      first.completeError(PlatformException(code: 'write_failed'));
      await failed;
      await b;
      await confirm;
      expect(received.map((e) => e['op']), ['draft', 'draft', 'confirm']);
      expect(received.take(2).map((e) => e['value']), ['first', 'second']);
      await bridge.close();
      await expectLater(bridge.command('confirm'), throwsStateError);
      expect(received.length, 3);
    },
  );
  test('initial response cannot replace a newer event and disposal cancels the stream', () async {
    final first = Completer<String>();
    final started = Completer<void>();
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      expect(call.method, 'snapshot');
      started.complete();
      return first.future;
    });
    final bridge = SourceManagerBridge();
    final received = <SourceManagerSnapshot>[];
    final done = Completer<void>();
    bridge.snapshots().listen(received.add, onDone: done.complete);
    await started.future;
    await emit(managerState()..['query'] = 'new');
    first.complete(jsonEncode(managerState()..['query'] = 'old'));
    await Future<void>.delayed(Duration.zero);
    expect(received.map((e) => e.query), ['new']);
    await bridge.close();
    await done.future;
    expect(listening, false);
  });
  test(
    'event errors and stream closure cannot be overwritten by a stale snapshot',
    () async {
      final first = Completer<String>();
      final started = Completer<void>();
      binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
        call,
      ) async {
        started.complete();
        return first.future;
      });
      final bridge = SourceManagerBridge();
      final received = <SourceManagerSnapshot>[];
      final errors = <Object>[];
      final done = Completer<void>();
      bridge.snapshots().listen(
        received.add,
        onError: errors.add,
        onDone: done.complete,
      );
      await started.future;
      await binding.defaultBinaryMessenger.handlePlatformMessage(
        events.name,
        const StandardMethodCodec().encodeErrorEnvelope(code: 'native_error'),
        (_) {},
      );
      first.complete(jsonEncode(managerState()));
      await Future<void>.delayed(Duration.zero);
      expect(errors.single, isA<PlatformException>());
      expect(received, isEmpty);
      await binding.defaultBinaryMessenger.handlePlatformMessage(
        events.name,
        null,
        (_) {},
      );
      await done.future;
      expect(errors.last, isA<StateError>());
      expect(listening, false);
      await bridge.close();
    },
  );
  test(
    'closing rejects queued commands without invoking native a second time',
    () async {
      final first = Completer<void>();
      final started = Completer<void>();
      var count = 0;
      binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
        call,
      ) async {
        count++;
        started.complete();
        await first.future;
        return null;
      });
      final bridge = SourceManagerBridge();
      final active = bridge.command('draft', {'value': 'last'});
      final queued = bridge.command('confirm');
      final rejected = expectLater(queued, throwsStateError);
      await started.future;
      await bridge.close();
      first.complete();
      await active;
      await rejected;
      expect(count, 1);
    },
  );
}
