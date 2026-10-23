import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/embedded/rule_subscriptions/rule_subscription_bridge.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('test/subscriptions');
  late StreamController<Object?> events;
  late RuleSubscriptionBridge bridge;
  late List<Map<Object?, Object?>> commands;
  var revision = 0;
  var sequence = 0;
  var reject = false;
  Map<String, Object?> state({String generation = 'owner', int? order}) => {
    'generation': generation,
    'revision': revision,
    'sequence': order ?? ++sequence,
    'loaded': true,
    'rows': <Object?>[],
    'editor': null,
  };
  setUp(() async {
    events = StreamController<Object?>();
    commands = [];
    revision = 0;
    sequence = 0;
    reject = false;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'snapshot') return state();
          final args = Map<Object?, Object?>.from(call.arguments as Map);
          commands.add(args);
          expect(args['revision'], revision);
          if (reject && args['op'] == 'url') {
            throw PlatformException(code: 'rejected');
          }
          revision++;
          return state();
        });
    bridge = RuleSubscriptionBridge(methods: channel, events: events.stream);
    await bridge.load();
  });
  tearDown(() async {
    await bridge.close();
    await events.close();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });
  test(
    'bootstrap failure still permits native close without an editor snapshot',
    () async {
      const navigation = MethodChannel('test/navigation');
      const failedSnapshot = MethodChannel('test/failed-snapshot');
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(failedSnapshot, (_) async {
            throw PlatformException(code: 'bootstrap_failed');
          });
      final calls = <String>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(navigation, (call) async {
            calls.add(call.method);
            return null;
          });
      final unbootstrapped = RuleSubscriptionBridge(
        methods: failedSnapshot,
        navigation: navigation,
        events: const Stream.empty(),
      );
      try {
        await expectLater(
          unbootstrapped.load(),
          throwsA(isA<PlatformException>()),
        );
        await expectLater(unbootstrapped.command('save'), throwsStateError);
        await unbootstrapped.command('back');
        expect(calls, ['navigation.close']);
        expect(commands, isEmpty);
      } finally {
        await unbootstrapped.close();
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(navigation, null);
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(failedSnapshot, null);
      }
    },
  );
  test('queued fields and save use each acknowledged revision', () async {
    await Future.wait([
      bridge.command('name', {'value': 'A'}),
      bridge.command('url', {'value': 'https://example.test'}),
      bridge.command('save'),
    ]);
    expect(commands.map((e) => e['op']), ['name', 'url', 'save']);
    expect(commands.map((e) => e['revision']), [0, 1, 2]);
  });
  test(
    'rejected field blocks queued save open and move; retry does not clear',
    () async {
      reject = true;
      final field = bridge.command('url', {'value': 'draft'});
      final save = bridge.command('save');
      await expectLater(field, throwsA(isA<PlatformException>()));
      await expectLater(save, throwsStateError);
      for (final op in ['open', 'move', 'retry']) {
        await expectLater(bridge.command(op), throwsStateError);
      }
      expect(commands.length, 1);
      expect(bridge.hasRejectedFields, true);
      reject = false;
      await bridge.command('name', {'value': 'other'});
      expect(bridge.hasRejectedFields, true);
      await bridge.command('url', {'value': 'fixed'});
      expect(bridge.hasRejectedFields, false);
      await bridge.command('save');
    },
  );
  test('explicit cancel discards rejected input', () async {
    reject = true;
    await expectLater(
      bridge.command('url', {'value': 'draft'}),
      throwsA(isA<PlatformException>()),
    );
    await bridge.command('cancel');
    expect(bridge.hasRejectedFields, false);
  });
  test(
    'older same-revision events and foreign generations cannot replace ACK',
    () async {
      await bridge.command('name', {'value': 'new'});
      final acknowledged = bridge.latest;
      events.add(state(order: 1));
      events.add(state(generation: 'retired', order: 100));
      await Future<void>.delayed(Duration.zero);
      expect(bridge.latest, same(acknowledged));
      events.add(state());
      await Future<void>.delayed(Duration.zero);
      expect(
        bridge.latest?['sequence'],
        greaterThan(acknowledged?['sequence'] as int),
      );
    },
  );
}
