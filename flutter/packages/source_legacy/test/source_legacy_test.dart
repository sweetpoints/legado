import 'package:test/test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';

class _Host implements ScriptHost {
  @override
  Future<Object?> call(String method, List<Object?> arguments) async => method;
}

void main() {
  test('legacy import preserves original and never claims verified', () {
    final input = <String, Object?>{
      'bookSourceUrl': 'https://books.test',
      'bookSourceName': 'Test',
      'searchUrl': '/search?q={{key}}',
      'ruleSearch': {'bookList': '.book', 'name': '.title@text'},
    };
    final result = LegacySourceImporter().import(input);
    expect(result.source.stages['search']!.list, '@css:.book');
    expect(result.source.stages['search']!.fields['name'], '@css:.title@text');
    expect(result.original, input);
    expect(result.source.metadata['compatibility'], 'unverified');
    input['bookSourceName'] = 'changed';
    expect(result.original['bookSourceName'], 'Test');
  });
  test('JSoup shorthand never silently becomes supported CSS', () {
    for (final rule in [
      'class.book@tag.a@text',
      'tag.div.0@text',
      'textNodes',
      '.book.0@text',
      'a@ownText',
    ]) {
      final result = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'ruleContent': {'content': rule},
      });
      expect(result.requiresManualWork, true, reason: rule);
      expect(result.issues.map((e) => e.code), contains('legacy.jsoup_dsl'));
    }
  });
  test('unknown features require review', () {
    final result = LegacySourceImporter().import({
      'bookSourceUrl': 'https://books.test',
      'mainJs': 'java.lang.String',
      'ruleContent': {'content': '@js:java.ajax(url)'},
    });
    expect(result.requiresManualWork, isTrue);
    expect(
      result.issues.map((e) => e.code),
      contains('legacy.capability_requires_review'),
    );
  });
  test('toc canonical fields and pagination are mapped', () {
    final result = LegacySourceImporter().import({
      'bookSourceUrl': 'https://books.test',
      'ruleToc': {
        'chapterList': '.row',
        'chapterName': 'a@text',
        'chapterUrl': 'a@href',
        'nextTocUrl': 'a.next@href',
      },
    });
    expect(result.source.stages['toc']!.fields['title'], '@css:a@text');
    expect(result.source.stages['toc']!.fields['url'], '@css:a@href');
    expect(result.source.stages['toc']!.nextPage, '@css:a.next@href');
  });
  test('simple sync legacy scripts remain explicitly unverified', () {
    final result = LegacySourceImporter().import({
      'bookSourceUrl': 'https://books.test',
      'ruleContent': {'content': '@js:return java.base64Decode(result);'},
    });
    expect(result.requiresManualWork, false);
    expect(result.source.metadata['compatibility'], 'unverified');
  });
  test('invalid source URL fails rather than using relative URL', () {
    expect(
      () => LegacySourceImporter().import({'bookSourceUrl': 'relative'}),
      throwsFormatException,
    );
  });
  test('known utility adapters and unsupported sync requests', () async {
    final host = LegacyScriptHost(_Host());
    final encoded = await host.call('java.base64Encode', ['中文']);
    expect(await host.call('java.base64Decode', [encoded]), '中文');
    expect(await host.call('java.put', ['a', 'b']), 'b');
    expect(await host.call('java.get', ['a']), 'b');
    expect(await host.call('java.ajax', ['https://books.test']), 'net.get');
    expect(
      await host.call('java.md5Encode', ['abc']),
      '900150983cd24fb0d6963f7d28e17f72',
    );
    await expectLater(
      host.call('java.get', ['url', {}]),
      throwsUnsupportedError,
    );
    await expectLater(host.call('java.unknown', []), throwsUnsupportedError);
    await expectLater(
      host.call('java.base64Decode', ['!!!']),
      throwsFormatException,
    );
  });
}
