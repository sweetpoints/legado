import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:legado_app/l10n/generated/app_localizations.dart';
import 'package:legado_app/embedded/all_bookmarks/all_bookmarks_bridge.dart';
import 'package:legado_app/embedded/all_bookmarks/all_bookmarks_page.dart';

Map<String, Object?> row(String id, {String author = 'Author'}) => {
  'id': id,
  'bookName': 'Book',
  'bookAuthor': author,
  'chapter': 'Chapter $id',
  'original': 'Original $id',
  'content': 'Note $id',
};
Map<String, Object?> fixture({int generation = 1, int revision = 1}) => {
  'generation': generation,
  'revision': revision,
  'loaded': true,
  'exporting': false,
  'pending': false,
  'error': null,
  'scroll': 0,
  'offset': 0,
  'rows': [
    row('9223372036854775806'),
    row('9223372036854775807', author: 'Other'),
  ],
};
void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('legado/all_bookmarks');
  const events = MethodChannel('legado/all_bookmarks/state');
  late Map<String, Object?> native;
  late List<Map> calls;
  Completer<void>? gate;
  bool reject = false;
  setUp(() {
    native = fixture();
    calls = [];
    gate = null;
    reject = false;
    binding.defaultBinaryMessenger.setMockMethodCallHandler(
      events,
      (_) async => null,
    );
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if (call.method == 'snapshot') return native;
      calls.add(call.arguments as Map);
      await gate?.future;
      if (reject) throw PlatformException(code: 'private_error');
      native = {...native, 'revision': (native['revision'] as int) + 1};
      return native;
    });
  });
  tearDown(() {
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
    binding.defaultBinaryMessenger.setMockMethodCallHandler(events, null);
  });
  Future<void> app(
    WidgetTester tester,
    AllBookmarksBridge bridge, {
    String locale = 'en',
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        locale: Locale(locale),
        supportedLocales: AppLocalizations.supportedLocales,
        localizationsDelegates: [
          AppLocalizations.delegate,
          ...GlobalMaterialLocalizations.delegates,
        ],
        home: AllBookmarksPage(bridge: bridge),
      ),
    );
    await tester.pumpAndSettle();
  }

  test('native identity strings preserve full Long and duplicate identities fail closed', () {
    expect(AllBookmarksSnapshot(fixture()).rows.last.id, '9223372036854775807');
    expect(
      () => AllBookmarksSnapshot({
        ...fixture(),
        'rows': [row('1'), row('1')],
      }),
      throwsFormatException,
    );
  });
  test('retired native generation and old revisions cannot replace a current snapshot', () {
    final bridge = AllBookmarksBridge();
    bridge.accept(fixture(revision: 5));
    bridge.accept(fixture(revision: 4));
    expect(bridge.latest!.revision, 5);
    bridge.accept(fixture(generation: 2));
    bridge.accept(fixture(revision: 100));
    expect(bridge.latest!.generation, 2);
    return bridge.close();
  });
  test('queued actions reject a retired reply without dispatching a second operation', () async {
    final bridge = AllBookmarksBridge();
    await bridge.start();
    gate = Completer<void>();
    final first = bridge.command('open', {
      'id': '9223372036854775807',
      'edit': true,
    });
    final firstFailure = expectLater(first, throwsStateError);
    final second = bridge.command('export', {'markdown': false});
    final secondFailure = expectLater(second, throwsStateError);
    await Future<void>.delayed(Duration.zero);
    bridge.accept(fixture(generation: 2));
    gate!.complete();
    await firstFailure;
    await secondFailure;
    expect(calls.length, 1);
    await bridge.close();
  });
  testWidgets(
    'group boundary uses name and author and actual tap sends only exact identity',
    (tester) async {
      final bridge = AllBookmarksBridge();
      await app(tester, bridge);
      expect(find.text('Book(Author)'), findsOneWidget);
      expect(find.text('Book(Other)'), findsOneWidget);
      await tester.tap(
        find.bySemanticsIdentifier('all-bookmarks-row-9223372036854775807'),
      );
      await tester.pumpAndSettle();
      expect(calls.single, {
        'op': 'open',
        'generation': 1,
        'id': '9223372036854775807',
        'edit': false,
      });
    },
  );
  testWidgets(
    'long press edits and Room projection changes reuse the exact keyed row',
    (tester) async {
      final bridge = AllBookmarksBridge();
      await app(tester, bridge);
      final before = tester.element(
        find.byKey(const ValueKey('9223372036854775806')),
      );
      bridge.accept({
        ...fixture(revision: 2),
        'rows': [
          {...row('9223372036854775806'), 'content': 'Fresh Room note'},
        ],
      });
      await tester.pumpAndSettle();
      expect(
        identical(
          before,
          tester.element(find.byKey(const ValueKey('9223372036854775806'))),
        ),
        true,
      );
      expect(find.text('Fresh Room note'), findsOneWidget);
      await tester.longPress(
        find.bySemanticsIdentifier('all-bookmarks-row-9223372036854775806'),
      );
      await tester.pumpAndSettle();
      expect(calls.single['edit'], true);
    },
  );
  testWidgets(
    'JSON and Markdown menus dispatch the native contract format once',
    (tester) async {
      final bridge = AllBookmarksBridge();
      await app(tester, bridge, locale: 'zh');
      expect(find.text('全部书签'), findsOneWidget);
      for (final format in ['json', 'md']) {
        await tester.tap(find.bySemanticsIdentifier('all-bookmarks-menu'));
        await tester.pumpAndSettle();
        await tester.tap(
          find.bySemanticsIdentifier('all-bookmarks-export-$format'),
        );
        await tester.pumpAndSettle();
      }
      expect(calls.map((c) => c['op']), ['export', 'export']);
      expect(calls.map((c) => c['markdown']), [false, true]);
    },
  );
  testWidgets(
    'exporting and outstanding native effects disable row and menu actions',
    (tester) async {
      final bridge = AllBookmarksBridge();
      await app(tester, bridge);
      bridge.accept({...fixture(revision: 2), 'exporting': true});
      await tester.pump(); // Deliver the async broadcast snapshot.
      await tester.pump(); // Render busy controls without settling an indeterminate bar.
      await tester.tap(
        find.bySemanticsIdentifier('all-bookmarks-row-9223372036854775806'),
      );
      expect(calls, isEmpty);
      expect(
        tester
            .widget<PopupMenuButton<bool>>(find.byType(PopupMenuButton<bool>))
            .enabled,
        false,
      );
      bridge.accept({...fixture(revision: 3), 'pending': true});
      await tester.pumpAndSettle();
      await tester.longPress(
        find.bySemanticsIdentifier('all-bookmarks-row-9223372036854775806'),
      );
      expect(calls, isEmpty);
    },
  );
  testWidgets(
    'scroll restores index plus native physical offset and keeps group header pinned',
    (tester) async {
      native = {
        ...fixture(),
        'rows': [for (var i = 0; i < 50; i++) row('$i')],
        'scroll': 4,
        'offset': 17,
      };
      final bridge = AllBookmarksBridge();
      await app(tester, bridge);
      final position = tester
          .state<ScrollableState>(find.byType(Scrollable).first)
          .position;
      expect(
        position.pixels,
        closeTo(36 + 3 * 104 + 17 / tester.view.devicePixelRatio, .01),
      );
      final header = tester.getRect(
        find.bySemanticsIdentifier('all-bookmarks-header-0'),
      );
      final list = tester.getRect(
        find.bySemanticsIdentifier('all-bookmarks-list'),
      );
      expect(header.top, closeTo(list.top, .01));
      await tester.drag(
        find.bySemanticsIdentifier('all-bookmarks-list'),
        const Offset(0, -200),
      );
      await tester.pumpAndSettle();
      expect(calls.where((c) => c['op'] == 'scroll'), isNotEmpty);
      expect(calls.last['offset'], isNonNegative);
    },
  );
  testWidgets(
    'native command failure surfaces a localized error without retrying the action',
    (tester) async {
      final bridge = AllBookmarksBridge();
      await app(tester, bridge);
      reject = true;
      await tester.tap(
        find.bySemanticsIdentifier('all-bookmarks-row-9223372036854775806'),
      );
      await tester.pumpAndSettle();
      expect(calls.length, 1);
      expect(find.text('Unable to load bookmarks. Try again.'), findsOneWidget);
    },
  );

  testWidgets(
    'large text in a narrow native viewport keeps preview rows reachable without overflow',
    (tester) async {
      tester.view.physicalSize = const Size(840, 1200);
      tester.view.devicePixelRatio = 3;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final bridge = AllBookmarksBridge();
      await tester.pumpWidget(
        MaterialApp(
          supportedLocales: AppLocalizations.supportedLocales,
          localizationsDelegates: [
            AppLocalizations.delegate,
            ...GlobalMaterialLocalizations.delegates,
          ],
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context)
                .copyWith(textScaler: TextScaler.linear(2)),
            child: child!,
          ),
          home: AllBookmarksPage(bridge: bridge),
        ),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(
        find
            .bySemanticsIdentifier('all-bookmarks-row-9223372036854775806')
            .hitTestable(),
        findsOneWidget,
      );
      await tester.tap(
        find.bySemanticsIdentifier('all-bookmarks-row-9223372036854775806'),
      );
      await tester.pumpAndSettle();
      expect(calls.single['id'], '9223372036854775806');
      expect(tester.takeException(), isNull);
    },
  );
}
