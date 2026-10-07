import 'package:source_engine/source_engine.dart';
import 'package:source_tools/source_tools.dart';
import 'package:test/test.dart';

class _UnusedRuntime implements ScriptRuntime {
  @override
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  }) async => throw StateError('No script execution expected');
  @override
  Future<void> close() async {}
}

void main() {
  test('composition registers selected request adapter independently of legacy JS mode', () async {
    for (final legacy in [false, true]) {
      final stage = SourceStage(
        url: '{{exploreUrl}}',
        legacyRequestInput: 'exploreUrl',
        list: 'article',
        fields: {'name': 'h1@text'},
        maxPages: 2,
      );
      final source = SourceDefinition(
        id: 'fixture',
        name: 'Fixture',
        baseUrl: Uri.parse('https://example.org'),
        headers: {'X-Default': 'value'},
        metadata: {'legacy': legacy},
        stages: {'explore': stage},
      );
      final engine = createCliEngine(source, _UnusedRuntime());
      try {
        expect(engine.requestAdapter, isNotNull);
        final adapted = engine.requestAdapter!(source, stage, {
          'exploreUrl': 'https://example.org/category,{"method":"POST","body":"q={{key}}"}',
        });
        expect(adapted.legacyRequestInput, isNull);
        expect(adapted.method, 'POST');
        expect(adapted.url, 'https://example.org/category');
        expect(adapted.body, 'q={{key}}');
        expect(adapted.bodyEncoding, 'legacyFormUtf8');
        expect(adapted.bodyTemplateMode, 'legacyJsonString');
        expect(adapted.headers!['X-Default'], 'value');
        expect(adapted.list, stage.list);
        expect(adapted.fields, stage.fields);
        expect(adapted.maxPages, 2);
        final modernStage = SourceStage(url: '/modern');
        expect(
          engine.requestAdapter!(source, modernStage, {}),
          same(modernStage),
        );
        await expectLater(
          engine.execute(
            source,
            'explore',
            input: {
              'exploreUrl': 'https://example.org/category,{"webView":true}',
            },
          ),
          throwsA(
            isA<EngineException>().having(
              (e) => e.code,
              'code',
              'legacy_request_requires_migration',
            ),
          ),
        );
      } finally {
        await engine.close();
      }
    }
  });
}
