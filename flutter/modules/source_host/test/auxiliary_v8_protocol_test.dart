import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:source_host/source_host.dart';

import 'package:source_host/main.dart' show createSourceEngine;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late SourceHost host;
  var sequence = 0;
  setUp(() => host = SourceHost(createSourceEngine));
  tearDown(() => host.close());
  Future<Object?> evaluate(
    String script, {
    String? owner = 'book:source',
    Map<String, Object?> bindings = const {},
    String prelude = '',
    int timeout = 1000,
    String descriptorBaseUrl = 'https://fixture.invalid/',
    Map<String, String> headers = const {'X-Fixture': 'value'},
  }) async {
    final result = await host.handle(
      MethodCall('evaluateAuxiliary', {
        'protocolVersion': 1,
        'taskId': 'aux-${sequence++}',
        'script': script,
        'sourceId': owner,
        'bindings': bindings,
        'prelude': prelude,
        'timeoutMs': timeout,
        'sourceJson': {
          'baseUrl': descriptorBaseUrl,
          'headers': headers,
        },
      }),
    ) as Map;
    return result['value'];
  }

  test(
    'opaque owner retains real JS globals and library initialized once',
    () async {
      const library = 'globalThis.libRuns=(globalThis.libRuns||0)+1;';
      expect(await evaluate('var value=41; value', prelude: library), 41);
      expect(await evaluate('++value', prelude: library), 42);
      expect(await evaluate('libRuns', prelude: library), 1);
      expect(
        await evaluate('typeof value', owner: 'rss:source', prelude: library),
        'undefined',
      );
    },
  );
  test('clearSourceState actually discards the source VM', () async {
    await evaluate('globalThis.saved=42');
    await host.handle(
      const MethodCall('clearSourceState', {
        'protocolVersion': 1,
        'taskId': 'clear',
        'sourceId': 'book:source',
      }),
    );
    expect(await evaluate('typeof saved'), 'undefined');
  });
  test('null owner calls are ephemeral', () async {
    await evaluate('globalThis.privateValue=42', owner: null);
    expect(await evaluate('typeof privateValue', owner: null), 'undefined');
  });
  test('same owner keeps globals when page and source headers change', () async {
    await evaluate(
      'var counter=41; globalThis.saved=99;',
      bindings: {'baseUrl': 'https://fixture.invalid/book/first'},
    );
    expect(
      await evaluate(
        '({counter:++counter,saved:globalThis.saved,baseUrl})',
        bindings: {'baseUrl': 'https://next.invalid/book/second'},
        descriptorBaseUrl: 'https://next.invalid/',
        headers: {'X-Fixture': 'changed'},
      ),
      {
        'counter': 42,
        'saved': 99,
        'baseUrl': 'https://next.invalid/book/second',
      },
    );
  });
  test(
    'current page baseUrl binding is not replaced by source descriptor origin',
    () async {
      expect(
        await evaluate(
          'baseUrl',
          bindings: {'baseUrl': 'https://fixture.invalid/book/page'},
        ),
        'https://fixture.invalid/book/page',
      );
    },
  );
  test(
    'true syntax compilation does not execute and reports original source line',
    () async {
      final success = await host.handle(
        const MethodCall('checkAuxiliarySyntax', {
          'protocolVersion': 1,
          'taskId': 'syntax-success',
          'script': 'while(true){}; java.put("bad", "sideEffect");',
        }),
      );
      expect(success, isNull);
      final error = await host.handle(
        const MethodCall('checkAuxiliarySyntax', {
          'protocolVersion': 1,
          'taskId': 'syntax-error',
          'script': 'var x=1;\nconst broken = ;',
        }),
      ) as Map;
      expect(error['lineNumber'], 2);
      expect(error['columnNumber'], greaterThan(0));
    },
  );
  test('timeout leaves the source global session usable', () async {
    await evaluate('globalThis.saved=42');
    await expectLater(
      evaluate('while(true){}', timeout: 40),
      throwsA(
        isA<PlatformException>().having(
          (e) => e.code,
          'code',
          'script_timeout',
        ),
      ),
    );
    expect(await evaluate('saved'), 42);
  });
  test(
    'JSON bindings and synchronous java utility helper cross native RPC',
    () async {
      expect(
        await evaluate(
          '({number: input.n+1, value: java.md5Encode(input.text)})',
          bindings: {
            'input': {'n': 41, 'text': 'abc'},
          },
        ),
        {'number': 42, 'value': '900150983cd24fb0d6963f7d28e17f72'},
      );
    },
  );
  test('reserved native mode bindings are rejected', () async {
    await expectLater(
      evaluate('42', bindings: {'__sv8Protocol': 1, 'mode': 'syntax'}),
      throwsA(
        isA<PlatformException>().having(
          (e) => e.code,
          'code',
          'invalid_bindings',
        ),
      ),
    );
  });
}
