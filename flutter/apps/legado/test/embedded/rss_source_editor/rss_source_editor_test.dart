import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:legado_app/embedded/rss_source_editor/rss_source_editor_bridge.dart';
import 'package:legado_app/embedded/rss_source_editor/rss_source_editor_page.dart';
import 'package:legado_app/embedded/rss_source_editor/rss_source_editor_state.dart';
import 'package:legado_app/l10n/generated/app_localizations.dart';

import 'rss_editor_fixture.dart';

void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('legado/rss_source_editor');
  const events = MethodChannel('legado/rss_source_editor/state');
  late Map<String, Object?> native;
  late List<Map<String, Object?>> commands;
  Future<void> publish(Map<String, Object?> value) async {
    native = value;
    await binding.defaultBinaryMessenger.handlePlatformMessage(
      events.name,
      const StandardMethodCodec().encodeSuccessEnvelope(value),
      (_) {},
    );
  }

  void revise() {
    native['revision'] = (native['revision'] as int) + 1;
  }

  setUp(() {
    native = rssEditorFixture();
    commands = [];
    binding.defaultBinaryMessenger.setMockMethodCallHandler(
      events,
      (_) async => null,
    );
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if (call.method == 'snapshot') return native;
      final c = Map<String, Object?>.from(call.arguments as Map);
      commands.add(c);
      switch (c['op']) {
        case 'field':
          fieldMap(native, c['key'] as String).addAll({
            'text': c['value'],
            'start': c['start'],
            'end': c['end'],
            'length': (c['value'] as String).length,
          });
        case 'option':
          (native['options'] as Map)[c['key']] = c['value'];
        case 'expanded':
          native['expanded'] = c['value'];
        case 'autoComplete':
          native['autoComplete'] = c['value'];
        case 'tab':
          native['tab'] = c['index'];
          native['focus'] = null;
        case 'focus':
          native['focus'] = c['key'];
        case 'cancel':
          native['exit'] = true;
        case 'keepEditing':
          native['exit'] = false;
      }
      revise();
      if ({'field', 'option', 'expanded', 'autoComplete'}.contains(c['op'])) {
        return native;
      }
      await publish(native);
      return null;
    });
  });
  tearDown(() {
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
    binding.defaultBinaryMessenger.setMockMethodCallHandler(events, null);
  });
  Future<void> app(WidgetTester tester, {String locale = 'en'}) async {
    tester.view.physicalSize = const Size(1100, 1000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          restorationScopeId: 'test-embedded',
          locale: Locale(locale),
          localizationsDelegates: [
            AppLocalizations.delegate,
            ...GlobalMaterialLocalizations.delegates,
          ],
          supportedLocales: AppLocalizations.supportedLocales,
          home: const RssSourceEditorPage(),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  Finder tag(String id) => find.byKey(ValueKey(id));
  Future<void> click(WidgetTester tester, String id) async {
    await tester.ensureVisible(tag(id));
    await tester.tap(tag(id));
    await tester.pumpAndSettle();
  }

  test('decodes all 33 fields with unique four tab ownership', () {
    final state = RssEditorSnapshot.fromMap(native);
    expect(state.tabs.map((t) => t.fields.length), [15, 4, 7, 7]);
    expect(state.tabs.expand((t) => t.fields).length, 33);
  });
  test(
    'accepts reversed UTF16 selection and external field without original text',
    () {
      fieldMap(
        native,
        'sourceName',
      ).addAll({'text': 'A😀B', 'length': 4, 'start': 3, 'end': 1});
      fieldMap(
        native,
        'startJs',
      ).addAll({'external': true, 'text': null, 'length': 16000});
      final state = RssEditorSnapshot.fromMap(native);
      expect(state.field('sourceName')!.start, 3);
      expect(state.field('sourceName')!.end, 1);
      expect(state.field('startJs')!.text, isNull);
    },
  );
  for (final entry in <String, Object?>{
    'revision': 0,
    'generation': 0,
    'tab': 4,
    'keyboardRows': 0,
    'maxLines': 0,
    'schemaVersion': 2,
    'issue': 'Unknown',
  }.entries) {
    test('rejects malformed ${entry.key}', () {
      native[entry.key] = entry.value;
      expect(() => RssEditorSnapshot.fromMap(native), throwsFormatException);
    });
  }
  test(
    'external projection cannot masquerade truncated source as editable',
    () {
      fieldMap(
        native,
        'startJs',
      ).addAll({'external': true, 'text': 'cut', 'length': 20000});
      expect(() => RssEditorSnapshot.fromMap(native), throwsFormatException);
    },
  );
  testWidgets(
    'native 33-field tabs and real single-property options stay available',
    (tester) async {
      await app(tester);
      await click(tester, 'rss-options-toggle');
      await click(tester, 'rss-option-singleUrl');
      expect((native['options'] as Map)['singleUrl'], true);
      await click(tester, 'rss-option-preload');
      expect((native['options'] as Map)['singleUrl'], true);
      expect(commands.where((c) => c['op'] == 'option').map((c) => c['key']), [
        'singleUrl',
        'preload',
      ]);
      await click(tester, 'rss-type');
      await click(tester, 'rss-type-2');
      await click(tester, 'rss-article-style');
      await click(tester, 'rss-article-style-4');
      expect((native['options'] as Map)['type'], 2);
      expect((native['options'] as Map)['articleStyle'], 4);
      await click(tester, 'rss-tab-3');
      expect(tag('rss-field-ruleContent'), findsOneWidget);
      expect(tag('rss-option-cacheFirst'), findsOneWidget);
      await click(tester, 'rss-option-cacheFirst');
      expect((native['options'] as Map)['cacheFirst'], true);
    },
  );
  testWidgets(
    'rapid options use current local state rather than stale chip closure',
    (tester) async {
      await app(tester);
      await click(tester, 'rss-options-toggle');
      final f = tag('rss-option-singleUrl');
      await tester.tap(f);
      await tester.tap(f);
      await tester.pumpAndSettle();
      expect(
        commands.where((c) => c['op'] == 'option').map((c) => c['value']),
        [true, false],
      );
      expect((native['options'] as Map)['singleUrl'], false);
    },
  );
  testWidgets(
    'single field write keeps reversed selection and excludes composing-only events',
    (tester) async {
      await app(tester);
      final input = find.descendant(
        of: tag('rss-field-sourceName'),
        matching: find.byType(TextField),
      );
      await tester.tap(input);
      await tester.pumpAndSettle();
      tester.testTextInput.updateEditingValue(
        const TextEditingValue(
          text: 'A😀B',
          selection: TextSelection(baseOffset: 3, extentOffset: 1),
        ),
      );
      await tester.pumpAndSettle();
      final before = commands.where((c) => c['op'] == 'field').length;
      final controller = tester.widget<TextField>(input).controller!;
      controller.value = controller.value.copyWith(
        composing: const TextRange(start: 1, end: 3),
      );
      await tester.pumpAndSettle();
      expect(commands.where((c) => c['op'] == 'field').length, before);
      final c = commands.lastWhere((c) => c['op'] == 'field');
      expect(c['value'], 'A😀B');
      expect(c['start'], 3);
      expect(c['end'], 1);
    },
  );
  testWidgets('long external field opens native full editor only after focus', (
    tester,
  ) async {
    fieldMap(
      native,
      'sourceName',
    ).addAll({'external': true, 'text': null, 'length': 20000});
    await app(tester);
    expect(find.byType(TextField), findsNWidgets(14));
    await click(tester, 'rss-field-sourceName');
    expect(commands.map((c) => c['op']), ['focus', 'editor']);
    expect(commands.any((c) => c['op'] == 'field'), false);
  });
  testWidgets(
    'back first dismisses discard confirmation and never silently saves',
    (tester) async {
      await app(tester);
      await click(tester, 'rss-close');
      expect(tag('rss-discard-confirm'), findsOneWidget);
      await binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(commands.last['op'], 'keepEditing');
      expect(commands.any((c) => c['op'] == 'save'), false);
    },
  );
  for (final locale in ['en', 'zh']) {
    testWidgets('localized $locale controls preserve native save action', (
      tester,
    ) async {
      await app(tester, locale: locale);
      await click(tester, 'rss-save');
      expect(commands.last, containsPair('action', 'Close'));
      expect(
        find.text(locale == 'en' ? 'RSS source editor' : '订阅源编辑'),
        findsOneWidget,
      );
    });
  }
  testWidgets(
    'pending editor error exposes distinct recovery and cancellation',
    (tester) async {
      native.addAll({
        'editorPending': true,
        'canEdit': false,
        'error': 'fixture error',
      });
      await app(tester);
      await click(tester, 'rss-retry');
      expect(commands.last['op'], 'retryEditor');
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      expect(commands.last['op'], 'discardEditor');
    },
  );
  testWidgets('English URL interception field uses localized text', (
    tester,
  ) async {
    native['tab'] = 3;
    fieldMap(native, 'shouldOverrideUrlLoading')['label'] = '中文旧标签';
    await app(tester);
    expect(find.text('中文旧标签'), findsNothing);
    expect(find.textContaining('URL navigation interception'), findsWidgets);
  });
  test(
    'late initial snapshot cannot overwrite streamed newer revision',
    () async {
      final initial = Completer<Object?>();
      binding.defaultBinaryMessenger.setMockMethodCallHandler(
        channel,
        (_) => initial.future,
      );
      final bridge = RssEditorBridge();
      final values = <RssEditorSnapshot>[];
      final subscription = bridge.snapshots().listen(values.add);
      await Future<void>.delayed(Duration.zero);
      await publish(rssEditorFixture(revision: 3));
      initial.complete(rssEditorFixture(revision: 1));
      await Future<void>.delayed(Duration.zero);
      expect(values.map((s) => s.revision), [3]);
      await subscription.cancel();
      await bridge.close();
    },
  );
  test(
    'retired generations and stale revisions cannot roll back accepted draft',
    () async {
      final bridge = RssEditorBridge();
      final values = <RssEditorSnapshot>[];
      final subscription = bridge.snapshots().listen(values.add);
      await Future<void>.delayed(Duration.zero);
      await publish(rssEditorFixture(revision: 5));
      await publish(rssEditorFixture(revision: 2));
      await publish(rssEditorFixture(generation: 2, revision: 1));
      await publish(rssEditorFixture(revision: 8));
      await Future<void>.delayed(Duration.zero);
      expect(values.map((s) => '${s.generation}:${s.revision}'), [
        '1:1',
        '1:5',
        '2:1',
      ]);
      await subscription.cancel();
      await bridge.close();
    },
  );
  test(
    'rejected write blocks an already queued save until valid field ack',
    () async {
      final bridge = RssEditorBridge();
      final sub = bridge.snapshots().listen((_) {});
      await Future<void>.delayed(Duration.zero);
      var reject = true;
      binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
        call,
      ) async {
        final c = Map<String, Object?>.from(call.arguments as Map);
        commands.add(c);
        if (c['op'] == 'field' && reject) {
          throw PlatformException(code: 'rejected');
        }
        return c['op'] == 'field' ? native : null;
      });
      final edit = bridge.command('field', 1, {
        'key': 'sourceName',
        'value': 'bad',
        'start': 0,
        'end': 0,
      });
      final save = bridge.command('save', 1, {'action': 'Close'});
      await expectLater(edit, throwsA(isA<PlatformException>()));
      await expectLater(save, throwsA(isA<RssEditorWriteBlocked>()));
      expect(commands.map((c) => c['op']), ['field']);
      expect(bridge.hasRejectedFields, true);
      reject = false;
      await bridge.command('field', 1, {
        'key': 'sourceName',
        'value': 'ok',
        'start': 0,
        'end': 0,
      });
      await bridge.command('save', 1, {'action': 'Close'});
      expect(bridge.hasRejectedFields, false);
      expect(commands.last['op'], 'save');
      await sub.cancel();
      await bridge.close();
    },
  );
  testWidgets(
    'field replies drain pending even when events conflate intermediate input',
    (tester) async {
      var current = const RssEditorField(
        key: 'sourceName',
        label: 'Name',
        text: '',
        start: 0,
        end: 0,
        external: false,
        length: 0,
      );
      final replies = <Completer<RssEditorSnapshot?>>[];
      Future<RssEditorSnapshot?> command(
        String op, [
        Map<String, Object?> args = const {},
      ]) {
        if (op != 'field') return Future.value(null);
        final c = Completer<RssEditorSnapshot?>();
        replies.add(c);
        return c.future;
      }

      await tester.pumpWidget(
        MaterialApp(
          localizationsDelegates: [
            AppLocalizations.delegate,
            ...GlobalMaterialLocalizations.delegates,
          ],
          supportedLocales: AppLocalizations.supportedLocales,
          home: Scaffold(
            body: RssEditorTextField(
              field: current,
              focused: false,
              enabled: true,
              maxLines: 6,
              command: command,
              onEditor: () {},
              latestField: () => current,
            ),
          ),
        ),
      );
      await tester.enterText(find.byType(TextField), 'first');
      await tester.pump();
      await tester.enterText(find.byType(TextField), 'second');
      await tester.pump();
      current = current.copyWith(text: 'second', length: 6, start: 6, end: 6);
      replies[0].complete(null);
      await tester.pump();
      expect(
        tester.widget<TextField>(find.byType(TextField)).controller!.text,
        'second',
      );
      replies[1].complete(null);
      await tester.pump();
      current = current.copyWith(text: 'undo', length: 4, start: 4, end: 4);
      await tester.pumpWidget(
        MaterialApp(
          localizationsDelegates: [
            AppLocalizations.delegate,
            ...GlobalMaterialLocalizations.delegates,
          ],
          supportedLocales: AppLocalizations.supportedLocales,
          home: Scaffold(
            body: RssEditorTextField(
              field: current,
              focused: false,
              enabled: true,
              maxLines: 6,
              command: command,
              onEditor: () {},
              latestField: () => current,
            ),
          ),
        ),
      );
      expect(
        tester.widget<TextField>(find.byType(TextField)).controller!.text,
        'undo',
      );
    },
  );
  testWidgets(
    'loaded save error keeps save available without ineffective load retry',
    (tester) async {
      native['error'] = 'fixture save failed';
      await app(tester);
      expect(tag('rss-retry'), findsNothing);
      await click(tester, 'rss-save');
      expect(commands.last['op'], 'save');
    },
  );
  testWidgets('initial load failure has actual load recovery', (tester) async {
    native.addAll({
      'loaded': false,
      'canEdit': false,
      'error': 'fixture load failed',
    });
    await app(tester);
    await click(tester, 'rss-retry');
    expect(commands.last['op'], 'retry');
  });
  testWidgets(
    'rejected field rolls back and cannot be bypassed by load retry',
    (tester) async {
      await app(tester);
      binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
        call,
      ) async {
        final c = Map<String, Object?>.from(call.arguments as Map);
        commands.add(c);
        if (c['op'] == 'field') throw PlatformException(code: 'invalid_field');
        return null;
      });
      await tester.enterText(
        find.descendant(
          of: tag('rss-field-sourceName'),
          matching: find.byType(TextField),
        ),
        'rejected',
      );
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<TextField>(
              find.descendant(
                of: tag('rss-field-sourceName'),
                matching: find.byType(TextField),
              ),
            )
            .controller!
            .text,
        '',
      );
      expect(tag('rss-retry'), findsNothing);
      await click(tester, 'rss-save');
      expect(commands.any((c) => c['op'] == 'save'), false);
    },
  );
  testWidgets(
    'each tab restores independent scroll across configuration restoration',
    (tester) async {
      await app(tester);
      tester.view.physicalSize = const Size(1100, 450);
      await tester.pumpAndSettle();
      await tester.ensureVisible(tag('rss-field-jsLib'));
      await tester.pumpAndSettle();
      ScrollPosition position() => tester
          .widget<SingleChildScrollView>(
            find
                .descendant(
                  of: tag('rss-fields-${native['tab']}'),
                  matching: find.byType(SingleChildScrollView),
                )
                .first,
          )
          .controller!
          .position;
      final baseOffset = position().pixels;
      expect(baseOffset, greaterThan(0));
      await click(tester, 'rss-tab-1');
      expect(position().pixels, 0);
      await tester.ensureVisible(tag('rss-field-preloadJs'));
      await tester.pumpAndSettle();
      final startOffset = position().pixels;
      expect(startOffset, greaterThan(0));
      await click(tester, 'rss-tab-0');
      expect(position().pixels, baseOffset);
      await tester.restartAndRestore();
      await tester.pumpAndSettle();
      expect(position().pixels, baseOffset);
      await click(tester, 'rss-tab-1');
      expect(position().pixels, startOffset);
    },
  );
  testWidgets(
    'compact 320 by 640 viewport keeps five-row assistance usable with actual IME insets',
    (tester) async {
      native['keyboardRows'] = 5;
      await app(tester);
      tester.view.physicalSize = const Size(320, 640);
      tester.view.viewInsets = const FakeViewPadding(bottom: 300);
      addTearDown(tester.view.resetViewInsets);
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(tag('rss-keyboard'), findsOneWidget);
      expect(tag('rss-tab-0'), findsNothing);
      await tester.enterText(
        find.descendant(
          of: tag('rss-field-sourceName'),
          matching: find.byType(TextField),
        ),
        'Compact',
      );
      await tester.pumpAndSettle();
      expect(fieldMap(native, 'sourceName')['text'], 'Compact');
      await click(tester, 'rss-undo');
      expect(commands.last['op'], 'undo');
      expect(tester.takeException(), isNull);
    },
  );
}
