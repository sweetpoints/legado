import 'dart:convert';
import 'dart:io';

import 'package:source_engine/source_engine.dart';
import 'package:test/test.dart';

import 'engine_test.dart' show NoHost, NoScripts;

class _FixtureNetwork extends NetworkClient {
  _FixtureNetwork(this.html);
  final String html;
  @override
  Future<NetworkResponse> request(
    Uri uri, {
    String method = 'GET',
    Map<String, String> headers = const {},
    String? body,
    String? charset,
    Duration timeout = const Duration(seconds: 30),
    CancellationToken? cancellation,
    int maxRedirects = 5,
    bool followRedirects = true,
  }) async => NetworkResponse(uri, 200, {}, html);
}

void main() {
  // JVM-generated truth, not outputs obtained from the implementation under test.
  final fixtures = jsonDecode(
    File('test/fixtures/legacy_literal_replacements.json').readAsStringSync(),
  ) as List;
  for (final raw in fixtures) {
    final fixture = Map<String, Object?>.from(raw as Map);
    test('literal replacement matches old JVM scalar/list: ${fixture['name']}', () async {
      final evaluator = RuleEvaluator(NoScripts());
      final context = ScriptContext(host: NoHost());
      final rule =
          '@legacy:tag.h2@text##${fixture['pattern']}##${fixture['replacement']}';
      expect(
        await evaluator.evaluate(
          '@legacy:tag.h2@text',
          fixture['html'],
          context,
        ),
        fixture['extracted'],
      );
      final values = await evaluator.evaluate(rule, fixture['html'], context);
      expect(values, fixture['expectedList']);
      // Old AnalyzeRule.getString applies replacement after joining all items.
      // The no-newline literal subset cannot match across the join boundary.
      final engine = SourceEngine(
        runtime: NoScripts(),
        network: _FixtureNetwork(fixture['html'] as String),
      );
      SourceDefinition source(String operation, SourceStage stage) =>
          SourceDefinition(
            id: 'literal',
            name: 'Literal',
            baseUrl: Uri.parse('https://fixture.invalid'),
            stages: {operation: stage},
            metadata: {'legacy': true},
          );
      try {
        final scalar = await engine.execute(
          source('info', SourceStage(url: '/', fields: {'name': rule})),
          'info',
        );
        expect(scalar.single['name'], fixture['expectedString']);
        final listed = await engine.execute(
          source(
            'search',
            SourceStage(
              url: '/',
              list: '@legacy:tag.h2',
              fields: {
                'content':
                    '@legacy:text##${fixture['pattern']}##${fixture['replacement']}',
              },
            ),
          ),
          'search',
        );
        // A stage node list retains a row with an empty field, whereas the
        // old string-list extractor omits an initially empty text node.
        if ((fixture['extracted'] as List).isNotEmpty) {
          expect(
            listed.map((r) => r['content']).toList(),
            fixture['expectedFields'],
          );
        } else {
          expect(listed, [
            {'content': ''},
          ]);
        }
      } finally {
        await engine.close();
      }
    });
  }
}
