import 'package:source_engine/source_engine.dart';
import 'package:test/test.dart';

import 'engine_test.dart' show NoScripts;

class _Pages extends NetworkClient {
  final requests = <Uri>[];
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
  }) async {
    requests.add(uri);
    return NetworkResponse(
      uri,
      200,
      {},
      '<h2>A&amp;amp;B</h2><a href="next?a=1&amp;amp;b=2">next</a>',
    );
  }
}

void main() {
  for (final metadata in <Map<String, Object?>>[
    {'legacy': true},
    {'legacy': false, 'legacyOriginal': <String, Object?>{}},
    {},
  ]) {
    test('scalar HTML4 provenance $metadata and excluded old paths', () async {
      final network = _Pages();
      final engine = SourceEngine(runtime: NoScripts(), network: network);
      final isLegacy = metadata.isNotEmpty;
      final source = SourceDefinition(
        id: 'fixture',
        name: 'Fixture',
        baseUrl: Uri.parse('https://books.test/'),
        metadata: metadata,
        stages: {
          'info': SourceStage(
            url: '/',
            fields: {
              'name': '@legacy:tag.h2@text',
              'author': '@legacy:tag.h2@text##&amp;##&lt;',
              'bookUrl': '@legacy:tag.a@href',
              'kind': '@legacy:tag.h2@text',
              'downloadUrls': '@legacy:tag.h2@text',
              'modern': '@css:h2@text',
            },
          ),
          'content': SourceStage(
            url: '/',
            fields: {'content': '@legacy:tag.h2@text'},
          ),
          'toc': SourceStage(
            url: '/',
            fields: {'title': '@legacy:tag.h2@text'},
            nextPage: '@legacy:tag.a@href',
            maxPages: 2,
          ),
        },
      );
      try {
        expect((await engine.execute(source, 'info')).single, {
          'name': isLegacy ? 'A&B' : 'A&amp;B',
          'author': isLegacy ? 'A<B' : 'A&lt;B',
          'bookUrl': isLegacy
              ? 'https://books.test/next?a=1&b=2'
              : 'https://books.test/next?a=1&amp;b=2',
          'kind': 'A&amp;B',
          'downloadUrls': 'A&amp;B',
          'modern': 'A&amp;B',
        });
        expect(
          (await engine.execute(source, 'content')).single['content'],
          'A&amp;B',
        );
        network.requests.clear();
        await expectLater(
          engine.execute(source, 'toc'),
          throwsA(
            isA<EngineException>().having(
              (e) => e.code,
              'code',
              'pagination_limit',
            ),
          ),
        );
        expect(network.requests.map((u) => u.toString()), [
          'https://books.test/',
          'https://books.test/next?a=1&amp;b=2',
        ]);
      } finally {
        await engine.close();
      }
    });
  }
  test('shared HTML4 decoder retains one-pass Commons Text boundaries', () {
    expect(
      unescapeHtml4('&amp;lt; &apos; &AMP; &copy &#128;'),
      '&lt; &apos; &AMP; &copy \u0080',
    );
  });
}
