import 'dart:convert';
import 'dart:io';

import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:source_migration/source_migration.dart';
import 'package:source_v8/source_v8.dart';
import 'package:test/test.dart';

void main() {
  late HttpServer server;
  late Uri base;
  late SourceEngine legacy;
  late SourceEngine modern;
  late List<Map<String, Object?>> requests;
  setUp(() async {
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    base = Uri.parse('http://${server.address.address}:${server.port}');
    requests = [];
    server.listen((request) async {
      final record = <String, Object?>{
        'url': request.uri.toString(),
        'method': request.method,
        'body': await utf8.decoder.bind(request).join(),
        'category': request.headers.value('x-category') ?? '',
        'base': request.headers.value('x-source') ?? '',
        'contentType': request.headers.value('content-type') ?? '',
      };
      requests.add(record);
      request.response.headers.contentType = ContentType.json;
      request.response.write(jsonEncode(record));
      await request.response.close();
    });
    legacy = SourceEngine(
      runtime: V8Runtime(prelude: legacyScriptPrelude),
      hostAdapter: LegacyScriptHost.new,
      requestAdapter: adaptLegacyRequest,
    );
    modern = SourceEngine(
      runtime: V8Runtime(),
      requestAdapter: adaptLegacyRequest,
    );
  });
  tearDown(() async {
    await legacy.close();
    await modern.close();
    await server.close(force: true);
  });

  const fields = {
    'name': r'$.url',
    'method': r'$.method',
    'body': r'$.body',
    'category': r'$.category',
    'base': r'$.base',
    'contentType': r'$.contentType',
    'proof': '@js:java.base64Encode("proof")',
  };
  Map<String, Object?> searchSource(String url) => {
    'bookSourceUrl': base.toString(),
    'bookSourceName': '页面表达式本地HTTP样本',
    'searchUrl': url,
    'ruleSearch': fields,
  };
  Map<String, Object?> exploreSource(Object menu) => {
    'bookSourceUrl': base.toString(),
    'bookSourceName': '选中分类独立请求',
    'header': jsonEncode({'X-Source': 'source'}),
    'exploreUrl': menu,
    'ruleExplore': fields,
  };

  Future<List<Map<String, Object?>>> compare(
    Map<String, Object?> original,
    String operation,
    Map<String, Object?> input,
  ) async {
    final imported = LegacySourceImporter().import(original);
    final migration = SourceMigrator().migrate(original);
    expect(imported.issues, isEmpty);
    expect(migration.issues, isEmpty);
    final baseline = await legacy.execute(
      imported.source,
      operation,
      input: input,
    );
    final candidate = SourceDefinition.fromJson(migration.candidate);
    final actual = await modern.execute(candidate, operation, input: input);
    expect(actual, baseline);
    expect(actual.single['proof'], 'cHJvb2Y=');
    return actual;
  }

  for (final (expression, page, expected) in [
    ('page+1', 1, 2),
    ('page-1', 1, 0),
    ('page-2', 1, -1),
    ('page + 12', 3, 15),
    ('page-1', 5, 4),
  ]) {
    test('actual V8 page arithmetic $expression at page $page', () async {
      final original = searchSource('/echo?p={{$expression}}');
      final imported = LegacySourceImporter().import(original);
      expect(imported.source.stages['search']!.legacyPageTemplates, isTrue);
      final rows = await compare(original, 'search', {'page': page});
      expect(rows.single['name'], '/echo?p=$expected');
      expect(requests.map((request) => request['url']), [
        '/echo?p=$expected',
        '/echo?p=$expected',
      ]);
    });
  }

  for (final (page, expected) in [(1, 'a'), (2, 'b'), (3, 'c'), (5, 'c')]) {
    test(
      'actual V8 legacy URL choice selects $expected at page $page',
      () async {
        final rows = await compare(
          searchSource('/echo?choice=<a,b,c>'),
          'search',
          {'page': page},
        );
        expect(rows.single['name'], '/echo?choice=$expected');
        expect(requests.map((request) => request['url']), [
          '/echo?choice=$expected',
          '/echo?choice=$expected',
        ]);
      },
    );
  }

  test(
    'selected A GET and B POST each retain their own request options',
    () async {
      final a =
          '/category/A,${jsonEncode({
            'method': 'GET',
            'headers': {'X-Category': 'A'},
          })}';
      final b =
          '/category/B?p={{page-1}},${jsonEncode({
            'method': 'POST',
            'headers': {'X-Category': 'B'},
            'body': 'q={{key}}&page={{page+1}}',
          })}';
      final original = exploreSource(
        jsonEncode([
          {'title': 'A', 'url': a},
          {'title': 'B', 'url': b},
        ]),
      );
      final first = await compare(original, 'explore', {
        'exploreUrl': a,
        'page': 3,
      });
      expect(first.single['method'], 'GET');
      expect(first.single['body'], '');
      expect(first.single['category'], 'A');
      expect(first.single['base'], 'source');
      final second = await compare(original, 'explore', {
        'exploreUrl': b,
        'page': 3,
        'key': '中文 +',
      });
      expect(second.single['name'], '/category/B?p=2');
      expect(second.single['method'], 'POST');
      expect(second.single['body'], 'q=%E4%B8%AD%E6%96%87+%2B&page=4');
      expect(second.single['category'], 'B');
      expect(second.single['base'], 'source');
      expect(second.single['contentType'], 'application/x-www-form-urlencoded');
      expect(requests.map((request) => request['method']), [
        'GET',
        'GET',
        'POST',
        'POST',
      ]);
      expect(requests.map((request) => request['category']), [
        'A',
        'A',
        'B',
        'B',
      ]);
    },
  );

  for (final selected in [
    '/echo?p={{page*2}}',
    '/echo?p={{Math.max(page,2)}}',
    '/echo?p={{page+01}}',
    '/echo?choice=<{{key}},b>',
    '/echo?choice=<{{page+1}},b>',
    '/echo?choice=<<a,b>,c>',
    '/echo?choice=<a,b',
    '/echo?choice=a,b>',
    '/echo,{"method":"POST","body":"q=x","unknown":true}',
    '/echo,{"method":"POST","body":"q=x","charset":"GBK"}',
  ]) {
    test(
      'selected unsupported category request fails before HTTP: $selected',
      () async {
        final original = exploreSource('A::/echo');
        final imported = LegacySourceImporter().import(original);
        final candidate = SourceDefinition.fromJson(
          SourceMigrator().migrate(original).candidate,
        );
        for (final (engine, definition) in [
          (legacy, imported.source),
          (modern, candidate),
        ]) {
          await expectLater(
            engine.execute(
              definition,
              'explore',
              input: {'exploreUrl': selected, 'page': 2},
            ),
            throwsA(
              isA<EngineException>().having(
                (error) => error.code,
                'code',
                'legacy_request_requires_migration',
              ),
            ),
          );
        }
        expect(requests, isEmpty);
      },
    );
  }

  for (final (label, request, code) in [
    (
      'plain URL placeholder',
      '/search?q={{key}}',
      'legacy_page_requires_migration',
    ),
    ('URL', '/echo?q={{key}}&p={{page+1}}', 'legacy_page_requires_migration'),
    (
      'form body',
      '/echo,{"method":"POST","body":"q={{key}}"}',
      'legacy_body_template_requires_migration',
    ),
  ]) {
    for (final value in ['<a,b>', 'a>b', 'a<b']) {
      test(
        'legacy $label rejects dynamic angle syntax $value before HTTP',
        () async {
          final original = searchSource(request);
          final imported = LegacySourceImporter().import(original);
          final migration = SourceMigrator().migrate(original);
          expect(imported.issues, isEmpty);
          expect(migration.issues, isEmpty);
          final candidate = SourceDefinition.fromJson(migration.candidate);
          for (final (engine, definition) in [
            (legacy, imported.source),
            (modern, candidate),
          ]) {
            await expectLater(
              engine.execute(
                definition,
                'search',
                input: {'key': value, 'page': 2},
              ),
              throwsA(
                isA<EngineException>().having(
                  (error) => error.code,
                  'code',
                  code,
                ),
              ),
            );
          }
          expect(requests, isEmpty);
        },
      );
    }
  }

  for (final page in <Object?>[null, 0, -1, 2.0, '2', 9007199254740992]) {
    test('legacy page input $page fails before HTTP', () async {
      final original = searchSource('/echo?p={{page+1}}');
      final imported = LegacySourceImporter().import(original);
      final candidate = SourceDefinition.fromJson(
        SourceMigrator().migrate(original).candidate,
      );
      for (final (engine, definition) in [
        (legacy, imported.source),
        (modern, candidate),
      ]) {
        await expectLater(
          engine.execute(definition, 'search', input: {'page': page}),
          throwsA(
            isA<EngineException>().having(
              (error) => error.code,
              'code',
              'legacy_page_requires_migration',
            ),
          ),
        );
      }
      expect(requests, isEmpty);
    });
  }

  test(
    'legacy explore requires explicit compatibility request adapter',
    () async {
      final unadapted = SourceEngine(runtime: V8Runtime());
      try {
        final imported = LegacySourceImporter().import(
          exploreSource('A::/echo'),
        );
        await expectLater(
          unadapted.execute(
            imported.source,
            'explore',
            input: {'exploreUrl': '/echo'},
          ),
          throwsA(
            isA<EngineException>().having(
              (error) => error.code,
              'code',
              'legacy_request_adapter_required',
            ),
          ),
        );
        expect(requests, isEmpty);
      } finally {
        await unadapted.close();
      }
    },
  );
}
