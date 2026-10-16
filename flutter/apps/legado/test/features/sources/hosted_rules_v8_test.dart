import 'package:flutter_test/flutter_test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:source_v8/source_v8.dart';

class _Host implements ScriptHost {
  final variables = <String, String>{};
  @override
  Future<Object?> call(String method, List<Object?> args) async {
    if (method == 'java.get') return variables[args.first] ?? '';
    if (method == 'java.put') {
      variables[args.first as String] = args[1] as String;
      return args[1];
    }
    throw EngineException('unsupported_host_api', method);
  }
}

void main() {
  test('hosted template reads entity-scoped variables, retains href identity and first-match Java replacement', () async {
    final runtime = V8Runtime(prelude: legacyScriptPrelude, persistent: true);
    final host = _Host();
    final scope = <String, Object?>{
      'id': 'scope',
      'target': 'book',
      'book': <String, String>{'host': 'https://fixture.invalid'},
      'chapter': <String, String>{},
      'source': <String, String>{},
    };
    final context = ScriptContext(
      host: host,
      variables: {
        'baseUrl': 'https://fixture.invalid/catalogue',
        'legacyVariableScope': scope,
      },
    );
    final source = SourceDefinition(
      id: 'fixture',
      name: 'Fixture',
      baseUrl: Uri.parse('https://fixture.invalid'),
    );
    final evaluator = HostedLegacyRuleEvaluator(
      runtime,
      const PortableLegacyScriptCapabilities({}),
    );
    try {
      final values = await evaluator.evaluate(
        '@get:{host}/{{@href}}',
        '<a href="read-1.html">One</a>',
        context,
        source: source,
        operation: 'toc',
        scalar: true,
      );
      // Template rules receive an element from the original list extraction.
      final marker = await PortableLegacyDomHost.legacyElements(
        'a',
        '<a href="read-1.html">One</a>',
        single: true,
      );
      final node = PortableLegacyDomHost.restoreMarker(marker)!.single;
      expect(
        await evaluator.evaluate(
          '@get:{host}/{{@href}}',
          node,
          context,
          source: source,
          operation: 'toc',
          scalar: true,
        ),
        ['https://fixture.invalid/read-1.html'],
      );
      expect(values, ['https://fixture.invalid/']);
      expect(
        await evaluator.evaluate(
          r'a@href##book-(\d+).*##img-$1.jpg###',
          '<a href="/book-123.html">One</a>',
          context,
          source: source,
          operation: 'explore',
          scalar: true,
        ),
        ['img-123.jpg'],
      );
    } finally {
      await runtime.close();
    }
  });
}
