import 'dart:convert';

import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:source_migration/source_migration.dart';
import 'package:source_v8/source_v8.dart';
import 'package:test/test.dart';

import 'support/fixture_server.dart';

void main() {
  test('selected explore category B overrides preserved original menu A in legacy and migrated source', () async {
    final server = await FixtureServer.start();
    final menu = '分类A::${server.baseUrl.resolve('/value')}';
    final original = <String, Object?>{
      'bookSourceUrl': server.baseUrl.toString(),
      'bookSourceName': '固定分类菜单',
      'exploreUrl': menu,
      'ruleExplore': {'name': 'tag.a@text', 'bookUrl': 'tag.a@href'},
    };
    final imported = LegacySourceImporter().import(original);
    final migration = SourceMigrator().migrate(original);
    expect(imported.issues, isEmpty);
    expect(migration.issues, isEmpty);
    final candidate = SourceDefinition.fromJson(migration.candidate);
    expect(candidate.metadata['legacy'], false);
    expect((candidate.metadata['legacyOriginal'] as Map)['exploreUrl'], menu);
    expect(candidate.stages['explore']!.url, '{{exploreUrl}}');
    final legacy = SourceEngine(
      runtime: V8Runtime(prelude: legacyScriptPrelude),
      hostAdapter: LegacyScriptHost.new,
    );
    final modern = SourceEngine(runtime: V8Runtime());
    try {
      final input = <String, Object?>{
        'exploreUrl': server.baseUrl.resolve('/book/123').toString(),
      };
      final baseline = await legacy.execute(
        imported.source,
        'explore',
        input: input,
      );
      expect(baseline, [
        {
          'name': 'Chapter',
          'bookUrl': server.baseUrl.resolve('/book/chapter/1').toString(),
        },
      ]);
      expect(
        await modern.execute(candidate, 'explore', input: input),
        baseline,
      );
      expect(server.requests, ['/book/123', '/book/123']);
    } finally {
      await legacy.close();
      await modern.close();
      await server.close();
    }
  });
  for (final hook in ['contentBatch', 'callBackJs']) {
    test(
      'whole-source migration refuses automatic mode for $hook pipeline',
      () {
        final original = <String, Object?>{
          'bookSourceUrl': 'https://fixture.invalid',
          'bookSourceName': 'pipeline',
          'ruleContent': {
            'content': 'tag.p@text',
            hook: 'java.cacheContent("url","body")',
          },
        };
        final migration = SourceMigrator().migrate(original);
        expect(migration.status, 'manualRequired');
        expect(
          migration.issues.any(
            (issue) =>
                issue.path == 'ruleContent.$hook' &&
                issue.code == 'legacy.pipeline_requires_review',
          ),
          true,
        );
        final candidate = SourceDefinition.fromJson(migration.candidate);
        expect(candidate.metadata['legacy'], true);
        expect(candidate.metadata['compatibility'], 'manualRequired');
        expect(migration.original, original);
        expect(candidate.metadata['legacyOriginal'], original);
      },
    );
  }
  for (final (rule, expected) in [
    ('tag.p.0: 2@text', 'zero\ntwo'),
    ('tag.p!0: 2@text', 'one'),
  ]) {
    test('actual V8 legacy index suffix permits ASCII spaces: $rule', () async {
      final engine = SourceEngine(runtime: V8Runtime());
      final source = SourceDefinition(
        id: 'indices',
        name: 'indices',
        baseUrl: Uri.parse('https://fixture.invalid'),
        script:
            'async function search(){return {value:await source.parse.getString(${jsonEncode('@legacy:$rule')},"<p>zero</p><p>one</p><p>two</p>")};}',
      );
      try {
        expect(
          (await engine.execute(source, 'search')).single['value'],
          expected,
        );
      } finally {
        await engine.close();
      }
    });
  }
}
