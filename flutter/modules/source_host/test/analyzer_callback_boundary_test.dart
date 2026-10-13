import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_host/main.dart' show createSourceEngine;

class _Callbacks implements ScriptHost {
  final calls = <String>[];
  @override
  Future<Object?> call(String method, List<Object?> args) async {
    calls.add(method);
    if (method == 'analyze.get') return 'A-token';
    throw StateError('Wrong callback route: $method');
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('production auxiliary runtime executes actual Kotlin analyzer proxy against bound callbacks', () async {
    final kotlinRoot = Directory(
      '../../../app/src/main/java/io/legado/app/model',
    ).absolute.path;
    final owner = File('$kotlinRoot/sourceEngine/LegacySourceScriptRunner.kt')
        .readAsStringSync();
    final prelude = RegExp(
      r'internal fun prelude\(library: String\): String =\s*"""([\s\S]*?)"""',
    ).firstMatch(owner)!.group(1)!.replaceAll(r'$library', '');
    final analyzer = File('$kotlinRoot/analyzeRule/AnalyzeRule.kt')
        .readAsStringSync();
    final script = RegExp(r'val script =\s*"""([\s\S]*?)"""\.trimIndent')
        .allMatches(analyzer)
        .last
        .group(1)!;
    final source = SourceDefinition(
      id: 'book:fixture',
      name: 'Fixture',
      baseUrl: Uri.parse('https://fixture.invalid/'),
      metadata: {'legacy': true},
    );
    final callbacks = _Callbacks(),
        engine = createSourceEngine(source, platform: callbacks);
    try {
      expect(
        await engine.evaluateAuxiliary(
          source,
          script,
          prelude: prelude,
          bindings: {
            'taskId': 'task',
            '__analyzeScript': "java.get('saved')",
            '__legacyExtractionPrefix': 'analyze',
            '__legacySourceTag': 'Fixture',
            '__legacySourceKey': 'https://fixture.invalid/',
            'sourceData': <String, Object?>{},
            'result': 'fixture',
            'src': 'fixture',
            'book': {'variable': '{"saved":"A-token"}'},
          },
        ),
        'A-token',
      );
      expect(callbacks.calls, ['analyze.get']);
      for (final body in [
        "__sourceHostSync('analyze.get',['saved'])",
        "java.get('saved')",
        "java.get('saved')",
      ]) {
        expect(
          await engine.evaluateAuxiliary(
            source,
            script,
            prelude: prelude,
            bindings: {
              'taskId': 'task-next',
              '__analyzeScript': body,
              '__legacyExtractionPrefix': 'analyze',
              '__legacySourceTag': 'Fixture',
              '__legacySourceKey': 'https://fixture.invalid/',
              'sourceData': <String, Object?>{},
              'result': 'fixture',
              'src': 'fixture',
              'book': {'variable': '{"saved":"A-token"}'},
            },
          ),
          'A-token',
        );
      }
      expect(callbacks.calls, List.filled(4, 'analyze.get'));
    } finally {
      await engine.close();
    }
  });
}
