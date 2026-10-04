import 'dart:convert';

import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:source_migration/source_migration.dart';
import 'package:source_v8/source_v8.dart';
import 'package:test/test.dart';

import 'support/fixture_server.dart';

void main() {
  Map<String, Object?> original(
    Object menu, {
    String base = 'https://fixture.invalid',
  }) => {
    'bookSourceUrl': base,
    'bookSourceName': '菜单固定样本',
    'exploreUrl': menu,
    'ruleExplore': {'name': 'tag.a@text', 'bookUrl': 'tag.a@href'},
  };
  test('static JSON menu preserves labels and selected B executes identically in actual V8', () async {
    final server = await FixtureServer.start();
    final items = <Map<String, Object?>>[
      {'title': '@js:display label', 'url': '/value', 'style': null},
      {
        'title': '分类B',
        'url': '/book/123',
        'style': {
          'flexGrow': 1,
          'alignSelf': 'stretch',
          'nested': {'color': '#abc'},
        },
      },
    ];
    final menu = jsonEncode(items);
    final input = original(menu, base: server.baseUrl.toString());
    final imported = LegacySourceImporter().import(input);
    final migration = SourceMigrator().migrate(input);
    expect(imported.issues, isEmpty);
    expect(migration.issues, isEmpty);
    final candidate = SourceDefinition.fromJson(migration.candidate);
    expect(imported.source.metadata['legacyExploreItems'], items);
    expect(candidate.metadata['legacyExploreItems'], items);
    expect(candidate.metadata['legacyOriginal'], input);
    expect(candidate.metadata['legacy'], false);
    final old = SourceEngine(
      runtime: V8Runtime(prelude: legacyScriptPrelude),
      hostAdapter: LegacyScriptHost.new,
    );
    final modern = SourceEngine(runtime: V8Runtime());
    try {
      final variables = <String, Object?>{'exploreUrl': items[1]['url']};
      final baseline = await old.execute(
        imported.source,
        'explore',
        input: variables,
      );
      expect(baseline, [
        {
          'name': 'Chapter',
          'bookUrl': server.baseUrl.resolve('/book/chapter/1').toString(),
        },
      ]);
      expect(
        await modern.execute(candidate, 'explore', input: variables),
        baseline,
      );
      expect(server.requests, ['/book/123', '/book/123']);
    } finally {
      await old.close();
      await modern.close();
      await server.close();
    }
  });
  test(
    'category request options and ambiguous line delimiters stay manual',
    () {
      for (final menu in [
        jsonEncode([
          {'title': '分类', 'url': '/book/123,{"method":"POST","body":"x=1"}'},
        ]),
        '分类::http://[::1]/path',
      ]) {
        final input = original(menu);
        final migration = SourceMigrator().migrate(input);
        expect(migration.status, 'manualRequired', reason: menu);
        expect(migration.issues, isNotEmpty);
        final candidate = SourceDefinition.fromJson(migration.candidate);
        expect(candidate.metadata['legacy'], true);
        expect(candidate.metadata['legacyOriginal'], input);
        expect(candidate.stages['explore']!.method, 'GET');
        expect(candidate.stages['explore']!.body, null);
      }
    },
  );
  test('plain IPv6 HTTP menu URL is structure only and does not create a delimiter issue', () {
    const url = 'http://[::1]/path';
    final migration = SourceMigrator().migrate(original(url));
    expect(migration.issues, isEmpty);
    final candidate = SourceDefinition.fromJson(migration.candidate);
    expect(candidate.metadata['legacyExploreItems'], [
      {'title': '', 'url': url},
    ]);
    expect(candidate.metadata['legacy'], false);
    expect(candidate.stages['explore']!.url, '{{exploreUrl}}');
  });
}
