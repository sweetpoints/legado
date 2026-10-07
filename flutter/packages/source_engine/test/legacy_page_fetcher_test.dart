import 'package:source_engine/source_engine.dart';
import 'package:test/test.dart';

import 'engine_test.dart' show NoScripts;

class _NoHttp extends NetworkClient {
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
  }) => throw StateError('Native fetch must not send duplicate Dart HTTP');
}

class _Native implements ScriptHost {
  _Native({this.repeatNext = false});
  final bool repeatNext;
  final calls = <Map>[];
  @override
  Future<Object?> call(String method, List<Object?> args) async {
    if (method == 'variables.put') return null;
    expect(method, 'legacyRequest.fetch');
    expect((args.last as Map)['__sourceHostCallback'], false);
    final request = args.first as Map;
    calls.add(request);
    return {
      'value': {
        'url': 'https://response.test/book/',
        'status': 200,
        'headers': {
          'Set-Cookie': ['first=1', 'second=2'],
        },
        'body':
            '<h2>Native page</h2>' +
            (repeatNext
                ? '<a class="next" href="https://response.test/book/">next</a>'
                : ''),
      },
      'variables': {'requestSaved': 'value'},
    };
  }
}

void main() {
  test('native original request bypasses restricted portable templates and duplicate HTTP', () async {
    final host = _Native();
    const rawUrl =
        'https://request.test/search, {"method":"POST","body":"q={{key}}"}';
    final source = SourceDefinition(
      id: 'fixture',
      name: 'Fixture',
      baseUrl: Uri.parse('https://base.test/'),
      metadata: {
        'legacyOriginal': {
          'searchUrl': rawUrl,
          'bookSourceUrl': 'https://base.test/',
        },
      },
      stages: {
        'search': SourceStage(
          url: '{{missingModernInput}}',
          fields: {'name': '@css:h2@text'},
        ),
      },
    );
    final engine = SourceEngine(
      runtime: NoScripts(),
      network: _NoHttp(),
      platform: host,
      legacyPageFetcher: const HostLegacyPageFetcher(),
    );
    try {
      expect(
        await engine.execute(
          source,
          'search',
          input: {'taskId': 'task', 'key': 'a"b', 'page': 2},
        ),
        [
          {'name': 'Native page'},
        ],
      );
      expect(host.calls.single['urlRule'], rawUrl);
      expect(host.calls.single['key'], 'a"b');
      expect(host.calls.single['page'], 2);
      expect(
        (engine.exportSession(source.id)['variables'] as Map)['requestSaved'],
        'value',
      );
    } finally {
      await engine.close();
    }
  });
  test(
    'response preserves final redirect URL and repeated HTTP headers',
    () async {
      final host = _Native();
      final response = await const HostLegacyPageFetcher().fetch(
        SourceDefinition(
          id: 'fixture',
          name: 'Fixture',
          baseUrl: Uri.parse('https://base.test/'),
          metadata: {
            'legacyOriginal': {'searchUrl': '/search'},
          },
        ),
        'search',
        {},
        ScriptContext(
          host: host,
          variables: {'taskId': 'task', 'baseUrl': 'https://base.test/'},
        ),
      );
      expect(response.url.toString(), 'https://response.test/book/');
      expect(response.multiHeaders['Set-Cookie'], ['first=1', 'second=2']);
      expect(response.body, '<h2>Native page</h2>');
    },
  );
  test(
    'original pagination stops before refetching the redirected first page',
    () async {
      final native = _Native(repeatNext: true);
      final source = SourceDefinition(
        id: 'loop',
        name: 'Loop',
        baseUrl: Uri.parse('https://base.test/'),
        metadata: {
          'legacyOriginal': {'bookSourceUrl': 'https://base.test/'},
        },
        stages: {
          'toc': SourceStage(
            url: '{{unused}}',
            fields: {'title': '@css:h2@text'},
            nextPage: '@css:a.next@href',
            maxPages: 3,
          ),
        },
      );
      final engine = SourceEngine(
        runtime: NoScripts(),
        network: _NoHttp(),
        platform: native,
        legacyPageFetcher: const HostLegacyPageFetcher(),
      );
      try {
        expect(
          await engine.execute(
            source,
            'toc',
            input: {'taskId': 'task', 'tocUrl': 'https://initial.test/toc'},
          ),
          [
            {'title': 'Native page'},
          ],
        );
        expect(native.calls, hasLength(1));
      } finally {
        await engine.close();
      }
    },
  );
}
