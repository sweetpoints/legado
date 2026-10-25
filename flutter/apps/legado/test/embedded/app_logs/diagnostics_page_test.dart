import 'dart:ui' show SemanticsAction;

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:legado_app/embedded/app_logs/app_logs_page.dart';
import 'package:legado_app/embedded/app_logs/diagnostics_bridge.dart';
import 'package:legado_app/embedded/crash_logs/crash_logs_page.dart';
import 'package:legado_app/l10n/generated/app_localizations.dart';

class FakeBridge extends DiagnosticsBridge {
  FakeBridge({super.crash});
  int revision = 0, page = 0;
  bool confirm = false, busy = false;
  int savedIndex = 0;
  double savedOffset = 0;
  final commands = <String>[];
  final scrollReceipts = <Map<String, Object?>>[];
  List<Map<String, Object?>> entries = List.generate(
    105,
    (i) => {
      'id': 'opaque-$i',
      'text': 'Synthetic $i',
      'time': '12:00',
      'details': true,
      'truncated': false,
      'links': <String>[],
    },
  );
  void emit() => accept({
    'schemaVersion': 1,
    'binding': 'binding',
    'revision': ++revision,
    'sequence': 0,
    'busy': busy,
    'error': false,
    'notice': null,
    'confirmClear': confirm,
    'page': page,
    'pages': ((entries.length + 99) ~/ 100).clamp(1, 100000),
    'scrollIndex': savedIndex,
    'scrollOffset': savedOffset,
    'rows': entries.skip(page * 100).take(100).toList(),
  });
  @override
  Future<void> start() async => emit();
  @override
  Future<void> command(
    String op, [
    Map<String, Object?> args = const {},
  ]) async {
    commands.add(op);
    if (op == 'scroll') scrollReceipts.add(Map.of(args));
    if (op == 'clear') {
      if (crash) {
        entries = [];
      } else {
        confirm = true;
      }
    }
    if (op == 'cancelClear') confirm = false;
    if (op == 'confirmClear') {
      confirm = false;
      entries = [];
    }
    if (op == 'next') page++;
    if (op == 'previous') page--;
    emit();
  }
}

