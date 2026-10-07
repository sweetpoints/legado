import 'dart:convert';

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
    engine = SourceEngine(runtime: V8Runtime());
  });
  tearDown(() async {
    await engine.close();
    await server.close();
  });

  SourceDefinition source() => SourceDefinition(
    id: 'fixture',
    name: '固定测试书源',
    baseUrl: server.baseUrl,
    stages: {
      'search': const SourceStage(
        url: '/search?q={{key}}',
        list: '@css:article.book',
        fields: {
          'name': '@css:a.title@text',
          'author': '@css:.author@text',
          'bookUrl': '@css:a.title@href',
        },
      ),
      'info': const SourceStage(
        url: '{{bookUrl}}',
        fields: {'name': '@css:h1@text', 'tocUrl': '@css:a.toc@href'},
      ),
      'toc': const SourceStage(
        url: '{{tocUrl}}',
        list: '@css:li',
        fields: {'name': '@css:a@text', 'chapterUrl': '@css:a@href'},
      ),
      'content': const SourceStage(
        url: '{{chapterUrl}}',
        fields: {'content': '@css:main@text'},
      ),
    },
  );

  test('search to正文 passes session and resolves relative stage URLs', () async {
    final books = await engine.execute(
      source(),
      'search',
      input: {'key': '测试'},
    );
    expect(books, hasLength(1));
    expect(books.single['name'], '测试书籍');
    expect(books.single['author'], '作者甲');
    expect(books.single['bookUrl'], server.baseUrl.resolve('/book').toString());
    final details = await engine.execute(source(), 'info', input: books.single);
    expect(details.single['tocUrl'], server.baseUrl.resolve('/toc').toString());
    final chapters = await engine.execute(
      source(),
      'toc',
      input: details.single,
    );
    expect(chapters.map((c) => c['name']), ['第一章', '第二章']);
    final content = await engine.execute(
      source(),
      'content',
      input: chapters.first,
    );
    expect(content.single['content'], '第一章正文：你好，世界。');
  });

  test('real V8 field awaits Dart HTTP host request', () async {
    final scripted = SourceDefinition(
      id: 'async',
      name: '异步书源',
      baseUrl: server.baseUrl,
      stages: const {
        'search': SourceStage(
          url: '/search',
          fields: {
            'name': '@js:JSON.parse(await source.net.get("/value")).value',
          },
        ),
      },
    );
    final results = await engine.execute(scripted, 'search');
    expect(results.single['name'], '异步结果');
    expect(server.requests, ['/search', '/value']);
  });

  test(
    'migration keeps original and produces executable basic source',
    () async {
      final original = <String, Object?>{
        'bookSourceUrl': server.baseUrl.toString(),
        'bookSourceName': '旧版书源',
        'searchUrl': '/search?q={{key}}',
        'ruleSearch': {
          'bookList': 'article.book',
          'name': 'a.title@text',
          'author': '.author@text',
          'bookUrl': 'a.title@href',
        },
      };
      final before = jsonEncode(original);
      final imported = LegacySourceImporter().import(original);
      final report = SourceMigrator().migrate(original);
      expect(jsonEncode(original), before);
      expect(report.original, original);
      expect(report.toJson()['verified'], isFalse);
      final migrated = SourceDefinition.fromJson(report.candidate);
      final oldResult = await engine.execute(
        imported.source,
        'search',
        input: {'key': '测试'},
      );
      final newResult = await engine.execute(
        migrated,
        'search',
        input: {'key': '测试'},
      );
      expect(newResult, oldResult);
      expect(newResult.single['name'], '测试书籍');
    },
  );

  test(
    'migrated ajax script executes asynchronously through real V8',
    () async {
      final migration = SourceMigrator().migrateScript(
        'return java.ajax("${server.baseUrl}/value");',
      );
      expect(migration.issues, isEmpty);
      expect(migration.candidate, isNotNull);
      final scripted = SourceDefinition(
        id: 'migrated-js',
        name: '迁移脚本',
        baseUrl: server.baseUrl,
        stages: {
          'search': SourceStage(
            url: '/search',
            fields: {'response': '@js:${migration.candidate}'},
          ),
        },
      );
      final result = await engine.execute(scripted, 'search');
      expect(jsonDecode(result.single['response'] as String), {
        'value': '异步结果',
      });
    },
  );
  test('unsupported Java interop requires manual migration without losing original', () {
    const script = 'return Packages.java.lang.System.getProperty("name");';
    final report = SourceMigrator().migrateScript(script);
    expect(report.original, script);
    expect(report.candidate, isNull);
    expect(report.requiresManualWork, isTrue);
    expect(report.issues, isNotEmpty);
  });
  test(
    'legacy chained list selects elements before evaluating row fields',
    () async {
      final definition = SourceDefinition(
        id: 'legacy-chain',
        name: '旧列表链',
        baseUrl: server.baseUrl,
        stages: const {
          'search': SourceStage(
            url: '/search',
            list: '@legacy:class.book@tag.a',
            fields: {'name': '@legacy:text', 'bookUrl': '@legacy:href'},
          ),
        },
      );
      final result = await engine.execute(definition, 'search');
      expect(result, [
        {'name': '测试书籍', 'bookUrl': server.baseUrl.resolve('/book').toString()},
      ]);
    },
  );
  test(
    'redirected stage uses final page URL for legacy parse URL resolution',
    () async {
      await engine.close();
      engine = SourceEngine(
        runtime: V8Runtime(prelude: legacyScriptPrelude),
        hostAdapter: LegacyScriptHost.new,
      );
      final source = SourceDefinition(
        id: 'redirect-parse',
        name: '重定向解析',
        baseUrl: server.baseUrl,
        stages: const {
          'search': SourceStage(
            url: '/jump',
            fields: {
              'chapterUrl': '@js:java.getStringList("tag.a@href",null,true)',
              'page': '@js:baseUrl',
            },
          ),
        },
      );
      final result = await engine.execute(source, 'search');
      expect(result, [
        {
          'chapterUrl': server.baseUrl.resolve('/book/chapter/1').toString(),
          'page': server.baseUrl.resolve('/book/123').toString(),
        },
      ]);
    },
  );
  test(
    'source headers inherit into JS requests and explicit headers replace them',
    () async {
      final definition = SourceDefinition(
        id: 'headers',
        name: '请求头',
        baseUrl: server.baseUrl,
        headers: const {'X-Request': 'source'},
        script: r'''
        async function search(){
          const first=await source.net.request({url:'/echo'});
          const second=await source.net.request({url:'/echo',headers:{'X-Request':'explicit'}});
          const third=await source.net.request({url:'/echo',inheritHeaders:false});
          return {inherited:JSON.parse(first.body).header,explicit:JSON.parse(second.body).header,
            removed:JSON.parse(third.body).header};
        }
      ''',
      );
      expect(await engine.execute(definition, 'search'), [
        {'inherited': 'source', 'explicit': 'explicit', 'removed': null},
      ]);
    },
  );
  test('legacy literal POST options preserve body and merged headers after migration', () async {
    await engine.close();
    engine = SourceEngine(
      runtime: V8Runtime(prelude: legacyScriptPrelude),
      hostAdapter: LegacyScriptHost.new,
    );
    final options = jsonEncode({
      'method': 'post',
      'headers': {'X-Extra': true, 'X-Replace': 'new'},
      'body': jsonEncode({'key': '{{key}}'}),
    });
    final original = <String, Object?>{
      'bookSourceUrl': server.baseUrl.toString(),
      'bookSourceName': '旧POST书源',
      'header': jsonEncode({'X-Auth': 'base', 'X-Replace': 'old'}),
      'searchUrl': '/echo-options,$options',
      'ruleSearch': {
        'name': r'$.body',
        'method': r'$.method',
        'auth': r'$.auth',
        'replace': r'$.replace',
        'extra': r'$.extra',
        'contentType': r'$.contentType',
        'proof': '@js:java.base64Encode("proof")',
      },
    };
    final imported = LegacySourceImporter().import(original);
    final migration = SourceMigrator().migrate(original);
    expect(imported.issues, isEmpty);
    expect(migration.issues, isEmpty);
    final oldFormat = await engine.execute(
      imported.source,
      'search',
      input: {'key': '中文'},
    );
    final newFormat = await engine.execute(
      SourceDefinition.fromJson(migration.candidate),
      'search',
      input: {'key': '中文'},
    );
    expect(newFormat, oldFormat);
    final echoed = newFormat.single;
    expect(echoed['method'], 'POST');
    expect(jsonDecode(echoed['name'] as String), {'key': '中文'});
    expect(echoed['auth'], 'base');
    expect(echoed['replace'], 'new');
    expect(echoed['extra'], 'true');
    expect(echoed['contentType'], 'application/json; charset=UTF-8');
    expect(echoed['proof'], 'cHJvb2Y=');
    expect(server.requests, ['/echo-options', '/echo-options']);
  });
}
