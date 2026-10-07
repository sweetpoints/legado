import 'dart:convert';
import 'dart:io';

import 'package:source_engine/source_engine.dart';
import 'package:source_v8/source_v8.dart';
import 'package:test/test.dart';

class _NoHost implements ScriptHost {
  @override
  Future<Object?> call(String method, List<Object?> arguments) =>
      throw UnsupportedError(method);
}

void main() {
  final fixture = jsonDecode(
    File('test/support/jsoup_golden.json').readAsStringSync(),
  ) as Map<String, dynamic>;
  final cases = fixture['cases'] as Map<String, dynamic>;
  late V8Runtime runtime;
  setUp(() => runtime = V8Runtime());
  tearDown(() => runtime.close());
  for (final entry in cases.entries) {
    test('JSoup-derived legacy output: ${entry.key}', () async {
      final results = await RuleEvaluator(runtime)
          .evaluate(entry.key, fixture['html'], ScriptContext(host: _NoHost()));
      expect(results.map(RuleEvaluator.text).toList(), entry.value);
    });
  }
}
