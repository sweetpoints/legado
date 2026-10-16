import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:test/test.dart';

class _Runtime implements AuxiliaryScriptRuntime {
  String? script;
  ScriptContext? context;
  Object? output = 'done';
  bool cancelBeforeReturn = false;
  @override
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  }) async {
    script = code;
    this.context = context;
    return output;
  }

  @override
  Future<Object?> evaluateAuxiliary(
    String code,
    ScriptContext context, {
    String prelude = '',
    CancellationToken? cancellation,
  }) async {
    script = code;
    this.context = context;
    if (cancelBeforeReturn) cancellation?.cancel();
    return output;
  }

  @override
  Future<ScriptDiagnostic?> checkSyntax(
    String code, {
    CancellationToken? cancellation,
  }) async => null;
  @override
  Future<void> close() async {}
}

class _Host implements ScriptHost {
  @override
  Future<Object?> call(String method, List<Object?> args) async =>
      throw StateError(method);
}

void main() {
  final source = SourceDefinition(
    id: 'fixture',
    name: 'Fixture',
    baseUrl: Uri.parse('https://fixture.invalid/'),
  );
  final context = ScriptContext(
    host: _Host(),
    variables: {'baseUrl': 'https://fixture.invalid/book/'},
  );
  Future<List<Object?>> run(String rule, Object input, {bool scalar = true}) =>
      PortableLegacyRuleEvaluator(_Runtime()).evaluate(
        rule,
        input,
        context,
        source: source,
        operation: 'info',
        scalar: scalar,
      );
  test('legacy regex absent/empty replacement and non-greedy groups retain Java behavior', () async {
    expect(
      await run(
        'p@text##<.*?>',
        '<p>&lt;a&gt;one&lt;/a&gt; &lt;b&gt;two&lt;/b&gt;</p>',
      ),
      ['one two'],
    );
    expect(await run('p@text##prefix:##', '<p>prefix:value</p>'), ['value']);
    expect(await run(r'p@text##(a)(b)##$2$1', '<p>ab ab</p>'), ['ba ba']);
    expect(
      await run(
        r'p@text##(?<!\[)[\u4e00-\u9fa5]+(?!\])',
        '<p>Fixture [玄幻]</p>',
      ),
      ['Fixture [玄幻]'],
    );
  });
  test('multiple at selectors and quoted punctuation are preserved', () async {
    expect(
      await run(
        'section@a.0@text',
        '<section><a>First</a><a>Second</a></section>',
      ),
      ['First'],
    );
    expect(
      await run(
        '[data-note="##@js:"]@text',
        '<p data-note="##@js:">literal</p>',
      ),
      ['literal'],
    );
  });
  test('CSS regex script newline chain uses opaque program completion and exact result', () async {
    final runtime = _Runtime();
    const suffix =
        '\nconst punctuation = "##@js:java.source";\nresult + punctuation;';
    expect(
      await PortableLegacyRuleEvaluator(runtime).evaluate(
        'p@text##x##y\n@js:$suffix',
        '<p>x</p>',
        context,
        source: source,
        operation: 'info',
        scalar: true,
      ),
      ['done'],
    );
    expect(runtime.script, suffix);
    expect(runtime.context!.variables['result'], 'y');
    expect(runtime.context!.variables['src'], '<p>x</p>');
  });
  test('scalar replacement runs after joining legacy string fields', () async {
    expect(await run(r'p@text##a\nb##joined', '<p>a</p><p>b</p>'), ['joined']);
  });
  test('malformed or unimplemented token families remain unsupported', () {
    for (final rule in [
      'p@text##[',
      'p@text##x##y##z',
      'p@text@js:',
      '[data-x="broken]@text',
      '<js>result;',
      'p@text##(?i)x##',
      '@get:{host}/{{@href}}',
      '@js:org.jsoup.Jsoup.parse(result)',
      '@js:java.ajax(baseUrl)',
      '@js:globalThis["java"]',
      '@webjs:document.title',
    ]) {
      expect(
        PortableLegacyRuleEvaluator.canEvaluate(rule),
        false,
        reason: rule,
      );
    }
  });
  test('cancellation propagates after auxiliary runtime evaluation', () async {
    final runtime = _Runtime()..cancelBeforeReturn = true;
    final token = CancellationToken();
    await expectLater(
      PortableLegacyRuleEvaluator(runtime).evaluate(
        '@js:result;',
        'value',
        context,
        source: source,
        operation: 'info',
        cancellation: token,
      ),
      throwsA(
        isA<EngineException>().having((e) => e.code, 'code', 'cancelled'),
      ),
    );
  });
}
