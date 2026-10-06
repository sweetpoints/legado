import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_host/task_host.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:source_platform/source_platform.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('legado/source_host_platform');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test(
    'concurrent cache callbacks carry caller task and preserve boolean results',
    () async {
      final entered = Completer<void>();
      final release = Completer<void>();
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        final args = call.arguments as Map;
        if (args['taskId'] == 'first') {
          entered.complete();
          await release.future;
          return true;
        }
        return false;
      });
      const platform = SourcePlatform(sourceId: 'source');
      final first = TaskScriptHost(platform, 'first');
      final second = TaskScriptHost(platform, 'second');
      final pending = first.call('batch.cacheContent', [
        {'index': 1},
        'one',
      ]);
      await entered.future;
      expect(
        await second.call('batch.cacheContent', ['chapter-2', 'two']),
        false,
      );
      release.complete();
      expect(await pending, true);
      expect(calls.map((c) => c.method), ['call', 'call']);
      expect(calls.first.arguments, {
        'sourceId': 'source',
        'taskId': 'first',
        'method': 'batch.cacheContent',
        'arguments': [
          {'index': 1},
          'one',
        ],
      });
      expect((calls.last.arguments as Map)['taskId'], 'second');
    },
  );

  test(
    'host failures propagate and malformed cache results cannot imply saved',
    () async {
      final host = TaskScriptHost(
        const SourcePlatform(sourceId: 'source'),
        'task',
      );
      for (final code in ['cancelled', 'stale_edit', 'batch_partial_save']) {
        messenger.setMockMethodCallHandler(
          channel,
          (_) async => throw PlatformException(code: code),
        );
        await expectLater(
          host.call('batch.cacheContent', ['chapter', 'body']),
          throwsA(isA<PlatformException>().having((e) => e.code, 'code', code)),
        );
      }
      messenger.setMockMethodCallHandler(channel, (_) async => null);
      await expectLater(
        host.call('batch.cacheContent', ['chapter', 'body']),
        throwsA(
          isA<EngineException>().having(
            (e) => e.code,
            'code',
            'invalid_host_response',
          ),
        ),
      );
      await expectLater(
        TaskScriptHost(
          const SourcePlatform(sourceId: 'source'),
          null,
        ).call('browser.show', ['https://example.test']),
        throwsA(
          isA<EngineException>().having(
            (e) => e.code,
            'code',
            'invalid_request',
          ),
        ),
      );
    },
  );

  test('legacy browser and batch calls use task RPC with original overload arguments', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return switch ((call.arguments as Map)['method']) {
        'batch.cacheContent' => true,
        'browser.open' => {
          'url': 'https://example.test/result',
          'body': 'verified',
        },
        _ => null,
      };
    });
    final legacy = LegacyScriptHost(
      TaskScriptHost(const SourcePlatform(sourceId: 'source'), 'task'),
    );
    expect(
      await legacy.call('java.cacheContent', [
        {'index': 3},
        'content',
      ]),
      true,
    );
    await legacy.call('java.showBrowser', [
      'https://example.test',
      '<html/>',
      'preload',
      '{}',
    ]);
    await legacy.call('java.startBrowser', [
      'https://example.test',
      'Title',
      null,
    ]);
    final response = await legacy.call('java.startBrowserAwait', [
      'https://example.test',
      'Title',
      false,
      '<html/>',
    ]) as Map;
    expect(response['body'], 'verified');
    expect(response['__legacyResponseKind'], 'str');
    expect(calls.map((c) => (c.arguments as Map)['method']), [
      'batch.cacheContent',
      'browser.show',
      'browser.start',
      'browser.open',
    ]);
    expect((calls.last.arguments as Map)['arguments'], [
      'https://example.test',
      'Title',
      {'refetchAfterSuccess': false, 'html': '<html/>'},
    ]);
    expect(calls.every((c) => (c.arguments as Map)['taskId'] == 'task'), true);
  });
  test(
    'explicit batch ID and external navigation retain complete RPC arguments',
    () async {
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return (call.arguments as Map)['method'] == 'batch.cacheContent'
            ? false
            : null;
      });
      final legacy = LegacyScriptHost(
        TaskScriptHost(const SourcePlatform(sourceId: 'source'), 'task'),
      );
      expect(
        await legacy.call('java.cacheContent', [
          'batch-id',
          {'index': 7},
          'body',
        ]),
        false,
      );
      await legacy.call('java.openUrl', ['https://example.test', 'text/html']);
      await legacy.call('java.openVideoPlayer', [
        'https://example.test/video',
        'Video',
        true,
      ]);
      expect(calls.map((c) => (c.arguments as Map)['method']), [
        'batch.cacheContent',
        'browser.openUrl',
        'browser.video',
      ]);
      expect((calls[0].arguments as Map)['arguments'], [
        'batch-id',
        {'index': 7},
        'body',
      ]);
      expect((calls[1].arguments as Map)['arguments'], [
        'https://example.test',
        'text/html',
      ]);
      expect((calls[2].arguments as Map)['arguments'], [
        'https://example.test/video',
        'Video',
        true,
      ]);
      expect(
        calls.every((c) => (c.arguments as Map)['taskId'] == 'task'),
        true,
      );
      await expectLater(
        legacy.call('java.openVideoPlayer', [
          'https://example.test',
          'Video',
          1,
        ]),
        throwsArgumentError,
      );
      await expectLater(
        legacy.call('java.cacheContent', ['batch-id', 'chapter', null]),
        throwsArgumentError,
      );
      expect(calls, hasLength(3));
    },
  );
  test('application callbacks use the same isolated task channel', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return (call.arguments as Map)['arguments'];
    });
    final task = TaskScriptHost(
      const SourcePlatform(sourceId: 'auxiliary'),
      'local-task',
    );
    for (final method in [
      'replacement.put',
      'replacement.t2s',
      'localBook.putVolume',
      'analyze.getString',
      'crypto.randomInt32',
      'ui.upLoginData',
      'ui.reLoginView',
      'sourceState.getLoginHeader',
      'sourceState.putVariable',
    ]) {
      final args = method == 'ui.upLoginData'
          ? <Object?>[
              {
                'nested': [true, null, 1],
              },
            ]
          : <Object?>['input'];
      expect(await task.call(method, args), args);
      expect(calls.last.arguments, {
        'sourceId': 'auxiliary',
        'taskId': 'local-task',
        'method': method,
        'arguments': args,
      });
    }
  });
}
