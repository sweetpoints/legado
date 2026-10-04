import 'package:test/test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';

class _Host implements ScriptHost {
  @override
  Future<Object?> call(String method, List<Object?> arguments) async =>
      method == 'net.request'
      ? {
          'url': (arguments[0] as Map)['url'],
          'status': 200,
          'headers': <String, String>{},
          'body': 'ok',
        }
      : method;
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
    expect(result.source.stages['search']!.list, '@legacy:.book');
    expect(
      result.source.stages['search']!.fields['name'],
      '@legacy:.title@text',
    );
    expect(result.original, input);
    expect(result.source.metadata['compatibility'], 'unverified');
    input['bookSourceName'] = 'changed';
    expect(result.original['bookSourceName'], 'Test');
  });
  test('JSoup shorthand uses explicit legacy dialect', () {
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
      expect(
        result.source.stages['content']!.fields['content'],
        '@legacy:$rule',
      );
      expect(result.source.metadata['compatibility'], 'unverified');
    }
  });
  test('field aliases and disabled cookie policy are explicit', () {
    final result = LegacySourceImporter().import({
      'bookSourceUrl': 'https://books.test',
      'searchUrl': '/search',
      'enabledCookieJar': false,
      'ruleSearch': {'bookList': 'class.book', 'lastChapter': 'tag.span@text'},
    });
    expect(
      result.source.stages['search']!.fields['latestChapterTitle'],
      '@legacy:tag.span@text',
    );
    expect(
      result.issues.map((e) => e.code),
      contains('legacy.cookie_policy_requires_review'),
    );
  });
  test('explicit CSS and leading outputs preserve legacy semantics', () {
    for (final rule in ['@text', '@ownText', '@html', '@all']) {
      final result = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'ruleContent': {'content': rule},
      });
      expect(
        result.source.stages['content']!.fields['content'],
        '@legacy:$rule',
      );
    }
    final result = LegacySourceImporter().import({
      'bookSourceUrl': 'https://books.test',
      'ruleContent': {'content': '@CSS:article@html'},
    });
    expect(
      result.source.stages['content']!.fields['content'],
      '@legacy:article@html',
    );
  });
  test('static source headers import and dynamic ones stay manual', () {
    for (final header in [
      '{"X-Token":"token"}',
      <String, Object?>{'X-Token': 'token'},
    ]) {
      final imported = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'header': header,
      });
      expect(imported.source.headers, {'X-Token': 'token'});
      expect(imported.requiresManualWork, false);
    }
    final imported = LegacySourceImporter().import({
      'bookSourceUrl': 'https://books.test',
      'header': '@js:java.get("token")',
    });
    expect(imported.requiresManualWork, true);
    expect(
      imported.issues.map((e) => e.code),
      contains('legacy.dynamic_header'),
    );
  });
  test(
    'literal URL options preserve static requests and merge source headers',
    () {
      final input = <String, Object?>{
        'bookSourceUrl': 'https://books.test',
        'header': {'X-Auth': 'base', 'X-Replace': 'old'},
        'searchUrl': '/search,{"method":"post","headers":{"X-Extra":true,"X-Replace":"new"},"body":{"key":"{{key}}"}}',
        'ruleSearch': {'bookList': 'class.book'},
      };
      final imported = LegacySourceImporter().import(input);
      final stage = imported.source.stages['search']!;
      expect(stage.url, '/search');
      expect(stage.method, 'POST');
      expect(stage.body, '{"key":"{{key}}"}');
      expect(stage.headers, {
        'X-Auth': 'base',
        'X-Replace': 'new',
        'X-Extra': 'true',
        'Content-Type': 'application/json; charset=UTF-8',
      });
      expect(imported.requiresManualWork, false);
      expect(imported.original, input);
    },
  );
  test('fixed form encodes literals and preserves valid encoded parts', () {
    final imported = LegacySourceImporter().import({
      'bookSourceUrl': 'https://books.test',
      'searchUrl': '/search,{"method":"POST","body":"key=中文&space=a b&plus=a+b&encoded=%E4%B8%AD"}',
      'ruleSearch': {'bookList': 'class.book'},
    });
    expect(
      imported.source.stages['search']!.body,
      'key=%E4%B8%AD%E6%96%87&space=a+b&plus=a%2Bb&encoded=%E4%B8%AD',
    );
    expect(imported.requiresManualWork, false);
  });
  test('unsupported URL options stay manual and charset never changes response decoder', () {
    for (final options in [
      '{"charset":"GBK"}',
      '{"webView":true}',
      '{"retry":1}',
      '{"bodyJs":"result"}',
      '{"headers":{"X":{"nested":true}}}',
      '{"method":"POST","body":"key={{key}}"}',
      '{broken}',
    ]) {
      final imported = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'searchUrl': '/search,$options',
        'ruleSearch': {'bookList': 'class.book'},
      });
      expect(imported.requiresManualWork, true, reason: options);
      expect(imported.source.stages['search']!.charset, isNull);
    }
  });
  test('commas in a plain URL are not misclassified as options', () {
    final imported = LegacySourceImporter().import({
      'bookSourceUrl': 'https://books.test',
      'searchUrl': '/search?keys=a,b',
      'ruleSearch': {'bookList': 'class.book'},
    });
    expect(imported.requiresManualWork, false);
    expect(imported.source.stages['search']!.url, '/search?keys=a,b');
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
    expect(result.source.stages['toc']!.fields['title'], '@legacy:a@text');
    expect(result.source.stages['toc']!.fields['url'], '@legacy:a@href');
    expect(result.source.stages['toc']!.nextPage, '@legacy:a.next@href');
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
    expect(await host.call('java.ajax', ['https://books.test']), 'ok');
    expect(
      await host.call('java.md5Encode', ['abc']),
      '900150983cd24fb0d6963f7d28e17f72',
    );
    expect(
      (await host.call('java.get', ['https://books.test', {}])
          as Map)['status'],
      200,
    );
    await expectLater(host.call('java.unknown', []), throwsUnsupportedError);
    await expectLater(
      host.call('java.base64Decode', ['!!!']),
      throwsFormatException,
    );
  });
}
