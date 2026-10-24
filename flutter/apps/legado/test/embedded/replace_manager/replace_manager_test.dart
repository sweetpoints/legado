import 'dart:async';
import 'dart:ui' show SemanticsAction, SemanticsActionEvent;

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:legado_app/embedded/replace_manager/replace_manager_bridge.dart';
import 'package:legado_app/embedded/replace_manager/replace_manager_page.dart';

class RecordingReplaceBridge extends ReplaceManagerBridge {
  final issued = <Map<String, Object?>>[];
  @override
  Future<void> command(String op, [Map<String, Object?> args = const {}]) {
    issued.add({...args, 'op': op});
    return super.command(op, args);
  }
}

Map<String, Object?> fixture() => {
  'schemaVersion': 1,
  'binding': 'one',
  'revision': 1,
  'loaded': true,
  'busy': false,
  'pending': false,
  'waitingNative': false,
  'error': false,
  'rows': [
    for (var i = 1; i <= 4; i++)
      {
        'id': i,
        'name': 'Rule $i',
        'displayName': 'Rule $i',
        'group': i == 1 ? 'Private' : null,
        'enabled': i != 1,
        'order': i * 9,
      },
  ],
  'groups': ['Private'],
  'selected': <int>[],
  'query': '',
  'queryStart': 0,
  'queryEnd': 0,
  'dialog': null,
  'targets': <int>[],
  'draft': '',
  'draftStart': 0,
  'draftEnd': 0,
  'manual': false,
  'dragging': null,
  'history': ['https://history.invalid/'],
  'summary': 'receipt',
  'canEncode': true,
  'scrollIndex': 0,
  'scrollOffset': 0,
  'labels': {
    for (final key in [
      'back',
      'search',
      'groups',
      'manageGroups',
      'enabled',
      'disabled',
      'noGroup',
      'more',
      'add',
      'importLocal',
      'importUrl',
      'importQr',
      'manual',
      'help',
      'retry',
      'edit',
      'top',
      'bottom',
      'delete',
      'all',
      'invert',
      'enableSelection',
      'disableSelection',
      'addGroup',
      'removeGroup',
      'export',
      'share',
      'confirm',
      'cancel',
      'deleteTitle',
      'sureDelete',
      'groupName',
      'path',
      'exportSuccess',
      'passphrase',
    ])
      key: key,
  },
};
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late Map<String, Object?> native;
  late List<Map<String, Object?>> commands;
  Future<void> Function(Map<String, Object?>)? before;
  bool wrongAck = false;
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  setUp(() {
    native = fixture();
    commands = [];
    before = null;
    wrongAck = false;
    messenger.setMockMethodCallHandler(
      const MethodChannel('legado/replace_manager/state'),
      (_) async => null,
    );
    messenger.setMockMethodCallHandler(ReplaceManagerBridge.channel, (
      call,
    ) async {
      if (call.method == 'snapshot') return {...native};
      final args = Map<String, Object?>.from(call.arguments as Map);
      commands.add(args);
      await before?.call(args);
      final op = args['op'];
      if (op == 'query' || op == 'draft') {
        if (!wrongAck) {
          native = {
            ...native,
            '$op': args['text'],
            '${op}Start': args['start'],
            '${op}End': args['end'],
          };
        }
      }
      if (op == 'selected') {
        native = {
          ...native,
          'selected': args['value'] == true
              ? [...(native['selected']! as List), args['id']]
              : <int>[],
        };
      }
      if (op == 'enabled') {
        native = {
          ...native,
          'rows': (native['rows']! as List).map((v) {
            final r = Map<String, Object?>.from(v as Map);
            return (args['ids']! as List).contains(r['id'])
                ? {...r, 'enabled': args['value']}
                : r;
          }).toList(),
        };
      }
      if (op == 'dialog') {
        native = {
          ...native,
          'dialog': args['kind'],
          'targets': args['ids'],
          'draft': '',
          'draftStart': 0,
          'draftEnd': 0,
        };
      }
      if (op == 'cancelDialog') {
        native = {
          ...native,
          'dialog': null,
          'draft': '',
          'draftStart': 0,
          'draftEnd': 0,
        };
      }
      if (op == 'manual') {
        native = {...native, 'manual': !(native['manual']! as bool)};
      }
      if (op == 'beginDrag') native = {...native, 'dragging': args['id']};
      if (op == 'cancelGesture' || op == 'finishDrag') {
        native = {...native, 'dragging': null};
      }
      native = {...native, 'revision': (native['revision']! as int) + 1};
      return {...native};
    });
  });
  tearDown(() {
    messenger.setMockMethodCallHandler(ReplaceManagerBridge.channel, null);
    messenger.setMockMethodCallHandler(
      const MethodChannel('legado/replace_manager/state'),
      null,
    );
  });
  test('snapshot validates identities and preserves full private values and raw selection', () {
    final value = fixture();
    value['query'] = 'Q' * 2000000;
    value['queryStart'] = 8;
    value['queryEnd'] = 3;
    final snapshot = ReplaceManagerSnapshot(value);
    expect(snapshot.text('query').length, 2000000);
    expect(snapshot.number('queryStart'), 8);
    expect(snapshot.number('queryEnd'), 3);
    expect(
      () => ReplaceManagerSnapshot({
        ...value,
        'rows': [...(value['rows']! as List), (value['rows']! as List).first],
      }),
      throwsFormatException,
    );
    expect(
      () => ReplaceManagerSnapshot({...value, 'draftStart': -1}),
      throwsFormatException,
    );
  });
  test('query and draft require exact text and raw range ACK before dependent operations', () async {
    final bridge = ReplaceManagerBridge();
    await bridge.start();
    wrongAck = true;
    await expectLater(
      bridge.command('query', {'text': 'raw', 'start': 3, 'end': 1}),
      throwsStateError,
    );
    final count = commands.length;
    await expectLater(
      bridge.command('effect', {'action': 'Share', 'id': null}),
      throwsStateError,
    );
    expect(commands.length, count);
    await expectLater(bridge.command('back'), throwsStateError);
    await expectLater(bridge.command('cancelDialog'), throwsStateError);
    await bridge.command('cancelQuery');
    expect(bridge.rejectedField, null);
    wrongAck = false;
    await bridge.command('query', {'text': 'raw', 'start': 3, 'end': 1});
    expect(bridge.latest!.number('queryEnd'), 1);
    expect(commands.map((v) => v['sequence']).toList(), [1, 2, 3]);
    await bridge.close();
  });
  test('serialized field acknowledgement precedes a queued effect and retired binding cannot act', () async {
    final bridge = ReplaceManagerBridge();
    await bridge.start();
    final gate = Completer<void>();
    before = (a) async {
      if (a['op'] == 'query') await gate.future;
    };
    final field = bridge.command('query', {
      'text': 'latest',
      'start': 6,
      'end': 6,
    });
    final effect = bridge.command('effect', {'action': 'Share', 'id': null});
    await Future<void>.delayed(Duration.zero);
    expect(commands.length, 1);
    gate.complete();
    await field;
    await effect;
    expect(commands.map((v) => v['op']).toList(), ['query', 'effect']);
    native = {...native, 'binding': 'two', 'revision': 1};
    await bridge.refresh();
    native = {...native, 'binding': 'one', 'revision': 99};
    await bridge.refresh();
    expect(bridge.latest!.binding, 'two');
    await bridge.close();
    await expectLater(bridge.command('back'), throwsStateError);
  });
  testWidgets(
    'actual selection and enable controls preserve identity metadata',
    (tester) async {
      await tester.pumpWidget(const MaterialApp(home: ReplaceManagerPage()));
      await tester.pumpAndSettle();
      await tester.tap(find.byType(Checkbox).first);
      await tester.pumpAndSettle();
      expect(native['selected'], [1]);
      final popup = find.byWidgetPredicate(
        (w) =>
            w is Semantics &&
            w.properties.identifier == 'replace-rule-selection-menu',
      );
      await tester.tap(popup);
      await tester.pumpAndSettle();
      await tester.tap(find.text('enableSelection'));
      await tester.pumpAndSettle();
      final row = (native['rows']! as List).first as Map;
      expect(row['enabled'], true);
      expect(row['id'], 1);
      expect(row['order'], 9);
      expect(row['group'], 'Private');
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
    },
  );
  testWidgets(
    'dialog input blocks confirm until ACK and cancel discards the pending native draft',
    (tester) async {
      native = {...native, 'dialog': 'ImportUrl'};
      final gate = Completer<void>();
      before = (a) async {
        if (a['op'] == 'draft') await gate.future;
      };
      await tester.pumpWidget(const MaterialApp(home: ReplaceManagerPage()));
      await tester.pumpAndSettle();
      final field = find.byWidgetPredicate(
        (w) =>
            w is Semantics &&
            w.properties.identifier == 'replace-rule-dialog-field',
      );
      await tester.enterText(
        find.descendant(of: field, matching: find.byType(TextField)),
        'https://input.invalid/',
      );
      await tester.pump();
      final button = find.descendant(
        of: find.byWidgetPredicate(
          (w) =>
              w is Semantics &&
              w.properties.identifier == 'replace-rule-dialog-confirm',
        ),
        matching: find.byType(TextButton),
      );
      expect(tester.widget<TextButton>(button).onPressed, isNull);
      gate.complete();
      await tester.pumpAndSettle();
      expect(tester.widget<TextButton>(button).onPressed, isNotNull);
      await tester.tap(find.text('cancel').last);
      await tester.pumpAndSettle();
      expect(native['dialog'], null);
      expect(native['draft'], '');
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
    },
  );
  testWidgets(
    'pause while reorder begin is in flight cancels rather than commits',
    (tester) async {
      final gate = Completer<void>();
      before = (a) async {
        if (a['op'] == 'beginDrag') await gate.future;
      };
      await tester.pumpWidget(const MaterialApp(home: ReplaceManagerPage()));
      await tester.pumpAndSettle();
      final gesture = await tester.startGesture(
        tester.getCenter(find.text('Rule 1')),
      );
      await tester.pump(const Duration(milliseconds: 600));
      expect(commands.where((v) => v['op'] == 'beginDrag').length, 1);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await tester.pump();
      gate.complete();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      await gesture.up();
      await tester.pumpAndSettle();
      expect(commands.any((v) => v['op'] == 'finishDrag'), false);
      expect(commands.any((v) => v['op'] == 'cancelGesture'), true);
      expect(native['dragging'], null);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
    },
  );
  testWidgets(
    'hardware Back cancels only modal; rejected query requires explicit recovery',
    (tester) async {
      native = {...native, 'dialog': 'AddGroup'};
      await tester.pumpWidget(const MaterialApp(home: ReplaceManagerPage()));
      await tester.pumpAndSettle();
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(native['dialog'], null);
      expect(commands.map((v) => v['op']), contains('cancelDialog'));
      expect(commands.any((v) => v['op'] == 'back'), false);
      wrongAck = true;
      final queryField = find.byWidgetPredicate(
        (w) =>
            w is Semantics && w.properties.identifier == 'replace-rule-search',
      );
      await tester.enterText(
        find.descendant(of: queryField, matching: find.byType(TextField)),
        'unacknowledged',
      );
      await tester.pumpAndSettle();
      expect(find.text('unacknowledged'), findsOneWidget);
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(commands.any((v) => v['op'] == 'back'), false);
      expect(commands.any((v) => v['op'] == 'effect'), false);
      await tester.tap(find.text('cancel').last);
      await tester.pumpAndSettle();
      expect(commands.last['op'], 'cancelQuery');
      wrongAck = false;
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(commands.last['op'], 'back');
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
    },
  );

  testWidgets(
    'visible modal cancel discards rejected background query before modal ACK',
    (tester) async {
      await tester.pumpWidget(const MaterialApp(home: ReplaceManagerPage()));
      await tester.pumpAndSettle();
      wrongAck = true;
      final field = find.byWidgetPredicate(
        (w) =>
            w is Semantics && w.properties.identifier == 'replace-rule-search',
      );
      await tester.enterText(
        find.descendant(of: field, matching: find.byType(TextField)),
        'unacknowledged',
      );
      await tester.pumpAndSettle();
      native = {
        ...native,
        'dialog': 'AddGroup',
        'revision': (native['revision']! as int) + 1,
      };
      await messenger.handlePlatformMessage(
        'legado/replace_manager/state',
        const StandardMethodCodec().encodeSuccessEnvelope(native),
        (_) {},
      );
      await tester.pumpAndSettle();
      final cancel = find.byWidgetPredicate(
        (w) =>
            w is Semantics &&
            w.properties.identifier == 'replace-rule-dialog-cancel',
      );
      expect(cancel, findsOneWidget);
      await tester.tap(
        find.descendant(of: cancel, matching: find.byType(TextButton)),
      );
      await tester.pumpAndSettle();
      expect(commands.reversed.take(2).map((v) => v['op']).toList(), [
        'cancelDialog',
        'cancelQuery',
      ]);
      expect(native['dialog'], null);
      expect(commands.any((v) => v['op'] == 'back'), false);
      expect(find.text('unacknowledged'), findsNothing);
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
    },
  );
  test(
    'late retired binding field failure cannot reject the new binding',
    () async {
      final bridge = ReplaceManagerBridge();
      await bridge.start();
      final gate = Completer<void>();
      before = (args) async {
        if (args['op'] == 'query') {
          await gate.future;
          throw PlatformException(code: 'retired_binding');
        }
      };
      final old = bridge.command('query', {
        'text': 'old private',
        'start': 11,
        'end': 3,
      });
      final rejected = expectLater(old, throwsA(isA<PlatformException>()));
      await Future<void>.delayed(Duration.zero);
      native = {...fixture(), 'binding': 'replacement'};
      await bridge.refresh();
      gate.complete();
      await rejected;
      expect(bridge.latest!.binding, 'replacement');
      expect(bridge.rejectedField, null);
      await bridge.command('effect', {'action': 'Share', 'id': null});
      expect(commands.last['binding'], 'replacement');
      expect(commands.last['op'], 'effect');
      await bridge.close();
    },
  );
  testWidgets(
    'one visible modal cancel settles a prior pending query rejection in queue order',
    (tester) async {
      final gate = Completer<void>();
      before = (args) async {
        if (args['op'] == 'query') {
          await gate.future;
          throw PlatformException(code: 'field_rejected');
        }
      };
      await tester.pumpWidget(const MaterialApp(home: ReplaceManagerPage()));
      await tester.pumpAndSettle();
      final queryField = find.byWidgetPredicate(
        (w) =>
            w is Semantics && w.properties.identifier == 'replace-rule-search',
      );
      await tester.enterText(
        find.descendant(of: queryField, matching: find.byType(TextField)),
        'pending private',
      );
      await tester.pump();
      native = {
        ...native,
        'dialog': 'AddGroup',
        'revision': (native['revision']! as int) + 1,
      };
      await messenger.handlePlatformMessage(
        'legado/replace_manager/state',
        const StandardMethodCodec().encodeSuccessEnvelope(native),
        (_) {},
      );
      await tester.pump();
      final cancel = find.byWidgetPredicate(
        (w) =>
            w is Semantics &&
            w.properties.identifier == 'replace-rule-dialog-cancel',
      );
      await tester.tap(
        find.descendant(of: cancel, matching: find.byType(TextButton)),
      );
      await tester.pump();
      expect(commands.map((v) => v['op']).toList(), ['query']);
      gate.complete();
      await tester.pumpAndSettle();
      expect(commands.map((v) => v['op']).toList(), [
        'query',
        'cancelQuery',
        'cancelDialog',
      ]);
      expect(native['dialog'], null);
      expect(find.text('pending private'), findsNothing);
      expect(commands.any((v) => v['op'] == 'back'), false);
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
    },
  );
  test(
    'late retired binding field ACK cannot poison the replacement owner',
    () async {
      final gate = Completer<void>();
      messenger.setMockMethodCallHandler(ReplaceManagerBridge.channel, (
        call,
      ) async {
        if (call.method == 'snapshot') return {...native};
        final args = Map<String, Object?>.from(call.arguments as Map);
        commands.add(args);
        if (args['op'] == 'query') {
          await gate.future;
          return {
            ...fixture(),
            'revision': 2,
            'query': args['text'],
            'queryStart': args['start'],
            'queryEnd': args['end'],
          };
        }
        native = {...native, 'revision': (native['revision']! as int) + 1};
        return {...native};
      });
      final bridge = ReplaceManagerBridge();
      await bridge.start();
      final old = bridge.command('query', {
        'text': 'old private',
        'start': 11,
        'end': 3,
      });
      final rejected = expectLater(old, throwsStateError);
      await Future<void>.delayed(Duration.zero);
      native = {...fixture(), 'binding': 'replacement'};
      await bridge.refresh();
      gate.complete();
      await rejected;
      expect(bridge.latest!.binding, 'replacement');
      expect(bridge.latest!.text('query'), '');
      expect(bridge.rejectedField, null);
      await bridge.command('effect', {'action': 'Share', 'id': null});
      expect(commands.last['binding'], 'replacement');
      expect(commands.last['op'], 'effect');
      await bridge.close();
    },
  );
  testWidgets(
    'native Back after modal cancel ACK before a new frame closes the manager',
    (tester) async {
      native = {...native, 'dialog': 'AddGroup'};
      await tester.pumpWidget(const MaterialApp(home: ReplaceManagerPage()));
      await tester.pumpAndSettle();
      final modal = find.byWidgetPredicate(
        (w) =>
            w is Semantics &&
            w.properties.identifier == 'replace-rule-dialog-field',
      );
      expect(modal, findsOneWidget);
      await tester.binding.handlePopRoute();
      // Drain channel ACK microtasks without pumping the scheduled rebuild frame.
      for (var i = 0; i < 32; i++) {
        await Future<void>.value();
      }
      expect(native['dialog'], null);
      expect(commands.map((v) => v['op']).toList(), ['cancelDialog']);
      expect(modal, findsOneWidget);
      await tester.binding.handlePopRoute();
      for (var i = 0; i < 32; i++) {
        await Future<void>.value();
      }
      expect(commands.map((v) => v['op']).toList(), ['cancelDialog', 'back']);
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
    },
  );
  testWidgets(
    'second native Back queued before the modal ACK resolves against the acknowledged root',
    (tester) async {
      native = {...native, 'dialog': 'AddGroup'};
      final gate = Completer<void>();
      before = (args) async {
        if (args['op'] == 'cancelDialog') await gate.future;
      };
      await tester.pumpWidget(const MaterialApp(home: ReplaceManagerPage()));
      await tester.pumpAndSettle();
      await tester.binding.handlePopRoute();
      for (var i = 0; i < 32; i++) {
        await Future<void>.value();
      }
      await tester.binding.handlePopRoute();
      for (var i = 0; i < 32; i++) {
        await Future<void>.value();
      }
      expect(native['dialog'], 'AddGroup');
      expect(commands.map((v) => v['op']).toList(), ['cancelDialog']);
      gate.complete();
      for (var i = 0; i < 32; i++) {
        await Future<void>.value();
      }
      expect(commands.map((v) => v['op']).toList(), ['cancelDialog', 'back']);
      expect(native['dialog'], null);
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
    },
  );
  testWidgets(
    'reordering keeps each logical replacement switch semantics identity and action owner',
    (tester) async {
      native = {
        ...native,
        'rows': (native['rows']! as List)
            .take(3)
            .map((v) => {...(v as Map), 'enabled': false})
            .toList(),
      };
      final semantics = tester.ensureSemantics();
      await tester.pumpWidget(const MaterialApp(home: ReplaceManagerPage()));
      await tester.pumpAndSettle();
      Finder tagged(String id) => find.byWidgetPredicate(
        (w) => w is Semantics && w.properties.identifier == id,
      );
      final before = {
        for (var id = 1; id <= 3; id++)
          id: tester.getSemantics(tagged('replace-rule-enabled-$id')).id,
      };
      final beforeElements = {
        for (var id = 1; id <= 3; id++)
          id: identityHashCode(tester.element(tagged('replace-rule-row-$id'))),
      };
      final beforeBounds = {
        for (var id = 1; id <= 3; id++)
          id: tester.getRect(tagged('replace-rule-row-$id')),
      };
      final rows = native['rows']! as List;
      native = {
        ...native,
        'rows': [rows[1], rows[2], rows[0]],
        'revision': (native['revision']! as int) + 1,
      };
      await messenger.handlePlatformMessage(
        'legado/replace_manager/state',
        const StandardMethodCodec().encodeSuccessEnvelope(native),
        (_) {},
      );
      await tester.pumpAndSettle();
      final y = [2, 3, 1]
          .map((id) => tester.getRect(tagged('replace-rule-row-$id')).center.dy)
          .toList();
      expect(y[0], lessThan(y[1]));
      expect(y[1], lessThan(y[2]));
      final after = {
        for (var id = 1; id <= 3; id++)
          id: tester.getSemantics(tagged('replace-rule-enabled-$id')).id,
      };
      final afterElements = {
        for (var id = 1; id <= 3; id++)
          id: identityHashCode(tester.element(tagged('replace-rule-row-$id'))),
      };
      final afterBounds = {
        for (var id = 1; id <= 3; id++)
          id: tester.getRect(tagged('replace-rule-row-$id')),
      };
      // Exercise the same semantics action ID that an accessibility client obtained before movement.
      tester.binding.performSemanticsAction(
        SemanticsActionEvent(
          type: SemanticsAction.tap,
          viewId: tester.view.viewId,
          nodeId: before[1]!,
        ),
      );
      await tester.pumpAndSettle();
      final enabled = commands.lastWhere((v) => v['op'] == 'enabled');
      expect(
        enabled['ids'],
        [1],
        reason:
            'beforeNode=$before afterNode=$after beforeElement=$beforeElements afterElement=$afterElements beforeBounds=$beforeBounds afterBounds=$afterBounds',
      );
      expect(enabled['value'], true);
      expect(
        after,
        before,
        reason: 'Logical replacement switch semantics identity must survive movement',
      );
      expect(
        afterElements,
        beforeElements,
        reason: 'The row element belongs to its business ID',
      );
      for (var id = 1; id <= 3; id++) {
        final node = tester.getSemantics(tagged('replace-rule-enabled-$id'));
        expect(node.identifier, 'replace-rule-enabled-$id');
        var owner = node.parent;
        var matchesRow = false;
        while (owner != null) {
          if (owner.identifier == 'replace-rule-row-$id') matchesRow = true;
          owner = owner.parent;
        }
        expect(
          matchesRow,
          true,
          reason: 'Switch semantics stays inside the matching row owner',
        );
      }
      final savedRows = (native['rows']! as List).cast<Map>();
      expect(savedRows.map((r) => r['id']).toList(), [2, 3, 1]);
      expect(savedRows.map((r) => r['enabled']).toList(), [false, false, true]);
      expect(savedRows.last['group'], 'Private');
      expect(savedRows.last['order'], 9);
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
      semantics.dispose();
    },
  );
  testWidgets(
    'latest held pointer survives delayed native begin ACK without returning the preview to its origin',
    (tester) async {
      final bridge = RecordingReplaceBridge();
      final gate = Completer<void>();
      final beginObserved = Completer<void>();
      final baseline = (native['rows']! as List).cast<Map>();
      final previews = <List<Object?>>[];
      before = (args) async {
        if (args['op'] == 'beginDrag') {
          beginObserved.complete();
          await gate.future;
        }
        if (args['op'] == 'dragTo') {
          final moving = native['dragging'];
          final rows = baseline.where((r) => r['id'] != moving).toList();
          final target = rows.indexWhere((r) => r['id'] == args['id']);
          rows.insert(
            target + (args['after'] == true ? 1 : 0),
            baseline.singleWhere((r) => r['id'] == moving),
          );
          native = {...native, 'rows': rows};
          previews.add(rows.map((r) => r['id']).toList());
        }
      };
      await tester.pumpWidget(
        MaterialApp(home: ReplaceManagerPage(bridge: bridge)),
      );
      await tester.pumpAndSettle();
      final gesture = await tester.startGesture(
        tester.getCenter(find.text('Rule 1')),
      );
      try {
        await tester.pump(const Duration(milliseconds: 600));
        expect(beginObserved.isCompleted, true);
        expect(native['dragging'], null);
        final last = tester.getRect(find.text('Rule 3'));
        await gesture.moveTo(Offset(last.center.dx, last.center.dy + 20));
        await tester.pump();
        expect(commands.map((v) => v['op']).toList(), ['beginDrag']);
        gate.complete();
        await tester.pump();
        // No new pointer movement: hold the physical target across begin ACK and periodic pumps.
        for (var i = 0; i < 4; i++) {
          await tester.pump(const Duration(milliseconds: 40));
        }
        expect(
          (native['rows']! as List).map((v) => (v as Map)['id']).toList(),
          [2, 3, 1, 4],
          reason:
              'nativePreviewTrace=$previews delivered=${commands.where((v) => v["op"] == "dragTo").map((v) => {"id": v["id"], "after": v["after"]}).toList()}',
        );
        expect(native['dragging'], 1);
      } finally {
        if (!gate.isCompleted) gate.complete();
        await gesture.up();
        await tester.pump();
        await tester.pumpWidget(const SizedBox());
        await tester.pumpAndSettle();
      }
    },
  );
  testWidgets(
    'move updates await the begin ACK and releasing before a late ACK never revives the gesture',
    (tester) async {
      final bridge = RecordingReplaceBridge();
      final gate = Completer<void>();
      final beginObserved = Completer<void>();
      before = (args) async {
        if (args['op'] == 'beginDrag') {
          beginObserved.complete();
          await gate.future;
        }
      };
      await tester.pumpWidget(
        MaterialApp(home: ReplaceManagerPage(bridge: bridge)),
      );
      await tester.pumpAndSettle();
      final gesture = await tester.startGesture(
        tester.getCenter(find.text('Rule 1')),
      );
      var released = false;
      try {
        await tester.pump(const Duration(milliseconds: 600));
        expect(beginObserved.isCompleted, true);
        await gesture.moveTo(tester.getCenter(find.text('Rule 3')));
        await tester.pump();
        expect(
          bridge.issued.where((v) => v['op'] == 'dragTo'),
          isEmpty,
          reason: 'Do not compute or queue a preview from a pre-ACK native snapshot',
        );
        await gesture.up();
        released = true;
        await tester.pump();
        gate.complete();
        await tester.pump();
        for (var i = 0; i < 4; i++) {
          await tester.pump(const Duration(milliseconds: 40));
        }
        expect(native['dragging'], null);
        expect(bridge.issued.where((v) => v['op'] == 'dragTo'), isEmpty);
        expect(commands.where((v) => v['op'] == 'finishDrag').length, 1);
      } finally {
        if (!gate.isCompleted) gate.complete();
        if (!released) await gesture.up();
        await tester.pump();
        await tester.pumpWidget(const SizedBox());
        await tester.pumpAndSettle();
      }
    },
  );
}
