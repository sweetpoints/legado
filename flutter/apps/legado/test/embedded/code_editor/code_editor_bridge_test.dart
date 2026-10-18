import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/embedded/code_editor/code_editor_bridge.dart';
import 'package:legado_app/embedded/code_editor/code_editor_state.dart';

import 'code_fixture.dart';

void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('legado/code_editor');
  const events = MethodChannel('legado/code_editor/state');
  tearDown(() {
    for (final c in [channel, events]) {
      binding.defaultBinaryMessenger.setMockMethodCallHandler(c, null);
    }
  });
  test(
    'strict chrome snapshot carries no document model and validates selections',
    () {
      final m = codeFixture();
      final state = CodeEditorSnapshot.fromMap(m);
      expect(state.enabled, true);
      expect(state.generation, 1);
      expect(
        () => CodeEditorSnapshot.fromMap({...m, 'schemaVersion': 2}),
        throwsFormatException,
      );
      expect(
        () => CodeEditorSnapshot.fromMap({...m, 'generation': 0}),
        throwsFormatException,
      );
      (m['search'] as Map)['queryEnd'] = 1;
      expect(() => CodeEditorSnapshot.fromMap(m), throwsFormatException);
    },
  );
  test('search contract retains ignoreCase and all UTF16 cursor offsets', () {
    const search = CodeEditorSearch(
      query: '😀a',
      queryStart: 2,
      queryEnd: 3,
      replacement: 'x',
      replacementStart: 1,
      replacementEnd: 1,
      ignoreCase: true,
    );
    expect(CodeEditorSearch.fromMap(search.toMap()), search);
  });
  test(
    'orders rapid search before save and freezes nested search maps',
    () async {
      final barrier = Completer<void>();
      final calls = <Map>[];
      binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
        call,
      ) async {
        calls.add(call.arguments as Map);
        if (calls.length == 1) await barrier.future;
        return null;
      });
      final bridge = CodeEditorBridge();
      final value = const CodeEditorSearch(
        query: 'a',
        queryStart: 1,
        queryEnd: 1,
      ).toMap();
      final first = bridge.command('search', {'value': value});
      final save = bridge.command('action', {'name': 'SAVE'});
      value['query'] = 'mutated';
      await Future<void>.delayed(Duration.zero);
      expect(calls.length, 1);
      barrier.complete();
      await first;
      await save;
      expect((calls.first['value'] as Map)['query'], 'a');
      expect(calls.last['name'], 'SAVE');
      await bridge.close();
    },
  );
  test(
    'rejected search blocks queued save and replace until corrected search',
    () async {
      final barrier = Completer<void>();
      final calls = <Map>[];
      var reject = true;
      binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
        call,
      ) async {
        final args = call.arguments as Map;
        calls.add(args);
        if (args['op'] == 'search' && reject) {
          await barrier.future;
          throw PlatformException(code: 'invalid_request');
        }
        return null;
      });
      final bridge = CodeEditorBridge();
      final failed = expectLater(
        bridge.command('search', {'value': const CodeEditorSearch().toMap()}),
        throwsA(isA<PlatformException>()),
      );
      final blocked = expectLater(
        bridge.command('action', {'name': 'SAVE'}),
        throwsA(isA<CodeEditorWriteBlocked>()),
      );
      barrier.complete();
      await failed;
      await blocked;
      expect(calls.length, 1);
      await expectLater(
        bridge.command('action', {'name': 'REPLACE_ALL'}),
        throwsA(isA<CodeEditorWriteBlocked>()),
      );
      reject = false;
      await bridge.command('search', {
        'value': const CodeEditorSearch(query: 'corrected').toMap(),
      });
      await bridge.command('action', {'name': 'SAVE'});
      expect(bridge.hasRejectedSearch, false);
      expect(calls.last['name'], 'SAVE');
      await bridge.close();
    },
  );
  test('retry explicitly recovers rejected search', () async {
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if ((call.arguments as Map)['op'] == 'search') {
        throw PlatformException(code: 'invalid_request');
      }
      return null;
    });
    final bridge = CodeEditorBridge();
    await expectLater(
      bridge.command('search'),
      throwsA(isA<PlatformException>()),
    );
    await bridge.command('retry');
    await bridge.command('action', {'name': 'SAVE'});
    expect(bridge.hasRejectedSearch, false);
    await bridge.close();
  });
  test('event subscription wins over stale initial snapshot', () async {
    final initial = Completer<Object?>();
    binding.defaultBinaryMessenger.setMockMethodCallHandler(
      events,
      (_) async => null,
    );
    binding.defaultBinaryMessenger.setMockMethodCallHandler(
      channel,
      (_) async => initial.future,
    );
    final bridge = CodeEditorBridge();
    final states = <CodeEditorSnapshot>[];
    final sub = bridge.snapshots().listen(states.add);
    await Future<void>.delayed(Duration.zero);
    await binding.defaultBinaryMessenger.handlePlatformMessage(
      events.name,
      const StandardMethodCodec().encodeSuccessEnvelope(
        codeFixture(generation: 2),
      ),
      (_) {},
    );
    initial.complete(codeFixture());
    await Future<void>.delayed(Duration.zero);
    expect(states.map((s) => s.generation), [2]);
    await sub.cancel();
    await bridge.close();
  });
  test(
    'malformed event surfaces error then valid chrome can recover',
    () async {
      binding.defaultBinaryMessenger.setMockMethodCallHandler(
        events,
        (_) async => null,
      );
      binding.defaultBinaryMessenger.setMockMethodCallHandler(
        channel,
        (_) async => codeFixture(),
      );
      final bridge = CodeEditorBridge();
      final errors = <Object>[];
      final states = <CodeEditorSnapshot>[];
      final sub = bridge.snapshots().listen(states.add, onError: errors.add);
      await Future<void>.delayed(Duration.zero);
      for (final value in [42, codeFixture(generation: 2)]) {
        await binding.defaultBinaryMessenger.handlePlatformMessage(
          events.name,
          const StandardMethodCodec().encodeSuccessEnvelope(value),
          (_) {},
        );
      }
      await Future<void>.delayed(Duration.zero);
      expect(errors.single, isA<FormatException>());
      expect(states.last.generation, 2);
      await sub.cancel();
      await bridge.close();
    },
  );
  test('closed transport rejects commands', () async {
    final bridge = CodeEditorBridge();
    await bridge.close();
    await expectLater(bridge.command('exit'), throwsStateError);
  });
}
