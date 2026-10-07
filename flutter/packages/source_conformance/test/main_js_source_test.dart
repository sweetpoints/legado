import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:source_migration/source_migration.dart';
import 'package:source_v8/source_v8.dart';
import 'package:test/test.dart';

import 'support/fixture_server.dart';

void main() {
  late FixtureServer server;
  late SourceEngine engine;
  setUp(() async {
    server = await FixtureServer.start();
    engine = SourceEngine(
      runtime: V8Runtime(prelude: legacyScriptPrelude),
      hostAdapter: LegacyScriptHost.new,
    );
  });
  tearDown(() async {
    await engine.close();
    await server.close();
  });
  Map<String, Object?> original(String program) => {
    'bookSourceUrl': server.baseUrl.toString(),
    'bookSourceName': 'Full mainJs source',
    'enabledCookieJar': true,
    'mainJs': program,
  };

  test('full mainJs preserves positional search/explore and synchronous HTTP in V8', () async {
    final raw = original(r'''
      const suffix = source.bookSourceName;
      function search(key, page) {
        const value = JSON.parse(java.ajax(baseUrl + '/value')).value;
        return JSON.stringify([{name:key + ':' + page + ':' + value,author:suffix}]);
      }
      function explore(url, page) { return [{name:url + ':' + page,author:sourceApi.bookSourceName}]; }
    ''');
    final imported = LegacySourceImporter().import(raw);
    expect(imported.issues, isEmpty);
    expect(imported.source.script, isNotNull);
    expect(imported.source.stages, isEmpty);
    expect(imported.source.metadata['legacyMainJs'], true);
    expect(imported.source.metadata['legacyOriginal'], raw);
    final migration = SourceMigrator().migrate(raw);
    expect(migration.issues, isEmpty);
    expect(migration.status, 'unverified');
    final candidate = SourceDefinition.fromJson(migration.candidate);
    expect(candidate.metadata['legacy'], true);
    for (final definition in [imported.source, candidate]) {
      expect(
        await engine.execute(
          definition,
          'search',
          input: {'key': 'K', 'page': 3},
        ),
        [
          {'name': 'K:3:异步结果', 'author': 'Full mainJs source'},
        ],
      );
      expect(
        await engine.execute(
          definition,
          'explore',
          input: {'exploreUrl': '/category'},
        ),
        [
          {'name': '/category:1', 'author': 'Full mainJs source'},
        ],
      );
    }
    expect(server.requests, ['/value', '/value']);
  });

  test('async mainJs functions retain positional arguments and curated Java host calls', () async {
    final definition = LegacySourceImporter()
        .import(
          original(r'''
      async function search(key, page) {
        const value = await Promise.resolve(key + ':' + page);
        return [{name:value, digest:java.md5Encode('abc')}];
      }
    '''),
        )
        .source;
    expect(
      await engine.execute(
        definition,
        'search',
        input: {'key': 'Async', 'page': 2},
      ),
      [
        {'name': 'Async:2', 'digest': '900150983cd24fb0d6963f7d28e17f72'},
      ],
    );
  });

  for (final declaration in ['const', 'let']) {
    test(
      'mainJs $declaration source config shadows the injected DTO safely',
      () async {
        final definition = LegacySourceImporter()
            .import(
              original('''
        $declaration source = {bookSourceName:'Declared config'};
        function search(key, page) {
          return [{name:source.bookSourceName + ':' + key + ':' + page,author:sourceApi.bookSourceName}];
        }
      '''),
            )
            .source;
        expect(
          await engine.execute(
            definition,
            'search',
            input: {'key': 'K', 'page': 2},
          ),
          [
            {'name': 'Declared config:K:2', 'author': 'Full mainJs source'},
          ],
        );
      },
    );
  }

  test('mainJs info and toc receive flattened or explicit book DTOs', () async {
    final imported = LegacySourceImporter().import(
      original(r'''
      function getBookInfo(book) { book.name += ':info'; return JSON.stringify(book); }
      function getChapters(book) { return JSON.stringify([{title:book.name,url:book.tocUrl,isVolume:true,index:7}]); }
    '''),
    );
    for (final input in <Map<String, Object?>>[
      {'name': 'Book', 'bookUrl': '/book', 'tocUrl': '/toc'},
      {
        'book': {'name': 'Book', 'bookUrl': '/book', 'tocUrl': '/toc'},
      },
    ]) {
      expect(
        (await engine.execute(
          imported.source,
          'info',
          input: input,
        )).single['name'],
        'Book:info',
      );
      expect(await engine.execute(imported.source, 'toc', input: input), [
        {'title': 'Book', 'url': '/toc', 'isVolume': true, 'index': 7},
      ]);
    }
  });

  for (final body in [
    '',
    'function getBookInfo(book) { book.name="updated"; }',
  ]) {
    test(
      'optional mainJs info keeps book when missing or undefined: $body',
      () async {
        final definition = LegacySourceImporter()
            .import(original('$body\nfunction search(){return [];}'))
            .source;
        final result = await engine.execute(
          definition,
          'info',
          input: {'name': 'Book', 'tocUrl': '/toc'},
        );
        expect(result.single['name'], body.isEmpty ? 'Book' : 'updated');
        expect(result.single['tocUrl'], '/toc');
      },
    );
  }

  test(
    'mainJs content preserves chapter DTO fields and JSON-looking text',
    () async {
      final definition = LegacySourceImporter()
          .import(
            original(r'''
      function getContent(chapter, book, nextChapterUrl) {
        return JSON.stringify([chapter.url,chapter.title,chapter.index,chapter.isVolume,book.name,nextChapterUrl]);
      }
    '''),
          )
          .source;
      final result = await engine.execute(
        definition,
        'content',
        input: {
          'chapter': {
            'url': '/chapter',
            'title': 'Title',
            'index': 9,
            'isVolume': false,
          },
          'book': {'name': 'Book'},
          'nextChapterUrl': '/next',
        },
      );
      expect(
        result.single['content'],
        '["/chapter","Title",9,false,"Book","/next"]',
      );
      final flat = await engine.execute(
        definition,
        'content',
        input: {
          'chapterUrl': '/chapter',
          'chapterTitle': 'Title',
          'name': 'Book',
          'nextChapterUrl': null,
        },
      );
      expect(
        flat.single['content'],
        '["/chapter","Title",null,null,"Book",null]',
      );
    },
  );

  test(
    'legacy mainJs lexical functions do not recurse into modern wrappers',
    () async {
      final definition = LegacySourceImporter()
          .import(
            original(r'''
      let count=0;
      const search = (key, page) => [{name:key + ':' + page + ':' + (++count)}];
    '''),
          )
          .source;
      for (var i = 0; i < 2; i++) {
        expect(
          (await engine.execute(
            definition,
            'search',
            input: {'key': 'A'},
          )).single['name'],
          'A:1:1',
        );
      }
    },
  );

  for (final (program, message) in [
    ('function getChapters(){return [];}', 'Missing legacy function search'),
    (
      'function search(){return java.unknownApi("x");}',
      'legacy.unsupported_api',
    ),
    ('function search(){return "{bad json";}', 'SyntaxError'),
    ('function search(){return {name:"Wrong shape"};}', 'must return an array'),
  ]) {
    test(
      'mainJs unsupported execution fails explicitly without HTTP: $message',
      () async {
        final definition = LegacySourceImporter()
            .import(original(program))
            .source;
        await expectLater(
          engine.execute(definition, 'search'),
          throwsA(
            isA<EngineException>().having(
              (error) => error.toString(),
              'error',
              contains(message),
            ),
          ),
        );
        expect(server.requests, isEmpty);
      },
    );
  }

  test(
    'mainJs leaves unsupported jsLib manual and modern script input untouched',
    () async {
      final raw = original('function search(){return [];}')
        ..['jsLib'] = 'external unsupported library';
      expect(
        LegacySourceImporter().import(raw).issues.map((issue) => issue.path),
        contains('jsLib'),
      );
      final modern = SourceDefinition(
        id: 'modern',
        name: 'Modern',
        baseUrl: server.baseUrl,
        script: 'function search(input){return [{name:input.key,kind:typeof input}];}',
      );
      expect(await engine.execute(modern, 'search', input: {'key': 'Modern'}), [
        {'name': 'Modern', 'kind': 'object'},
      ]);
    },
  );
}
