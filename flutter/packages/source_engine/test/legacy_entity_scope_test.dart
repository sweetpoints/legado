import 'dart:convert';

import 'package:source_engine/source_engine.dart';
import 'package:test/test.dart';

import 'engine_test.dart' show NoScripts;

class _Pages extends NetworkClient {
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
  }) async => NetworkResponse(uri, 200, {}, 'fixture');
}

class _Host implements ScriptHost {
  _Host(this.extract);
  final Object? Function(Map request, Map scope) extract;
  final scopes = <Map>[];
  @override
  Future<Object?> call(String method, List<Object?> args) async {
    final r = args.first as Map;
    final scope = jsonDecode(jsonEncode(r['variableScope'])) as Map;
    scopes.add(scope);
    final value = extract(r, scope);
    return {
      'value': value,
      'variables': <String, String>{},
      'variableScope': scope,
    };
  }
}

SourceDefinition _source() => SourceDefinition(
  id: 'source',
  name: 'Fixture',
  baseUrl: Uri.parse('https://fixture.invalid/'),
  metadata: {
    'legacyOriginal': {'searchUrl': '/search'},
  },
  stages: {
    'search': SourceStage(
      url: '/search',
      list: 'rows',
      fields: {'name': 'saveName', 'bookUrl': 'url'},
    ),
    'info': SourceStage(url: '{{bookUrl}}', fields: {'name': 'detail'}),
    'toc': SourceStage(
      url: '{{bookUrl}}',
      list: 'rows',
      fields: {'title': 'chapter'},
    ),
    'content': SourceStage(url: '{{chapterUrl}}', fields: {'content': 'body'}),
  },
);
SourceEngine _engine(_Host host, {bool native = false}) => SourceEngine(
  runtime: NoScripts(),
  network: _Pages(),
  platform: host,
  legacyRuleEvaluator: const HostLegacyRuleEvaluator(),
  legacyPageFetcher: native ? const HostLegacyPageFetcher() : null,
);
void main() {
  test('cleared seeded book variables publish an empty patch instead of retaining old values', () async {
    final host = _Host((request, scope) {
      (scope['book'] as Map).clear();
      return 'Title';
    });
    final engine = _engine(host);
    try {
      expect(
        await engine.execute(
          _source(),
          'info',
          input: {
            'taskId': 'clear',
            'bookUrl': 'https://fixture.invalid/book',
            'book': {
              'variable': {'old': 'value'},
            },
          },
        ),
        [
          {'name': 'Title', 'variable': <String, String>{}},
        ],
      );
    } finally {
      await engine.close();
    }
  });
  test(
    'legacy variables named book cannot replace the JSON book binding',
    () async {
      final host = _Host((request, scope) {
        expect((request['variables'] as Map)['book'], isA<Map>());
        expect((scope['book'] as Map)['book'], 'variable value');
        return 'Title';
      });
      final engine = _engine(host);
      try {
        await engine.execute(
          _source(),
          'info',
          input: {
            'taskId': 'reserved',
            'bookUrl': 'https://fixture.invalid/book',
            'book': {
              'name': 'Book',
              'variable': {'book': 'variable value'},
            },
          },
        );
      } finally {
        await engine.close();
      }
    },
  );
  test('each search row owns its book variables and never writes source-global state', () async {
    final host = _Host((r, s) {
      if (r['mode'] == 'elements') {
        return [
          {'name': 'A'},
          {'name': 'B'},
        ];
      }
      final row = r['input'] as Map, book = s['book'] as Map;
      expect(s['target'], 'book');
      if (r['rule'] == 'saveName') {
        expect(book.containsKey('saved'), false);
        book['saved'] = row['name'];
        return row['name'];
      }
      expect(book['saved'], row['name']);
      return 'https://fixture.invalid/${row['name']}';
    });
    final actual = _engine(host);
    try {
      expect(
        await actual.execute(_source(), 'search', input: {'taskId': 'search'}),
        [
          {
            'name': 'A',
            'bookUrl': 'https://fixture.invalid/A',
            'variable': {'saved': 'A'},
          },
          {
            'name': 'B',
            'bookUrl': 'https://fixture.invalid/B',
            'variable': {'saved': 'B'},
          },
        ],
      );
      expect(
        host.scopes
            .where((s) => (s['book'] as Map).containsKey('saved'))
            .map((s) => s['id'])
            .toSet(),
        hasLength(2),
      );
      expect(actual.exportSession('source')['variables'], isEmpty);
    } finally {
      await actual.close();
    }
  });
  test('later info calls seed the supplied live book rather than the previous book', () async {
    final host = _Host((r, s) {
      final b = s['book'] as Map;
      b['detail'] = 'detail-${b['saved']}';
      return b['detail'];
    });
    final engine = _engine(host);
    try {
      for (final name in ['B', 'A']) {
        final result = await engine.execute(
          _source(),
          'info',
          input: {
            'taskId': 'info-$name',
            'bookUrl': 'https://fixture.invalid/$name',
            'book': {
              'name': name,
              'variable': jsonEncode({'saved': name, 'keep': 'old'}),
            },
          },
        );
        expect(result, [
          {
            'name': 'detail-$name',
            'variable': {
              'saved': name,
              'keep': 'old',
              'detail': 'detail-$name',
            },
          },
        ]);
      }
      expect(engine.exportSession('source')['variables'], isEmpty);
    } finally {
      await engine.close();
    }
  });
  test(
    'toc writes chapter-local values while reads retain the book layer',
    () async {
      final host = _Host((r, s) {
        if (r['mode'] == 'elements') return ['one', 'two'];
        expect((s['book'] as Map)['saved'], 'book');
        expect(s['target'], 'chapter');
        expect((s['chapter'] as Map).containsKey('local'), false);
        (s['chapter'] as Map)['local'] = r['input'];
        return r['input'];
      });
      final engine = _engine(host);
      try {
        expect(
          await engine.execute(
            _source(),
            'toc',
            input: {
              'taskId': 'toc',
              'bookUrl': 'https://fixture.invalid/book',
              'book': {
                'variable': {'saved': 'book'},
              },
            },
          ),
          [
            {
              'title': 'one',
              'variable': {'local': 'one'},
              'bookVariable': {'saved': 'book'},
            },
            {
              'title': 'two',
              'variable': {'local': 'two'},
              'bookVariable': {'saved': 'book'},
            },
          ],
        );
      } finally {
        await engine.close();
      }
    },
  );
  test(
    'native URL writes book state but content writes chapter state',
    () async {
      final host = _Host((r, s) {
        if (r.containsKey('urlRule')) {
          expect(s['target'], 'book');
          (s['book'] as Map)['request'] = 'URL';
          return {
            'url': 'https://fixture.invalid/chapter',
            'status': 200,
            'body': 'fixture',
            'headers': <String, List<String>>{},
          };
        }
        expect(s['target'], 'chapter');
        expect((s['book'] as Map)['request'], 'URL');
        expect((s['chapter'] as Map)['same'], '');
        expect((s['book'] as Map)['same'], 'Book fallback');
        (s['chapter'] as Map)['local'] = 'chapter';
        return 'Text';
      });
      final engine = _engine(host, native: true);
      engine.importSession('source', {
        'variables': {'same': 'wrong source global'},
        'cookies': [],
      });
      try {
        expect(
          await engine.execute(
            _source(),
            'content',
            input: {
              'taskId': 'content',
              'chapterUrl': 'https://fixture.invalid/chapter',
              'book': {
                'variable': {'same': 'Book fallback'},
              },
              'chapter': {
                'variable': {'same': ''},
              },
            },
          ),
          [
            {
              'content': 'Text',
              'variable': {'same': '', 'local': 'chapter'},
              'bookVariable': {'same': 'Book fallback', 'request': 'URL'},
            },
          ],
        );
        expect(engine.exportSession('source')['variables'], {
          'same': 'wrong source global',
        });
      } finally {
        await engine.close();
      }
    },
  );
}
