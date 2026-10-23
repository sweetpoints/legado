import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/embedded/rss_manager/rss_manager_bridge.dart';

import 'rss_manager_fixture.dart';

void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('legado/rss_manager');
  const events = MethodChannel('legado/rss_manager/state');
  setUp(
    () => binding.defaultBinaryMessenger.setMockMethodCallHandler(
      events,
      (_) async => null,
    ),
  );
  tearDown(() {
    binding.defaultBinaryMessenger.setMockMethodCallHandler(events, null);
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });
  test('snapshot validates identity and derives only visible selection', () {
    expect(RssManagerSnapshot.fromJson(state()).selection, ['a' * 64]);
    expect(
      () => RssManagerSnapshot.fromJson({...state(), 'schemaVersion': 2}),
      throwsFormatException,
    );
    expect(
      () => RssManagerSnapshot.fromJson({
        ...state(),
        'rows': [
          {'id': 'not-a-room-id', 'name': 'x', 'enabled': true},
        ],
      }),
      throwsFormatException,
    );
  });
  test('rejected draft blocks queued confirm and retry until an actual matching field ACK', () async {
    final commands = <Map>[];
    var reject = true;
    var revision = 1;
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if (call.method == 'snapshot') return jsonEncode(state());
      final args = call.arguments as Map;
      commands.add(args);
      if (args['op'] == 'draft' && reject) {
        throw PlatformException(code: 'not_editable');
      }
      return jsonEncode(
        state(revision: ++revision, draft: args['value'] as String? ?? ''),
      );
    });
    final bridge = RssManagerBridge();
    final subscription = bridge.snapshots().listen((_) {});
    await Future<void>.delayed(Duration.zero);
    final first = bridge.command('draft', {
      'value': 'new',
      'start': 3,
      'end': 3,
    });
    final failed = expectLater(first, throwsA(isA<PlatformException>()));
    final queued = expectLater(bridge.command('confirm'), throwsStateError);
    await failed;
    await queued;
    await expectLater(bridge.command('retry'), throwsStateError);
    expect(commands.map((c) => c['op']), ['draft']);
    reject = false;
    await bridge.retryField();
    await bridge.command('confirm');
    expect(commands.map((c) => c['op']), ['draft', 'draft', 'confirm']);
    expect(commands.map((c) => c['sequence']), [1, 2, 3]);
    await subscription.cancel();
    await bridge.close();
  });
  test(
    'nonmatching ACK rejects field even if platform returned success',
    () async {
      binding.defaultBinaryMessenger.setMockMethodCallHandler(
        channel,
        (call) async => jsonEncode(state()),
      );
      final bridge = RssManagerBridge();
      final subscription = bridge.snapshots().listen((_) {});
      await Future<void>.delayed(Duration.zero);
      await expectLater(
        bridge.command('query', {'value': 'new', 'start': 3, 'end': 3}),
        throwsStateError,
      );
      await expectLater(
        bridge.command('effect', {'value': 'Export', 'ids': []}),
        throwsStateError,
      );
      await subscription.cancel();
      await bridge.close();
    },
  );
  test('older event cannot rewind current binding revision', () async {
    binding.defaultBinaryMessenger.setMockMethodCallHandler(
      channel,
      (_) async => jsonEncode(state(revision: 2, query: 'latest')),
    );
    final bridge = RssManagerBridge();
    final received = <String>[];
    final subscription = bridge.snapshots().listen(
      (s) => received.add(s.data['query'] as String),
    );
    await Future<void>.delayed(Duration.zero);
    await binding.defaultBinaryMessenger.handlePlatformMessage(
      events.name,
      const StandardMethodCodec().encodeSuccessEnvelope(
        jsonEncode(state(revision: 1, query: 'old')),
      ),
      (_) {},
    );
    await Future<void>.delayed(Duration.zero);
    expect(received, ['latest']);
    await subscription.cancel();
    await bridge.close();
  });
  test('rejected query blocks queued native handoffs and snapshot cannot clear rejection', () async {
    final received = <Map>[];
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if (call.method == 'snapshot') return jsonEncode(state());
      final args = call.arguments as Map;
      received.add(args);
      throw PlatformException(code: 'write_failed');
    });
    final bridge = RssManagerBridge();
    final subscription = bridge.snapshots().listen((_) {});
    await Future<void>.delayed(Duration.zero);
    await expectLater(
      bridge.command('query', {'value': 'pending', 'start': 7, 'end': 7}),
      throwsA(isA<PlatformException>()),
    );
    for (final action in ['ImportLocal', 'Export', 'Share']) {
      await expectLater(
        bridge.command('effect', {'value': action, 'ids': []}),
        throwsStateError,
      );
    }
    await expectLater(bridge.command('copy'), throwsStateError);
    await expectLater(bridge.command('retry'), throwsStateError);
    final nextSubscription = bridge.snapshots().listen((_) {});
    await Future<void>.delayed(Duration.zero);
    await expectLater(bridge.command('confirm'), throwsStateError);
    expect(received.length, 1);
    await nextSubscription.cancel();
    await subscription.cancel();
    await bridge.close();
  });
  test(
    'initial snapshot failure still permits native Back without binding',
    () async {
      const embedded = MethodChannel('legado/embedded_ui');
      var closes = 0;
      binding.defaultBinaryMessenger.setMockMethodCallHandler(
        channel,
        (_) async => throw PlatformException(code: 'storage_error'),
      );
      binding.defaultBinaryMessenger.setMockMethodCallHandler(embedded, (
        call,
      ) async {
        expect(call.method, 'navigation.close');
        closes++;
        return null;
      });
      final bridge = RssManagerBridge();
      final subscription = bridge.snapshots().listen(
        (_) {},
        onError: (Object _) {},
      );
      await Future<void>.delayed(Duration.zero);
      await bridge.command('back');
      expect(closes, 1);
      await subscription.cancel();
      await bridge.close();
      binding.defaultBinaryMessenger.setMockMethodCallHandler(embedded, null);
    },
  );
  test(
    'command ACK projects rows even when it arrives before an older event',
    () async {
      binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
        call,
      ) async {
        if (call.method == 'snapshot') return jsonEncode(state());
        final updated = state(revision: 3, query: 'new')
          ..['rows'] = [
            {
              'id': 'b' * 64,
              'name': 'New rows',
              'group': null,
              'enabled': true,
            },
          ];
        return jsonEncode(updated);
      });
      final bridge = RssManagerBridge();
      final received = <RssManagerSnapshot>[];
      final subscription = bridge.snapshots().listen(received.add);
      await Future<void>.delayed(Duration.zero);
      await bridge.command('query', {'value': 'new', 'start': 3, 'end': 3});
      await binding.defaultBinaryMessenger.handlePlatformMessage(
        events.name,
        const StandardMethodCodec().encodeSuccessEnvelope(
          jsonEncode(state(revision: 2, query: 'old')),
        ),
        (_) {},
      );
      await Future<void>.delayed(Duration.zero);
      expect(received.last.data['query'], 'new');
      expect(received.last.rows.single['name'], 'New rows');
      expect(received.map((s) => s.data['revision']), [1, 3]);
      await subscription.cancel();
      await bridge.close();
    },
  );
}
