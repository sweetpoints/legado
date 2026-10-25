import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:legado_app/embedded/bookmark_editor/bookmark_editor_bridge.dart';
import 'package:legado_app/embedded/bookmark_editor/bookmark_editor_page.dart';
import 'package:legado_app/l10n/generated/app_localizations.dart';

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
  'focus': 'text',
  'chapter': 'Chapter One',
  'canDelete': true,
  'text': 'Original',
  'textStart': 0,
  'textEnd': 0,
  'content': 'Note',
  'contentStart': 0,
  'contentEnd': 0,
};
void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('legado/bookmark_editor');
  const events = MethodChannel('legado/bookmark_editor/state');
  late Map<String, Object?> native;
  late List<Map> calls;
  bool reject = false;
  bool mismatchedAck = false;
  Completer<void>? gate;
  Completer<Object?>? snapshotGate;
  setUp(() {
    native = fixture();
    calls = [];
    reject = false;
    mismatchedAck = false;
    gate = null;
    snapshotGate = null;
    binding.defaultBinaryMessenger.setMockMethodCallHandler(
      events,
      (_) async => null,
    );
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if (call.method == 'snapshot') {
        final pending = snapshotGate;
        return pending == null ? native : await pending.future;
      }
      final args = call.arguments as Map;
      calls.add(args);
      if (args['op'] == 'field') {
        await gate?.future;
        if (reject || native['finished'] == true) {
          throw PlatformException(code: 'field_rejected');
        }
        final key = args['key'] as String;
        native = {
          ...native,
          key: mismatchedAck ? 'wrong ACK' : args['text'],
          '${key}Start': args['start'],
          '${key}End': args['end'],
          'focus': key,
        };
      }
      if (['confirm', 'delete', 'cancel'].contains(args['op'])) {
        native = {...native, 'finished': true, 'canEdit': false};
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
    WidgetTester t, {
    BookmarkEditorBridge? bridge,
    String locale = 'en',
  }) async {
    await t.pumpWidget(
      MaterialApp(
        locale: Locale(locale),
        supportedLocales: AppLocalizations.supportedLocales,
        localizationsDelegates: [
          AppLocalizations.delegate,
          ...GlobalMaterialLocalizations.delegates,
        ],
        home: BookmarkEditorPage(bridge: bridge),
      ),
    );
    await t.pump();
    await t.pump();
  }

  test('raw UTF16 selections are bounded and malformed focus is rejected', () {
    expect(
      () => BookmarkEditorSnapshot({...fixture(), 'textStart': 99}),
      throwsFormatException,
    );
    expect(
      () => BookmarkEditorSnapshot({...fixture(), 'focus': 'unknown'}),
      throwsFormatException,
    );
    final value = BookmarkEditorSnapshot({
      ...fixture(),
      'content': 'A😀BC',
      'contentStart': 5,
      'contentEnd': 1,
    });
    expect(value.start('content'), 5);
    expect(value.end('content'), 1);
  });
  test(
    'generation retirement and revision fences reject late snapshots',
    () async {
      final b = BookmarkEditorBridge();
      b.accept(fixture());
      b.accept({...fixture(revision: 3), 'text': 'latest'});
      b.accept(fixture(revision: 2));
      expect(b.latest!.text('text'), 'latest');
      b.accept(fixture(generation: 2));
      b.accept(fixture(generation: 1, revision: 100));
      expect(b.latest!.generation, 2);
      await b.close();
    },
  );
  test('confirm waits for exact field and raw selection ACK', () async {
    final b = BookmarkEditorBridge();
    b.accept(fixture());
    gate = Completer();
    final field = b.command('field', {
      'key': 'content',
      'text': 'A😀BC',
      'start': 5,
      'end': 1,
    });
    final save = b.command('confirm');
    await Future<void>.delayed(Duration.zero);
    expect(calls.map((c) => c['op']), ['field']);
    gate!.complete();
    await field;
    await save;
    expect(calls.map((c) => c['op']), ['field', 'confirm']);
    expect(native['contentStart'], 5);
    await b.close();
  });
  test('rejected field blocks mutations but cancel immediately discards without confirmation', () async {
    final b = BookmarkEditorBridge();
    b.accept(fixture());
    reject = true;
    await expectLater(
      b.command('field', {
        'key': 'text',
        'text': 'draft',
        'start': 2,
        'end': 1,
      }),
      throwsA(isA<PlatformException>()),
    );
    await expectLater(b.command('confirm'), throwsStateError);
    await expectLater(b.command('delete'), throwsStateError);
    await b.command('cancel');
    expect(calls.map((c) => c['op']), ['field', 'cancel']);
    expect(native['finished'], true);
    await b.close();
  });
  test(
    'cancel bypasses pending field and late ACK cannot reopen finished owner',
    () async {
      final b = BookmarkEditorBridge();
      b.accept(fixture());
      gate = Completer();
      final field = b.command('field', {
        'key': 'text',
        'text': 'draft',
        'start': 2,
        'end': 1,
      });
      final failure = expectLater(field, throwsA(isA<PlatformException>()));
      await Future<void>.delayed(Duration.zero);
      await b.command('cancel');
      expect(native['finished'], true);
      gate!.complete();
      await failure;
      expect(b.latest!.finished, true);
      await b.close();
    },
  );
  test(
    'mismatched ACK blocks delete until the exact field has been repaired',
    () async {
      final b = BookmarkEditorBridge();
      b.accept(fixture());
      mismatchedAck = true;
      await expectLater(
        b.command('field', {
          'key': 'content',
          'text': 'Draft',
          'start': 2,
          'end': 1,
        }),
        throwsStateError,
      );
      await expectLater(b.command('delete'), throwsStateError);
      mismatchedAck = false;
      await b.command('field', {
        'key': 'content',
        'text': 'Draft',
        'start': 2,
        'end': 1,
      });
      await b.command('delete');
      expect(calls.last['op'], 'delete');
      await b.close();
    },
  );
  test('retired generation cannot apply queued mutation after its pending field ACK', () async {
    final b = BookmarkEditorBridge();
    b.accept(fixture());
    gate = Completer();
    final field = b.command('field', {
      'key': 'content',
      'text': 'Draft',
      'start': 2,
      'end': 1,
    });
    final deletion = b.command('delete');
    final fieldFailure = expectLater(field, throwsStateError);
    final deleteFailure = expectLater(deletion, throwsStateError);
    await Future<void>.delayed(Duration.zero);
    b.accept(fixture(generation: 2));
    gate!.complete();
    await fieldFailure;
    await deleteFailure;
    expect(b.latest!.generation, 2);
    expect(calls.map((call) => call['op']), ['field']);
    await b.close();
  });
  testWidgets(
    'normal page shows chapter and two editable fields with native confirm/delete',
    (t) async {
      await app(t);
      expect(find.text('Bookmark'), findsOneWidget);
      expect(find.text('Chapter One'), findsOneWidget);
      expect(find.byType(TextField), findsNWidgets(2));
      await t.enterText(find.byType(TextField).first, 'Edited');
      await t.pump();
      await t.tap(find.text('OK'));
      await t.pump();
      await t.pump();
      expect(native['text'], 'Edited');
      expect(calls.last['op'], 'confirm');
    },
  );
  testWidgets(
    'new bookmark cannot delete and Chinese labels use feature localization',
    (t) async {
      native = {...native, 'canDelete': false};
      await app(t, locale: 'zh');
      expect(find.text('书签'), findsOneWidget);
      expect(find.text('删除'), findsNothing);
      expect(find.text('笔记'), findsOneWidget);
    },
  );
  testWidgets(
    'fresh native draft restores reverse selection and focused note without editing native',
    (t) async {
      native = {
        ...fixture(revision: 4),
        'content': 'A😀BC',
        'contentStart': 5,
        'contentEnd': 1,
        'focus': 'content',
      };
      await app(t);
      await t.pump();
      await t.pump();
      final field = t.widget<TextField>(find.byType(TextField).last);
      expect(
        field.controller!.selection,
        const TextSelection(baseOffset: 5, extentOffset: 1),
      );
      expect(field.focusNode!.hasFocus, true);
      expect(calls.where((c) => c['op'] == 'field'), isEmpty);
    },
  );
  testWidgets(
    'native Back immediately cancels rejected local draft without Keep/Discard',
    (t) async {
      await app(t);
      reject = true;
      await t.enterText(find.byType(TextField).first, 'Unsaved');
      await t.pump();
      await t.binding.handlePopRoute();
      await t.pump();
      await t.pump();
      expect(calls.last['op'], 'cancel');
      expect(find.byType(AlertDialog), findsNothing);
    },
  );
  testWidgets(
    'pending initial snapshot shows progress and never the native Retry identity',
    (t) async {
      snapshotGate = Completer();
      await app(t);
      expect(find.text('Retry'), findsNothing);
      snapshotGate!.complete({
        ...fixture(),
        'loadFailed': true,
        'canEdit': false,
        'error': 'missing ticket',
      });
      await t.pump();
      await t.pump();
      expect(find.text('Retry'), findsOneWidget);
      await t.tap(find.text('Retry'));
      await t.pump();
      await t.pump();
      expect(
        calls.where((call) => call['op'] != 'focus').map((call) => call['op']),
        ['retry'],
      );
    },
  );
  testWidgets(
    'busy commit disables buttons and Back cannot change completed authority',
    (t) async {
      native = {...native, 'saving': true, 'canEdit': false};
      await app(t);
      expect(
        t.widget<TextButton>(find.widgetWithText(TextButton, 'OK')).onPressed,
        isNull,
      );
      expect(
        t
            .widget<TextButton>(find.widgetWithText(TextButton, 'Cancel'))
            .onPressed,
        isNull,
      );
    },
  );
}
