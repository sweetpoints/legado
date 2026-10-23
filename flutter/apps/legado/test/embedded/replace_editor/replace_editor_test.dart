import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:legado_app/embedded/replace_editor/replace_editor_bridge.dart';
import 'package:legado_app/embedded/replace_editor/replace_editor_page.dart';

Map<String, Object?> snapshot({
  int generation = 1,
  int revision = 1,
  bool editable = true,
}) => {
  'generation': generation,
  'revision': revision,
  'fields': {
    for (final key in replaceFields)
      key: {'text': '', 'start': 0, 'end': 0, 'external': false, 'label': key},
  },
  'focus': null,
  'loaded': true,
  'busy': false,
  'editable': editable,
  'finished': false,
  'exit': false,
  'editorReturning': false,
  'editorOpen': false,
  'regex': false,
  'title': false,
  'source': false,
  'content': true,
  'preview': 'Preview',
  'previewFailed': false,
  'previewError': null,
  'scroll': 0,
  'keys': [
    {'key': '[x]', 'value': 'insert'},
  ],
  'rows': 1,
  'labels': {
    for (final key in [
      'heading',
      'back',
      'save',
      'editor',
      'more',
      'copy',
      'paste',
      'retry',
      'cancel',
      'keep',
      'discard',
      'exit',
      'exitMessage',
      'regex',
      'title',
      'source',
      'content',
      'help',
      'config',
      'output',
      'undo',
      'redo',
    ])
      key: key,
  },
};
void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('legado/replace_editor');
  const events = MethodChannel('legado/replace_editor/state');
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
        final fields = Map<String, Object?>.from(native['fields'] as Map);
        final field = Map<String, Object?>.from(fields[args['key']] as Map);
        fields[args['key'] as String] = {
          ...field,
          'text': args['text'],
          'start': args['start'],
          'end': args['end'],
        };
        native = {...native, 'fields': fields};
      }
      if (['keep', 'discard'].contains(args['op'])) {
        native = {...native, 'exit': false};
      }
      if (args['op'] == 'clearFocus') native = {...native, 'focus': null};
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
    'retired generation and old revision never overwrite newer native drafts',
    () async {
      final bridge = ReplaceEditorBridge();
      bridge.accept(snapshot(revision: 5));
      bridge.accept(snapshot(revision: 2));
      expect(bridge.latest!.revision, 5);
      bridge.accept(snapshot(generation: 2));
      bridge.accept(snapshot(revision: 8));
      expect(bridge.latest!.generation, 2);
      await bridge.close();
    },
  );
  test('failed field ACK blocks save clipboard fullscreen flags and retry until repaired', () async {
    final bridge = ReplaceEditorBridge();
    await bridge.start();
    reject = true;
    await expectLater(
      bridge.command('field', {
        'key': 'Pattern',
        'text': 'Draft',
        'start': 4,
        'end': 1,
      }),
      throwsA(isA<PlatformException>()),
    );
    for (final op in ['save', 'copy', 'paste', 'editor', 'flags', 'retry']) {
      await expectLater(bridge.command(op), throwsStateError);
    }
    expect(commands.map((e) => e['op']), ['field']);
    reject = false;
    await bridge.command('field', {
      'key': 'Pattern',
      'text': 'Draft',
      'start': 4,
      'end': 1,
    });
    expect(bridge.latest!.fields['Pattern']!.start, 4);
    expect(bridge.latest!.fields['Pattern']!.end, 1);
    await bridge.command('save');
    expect(commands.last['op'], 'save');
    await bridge.close();
  });
  test('save waits for queued native field ACK and queued old owner work is retired', () async {
    final bridge = ReplaceEditorBridge();
    await bridge.start();
    gate = Completer<void>();
    final field = bridge.command('field', {
      'key': 'Name',
      'text': 'Draft',
      'start': 5,
      'end': 5,
    });
    final save = bridge.command('save');
    await Future<void>.delayed(Duration.zero);
    expect(commands.map((e) => e['op']), ['field']);
    gate!.complete();
    await field;
    await save;
    expect(commands.last['op'], 'save');
    bridge.accept(snapshot(generation: 2));
    await bridge.close();
    await expectLater(bridge.command('save'), throwsStateError);
  });
  testWidgets('all fields flags preview and guarded save use real controls', (
    tester,
  ) async {
    final bridge = ReplaceEditorBridge();
    await tester.pumpWidget(
      MaterialApp(home: ReplaceEditorPage(bridge: bridge)),
    );
    await tester.pumpAndSettle();
    final name = find.byType(TextField).first;
    await tester.enterText(name, 'Draft');
    await tester.pumpAndSettle();
    expect((native['fields'] as Map)['Name']['text'], 'Draft');
    await tester.tap(find.byTooltip('save'));
    await tester.pumpAndSettle();
    expect(commands.last['op'], 'save');
    expect(find.byType(TextField), findsNWidgets(8));
    expect(find.byType(CheckboxListTile), findsNWidgets(4));
    expect(find.byType(SelectableText), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
  });
  testWidgets(
    'eight exact values and four flags reach native commands, output clears fullscreen target',
    (tester) async {
      final bridge = ReplaceEditorBridge();
      await tester.pumpWidget(
        MaterialApp(home: ReplaceEditorPage(bridge: bridge)),
      );
      await tester.pumpAndSettle();
      for (var index = 0; index < replaceFields.length; index++) {
        final target = find.byType(TextField).at(index);
        await tester.ensureVisible(target);
        await tester.enterText(target, index == 6 ? '1250' : 'value-$index');
        await tester.pumpAndSettle();
        expect(
          (native['fields'] as Map)[replaceFields[index]]['text'],
          index == 6 ? '1250' : 'value-$index',
        );
      }
      for (var index = 0; index < 4; index++) {
        final target = find.byType(CheckboxListTile).at(index);
        await tester.ensureVisible(target);
        await tester.tap(target);
        await tester.pumpAndSettle();
        expect(commands.last['op'], 'flags');
        expect(
          commands.last[['regex', 'title', 'source', 'content'][index]],
          index != 3,
        );
      }
      await tester.ensureVisible(find.byType(SelectableText));
      await tester.tap(find.byType(SelectableText));
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('editor'));
      await tester.pumpAndSettle();
      expect(commands.last['op'], 'editor');
      expect(commands.last['key'], isNull);
      await tester.pumpWidget(const SizedBox());
      await tester.pump();
    },
  );
  testWidgets(
    'unsafe fields launch exact external target and pending return offers retry discard',
    (tester) async {
      final fields = Map<String, Object?>.from(native['fields'] as Map);
      fields['Pattern'] = {...(fields['Pattern'] as Map), 'external': true};
      native = {...native, 'fields': fields};
      final bridge = ReplaceEditorBridge();
      await tester.pumpWidget(
        MaterialApp(home: ReplaceEditorPage(bridge: bridge)),
      );
      await tester.pumpAndSettle();
      final external = find.widgetWithText(OutlinedButton, 'Pattern — editor');
      await tester.ensureVisible(external);
      await tester.tap(external);
      await tester.pumpAndSettle();
      expect(commands.last['op'], 'editor');
      expect(commands.last['key'], 'Pattern');
      native = {
        ...native,
        'editable': false,
        'editorReturning': true,
        'revision': 99,
      };
      bridge.accept(native);
      await tester.pumpAndSettle();
      await tester.tap(find.text('retry'));
      await tester.pumpAndSettle();
      expect(commands.last['op'], 'retryEditor');
      await tester.tap(find.text('cancel'));
      await tester.pumpAndSettle();
      expect(commands.last['op'], 'discardEditor');
      expect(
        tester
            .widget<IconButton>(find.widgetWithIcon(IconButton, Icons.save))
            .onPressed,
        isNull,
      );
      await tester.pumpWidget(const SizedBox());
      await tester.pump();
    },
  );
  testWidgets(
    'keyboard assistance keeps native insertion undo redo and configuration commands',
    (tester) async {
      tester.view.viewInsets = const FakeViewPadding(bottom: 250);
      addTearDown(tester.view.resetViewInsets);
      final bridge = ReplaceEditorBridge();
      await tester.pumpWidget(
        MaterialApp(home: ReplaceEditorPage(bridge: bridge)),
      );
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).first, 'Name');
      await tester.pumpAndSettle();
      await tester.tap(find.text('[x]'));
      await tester.pumpAndSettle();
      expect(commands.last['op'], 'insert');
      expect(commands.last['text'], 'insert');
      for (final op in ['undo', 'redo', 'config']) {
        await tester.tap(find.byTooltip(op));
        await tester.pumpAndSettle();
        expect(commands.last['op'], op);
      }
      await tester.pumpWidget(const SizedBox());
      await tester.pump();
    },
  );
  testWidgets(
    'dirty close uses modal keep and discard without losing current text',
    (tester) async {
      final bridge = ReplaceEditorBridge();
      await tester.pumpWidget(
        MaterialApp(home: ReplaceEditorPage(bridge: bridge)),
      );
      await tester.pumpAndSettle();
      reject = true;
      await tester.enterText(find.byType(TextField).first, 'Rejected draft');
      await tester.pumpAndSettle();
      native = {...native, 'exit': true, 'revision': 99};
      bridge.accept(native);
      await tester.pumpAndSettle();
      expect(find.byType(AlertDialog), findsOneWidget);
      await tester.tap(find.text('keep'));
      await tester.pumpAndSettle();
      expect(commands.any((e) => e['op'] == 'keep'), isTrue);
      expect(
        tester.widget<TextField>(find.byType(TextField).first).controller!.text,
        'Rejected draft',
      );
      await tester.tap(find.byTooltip('back'));
      await tester.pumpAndSettle();
      expect(commands.last['op'], 'close');
      expect(commands.last['localDirty'], isTrue);

      await tester.pumpWidget(const SizedBox());
      await tester.pump();
    },
  );
}
