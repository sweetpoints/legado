import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/embedded/source_editor/source_editor_bridge.dart';
import 'package:legado_app/embedded/source_editor/source_editor_state.dart';

import 'editor_fixture.dart';

void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('legado/source_editor');
  const events = MethodChannel('legado/source_editor/state');
  tearDown(() {
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
    binding.defaultBinaryMessenger.setMockMethodCallHandler(events, null);
  });
  test('strict snapshots reject unknown versions, duplicate fields and invalid cursor', () {
    final m = editorFixture();
    expect(SourceEditorSnapshot.fromMap(m).document!.tabs.length, 7);
    expect(
      () => SourceEditorSnapshot.fromMap({...m, 'schemaVersion': 2}),
      throwsFormatException,
    );
    final doc = m['document'] as Map;
    final form = doc['form'] as Map;
    final tabs = form['tabs'] as List;
    final field = (tabs[0] as List).first as Map;
    field['selectionEnd'] = 100;
    expect(() => SourceEditorSnapshot.fromMap(m), throwsFormatException);
    field['selectionEnd'] = 0;
    (tabs[0] as List).add(field);
    expect(() => SourceEditorSnapshot.fromMap(m), throwsFormatException);
  });
  test(
    'orders every field update before save, recovers queue after failure',
    () async {
      final calls = <Map>[];
      final barrier = Completer<void>();
      binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
        call,
      ) async {
        final args = call.arguments as Map;
        calls.add(args);
        if (calls.length == 1) {
          await barrier.future;
          throw PlatformException(code: 'rejected');
        }
        return null;
      });
      final bridge = SourceEditorBridge();
      final first = bridge.command('field', {'value': 'one'});
      final firstError = expectLater(first, throwsA(isA<PlatformException>()));
      final second = bridge.command('field', {'value': 'two'});
      final save = bridge.command('save', {'action': 'FINISH'});
      await Future<void>.delayed(Duration.zero);
      expect(calls.length, 1);
      barrier.complete();
      await firstError;
      await second;
      await save;
      expect(calls.map((c) => c['op']), ['field', 'field', 'save']);
      expect(calls[1]['value'], 'two');
      await bridge.close();
    },
  );
  test('freezes nested arguments before queued platform invocation', () async {
    final barrier = Completer<void>();
    final calls = <Map>[];
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      calls.add(call.arguments as Map);
      if (calls.length == 1) await barrier.future;
      return null;
    });
    final bridge = SourceEditorBridge();
    final first = bridge.command('retry');
    final value = {'enabled': true};
    final next = bridge.command('options', {'value': value});
    value['enabled'] = false;
    barrier.complete();
    await first;
    await next;
    expect((calls[1]['value'] as Map)['enabled'], true);
    await bridge.close();
  });
  test(
    'subscribes first and ignores stale initial snapshot after newer event',
    () async {
      final initial = Completer<Object?>();
      binding.defaultBinaryMessenger.setMockMethodCallHandler(
        events,
        (_) async => null,
      );
      binding.defaultBinaryMessenger.setMockMethodCallHandler(
        channel,
        (_) async => initial.future,
      );
      final bridge = SourceEditorBridge();
      final received = <SourceEditorSnapshot>[];
      final sub = bridge.snapshots().listen(received.add);
      await Future<void>.delayed(Duration.zero);
      await binding.defaultBinaryMessenger.handlePlatformMessage(
        events.name,
        const StandardMethodCodec().encodeSuccessEnvelope(
          editorFixture(value: 'new', revision: 2),
        ),
        (_) {},
      );
      initial.complete(editorFixture(value: 'old'));
      await Future<void>.delayed(Duration.zero);
      expect(received.map((s) => s.document!.tabs[0][0].value), ['new']);
      await sub.cancel();
      await bridge.close();
    },
  );
  test(
    'malformed transport reports error and next valid event recovers',
    () async {
      binding.defaultBinaryMessenger.setMockMethodCallHandler(
        events,
        (_) async => null,
      );
      binding.defaultBinaryMessenger.setMockMethodCallHandler(
        channel,
        (_) async => editorFixture(),
      );
      final bridge = SourceEditorBridge();
      final received = <SourceEditorSnapshot>[];
      final errors = <Object>[];
      final sub = bridge.snapshots().listen(received.add, onError: errors.add);
      await Future<void>.delayed(Duration.zero);
      for (final value in [42, editorFixture(value: 'recovered')]) {
        await binding.defaultBinaryMessenger.handlePlatformMessage(
          events.name,
          const StandardMethodCodec().encodeSuccessEnvelope(value),
          (_) {},
        );
      }
      await Future<void>.delayed(Duration.zero);
      expect(errors.single, isA<FormatException>());
      expect(received.last.document!.tabs[0][0].value, 'recovered');
      await sub.cancel();
      await bridge.close();
    },
  );
  test('closing rejects future commands', () async {
    final bridge = SourceEditorBridge();
    await bridge.close();
    await expectLater(bridge.command('save'), throwsStateError);
  });
}
