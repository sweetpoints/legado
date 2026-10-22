import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:legado_app/embedded/rss_favorites/rss_favorite_config_bridge.dart';
import 'package:legado_app/embedded/rss_favorites/rss_favorite_config_page.dart';
import 'package:legado_app/l10n/generated/app_localizations.dart';

Map<String, Object?> snapshot({
  int generation = 1,
  int revision = 1,
  bool busy = false,
  bool loaded = true,
  String title = 'Original',
  String group = 'Original group',
  String focus = 'title',
}) => {
  'generation': generation,
  'revision': revision,
  'title': title,
  'group': group,
  'focus': focus,
  'titleStart': 0,
  'titleEnd': 0,
  'groupStart': 0,
  'groupEnd': 0,
  'loaded': loaded,
  'loading': !loaded,
  'busy': busy,
  'canEdit': loaded && !busy,
  'error': false,
  'finished': false,
};
void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('legado/rss_favorite_config');
  const events = MethodChannel('legado/rss_favorite_config/state');
  late Map<String, Object?> native;
  late List<Map> commands;
  bool reject = false;
  Completer<void>? gate;
  setUp(() {
    native = snapshot();
    commands = [];
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
      commands.add(args);
      if (args['op'] == 'field') {
        await gate?.future;
        if (reject) throw PlatformException(code: 'rejected');
        final key = args['key'] as String;
        native = {
          ...native,
          key: args['text'],
          '${key}Start': args['start'],
          '${key}End': args['end'],
        };
      }
      if (args['op'] == 'focus') native = {...native, 'focus': args['key']};
      native = {...native, 'revision': (native['revision'] as int) + 1};
      return native;
    });
  });
  tearDown(() {
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
    binding.defaultBinaryMessenger.setMockMethodCallHandler(events, null);
  });
  test(
    'retired generations and older snapshots cannot replace native state',
    () async {
      final bridge = RssFavoriteConfigBridge();
      bridge.accept(snapshot(revision: 5));
      bridge.accept(snapshot(revision: 2));
      expect(bridge.latest!.revision, 5);
      bridge.accept(snapshot(generation: 2));
      bridge.accept(snapshot(revision: 8));
      expect(bridge.latest!.generation, 2);
      await bridge.close();
    },
  );
  test(
    'rejected field blocks queued save and retry until that field ACK',
    () async {
      final bridge = RssFavoriteConfigBridge();
      await bridge.start();
      reject = true;
      final field = bridge.command('field', {
        'key': 'title',
        'text': 'Draft',
        'start': 2,
        'end': 2,
      });
      final save = bridge.command('save');
      await expectLater(field, throwsA(isA<PlatformException>()));
      await expectLater(save, throwsStateError);
      await expectLater(bridge.command('retry'), throwsStateError);
      expect(commands.where((e) => e['op'] == 'save'), isEmpty);
      reject = false;
      await bridge.command('field', {
        'key': 'title',
        'text': 'Draft',
        'start': 2,
        'end': 2,
      });
      await bridge.command('save');
      expect(commands.last['op'], 'save');
      expect(bridge.hasRejectedField, isFalse);
      await bridge.close();
    },
  );
  test('field queue delivers immutable selections before save and explicit cancel remains possible', () async {
    final bridge = RssFavoriteConfigBridge();
    await bridge.start();
    gate = Completer<void>();
    final field = bridge.command('field', {
      'key': 'group',
      'text': 'Group',
      'start': 1,
      'end': 4,
    });
    final save = bridge.command('save');
    await Future<void>.delayed(Duration.zero);
    expect(commands.length, 1);
    gate!.complete();
    await field;
    await save;
    expect(bridge.latest!.groupStart, 1);
    expect(bridge.latest!.groupEnd, 4);
    reject = true;
    await expectLater(
      bridge.command('field', {
        'key': 'title',
        'text': 'X',
        'start': 0,
        'end': 0,
      }),
      throwsA(isA<PlatformException>()),
    );
    await bridge.command('cancel');
    expect(commands.last['op'], 'cancel');
    await bridge.close();
  });
  Future<void> app(
    WidgetTester tester, {
    String locale = 'en',
    RssFavoriteConfigBridge? bridge,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        locale: Locale(locale),
        localizationsDelegates: [
          AppLocalizations.delegate,
          ...GlobalMaterialLocalizations.delegates,
        ],
        supportedLocales: AppLocalizations.supportedLocales,
        home: RssFavoriteConfigPage(bridge: bridge),
      ),
    );
    await tester.pump();
    await tester.pump();
  }

  testWidgets('both fields and save use the actual ordered protocol', (
    tester,
  ) async {
    await app(tester);
    await tester.enterText(find.byType(TextField).first, 'Draft title');
    await tester.pump();
    await tester.enterText(find.byType(TextField).last, 'Draft group');
    await tester.pump();
    await tester.tap(find.text('OK'));
    await tester.pump();
    expect(native['title'], 'Draft title');
    expect(native['group'], 'Draft group');
    expect(commands.last['op'], 'save');
  });
  testWidgets('busy snapshot disables edits save delete and cancel', (
    tester,
  ) async {
    native = snapshot(busy: true);
    await app(tester);
    expect(
      tester.widget<TextField>(find.byType(TextField).first).enabled,
      isFalse,
    );
    for (final label in ['OK', 'Delete', 'Cancel']) {
      expect(
        tester
            .widget<TextButton>(find.widgetWithText(TextButton, label))
            .onPressed,
        isNull,
      );
    }
  });
  testWidgets(
    'restored group selection focus and Chinese labels use native loaded state',
    (tester) async {
      native = {
        ...snapshot(group: 'Restored group', focus: 'group'),
        'groupStart': 2,
        'groupEnd': 5,
      };
      await app(tester, locale: 'zh');
      await tester.pump();
      final field = tester.widget<TextField>(find.byType(TextField).last);
      expect(
        field.controller!.selection,
        const TextSelection(baseOffset: 2, extentOffset: 5),
      );
      expect(field.focusNode!.hasFocus, isTrue);
      expect(find.text('分组名'), findsOneWidget);
    },
  );
  testWidgets(
    'late initial snapshot never resets edited text and Done never saves',
    (tester) async {
      final bridge = RssFavoriteConfigBridge();
      await app(tester, bridge: bridge);
      await tester.enterText(find.byType(TextField).last, 'Pending group');
      await tester.pump();
      bridge.accept(snapshot(revision: 0));
      await tester.pump();
      expect(
        tester.widget<TextField>(find.byType(TextField).last).controller!.text,
        'Pending group',
      );
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pump();
      expect(commands.where((e) => e['op'] == 'save'), isEmpty);
    },
  );
  testWidgets(
    'delete and cancel remain separate operations without implicit save',
    (tester) async {
      await app(tester);
      await tester.tap(find.text('Delete'));
      await tester.pump();
      expect(commands.last['op'], 'delete');
      await tester.tap(find.text('Cancel'));
      await tester.pump();
      expect(commands.last['op'], 'cancel');
      expect(commands.where((e) => e['op'] == 'save'), isEmpty);
    },
  );
  testWidgets(
    'failed load retries through native and outside cancellation never saves',
    (tester) async {
      native = {...snapshot(loaded: false), 'loading': false, 'error': true};
      await app(tester);
      expect(
        tester.widget<TextField>(find.byType(TextField).first).enabled,
        isFalse,
      );
      expect(
        find.text('Favorites operation failed. Please retry.'),
        findsOneWidget,
      );
      native = snapshot(revision: 2);
      await tester.tap(find.text('Retry'));
      await tester.pump();
      expect(commands.where((e) => e['op'] == 'retry').length, 1);
      expect(
        tester.widget<TextField>(find.byType(TextField).first).enabled,
        isTrue,
      );
      await tester.enterText(find.byType(TextField).first, 'Cancelled');
      await tester.pump();
      await tester.tapAt(const Offset(4, 4));
      await tester.pump();
      expect(commands.last['op'], 'cancel');
      expect(commands.where((e) => e['op'] == 'save'), isEmpty);
    },
  );
  testWidgets(
    'blank fields are delivered untrimmed for native nullable fallback',
    (tester) async {
      await app(tester);
      await tester.enterText(find.byType(TextField).first, ' ');
      await tester.pump();
      await tester.enterText(find.byType(TextField).last, '  ');
      await tester.pump();
      await tester.tap(find.text('OK'));
      await tester.pump();
      expect(native['title'], ' ');
      expect(native['group'], '  ');
      expect(commands.last['op'], 'save');
    },
  );
  testWidgets(
    'selection ACK and IME Next move real focus while Done retains group and never saves',
    (tester) async {
      await app(tester);
      final title = tester.widget<TextField>(find.byType(TextField).first);
      title.controller!.selection = const TextSelection(
        baseOffset: 1,
        extentOffset: 4,
      );
      await tester.pump();
      expect(native['titleStart'], 1);
      expect(native['titleEnd'], 4);
      await tester.testTextInput.receiveAction(TextInputAction.next);
      await tester.pump();
      final group = tester.widget<TextField>(find.byType(TextField).last);
      expect(group.focusNode!.hasFocus, isTrue);
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pump();
      expect(group.focusNode!.hasFocus, isTrue);
      expect(commands.where((e) => e['op'] == 'save'), isEmpty);
    },
  );
}
