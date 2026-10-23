import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:legado_app/embedded/embedded_source_management.dart';

import 'rss_manager_fixture.dart';

void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('legado/rss_manager');
  const events = MethodChannel('legado/rss_manager/state');
  const bootstrap = MethodChannel('legado/embedded_ui');
  late Map<String, Object?> native;
  late List<Map> commands;
  var locale = 'en';
  Future<void> emit() async {
    native['revision'] = (native['revision'] as int) + 1;
    await binding.defaultBinaryMessenger.handlePlatformMessage(
      events.name,
      const StandardMethodCodec().encodeSuccessEnvelope(jsonEncode(native)),
      (_) {},
    );
  }

  setUp(() {
    native = state();
    commands = [];
    locale = 'en';
    binding.defaultBinaryMessenger.setMockMethodCallHandler(
      bootstrap,
      (_) async => {'route': '/rss-manager', 'locale': locale, 'theme': 'dark'},
    );
    binding.defaultBinaryMessenger.setMockMethodCallHandler(
      events,
      (_) async => null,
    );
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if (call.method == 'snapshot') return jsonEncode(native);
      final args = call.arguments as Map;
      commands.add(args);
      switch (args['op']) {
        case 'query':
        case 'draft':
          final key = args['op'] as String;
          native[key] = args['value'];
          native['${key}Start'] = args['start'];
          native['${key}End'] = args['end'];
        case 'dialog':
          native['dialog'] = args['value'];
          native['targets'] = args['ids'];
        case 'all':
          native['selected'] = (native['rows'] as List)
              .map((r) => (r as Map)['id'])
              .toList();
        case 'invert':
          final selected = (native['selected'] as List).toSet();
          native['selected'] = (native['rows'] as List)
              .map((r) => (r as Map)['id'])
              .where((id) => !selected.contains(id))
              .toList();
        case 'cancel':
          native['dialog'] = null;
      }
      await emit();
      return jsonEncode(native);
    });
  });
  tearDown(() {
    for (final c in [channel, events, bootstrap]) {
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

  testWidgets(
    'real bootstrap opens RSS manager and query/draft precede import confirmation',
    (tester) async {
      await app(tester);
      expect(find.text('RSS sources'), findsOneWidget);
      await tester.enterText(
        find.byKey(const ValueKey('rss-manager-query')),
        'RSS',
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('rss-manager-menu')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Import URL'));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byKey(const ValueKey('rss-manager-draft')),
        'https://source.invalid',
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Confirm'));
      await tester.pumpAndSettle();
      expect(
        commands.map((c) => c['op']),
        containsAllInOrder(['query', 'dialog', 'draft', 'confirm']),
      );
      expect(
        commands.firstWhere((c) => c['op'] == 'draft')['value'],
        'https://source.invalid',
      );
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
    },
  );
  testWidgets(
    'native stable filters avoid translated-label coupling and bulk export remains reachable',
    (tester) async {
      await app(tester);
      await tester.tap(find.byKey(const ValueKey('rss-manager-filters')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Enabled'));
      await tester.pumpAndSettle();
      expect(commands.last['op'], 'filter');
      expect(commands.last['value'], 'enabled');
      await tester.tap(find.byKey(const ValueKey('rss-manager-bulk')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Export'));
      await tester.pumpAndSettle();
      expect(commands.last['op'], 'effect');
      expect(commands.last['value'], 'Export');
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
    },
  );
  testWidgets(
    'Chinese dark bootstrap restores a native draft and modal cancel without a mutation',
    (tester) async {
      locale = 'zh';
      native = {...state(draft: '保留草稿'), 'dialog': 'AddGroup'};
      await app(tester);
      expect(find.text('订阅源管理'), findsOneWidget);
      expect(find.text('保留草稿'), findsOneWidget);
      await tester.tap(find.text('取消'));
      await tester.pumpAndSettle();
      expect(commands.map((c) => c['op']), ['cancel']);
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
    },
  );
  testWidgets(
    'raw cancellation of an accepted slide cancels instead of committing',
    (tester) async {
      final ids = ['a' * 64, 'b' * 64, 'c' * 64];
      native['selected'] = <String>[];
      native['rows'] = [
        for (var i = 0; i < ids.length; i++)
          {'id': ids[i], 'name': 'RSS $i', 'group': null, 'enabled': true},
      ];
      await app(tester);
      Finder checkbox(int index) => find.byWidgetPredicate(
        (widget) =>
            widget is Semantics &&
            widget.properties.identifier == 'rss-manager-select-${ids[index]}',
      );
      Future<TestGesture> heldSlide() async {
        final start = tester.getCenter(checkbox(0));
        final target = tester.getCenter(checkbox(2));
        final gesture = await tester.startGesture(start);
        for (var i = 1; i <= 20; i++) {
          await gesture.moveTo(Offset.lerp(start, target, i / 20)!);
          await tester.pump(const Duration(milliseconds: 10));
        }
        expect(
          commands.any((command) => command['op'] == 'beginSelection'),
          isTrue,
        );
        expect(
          commands.any((command) => command['op'] == 'selectionRange'),
          isTrue,
        );
        return gesture;
      }

      final cancelled = await heldSlide();
      await cancelled.cancel();
      await tester.pumpAndSettle();
      expect(
        commands.where((command) => command['op'] == 'cancelGesture'),
        hasLength(1),
      );
      expect(
        commands.any((command) => command['op'] == 'finishSelection'),
        isFalse,
      );
      expect(commands.any((command) => command['op'] == 'selected'), isFalse);
      commands.clear();
      final completed = await heldSlide();
      await completed.up();
      await tester.pumpAndSettle();
      expect(
        commands.where((command) => command['op'] == 'finishSelection'),
        hasLength(1),
      );
      expect(
        commands.any((command) => command['op'] == 'cancelGesture'),
        isFalse,
      );
      expect(commands.any((command) => command['op'] == 'selected'), isFalse);
    },
  );

  testWidgets(
    'two checkbox pointers cannot replace the pending slide owner or commit its cancellation',
    (tester) async {
      final ids = ['a' * 64, 'b' * 64, 'c' * 64];
      native['selected'] = <String>[];
      native['rows'] = [
        for (var i = 0; i < ids.length; i++)
          {'id': ids[i], 'name': 'RSS $i', 'group': null, 'enabled': true},
      ];
      await app(tester);
      Finder checkbox(int index) => find.byWidgetPredicate(
        (w) =>
            w is Semantics &&
            w.properties.identifier == 'rss-manager-select-${ids[index]}',
      );
      final live = <TestGesture>{};
      Future<TestGesture> down(int index) async {
        final gesture = await tester.startGesture(
          tester.getCenter(checkbox(index)),
        );
        live.add(gesture);
        return gesture;
      }

      Future<void> move(TestGesture gesture, Offset start, Offset end) async {
        for (var i = 1; i <= 20; i++) {
          await gesture.moveTo(Offset.lerp(start, end, i / 20)!);
          await tester.pump(const Duration(milliseconds: 10));
        }
      }

      Future<void> cancel(TestGesture gesture) async {
        live.remove(gesture);
        await gesture.cancel();
      }

      Future<void> up(TestGesture gesture) async {
        live.remove(gesture);
        await gesture.up();
      }

      try {
        final first = tester.getCenter(checkbox(0));
        final middle = tester.getCenter(checkbox(1));
        final last = tester.getCenter(checkbox(2));
        final owner = await down(0);
        final foreign = await down(
          1,
        ); // Both down before either crosses touch slop.
        await move(owner, first, last);
        expect(
          commands.where((c) => c['op'] == 'beginSelection'),
          hasLength(1),
        );
        expect(commands.where((c) => c['op'] == 'selectionRange').last['ids'], [
          ids[0],
          ids[2],
        ]);
        commands.clear();
        await move(foreign, middle, first);
        await cancel(foreign);
        await tester.pump();
        expect(
          commands.any(
            (c) => [
              'beginSelection',
              'finishSelection',
              'cancelGesture',
              'selected',
            ].contains(c['op']),
          ),
          isFalse,
        );
        expect(
          commands
              .where((c) => c['op'] == 'selectionRange')
              .every(
                (c) =>
                    (c['ids'] as List).first == ids[0] &&
                    (c['ids'] as List).last == ids[2],
              ),
          isTrue,
        );
        await cancel(owner);
        await tester.pumpAndSettle();
        expect(commands.where((c) => c['op'] == 'cancelGesture'), hasLength(1));
        expect(
          commands.any(
            (c) => ['finishSelection', 'selected'].contains(c['op']),
          ),
          isFalse,
        );

        commands.clear();
        final nextOwner = await down(0);
        final retiredTap = await down(1);
        await move(nextOwner, first, last);
        await up(nextOwner);
        await tester.pumpAndSettle();
        expect(
          commands.where((c) => c['op'] == 'finishSelection'),
          hasLength(1),
        );
        await up(
          retiredTap,
        ); // Its tap began while another pointer owned the session.
        await tester.pumpAndSettle();
        expect(commands.any((c) => c['op'] == 'selected'), isFalse);
        expect(commands.any((c) => c['op'] == 'cancelGesture'), isFalse);
        commands.clear();
        await tester.tap(
          checkbox(1),
        ); // A genuinely new tap after release remains usable.
        await tester.pumpAndSettle();
        expect(commands.where((c) => c['op'] == 'selected'), hasLength(1));
        expect(commands.single['ids'], [ids[1]]);
      } finally {
        for (final gesture in live.toList()) {
          await gesture.cancel();
        }
        await tester.pumpWidget(const SizedBox());
        await tester.pumpAndSettle();
      }
    },
  );

  testWidgets(
    'all six native dialogs block the entire Android host and preserve original scrim cancellation',
    (tester) async {
      await app(tester);
      final morePoint = tester.getCenter(
        find.byKey(const ValueKey('rss-manager-menu')),
      );
      final filterPoint = tester.getCenter(
        find.byKey(const ValueKey('rss-manager-filters')),
      );
      final barrier = find.byKey(const ValueKey('rss-manager-modal-barrier'));
      const dialogs = [
        'Delete',
        'AddGroup',
        'RemoveGroup',
        'ImportUrl',
        'ExportResult',
        'Passphrase',
      ];
      for (final dialog in dialogs) {
        native['dialog'] = dialog;
        native['draft'] = 'retained-$dialog';
        native['draftStart'] = (native['draft'] as String).length;
        native['draftEnd'] = native['draftStart'];
        native['targets'] = ['a' * 64];
        await emit();
        await tester.pumpAndSettle();
        expect(barrier, findsOneWidget);
        final covered = tester.getRect(barrier);
        expect(covered.contains(morePoint), isTrue);
        expect(covered.contains(filterPoint), isTrue);
        final field = find.byKey(const ValueKey('rss-manager-draft'));
        if (!['Delete', 'ExportResult', 'Passphrase'].contains(dialog)) {
          await tester.enterText(field, 'edited-$dialog');
          await tester.pumpAndSettle();
          expect(native['draft'], 'edited-$dialog');
          expect(commands.last['op'], 'draft');
        } else if (dialog != 'Delete') {
          expect(tester.widget<TextField>(field).readOnly, isTrue);
        }
        final retained = native['draft'];
        commands.clear();
        // A real tap on inert card content must not dismiss or mutate the draft.
        await tester.tapAt(
          tester.getCenter(
            find
                .descendant(of: find.byType(Card), matching: find.byType(Text))
                .first,
          ),
        );
        await tester.pumpAndSettle();
        expect(commands, isEmpty);
        expect(native['dialog'], dialog);
        expect(native['draft'], retained);
        // AppBar points may hit card content or the scrim, but never its old menu.
        for (final point in [morePoint, filterPoint]) {
          commands.clear();
          await tester.tapAt(point);
          await tester.pumpAndSettle();
          expect(commands.length, lessThanOrEqualTo(1));
          expect(commands.every((c) => c['op'] == 'cancel'), isTrue);
          expect(find.text('Add source'), findsNothing);
          expect(find.text('Enabled'), findsNothing);
          if (commands.isEmpty) {
            expect(native['draft'], retained);
            expect(native['dialog'], dialog);
          }
          // Reopen the same native-owned dialog after an intentional scrim cancel.
          native['dialog'] = dialog;
          native['draft'] = retained;
          await emit();
          await tester.pumpAndSettle();
        }
        commands.clear();
        await tester.tapAt(const Offset(2, 2));
        await tester.pumpAndSettle();
        expect(commands.map((c) => c['op']), ['cancel']);
        expect(native['dialog'], isNull);
        expect(barrier, findsNothing);
      }
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
    },
  );
  testWidgets(
    'real reorder preserves its native session until animated drop and cancels only its own pointer',
    (tester) async {
      final ids = ['a' * 64, 'b' * 64, 'c' * 64];
      native['rows'] = [
        for (var i = 0; i < ids.length; i++)
          {'id': ids[i], 'name': 'RSS $i', 'group': null, 'enabled': true},
      ];
      await app(tester);
      Finder handle(int index) => find.descendant(
        of: find.byKey(ValueKey('rss-manager-row-${ids[index]}')),
        matching: find.byType(ReorderableDragStartListener),
      );
      List<Map> reorderCommands() => [
        for (final args in commands)
          if ([
            'beginDrag',
            'dragTo',
            'finishDrag',
            'cancelGesture',
          ].contains(args['op']))
            Map.of(args)
              ..remove('binding')
              ..remove('sequence'),
      ];
      Future<TestGesture> held(int from, int to) async {
        final start = tester.getCenter(handle(from));
        final target = tester.getCenter(handle(to));
        final gesture = await tester.startGesture(start);
        await tester.pump(const Duration(milliseconds: 600));
        // Match a real pointer's continuous motion rather than teleporting
        // across rows before Flutter's gap geometry can update.
        final crossed = start + const Offset(0, 24);
        await gesture.moveTo(crossed);
        await tester.pump(const Duration(milliseconds: 25));
        final end =
            target +
            Offset(
              0,
              to == from
                  ? 0
                  : to > from
                  ? 20
                  : -20,
            );
        for (var step = 1; step <= 20; step++) {
          await gesture.moveTo(Offset.lerp(crossed, end, step / 20)!);
          await tester.pump(const Duration(milliseconds: 25));
        }
        await tester.pump(const Duration(milliseconds: 400));
        expect(reorderCommands(), [
          {
            'op': 'beginDrag',
            'ids': [ids[from]],
          },
        ]);
        return gesture;
      }

      for (final direction in [(0, 2), (2, 0)]) {
        commands.clear();
        final gesture = await held(direction.$1, direction.$2);
        await gesture.up();
        await tester.pump(const Duration(milliseconds: 16));
        // onReorderEnd runs at pointer-up, before the 250ms proxy animation.
        expect(reorderCommands(), [
          {
            'op': 'beginDrag',
            'ids': [ids[direction.$1]],
          },
        ]);
        await tester.pumpAndSettle();
        expect(reorderCommands(), [
          {
            'op': 'beginDrag',
            'ids': [ids[direction.$1]],
          },
          {
            'op': 'dragTo',
            'ids': [ids[direction.$2]],
            'after': direction.$2 > direction.$1,
          },
          {'op': 'finishDrag'},
        ]);
      }
      commands.clear();
      final unchanged = await held(1, 1);
      await unchanged.up();
      await tester.pumpAndSettle();
      expect(reorderCommands(), [
        {
          'op': 'beginDrag',
          'ids': [ids[1]],
        },
        {'op': 'cancelGesture'},
      ]);
      commands.clear();
      final cancelled = await held(0, 2);
      await cancelled.cancel();
      await tester.pumpAndSettle();
      expect(reorderCommands(), [
        {
          'op': 'beginDrag',
          'ids': [ids[0]],
        },
        {'op': 'cancelGesture'},
      ]);
      commands.clear();
      final firstStart = tester.getCenter(handle(0));
      final secondStart = tester.getCenter(handle(1));
      for (final index in [0, 1]) {
        final listeners = find
            .descendant(of: handle(index), matching: find.byType(Listener))
            .evaluate()
            .map((e) => e.renderObject)
            .toList();
        final hits = tester
            .hitTestOnBinding(tester.getCenter(handle(index)))
            .path
            .map((e) => e.target)
            .toList();
        expect(listeners.any(hits.contains), isTrue);
      }
      final pendingFirst = await tester.startGesture(firstStart);
      final pendingSecond = await tester.startGesture(secondStart);
      // Immediate recognition can start both drags in consecutive down events.
      // The SDK cancels its first visual session when the second handle takes over.
      await tester.pump();
      final takeover = [
        {
          'op': 'beginDrag',
          'ids': [ids[0]],
        },
        {'op': 'cancelGesture'},
        {
          'op': 'beginDrag',
          'ids': [ids[1]],
        },
      ];
      expect(reorderCommands(), takeover);
      await pendingFirst.moveTo(firstStart + const Offset(0, 24));
      await pendingFirst.cancel();
      await tester.pump();
      expect(reorderCommands(), takeover);
      // Permission may change while held; the owning cancellation must still end it.
      native['busy'] = true;
      await emit();
      // Flush the stream delivery, then build its disabled controls. An
      // indeterminate busy animation intentionally cannot pumpAndSettle.
      await tester.pump();
      await tester.pump();
      expect(
        tester.widget<ReorderableDragStartListener>(handle(0)).enabled,
        isFalse,
      );
      await pendingSecond.cancel();
      await tester.pump();
      expect(reorderCommands(), [
        ...takeover,
        {'op': 'cancelGesture'},
      ]);
      native['busy'] = false;
      await emit();
      await tester.pumpAndSettle();
      commands.clear();
      final next = await held(0, 2);
      await next.up();
      await tester.pumpAndSettle();
      expect(reorderCommands().last, {'op': 'finishDrag'});
      commands.clear();
      native['busy'] = true;
      await emit();
      // Flush the stream delivery, then build its disabled controls. An
      // indeterminate busy animation intentionally cannot pumpAndSettle.
      await tester.pump();
      await tester.pump();
      expect(
        tester.widget<ReorderableDragStartListener>(handle(0)).enabled,
        isFalse,
      );
      final disabled = await tester.startGesture(tester.getCenter(handle(0)));
      await disabled.moveBy(const Offset(0, 200));
      await disabled.cancel();
      await tester.pump();
      expect(reorderCommands(), isEmpty);
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
    },
  );
  testWidgets(
    'unloaded and empty rows never overwrite saved scroll before native rows arrive',
    (tester) async {
      native = {
        ...state(),
        'loaded': false,
        'rows': [],
        'scrollIndex': 8,
        'scrollOffset': 16,
      };
      await app(tester);
      expect(commands.where((c) => c['op'] == 'scroll'), isEmpty);
      native = {
        ...native,
        'loaded': true,
        'rows': List.generate(
          30,
          (i) => {
            'id': i.toRadixString(16).padLeft(64, '0'),
            'name': 'RSS $i',
            'group': null,
            'enabled': true,
          },
        ),
      };
      await emit();
      await tester.pumpAndSettle();
      final list = tester.widget<ReorderableListView>(
        find.byType(ReorderableListView),
      );
      expect(list.scrollController!.offset, 8 * 72 + 16);
      expect(
        commands.where(
          (c) => c['op'] == 'scroll' && c['index'] == 0 && c['offset'] == 0,
        ),
        isEmpty,
      );
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
    },
  );
  testWidgets(
    'initial snapshot failure retries the stream without requiring a binding',
    (tester) async {
      var failed = true;
      var reads = 0;
      binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
        call,
      ) async {
        expect(call.method, 'snapshot');
        reads++;
        if (failed) throw PlatformException(code: 'storage_error');
        return jsonEncode(native);
      });
      await app(tester);
      expect(find.text('Retry'), findsOneWidget);
      failed = false;
      await tester.tap(find.text('Retry'));
      await tester.pumpAndSettle();
      expect(reads, 2);
      expect(find.byKey(const ValueKey('rss-manager-query')), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
    },
  );
  testWidgets('cursor-only selection changes receive native field ACKs', (
    tester,
  ) async {
    await app(tester);
    await tester.enterText(
      find.byKey(const ValueKey('rss-manager-query')),
      'RSS',
    );
    await tester.pumpAndSettle();
    final field = tester.widget<TextField>(
      find.byKey(const ValueKey('rss-manager-query')),
    );
    field.controller!.selection = const TextSelection(
      baseOffset: 1,
      extentOffset: 2,
    );
    await tester.pumpAndSettle();
    expect(commands.last['op'], 'query');
    expect(commands.last['start'], 1);
    expect(commands.last['end'], 2);
    expect(native['queryStart'], 1);
    expect(native['queryEnd'], 2);
    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  });

  testWidgets(
    'select all toggles off visible selection on its second activation',
    (tester) async {
      native['selected'] = [];
      await app(tester);
      await tester.tap(find.text('Select all'));
      await tester.pumpAndSettle();
      expect(native['selected'], ['a' * 64]);
      await tester.tap(find.text('Select all'));
      await tester.pumpAndSettle();
      expect(native['selected'], isEmpty);
      expect(commands.map((c) => c['op']), ['all', 'invert']);
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
    },
  );
}
