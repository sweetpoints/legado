import 'dart:async';
import 'dart:ui' show SemanticsAction, SemanticsActionEvent;

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:legado_app/embedded/highlight_manager/highlight_manager_bridge.dart';
import 'package:legado_app/embedded/highlight_manager/highlight_manager_page.dart';
import 'package:legado_app/embedded/highlight_manager/highlight_groups_page.dart';
import 'package:legado_app/l10n/generated/app_localizations.dart';

Map<String, Object?> fixture({bool groups = false}) => {
  'schemaVersion': 1,
  'binding': 'one',
  'revision': 1,
  'loaded': true,
  'busy': false,
  'error': null,
  'groups': ['Characters', 'Quotes'],
  if (groups) ...{
    'stage': 'Rename',
    'source': 'Characters',
    'name': 'Characters',
    'nameStart': 10,
    'nameEnd': 10,
  } else ...{
    'selected': <String>[],
    'deletion': <String>[],
    'exportResult': null,
    'exporting': false,
    'scrollIndex': 0,
    'scrollOffset': 0.0,
    'rows': [
      for (var i = 1; i <= 3; i++)
        {
          'id': i,
          'uuid': 'id$i',
          'name': 'Name$i',
          'label': 'Name$i',
          'enabled': i != 1,
          'order': i,
        },
    ],
  },
  'labels': {
    for (final k in [
      'title',
      'back',
      'add',
      'more',
      'import',
      'exportAll',
      'groups',
      'filter',
      'all',
      'allGroups',
      'invert',
      'delete',
      'enableSelection',
      'disableSelection',
      'topSelection',
      'bottomSelection',
      'export',
      'share',
      'retry',
      'edit',
      'top',
      'bottom',
      'up',
      'down',
      'empty',
      'noGroup',
      'cancel',
      'confirm',
      'sureDelete',
      'exportSuccess',
      'close',
      'groupEdit',
      'groupName',
      'groupDelete',
      'groupDeleteMessage',
      'moveGroup',
    ])
      k: k,
  },
};
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late Map<String, Object?> native;
  late List<Map<String, Object?>> commands;
  Future<void> Function(Map<String, Object?>)? before;
  var wrong = false;
  void handler(bool groups) {
    final path = groups ? 'highlight_groups' : 'highlight_manager';
    messenger.setMockMethodCallHandler(
      MethodChannel('legado/$path/state'),
      (_) async => null,
    );
    messenger.setMockMethodCallHandler(MethodChannel('legado/$path'), (
      call,
    ) async {
      if (call.method == 'snapshot') return {...native};
      final a = Map<String, Object?>.from(call.arguments as Map);
      commands.add(a);
      await before?.call(a);
      if (a['op'] == 'name' && !wrong) {
        native = {
          ...native,
          'name': a['text'],
          'nameStart': a['start'],
          'nameEnd': a['end'],
        };
      }
      if (a['op'] == 'cancel' || a['op'] == 'back') {
        native = {
          ...native,
          'stage': 'None',
          'name': '',
          'nameStart': 0,
          'nameEnd': 0,
        };
      }
      if (a['op'] == 'scroll') {
        native = {
          ...native,
          'scrollIndex': a['index'],
          'scrollOffset': a['offset'],
        };
      }
      if (a['op'] == 'select') {
        native = {
          ...native,
          'selected': [a['uuid']],
        };
      }
      if (a['op'] == 'enable') {
        native = {
          ...native,
          'rows': (native['rows']! as List)
              .map(
                (r) => (r as Map)['uuid'] == a['uuid']
                    ? {...r, 'enabled': a['value']}
                    : r,
              )
              .toList(),
        };
      }
      if (a['op'] == 'slideStart') {
        native = {
          ...native,
          'selected': [a['uuid']],
        };
      }
      if (a['op'] == 'slideMove') {
        final rows = native['rows']! as List;
        final to = rows.indexWhere((r) => (r as Map)['uuid'] == a['uuid']);
        native = {
          ...native,
          'selected': rows.take(to + 1).map((r) => (r as Map)['uuid']).toList(),
        };
      }
      if (a['op'] == 'dragMove') {
        final rows = List<Object?>.of(native['rows']! as List);
        final index = rows.indexWhere((r) => (r as Map)['uuid'] == a['uuid']);
        final to = rows.indexWhere((r) => (r as Map)['uuid'] == a['to']);
        rows.insert(to, rows.removeAt(index));
        native = {...native, 'rows': rows};
      }
      native = {...native, 'revision': (native['revision']! as int) + 1};
      return {...native};
    });
  }

  setUp(() {
    native = fixture();
    commands = [];
    before = null;
    wrong = false;
    handler(false);
    handler(true);
  });
  tearDown(() {
    for (final p in ['highlight_manager', 'highlight_groups']) {
      messenger.setMockMethodCallHandler(MethodChannel('legado/$p'), null);
      messenger.setMockMethodCallHandler(
        MethodChannel('legado/$p/state'),
        null,
      );
    }
  });
  test(
    'strict identity and full raw reversed UTF16 selections are preserved',
    () {
      final raw = fixture(groups: true);
      raw['name'] = '😀\n${'X' * 2000000}';
      raw['nameStart'] = 9;
      raw['nameEnd'] = 1;
      final value = HighlightSnapshot(raw);
      expect(value.text('name').length, 2000003);
      expect(value.number('nameStart'), 9);
      expect(value.number('nameEnd'), 1);
      expect(
        () => HighlightSnapshot({...raw, 'nameEnd': -1}),
        throwsFormatException,
      );
      final m = fixture();
      expect(
        () => HighlightSnapshot({
          ...m,
          'rows': [...m['rows']! as List, (m['rows']! as List).first],
        }),
        throwsFormatException,
      );
    },
  );
  test('raw name ACK serializes before dependent rename action', () async {
    native = fixture(groups: true);
    final bridge = HighlightBridge(groups: true);
    await bridge.start();
    final gate = Completer<void>();
    before = (a) async {
      if (a['op'] == 'name') await gate.future;
    };
    final name = bridge.command('name', {
      'text': ' raw\n😀 ',
      'start': 6,
      'end': 1,
    });
    final confirm = bridge.command('confirmRename');
    await Future<void>.delayed(Duration.zero);
    expect(commands.map((a) => a['op']), ['name']);
    gate.complete();
    await name;
    await confirm;
    expect(commands.map((a) => a['op']), ['name', 'confirmRename']);
    expect(bridge.latest!.number('nameEnd'), 1);
    await bridge.close();
  });
  test(
    'rejected raw name blocks dependent action but explicit Back cancels it',
    () async {
      native = fixture(groups: true);
      final bridge = HighlightBridge(groups: true);
      await bridge.start();
      wrong = true;
      await expectLater(
        bridge.command('name', {'text': 'draft', 'start': 5, 'end': 2}),
        throwsStateError,
      );
      await expectLater(bridge.command('confirmRename'), throwsStateError);
      expect(commands.length, 1);
      await bridge.command('back');
      expect(bridge.rejectedName, false);
      expect(bridge.latest!.text('stage'), 'None');
      await bridge.close();
    },
  );
  test('late retired owner ACK cannot reject the new owner', () async {
    native = fixture(groups: true);
    final bridge = HighlightBridge(groups: true);
    await bridge.start();
    final gate = Completer<void>();
    before = (_) => gate.future;
    final pending = bridge.command('name', {
      'text': 'old',
      'start': 3,
      'end': 3,
    });
    final rejected = expectLater(pending, throwsStateError);
    await Future<void>.delayed(Duration.zero);
    native = {...native, 'binding': 'two', 'revision': 1};
    await bridge.refresh();
    gate.complete();
    await rejected;
    expect(bridge.latest!.binding, 'two');
    expect(bridge.rejectedName, false);
    native = {...native, 'binding': 'one', 'revision': 90};
    await bridge.refresh();
    expect(bridge.latest!.binding, 'two');
    await bridge.close();
  });
  Widget app(Widget child) => MaterialApp(
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    home: child,
  );
  Finder id(String value) => find.byWidgetPredicate(
    (w) => w is Semantics && w.properties.identifier == value,
  );
  testWidgets('normal manager selection action addresses the logical UUID', (
    tester,
  ) async {
    final bridge = HighlightBridge();
    await tester.pumpWidget(app(HighlightManagerPage(bridge: bridge)));
    await tester.pumpAndSettle();
    await tester.tap(id('highlight-management-select-id2'));
    await tester.pumpAndSettle();
    expect(commands.last['uuid'], 'id2');
    expect(bridge.latest!.strings('selected'), ['id2']);
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
  });
  testWidgets('row semantics action owner stays with UUID after reorder', (
    tester,
  ) async {
    final semantics = tester.ensureSemantics();
    final bridge = HighlightBridge();
    await tester.pumpWidget(app(HighlightManagerPage(bridge: bridge)));
    await tester.pumpAndSettle();
    final original = tester.getSemantics(
      id('highlight-management-enabled-id1'),
    );
    native = {
      ...native,
      'rows': [
        (native['rows']! as List)[1],
        (native['rows']! as List)[2],
        (native['rows']! as List)[0],
      ],
      'revision': 5,
    };
    await bridge.refresh();
    await tester.pumpAndSettle();
    final moved = tester.getSemantics(id('highlight-management-enabled-id1'));
    expect(moved.id, original.id);
    tester.binding.performSemanticsAction(
      SemanticsActionEvent(
        type: SemanticsAction.tap,
        nodeId: original.id,
        viewId: tester.view.viewId,
      ),
    );
    await tester.pumpAndSettle();
    expect(commands.last['op'], 'enable');
    expect(commands.last['uuid'], 'id1');
    expect(commands.last['value'], true);
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
    semantics.dispose();
  });
  testWidgets('physical long press reorder commits through the bridge', (
    tester,
  ) async {
    final bridge = HighlightBridge();
    await tester.pumpWidget(app(HighlightManagerPage(bridge: bridge)));
    await tester.pumpAndSettle();
    final start = tester.getCenter(id('highlight-management-name-id1'));
    final end = tester.getCenter(id('highlight-management-name-id3'));
    final gesture = await tester.startGesture(start);
    await tester.pump(const Duration(milliseconds: 600));
    await gesture.moveTo(end);
    await tester.pump(const Duration(milliseconds: 100));
    await gesture.up();
    await tester.pumpAndSettle();
    expect(
      commands.map((a) => a['op']),
      containsAllInOrder(['dragStart', 'dragMove', 'dragEnd']),
    );
    expect(bridge.latest!.rows.map((r) => r['uuid']), ['id2', 'id3', 'id1']);
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
  });
  testWidgets('physical checkbox long press slide sends one range gesture', (
    tester,
  ) async {
    final bridge = HighlightBridge();
    await tester.pumpWidget(app(HighlightManagerPage(bridge: bridge)));
    await tester.pumpAndSettle();
    final gesture = await tester.startGesture(
      tester.getCenter(id('highlight-management-select-id1')),
    );
    await tester.pump(const Duration(milliseconds: 600));
    await gesture.moveTo(
      tester.getCenter(id('highlight-management-select-id3')),
    );
    await tester.pump(const Duration(milliseconds: 100));
    await gesture.up();
    await tester.pumpAndSettle();
    expect(
      commands.map((a) => a['op']),
      containsAllInOrder(['slideStart', 'slideMove', 'slideEnd']),
    );
    expect(bridge.latest!.strings('selected'), ['id1', 'id2', 'id3']);
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
  });
  testWidgets(
    'private scroll index and offset restore and actual scrolling sends a coalesced ACK',
    (tester) async {
      native = {
        ...native,
        'rows': [
          for (var i = 0; i < 60; i++)
            {
              'id': i,
              'uuid': 'scroll$i',
              'name': 'Scroll $i',
              'label': 'Scroll $i',
              'enabled': true,
              'order': i,
            },
        ],
        'scrollIndex': 20,
        'scrollOffset': 13.5,
      };
      final bridge = HighlightBridge();
      await tester.pumpWidget(app(HighlightManagerPage(bridge: bridge)));
      await tester.pumpAndSettle();
      final list = tester.widget<ListView>(find.byType(ListView));
      expect(list.controller!.offset, closeTo(1613.5, .01));
      await tester.drag(find.byType(ListView), const Offset(0, -200));
      await tester.pumpAndSettle();
      expect(commands.where((a) => a['op'] == 'scroll'), isNotEmpty);
      final saved = bridge.latest!;
      expect(
        saved.number('scrollIndex') * 80 + (saved.raw['scrollOffset']! as num),
        closeTo(list.controller!.offset, .01),
      );
      await tester.pumpWidget(const SizedBox());
      await tester.pump();
      final second = HighlightBridge();
      await tester.pumpWidget(app(HighlightManagerPage(bridge: second)));
      await tester.pumpAndSettle();
      expect(
        tester.widget<ListView>(find.byType(ListView)).controller!.offset,
        closeTo(
          saved.number('scrollIndex') * 80 +
              (saved.raw['scrollOffset']! as num),
          .01,
        ),
      );
      await tester.pumpWidget(const SizedBox());
      await tester.pump();
    },
  );
  testWidgets(
    'group rename shows raw text while ACK is pending and cancel remains available',
    (tester) async {
      native = fixture(groups: true);
      final bridge = HighlightBridge(groups: true);
      await tester.pumpWidget(app(HighlightGroupsPage(bridge: bridge)));
      await tester.pumpAndSettle();
      final gate = Completer<void>();
      before = (a) async {
        if (a['op'] == 'name') await gate.future;
      };
      await tester.enterText(find.byType(TextField), '  Raw😀\n ');
      await tester.pump();
      expect(find.text('  Raw😀\n '), findsOneWidget);
      final confirm = tester.widget<TextButton>(
        find.descendant(
          of: id('highlight-group-rename-confirm'),
          matching: find.byType(TextButton),
        ),
      );
      expect(confirm.onPressed, null);
      gate.complete();
      await tester.pumpAndSettle();
      expect(bridge.latest!.text('name'), '  Raw😀\n ');
      await tester.tap(id('highlight-group-cancel'));
      await tester.pumpAndSettle();
      expect(bridge.latest!.text('stage'), 'None');
      await tester.pumpWidget(const SizedBox());
      await tester.pump();
    },
  );
}
