import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:legado_app/embedded/rss_favorites/rss_favorites_bridge.dart';
import 'package:legado_app/embedded/rss_favorites/rss_favorites_page.dart';
import 'package:legado_app/l10n/generated/app_localizations.dart';

Map<String, Object?> fixture({
  int generation = 1,
  int revision = 1,
  bool toolbar = true,
  bool loaded = true,
  String group = 'B',
  int offset = 0,
  Map<String, Object?>? confirmation,
}) => {
  'generation': generation,
  'revision': revision,
  'toolbar': toolbar,
  'loaded': loaded,
  'busy': false,
  'error': false,
  'groups': ['A', 'B'],
  'group': group,
  'rows': [
    for (final g in ['A', 'B'])
      for (var i = 0; i < 80; i++)
        {
          'id': '$g$i',
          'group': g,
          'title': '$g item $i',
          'date': null,
          'image': false,
          'imageKey': '',
        },
  ],
  'positions': {
    'B': {'index': offset ~/ 100, 'offset': offset % 100},
  },
  'confirmation': confirmation,
};

class InspectBridge extends RssFavoritesBridge {
  final errors = <Object>[];
  @override
  Future<void> start() async {
    try {
      await super.start();
    } catch (e) {
      errors.add(e);
      rethrow;
    }
  }
}

