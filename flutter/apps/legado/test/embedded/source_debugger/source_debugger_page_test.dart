import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter/rendering.dart';
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
      if (args['op'] == 'query') {
        native = {
          ...native,
          'revision': (native['revision'] as int) + 1,
          'query': args['text'],
          'queryStart': args['start'],
          'queryEnd': args['end'],
        };
        return native;
      }
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

  Future<void> emit(
    WidgetTester t,
    Map<String, Object?> value, {
    bool stale = false,
  }) async {
    if (!stale) {
      value = {
        ...value,
        'revision': value['revision'] == native['revision']
            ? (native['revision'] as int) + 1
            : value['revision'],
      };
      native = value;
    }
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
      final text = t.widget<Text>(
        find.descendant(
          of: tag('source-debug-output'),
          matching: find.byType(Text),
        ),
      );
      final spans = (text.textSpan! as TextSpan).children!
          .whereType<TextSpan>()
          .toList();
      final links = spans.where((s) => s.recognizer != null).toList();
      expect(links.length, 1);
      expect(links.single.text, 'https://example.org/path');
      final paragraph = t.renderObject<RenderParagraph>(
        find.descendant(
          of: tag('source-debug-output'),
          matching: find.byType(RichText),
        ),
      );
      final linkRect = paragraph
          .getBoxesForSelection(
            const TextSelection(baseOffset: 0, extentOffset: 24),
          )
          .first
          .toRect();
      await t.tapAt(paragraph.localToGlobal(linkRect.center));
      await t.pumpAndSettle();
      expect(commands.last['op'], 'openUrl');
      expect(commands.last['url'], 'https://example.org/path');
      await t.longPress(
        find.descendant(
          of: tag('source-debug-output'),
          matching: find.byType(Text),
        ),
      );
      await t.pumpAndSettle();
      expect(find.byType(SelectionArea), findsOneWidget);
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
    await emit(t, {
      ...native,
      'revision': 1,
      'queryStart': 5,
      'queryEnd': 5,
    }, stale: true);
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
      final editable = t
          .state<SelectionAreaState>(find.byType(SelectionArea))
          .selectableRegion;
      editable.selectAll(SelectionChangedCause.toolbar);
      await t.pumpAndSettle();
      editable.contextMenuButtonItems
          .firstWhere((item) => item.type == ContextMenuButtonType.copy)
          .onPressed!();
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
    'blurred pending input survives an old echo then accepts later native prefix',
    (t) async {
      await app(t);
      final barrier = Completer<void>();
      handler = (a) async {
        if (a['op'] == 'query') await barrier.future;
      };
      await t.enterText(tag('source-debug-query'), 'pending query');
      await t.tap(tag('source-debug-run'));
      await t.pump();
      final field = t.widget<TextField>(
        find.descendant(
          of: tag('source-debug-query'),
          matching: find.byType(TextField),
        ),
      );
      expect(field.focusNode!.hasFocus, false);
      await emit(t, {
        ...native,
        'query': 'old',
        'queryStart': 3,
        'queryEnd': 3,
        'help': false,
      });
      expect(field.controller!.text, 'pending query');
      barrier.complete();
      await t.pumpAndSettle();
      await emit(t, {
        ...native,
        'query': 'pending query',
        'queryStart': 13,
        'queryEnd': 13,
      });
      expect(field.controller!.text, 'pending query');
      await emit(t, {
        ...native,
        'query': '++pending query',
        'queryStart': 15,
        'queryEnd': 15,
      });
      expect(field.controller!.text, '++pending query');
      expect(
        field.controller!.selection,
        const TextSelection.collapsed(offset: 15),
      );
    },
  );
  testWidgets(
    'rejected latest query rolls back, blocks queued run, then corrected input recovers',
    (t) async {
      native = {...native, 'query': 'saved', 'queryStart': 5, 'queryEnd': 5};
      await app(t);
      final barrier = Completer<void>();
      handler = (a) async {
        if (a['op'] == 'query') {
          await barrier.future;
          throw PlatformException(code: 'invalid_request');
        }
      };
      await t.enterText(tag('source-debug-query'), 'rejected');
      await t.tap(tag('source-debug-run'));
      await t.pump();
      barrier.complete();
      await t.pumpAndSettle();
      final field = t.widget<TextField>(
        find.descendant(
          of: tag('source-debug-query'),
          matching: find.byType(TextField),
        ),
      );
      expect(field.controller!.text, 'saved');
      expect(commands.where((c) => c['op'] == 'run'), isEmpty);
      await emit(t, {
        ...native,
        'query': 'native',
        'queryStart': 6,
        'queryEnd': 6,
      });
      expect(field.controller!.text, 'native');
      handler = null;
      await t.enterText(tag('source-debug-query'), 'corrected');
      await t.tap(tag('source-debug-run'));
      await t.pumpAndSettle();
      expect(commands.last['op'], 'run');
    },
  );
  testWidgets(
    'IME composing-only changes do not leave pending input blocking native prefix',
    (t) async {
      native = {...native, 'query': 'abc', 'queryStart': 3, 'queryEnd': 3};
      await app(t);
      final field = t.widget<TextField>(
        find.descendant(
          of: tag('source-debug-query'),
          matching: find.byType(TextField),
        ),
      );
      field.controller!.value = field.controller!.value.copyWith(
        composing: const TextRange(start: 0, end: 3),
      );
      field.controller!.clearComposing();
      await t.pumpAndSettle();
      expect(commands.where((c) => c['op'] == 'query'), isEmpty);
      await t.tap(tag('source-debug-prefix-plus'));
      await t.pumpAndSettle();
      expect(commands.last['op'], 'prefix');
      await emit(t, {
        ...native,
        'query': '++abc',
        'queryStart': 5,
        'queryEnd': 5,
        'help': false,
      });
      expect(field.controller!.text, '++abc');
    },
  );
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
  testWidgets(
    'conflated prefix event is applied when its older query acknowledgement arrives',
    (t) async {
      await app(t);
      final ack = Completer<Object?>();
      binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
        call,
      ) async {
        if (call.method == 'snapshot') return native;
        final args = call.arguments as Map;
        commands.add(args);
        if (args['op'] == 'query') return ack.future;
        return null;
      });
      await t.enterText(tag('source-debug-query'), 'abc');
      await t.pump();
      final queryAck = {
        ...native,
        'revision': 2,
        'query': 'abc',
        'queryStart': 3,
        'queryEnd': 3,
      };
      // No intermediate query echo: StateFlow has conflated it with native prefix.
      await emit(t, {
        ...native,
        'revision': 3,
        'query': '++abc',
        'queryStart': 5,
        'queryEnd': 5,
        'help': false,
      });
      final field = t.widget<TextField>(
        find.descendant(
          of: tag('source-debug-query'),
          matching: find.byType(TextField),
        ),
      );
      expect(field.controller!.text, 'abc');
      ack.complete(queryAck);
      await t.pumpAndSettle();
      expect(field.controller!.text, '++abc');
      expect(
        field.controller!.selection,
        const TextSelection.collapsed(offset: 5),
      );
      await emit(t, {...queryAck, 'revision': 2}, stale: true);
      expect(field.controller!.text, '++abc');
    },
  );
  testWidgets('query acknowledgement clears pending without an event echo', (
    t,
  ) async {
    await app(t);
    final ack = Completer<Object?>();
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if (call.method == 'snapshot') return native;
      final args = call.arguments as Map;
      commands.add(args);
      if (args['op'] == 'query') return ack.future;
      return null;
    });
    await t.enterText(tag('source-debug-query'), 'abc');
    await t.pump();
    // Native accepted the query without publishing any intermediate event.
    native = {
      ...native,
      'revision': 2,
      'query': 'abc',
      'queryStart': 3,
      'queryEnd': 3,
    };
    ack.complete(native);
    await t.pumpAndSettle();
    await emit(t, {
      ...native,
      'query': 'Category::https://example.org',
      'queryStart': 28,
      'queryEnd': 28,
    });
    final field = t.widget<TextField>(
      find.descendant(
        of: tag('source-debug-query'),
        matching: find.byType(TextField),
      ),
    );
    expect(field.controller!.text, 'Category::https://example.org');
  });
  testWidgets(
    'updated rich log exposes each real text fragment and link without duplicate full labels',
    (t) async {
      final semantics = t.ensureSemantics();
      try {
        native = {
          ...native,
          'help': false,
          'output': 'https://example.org/start first',
        };
        await app(t);
        const log =
            'Header https://example.org/info\n<div class="body">Fixture body</div> tail';
        await emit(t, {...native, 'output': log});
        final fragments = <String>[];
        void collect(SemanticsNode node) {
          final data = node.getSemanticsData();
          if (data.label.isNotEmpty) fragments.add(data.label);
          if (data.value.isNotEmpty) fragments.add(data.value);
          node.visitChildren((child) {
            collect(child);
            return true;
          });
        }

        collect(t.getSemantics(tag('source-debug-output')));
        final accessible = fragments.join();
        expect(accessible, contains('Fixture body'));
        expect('Fixture body'.allMatches(accessible).length, 1);
        expect(accessible, contains('https://example.org/info'));
        expect(accessible, contains('Header'));
        expect(accessible, contains('tail'));
        expect(fragments.where((value) => value == log), isEmpty);
      } finally {
        semantics.dispose();
      }
    },
  );
}
