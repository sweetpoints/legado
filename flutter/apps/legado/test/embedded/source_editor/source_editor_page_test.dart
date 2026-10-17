import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:legado_app/embedded/embedded_source_management.dart';
import 'package:legado_app/embedded/source_editor/source_editor_page.dart';
import 'package:legado_app/embedded/source_editor/source_editor_state.dart';
import 'package:legado_app/l10n/generated/app_localizations.dart';

import 'editor_fixture.dart';

void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  const bootstrap = MethodChannel('legado/embedded_ui');
  const channel = MethodChannel('legado/source_editor');
  const events = MethodChannel('legado/source_editor/state');
  late Map<String, Object?> native;
  late List<Map<String, Object?>> commands;
  String locale = 'en';
  setUp(() {
    native = editorFixture();
    commands = [];
    locale = 'en';
    binding.defaultBinaryMessenger.setMockMethodCallHandler(
      bootstrap,
      (_) async => {
        'route': '/source-editor',
        'locale': locale,
        'theme': 'dark',
      },
    );
    binding.defaultBinaryMessenger.setMockMethodCallHandler(
      events,
      (_) async => null,
    );
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if (call.method == 'snapshot') return native;
      commands.add(Map<String, Object?>.from(call.arguments as Map));
      return null;
    });
  });
  tearDown(() {
    for (final c in [bootstrap, channel, events]) {
      binding.defaultBinaryMessenger.setMockMethodCallHandler(c, null);
    }
  });
  Future<void> app(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1100, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      const ProviderScope(child: EmbeddedSourceManagementApp()),
    );
    await tester.pumpAndSettle();
  }

  Future<void> publish(WidgetTester tester, Map<String, Object?> next) async {
    native = next;
    await binding.defaultBinaryMessenger.handlePlatformMessage(
      events.name,
      const StandardMethodCodec().encodeSuccessEnvelope(next),
      (_) {},
    );
    if (next['busy'] == true || next['migrationRunning'] == true) {
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
    } else {
      await tester.pumpAndSettle();
    }
  }

  Finder tag(String id) => find.byKey(ValueKey(id));
  Future<void> click(WidgetTester tester, String id) async {
    await tester.ensureVisible(tag(id));
    await tester.tap(tag(id));
    await tester.pumpAndSettle();
  }

  testWidgets(
    'bootstrap selects full editor with seven tabs and native field labels',
    (tester) async {
      await app(tester);
      expect(find.text('Edit book source'), findsOneWidget);
      expect(find.text('Source name'), findsWidgets);
      for (var i = 0; i < 7; i++) {
        expect(tag('source-tab-$i'), findsOneWidget);
      }
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets('English and Chinese editor chrome use ARB localizations', (
    tester,
  ) async {
    locale = 'zh';
    await app(tester);
    expect(find.text('编辑书源'), findsOneWidget);
    expect(find.text('基本'), findsOneWidget);
    await click(tester, 'source-menu');
    expect(find.text('调试书源'), findsOneWidget);
  });
  testWidgets('edit queues complete text and cursor before save', (
    tester,
  ) async {
    await app(tester);
    await tester.enterText(find.byType(TextField).first, 'Rapid text 😀');
    await click(tester, 'source-save');
    final updates = commands.where((c) => c['op'] == 'field');
    expect(updates.last['value'], 'Rapid text 😀');
    expect(updates.last['end'], 'Rapid text 😀'.length);
    expect(commands.last, {'op': 'save', 'action': 'FINISH'});
  });
  testWidgets(
    'tabs send native selection without replacing the draft in Dart',
    (tester) async {
      await app(tester);
      await click(tester, 'source-tab-3');
      expect(commands.last, {'op': 'tab', 'index': 3});
      expect(tag('source-field-0-bookSourceName'), findsOneWidget);
      await publish(tester, editorFixture(tab: 3));
      expect(tag('source-field-3-rule3'), findsOneWidget);
    },
  );
  testWidgets(
    'expanded options send full immutable options and preserve rapid toggles',
    (tester) async {
      await app(tester);
      final doc = native['document'] as Map;
      doc['optionsExpanded'] = true;
      await publish(tester, native);
      await click(tester, 'source-option-enabled');
      await click(tester, 'source-option-enabledExplore');
      final edits = commands.where((c) => c['op'] == 'options').toList();
      final last = edits.last['value'] as Map;
      expect(last['enabled'], false);
      expect(last['enabledExplore'], false);
      expect(last.length, 7);
    },
  );
  testWidgets(
    'native discard state presents explicit keep and discard commands',
    (tester) async {
      await app(tester);
      await click(tester, 'source-close');
      expect(commands.last['op'], 'cancel');
      await publish(tester, {...native, 'confirmDiscard': true});
      await click(tester, 'source-discard-cancel');
      expect(commands.last['op'], 'keepEditing');
      await click(tester, 'source-discard-confirm');
      expect(commands.last['op'], 'discard');
    },
  );
  testWidgets('native variable draft uses ordered edit and save', (
    tester,
  ) async {
    await app(tester);
    await publish(tester, {
      ...native,
      'variable': 'old',
      'variableComment': 'Instructions',
    });
    expect(find.text('Instructions'), findsOneWidget);
    await tester.enterText(
      find.descendant(
        of: tag('source-variable'),
        matching: find.byType(TextField),
      ),
      'new variable',
    );
    await tester.tap(find.widgetWithText(TextButton, 'Save'));
    await tester.pumpAndSettle();
    expect(commands.last, {'op': 'variableSave', 'value': 'new variable'});
    expect(
      commands.any(
        (c) => c['op'] == 'variableEdit' && c['value'] == 'new variable',
      ),
      true,
    );
  });
  testWidgets('group selection inserts into native focused field', (
    tester,
  ) async {
    await app(tester);
    await publish(tester, {
      ...native,
      'groups': ['Favorites', 'Archived'],
    });
    await tester.tap(find.text('Favorites'));
    await tester.pumpAndSettle();
    expect(commands.sublist(commands.length - 2), [
      {'op': 'insert', 'text': 'Favorites'},
      {'op': 'dismissGroups'},
    ]);
  });
  testWidgets('migration preview respects native canApply and shows issues', (
    tester,
  ) async {
    await app(tester);
    await publish(tester, {
      ...native,
      'migrationReport': {
        'revision': 1,
        'status': 'needs_review',
        'requiresManualWork': true,
        'canApply': false,
        'issues': [
          {
            'path': 'ruleToc',
            'code': 'unsupported',
            'message': 'Review this rule',
          },
        ],
      },
    });
    expect(find.textContaining('Review this rule'), findsOneWidget);
    expect(
      tester
          .widget<FilledButton>(
            find.descendant(
              of: tag('source-migration-apply'),
              matching: find.byType(FilledButton),
            ),
          )
          .onPressed,
      isNull,
    );
    await publish(tester, {
      ...native,
      'migrationReport': {
        'revision': 1,
        'status': 'ready',
        'requiresManualWork': false,
        'canApply': true,
        'issues': [],
      },
    });
    await click(tester, 'source-migration-apply');
    expect(commands.last['op'], 'applyMigration');
  });
  testWidgets('busy state disables save and field edits', (tester) async {
    await app(tester);
    await publish(tester, {...native, 'busy': true});
    expect(
      tester.widget<TextField>(find.byType(TextField).first).enabled,
      false,
    );
    expect(
      tester
          .widget<IconButton>(
            find.descendant(
              of: tag('source-save'),
              matching: find.byType(IconButton),
            ),
          )
          .onPressed,
      isNull,
    );
  });
  testWidgets('native error remains visible and retry sends command', (
    tester,
  ) async {
    await app(tester);
    await publish(tester, {...native, 'error': 'Storage unavailable'});
    expect(find.text('Storage unavailable'), findsOneWidget);
    await click(tester, 'source-retry');
    expect(commands.last['op'], 'retry');
  });
  testWidgets('menu debug saves draft before native navigation', (
    tester,
  ) async {
    await app(tester);
    await click(tester, 'source-menu');
    await click(tester, 'source-menu-DEBUG');
    expect(commands.last, {'op': 'save', 'action': 'DEBUG'});
  });
  testWidgets(
    'long or combining-heavy fields open native rule editor without inline layout',
    (tester) async {
      native = editorFixture(value: 'x' * 20001);
      await app(tester);
      expect(find.byType(TextField), findsNothing);
      await click(tester, 'source-field-0-bookSourceName');
      expect(commands.sublist(commands.length - 2), [
        {'op': 'focus', 'tab': 0, 'key': 'bookSourceName'},
        {'op': 'native', 'action': 'EDITOR'},
      ]);
    },
  );
  testWidgets(
    'rapid input ignores stale echoes, acknowledges latest and accepts native undo',
    (tester) async {
      final commands = <Map<String, Object?>>[];
      SourceEditorField model(String text, int cursor) => SourceEditorField(
        key: 'name',
        label: 'Name',
        value: text,
        selectionStart: cursor,
        selectionEnd: cursor,
      );
      Future<void> render(SourceEditorField field) async {
        await tester.pumpWidget(
          MaterialApp(
            localizationsDelegates: AppLocalizations.localizationsDelegates,
            supportedLocales: AppLocalizations.supportedLocales,
            home: Scaffold(
              body: SourceEditorTextField(
                key: const ValueKey('field'),
                field: field,
                tab: 0,
                focused: false,
                enabled: true,
                command: (op, [args = const {}]) async {
                  commands.add({'op': op, ...args});
                },
                onEditor: () {},
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
      }

      await render(model('', 0));
      await tester.enterText(find.byType(TextField), 'a');
      await tester.enterText(find.byType(TextField), 'ab');
      await render(model('a', 1));
      expect(
        tester.widget<TextField>(find.byType(TextField)).controller!.text,
        'ab',
      );
      await render(model('ab', 2));
      await render(model('native undo', 6));
      final controller = tester
          .widget<TextField>(find.byType(TextField))
          .controller!;
      expect(controller.text, 'native undo');
      expect(controller.selection.baseOffset, 6);
      expect(commands.where((c) => c['op'] == 'field').length, 2);
    },
  );
  testWidgets(
    'same text edited back drains repeated pending values and accepts external updates',
    (tester) async {
      SourceEditorField model(String text) => SourceEditorField(
        key: 'name',
        label: 'Name',
        value: text,
        selectionStart: text.length,
        selectionEnd: text.length,
      );
      Future<void> render(String text) async {
        await tester.pumpWidget(
          MaterialApp(
            home: Scaffold(
              body: SourceEditorTextField(
                key: const ValueKey('field'),
                field: model(text),
                tab: 0,
                focused: false,
                enabled: true,
                command: (op, [args = const {}]) async {},
                onEditor: () {},
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
      }

      await render('');
      for (final text in ['a', 'b', 'a']) {
        await tester.enterText(find.byType(TextField), text);
      }
      await render('a');
      await render('external');
      expect(
        tester.widget<TextField>(find.byType(TextField)).controller!.text,
        'external',
      );
    },
  );
  testWidgets('IME composition survives matching native reflection', (
    tester,
  ) async {
    const field = SourceEditorField(key: 'name', label: 'Name', value: '');
    Future<void> render(SourceEditorField value) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SourceEditorTextField(
              key: const ValueKey('field'),
              field: value,
              tab: 0,
              focused: false,
              enabled: true,
              command: (op, [args = const {}]) async {},
              onEditor: () {},
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
    }

    await render(field);
    await tester.showKeyboard(find.byType(TextField));
    const composing = TextEditingValue(
      text: 'pin',
      selection: TextSelection.collapsed(offset: 3),
      composing: TextRange(start: 0, end: 3),
    );
    tester.testTextInput.updateEditingValue(composing);
    await tester.pump();
    await render(
      field.copyWith(value: 'pin', selectionStart: 3, selectionEnd: 3),
    );
    expect(
      tester
          .widget<TextField>(find.byType(TextField))
          .controller!
          .value
          .composing,
      composing.composing,
    );
  });
}
