import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:legado_app/embedded/dictionary_manager/dictionary_manager_bridge.dart';
import 'package:legado_app/embedded/dictionary_manager/dictionary_manager_page.dart';

Map<String, Object?> fixture({
  int revision = 1,
  String binding = 'owner',
  String input = '',
  bool online = false,
}) => {
  'schemaVersion': 1,
  'binding': binding,
  'revision': revision,
  'loading': false,
  'busy': false,
  'error': false,
  'rules': [
    {'name': 'A', 'enabled': true},
    {'name': 'B', 'enabled': false},
  ],
  'selected': <String>[],
  'deleteName': null,
  'online': online,
  'onlineInput': input,
  'history': ['https://rules.invalid/dict'],
  'exportUrl': null,
  'exportSummary': '',
  'canEncode': false,
  'passphrase': null,
};
void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('legado/dictionary_manager');
  const events = MethodChannel('legado/dictionary_manager/state');
  setUp(
    () => binding.defaultBinaryMessenger.setMockMethodCallHandler(
      events,
      (_) async => null,
    ),
  );
  tearDown(() {
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
    binding.defaultBinaryMessenger.setMockMethodCallHandler(events, null);
  });
  test(
    'typed projection rejects duplicate identities and foreign selection',
    () {
      expect(DictionaryManagerSnapshot(fixture()).rules.last.enabled, false);
      expect(
        () => DictionaryManagerSnapshot({
          ...fixture(),
          'selected': ['absent'],
        }),
        throwsFormatException,
      );
      expect(
        () => DictionaryManagerSnapshot({
          ...fixture(),
          'rules': [
            {'name': 'A', 'enabled': true},
            {'name': 'A', 'enabled': true},
          ],
        }),
        throwsFormatException,
      );
    },
  );
  test(
    'failed input blocks dependent import until matching acknowledged retry',
    () async {
      var reject = true;
      var revision = 1;
      final calls = <String>[];
      binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
        call,
      ) async {
        if (call.method == 'snapshot') return fixture();
        final a = call.arguments as Map;
        calls.add(a['op'] as String);
        if (reject) throw PlatformException(code: 'busy');
        return fixture(
          revision: ++revision,
          input: a['value'] as String? ?? 'URL',
        );
      });
      final bridge = DictionaryManagerBridge();
      await bridge.start();
      await expectLater(
        bridge.command('input', {'value': 'URL'}),
        throwsA(isA<PlatformException>()),
      );
      await expectLater(bridge.command('confirmOnline'), throwsStateError);
      reject = false;
      await bridge.command('input', {'value': 'URL'});
      await bridge.command('confirmOnline');
      expect(calls, ['input', 'input', 'confirmOnline']);
      await bridge.close();
    },
  );
  test('mismatched input ACK cannot unblock import', () async {
    var revision = 1;
    binding.defaultBinaryMessenger.setMockMethodCallHandler(
      channel,
      (call) async => fixture(revision: revision++),
    );
    final bridge = DictionaryManagerBridge();
    await bridge.start();
    await expectLater(
      bridge.command('input', {'value': 'private'}),
      throwsStateError,
    );
    await expectLater(bridge.command('confirmOnline'), throwsStateError);
    await bridge.close();
  });
  test('retired binding cannot perform queued operations', () async {
    var owner = 'first';
    var revision = 1;
    final hold = Completer<Object?>();
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if (call.method == 'snapshot') {
        return fixture(binding: owner, revision: revision++);
      }
      return await hold.future;
    });
    final bridge = DictionaryManagerBridge();
    await bridge.start();
    final first = bridge.command('toggle', {'name': 'A'});
    final firstError = expectLater(first, throwsStateError);
    final nextError = expectLater(bridge.command('share'), throwsStateError);
    await Future<void>.delayed(Duration.zero);
    owner = 'replacement';
    await bridge.refresh();
    hold.complete(fixture(binding: 'first', revision: 9));
    await firstError;
    await nextError;
    expect(bridge.latest!.binding, 'replacement');
    await bridge.close();
  });
  testWidgets('selection and import menu dispatch real control commands', (
    tester,
  ) async {
    var revision = 1;
    final calls = <Map>[];
    var s = fixture();
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if (call.method == 'snapshot') return s;
      final a = Map<String, Object?>.from(call.arguments as Map);
      calls.add(a);
      s = {
        ...s,
        'revision': ++revision,
        if (a['op'] == 'toggle') 'selected': ['A'],
        if (a['op'] == 'online') 'online': a['value'],
        if (a['op'] == 'input') 'onlineInput': a['value'],
      };
      return s;
    });
    await tester.pumpWidget(const MaterialApp(home: DictionaryManagerPage()));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('dictionary-select-A')));
    await tester.pumpAndSettle();
    expect(calls.single['name'], 'A');
    await tester.tap(find.byKey(const ValueKey('dictionary-selection-menu')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Share selected'));
    await tester.pumpAndSettle();
    expect(calls.last['op'], 'share');
    await tester.tap(find.byKey(const ValueKey('dictionary-menu')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Import online'));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const ValueKey('dictionary-online-input')),
      'https://dict.invalid',
    );
    await tester.pumpAndSettle();
    expect(calls.last['op'], 'input');
    expect(calls.last['value'], 'https://dict.invalid');
    await tester.tap(find.text('Import').last);
    await tester.pumpAndSettle();
    expect(calls.last['op'], 'confirmOnline');
    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  });
  testWidgets('accessible row reorder commits exact adjacent identities', (
    tester,
  ) async {
    var revision = 1;
    final calls = <Map>[];
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if (call.method != 'snapshot') calls.add(call.arguments as Map);
      return fixture(revision: revision++);
    });
    await tester.pumpWidget(const MaterialApp(home: DictionaryManagerPage()));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('dictionary-more-A')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Move down'));
    await tester.pumpAndSettle();
    expect(calls.map((a) => a['op']), ['move', 'finishReorder']);
    expect(calls.first['from'], 'A');
    expect(calls.first['to'], 'B');
    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  });
  for (final cancelled in [false, true]) {
    testWidgets(
      'delayed drag ACK has one move then ${cancelled ? 'rollback' : 'commit'}',
      (tester) async {
        final hold = Completer<Object?>();
        var revision = 1;
        var current = fixture();
        final calls = <Map>[];
        binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
          call,
        ) async {
          if (call.method == 'snapshot') return current;
          final args = call.arguments as Map;
          calls.add(args);
          if (args['op'] == 'move') return hold.future;
          return {...current, 'revision': ++revision};
        });
        await tester.pumpWidget(
          const MaterialApp(home: DictionaryManagerPage()),
        );
        await tester.pumpAndSettle();
        final gesture = await tester.startGesture(
          tester.getCenter(find.text('A')),
        );
        await tester.pump(const Duration(milliseconds: 600));
        await gesture.moveBy(const Offset(0, 80));
        await tester.pump(const Duration(milliseconds: 400));
        expect(calls.where((call) => call['op'] == 'move').length, 1);
        if (cancelled) {
          await gesture.cancel();
        } else {
          await gesture.up();
        }
        await tester.pump(const Duration(milliseconds: 400));
        expect(calls.map((call) => call['op']), ['move']);
        current = {
          ...fixture(revision: ++revision),
          'rules': [
            {'name': 'B', 'enabled': false},
            {'name': 'A', 'enabled': true},
          ],
        };
        hold.complete(current);
        await tester.pumpAndSettle();
        expect(calls.map((call) => call['op']), [
          'move',
          cancelled ? 'cancelReorder' : 'finishReorder',
        ]);
        await tester.pumpWidget(const SizedBox());
        await tester.pumpAndSettle();
      },
    );
  }
  test('cancel rejected online draft allows independent actions', () async {
    var revision = 1;
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if (call.method == 'snapshot') return fixture(online: true);
      final args = call.arguments as Map;
      if (args['op'] == 'input') throw PlatformException(code: 'busy');
      return fixture(revision: ++revision);
    });
    final bridge = DictionaryManagerBridge();
    await bridge.start();
    await expectLater(
      bridge.command('input', {'value': 'draft'}),
      throwsA(isA<PlatformException>()),
    );
    await bridge.command('online', {'value': false});
    await bridge.command('selectAll');
    await bridge.close();
  });
  testWidgets('failed move ACK after release rolls back native preview', (
    tester,
  ) async {
    final hold = Completer<Object?>();
    var revision = 1;
    final calls = <Map>[];
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if (call.method != 'snapshot') {
        final args = call.arguments as Map;
        calls.add(args);
        if (args['op'] == 'move') return await hold.future;
      }
      return fixture(revision: revision++);
    });
    await tester.pumpWidget(const MaterialApp(home: DictionaryManagerPage()));
    await tester.pumpAndSettle();
    final gesture = await tester.startGesture(tester.getCenter(find.text('A')));
    await tester.pump(const Duration(milliseconds: 600));
    await gesture.moveBy(const Offset(0, 80));
    await tester.pump(const Duration(milliseconds: 100));
    await gesture.up();
    await tester.pump();
    hold.completeError(PlatformException(code: 'background'));
    await tester.pumpAndSettle();
    expect(calls.map((a) => a['op']), ['move', 'cancelReorder']);
    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  });
}
