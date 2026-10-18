import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';
import 'package:legado_app/embedded/source_debugger/source_debugger_page.dart';
import 'package:legado_app/l10n/generated/app_localizations.dart';

import 'debugger_fixture.dart';

void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('legado/source_debugger');
  const events = MethodChannel('legado/source_debugger/state');
  late Map<String, Object?> native;
  late List<Map> commands;
  Future<void> Function(Map)? handler;
  setUp(() {
    native = debuggerFixture();
    commands = [];
    handler = null;
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
      await handler?.call(args);
      return null;
    });
  });
  tearDown(() {
    binding.defaultBinaryMessenger.setMockMethodCallHandler(events, null);
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });
  Finder tag(String id) => find.byKey(ValueKey(id));
  Future<void> app(
    WidgetTester t, {
    String locale = 'en',
    Size size = const Size(900, 1000),
  }) async {
    t.view.physicalSize = size;
    t.view.devicePixelRatio = 1;
    addTearDown(t.view.resetPhysicalSize);
    addTearDown(t.view.resetDevicePixelRatio);
    await t.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          restorationScopeId: 'test',
          locale: Locale(locale),
          localizationsDelegates: [
            AppLocalizations.delegate,
            ...GlobalMaterialLocalizations.delegates,
          ],
          supportedLocales: AppLocalizations.supportedLocales,
          theme: ThemeData(
            colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
          ),
          home: const SourceDebuggerPage(),
        ),
      ),
    );
    if (native['loading'] == true || native['running'] == true) {
      await t.pump();
      await t.pump(const Duration(milliseconds: 300));
    } else {
      await t.pumpAndSettle();
    }
  }

  Future<void> emit(WidgetTester t, Map<String, Object?> value) async {
    native = value;
    await binding.defaultBinaryMessenger.handlePlatformMessage(
      events.name,
      const StandardMethodCodec().encodeSuccessEnvelope(value),
      (_) {},
    );
    await t.pumpAndSettle();
  }

  testWidgets(
    'query selection precedes run and no query payload is sent in run',
    (t) async {
      await app(t);
      await t.enterText(tag('source-debug-query'), '😀query');
      await t.tap(tag('source-debug-run'));
      await t.pumpAndSettle();
      final query = commands.lastWhere((c) => c['op'] == 'query');
      expect(query['text'], '😀query');
      expect(query['start'], 7);
      expect(commands.last['op'], 'run');
      expect(commands.last.containsKey('query'), false);
    },
  );
  testWidgets('late input echoes cannot overwrite text currently being typed', (
    t,
  ) async {
    await app(t);
    final barrier = Completer<void>();
    handler = (a) async {
      if (a['op'] == 'query') await barrier.future;
    };
    await t.enterText(tag('source-debug-query'), 'new query');
    await t.pump();
    await emit(t, {...native, 'query': 'old', 'queryStart': 3, 'queryEnd': 3});
    expect(
      t
          .widget<TextField>(
            find.descendant(
              of: tag('source-debug-query'),
              matching: find.byType(TextField),
            ),
          )
          .controller!
          .text,
      'new query',
    );
    barrier.complete();
    await t.pumpAndSettle();
  });
  testWidgets('book menu retains all HTML stages even with no HTML available', (
    t,
  ) async {
    await app(t);
    for (final stage in ['search', 'book', 'toc', 'content']) {
      await t.tap(tag('source-debug-menu'));
      await t.pumpAndSettle();
      await t.tap(tag('source-debug-html-$stage'));
      await t.pumpAndSettle();
      expect(commands.last, containsPair('stage', stage));
    }
    await t.tap(tag('source-debug-menu'));
    await t.pumpAndSettle();
    await t.tap(tag('source-debug-refresh'));
    await t.pumpAndSettle();
    expect(commands.last['op'], 'refreshExplore');
  });
  testWidgets('rss menu contains only list and content, and no scan', (
    t,
  ) async {
    native = debuggerFixture(kind: 'rss');
    await app(t, locale: 'zh');
    expect(find.text('调试源'), findsOneWidget);
    expect(tag('source-debug-scan'), findsNothing);
    await t.tap(tag('source-debug-menu'));
    await t.pumpAndSettle();
    expect(tag('source-debug-html-list'), findsOneWidget);
    expect(tag('source-debug-html-content'), findsOneWidget);
    expect(tag('source-debug-html-book'), findsNothing);
  });
  testWidgets(
    'long press categories opens chooser and disabled rows cannot run',
    (t) async {
      await app(t);
      await t.longPress(tag('source-debug-category'));
      await t.pumpAndSettle();
      final disabled = t.widget<ListTile>(
        find.descendant(
          of: tag('source-debug-sort-2'),
          matching: find.byType(ListTile),
        ),
      );
      expect(disabled.enabled, false);
      await t.tap(tag('source-debug-sort-1'));
      await t.pumpAndSettle();
      expect(commands.last['op'], 'sort');
      expect(commands.last['index'], 1);
    },
  );
  testWidgets(
    'category chooser survives restoration and Back dismisses it first',
    (t) async {
      await app(t);
      await t.tap(tag('source-debug-sorts'));
      await t.pumpAndSettle();
      await t.restartAndRestore();
      await t.pumpAndSettle();
      expect(tag('source-debug-sort-1'), findsOneWidget);
      await t.binding.handlePopRoute();
      await t.pumpAndSettle();
      expect(tag('source-debug-sort-1'), findsNothing);
      expect(commands.where((c) => c['op'] == 'close'), isEmpty);
      await t.binding.handlePopRoute();
      await t.pumpAndSettle();
      expect(commands.where((c) => c['op'] == 'close').length, 1);
    },
  );
  testWidgets(
    'output remains selectable and links exclude email-like references',
    (t) async {
      native = {
        ...native,
        'help': false,
        'output': 'https://example.org/path @ignored.example.org ordinary text',
      };
      await app(t);
      final text = t.widget<SelectableText>(find.byType(SelectableText));
      final spans = text.textSpan!.children!.whereType<TextSpan>().toList();
      final links = spans.where((s) => s.recognizer != null).toList();
      expect(links.length, 1);
      expect(links.single.text, 'https://example.org/path');
      // Invoke the same recognizer callback used by the displayed selectable link.
      (links.single.recognizer as dynamic).onTap();
      await t.pumpAndSettle();
      expect(commands.last['op'], 'openUrl');
      expect(commands.last['url'], 'https://example.org/path');
      await t.longPress(find.byType(SelectableText));
      await t.pumpAndSettle();
      expect(find.byType(SelectableText), findsOneWidget);
    },
  );
  testWidgets(
    'busy warning and native retry permission are respected on small viewport',
    (t) async {
      native = {
        ...native,
        'loaded': false,
        'error': null,
        'issue': 'busy',
        'canRetry': true,
      };
      await app(t, size: const Size(320, 640));
      expect(
        find.text('Debug channel is busy. Try again later.'),
        findsOneWidget,
      );
      await t.tap(tag('source-debug-retry'));
      await t.pumpAndSettle();
      expect(commands.last['op'], 'retry');
      expect(t.takeException(), null);
    },
  );
  testWidgets(
    'loaded help focuses query, short prefix keeps IME and native echo',
    (t) async {
      native = {...native, 'loaded': false, 'loading': true};
      await app(t);
      TextField field() => t.widget<TextField>(
        find.descendant(
          of: tag('source-debug-query'),
          matching: find.byType(TextField),
        ),
      );
      expect(field().focusNode!.hasFocus, false);
      await emit(t, {...native, 'loaded': true, 'loading': false});
      expect(field().focusNode!.hasFocus, true);
      await t.ensureVisible(tag('source-debug-prefix-plus'));
      await t.tap(tag('source-debug-prefix-plus'));
      await t.pumpAndSettle();
      expect(commands.last['op'], 'prefix');
      expect(field().focusNode!.hasFocus, true);
      await emit(t, {...native, 'query': '++', 'queryStart': 2, 'queryEnd': 2});
      expect(field().controller!.text, '++');
      expect(field().focusNode!.hasFocus, true);
      expect(commands.where((c) => c['op'] == 'run'), isEmpty);
    },
  );
  testWidgets('old selection echoes do not undo a new UTF16 selection', (
    t,
  ) async {
    native = {...native, 'query': '😀abc', 'queryStart': 5, 'queryEnd': 5};
    await app(t);
    final field = t.widget<TextField>(
      find.descendant(
        of: tag('source-debug-query'),
        matching: find.byType(TextField),
      ),
    );
    field.controller!.selection = const TextSelection(
      baseOffset: 2,
      extentOffset: 4,
    );
    await t.pumpAndSettle();
    expect(commands.last['start'], 2);
    expect(commands.last['end'], 4);
    await emit(t, {...native, 'queryStart': 5, 'queryEnd': 5});
    expect(
      field.controller!.selection,
      const TextSelection(baseOffset: 2, extentOffset: 4),
    );
  });
  testWidgets(
    'selectable URL log copies the full selected text without opening a link',
    (t) async {
      const log = 'https://example.org/path ordinary text';
      native = {...native, 'help': false, 'output': log};
      await app(t);
      String? clipboard;
      binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (call) async {
          if (call.method == 'Clipboard.setData') {
            clipboard = (call.arguments as Map)['text'] as String;
          }
          if (call.method == 'Clipboard.getData') return {'text': clipboard};
          return null;
        },
      );
      addTearDown(
        () => binding.defaultBinaryMessenger.setMockMethodCallHandler(
          SystemChannels.platform,
          null,
        ),
      );
      final selectable = find.descendant(
        of: find.byType(SelectableText),
        matching: find.byType(EditableText),
      );
      final editable = t.state<EditableTextState>(selectable);
      editable.selectAll(SelectionChangedCause.toolbar);
      await t.pumpAndSettle();
      editable.copySelection(SelectionChangedCause.toolbar);
      await t.pumpAndSettle();
      expect(clipboard, log);
      expect(commands.where((c) => c['op'] == 'openUrl'), isEmpty);
    },
  );
  testWidgets(
    'reverse UTF16 selection retains anchor and extent through commands and echoes',
    (t) async {
      native = {...native, 'query': '😀abc', 'queryStart': 4, 'queryEnd': 2};
      await app(t);
      final field = t.widget<TextField>(
        find.descendant(
          of: tag('source-debug-query'),
          matching: find.byType(TextField),
        ),
      );
      expect(
        field.controller!.selection,
        const TextSelection(baseOffset: 4, extentOffset: 2),
      );
      field.controller!.selection = const TextSelection(
        baseOffset: 5,
        extentOffset: 2,
      );
      await t.pumpAndSettle();
      expect(commands.last['start'], 5);
      expect(commands.last['end'], 2);
      await emit(t, {...native, 'queryStart': 5, 'queryEnd': 2});
      expect(
        field.controller!.selection,
        const TextSelection(baseOffset: 5, extentOffset: 2),
      );
    },
  );
  testWidgets('long legacy queries are not truncated by Flutter', (t) async {
    final text = 'q' * 65537;
    await app(t);
    await t.enterText(tag('source-debug-query'), text);
    await t.pumpAndSettle();
    expect(commands.lastWhere((c) => c['op'] == 'query')['text'], text);
    await emit(t, {
      ...native,
      'query': text,
      'queryStart': text.length,
      'queryEnd': text.length,
    });
    final field = t.widget<TextField>(
      find.descendant(
        of: tag('source-debug-query'),
        matching: find.byType(TextField),
      ),
    );
    expect(field.controller!.text, text);
  });
  testWidgets(
    'book examples preserve prefixes, detail and configured keyword',
    (t) async {
      native = {...native, 'keyword': 'configured'};
      await app(t);
      await t.tap(tag('source-debug-example-my'));
      await t.pumpAndSettle();
      expect(commands.last['op'], 'run');
      expect(
        commands.where((c) => c['op'] == 'query').last['text'],
        'configured',
      );
      await t.tap(tag('source-debug-prefix-plus'));
      await t.pumpAndSettle();
      expect(commands.last['value'], '++');
      await t.tap(tag('source-debug-prefix-minus'));
      await t.pumpAndSettle();
      expect(commands.last['value'], '--');
      await t.tap(tag('source-debug-detail'));
      await t.pumpAndSettle();
      expect(commands.last['op'], 'detail');
    },
  );
}
