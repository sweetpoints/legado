import 'package:source_engine/source_engine.dart';
import 'package:source_v8/source_v8.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:test/test.dart';

void main() {
  late SourceEngine engine;
  setUp(() => engine = SourceEngine(runtime: V8Runtime()));
  tearDown(() => engine.close());

  Future<Map<String, Object?>> run(
    String script, {
    CancellationToken? cancellation,
  }) async {
    final source = SourceDefinition(
      id: 'parse',
      name: '解析测试',
      baseUrl: Uri.parse('https://fixture.invalid/base/'),
      script: 'async function search(){ $script }',
    );
    return (await engine.execute(
      source,
      'search',
      cancellation: cancellation,
    )).single;
  }

  test(
    'V8 async parse handles CSS XPath JSONPath and legacy scalar/list values',
    () async {
      final result = await run(r'''
      const html='<section><a href="one"> Alpha <b>Beta</b></a><a href="two">Second</a></section>';
      return {
        css:await source.parse.getString('@css:a@text',html),
        xpath:await source.parse.getStringList('@xpath://a/@href',html),
        json:await source.parse.getStringList('$.items[*].name',{items:[{name:'first'},{name:'second'}]}),
        legacy:await source.parse.getString('@legacy:tag.a@text',html),
        urls:await source.parse.getStringList('@legacy:tag.a@href',html,true),
        explicitBase:await source.parse.getString('@legacy:tag.a.0@href',html,true,'https://other.invalid/books/'),
        empty:await source.parse.getStringList('',html)
      };
    ''');
      expect(result, {
        'css': ' Alpha Beta\nSecond',
        'xpath': ['one', 'two'],
        'json': ['first', 'second'],
        'legacy': 'Alpha Beta\nSecond',
        'urls': [
          'https://fixture.invalid/base/one',
          'https://fixture.invalid/base/two',
        ],
        'explicitBase': 'https://other.invalid/books/one',
        'empty': [],
      });
    },
  );

  test(
    'new parse element APIs expose JSON data or HTML strings explicitly',
    () async {
      final result = await run(r'''
      const elements=await source.parse.getElements('@legacy:tag.a','<a href="one">First</a><a href="two">Second</a>');
      return {elements, first:await source.parse.getElement('$.items[*]',{items:[{name:'first'},{name:'second'}]})};
    ''');
      expect(result, {
        'elements': ['<a href="one">First</a>', '<a href="two">Second</a>'],
        'first': {'name': 'first'},
      });
    },
  );

  test('nested JS parse rejection is reported without reentering V8', () async {
    final result = await run(r'''
      const errors=[];
      for(const rule of ['@js:result','<js>result</js>']) {
        try { await source.parse.getString(rule,'input'); errors.push(false); }
        catch(e) {errors.push(String(e).includes('unsupported_rule'));}
      }
      return {errors};
    ''');
    expect(result, {
      'errors': [true, true],
    });
  });

  test('pre-cancelled parse task fails with cancellation', () async {
    final token = CancellationToken()..cancel();
    await expectLater(
      run(
        'return {value:await source.parse.getString("@css:p@text","<p>value</p>")};',
        cancellation: token,
      ),
      throwsA(
        isA<EngineException>().having((e) => e.code, 'code', 'cancelled'),
      ),
    );
  });
  test('legacy parsing defaults to result and keeps explicit content and URL overloads', () async {
    await engine.close();
    engine = SourceEngine(
      runtime: V8Runtime(prelude: legacyScriptPrelude),
      hostAdapter: LegacyScriptHost.new,
    );
    final result = await run(r'''
      globalThis.result='<a href="one">First</a><a href="two">Second</a>';
      return {defaultText:java.getString('tag.a@text'),
        explicit:java.getString('tag.p@text','<p>Other</p>'),
        texts:java.getStringList('tag.a@text'),
        urls:java.getStringList('tag.a@href',null,true),
        json:java.getStringList('$.names[*]','{"names":["a","b"]}'),
        empty:java.getStringList('')};
    ''');
    expect(result, {
      'defaultText': 'First\nSecond',
      'explicit': 'Other',
      'texts': ['First', 'Second'],
      'urls': [
        'https://fixture.invalid/base/one',
        'https://fixture.invalid/base/two',
      ],
      'json': ['a', 'b'],
      'empty': null,
    });
  });
  test(
    'legacy element facades preserve supported object methods and reject html',
    () async {
      await engine.close();
      engine = SourceEngine(
        runtime: V8Runtime(prelude: legacyScriptPrelude),
        hostAdapter: LegacyScriptHost.new,
      );
      final result = await run(r'''
      globalThis.result='<div><a href="one">First <b>bold</b></a><a href="two">Second</a></div>';
      const list=java.getElements('tag.a');
      const one=java.getElement('tag.a.0');
      let rejected=false;try { one.html(); } catch(e) {rejected=String(e).includes('unsupported_element_api');}
      return {size:list.size(),first:list.first().text(),last:list.last().attr('href'),
        index:list.get(1).text(),text:list.text(),attr:list.attr('href'),
        nested:one.selectFirst('b').text(),selected:list.select('b').text(),
        html:one.outerHtml(),transport:one,rejected};
    ''');
      expect(result, {
        'size': 2,
        'first': 'First bold',
        'last': 'two',
        'index': 'Second',
        'text': 'First bold Second',
        'attr': 'one',
        'nested': 'bold',
        'selected': 'bold',
        'html': '<a href="one">First <b>bold</b></a>',
        'transport': '<a href="one">First <b>bold</b></a>',
        'rejected': true,
      });
    },
  );
}