Future<void> mount(WidgetTester tester, FakeBridge bridge) async {
  await tester.pumpWidget(
    MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: bridge.crash
          ? CrashLogsPage(bridge: bridge)
          : AppLogsPage(bridge: bridge),
    ),
  );
  if (bridge.busy) {
    await tester.pump();
  } else {
    await tester.pumpAndSettle();
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'paused rejection resynchronizes its receipt without replaying the action',
    () async {
      final seed = FakeBridge();
      seed.emit();
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      var revision = 0, sequence = 0, calls = 0;
      final received = <int>[];
      Map<String, Object?> snapshot() => {
        ...seed.latest!.raw,
        'revision': ++revision,
        'sequence': sequence,
      };
      const method = MethodChannel('legado/app_logs');
      const event = MethodChannel('legado/app_logs/state');
      messenger.setMockMethodCallHandler(event, (_) async => null);
      messenger.setMockMethodCallHandler(method, (call) async {
        if (call.method == 'snapshot') return snapshot();
        final args = Map<String, Object?>.from(call.arguments as Map);
        received.add(args['sequence']! as int);
        calls++;
        if (calls == 1) throw PlatformException(code: 'invalid_request');
        expect(args['sequence'], sequence + 1);
        sequence++;
        return snapshot();
      });
      final bridge = DiagnosticsBridge();
      try {
        await bridge.start();
        await expectLater(
          bridge.command('export'),
          throwsA(isA<PlatformException>()),
        );
        expect(calls, 1);
        await bridge.command('export');
        expect(received, [1, 1]);
        expect(calls, 2);
      } finally {
        await bridge.close();
        await seed.close();
        messenger.setMockMethodCallHandler(method, null);
        messenger.setMockMethodCallHandler(event, null);
      }
    },
  );
  testWidgets('export identifier owns its advertised actual tap action', (
    tester,
  ) async {
    final semantics = tester.ensureSemantics();
    try {
      final bridge = FakeBridge();
      await mount(tester, bridge);
      await tester.tap(find.byType(PopupMenuButton<String>));
      await tester.pumpAndSettle();
      final node = tester.getSemantics(
        find.bySemanticsIdentifier('app-logs-export'),
      );
      expect(node.getSemanticsData().hasAction(SemanticsAction.tap), isTrue);
    } finally {
      semantics.dispose();
    }
  });
  testWidgets(
    'normal app clear confirmation blocks export and rows; cancel preserves data',
    (tester) async {
      final bridge = FakeBridge();
      await mount(tester, bridge);
      await tester.tap(find.text('Clear').first);
      await tester.pumpAndSettle();
      expect(find.text('Clear all logs?'), findsOneWidget);
      expect(
        tester
            .widget<PopupMenuButton<String>>(
              find.byType(PopupMenuButton<String>),
            )
            .enabled,
        isFalse,
      );
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      expect(bridge.entries.length, 105);
      await tester.tap(find.text('Clear').first);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Clear').last);
      await tester.pumpAndSettle();
      expect(bridge.entries, isEmpty);
      expect(bridge.commands.where((op) => op == 'confirmClear').length, 1);
    },
  );
  testWidgets(
    'native opaque page after first 100 remains reachable with no crash sharing',
    (tester) async {
      final bridge = FakeBridge(crash: true);
      await mount(tester, bridge);
      expect(find.byType(PopupMenuButton<String>), findsNothing);
      await tester.tap(find.text('Next'));
      await tester.pumpAndSettle();
      expect(find.text('Synthetic 100'), findsOneWidget);
      await tester.tap(find.text('Details').first);
      await tester.pumpAndSettle();
      expect(bridge.commands.where((op) => op == 'open').length, 1);
      await tester.tap(find.text('Previous'));
      await tester.pumpAndSettle();
      expect(find.text('Synthetic 0'), findsOneWidget);
    },
  );
  testWidgets(
    'live prepend preserves visible business identity while top follows new rows',
    (tester) async {
      final bridge = FakeBridge();
      await mount(tester, bridge);
      await tester.drag(find.byType(ListView).first, const Offset(0, -600));
      await tester.pumpAndSettle();
      final list = tester.widget<ListView>(find.byType(ListView).first);
      final before = list.controller!.offset;
      bridge.entries.insert(0, {
        'id': 'new',
        'text': 'New synthetic',
        'time': '12:01',
        'details': false,
        'truncated': false,
        'links': <String>[],
      });
      bridge.emit();
      bridge.entries.insert(0, {
        'id': 'new2',
        'text': 'Second new synthetic',
        'time': '12:02',
        'details': false,
        'truncated': false,
        'links': <String>[],
      });
      bridge.emit();
      await tester.pumpAndSettle();
      expect(list.controller!.offset, closeTo(before + 304, 0.1));
      await tester.pump(const Duration(milliseconds: 150));
      await tester.pumpAndSettle();
      final first = (list.controller!.offset / 152).floor();
      expect(bridge.scrollReceipts.last['id'], bridge.entries[first]['id']);
      expect(bridge.scrollReceipts.last['index'], first);
    },
  );
  testWidgets(
    'restored viewport waits for the resumed native list instead of writing zero over it',
    (tester) async {
      final bridge = FakeBridge();
      final entries = bridge.entries;
      bridge.busy = true;
      bridge.entries = [];
      bridge.savedIndex = 5;
      bridge.savedOffset = 10;
      await mount(tester, bridge);
      expect(bridge.commands.where((op) => op == 'scroll'), isEmpty);
      bridge.busy = false;
      bridge.entries = entries;
      bridge.emit();
      await tester.pumpAndSettle();
      final list = tester.widget<ListView>(find.byType(ListView).first);
      expect(list.controller!.offset, closeTo(5 * 152 + 10, 0.1));
    },
  );
  testWidgets(
    '320-wide double text scale keeps long log rows details links and clear controls reachable',
    (tester) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      for (final crash in [false, true]) {
        final bridge = FakeBridge(crash: crash);
        bridge.entries = [
          {
            'id': 'opaque-long',
            'text': 'Synthetic preview '.padRight(512, 'x'),
            'time': '2026-10-25 12:00:00',
            'details': true,
            'truncated': false,
            'links': crash ? <String>[] : ['https://example.invalid/synthetic'],
          },
          ...List.generate(
            100,
            (i) => {
              'id': 'more-$i',
              'text': 'Synthetic extra $i',
              'time': '',
              'details': false,
              'truncated': false,
              'links': <String>[],
            },
          ),
        ];
        await tester.pumpWidget(
          MaterialApp(
            localizationsDelegates: AppLocalizations.localizationsDelegates,
            supportedLocales: AppLocalizations.supportedLocales,
            builder: (context, child) => MediaQuery(
              data: MediaQuery.of(context)
                  .copyWith(textScaler: TextScaler.linear(2)),
              child: child!,
            ),
            home: crash
                ? CrashLogsPage(bridge: bridge)
                : AppLogsPage(bridge: bridge),
          ),
        );
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        await tester.tap(find.text('Details'));
        await tester.pumpAndSettle();
        expect(bridge.commands.where((op) => op == 'open').length, 1);
        if (!crash) {
          await tester.ensureVisible(
            find.text('https://example.invalid/synthetic'),
          );
          final link = find
              .ancestor(
                of: find.text('https://example.invalid/synthetic'),
                matching: find.byType(TextButton),
              )
              .first;
          final visible = tester
              .getRect(link)
              .intersect(const Rect.fromLTWH(0, 0, 320, 640));
          expect(visible.isEmpty, isFalse);
          await tester.tapAt(visible.center);
          await tester.pumpAndSettle();
          expect(bridge.commands.where((op) => op == 'link').length, 1);
        }
        await tester.tap(find.text('Clear').first);
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        if (!crash) {
          await tester.tap(find.text('Cancel'));
          await tester.pumpAndSettle();
        }
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox());
        await tester.pumpAndSettle();
      }
    },
  );
  test('snapshot rejects oversized projections and duplicate identity', () {
    final b = FakeBridge();
    b.emit();
    final raw = Map<String, Object?>.from(b.latest!.raw);
    raw['rows'] = List.generate(
      101,
      (i) => {'id': '$i', 'text': 'x', 'time': ''},
    );
    expect(() => DiagnosticsSnapshot(raw), throwsFormatException);
    raw['rows'] = [
      {'id': 'same', 'text': 'x', 'time': ''},
      {'id': 'same', 'text': 'y', 'time': ''},
    ];
    expect(() => DiagnosticsSnapshot(raw), throwsFormatException);
  });
}
