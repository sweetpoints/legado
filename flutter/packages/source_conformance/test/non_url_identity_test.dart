import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:source_migration/source_migration.dart';
import 'package:source_v8/source_v8.dart';
import 'package:test/test.dart';

import 'support/fixture_server.dart';

void main() {
  late FixtureServer server;
  setUp(() async => server = await FixtureServer.start());
  tearDown(() => server.close());
  Map<String, Object?> source(String id) => {
    'bookSourceUrl': id,
    'bookSourceName': id,
    'searchUrl':
        '${server.baseUrl.resolve('/book/123')}?q={{key}}&page={{page}}',
    'ruleSearch': {
      'name': 'tag.a@text',
      'bookUrl': 'tag.a@href',
      'requestUrl': '@js:baseUrl',
    },
    'ruleBookInfo': {'name': 'tag.a@text'},
  };
  SourceEngine engine() => SourceEngine(
    runtime: V8Runtime(prelude: legacyScriptPrelude),
    hostAdapter: LegacyScriptHost.new,
  );
  test('non-URL ID keeps identity and review while absolute search works in original and candidate', () async {
    final input = source('local-source-A');
    final imported = LegacySourceImporter().import(input);
    final migration = SourceMigrator().migrate(input);
    final candidate = SourceDefinition.fromJson(migration.candidate);
    expect(imported.source.id, 'local-source-A');
    expect(candidate.id, 'local-source-A');
    expect(candidate.metadata['legacyBaseUrlUnavailable'], true);
    expect(migration.status, 'manualRequired');
    expect(candidate.metadata['legacy'], true);
    expect(candidate.metadata['compatibility'], 'manualRequired');
    expect(
      migration.issues.any(
        (issue) => issue.code == 'legacy.base_url_requires_review',
      ),
      true,
    );
    expect(migration.original, input);
    final old = engine();
    final migrated = engine();
    try {
      old.importSession(imported.source.id, {
        'variables': {'marker': 'A'},
      });
      migrated.importSession(candidate.id, {
        'variables': {'marker': 'A'},
      });
      const variables = <String, Object?>{'key': '中文 空格', 'page': 2};
      final result = await old.execute(
        imported.source,
        'search',
        input: variables,
      );
      expect(result, [
        {
          'name': 'Chapter',
          'bookUrl': server.baseUrl.resolve('/book/chapter/1').toString(),
          'requestUrl':
              '${server.baseUrl.resolve('/book/123')}?q=${Uri.encodeComponent('中文 空格')}&page=2',
        },
      ]);
      expect(
        await migrated.execute(candidate, 'search', input: variables),
        result,
      );
      expect(server.requests, ['/book/123', '/book/123']);
      for (final (executor, definition) in [
        (old, imported.source),
        (migrated, candidate),
      ]) {
        await expectLater(
          executor.execute(
            definition,
            'info',
            input: {'bookUrl': '/relative-book'},
          ),
          throwsA(
            isA<EngineException>().having(
              (error) => error.code,
              'code',
              'legacy_base_url_required',
            ),
          ),
        );
      }
      expect(server.requests, ['/book/123', '/book/123']);
    } finally {
      await old.close();
      await migrated.close();
    }
  });
  test('two non-URL IDs sharing an absolute endpoint keep separate engine sessions', () async {
    final first = LegacySourceImporter()
        .import(source('local-source-A'))
        .source;
    final second = LegacySourceImporter()
        .import(source('local-source-B'))
        .source;
    expect(first.id, isNot(second.id));
    expect(first.stages['search']!.url, second.stages['search']!.url);
    final executor = engine();
    try {
      executor.importSession(first.id, {
        'variables': {'marker': 'A'},
      });
      executor.importSession(second.id, {
        'variables': {'marker': 'B'},
      });
      for (final (definition, marker) in [
        (first, 'A'),
        (second, 'B'),
        (first, 'A'),
      ]) {
        expect(
          (await executor.execute(
            definition,
            'search',
            input: {'key': 'same', 'page': 1},
          )).single['name'],
          'Chapter',
        );
        expect(executor.exportSession(definition.id)['variables'], {
          'marker': marker,
        });
      }
      expect(executor.exportSession(first.id)['variables'], {'marker': 'A'});
      expect(executor.exportSession(second.id)['variables'], {'marker': 'B'});
    } finally {
      await executor.close();
    }
  });
}
