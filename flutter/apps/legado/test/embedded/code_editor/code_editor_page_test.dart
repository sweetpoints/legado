import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:legado_app/embedded/embedded_source_management.dart';
import 'package:legado_app/embedded/code_editor/code_editor_page.dart';
import 'package:legado_app/embedded/code_editor/code_editor_surface.dart';
import 'package:legado_app/l10n/generated/app_localizations.dart';

import 'code_fixture.dart';

class _SurfaceProbe extends StatefulWidget {
  const _SurfaceProbe({
    required this.generation,
    required this.created,
    required this.closed,
  });
  final int generation;
  final void Function(int) created, closed;
  @override
  State<_SurfaceProbe> createState() => _SurfaceProbeState();
}

class _SurfaceProbeState extends State<_SurfaceProbe> {
  @override
  void initState() {
    super.initState();
    widget.created(widget.generation);
  }

  @override
  void dispose() {
    widget.closed(widget.generation);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => SizedBox.expand(
    key: ValueKey('native-surface-probe-${widget.generation}'),
  );
}

void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  const bootstrap = MethodChannel('legado/embedded_ui');
  const channel = MethodChannel('legado/code_editor');
  const events = MethodChannel('legado/code_editor/state');
  late Map<String, Object?> native;
  late List<Map<String, Object?>> commands;
  late List<int> created, closed;
  String locale = 'en';
  Future<void> Function(Map<String, Object?>)? handler;
  setUp(() {
    native = codeFixture();
    commands = [];
    created = [];
    closed = [];
    locale = 'en';
    handler = null;
    binding.defaultBinaryMessenger.setMockMethodCallHandler(
      bootstrap,
      (_) async => {'route': '/code-editor', 'locale': locale, 'theme': 'dark'},
    );
    binding.defaultBinaryMessenger.setMockMethodCallHandler(
      events,
      (_) async => null,
    );
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if (call.method == 'snapshot') return native;
      final args = Map<String, Object?>.from(call.arguments as Map);
      commands.add(args);
      await handler?.call(args);
      return null;
    });
  });
  tearDown(() {
    for (final c in [bootstrap, channel, events]) {
      binding.defaultBinaryMessenger.setMockMethodCallHandler(c, null);
    }
  });
  Finder tag(String id) => find.byKey(ValueKey(id));
  Future<void> app(
    WidgetTester tester, {
    bool keyboard = false,
    Size size = const Size(1100, 900),
  }) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    if (keyboard) {
      tester.view.viewInsets = const FakeViewPadding(bottom: 240);
      addTearDown(tester.view.resetViewInsets);
    }
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          codeEditorSurfaceBuilderProvider.overrideWith(
            (ref) =>
                (context, generation) => _SurfaceProbe(
                  generation: generation,
                  created: created.add,
                  closed: closed.add,
                ),
          ),
        ],
        child: const EmbeddedSourceManagementApp(),
      ),
    );
    if ((native['engine'] as Map)['ready'] == false) {
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
    } else {
      await tester.pumpAndSettle();
    }
  }

  Future<void> publish(WidgetTester tester, Map<String, Object?> value) async {
    native = value;
    await binding.defaultBinaryMessenger.handlePlatformMessage(
      events.name,
      const StandardMethodCodec().encodeSuccessEnvelope(value),
      (_) {},
    );
    final engine = value['engine'] as Map;
    final progress =
        value['busy'] == true ||
        engine['reading'] == true ||
        engine['replacing'] == true ||
        (value['hasSession'] == true &&
            engine['ready'] == false &&
            engine['failed'] == false);
    if (progress) {
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
    } else {
      await tester.pumpAndSettle();
    }
  }

  Future<void> click(WidgetTester tester, String id) async {
    await tester.ensureVisible(tag(id));
    await tester.tap(tag(id));
    await tester.pumpAndSettle();
  }

  Finder input(String id) =>
      find.descendant(of: tag(id), matching: find.byType(TextField));
  testWidgets(
    'bootstrap opens native-surface editor without document text in Dart',
    (tester) async {
      await app(tester);
      expect(find.byType(CodeEditorPage), findsOneWidget);
      expect(created, [1]);
      expect(find.byType(TextField), findsNothing);
      expect(tag('code-toolbar'), findsOneWidget);
      await click(tester, 'code-save');
      expect(commands.last, {'op': 'action', 'name': 'SAVE'});
    },
  );
  testWidgets('editor chrome supports Chinese localization', (tester) async {
    locale = 'zh';
    await app(tester);
    await click(tester, 'code-menu');
    expect(find.text('编辑器主题'), findsOneWidget);
    expect(find.text('格式化代码'), findsOneWidget);
  });
  testWidgets('session generation alone recreates native surface', (
    tester,
  ) async {
    await app(tester);
    await publish(tester, {...native, 'autoWrap': true});
    await publish(tester, {...native, 'error': 'retryable'});
    expect(created, [1]);
    expect(closed, isEmpty);
    await publish(tester, codeFixture(generation: 2));
    expect(created, [1, 2]);
    expect(closed, [1]);
  });
  testWidgets('no session never creates a platform surface', (tester) async {
    native = {...native, 'hasSession': false, 'generation': 0};
    await app(tester);
    expect(created, isEmpty);
  });
  testWidgets('read-only editor hides write actions and disables replacement', (
    tester,
  ) async {
    native = {...codeFixture(visible: true), 'writable': false};
    await app(tester);
    expect(tag('code-save'), findsNothing);
    expect(tag('code-debug'), findsNothing);
    expect(
      tester
          .widget<TextButton>(
            find.descendant(
              of: tag('code-replace'),
              matching: find.byType(TextButton),
            ),
          )
          .onPressed,
      isNull,
    );
    await click(tester, 'code-menu');
    expect(tag('code-login'), findsNothing);
    expect(tag('code-menu-FORMAT'), findsNothing);
  });
  testWidgets('safe editor omits Sora-only search and menu actions', (
    tester,
  ) async {
    native = {...native, 'safe': true};
    await app(tester);
    expect(tag('code-search-toggle'), findsNothing);
    await click(tester, 'code-menu');
    for (final name in [
      'THEME',
      'FORMAT',
      'SELECT_ALL',
      'SYNTAX',
      'SETTINGS',
      'WRAP',
    ]) {
      expect(tag('code-menu-$name'), findsNothing);
    }
    expect(tag('code-menu-CURL'), findsOneWidget);
  });
  testWidgets('engine reading disables toolbar without replacing native view', (
    tester,
  ) async {
    await app(tester);
    (native['engine'] as Map)['reading'] = true;
    await publish(tester, native);
    expect(created, [1]);
    expect(
      tester
          .widget<IconButton>(
            find.descendant(
              of: tag('code-save'),
              matching: find.byType(IconButton),
            ),
          )
          .onPressed,
      isNull,
    );
  });
  testWidgets(
    'restored visible search gains focus when the native engine becomes ready',
    (tester) async {
      native = codeFixture(visible: true);
      (native['engine'] as Map)['ready'] = false;
      await app(tester);
      expect(
        tester.widget<TextField>(input('code-query')).focusNode!.hasFocus,
        false,
      );
      (native['engine'] as Map)['ready'] = true;
      await publish(tester, native);
      expect(
        tester.widget<TextField>(input('code-query')).focusNode!.hasFocus,
        true,
      );
      expect(tester.testTextInput.hasAnyClients, true);
      expect(tester.testTextInput.isVisible, true);
    },
  );
  testWidgets('native load errors expose retry and failed engine restart', (
    tester,
  ) async {
    await app(tester);
    await publish(tester, {
      ...native,
      'error': 'Session failure',
      'engine': {...(native['engine'] as Map), 'ready': false, 'failed': true},
    });
    expect(find.text('Session failure'), findsOneWidget);
    await click(tester, 'code-retry');
    expect(commands.last['op'], 'retry');
    await click(tester, 'code-restart');
    expect(commands.last['op'], 'restart');
  });
  testWidgets(
    'system Back and explicit close use native exit and discard confirmation',
    (tester) async {
      await app(tester);
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(commands.last['op'], 'exit');
      await publish(tester, {...native, 'confirmDiscard': true});
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(commands.last['op'], 'keepEditing');
      await click(tester, 'code-discard-confirm');
      expect(commands.last['op'], 'discard');
    },
  );
  testWidgets('native menu actions retain stable command names', (
    tester,
  ) async {
    await app(tester);
    await click(tester, 'code-menu');
    await click(tester, 'code-menu-FORMAT');
    expect(commands.last, {'op': 'action', 'name': 'FORMAT'});
    await click(tester, 'code-menu');
    await click(tester, 'code-login');
    expect(commands.last, {'op': 'action', 'name': 'LOGIN'});
  });
  testWidgets('search echoes cannot overwrite later fast query input', (
    tester,
  ) async {
    native = codeFixture(visible: true);
    await app(tester);
    await tester.enterText(input('code-query'), 'a');
    await tester.enterText(input('code-query'), 'ab');
    final stale = {
      ...codeFixture(visible: true),
      'search': {
        ...(native['search'] as Map),
        'query': 'a',
        'queryStart': 1,
        'queryEnd': 1,
      },
    };
    await publish(tester, stale);
    expect(
      tester.widget<TextField>(input('code-query')).controller!.text,
      'ab',
    );
    final latest = {
      ...native,
      'search': {
        ...(native['search'] as Map),
        'query': 'ab',
        'queryStart': 2,
        'queryEnd': 2,
      },
    };
    await publish(tester, latest);
    expect(
      tester.widget<TextField>(input('code-query')).controller!.text,
      'ab',
    );
    await publish(tester, {
      ...native,
      'search': {
        ...(native['search'] as Map),
        'query': 'restored',
        'queryStart': 3,
        'queryEnd': 3,
      },
    });
    expect(
      tester.widget<TextField>(input('code-query')).controller!.text,
      'restored',
    );
    expect(created, [1]);
  });
  testWidgets(
    'query and replacement updates preserve the other optimistic draft',
    (tester) async {
      native = codeFixture(visible: true);
      (native['search'] as Map)['replaceVisible'] = true;
      (native['search'] as Map)['ignoreCase'] = true;
      await app(tester);
      await tester.enterText(input('code-query'), 'find');
      await tester.enterText(input('code-replacement'), 'replace');
      await click(tester, 'code-save');
      final last =
          commands.where((c) => c['op'] == 'search').last['value'] as Map;
      expect(last['query'], 'find');
      expect(last['replacement'], 'replace');
      expect(last['ignoreCase'], true);
      expect(commands.last['name'], 'SAVE');
    },
  );
  testWidgets(
    'assist inserts into focused Flutter query with selection and no document insert',
    (tester) async {
      native = codeFixture(visible: true);
      await app(tester, keyboard: true);
      await tester.enterText(input('code-query'), 'abc');
      final controller = tester
          .widget<TextField>(input('code-query'))
          .controller!;
      controller.selection = const TextSelection(
        baseOffset: 1,
        extentOffset: 2,
      );
      await tester.pump();
      await click(tester, 'code-assist-0');
      expect(controller.text, 'a@c');
      expect(controller.selection.baseOffset, 2);
      expect(commands.where((c) => c['op'] == 'insert'), isEmpty);
    },
  );
  testWidgets(
    'assist inserts into replacement while native undo redo remain separate',
    (tester) async {
      native = codeFixture(visible: true);
      (native['search'] as Map)['replaceVisible'] = true;
      await app(tester, keyboard: true);
      await tester.enterText(input('code-replacement'), 'xy');
      await click(tester, 'code-assist-0');
      expect(
        tester.widget<TextField>(input('code-replacement')).controller!.text,
        'xy@',
      );
      await click(tester, 'code-undo');
      expect(commands.last['name'], 'UNDO');
      await click(tester, 'code-redo');
      expect(commands.last['name'], 'REDO');
    },
  );
  testWidgets(
    'assist goes to native document when Flutter search is not focused',
    (tester) async {
      await app(tester, keyboard: true);
      await click(tester, 'code-assist-0');
      expect(commands.last, {'op': 'insert', 'text': '@'});
    },
  );
  testWidgets(
    'search rejection rolls back and already queued save is blocked',
    (tester) async {
      native = codeFixture(visible: true);
      final barrier = Completer<void>();
      var reject = true;
      handler = (args) async {
        if (args['op'] == 'search' && reject) {
          await barrier.future;
          throw PlatformException(code: 'invalid_request');
        }
      };
      await app(tester);
      await tester.enterText(input('code-query'), 'rejected');
      await tester.tap(tag('code-save'));
      await tester.pump();
      barrier.complete();
      await tester.pumpAndSettle();
      expect(
        tester.widget<TextField>(input('code-query')).controller!.text,
        '',
      );
      expect(commands.where((c) => c['name'] == 'SAVE'), isEmpty);
      expect(tag('code-retry'), findsOneWidget);
      reject = false;
      await tester.enterText(input('code-query'), 'accepted');
      await click(tester, 'code-save');
      expect(commands.last['name'], 'SAVE');
    },
  );
  testWidgets(
    'search regex toggles use current draft even before widget rebuild',
    (tester) async {
      native = codeFixture(visible: true);
      await app(tester);
      final checkbox = tester.widget<Checkbox>(
        find.descendant(of: tag('code-regex'), matching: find.byType(Checkbox)),
      );
      checkbox.onChanged!(false);
      checkbox.onChanged!(false);
      await tester.pumpAndSettle();
      final values = commands
          .where((c) => c['op'] == 'search')
          .map((c) => (c['value'] as Map)['regex'])
          .toList();
      expect(values, [false, true]);
    },
  );
  testWidgets('search and keyboard remain within a narrow mobile layout', (
    tester,
  ) async {
    native = codeFixture(visible: true);
    (native['search'] as Map)['replaceVisible'] = true;
    await app(tester, keyboard: true, size: const Size(360, 800));
    expect(tester.takeException(), isNull);
    expect(tag('code-search'), findsOneWidget);
  });
  testWidgets(
    'help references invoke native actions and Back only closes help',
    (tester) async {
      await app(tester);
      await click(tester, 'code-menu');
      await click(tester, 'code-menu-HELP');
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(commands, isEmpty);
      await click(tester, 'code-menu');
      await click(tester, 'code-menu-HELP');
      await click(tester, 'code-help-RULE_HELP');
      expect(commands.last, {'op': 'action', 'name': 'RULE_HELP'});
    },
  );
  testWidgets('Android surface uses traditional HC and sends generation only', (
    tester,
  ) async {
    final creates = <Map>[];
    final disposes = <Object?>[];
    binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform_views,
      (call) async {
        if (call.method == 'create') creates.add(call.arguments as Map);
        if (call.method == 'dispose') disposes.add(call.arguments);
        return null;
      },
    );
    addTearDown(
      () => binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform_views,
        null,
      ),
    );
    Future<void> render(int generation) async {
      await tester.pumpWidget(
        MaterialApp(home: CodeEditorSurface(generation: generation)),
      );
      await tester.pumpAndSettle();
    }

    await render(7);
    expect(creates.length, 1);
    expect(creates.single['hybrid'], true);
    expect(creates.single['viewType'], 'legado/code_editor_surface');
    final bytes = creates.single['params'] as Uint8List;
    expect(
      const StandardMessageCodec().decodeMessage(ByteData.sublistView(bytes)),
      {'generation': 7},
    );
    await render(7);
    expect(creates.length, 1);
    await render(8);
    expect(creates.length, 2);
    expect(disposes.length, 1);
    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
    expect(disposes.length, 2);
  }, variant: TargetPlatformVariant.only(TargetPlatform.android));
  testWidgets(
    'unsupported desktop surface renders explanation instead of AndroidView',
    (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: const CodeEditorSurface(generation: 1),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.textContaining('available on Android'), findsOneWidget);
      expect(find.byType(PlatformViewLink), findsNothing);
    },
    variant: TargetPlatformVariant.only(TargetPlatform.linux),
  );
  testWidgets('IME query composition survives matching native echo', (
    tester,
  ) async {
    native = codeFixture(visible: true);
    await app(tester);
    await tester.showKeyboard(input('code-query'));
    const value = TextEditingValue(
      text: 'pin',
      selection: TextSelection.collapsed(offset: 3),
      composing: TextRange(start: 0, end: 3),
    );
    tester.testTextInput.updateEditingValue(value);
    await tester.pump();
    await publish(tester, {
      ...native,
      'search': {
        ...(native['search'] as Map),
        'query': 'pin',
        'queryStart': 3,
        'queryEnd': 3,
      },
    });
    expect(
      tester.widget<TextField>(input('code-query')).controller!.value.composing,
      value.composing,
    );
  });
}
