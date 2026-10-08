import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:test/test.dart';

class _Host implements ScriptHost {
  @override
  Future<Object?> call(String method, List<Object?> arguments) async => null;
}

class _Runtime implements ScriptRuntime {
  String? code;
  ScriptContext? context;
  @override
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  }) async {
    this.code = code;
    this.context = context;
    return [
      {'name': 'script result'},
    ];
  }

  @override
  Future<void> close() async {}
}

LegacyImport importRule(String rule, {String field = 'name'}) =>
    LegacySourceImporter().import({
      'bookSourceUrl': 'https://rules.test',
      'searchUrl': '/search',
      'ruleSearch': {field: rule},
    });

void main() {
  const html =
      '<p class="a">A1</p><p class="b">B1</p>'
      '<p class="a">A2</p><p class="b">B2</p>';
  test(
    'single CSS operator families execute through the existing core',
    () async {
      final evaluator = RuleEvaluator(_Runtime());
      final context = ScriptContext(host: _Host());
      for (final entry in {
        '.missing@text||.a@text': ['A1', 'A2'],
        '.a@text&&.b@text': ['A1', 'A2', 'B1', 'B2'],
        '.a@text%%.b@text': ['A1', 'B1', 'A2', 'B2'],
      }.entries) {
        final imported = importRule(entry.key);
        expect(imported.requiresManualWork, false, reason: entry.key);
        expect(imported.source.metadata['compatibility'], 'unverified');
        expect(
          await evaluator.evaluate(
            imported.source.stages['search']!.fields['name']!,
            html,
            context,
          ),
          entry.value,
        );
      }
      final list = importRule('.missing||.b', field: 'bookList');
      final values = await evaluator.evaluate(
        list.source.stages['search']!.list!,
        html,
        context,
        elements: true,
      );
      expect(values.map(RuleEvaluator.text), ['B1', 'B2']);
    },
  );

  test('quoted and bracketed operator tokens remain CSS data', () async {
    final imported = importRule('a[data-value="a&&b||c%%d"]@text');
    expect(imported.requiresManualWork, false);
    expect(
      await RuleEvaluator(_Runtime()).evaluate(
        imported.source.stages['search']!.fields['name']!,
        '<a data-value="a&&b||c%%d">literal</a>',
        ScriptContext(host: _Host()),
      ),
      ['literal'],
    );
  });

  test('mixed operators and unsupported leaf dialects remain manual', () {
    for (final rule in [
      '.a@text||.b@text&&.c@text',
      '.a@text%%.b@text||.c@text',
      '.a@text||',
      '.a@text||@XPath://p/text()',
      '.a@text||@js:result',
      '.a@text##a.*##b',
      '.a@text||@get:{value}',
      '.a@all||.b@text',
      '.a@html&&.b@text',
      '//p',
    ]) {
      expect(importRule(rule).requiresManualWork, true, reason: rule);
    }
  });

  test(
    'whole pure V8 list scripts normalize wrappers and stay opaque',
    () async {
      for (final entry in {
        '@JS:JSON.parse(result).items || []': 'JSON.parse(result).items || []',
        ' <JS>result.name && result.author</JS> ':
            'result.name && result.author',
        '@js:const values=JSON.parse(result); values.items;':
            'const values=JSON.parse(result); values.items;',
        '@js:({java:"literal",source:result. source,book:"literal"})':
            '({java:"literal",source:result. source,book:"literal"})',
        '@js:/* java is only a comment */ "java";':
            '/* java is only a comment */ "java";',
      }.entries) {
        final runtime = _Runtime();
        final imported = importRule(entry.key, field: 'bookList');
        expect(imported.requiresManualWork, false, reason: entry.key);
        expect(imported.source.metadata['compatibility'], 'unverified');
        expect(
          await RuleEvaluator(runtime).evaluate(
            imported.source.stages['search']!.list!,
            '{"items":[{"name":"value"}],"source":"literal"}',
            ScriptContext(host: _Host()),
          ),
          [
            {'name': 'script result'},
          ],
        );
        expect(runtime.code, entry.value);
        expect(
          runtime.context!.variables['result'],
          '{"items":[{"name":"value"}],"source":"literal"}',
        );
      }
    },
  );

  test('pure script allowance is list-scoped including toc, never scalar', () {
    final toc = LegacySourceImporter().import({
      'bookSourceUrl': 'https://rules.test',
      'ruleToc': {'chapterList': '@js:JSON.parse(result).items'},
    });
    expect(toc.requiresManualWork, false);
    expect(toc.source.stages['toc']!.list, '@js:JSON.parse(result).items');
    expect(importRule('@js:JSON.parse(result).items').requiresManualWork, true);
  });

  test(
    'host-dependent scripts, embedded JS and pipeline hooks keep issues',
    () {
      for (final rule in [
        '@js:JSON.parse(src).items',
        '@js:java.lang.String.valueOf(result)',
        '@js:Packages.java.lang.String(result)',
        '@js:java.unknownMethod(result)',
        '@js:book.getVariable("key")',
        '@js:source.getKey()',
        '@js:this.java.unknownMethod()',
        '@js:eval("java.lang.String(result)")',
        '@js:"{{value}}"',
        '.a@js:result',
      ]) {
        expect(
          importRule(rule, field: 'bookList').requiresManualWork,
          true,
          reason: rule,
        );
      }
      final pipeline = LegacySourceImporter().import({
        'bookSourceUrl': 'https://rules.test',
        'ruleContent': {'imageDecode': '@js:result'},
      });
      expect(
        pipeline.issues.map((issue) => issue.code),
        contains('legacy.pipeline_requires_review'),
      );
      final library = LegacySourceImporter().import({
        'bookSourceUrl': 'https://rules.test',
        'jsLib': 'function helper(value){return value;}',
        'loginUrl': 'https://rules.test/login',
      });
      expect(library.requiresManualWork, true);
      expect(
        library.issues.map((issue) => issue.path),
        containsAll(['jsLib', 'loginUrl']),
      );
    },
  );
}
