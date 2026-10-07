import 'dart:convert';

import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:source_migration/source_migration.dart';
import 'package:source_v8/source_v8.dart';
import 'package:test/test.dart';

import 'support/fixture_server.dart';

void main() {
  late FixtureServer server;
  late SourceEngine legacy;
  late SourceEngine modern;
  setUp(() async {
    server = await FixtureServer.start();
    legacy = SourceEngine(
      runtime: V8Runtime(prelude: legacyScriptPrelude),
      hostAdapter: LegacyScriptHost.new,
    );
    modern = SourceEngine(runtime: V8Runtime());
  });
  tearDown(() async {
    await legacy.close();
    await modern.close();
    await server.close();
  });

  Map<String, Object?> original(String body, {String? contentType}) => {
    'bookSourceUrl': server.baseUrl.toString(),
    'bookSourceName': '旧版 UTF8 POST 表单',
    'searchUrl':
        '/echo-options,${jsonEncode({
          'method': 'POST',
          'body': body,
          if (contentType != null) 'headers': {'Content-Type': contentType},
        })}',
    'ruleSearch': {
      'name': r'$.body',
      'method': r'$.method',
      'contentType': r'$.contentType',
      'proof': '@js:java.base64Encode("proof")',
    },
  };

  Future<List<Map<String, Object?>>> compare(
    String body,
    Object key, {
    required String encoding,
    String? contentType,
  }) async {
    final input = original(body, contentType: contentType);
    final imported = LegacySourceImporter().import(input);
    final migration = SourceMigrator().migrate(input);
    expect(imported.issues, isEmpty);
    expect(migration.issues, isEmpty);
    final candidate = SourceDefinition.fromJson(migration.candidate);
    expect(imported.source.stages['search']!.bodyEncoding, encoding);
    expect(candidate.stages['search']!.bodyEncoding, encoding);
    final templateMode = body.contains('{{') ? 'legacyJsonString' : 'raw';
    expect(imported.source.stages['search']!.bodyTemplateMode, templateMode);
    expect(candidate.stages['search']!.bodyTemplateMode, templateMode);
    final baseline = await legacy.execute(
      imported.source,
      'search',
      input: {'key': key},
    );
    final migrated = await modern.execute(
      candidate,
      'search',
      input: {'key': key},
    );
    expect(migrated, baseline);
    expect(migrated.single['method'], 'POST');
    // These values require genuine V8 execution in both legacy and migrated JS.
    expect(migrated.single['proof'], 'cHJvb2Y=');
    expect(server.requests, ['/echo-options', '/echo-options']);
    return migrated;
  }

  for (final (label, body, contentType) in [
    ('JSON', '{"q":"{{key}}"}', null),
    ('explicit Content-Type', 'q={{key}}', 'text/plain; charset=UTF-8'),
  ]) {
    for (final (valueLabel, value) in [
      ('double quote', 'a"b'),
      ('backslash', r'a\b'),
      ('newline', 'a\nb'),
      ('null control', 'a\u0000b'),
    ]) {
      test('legacy raw $label rejects $valueLabel before HTTP', () async {
        final input = original(body, contentType: contentType);
        final imported = LegacySourceImporter().import(input);
        final migration = SourceMigrator().migrate(input);
        expect(imported.issues, isEmpty);
        expect(migration.issues, isEmpty);
        final candidate = SourceDefinition.fromJson(migration.candidate);
        for (final (engine, definition) in [
          (legacy, imported.source),
          (modern, candidate),
        ]) {
          final stage = definition.stages['search']!;
          expect(stage.bodyEncoding, 'raw');
          expect(stage.bodyTemplateMode, 'legacyJsonString');
          await expectLater(
            engine.execute(definition, 'search', input: {'key': value}),
            throwsA(
              isA<EngineException>().having(
                (error) => error.code,
                'code',
                'legacy_body_template_requires_migration',
              ),
            ),
          );
        }
        expect(server.requests, isEmpty);
      });
    }
  }

  test(
    'explicit legacy Content-Type keeps safe body bytes raw after migration',
    () async {
      final rows = await compare(
        'q={{key}}',
        '中文 +&next=a=b',
        encoding: 'raw',
        contentType: 'text/plain; charset=UTF-8',
      );
      expect(rows.single['name'], 'q=中文 +&next=a=b');
      expect(rows.single['contentType'], 'text/plain; charset=UTF-8');
    },
  );

  for (final (label, value) in [
    ('double quote', 'a"b'),
    ('backslash', r'a\b'),
    ('newline', 'a\nb'),
    ('null control', 'a\u0000b'),
  ]) {
    test(
      'modern hand-written raw body permits $label independently of legacy JSON',
      () async {
        final stage = SourceStage(
          url: '/echo-options',
          method: 'POST',
          body: 'q={{key}}',
          headers: const {'Content-Type': 'text/plain; charset=UTF-8'},
          fields: const {'name': r'@json:$.body', 'proof': '@js:21*2'},
        );
        expect(stage.bodyEncoding, 'raw');
        expect(stage.bodyTemplateMode, 'raw');
        final definition = SourceDefinition(
          id: 'modern-raw',
          name: 'Modern raw body',
          baseUrl: server.baseUrl,
          stages: {'search': stage},
        );
        final rows = await modern.execute(
          definition,
          'search',
          input: {'key': value},
        );
        expect(rows.single['name'], 'q=$value');
        expect(rows.single['proof'], '42');
        expect(server.requests, ['/echo-options']);
      },
    );
  }

  for (final (label, body) in [
    ('literal', '<q>fixed</q>'),
    ('templated', '<q>{{key}}</q>'),
  ]) {
    test(
      'legacy $label XML options stay manual because of whole-options angle scan',
      () {
        final input = original(body);
        final imported = LegacySourceImporter().import(input);
        final migration = SourceMigrator().migrate(input);
        expect(imported.requiresManualWork, isTrue);
        expect(migration.status, 'manualRequired');
        expect(migration.original, input);
        final candidate = SourceDefinition.fromJson(migration.candidate);
        expect(candidate.metadata['legacyOriginal'], input);
        expect(candidate.metadata['legacy'], isTrue);
        expect(imported.source.stages['search']!.body, body);
        expect(candidate.stages['search']!.body, body);
        expect(
          imported.issues.any(
            (issue) => issue.message.contains('page choices'),
          ),
          isTrue,
        );
        expect(server.requests, isEmpty);
      },
    );
  }

  // Expected wire bodies specify the old form semantics explicitly. In particular
  // substitution happens before the old form splitting, preserving inserted &.
  for (final (label, body, key, expected) in [
    (
      'Unicode and plus',
      'q={{key}}&tail=',
      '中文 +',
      'q=%E4%B8%AD%E6%96%87+%2B&tail=',
    ),
    (
      'literal leading empty segments',
      '&&q=中文 +&&tail=&',
      'unused',
      'q=%E4%B8%AD%E6%96%87+%2B&&tail=&',
    ),
    ('valid percent encoding', 'q={{key}}', '%20', 'q=%20'),
    ('mixed escaped and unescaped text', 'q={{key}}', '%20 +', 'q=%2520+%2B'),
    ('invalid percent encoding', 'q={{key}}', '%GG', 'q=%25GG'),
    (
      'inserted parameter and equals',
      'key={{key}}&tail=',
      'a&next=b=c',
      'key=a&next=b%3Dc&tail=',
    ),
    (
      'middle and trailing empty segments',
      'q={{key}}&&tail=&',
      'value',
      'q=value&&tail=&',
    ),
    (
      'literal plus outside template',
      'q={{key}}&literal=one+two',
      'value',
      'q=value&literal=one%2Btwo',
    ),
    (
      'static Unicode',
      'q={{key}}&literal=中文',
      'value',
      'q=value&literal=%E4%B8%AD%E6%96%87',
    ),
  ]) {
    test('actual V8 legacy and migrated form POST: $label', () async {
      final rows = await compare(body, key, encoding: 'legacyFormUtf8');
      expect(rows.single['name'], expected);
      expect(rows.single['contentType'], 'application/x-www-form-urlencoded');
    });
  }

  for (final (label, value) in [
    ('double quote', 'a"b'),
    ('backslash', r'a\b'),
    ('newline', 'a\nb'),
    ('tab', 'a\tb'),
    ('null control', 'a\u0000b'),
    ('DEL control', 'a\u007fb'),
    ('integral double', 2.0),
    ('fractional double', 2.5),
    ('unsafe JS integer', 9007199254740992),
  ]) {
    test('legacy options boundary rejects $label before HTTP', () async {
      final input = original('q={{key}}');
      final imported = LegacySourceImporter().import(input);
      final migration = SourceMigrator().migrate(input);
      expect(imported.issues, isEmpty);
      expect(migration.issues, isEmpty);
      for (final (engine, definition) in [
        (legacy, imported.source),
        (modern, SourceDefinition.fromJson(migration.candidate)),
      ]) {
        await expectLater(
          engine.execute(definition, 'search', input: {'key': value}),
          throwsA(
            isA<EngineException>().having(
              (error) => error.code,
              'code',
              'legacy_body_template_requires_migration',
            ),
          ),
        );
      }
      expect(server.requests, isEmpty);
    });
  }

  test(
    'templated leading empty segments remain a manual migration boundary',
    () {
      final input = original('&&q={{key}}&&tail=&');
      final imported = LegacySourceImporter().import(input);
      final migration = SourceMigrator().migrate(input);
      expect(imported.requiresManualWork, isTrue);
      expect(migration.status, 'manualRequired');
      expect(
        imported.issues.any(
          (issue) => issue.message.contains('fixed non-empty parameter name'),
        ),
        isTrue,
      );
      expect(server.requests, isEmpty);
    },
  );

  for (final (label, body, expected) in [
    ('JSON', '{"q":"{{key}}"}', '{"q":"中文 +&next=a=b"}'),
  ]) {
    test('legacy $label body remains raw after migration', () async {
      final rows = await compare(body, '中文 +&next=a=b', encoding: 'raw');
      expect(rows.single['name'], expected);
      // The old URL-options branch assigns JSON Content-Type to XML too.
      expect(rows.single['contentType'], 'application/json; charset=UTF-8');
    });
  }
}
