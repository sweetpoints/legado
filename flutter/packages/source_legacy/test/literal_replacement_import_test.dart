import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:test/test.dart';

class _NoScript implements ScriptRuntime, ScriptHost {
  @override
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  }) async => throw StateError('No JS expected');
  @override
  Future<Object?> call(String method, List<Object?> arguments) async =>
      throw StateError('No host call expected');
  @override
  Future<void> close() async {}
}

class _ConfigNetwork extends NetworkClient {
  final calls = <Uri>[];
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
    calls.add(uri);
    return NetworkResponse(
      uri,
      200,
      {},
      uri.path == '/search'
          ? '<div class="book"><h2>Book title</h2><a href="/book/1">book</a></div>'
          : '<p class="content">Chapter text</p>',
    );
  }
}

void main() {
  test(
    'debug keyword and rendering style never enter the extraction pipeline',
    () async {
      final input = <String, Object?>{
        'bookSourceUrl': 'https://books.test',
        'searchUrl': '/search?q={{key}}',
        'ruleSearch': {
          'checkKeyWord': '@js:throw "configuration is not code";',
          'bookList': 'class.book',
          'name': 'tag.h2@text',
          'bookUrl': 'tag.a@href',
        },
        'ruleContent': {
          'imageStyle': '@js:throw "rendering configuration is not code";',
          'content': 'class.content@text',
        },
      };
      final imported = LegacySourceImporter().import(input);
      expect(imported.issues, isEmpty);
      expect(
        imported.source.stages['search']!.fields,
        isNot(contains('checkKeyWord')),
      );
      expect(
        imported.source.stages['content']!.fields,
        isNot(contains('imageStyle')),
      );
      expect(imported.original, input);
      expect(imported.source.metadata['legacyOriginal'], input);
      final network = _ConfigNetwork();
      final engine = SourceEngine(runtime: _NoScript(), network: network);
      try {
        final search = await engine.execute(
          imported.source,
          'search',
          input: {'key': 'reader'},
        );
        expect(search, [
          {'name': 'Book title', 'bookUrl': 'https://books.test/book/1'},
        ]);
        final content = await engine.execute(
          imported.source,
          'content',
          input: {'chapterUrl': 'https://books.test/chapter'},
        );
        expect(content, [
          {'content': 'Chapter text'},
        ]);
        expect(network.calls.map((uri) => uri.path), ['/search', '/chapter']);
        expect(network.calls.first.queryParameters['q'], 'reader');
      } finally {
        await engine.close();
      }
    },
  );

  test('configuration preserves every JSON shape accepted by old StringJsonDeserializer', () {
    for (final value in <Object?>[
      null,
      'FULL',
      123,
      true,
      {'style': 'FULL'},
      ['FULL'],
    ]) {
      for (final mainJs in [false, true]) {
        final input = <String, Object?>{
          'bookSourceUrl': 'https://books.test',
          if (mainJs) 'mainJs': 'function getContent(){return "text";}',
          'ruleSearch': {'checkKeyWord': value},
          'ruleContent': {'imageStyle': value},
        };
        final imported = LegacySourceImporter().import(input);
        expect(imported.issues, isEmpty, reason: '$value / $mainJs');
        expect(imported.original, input);
        expect(imported.source.metadata['legacyOriginal'], input);
        expect(
          imported.source.stages.values.expand((stage) => stage.fields.keys),
          isNot(anyOf(contains('checkKeyWord'), contains('imageStyle'))),
        );
      }
    }
  });

  test('configuration names are scoped and genuine hooks stay manual', () {
    for (final stage in ['ruleExplore', 'ruleBookInfo']) {
      final imported = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        stage: {'checkKeyWord': 'keyword'},
      });
      expect(
        imported.issues.map((issue) => issue.code),
        contains('legacy.pipeline_requires_review'),
      );
    }
    for (final hook in [
      'init',
      'replaceRegex',
      'imageDecode',
      'payAction',
      'callBackJs',
    ]) {
      final imported = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'ruleContent': {hook: 'script'},
      });
      expect(
        imported.issues.map((issue) => issue.code),
        contains('legacy.pipeline_requires_review'),
        reason: hook,
      );
    }
  });

  test(
    'literal scalar replacements preserve rules without claiming verified',
    () async {
      final runtime = _NoScript();
      for (final entry in <String, List<Object?>>{
        'p@text##旧##新': ['新新', '新文'],
        'p@text##旧##': ['', '文'],
        '@CSS:p@text##旧##新': ['新新', '新文'],
        'p@ownText##旧##新': ['新新', '新文'],
        'p@textNodes##旧##新': ['新新', '新文'],
      }.entries) {
        final imported = LegacySourceImporter().import({
          'bookSourceUrl': 'https://books.test',
          'searchUrl': '/search',
          'ruleSearch': {'bookList': 'p', 'name': entry.key},
        });
        expect(imported.issues, isEmpty, reason: entry.key);
        expect(imported.source.metadata['compatibility'], 'unverified');
        expect(imported.original['ruleSearch'], {
          'bookList': 'p',
          'name': entry.key,
        });
        final rule = imported.source.stages['search']!.fields['name']!;
        final result = await RuleEvaluator(runtime)
            .evaluate(rule, '<p>旧旧</p><p>旧文</p>', ScriptContext(host: runtime));
        expect(result, entry.value);
      }
    },
  );
  test('next page list replacement remains manual', () {
    final imported = LegacySourceImporter().import({
      'bookSourceUrl': 'https://books.test',
      'ruleToc': {'chapterList': 'a', 'nextTocUrl': 'a.next@text##old##new'},
    });
    expect(imported.issues.single.code, 'legacy.rule_requires_review');
    expect(
      imported.source.stages['toc']!.nextPage,
      '@legacy:a.next@text##old##new',
    );
  });
  test('regex dynamic malformed and entity replacements stay manual', () {
    for (final rule in [
      'p@text##old##new###',
      'p@text####new',
      '##old##new',
      r'p@text##o.*##new',
      r'p@text##[ab]##new',
      r'p@text##old##$1',
      r'p@text##old##\n',
      'p@text##old##&amp;',
      'p@text##old##{{key}}',
      'p@text##old##a\nb',
      'p@text##o\nld##new',
      'p@text##old##"new"',
      'p@text##old##new##more',
      '@js:return result##old##new',
      '@XPath://p##old##new',
      'p@text||a@text##old##new',
      '@get:variable##old##new',
      ':old##old##new',
      '@unknown:p##old##new',
      r'$.name##old##new',
      r'@json:$.name##old##new',
      '@legacy:@XPath://p##old##new',
      '@legacy:@regex:old##old##new',
      '@legacy::old##old##new',
      '@legacy://p##old##new',
      '@legacy:@unknown:p@text##old##new',
      'p@html##old##new',
      'p@all##old##new',
      'p@children##old##new',
      'a@href##old##new',
      'p##old##new',
    ]) {
      final imported = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'searchUrl': '/search',
        'ruleSearch': {'name': rule},
      });
      expect(imported.requiresManualWork, true, reason: rule);
    }
  });
  test('list and pipeline hooks do not gain replacement compatibility', () {
    final imported = LegacySourceImporter().import({
      'bookSourceUrl': 'https://books.test',
      'searchUrl': '/search',
      'ruleSearch': {'bookList': 'a##old##new'},
      'ruleContent': {'imageDecode': 'p@text##old##new'},
    });
    expect(
      imported.issues.any(
        (i) =>
            i.path == 'ruleSearch.bookList' &&
            i.code == 'legacy.rule_requires_review',
      ),
      true,
    );
    expect(
      imported.issues.any(
        (i) =>
            i.path == 'ruleContent.imageDecode' &&
            i.code == 'legacy.pipeline_requires_review',
      ),
      true,
    );
  });
  test(
    'mainJs still reports App side content hooks without flagging stage fields',
    () {
      for (final hook in ['imageDecode', 'payAction', 'callBackJs']) {
        final imported = LegacySourceImporter().import({
          'bookSourceUrl': 'https://books.test',
          'mainJs': 'function getContent() { return "text"; }',
          'ruleContent': {
            'content': '@js:throw "owned by mainJs";',
            hook: 'script',
          },
        });
        expect(imported.issues.map((i) => i.path), ['ruleContent.$hook']);
        expect(imported.issues.single.code, 'legacy.pipeline_requires_review');
        expect(imported.source.metadata['compatibility'], 'manualRequired');
        expect(imported.source.script, isNotNull);
      }
      final clean = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'mainJs': 'function getContent() { return "text"; }',
        'ruleContent': {
          'content': '@js:throw "ignored";',
          'imageStyle': null,
          'imageDecode': '',
          'payAction': '',
          'callBackJs': null,
        },
      });
      expect(clean.issues, isEmpty);
      expect(clean.source.metadata['compatibility'], 'unverified');
    },
  );
  test('mainJs content hook containers and values must still be valid', () {
    for (final content in ['not a rule object', 42, []]) {
      final imported = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'mainJs': 'function getContent() { return "text"; }',
        'ruleContent': content,
      });
      expect(imported.issues.single.code, 'legacy.invalid_rule_object');
      expect(imported.source.metadata['compatibility'], 'manualRequired');
    }
    final invalid = LegacySourceImporter().import({
      'bookSourceUrl': 'https://books.test',
      'mainJs': 'function getContent() { return "text"; }',
      'ruleContent': {'imageDecode': 42},
    });
    expect(invalid.issues.single.code, 'legacy.invalid_rule');
    for (final content in [null, <String, Object?>{}]) {
      final clean = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'mainJs': 'function getContent() { return "text"; }',
        'ruleContent': content,
      });
      expect(clean.issues, isEmpty);
    }
  });
  test(
    'only known legacy scalar field call sites admit literal replacement',
    () {
      for (final entry in {
        'ruleSearch': 'name',
        'ruleExplore': 'author',
        'ruleBookInfo': 'intro',
        'ruleToc': 'chapterName',
      }.entries) {
        final imported = LegacySourceImporter().import({
          'bookSourceUrl': 'https://books.test',
          'searchUrl': '/search',
          entry.key: {entry.value: 'p@text##old##new'},
        });
        expect(imported.issues, isEmpty, reason: entry.key);
      }
      for (final entry in {
        'ruleSearch': ['kind', 'unknown', 'bookList'],
        'ruleBookInfo': ['kind', 'downloadUrls', 'unknown'],
        'ruleToc': ['nextTocUrl', 'chapterList', 'unknown'],
        'ruleContent': ['content', 'nextContentUrl', 'unknown'],
      }.entries) {
        for (final field in entry.value) {
          final imported = LegacySourceImporter().import({
            'bookSourceUrl': 'https://books.test',
            'searchUrl': '/search',
            entry.key: {field: 'p@text##old##new'},
          });
          expect(
            imported.issues.any(
              (i) =>
                  i.path == '${entry.key}.$field' &&
                  i.code == 'legacy.rule_requires_review',
            ),
            true,
            reason: '${entry.key}.$field',
          );
        }
      }
    },
  );
}
