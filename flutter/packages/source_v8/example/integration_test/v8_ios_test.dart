import 'dart:async';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_v8/source_v8.dart';

class _Host implements ScriptHost {
  @override
  Future<Object?> call(String method, List<Object?> arguments) async {
    await Future<void>.delayed(const Duration(milliseconds: 5));
    return (arguments.first as int) + 1;
  }
}

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets(
    'actual iOS native asset executes jitless V8 with ICU and Temporal',
    (tester) async {
      expect(Platform.isIOS, isTrue);
      final runtime = V8Runtime();
      try {
        expect(runtime.version, '15.4.80.25');
        expect(
          await runtime.evaluate('6 * 7', ScriptContext(host: _Host())),
          42,
        );
        expect(
          await runtime.evaluate(
            '[typeof WebAssembly, typeof Intl, typeof Temporal]',
            ScriptContext(host: _Host()),
          ),
          ['undefined', 'object', 'object'],
        );
      } finally {
        await runtime.close();
      }
    },
  );

  testWidgets(
    'actual iOS asset services asynchronous and synchronous Dart hosts',
    (tester) async {
      final runtime = V8Runtime();
      try {
        expect(
          await runtime.evaluate(
            '__sourceHost("echo", [41])',
            ScriptContext(host: _Host()),
          ),
          42,
        );
        expect(
          await runtime.evaluate(
            '__sourceHostSync("echo", [41])',
            ScriptContext(host: _Host()),
          ),
          42,
        );
      } finally {
        await runtime.close();
      }
    },
  );

  testWidgets('actual iOS persistent context survives cancellation', (
    tester,
  ) async {
    final runtime = V8Runtime(persistent: true);
    try {
      expect(
        await runtime.evaluateAuxiliary(
          '(globalThis.savedValue = 41, 42)',
          ScriptContext(host: _Host()),
        ),
        42,
      );
      final cancellation = CancellationToken();
      final result = runtime.evaluateAuxiliary(
        'while (true) {}',
        ScriptContext(host: _Host()),
        cancellation: cancellation,
      );
      final assertion = expectLater(result, throwsA(isA<EngineException>()));
      await Future<void>.delayed(const Duration(milliseconds: 50));
      cancellation.cancel();
      await assertion;
      expect(
        await runtime.evaluateAuxiliary(
          'savedValue + 1',
          ScriptContext(host: _Host()),
        ),
        42,
      );
    } finally {
      await runtime.close();
    }
  });
}
