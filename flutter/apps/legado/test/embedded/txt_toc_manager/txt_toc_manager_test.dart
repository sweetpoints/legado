import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:legado_app/embedded/txt_toc_manager/txt_toc_manager_bridge.dart';
import 'package:legado_app/embedded/txt_toc_manager/txt_toc_manager_page.dart';
import 'package:legado_app/l10n/generated/app_localizations.dart';

Map<String, Object?> fixture() => {
  'schemaVersion': 1,
  'binding': 'one',
  'revision': 1,
  'loaded': true,
  'busy': false,
  'error': false,
  'picker': false,
  'rows': [
    for (var i = 1; i <= 3; i++)
      {
        'id': i,
        'name': 'Rule $i',
        'example': 'Example $i',
        'enabled': i != 1,
        'order': i,
      },
  ],
  'selected': <int>[],
  'selectedId': null,
  'pickerFinished': false,
  'query': '',
  'queryStart': 0,
  'queryEnd': 0,
  'onlineInput': '',
  'onlineInputStart': 0,
  'onlineInputEnd': 0,
  'deleteIds': <int>[],
  'deleteNames': <String>[],
  'online': false,
  'history': ['https://fixture.invalid/'],
  'exportUrl': null,
  'exportSummary': '',
  'canEncode': false,
  'passphrase': null,
  'scrollIndex': 0,
  'scrollOffset': 0,
  'labels': {
    for (final key in [
      'back',
      'search',
      'more',
      'add',
      'importFile',
      'online',
      'importQr',
      'default',
      'help',
      'retry',
      'edit',
      'top',
      'bottom',
      'up',
      'down',
      'delete',
      'all',
      'invert',
      'enableSelection',
      'disableSelection',
      'export',
      'share',
      'confirm',
      'cancel',
      'deleteTitle',
      'sureDelete',
      'sourceUrl',
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
  bool wrongAck = false;
  Future<void> Function(Map<String, Object?>)? before;
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  setUp(() {
    native = fixture();
    commands = [];
    wrongAck = false;
    before = null;
    messenger.setMockMethodCallHandler(
      const MethodChannel('legado/txt_toc_manager/state'),
      (_) async => null,
    );
    messenger.setMockMethodCallHandler(TxtTocManagerBridge.channel, (
      call,
    ) async {
      if (call.method == 'snapshot') return {...native};
      final a = Map<String, Object?>.from(call.arguments as Map);
      commands.add(a);
      await before?.call(a);
      final op = a['op'];
      if ((op == 'query' || op == 'onlineInput') && !wrongAck) {
        native = {
          ...native,
          '$op': a['text'],
          '${op}Start': a['start'],
          '${op}End': a['end'],
        };
      }
      if (op == 'toggle') {
        native = {
          ...native,
          'selected': (native['selected']! as List).contains(a['id'])
              ? <int>[]
              : [a['id']],
        };
      }
      if (op == 'choose') native = {...native, 'selectedId': a['id']};
      if (op == 'cancelModal') {
        native = {
          ...native,
          'online': false,
          'deleteIds': <int>[],
          'exportUrl': null,
          'passphrase': null,
        };
      }
      if (op == 'online') native = {...native, 'online': a['value']};
      native = {...native, 'revision': (native['revision']! as int) + 1};
      return {...native};
    });
  });
  tearDown(() {
    messenger.setMockMethodCallHandler(TxtTocManagerBridge.channel, null);
    messenger.setMockMethodCallHandler(
      const MethodChannel('legado/txt_toc_manager/state'),
      null,
    );
  });
  test('full private UTF16 text and reversed selection survive projection', () {
    final value = fixture()
      ..['query'] = 'x' * 2000000
      ..['queryStart'] = 12
      ..['queryEnd'] = 4;
    final s = TxtTocManagerSnapshot(value);
    expect(s.text('query').length, 2000000);
    expect(s.number('queryEnd'), 4);
    expect(
      () => TxtTocManagerSnapshot({...value, 'queryStart': 2000001}),
      throwsFormatException,
    );
    expect(
      () => TxtTocManagerSnapshot({
        ...value,
        'rows': [
          (value['rows']! as List).first,
          (value['rows']! as List).first,
        ],
      }),
      throwsFormatException,
    );
  });
  test('commands serialize and field ack gates irreversible choice', () async {
    final bridge = TxtTocManagerBridge();
    await bridge.start();
    final gate = Completer<void>();
    before = (a) async {
      if (a['op'] == 'query') await gate.future;
    };
    final input = bridge.command('query', {
      'text': 'private',
      'start': 6,
      'end': 2,
    });
    final choose = bridge.command('confirmChoice');
    await Future<void>.delayed(Duration.zero);
    expect(commands.map((a) => a['op']), ['query']);
    gate.complete();
    await input;
    await choose;
    expect(commands.map((a) => a['op']), ['query', 'confirmChoice']);
    await bridge.close();
  });
  test(
    'rejected input prevents unrelated actions until its correct ack',
    () async {
      final bridge = TxtTocManagerBridge();
      await bridge.start();
      wrongAck = true;
      await expectLater(
        bridge.command('onlineInput', {
          'text': 'private',
          'start': 7,
          'end': 3,
        }),
        throwsStateError,
      );
      await expectLater(bridge.command('confirmOnline'), throwsStateError);
      expect(commands.length, 1);
      wrongAck = false;
      await bridge.command('onlineInput', {
        'text': 'private',
        'start': 7,
        'end': 3,
      });
      await bridge.command('confirmOnline');
      expect(commands.last['op'], 'confirmOnline');
      await bridge.close();
    },
  );
  test('Back resolves modal after earlier native input ack', () async {
    native = {...native, 'online': true};
    final bridge = TxtTocManagerBridge();
    await bridge.start();
    final gate = Completer<void>();
    before = (a) async {
      if (a['op'] == 'onlineInput') await gate.future;
    };
    final text = bridge.command('onlineInput', {
      'text': 'draft',
      'start': 5,
      'end': 1,
    });
    final back = bridge.navigateBack();
    gate.complete();
    await text;
    await back;
    expect(commands.map((a) => a['op']), ['onlineInput', 'cancelModal']);
    expect(bridge.latest!.dialog, isNull);
    await bridge.close();
  });
  test('retired binding ack cannot poison a new owner', () async {
    final bridge = TxtTocManagerBridge();
    await bridge.start();
    final gate = Completer<void>();
    before = (a) async {
      await gate.future;
    };
    final old = bridge.command('query', {'text': 'old', 'start': 3, 'end': 3});
    await Future<void>.delayed(Duration.zero);
    native = {...native, 'binding': 'two', 'revision': 1};
    await bridge.refresh();
    gate.complete();
    await expectLater(old, throwsStateError);
    expect(bridge.latest!.binding, 'two');
    expect(bridge.rejectedField, isNull);
    await bridge.close();
  });
  Future<void> show(
    WidgetTester tester, {
    bool picker = false,
    TxtTocManagerBridge? bridge,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: TxtTocManagerPage(picker: picker, bridge: bridge),
      ),
    );
    await tester.pumpAndSettle();
  }

  Finder tag(String id) => find.byWidgetPredicate(
    (w) => w is Semantics && w.properties.identifier == id,
  );
  testWidgets(
    'normal checkbox enables selection menu and shares its native identity',
    (tester) async {
      await show(tester);
      expect(
        tester
            .widget<PopupMenuButton<String>>(
              find.descendant(
                of: tag('txt-toc-selection-menu'),
                matching: find.byType(PopupMenuButton<String>),
              ),
            )
            .enabled,
        isFalse,
      );
      await tester.tap(
        find.descendant(
          of: tag('txt-toc-select-2'),
          matching: find.byType(Checkbox),
        ),
      );
      await tester.pumpAndSettle();
      expect(commands.last['id'], 2);
      await tester.tap(tag('txt-toc-selection-menu'));
      await tester.pumpAndSettle();
      await tester.tap(tag('txt-toc-share'));
      await tester.pumpAndSettle();
      expect(commands.last['op'], 'share');
    },
  );
  testWidgets(
    'picker chooses actual radio and confirm keeps native receipt path',
    (tester) async {
      native = {...native, 'picker': true};
      await show(tester, picker: true);
      await tester.tap(tag('txt-toc-select-2'));
      await tester.pumpAndSettle();
      expect(commands.last['op'], 'choose');
      expect(commands.last['id'], 2);
      await tester.tap(tag('txt-toc-confirm'));
      await tester.pumpAndSettle();
      expect(commands.last['op'], 'confirmChoice');
    },
  );
  testWidgets('business keyed row element follows reordered data', (
    tester,
  ) async {
    final bridge = TxtTocManagerBridge();
    await show(tester, bridge: bridge);
    final original = tester.element(find.byKey(const ValueKey(1)));
    final rows = (native['rows']! as List);
    native = {
      ...native,
      'rows': [rows[1], rows[2], rows[0]],
      'revision': 20,
    };
    await bridge.refresh();
    await tester.pumpAndSettle();
    expect(tester.element(find.byKey(const ValueKey(1))), same(original));
    await tester.tap(
      find.descendant(
        of: tag('txt-toc-select-1'),
        matching: find.byType(Checkbox),
      ),
    );
    await tester.pumpAndSettle();
    expect(commands.last['id'], 1);
  });
  testWidgets('online input retains raw selection and Back cancels its modal', (
    tester,
  ) async {
    native = {...native, 'online': true};
    await show(tester);
    final field = find.descendant(
      of: tag('txt-toc-online-input'),
      matching: find.byType(TextField),
    );
    await tester.enterText(field, 'https://private.invalid/');
    await tester.pumpAndSettle();
    expect(commands.last['op'], 'onlineInput');
    expect(commands.last['text'], 'https://private.invalid/');
    await tester.tap(tag('txt-toc-dialog-cancel'));
    await tester.pumpAndSettle();
    expect(commands.last['op'], 'cancelModal');
  });
  testWidgets(
    'picker actual backdrop cancels its owned host without choosing',
    (tester) async {
      native = {...native, 'picker': true};
      await show(tester, picker: true);
      await tester.tapAt(const Offset(5, 5));
      await tester.pumpAndSettle();
      expect(commands.last['op'], 'back');
      expect(commands.where((a) => a['op'] == 'confirmChoice'), isEmpty);
    },
  );
}
