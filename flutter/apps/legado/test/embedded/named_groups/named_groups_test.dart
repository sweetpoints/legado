import 'dart:async';
import 'dart:ui' show SemanticsAction, SemanticsActionEvent;

import 'package:flutter/semantics.dart' show SemanticsNode;

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:legado_app/l10n/generated/app_localizations.dart';
import 'package:legado_app/embedded/named_groups/named_groups_bridge.dart';
import 'package:legado_app/embedded/named_groups/named_groups_page.dart';

Map<String, Object?> fixture({
  int generation = 1,
  int revision = 1,
  bool editing = false,
  String kind = 'book',
}) => {
  'generation': generation,
  'revision': revision,
  'kind': kind,
  'showDone': kind == 'rss',
  'loading': false,
  'busy': false,
  'error': null,
  'editing': editing,
  'recoveryFailed': false,
  'mutationUncertain': false,
  'original': editing ? 'One' : null,
  'name': editing ? 'One' : '',
  'start': editing ? 3 : 0,
  'end': editing ? 3 : 0,
  'groups': [
    {'id': '1', 'name': 'One'},
    {'id': '2', 'name': 'Two'},
  ],
};
void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('legado/named_groups');
  const events = MethodChannel('legado/named_groups/state');
  late Map<String, Object?> native;
  late List<Map> calls;
  bool reject = false;
  Completer<void>? gate;
  setUp(() {
    native = fixture();
    calls = [];
    reject = false;
    gate = null;
    binding.defaultBinaryMessenger.setMockMethodCallHandler(
      events,
      (_) async => null,
    );
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if (call.method == 'snapshot') return native;
      final args = call.arguments as Map;
      calls.add(args);
      if (args['op'] == 'field') {
        await gate?.future;
        if (reject) throw PlatformException(code: 'rejected');
        native = {
          ...native,
          'name': args['text'],
          'start': args['start'],
          'end': args['end'],
        };
      }
      if (args['op'] == 'edit' || args['op'] == 'add') {
        native = {...native, 'editing': true};
      }
      if (args['op'] == 'cancel' || args['op'] == 'back') {
        native = {
          ...native,
          'editing': false,
          'name': '',
          'start': 0,
          'end': 0,
        };
      }
      native = {...native, 'revision': (native['revision'] as int) + 1};
      return native;
    });
  });
  tearDown(() {
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
    binding.defaultBinaryMessenger.setMockMethodCallHandler(events, null);
  });
  Future<void> app(
    WidgetTester tester, {
    NamedGroupsBridge? bridge,
    String locale = 'en',
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        locale: Locale(locale),
        supportedLocales: AppLocalizations.supportedLocales,
        localizationsDelegates: [
          AppLocalizations.delegate,
          ...GlobalMaterialLocalizations.delegates,
        ],
        home: NamedGroupsPage(bridge: bridge),
      ),
    );
    await tester.pump();
    await tester.pump();
  }

  test('snapshot validates allowed native kind, IDs and UTF16 selection', () {
    expect(
      () => NamedGroupsSnapshot({...fixture(), 'kind': 'untrusted'}),
      throwsFormatException,
    );
    expect(
      () => NamedGroupsSnapshot({...fixture(), 'start': 1}),
      throwsFormatException,
    );
    expect(
      () => NamedGroupsSnapshot({
        ...fixture(),
        'groups': [
          {'id': '1', 'name': 'One'},
          {'id': '1', 'name': 'Two'},
        ],
      }),
      throwsFormatException,
    );
  });
  test(
    'retired generations and old revisions cannot replace current snapshot',
    () async {
      final bridge = NamedGroupsBridge();
      bridge.accept(fixture(revision: 5));
      bridge.accept(fixture(revision: 2));
      expect(bridge.latest!.revision, 5);
      bridge.accept(fixture(generation: 2));
      bridge.accept(fixture(revision: 9));
      expect(bridge.latest!.generation, 2);
      await bridge.close();
    },
  );
  test('confirm serializes behind exact field text and raw selection acknowledgement', () async {
    final bridge = NamedGroupsBridge();
    await bridge.start();
    gate = Completer<void>();
    final field = bridge.command('field', {
      'text': ' A😀B ',
      'start': 2,
      'end': 4,
    });
    final confirm = bridge.command('confirm');
    await Future<void>.delayed(Duration.zero);
    expect(calls.map((c) => c['op']), ['field']);
    gate!.complete();
    await field;
    await confirm;
    expect(calls.map((c) => c['op']), ['field', 'confirm']);
    expect(bridge.latest!.name, ' A😀B ');
    expect(bridge.latest!.start, 2);
    expect(bridge.latest!.end, 4);
    await bridge.close();
  });
  test(
    'rejected input blocks confirm but explicit cancel remains available',
    () async {
      final bridge = NamedGroupsBridge();
      await bridge.start();
      reject = true;
      await expectLater(
        bridge.command('field', {'text': 'draft', 'start': 1, 'end': 2}),
        throwsA(isA<PlatformException>()),
      );
      await expectLater(bridge.command('confirm'), throwsStateError);
      await bridge.command('cancel');
      expect(calls.map((c) => c['op']), ['field', 'cancel']);
      expect(bridge.hasRejectedField, false);
      await bridge.close();
    },
  );
  test(
    'late failed ACK from retired engine cannot poison the new generation',
    () async {
      final bridge = NamedGroupsBridge();
      await bridge.start();
      gate = Completer<void>();
      reject = true;
      final result = expectLater(
        bridge.command('field', {'text': 'draft', 'start': 1, 'end': 2}),
        throwsA(isA<PlatformException>()),
      );
      await Future<void>.delayed(Duration.zero);
      bridge.accept(fixture(generation: 2));
      gate!.complete();
      await result;
      expect(bridge.latest!.generation, 2);
      expect(bridge.hasRejectedField, false);
      await bridge.close();
    },
  );
  testWidgets('RSS alone exposes Done and localized group actions', (
    tester,
  ) async {
    native = fixture(kind: 'rss');
    await app(tester, locale: 'zh');
    expect(find.text('分组管理'), findsOneWidget);
    expect(find.bySemanticsIdentifier('named-group-done'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
    native = fixture(kind: 'replace');
    await app(tester);
    expect(find.bySemanticsIdentifier('named-group-done'), findsNothing);
  });
  testWidgets(
    'restored editor preserves actual UTF16 selection without echoing a field write',
    (tester) async {
      native = {
        ...fixture(editing: true),
        'name': 'raw😀name',
        'start': 2,
        'end': 5,
      };
      await app(tester);
      final field = tester.widget<TextField>(find.byType(TextField));
      expect(field.controller!.text, 'raw😀name');
      expect(
        field.controller!.selection,
        const TextSelection(baseOffset: 2, extentOffset: 5),
      );
      expect(calls, isEmpty);
    },
  );
  testWidgets(
    'failed mutation keeps real draft rendered and explicit cancel available',
    (tester) async {
      final bridge = NamedGroupsBridge();
      native = fixture(editing: true);
      await app(tester, bridge: bridge);
      await tester.enterText(find.byType(TextField), 'private draft');
      await tester.pump();
      bridge.accept({...native, 'error': 'disk', 'revision': 20});
      await tester.pump();
      expect(find.text('private draft'), findsOneWidget);
      expect(find.bySemanticsIdentifier('named-group-cancel'), findsOneWidget);
    },
  );
  testWidgets(
    'business key and semantic owner follow groups through list reordering',
    (tester) async {
      final bridge = NamedGroupsBridge();
      await app(tester, bridge: bridge);
      final before = tester.element(find.byKey(const ValueKey('1')));
      bridge.accept({
        ...native,
        'groups': [
          {'id': '2', 'name': 'Two'},
          {'id': '1', 'name': 'One'},
        ],
        'revision': 20,
      });
      await tester.pump();
      expect(
        identical(before, tester.element(find.byKey(const ValueKey('1')))),
        true,
      );
      await tester.tap(find.bySemanticsIdentifier('named-group-edit-1'));
      await tester.pump();
      expect(calls.last['id'], '1');
    },
  );
  testWidgets(
    'uncertain committed receipt blocks another write but allows explicit draft cancellation',
    (tester) async {
      native = {
        ...fixture(editing: true),
        'mutationUncertain': true,
        'error': 'Previous write may have completed',
      };
      await app(tester);
      expect(tester.widget<TextField>(find.byType(TextField)).enabled, false);
      final confirm = tester.widget<TextButton>(
        find.descendant(
          of: find.bySemanticsIdentifier('named-group-confirm'),
          matching: find.byType(TextButton),
        ),
      );
      final cancel = tester.widget<TextButton>(
        find.descendant(
          of: find.bySemanticsIdentifier('named-group-cancel'),
          matching: find.byType(TextButton),
        ),
      );
      expect(confirm.onPressed, isNull);
      expect(cancel.onPressed, isNotNull);
      expect(calls, isEmpty);
    },
  );
  testWidgets(
    'cancel queued behind input ignores the disappearing field selection echo',
    (tester) async {
      native = fixture(editing: true);
      gate = Completer<void>();
      await app(tester);
      await tester.enterText(find.byType(TextField), 'draft');
      await tester.pump();
      await tester.tap(find.bySemanticsIdentifier('named-group-cancel'));
      final field = tester.widget<TextField>(find.byType(TextField));
      field.controller!.selection = const TextSelection(
        baseOffset: 1,
        extentOffset: 3,
      );
      await tester.pump();
      expect(calls.map((c) => c['op']), ['field']);
      gate!.complete();
      await tester.pump();
      await tester.pump();
      expect(calls.map((c) => c['op']), ['field', 'cancel']);
    },
  );
  testWidgets(
    'owned list identifier contains a separate advertised scroll action for many groups',
    (tester) async {
      native = {
        ...fixture(),
        'groups': [
          for (var i = 0; i < 120; i++) {'id': '$i', 'name': 'Group $i'},
        ],
      };
      await app(tester);
      await tester.pumpAndSettle();
      final owner = tester.getSemantics(
        find.bySemanticsIdentifier('named-group-list'),
      );
      expect(
        owner.getSemanticsData().hasAction(SemanticsAction.scrollUp),
        false,
      );
      final pending = <SemanticsNode>[owner];
      SemanticsNode? scroller;
      while (pending.isNotEmpty) {
        final node = pending.removeAt(0);
        if (node.getSemanticsData().hasAction(SemanticsAction.scrollUp)) {
          scroller = node;
          break;
        }
        node.visitChildren((child) {
          pending.add(child);
          return true;
        });
      }
      expect(scroller, isNotNull);
      expect(scroller!.id, isNot(owner.id));
      final position = tester
          .state<ScrollableState>(find.byType(Scrollable))
          .position;
      expect(position.pixels, 0);
      tester.binding.performSemanticsAction(
        SemanticsActionEvent(
          type: SemanticsAction.scrollUp,
          viewId: tester.view.viewId,
          nodeId: scroller.id,
        ),
      );
      await tester.pumpAndSettle();
      expect(position.pixels, greaterThan(0));
      expect(calls, isEmpty);
    },
  );
}