void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('legado/rss_favorites');
  const events = MethodChannel('legado/rss_favorites/state');
  late Map<String, Object?> native;
  late List<Map> commands;
  Completer<Object?>? bootstrap;
  setUp(() {
    native = fixture();
    commands = [];
    bootstrap = null;
    binding.defaultBinaryMessenger.setMockMethodCallHandler(
      events,
      (_) async => null,
    );
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if (call.method == 'snapshot') {
        if (bootstrap != null) return await bootstrap!.future;
        return native;
      }
      final args = call.arguments as Map;
      commands.add(args);
      native = {...native, 'revision': (native['revision'] as int) + 1};
      if (args['op'] == 'group') native['group'] = args['group'];
      if (args['op'] == 'delete') {
        native['confirmation'] = {'kind': 'Row', 'target': args['id']};
      }
      if (args['op'] == 'cancel' || args['op'] == 'confirm') {
        native['confirmation'] = null;
      }
      return native;
    });
  });
  tearDown(() {
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
    binding.defaultBinaryMessenger.setMockMethodCallHandler(events, null);
  });
  Future<void> app(
    WidgetTester tester, {
    String locale = 'en',
    RssFavoritesBridge? bridge,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        locale: Locale(locale),
        localizationsDelegates: [
          AppLocalizations.delegate,
          ...GlobalMaterialLocalizations.delegates,
        ],
        supportedLocales: AppLocalizations.supportedLocales,
        home: RssFavoritesPage(bridge: bridge),
      ),
    );
    await tester.pump();
    await tester.pump();
  }

  test('old revisions and retired generations cannot replace authoritative snapshot', () async {
    final bridge = RssFavoritesBridge();
    bridge.accept(fixture(revision: 4));
    bridge.accept(fixture(revision: 2));
    expect(bridge.latest!.revision, 4);
    bridge.accept(fixture(generation: 2));
    bridge.accept(fixture(generation: 1, revision: 99));
    expect(bridge.latest!.generation, 2);
    await bridge.close();
  });
  test(
    'queued operations reject old generation before native mutation',
    () async {
      final bridge = RssFavoritesBridge();
      bridge.accept(fixture());
      final command = bridge.command('deleteAll', {});
      bridge.accept(fixture(generation: 2));
      await expectLater(command, throwsStateError);
      expect(commands, isEmpty);
      await bridge.close();
    },
  );
  testWidgets(
    'late loaded bootstrap restores selected group and native scroll without initial overwrite',
    (t) async {
      bootstrap = Completer();
      final inspected = InspectBridge();
      addTearDown(inspected.close);
      await app(t, bridge: inspected);
      expect(find.text('B item 0'), findsNothing);
      expect(commands, isEmpty);
      bootstrap!.complete(fixture(offset: 1240));
      await t.pump();
      await t.pump(const Duration(milliseconds: 16));
      await t.pumpAndSettle();
      expect(inspected.errors, isEmpty);
      expect(
        inspected.latest,
        isNotNull,
        reason: t.allWidgets.whereType<Text>().map((w) => w.data).join('|'),
      );
      expect(
        t.state<ScrollableState>(find.byType(Scrollable).last).position.pixels,
        1240,
      );
      expect(find.text('B item 12'), findsOneWidget);
      expect(find.text('B item 0'), findsNothing);
      expect(commands.where((c) => c['op'] == 'scroll'), isEmpty);
    },
  );
  testWidgets(
    'row long press opens native confirmation and modal blocks toolbar until cancel',
    (t) async {
      await app(t);
      await t.pumpAndSettle();
      await t.longPress(find.text('B item 0'));
      await t.pumpAndSettle();
      expect(commands.last['op'], 'delete');
      expect(commands.last['id'], 'B0');
      expect(find.text('Confirm deletion?'), findsOneWidget);
      await t.tap(find.text('Cancel'));
      await t.pumpAndSettle();
      expect(commands.last['op'], 'cancel');
      expect(find.text('Confirm deletion?'), findsNothing);
    },
  );
  testWidgets('group tab routes only opaque row id for native read', (t) async {
    await app(t);
    await t.pumpAndSettle();
    await t.tap(find.text('A'));
    await t.pumpAndSettle();
    expect(commands.last['op'], 'group');
    expect(commands.last['group'], 'A');
    await t.tap(find.text('A item 0'));
    await t.pumpAndSettle();
    expect(commands.last['op'], 'read');
    expect(commands.last.keys, unorderedEquals(['generation', 'op', 'id']));
  });
  Future<void> dragToRest(WidgetTester tester, double fraction) async {
    final list = find.byType(ListView);
    final size = tester.getSize(list);
    final start = tester.getCenter(list);
    final gesture = await tester.startGesture(start);
    for (var step = 1; step <= 20; step++) {
      await gesture.moveTo(
        start + Offset(size.width * fraction * step / 20, 0),
      );
      await tester.pump(const Duration(milliseconds: 40));
    }
    // Release with negligible velocity, so only travelled distance can change groups.
    await tester.pump(const Duration(milliseconds: 400));
    await gesture.up();
    await tester.pumpAndSettle();
  }

  testWidgets(
    'slow horizontal drags settle into the adjacent group in both directions',
    (t) async {
      native = fixture(group: 'A');
      await app(t);
      await t.pumpAndSettle();
      await dragToRest(t, -.7);
      expect(native['group'], 'B');
      expect(commands.where((c) => c['op'] == 'group').single['group'], 'B');
      commands.clear();
      await dragToRest(t, .7);
      expect(native['group'], 'A');
      expect(commands.where((c) => c['op'] == 'group').single['group'], 'A');
    },
  );

  testWidgets('short fast horizontal flings change groups in both directions', (
    t,
  ) async {
    native = fixture(group: 'A');
    await app(t);
    await t.pumpAndSettle();
    final distance = t.getSize(find.byType(ListView)).width * .2;
    await t.fling(find.byType(ListView), Offset(-distance, 0), 1200);
    await t.pumpAndSettle();
    expect(native['group'], 'B');
    commands.clear();
    await t.fling(find.byType(ListView), Offset(distance, 0), 1200);
    await t.pumpAndSettle();
    expect(native['group'], 'A');
    expect(commands.where((c) => c['op'] == 'group').single['group'], 'A');
  });

  testWidgets(
    'short drags at rest and outward edge drags do not switch groups',
    (t) async {
      native = fixture(group: 'A');
      await app(t);
      await t.pumpAndSettle();
      await dragToRest(t, -.2);
      expect(native['group'], 'A');
      await dragToRest(t, .7);
      expect(native['group'], 'A');
      expect(commands.where((c) => c['op'] == 'group'), isEmpty);
    },
  );
  testWidgets(
    'fixed group hides toolbar while native confirmation survives bootstrap',
    (t) async {
      native = fixture(
        toolbar: false,
        confirmation: {'kind': 'Group', 'target': 'B'},
      );
      native['groups'] = ['B'];
      await app(t, locale: 'zh');
      await t.pumpAndSettle();
      expect(find.byType(AppBar), findsNothing);
      expect(find.text('确认删除？'), findsOneWidget);
      await t.tap(find.text('删除'));
      await t.pumpAndSettle();
      expect(commands.last['op'], 'confirm');
    },
  );
}
