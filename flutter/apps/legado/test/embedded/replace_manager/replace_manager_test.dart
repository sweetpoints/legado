import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:legado_app/embedded/replace_manager/replace_manager_bridge.dart';
import 'package:legado_app/embedded/replace_manager/replace_manager_page.dart';

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
}
