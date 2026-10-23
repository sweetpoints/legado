import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:legado_app/l10n/generated/app_localizations.dart';
import 'package:legado_app/embedded/dictionary_rule_editor/dictionary_rule_editor_bridge.dart';
import 'package:legado_app/embedded/dictionary_rule_editor/dictionary_rule_editor_page.dart';

Map<String, Object?> fixture({int generation = 1, int revision = 1}) => {
  'generation': generation,
  'revision': revision,
  'loading': false,
  'saving': false,
  'finished': false,
  'confirmExit': false,
  'loadFailed': false,
  'canEdit': true,
  'error': null,
  'focus': null,
  for (final field in dictionaryFields) ...{
    field: '',
    '${field}Start': 0,
    '${field}End': 0,
  },
};
void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('legado/dictionary_rule_editor');
  const events = MethodChannel('legado/dictionary_rule_editor/state');
  late Map<String, Object?> native;
  late List<Map> calls;
  bool reject = false;
  Completer<void>? fieldGate;
  setUp(() {
    native = fixture();
    calls = [];
    reject = false;
    fieldGate = null;
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
        await fieldGate?.future;
        if (reject) throw PlatformException(code: 'field_rejected');
        final key = args['key'] as String;
        native = {
          ...native,
          key: args['text'],
          '${key}Start': args['start'],
          '${key}End': args['end'],
          'focus': key,
        };
      }
      if (args['op'] == 'cancel') {
        native = {...native, 'confirmExit': true, 'canEdit': false};
      }
      if (args['op'] == 'keep') {
        native = {...native, 'confirmExit': false, 'canEdit': true};
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
    String locale = 'en',
    DictionaryRuleEditorBridge? bridge,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        locale: Locale(locale),
        supportedLocales: AppLocalizations.supportedLocales,
        localizationsDelegates: [
          AppLocalizations.delegate,
          ...GlobalMaterialLocalizations.delegates,
        ],
        home: DictionaryRuleEditorPage(bridge: bridge),
      ),
    );
    await tester.pump();
    await tester.pump();
  }

  test('selection validation rejects malformed native state', () {
    expect(
      () => DictionaryEditorSnapshot({...fixture(), 'NameStart': 2}),
      throwsFormatException,
    );
    expect(
      () => DictionaryEditorSnapshot({...fixture(), 'focus': 'unknown'}),
      throwsFormatException,
    );
  });
  test(
    'retired generation and old revisions cannot replace current state',
    () async {
      final bridge = DictionaryRuleEditorBridge();
      bridge.accept(fixture(revision: 5));
      bridge.accept(fixture(revision: 3));
      expect(bridge.latest!.revision, 5);
      bridge.accept(fixture(generation: 2));
      bridge.accept(fixture(revision: 8));
      expect(bridge.latest!.generation, 2);
      await bridge.close();
    },
  );
  test('save waits for full text and selection acknowledgement', () async {
    final bridge = DictionaryRuleEditorBridge();
    await bridge.start();
    fieldGate = Completer<void>();
    final field = bridge.command('field', {
      'key': 'ShowRule',
      'text': 'draft',
      'start': 2,
      'end': 4,
    });
    final save = bridge.command('save');
    await Future<void>.delayed(Duration.zero);
    expect(calls.map((e) => e['op']), ['field']);
    fieldGate!.complete();
    await field;
    await save;
    expect(calls.map((e) => e['op']), ['field', 'save']);
    expect(bridge.latest!.start('ShowRule'), 2);
    expect(bridge.latest!.end('ShowRule'), 4);
    await bridge.close();
  });
  test('failed edit blocks copy paste save fullscreen until that field acknowledged', () async {
    final bridge = DictionaryRuleEditorBridge();
    await bridge.start();
    reject = true;
    await expectLater(
      bridge.command('field', {
        'key': 'Name',
        'text': 'draft',
        'start': 2,
        'end': 2,
      }),
      throwsA(isA<PlatformException>()),
    );
    for (final op in ['save', 'copy', 'paste', 'fullscreen', 'retry']) {
      await expectLater(bridge.command(op), throwsStateError);
    }
    expect(calls.length, 1);
    reject = false;
    await bridge.command('field', {
      'key': 'Name',
      'text': 'draft',
      'start': 2,
      'end': 2,
    });
    await bridge.command('save');
    expect(bridge.hasRejectedField, false);
    await bridge.close();
  });
  test(
    'queued work from retired engine never targets replacement owner',
    () async {
      final bridge = DictionaryRuleEditorBridge();
      await bridge.start();
      final command = bridge.command('save');
      bridge.accept(fixture(generation: 2));
      await expectLater(command, throwsStateError);
      expect(calls, isEmpty);
      await bridge.close();
    },
  );
  testWidgets(
    'three real fields serialize input before toolbar save and localized labels',
    (tester) async {
      await app(tester);
      expect(find.text('Dictionary rule'), findsOneWidget);
      final fields = find.byType(TextField);
      expect(fields, findsNWidgets(3));
      await tester.enterText(fields.at(0), 'draft');
      await tester.enterText(fields.at(1), 'https://example.org');
      await tester.ensureVisible(fields.at(2));
      await tester.enterText(fields.at(2), '@js:return 2');
      await tester.pump();
      await tester.tap(find.byTooltip('Save'));
      await tester.pump();
      await tester.pump();
      expect(native['Name'], 'draft');
      expect(native['UrlRule'], 'https://example.org');
      expect(native['ShowRule'], '@js:return 2');
      expect(calls.last['op'], 'save');
    },
  );
  testWidgets(
    'dirty exit offers keep and explicit discard actions in Chinese',
    (tester) async {
      await app(tester, locale: 'zh');
      await tester.enterText(find.byType(TextField).first, '草稿');
      await tester.pump();
      await tester.tap(find.byTooltip('关闭'));
      await tester.pump();
      await tester.pump();
      expect(find.text('尚未保存，是否继续编辑？'), findsOneWidget);
      await tester.tap(find.text('继续编辑'));
      await tester.pump();
      await tester.pump();
      expect(find.text('草稿'), findsOneWidget);
      await tester.tap(find.byTooltip('关闭'));
      await tester.pump();
      await tester.pump();
      await tester.tap(find.text('放弃修改'));
      await tester.pump();
      expect(calls.last['op'], 'discard');
    },
  );
  testWidgets(
    'menu exposes native copy and paste without a Dart clipboard replacement',
    (tester) async {
      await app(tester);
      await tester.tap(find.byTooltip('Menu'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Copy rule'));
      await tester.pumpAndSettle();
      expect(calls.last['op'], 'copy');
      await tester.tap(find.byTooltip('Menu'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Paste rule'));
      await tester.pumpAndSettle();
      expect(calls.last['op'], 'paste');
    },
  );
  testWidgets('external snapshot updates text selection and focused field', (
    tester,
  ) async {
    final bridge = DictionaryRuleEditorBridge();
    await app(tester, bridge: bridge);
    bridge.accept({
      ...fixture(revision: 3),
      'ShowRule': 'returned',
      'ShowRuleStart': 2,
      'ShowRuleEnd': 5,
      'focus': 'ShowRule',
    });
    await tester.pump();
    await tester.pump();
    final field = tester.widget<TextField>(find.byType(TextField).at(2));
    expect(field.controller!.text, 'returned');
    expect(
      field.controller!.selection,
      const TextSelection(baseOffset: 2, extentOffset: 5),
    );
    expect(field.focusNode!.hasFocus, true);
  });
  testWidgets(
    'syntax keeps UTF16 text offsets, token colors and IME composing range',
    (tester) async {
      final controller = DictionaryRuleTextController();
      const text = '@js: const data = {"name":true}; return data\\n';
      controller.value = const TextEditingValue(
        text: text,
        selection: TextSelection.collapsed(offset: 4),
        composing: TextRange(start: 5, end: 10),
      );
      late TextSpan span;
      late ColorScheme palette;
      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (context) {
              palette = Theme.of(context).colorScheme;
              span = controller.buildTextSpan(
                context: context,
                style: const TextStyle(),
                withComposing: true,
              );
              return const SizedBox();
            },
          ),
        ),
      );
      expect(span.toPlainText(), text);
      final children = span.children!.cast<TextSpan>().toList();
      expect(
        children.any(
          (e) =>
              e.text == 'const' &&
              e.style!.color != null &&
              e.style!.decoration == TextDecoration.underline,
        ),
        true,
      );
      expect(
        children.any(
          (e) => e.text!.contains('return') && e.style!.color != null,
        ),
        true,
      );
      Color? colorAt(int position) {
        var offset = 0;
        for (final span in children) {
          final end = offset + span.text!.length;
          if (position < end) return span.style?.color;
          offset = end;
        }
        return null;
      }

      expect(
        DictionaryRuleTextController.patterns.first.firstMatch(text)!.group(0),
        '@js:',
      );
      expect(colorAt(0), palette.tertiary);
      expect(
        colorAt(3),
        palette.tertiary,
      ); // Later operation ':' retains the Legado token role.
      expect(colorAt(text.indexOf('const')), palette.secondary);
      expect(colorAt(text.indexOf('"name"')), palette.primary);
      expect(
        colorAt(text.indexOf('true')),
        palette.secondary,
      ); // JS wins over JSON token family.
      expect(colorAt(text.length - 2), palette.onSurfaceVariant);
      expect(controller.selection.baseOffset, 4);
      controller.dispose();
    },
  );
}
