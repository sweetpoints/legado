import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:legado_app/embedded/embedded_source_management.dart';
import 'package:legado_app/embedded/source_management_backend.dart';
import 'package:legado_app/services/book_source_service.dart';

import '../features/sources/source_test_repository.dart';

const _channel = MethodChannel('legado/embedded_ui');
const _events = MethodChannel('legado/embedded_ui/changes');

void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  late List<Map<String, Object?>> rows;
  late List<MethodCall> calls;
  bool listening = false;
  bool failBootstrap = false;
  bool failList = false;
  String? clipboard;
  Completer<void>? pendingImport;
  Future<void> emit() async {
    if (!listening) return;
    await binding.defaultBinaryMessenger.handlePlatformMessage(
      _events.name,
      const StandardMethodCodec().encodeSuccessEnvelope('sources.changed'),
      (_) {},
    );
  }

  setUp(() {
    rows = [
      {
        'bookSourceUrl': 'opaque-original',
        'bookSourceName': 'Original',
        'enabled': true,
        'unknown': {'keep': 7},
      },
    ];
    calls = [];
    listening = false;
    failBootstrap = false;
    failList = false;
    clipboard = null;
    pendingImport = null;
    binding.defaultBinaryMessenger.setMockMethodCallHandler(_events, (
      call,
    ) async {
      listening = call.method == 'listen';
      return null;
    });
    binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData') {
          clipboard = (call.arguments as Map)['text'] as String;
        }
        return null;
      },
    );
    binding.defaultBinaryMessenger.setMockMethodCallHandler(_channel, (
      call,
    ) async {
      calls.add(call);
      final args = call.arguments as Map?;
      switch (call.method) {
        case 'bootstrap':
          if (failBootstrap) throw PlatformException(code: 'bootstrap_failed');
          return {'route': '/sources', 'locale': 'en', 'theme': 'dark'};
        case 'sources.list':
          if (failList) throw PlatformException(code: 'list_failed');
          return jsonEncode(rows);
        case 'sources.import':
          if (pendingImport != null) await pendingImport!.future;
          final value = jsonDecode(args!['json'] as String);
          for (final record in value is List ? value : [value]) {
            rows.add(Map<String, Object?>.from(record as Map));
          }
          await emit();
          return null;
        case 'sources.update':
          final record = Map<String, Object?>.from(
            jsonDecode(args!['json'] as String) as Map,
          );
          final id = args['expectedId'];
          expect(record['bookSourceUrl'], id);
          rows[rows.indexWhere((e) => e['bookSourceUrl'] == id)] = record;
          await emit();
          return null;
        case 'sources.setEnabled':
          rows.firstWhere((e) => e['bookSourceUrl'] == args!['id'])['enabled'] =
              args!['enabled'];
          await emit();
          return null;
        case 'sources.remove':
          rows.removeWhere((e) => e['bookSourceUrl'] == args!['id']);
          await emit();
          return null;
        case 'navigation.close':
          return null;
        default:
          throw StateError('Unexpected embedded method ${call.method}');
      }
    });
  });
  tearDown(() {
    binding.defaultBinaryMessenger.setMockMethodCallHandler(_channel, null);
    binding.defaultBinaryMessenger.setMockMethodCallHandler(_events, null);
    binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      null,
    );
  });
  Future<void> app(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1280, 1200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      const ProviderScope(child: EmbeddedSourceManagementApp()),
    );
    await tester.pumpAndSettle();
  }

  test('native debugger bootstrap is accepted without opening the source repository', () async {
    binding.defaultBinaryMessenger.setMockMethodCallHandler(_channel, (
      call,
    ) async {
      expect(call.method, 'bootstrap');
      return {'route': '/source-debugger', 'locale': 'zh', 'theme': 'system'};
    });
    final container = ProviderContainer();
    addTearDown(container.dispose);
    final config = await container.read(
      embeddedSourceConfigurationProvider.future,
    );
    expect(config.route, '/source-debugger');
    expect(config.locale, 'zh');
    expect(config.theme, 'system');
  });

  test('standalone repository adapter retains complete records and leaves the shared service owner in control', () async {
    final repository = SourceTestRepository();
    final service = BookSourceService(repository);
    final backend = RepositorySourceManagementBackend(repository, service);
    await backend.importJson(
      '{"bookSourceUrl":"opaque-standalone","bookSourceName":"Standalone","unknown":{"nested":[1,null]}}',
    );
    final loaded = (await backend.sources.first).single;
    expect(loaded['unknown'], {
      'nested': [1, null],
    });
    await backend.update(
      jsonEncode({...loaded, 'bookSourceName': 'Edited'}),
      expectedId: 'opaque-standalone',
    );
    await backend.setEnabled({...loaded, 'bookSourceName': 'Edited'}, false);
    expect(
      (jsonDecode(await backend.exportJson()) as List).single['enabled'],
      false,
    );
    await expectLater(
      backend.update(
        '{"bookSourceUrl":"different","bookSourceName":"Rejected"}',
        expectedId: 'opaque-standalone',
      ),
      throwsFormatException,
    );
    await backend.deleteSource('opaque-standalone');
    expect(await repository.sources(), isEmpty);
    await backend.close();
    await service.importJson('{"bookSourceUrl":"owner-still-open"}');
    expect(repository.sourceRows.containsKey('owner-still-open'), true);
    await service.close();
    await repository.close();
    expect(calls, isEmpty);
  });

  testWidgets(
    'same shared management UI closes import/edit/toggle/export/delete through native channel only',
    (tester) async {
      await app(tester);
      expect(find.text('Original'), findsOneWidget);
      expect(listening, true);
      expect(
        Theme.of(tester.element(find.text('Original'))).brightness,
        Brightness.dark,
      );
      await tester.tap(find.text('Import'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      expect(calls.where((e) => e.method == 'navigation.close'), isEmpty);
      await tester.tap(find.text('Import'));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byType(TextField),
        '{"bookSourceUrl":"opaque-new","bookSourceName":"Imported","unknown":{"nested":[1,null]}}',
      );
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      expect(find.text('Imported'), findsOneWidget);
      expect(calls.where((e) => e.method == 'sources.import').length, 1);
      await tester.tap(find.text('Imported'));
      await tester.pumpAndSettle();
      final input = tester
          .widget<TextField>(find.byType(TextField))
          .controller!
          .text;
      final edited = Map<String, Object?>.from(jsonDecode(input) as Map);
      edited['bookSourceName'] = 'Renamed';
      await tester.enterText(find.byType(TextField), jsonEncode(edited));
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      expect(find.text('Renamed'), findsOneWidget);
      expect(rows.last['unknown'], {
        'nested': [1, null],
      });
      expect(
        (calls.firstWhere((e) => e.method == 'sources.update').arguments
            as Map)['expectedId'],
        'opaque-new',
      );
      await tester.tap(find.byType(Switch).last);
      await tester.pumpAndSettle();
      expect(rows.last['enabled'], false);
      await tester.tap(find.byTooltip('Copy all sources as JSON'));
      await tester.pumpAndSettle();
      expect((jsonDecode(clipboard!) as List).last['unknown'], {
        'nested': [1, null],
      });
      await tester.tap(find.byTooltip('Delete source').last);
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Delete'));
      await tester.pumpAndSettle();
      expect(find.text('Renamed'), findsNothing);
      expect(rows.length, 1);
      await tester.tap(find.byType(BackButton));
      await tester.pumpAndSettle();
      expect(calls.where((e) => e.method == 'navigation.close').length, 1);
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
      expect(listening, false);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'bootstrap and list failures have working retries and identity edits never call native update',
    (tester) async {
      failBootstrap = true;
      await app(tester);
      expect(find.text('Retry'), findsOneWidget);
      await tester.tap(find.byType(BackButton));
      await tester.pumpAndSettle();
      expect(calls.where((e) => e.method == 'navigation.close').length, 1);
      failBootstrap = false;
      failList = true;
      await tester.tap(find.text('Retry'));
      await tester.pumpAndSettle();
      expect(find.text('Retry'), findsOneWidget);
      failList = false;
      await tester.tap(find.text('Retry'));
      await tester.pumpAndSettle();
      expect(find.text('Original'), findsOneWidget);
      await tester.tap(find.text('Original'));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byType(TextField),
        '{"bookSourceUrl":"changed","bookSourceName":"Invalid identity"}',
      );
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      expect(find.textContaining('Source identity cannot'), findsOneWidget);
      expect(calls.where((e) => e.method == 'sources.update'), isEmpty);
      expect(rows.single['bookSourceUrl'], 'opaque-original');
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
    },
  );

  for (final lateError in [false, true]) {
    testWidgets(
      'system back during pending save ignores late ${lateError ? 'failure' : 'success'} without popping the source page',
      (tester) async {
        await app(tester);
        pendingImport = Completer<void>();
        await tester.tap(find.text('Import'));
        await tester.pumpAndSettle();
        await tester.enterText(
          find.byType(TextField),
          '{"bookSourceUrl":"late-source","bookSourceName":"Late source"}',
        );
        await tester.tap(find.text('Save'));
        await tester.pump();
        await tester.binding.handlePopRoute();
        await tester.pumpAndSettle();
        expect(find.byType(TextField), findsNothing);
        if (lateError) {
          pendingImport!.completeError(PlatformException(code: 'late_failure'));
        } else {
          pendingImport!.complete();
        }
        await tester.pumpAndSettle();
        expect(find.text('Original'), findsOneWidget);
        expect(calls.where((e) => e.method == 'navigation.close'), isEmpty);
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox());
        await tester.pumpAndSettle();
      },
    );
  }

  test('change arriving while first native snapshot is pending triggers a serialized fresh snapshot', () async {
    final pending = Completer<String>();
    final started = Completer<void>();
    var listCalls = 0;
    binding.defaultBinaryMessenger.setMockMethodCallHandler(_channel, (
      call,
    ) async {
      expect(call.method, 'sources.list');
      listCalls++;
      if (listCalls == 1) {
        started.complete();
        return pending.future;
      }
      return jsonEncode(rows);
    });
    final backend = MethodChannelSourceManagementBackend();
    final updated = Completer<void>();
    final snapshots = <List<Map<String, Object?>>>[];
    final subscription = backend.sources.listen((value) {
      snapshots.add(value);
      if (value.single['bookSourceName'] == 'Newer' && !updated.isCompleted) {
        updated.complete();
      }
    });
    await started.future;
    rows.single['bookSourceName'] = 'Newer';
    await emit();
    await emit();
    pending.complete(
      '[{"bookSourceUrl":"opaque-original","bookSourceName":"Stale"}]',
    );
    await updated.future;
    expect(listCalls, 2);
    expect(snapshots.first.single['bookSourceName'], 'Stale');
    expect(snapshots.last.single['bookSourceName'], 'Newer');
    await subscription.cancel();
    await backend.close();
    expect(listening, false);
  });

  test('closing an embedded backend cancels its event channel and ignores a late snapshot', () async {
    final pending = Completer<String>();
    final started = Completer<void>();
    binding.defaultBinaryMessenger.setMockMethodCallHandler(_channel, (
      call,
    ) async {
      started.complete();
      return pending.future;
    });
    final backend = MethodChannelSourceManagementBackend();
    final values = <List<Map<String, Object?>>>[];
    final done = Completer<void>();
    backend.sources.listen(values.add, onDone: done.complete);
    await started.future;
    await backend.close();
    await done.future;
    pending.complete(jsonEncode(rows));
    await Future<void>.delayed(Duration.zero);
    expect(values, isEmpty);
    expect(listening, false);
  });

  testWidgets(
    'native source with malformed rules remains visible and exportable for repair',
    (tester) async {
      rows = [
        {
          'bookSourceUrl': 'repair-native',
          'bookSourceName': 'Needs repair',
          'ruleSearch': 42,
          'ruleContent': {
            'content': {'invalid': true},
          },
          'unknown': [1, null],
        },
      ];
      await app(tester);
      expect(find.text('Needs repair'), findsOneWidget);
      expect(find.text('repair-native'), findsOneWidget);
      await tester.tap(find.byTooltip('Copy all sources as JSON'));
      await tester.pumpAndSettle();
      expect(jsonDecode(clipboard!), rows);
      expect(
        calls.every((e) => ['bootstrap', 'sources.list'].contains(e.method)),
        true,
      );
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    },
  );

  test('554 source list above mutation limit loads and exports every unmodified record', () async {
    rows = [
      for (var i = 0; i < 554; i++)
        {
          'bookSourceUrl': 'opaque-realistic-$i',
          'bookSourceName': 'Source $i',
          'bookSourceComment': '保留原书源注释' * 600,
          'unknown': {
            'nested': [i, null, '原始字段😀'],
          },
        },
    ];
    final nativeJson = jsonEncode(rows);
    expect(
      utf8.encode(nativeJson).length,
      greaterThan(MethodChannelSourceManagementBackend.maxJsonBytes),
    );
    expect(
      utf8.encode(nativeJson).length,
      lessThan(MethodChannelSourceManagementBackend.maxListJsonBytes),
    );
    final backend = MethodChannelSourceManagementBackend();
    final listed = await backend.sources.first;
    expect(listed.length, 554);
    expect(listed, rows);
    expect(jsonDecode(await backend.exportJson()), rows);
    await expectLater(backend.importJson(nativeJson), throwsFormatException);
    await expectLater(
      backend.update(nativeJson, expectedId: 'opaque-realistic-0'),
      throwsFormatException,
    );
    expect(calls.every((e) => e.method == 'sources.list'), true);
    expect(jsonEncode(rows), nativeJson);
    await backend.close();
  });

  test('native list and export reject above 16 MiB or 10000 sources without truncation or mutation', () async {
    final backend = MethodChannelSourceManagementBackend();
    final overBytes =
        '[{"bookSourceUrl":"large","bookSourceComment":"${'x' * MethodChannelSourceManagementBackend.maxListJsonBytes}"}]';
    final overCount = jsonEncode([
      for (
        var i = 0;
        i <= MethodChannelSourceManagementBackend.maxListedSources;
        i++
      )
        {'bookSourceUrl': 'source-$i'},
    ]);
    for (final response in [overBytes, overCount]) {
      binding.defaultBinaryMessenger.setMockMethodCallHandler(_channel, (
        call,
      ) async {
        calls.add(call);
        expect(call.method, 'sources.list');
        return response;
      });
      final expectedCode = identical(response, overBytes)
          ? 'source_json_too_large'
          : 'too_many_sources';
      final matcher = throwsA(
        isA<FormatException>().having((e) => e.message, 'code', expectedCode),
      );
      await expectLater(backend.sources.first, matcher);
      await expectLater(backend.exportJson(), matcher);
    }
    expect(rows.single['bookSourceName'], 'Original');
    expect(calls.length, 4);
    await backend.close();
  });

  test(
    'embedded payload bounds are enforced before sending native mutation',
    () async {
      final backend = MethodChannelSourceManagementBackend();
      await expectLater(
        backend.importJson(
          'x' * (MethodChannelSourceManagementBackend.maxJsonBytes + 1),
        ),
        throwsFormatException,
      );
      final many = [
        for (
          var i = 0;
          i <= MethodChannelSourceManagementBackend.maxSources;
          i++
        )
          {'bookSourceUrl': 'id-$i'},
      ];
      await expectLater(
        backend.importJson(jsonEncode(many)),
        throwsFormatException,
      );
      expect(calls, isEmpty);
      await backend.close();
    },
  );
}
