import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:legado_app/embedded/embedded_source_management.dart';
import 'package:legado_app/embedded/source_manager/source_manager_labels.dart';
import 'package:legado_app/embedded/source_manager/source_manager_state.dart';

import 'manager_fixture.dart';

void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  const bootstrap = MethodChannel('legado/embedded_ui');
  const channel = MethodChannel('legado/source_manager');
  const eventChannel = MethodChannel('legado/source_manager/state');
  late Map<String, Object?> native;
  late List<Map<String, Object?>> commands;
  bool listening = false;
  Future<void> Function(Map<String, Object?>)? inputBarrier;
  Future<void> publish(WidgetTester tester, Map<String, Object?> value) async {
    native = value;
    await binding.defaultBinaryMessenger.handlePlatformMessage(
      eventChannel.name,
      const StandardMethodCodec().encodeSuccessEnvelope(jsonEncode(value)),
      (_) {},
    );
    if (value['checkMessage'] == null) {
      await tester.pumpAndSettle();
    } else {
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
    }
  }

  setUp(() {
    native = managerState();
    commands = [];
    listening = false;
    inputBarrier = null;
    binding.defaultBinaryMessenger.setMockMethodCallHandler(bootstrap, (
      call,
    ) async {
      expectSync(call.method, 'bootstrap');
      return {'route': '/source-manager', 'locale': 'en', 'theme': 'dark'};
    });
    binding.defaultBinaryMessenger.setMockMethodCallHandler(eventChannel, (
      call,
    ) async {
      listening = call.method == 'listen';
      return null;
    });
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if (call.method == 'snapshot') return jsonEncode(native);
      expectSync(call.method, 'command');
      final command = Map<String, Object?>.from(call.arguments as Map);
      commands.add(command);
      await inputBarrier?.call(command);
      return null;
    });
  });
  tearDown(() {
    for (final c in [bootstrap, channel, eventChannel]) {
      binding.defaultBinaryMessenger.setMockMethodCallHandler(c, null);
    }
  });
  Future<void> app(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1200, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      const ProviderScope(child: EmbeddedSourceManagementApp()),
    );
    await tester.pumpAndSettle();
  }

  Future<void> stop(WidgetTester tester) async {
    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  }

  Future<void> pick(WidgetTester tester, String menu, String value) async {
    await tester.tap(find.byKey(ValueKey(menu)));
    await tester.pumpAndSettle();
    final entry = find.byKey(ValueKey(value));
    expect(entry, findsOneWidget);
    await tester.ensureVisible(entry);
    await tester.pumpAndSettle();
    await tester.tap(entry);
    await tester.pumpAndSettle();
  }

  testWidgets(
    'every main menu, group filter, seven sort and direction commands remain reachable',
    (tester) async {
      await app(tester);
      expect(find.text('Source a (Group A)'), findsOneWidget);
      for (final action in sourceManagerMainActions) {
        await pick(
          tester,
          'source-manager-menu',
          'source-manager-action:$action',
        );
        expect(commands.last, {'op': 'action', 'action': action, 'key': ''});
      }
      for (final sort in SourceManagerSnapshot.sorts) {
        await pick(tester, 'source-manager-menu', 'source-manager-sort:$sort');
        expect(commands.last, {'op': 'sort', 'value': sort});
      }
      await pick(tester, 'source-manager-menu', 'source-manager-group:Group B');
      expect(commands.last, {'op': 'query', 'value': 'group:Group B'});
      await tester.enterText(
        find.byKey(const ValueKey('source-manager-query')),
        'group:typed',
      );
      await tester.pumpAndSettle();
      await tester.pump();
      expect(commands.last, {'op': 'query', 'value': 'group:typed'});
      expect(tester.takeException(), isNull);
      await stop(tester);
    },
  );
  testWidgets(
    'selection, all bulk actions and mutations, status and row actions use authoritative native state',
    (tester) async {
      await app(tester);
      await tester.tap(find.byKey(const ValueKey('select:a')));
      await tester.pumpAndSettle();
      expect(commands.last, {'op': 'toggle', 'key': 'a'});
      expect(
        tester.widget<Checkbox>(find.byKey(const ValueKey('select:a'))).value,
        false,
      );
      await publish(tester, {
        ...native,
        'selected': ['a', 'hidden'],
        'showStatus': true,
      });
      expect(
        tester.widget<Checkbox>(find.byKey(const ValueKey('select:a'))).value,
        true,
      );
      expect(find.text('Selected 1/3'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('source-manager-select-all')));
      await tester.pumpAndSettle();
      expect(commands.last['op'], 'selectAll');
      await tester.tap(find.byKey(const ValueKey('source-manager-invert')));
      await tester.pumpAndSettle();
      expect(commands.last['op'], 'invert');
      for (final mutation in sourceManagerBulkMutations) {
        await pick(
          tester,
          'source-manager-selection-menu',
          'source-manager-mutation:$mutation',
        );
        expect(commands.last, {'op': 'mutation', 'mutation': mutation});
      }
      for (final action in sourceManagerBulkActions) {
        await pick(
          tester,
          'source-manager-selection-menu',
          'source-manager-action:$action',
        );
        expect(commands.last, {'op': 'action', 'action': action, 'key': ''});
      }
      for (final status in ['', 'NEEDS_CHECK', 'PASSED', 'FAILED']) {
        await tester.tap(find.byKey(ValueKey('source-manager-status:$status')));
        await tester.pumpAndSettle();
        expect(commands.last, {'op': 'status', 'value': status});
      }
      await tester.tap(find.byKey(const ValueKey('enabled:a')));
      await tester.pumpAndSettle();
      expect(commands.last, {
        'op': 'mutation',
        'mutation': 'DISABLE',
        'key': 'a',
      });
      for (final action in [
        'top',
        'bottom',
        'up',
        'down',
        'login',
        'search',
        'debug',
        'delete',
        'explore',
      ]) {
        await pick(
          tester,
          'source-manager-row-menu:a',
          'source-manager-action:$action',
        );
        expect(commands.last, {'op': 'action', 'action': action, 'key': 'a'});
      }
      await tester.tap(find.text('Edit').first);
      await tester.pumpAndSettle();
      expect(commands.last, {'op': 'action', 'action': 'edit', 'key': 'a'});
      expect(find.text('JS source'), findsOneWidget);
      await publish(tester, {...native, 'byDomain': true, 'canMove': false});
      expect(find.text('example.org'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('source-manager-row-menu:a')));
      await tester.pumpAndSettle();
      expect(
        find.byKey(const ValueKey('source-manager-action:top')),
        findsNothing,
      );
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      await stop(tester);
    },
  );
  testWidgets(
    'all seven restored dialogs preserve drafts, feedback, history, passphrase and native confirmation',
    (tester) async {
      await app(tester);
      for (final dialog in SourceManagerSnapshot.dialogs) {
        await publish(tester, {
          ...native,
          'dialog': dialog,
          'draft': 'restored-$dialog',
          'feedback': {
            'url': 'https://example.org/export',
            'summary': 'Export summary',
            'canSharePassphrase': true,
            'passphrase': 'fixture-only',
          },
        });
        expect(
          find.byKey(const ValueKey('source-manager-dialog')),
          findsOneWidget,
        );
        if (dialog != 'DELETE') {
          final field = find.byKey(const ValueKey('source-manager-draft'));
          expect(
            tester.widget<TextField>(field).controller!.text,
            'restored-$dialog',
          );
          await tester.enterText(field, 'typed-$dialog');
          await tester.pumpAndSettle();
          expect(commands.last, {'op': 'draft', 'value': 'typed-$dialog'});
        }
        if (dialog == 'IMPORT') {
          await tester.tap(find.text('https://example.org/sources.json'));
          await tester.pumpAndSettle();
          expect(commands.last, {
            'op': 'draft',
            'value': 'https://example.org/sources.json',
          });
          await tester.tap(find.byTooltip('Forget import history'));
          await tester.pumpAndSettle();
          expect(commands.last, {
            'op': 'forgetImport',
            'value': 'https://example.org/sources.json',
          });
        }
        if (dialog == 'ADD_GROUP' || dialog == 'REMOVE_GROUP') {
          await tester.tap(find.text('Group B'));
          await tester.pumpAndSettle();
          expect(commands.last, {'op': 'draft', 'value': 'Group B'});
        }
        if (dialog == 'CHECK') {
          await tester.tap(find.text('Check settings'));
          await tester.pumpAndSettle();
          expect(commands.last, {
            'op': 'action',
            'action': 'check-config',
            'key': '',
          });
        }
        if (dialog == 'EXPORT_SUCCESS') {
          expect(find.text('Export summary'), findsOneWidget);
          await tester.tap(find.widgetWithText(TextButton, 'Share passphrase'));
          await tester.pumpAndSettle();
          expect(commands.last, {'op': 'passphrase'});
        }
        await tester.tap(find.byKey(const ValueKey('source-manager-confirm')));
        await tester.pumpAndSettle();
        expect(commands.last, {'op': 'confirm'});
        expect(
          find.byKey(const ValueKey('source-manager-dialog')),
          findsOneWidget,
        ); // No optimistic close before native acceptance.
        await publish(tester, {...native, 'dialog': null});
        expect(
          find.byKey(const ValueKey('source-manager-dialog')),
          findsNothing,
        );
      }
      await stop(tester);
    },
  );
  testWidgets(
    'back dismisses dialog, clears hidden selection then requests native navigation; progress can cancel',
    (tester) async {
      await app(tester);
      await publish(tester, {
        ...native,
        'dialog': 'DELETE',
        'selected': ['a', 'hidden'],
      });
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(commands.last, {'op': 'dismiss'});
      await publish(tester, {...native, 'dialog': null});
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(commands.last, {
        'op': 'slide',
        'keys': <String>[],
        'selectedAtStart': <String>[],
      });
      await publish(tester, {
        ...native,
        'selected': <String>[],
        'checkMessage': 'Checking 1/3',
        'debugMessages': {'a': 'working'},
      });
      await tester.tap(find.text('Cancel'));
      await tester.pump();
      expect(commands.last, {
        'op': 'action',
        'action': 'cancel-check',
        'key': '',
      });
      await tester.binding.handlePopRoute();
      await tester.pump();
      expect(commands.last, {'op': 'action', 'action': 'back', 'key': ''});
      await stop(tester);
      expect(listening, false);
    },
  );
  testWidgets(
    'long-press selection and reorder emit the original interval and move protocol',
    (tester) async {
      native['selected'] = ['hidden'];
      await app(tester);
      final selection = await tester.startGesture(
        tester.getCenter(find.byKey(const ValueKey('select:a'))),
      );
      await tester.pump(const Duration(milliseconds: 600));
      await selection.moveTo(
        tester.getCenter(find.byKey(const ValueKey('select:c'))),
      );
      await tester.pump(const Duration(milliseconds: 40));
      await selection.up();
      await tester.pumpAndSettle();
      expect(commands.where((e) => e['op'] == 'slide').last, {
        'op': 'slide',
        'keys': ['a', 'b', 'c'],
        'selectedAtStart': ['hidden'],
      });
      commands.clear();
      final drag = await tester.startGesture(
        tester.getCenter(
          find.byKey(const ValueKey('source-manager-row-menu:a')),
        ),
      );
      await tester.pump(const Duration(milliseconds: 600));
      await drag.moveTo(
        tester.getCenter(
          find.byKey(const ValueKey('source-manager-row-menu:c')),
        ),
      );
      await tester.pump(const Duration(milliseconds: 500));
      await drag.up();
      await tester.pumpAndSettle();
      expect(commands.first, {'op': 'beginDrag', 'key': 'a'});
      expect(commands.any((e) => e['op'] == 'previewDrag'), true);
      expect(commands.last, {'op': 'finishDrag', 'cancelled': false});
      await stop(tester);
    },
  );
  testWidgets(
    'reorder supports both directions, unchanged drops, cancellation and disabled native permission',
    (tester) async {
      await app(tester);
      Future<void> drag(String from, String to, {bool cancel = false}) async {
        final start = tester.getCenter(
          find.byKey(ValueKey('source-manager-row-menu:$from')),
        );
        final target = tester.getCenter(
          find.byKey(ValueKey('source-manager-row-menu:$to')),
        );
        final gesture = await tester.startGesture(start);
        await tester.pump(const Duration(milliseconds: 600));
        await gesture.moveTo(
          target +
              Offset(
                0,
                target.dy == start.dy
                    ? 0
                    : target.dy > start.dy
                    ? 140
                    : -80,
              ),
        );
        await tester.pump(const Duration(milliseconds: 400));
        if (cancel) {
          await gesture.cancel();
        } else {
          await gesture.up();
        }
        await tester.pumpAndSettle();
      }

      await drag('a', 'c');
      expect(commands.first, {'op': 'beginDrag', 'key': 'a'});
      expect(commands.where((e) => e['op'] == 'previewDrag').single, {
        'op': 'previewDrag',
        'index': 2,
      });
      expect(commands.last, {'op': 'finishDrag', 'cancelled': false});
      commands.clear();
      await drag('c', 'a');
      expect(commands.first, {'op': 'beginDrag', 'key': 'c'});
      expect(commands.where((e) => e['op'] == 'previewDrag').single, {
        'op': 'previewDrag',
        'index': 0,
      });
      expect(commands.last, {'op': 'finishDrag', 'cancelled': false});
      commands.clear();
      await drag('b', 'b');
      expect(commands, [
        {'op': 'beginDrag', 'key': 'b'},
        {'op': 'finishDrag', 'cancelled': true},
      ]);
      commands.clear();
      await drag('a', 'c', cancel: true);
      expect(commands, [
        {'op': 'beginDrag', 'key': 'a'},
        {'op': 'finishDrag', 'cancelled': true},
      ]);
      commands.clear();
      await drag('a', 'c'); // Cancellation leaves the next drag usable.
      expect(commands.last, {'op': 'finishDrag', 'cancelled': false});
      await publish(tester, {...native, 'canMove': false});
      commands.clear();
      await drag('a', 'c');
      expect(commands, isEmpty);
      await stop(tester);
    },
  );
  testWidgets('long press drag auto scrolls beyond the initial viewport', (
    tester,
  ) async {
    native = managerState(ids: List.generate(50, (i) => 'row$i'));
    await app(tester);
    final menu = find.byKey(const ValueKey('source-manager-row-menu:row0'));
    final gesture = await tester.startGesture(tester.getCenter(menu));
    await tester.pump(const Duration(milliseconds: 600));
    final list = tester.getRect(find.byType(ReorderableListView));
    await gesture.moveTo(Offset(list.right - 30, list.bottom - 4));
    for (var i = 0; i < 30; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    await gesture.up();
    await tester.pumpAndSettle();
    final target =
        commands.singleWhere((e) => e['op'] == 'previewDrag')['index']! as int;
    expect(target, greaterThan(10));
    expect(commands.last, {'op': 'finishDrag', 'cancelled': false});
    await stop(tester);
  });
  testWidgets(
    'focused query accepts native changes without replacing rapid input with older acknowledgements',
    (tester) async {
      await app(tester);
      final field = find.byKey(const ValueKey('source-manager-query'));
      await tester.enterText(field, 'a');
      final completion = Completer<void>();
      inputBarrier = (args) async {
        if (args['op'] == 'query' && args['value'] == 'ab') {
          await completion.future;
        }
      };
      await tester.enterText(field, 'ab');
      await publish(tester, {...native, 'query': 'a'});
      expect(tester.widget<TextField>(field).controller!.text, 'ab');
      completion.complete();
      await publish(tester, {...native, 'query': 'ab'});
      expect(tester.widget<TextField>(field).controller!.text, 'ab');
      await publish(tester, {...native, 'query': 'group:Native'});
      expect(tester.widget<TextField>(field).controller!.text, 'group:Native');
      await publish(tester, {...native, 'query': ''});
      expect(tester.widget<TextField>(field).controller!.text, '');
      expect(commands.where((e) => e['op'] == 'query').map((e) => e['value']), [
        'a',
        'ab',
      ]);
      await stop(tester);
    },
  );
  testWidgets(
    'coalesced typing back to original cannot hide a later native group choice',
    (tester) async {
      await app(tester);
      final query = find.byKey(const ValueKey('source-manager-query'));
      await tester.showKeyboard(query);
      tester.testTextInput.enterText('group:Group B');
      tester.testTextInput.enterText(
        '',
      ); // No intervening Flutter frame, as with conflated StateFlow.
      await tester.pumpAndSettle();
      await pick(tester, 'source-manager-menu', 'source-manager-group:Group B');
      await publish(tester, {...native, 'query': 'group:Group B'});
      expect(tester.widget<TextField>(query).controller!.text, 'group:Group B');
      await publish(tester, {...native, 'dialog': 'ADD_GROUP', 'draft': ''});
      final draft = find.byKey(const ValueKey('source-manager-draft'));
      await tester.showKeyboard(draft);
      tester.testTextInput.enterText('Group B');
      tester.testTextInput.enterText('');
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(TextButton, 'Group B'));
      await tester.pumpAndSettle();
      expect(commands.last, {'op': 'draft', 'value': 'Group B'});
      await publish(tester, {...native, 'draft': 'Group B'});
      expect(tester.widget<TextField>(draft).controller!.text, 'Group B');
      await tester.tap(find.byKey(const ValueKey('source-manager-confirm')));
      await tester.pumpAndSettle();
      expect(commands.last, {'op': 'confirm'});
      await stop(tester);
    },
  );
  testWidgets(
    'system back closes popup menus before selection or native navigation',
    (tester) async {
      await app(tester);
      for (final menu in [
        'source-manager-menu',
        'source-manager-row-menu:a',
        'source-manager-selection-menu',
      ]) {
        await publish(tester, {
          ...native,
          'selected': menu == 'source-manager-selection-menu'
              ? ['a', 'hidden']
              : <String>[],
        });
        commands.clear();
        await tester.tap(find.byKey(ValueKey(menu)));
        await tester.pumpAndSettle();
        expect(find.byType(PopupMenuItem<String>), findsWidgets);
        await tester.binding.handlePopRoute();
        await tester.pumpAndSettle();
        expect(find.byType(PopupMenuItem<String>), findsNothing);
        expect(commands, isEmpty);
      }
      await stop(tester);
    },
  );
}
