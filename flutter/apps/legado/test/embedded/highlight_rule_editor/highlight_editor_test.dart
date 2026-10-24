import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:legado_app/l10n/generated/app_localizations.dart';
import 'package:legado_app/embedded/highlight_rule_editor/highlight_rule_editor_bridge.dart';
import 'package:legado_app/embedded/highlight_rule_editor/highlight_rule_editor_page.dart';
import 'package:legado_app/embedded/highlight_rule_editor/highlight_color_picker_bridge.dart';
import 'package:legado_app/embedded/highlight_rule_editor/highlight_color_picker_page.dart';

Map<String, Object?> editorFixture() => {
  'generation': 1,
  'revision': 1,
  'editable': true,
  'loading': false,
  'saving': false,
  'finished': false,
  'error': null,
  'focus': null,
  'scroll': 0,
  'regex': false,
  'body': true,
  'title': false,
  'fields': {
    for (final k in highlightFields)
      k: {'text': k == 'name' ? 'Original' : '', 'start': 0, 'end': 0},
  },
  'labels': {
    for (final k in [
      ...highlightFields,
      'heading',
      'retry',
      'style',
      'save',
      'cancel',
      'regex',
      'body',
      'title',
    ])
      k: k,
  },
  'preview': {
    'png': Uint8List.fromList(
      base64Decode(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==',
      ),
    ),
    'width': 1.0,
    'height': 1.0,
    'textColor': -1,
    'inputSha256': 'fixture',
  },
};
Map<String, Object?> colorFixture({bool alpha = true}) => {
  'generation': 11,
  'nonce': 'independent-color',
  'revision': 1,
  'sequence': 0,
  'value': 0x80123456.toSigned(32),
  'hex': alpha ? '#80123456' : '#123456',
  'invalid': false,
  'finished': false,
  'withAlpha': alpha,
  'presets': [-65536, 0, 0x80123456.toSigned(32)],
  'labels': {'heading': 'Color', 'save': 'Save', 'cancel': 'Cancel'},
};
void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  const editor = MethodChannel('legado/highlight_rule_editor');
  const color = MethodChannel('legado/highlight_rule_color');
  late Map<String, Object?> native;
  late List<Map> calls;
  Completer<void>? gate;
  bool reject = false;
  setUp(() {
    native = editorFixture();
    calls = [];
    gate = null;
    reject = false;
    for (final name in [
      'legado/highlight_rule_editor/state',
      'legado/highlight_rule_color/state',
    ]) {
      binding.defaultBinaryMessenger.setMockMethodCallHandler(
        MethodChannel(name),
        (_) async => null,
      );
    }
    for (final channel in [editor, color]) {
      binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
        call,
      ) async {
        if (call.method == 'snapshot') return native;
        final a = call.arguments as Map;
        calls.add(a);
        if (['field', 'hex', 'channel', 'preset'].contains(a['op'])) {
          await gate?.future;
          if (reject) throw PlatformException(code: 'input_rejected');
        }
        if (a['op'] == 'field') {
          native = {
            ...native,
            'fields': {
              ...native['fields'] as Map,
              a['key']: {
                'text': a['text'],
                'start': a['start'],
                'end': a['end'],
              },
            },
            'focus': a['key'],
          };
        }
        if (a['op'] == 'hex') {
          final raw = a['text'] as String,
              clean = (a['text'] as String).replaceFirst('#', '');
          final valid = RegExp(
            native['withAlpha'] == true
                ? r'^[0-9a-fA-F]{8}$'
                : r'^[0-9a-fA-F]{6}$',
          ).hasMatch(clean);
          native = {
            ...native,
            'hex': raw,
            'invalid': !valid,
            if (valid)
              'value':
                  (int.parse(clean, radix: 16) |
                          (native['withAlpha'] == true ? 0 : 0xff000000))
                      .toSigned(32),
          };
        }
        if (a['op'] == 'preset') {
          native = {
            ...native,
            'value': (native['presets'] as List)[a['index'] as int],
          };
        }
        if (a.containsKey('sequence')) {
          native = {...native, 'sequence': a['sequence']};
        }
        if (a['op'] == 'cancel' || a['op'] == 'save') {
          native = {...native, 'finished': true};
        }
        native = {...native, 'revision': (native['revision'] as int) + 1};
        return native;
      });
    }
  });
  tearDown(() {
    for (final name in [
      'legado/highlight_rule_editor',
      'legado/highlight_rule_color',
      'legado/highlight_rule_editor/state',
      'legado/highlight_rule_color/state',
    ]) {
      binding.defaultBinaryMessenger.setMockMethodCallHandler(
        MethodChannel(name),
        null,
      );
    }
  });
  Future<void> app(WidgetTester t, Widget page) async {
    await t.pumpWidget(
      MaterialApp(
        locale: const Locale('en'),
        supportedLocales: AppLocalizations.supportedLocales,
        localizationsDelegates: [
          AppLocalizations.delegate,
          ...GlobalMaterialLocalizations.delegates,
        ],
        home: page,
      ),
    );
    await t.pump();
    await t.pump();
  }

  test(
    'editor serializes exact raw selection ACK before dependent save',
    () async {
      final b = HighlightRuleEditorBridge();
      b.accept(native);
      gate = Completer();
      final field = b.command('field', {
        'key': 'name',
        'text': 'Draft',
        'start': 4,
        'end': 1,
      });
      final save = b.command('save');
      await Future<void>.delayed(Duration.zero);
      expect(calls.map((a) => a['op']), ['field']);
      gate!.complete();
      await field;
      await save;
      expect(calls.map((a) => a['op']), ['field', 'save']);
      expect(b.latest!.start('name'), 4);
      expect(b.latest!.end('name'), 1);
      await b.close();
    },
  );
  test('rejected editor field blocks style and save but repair and explicit cancel work', () async {
    final b = HighlightRuleEditorBridge();
    b.accept(native);
    reject = true;
    await expectLater(
      b.command('field', {
        'key': 'name',
        'text': 'Draft',
        'start': 0,
        'end': 0,
      }),
      throwsA(isA<PlatformException>()),
    );
    await expectLater(b.command('style'), throwsStateError);
    await expectLater(b.command('save'), throwsStateError);
    expect(calls.length, 1);
    reject = false;
    await b.command('field', {
      'key': 'name',
      'text': 'Draft',
      'start': 0,
      'end': 0,
    });
    expect(b.hasRejectedField, false);
    await b.command('cancel');
    await b.close();
  });
  test('editor retires queued writes on generation replacement and rejects invalid ranges', () async {
    final b = HighlightRuleEditorBridge();
    b.accept(native);
    gate = Completer();
    final f = b.command('field', {
      'key': 'name',
      'text': 'Draft',
      'start': 0,
      'end': 0,
    });
    await Future<void>.delayed(Duration.zero);
    b.accept({...native, 'generation': 2});
    gate!.complete();
    await expectLater(f, throwsStateError);
    b.accept(native);
    expect(b.latest!.generation, 2);
    expect(
      () => HighlightEditorSnapshot({
        ...native,
        'fields': {
          ...native['fields'] as Map,
          'name': {'text': 'x', 'start': 2, 'end': 0},
        },
      }),
      throwsFormatException,
    );
    await b.close();
  });
  testWidgets(
    'native back with pending editor input keeps or discards through Dart confirmation',
    (t) async {
      gate = Completer();
      await app(t, const HighlightRuleEditorPage());
      await t.enterText(
        find.byKey(const ValueKey('highlight-rule-name')),
        'Local draft',
      );
      await t.pump();
      await t.binding.handlePopRoute();
      await t.pumpAndSettle();
      expect(find.text('Keep editing'), findsOneWidget);
      expect(calls.where((a) => a['op'] == 'cancel'), isEmpty);
      await t.tap(find.text('Keep editing'));
      await t.pumpAndSettle();
      await t.binding.handlePopRoute();
      await t.pumpAndSettle();
      await t.tap(find.text('Discard changes'));
      await t.pump();
      expect(calls.where((a) => a['op'] == 'cancel'), isEmpty);
      gate!.complete();
      await t.pumpAndSettle();
      expect(calls.where((a) => a['op'] == 'cancel'), hasLength(1));
    },
  );
  testWidgets(
    'editor preserves rejected local text and native back asks before discard',
    (t) async {
      reject = true;
      await app(t, const HighlightRuleEditorPage());
      await t.enterText(
        find.byKey(const ValueKey('highlight-rule-name')),
        'Rejected draft',
      );
      await t.pumpAndSettle();
      expect(
        t
            .widget<TextField>(
              find.byKey(const ValueKey('highlight-rule-name')),
            )
            .controller!
            .text,
        'Rejected draft',
      );
      await t.binding.handlePopRoute();
      await t.pumpAndSettle();
      expect(find.text('Discard changes'), findsOneWidget);
      await t.tap(find.text('Discard changes'));
      await t.pumpAndSettle();
      expect(calls.where((a) => a['op'] == 'cancel'), hasLength(1));
    },
  );
  testWidgets(
    'restored editor uses raw reversed selection and actual focused controller',
    (t) async {
      native = {
        ...native,
        'focus': 'name',
        'fields': {
          ...native['fields'] as Map,
          'name': {'text': 'Restored', 'start': 6, 'end': 2},
        },
      };
      await app(t, const HighlightRuleEditorPage());
      await t.pumpAndSettle();
      final field = t.widget<TextField>(
        find.byKey(const ValueKey('highlight-rule-name')),
      );
      expect(field.controller!.text, 'Restored');
      expect(
        field.controller!.selection,
        const TextSelection(baseOffset: 6, extentOffset: 2),
      );
      expect(field.focusNode!.hasFocus, true);
    },
  );
  test('color accepts high bit and zero without lossy unsigned Int casts, independent nonce rejects cross session', () async {
    native = colorFixture();
    final b = HighlightColorPickerBridge();
    b.accept(native);
    await b.command('hex', {'text': '#FFFFFFFF'});
    expect(b.latest!.color, -1);
    await b.command('hex', {'text': '#00000000'});
    expect(b.latest!.color, 0);
    expect(
      () => b.accept({...native, 'nonce': 'other-window'}),
      throwsStateError,
    );
    expect(
      calls.every(
        (a) => a['nonce'] == 'independent-color' && a['generation'] == 11,
      ),
      true,
    );
    await b.close();
  });
  testWidgets(
    'color invalid input disables save and native back confirms pending draft',
    (t) async {
      native = colorFixture();
      gate = Completer();
      await app(t, const HighlightColorPickerPage());
      await t.enterText(
        find.byKey(const ValueKey('highlight-rule-color-hex')),
        '#invalid',
      );
      await t.pump();
      await t.binding.handlePopRoute();
      await t.pumpAndSettle();
      expect(find.text('Keep editing'), findsOneWidget);
      gate!.complete();
      await t.pumpAndSettle();
      await t.tap(find.text('Keep editing'));
      await t.pumpAndSettle();
      final save = find.widgetWithText(TextButton, 'Save');
      expect(t.widget<TextButton>(save).onPressed, isNull);
      expect(calls.where((a) => a['op'] == 'save'), isEmpty);
      await t.tap(find.text('Cancel'));
      await t.pumpAndSettle();
      expect(calls.where((a) => a['op'] == 'cancel'), hasLength(1));
      expect(calls.any((a) => a['generation'] == 1), false);
    },
  );
  testWidgets(
    'nonalpha picker excludes alpha slider, floor channel values and uses six digit hex',
    (t) async {
      native = colorFixture(alpha: false);
      await app(t, const HighlightColorPickerPage());
      expect(
        find.byKey(const ValueKey('highlight-rule-color-A')),
        findsNothing,
      );
      final slider = t.widget<Slider>(
        find.byKey(const ValueKey('highlight-rule-color-R')),
      );
      slider.onChanged!(17.9);
      await t.pumpAndSettle();
      expect(calls.last['value'], 17);
      await t.enterText(
        find.byKey(const ValueKey('highlight-rule-color-hex')),
        '#654321',
      );
      await t.pumpAndSettle();
      expect(native['value'], 0xff654321.toSigned(32));
    },
  );
  test('rejected color mutation blocks save but permits explicit cancellation after monotonic sequence gap', () async {
    native = colorFixture();
    final b = HighlightColorPickerBridge();
    b.accept(native);
    reject = true;
    await expectLater(
      b.command('hex', {'text': '#FFFFFFFF'}),
      throwsA(isA<PlatformException>()),
    );
    await expectLater(b.command('save'), throwsStateError);
    await b.command('cancel');
    expect(calls.last['sequence'], 3);
    expect(calls.where((a) => a['op'] == 'cancel'), hasLength(1));
    await b.close();
  });
}
