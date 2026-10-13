import 'package:source_engine/source_engine.dart';
import 'package:test/test.dart';

import 'engine_test.dart' show NoScripts;

class _Page extends NetworkClient {
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
  }) async => NetworkResponse(
    uri,
    200,
    {},
    '<a href="/first">One</a><a href="/second">Two</a>',
  );
}

void main() {
  final context = ScriptContext(host: _Host());
  test(
    'old JS-derived records read literal JSON keys, not HTML attributes',
    () async {
      final rules = RuleEvaluator(NoScripts());
      const record = {
        'name': '书名',
        'author': '作者',
        'name.with.dot': 'literal',
        'chapters': ['one', 'two'],
        'count': 3,
      };
      expect(await rules.evaluate('@legacy:name', record, context), ['书名']);
      expect(await rules.evaluate('@legacy:name.with.dot', record, context), [
        'literal',
      ]);
      expect(await rules.evaluate('@legacy:count', record, context), [3]);
      expect(await rules.evaluate('@legacy:missing', record, context), isEmpty);
      expect(
        await rules.evaluate(
          '@legacy:chapters',
          record,
          context,
          elements: true,
        ),
        ['one', 'two'],
      );
    },
  );
  for (final metadata in [
    {'legacy': true},
    {'legacy': false, 'legacyOriginal': <String, Object?>{}},
  ]) {
    test('legacy scalar link selects first href $metadata', () async {
      final engine = SourceEngine(runtime: NoScripts(), network: _Page());
      final source = SourceDefinition(
        id: 'fixture',
        name: 'Fixture',
        baseUrl: Uri.parse('https://books.test/'),
        metadata: metadata,
        stages: {
          'info': SourceStage(
            url: '/',
            fields: {
              'bookUrl': '@legacy:tag.a@href',
              'tocUrl': '@legacy:tag.a@href',
              'url': '@legacy:tag.a@href',
              'name': '@legacy:tag.a@text',
            },
          ),
        },
      );
      try {
        expect((await engine.execute(source, 'info')).single, {
          'bookUrl': 'https://books.test/first',
          'tocUrl': 'https://books.test/first',
          'url': 'https://books.test/first',
          'name': 'One\nTwo',
        });
      } finally {
        await engine.close();
      }
    });
  }
  test(
    'list extraction still returns every link without scalar URL projection',
    () async {
      final rules = RuleEvaluator(NoScripts());
      expect(
        await rules.evaluate(
          '@legacy:tag.a@href',
          '<a href="/first">One</a><a href="/second">Two</a>',
          context,
        ),
        ['/first', '/second'],
      );
    },
  );
}

class _Host implements ScriptHost {
  @override
  Future<Object?> call(String method, List<Object?> arguments) =>
      throw UnimplementedError();
}
