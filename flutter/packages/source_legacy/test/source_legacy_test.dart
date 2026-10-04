import 'dart:convert';

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
  test('URL option header placeholders require manual migration', () {
    for (final header in [
      {'X-Search': '{{key}}'},
      {'X-{{key}}': 'value'},
      jsonEncode({'X-Search': '{{page}}'}),
    ]) {
      final input = <String, Object?>{
        'bookSourceUrl': 'https://books.test',
        'searchUrl': '/search,${jsonEncode({'headers': header})}',
        'ruleSearch': {'bookList': 'class.book'},
      };
      final imported = LegacySourceImporter().import(input);
      expect(imported.requiresManualWork, true, reason: header.toString());
      expect(
        imported.issues.any((e) => e.message.startsWith('Header placeholders')),
        true,
      );
      expect(imported.source.stages['search']!.headers, isEmpty);
      expect(imported.original, input);
    }
  });
  test(
    'dynamic method and inferred body format never count as literal requests',
    () {
      for (final options in [
        {'method': '{{key}}'},
        {'method': 'POST', 'body': '{{key}}'},
      ]) {
        final imported = LegacySourceImporter().import({
          'bookSourceUrl': 'https://books.test',
          'searchUrl': '/search,${jsonEncode(options)}',
          'ruleSearch': {'bookList': 'class.book'},
        });
        expect(imported.requiresManualWork, true, reason: options.toString());
        expect(
          imported.issues.any((e) => e.code == 'legacy.request_options'),
          true,
        );
      }
      final explicitType = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'searchUrl':
            '/search,${jsonEncode({
              'method': 'POST',
              'body': '{{key}}',
              'headers': {'Content-Type': 'text/plain'},
            })}',
        'ruleSearch': {'bookList': 'class.book'},
      });
      expect(explicitType.requiresManualWork, false);
    },
  );
  test('commas in a plain URL are not misclassified as options', () {
    final imported = LegacySourceImporter().import({
      'bookSourceUrl': 'https://books.test',
      'searchUrl': '/search?keys=a,b',
      'ruleSearch': {'bookList': 'class.book'},
    });
    expect(imported.requiresManualWork, false);
    expect(imported.source.stages['search']!.url, '/search?keys=a,b');
  });

  test('direct literal string extraction imports without manual review', () {
    for (final script in [
      '@js:java.getString("class.title@text")',
      "@js:return java.getString('class.title@text');",
      '@js:return java.getStringList("@CSS:article:nth-child(2)@text");',
      '@js:java.getStringList("")',
    ]) {
      final imported = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'ruleContent': {'content': script},
      });
      expect(imported.requiresManualWork, false, reason: script);
      expect(imported.source.metadata['legacy'], true);
      expect(imported.source.metadata['compatibility'], 'unverified');
      expect(imported.source.stages['content']!.fields['content'], script);
    }
  });
  test('dynamic extraction overloads and element scripts remain manual', () {
    for (final script in [
      '@js:return java.getString(rule);',
      '@js:return java.getString("@text", result);',
      '@js:return java.getElement("class.title");',
      '@js:return java.getElements("class.title");',
      '@js:return java.getString("@js:java.get(1)");',
      '@js:return java.getString("class.a@text||class.b@text");',
    ]) {
      final imported = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'ruleContent': {'content': script},
      });
      expect(imported.requiresManualWork, true, reason: script);
    }
  });
  test(
    'plain URL paths still validate expressions and case-insensitive JS',
    () {
      for (final url in [
        '/search?p={{page + 1}}',
        '/search?q={{key.trim()}}',
        '/search?p={{page',
        '/search?p=<1,2,3>',
        '@JS:"https://books.test/search"',
        '<JS>"/search"</JS>',
      ]) {
        final imported = LegacySourceImporter().import({
          'bookSourceUrl': 'https://books.test',
          'searchUrl': url,
          'ruleSearch': {'bookList': 'tag.a'},
        });
        expect(imported.requiresManualWork, true, reason: url);
        expect(
          imported.issues.map((e) => e.code),
          contains('legacy.request_options'),
        );
      }
      final imported = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'searchUrl': '/search?q={{key}}&page={{page}}',
        'ruleSearch': {'bookList': 'tag.a'},
      });
      expect(imported.requiresManualWork, false);
    },
  );
  test('explore uses selected URL and keeps menu only in original', () {
    final input = <String, Object?>{
      'bookSourceUrl': 'https://books.test',
      'exploreUrl': '/category/a',
      'ruleExplore': {'bookList': 'tag.a', 'name': '@text'},
    };
    final imported = LegacySourceImporter().import(input);
    final stage = imported.source.stages['explore']!;
    expect(stage.url, '{{exploreUrl}}');
    expect(stage.method, 'GET');
    expect(stage.body, null);
    expect(stage.headers, null);
    expect(imported.requiresManualWork, false);
    expect(imported.original['exploreUrl'], '/category/a');
    expect(imported.source.metadata['legacyOriginal'], input);
  });
  test('explore dynamic menus and request options remain manual without leaking options', () {
    for (final menu in [
      '@JS:"/category/a"',
      '/category/{{page + 1}}',
      '/category/a,{"method":"POST","body":"x=1","headers":{"X-Category":"a"}}',
    ]) {
      final imported = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'exploreUrl': menu,
        'ruleExplore': {'bookList': 'tag.a'},
      });
      final stage = imported.source.stages['explore']!;
      expect(imported.requiresManualWork, true, reason: menu);
      expect(stage.url, '{{exploreUrl}}');
      expect(stage.method, 'GET');
      expect(stage.body, null);
      expect(stage.headers, null);
    }
  });
  test('content batch and callback hooks retain manual legacy metadata', () {
    for (final hook in ['contentBatch', 'callBackJs']) {
      final input = <String, Object?>{
        'bookSourceUrl': 'https://books.test',
        'ruleContent': {
          'content': 'tag.p@text',
          hook: 'java.cacheContent("chapter", "text")',
        },
      };
      final imported = LegacySourceImporter().import(input);
      expect(imported.requiresManualWork, true, reason: hook);
      expect(
        imported.issues.any(
          (e) =>
              e.path == 'ruleContent.$hook' &&
              e.code == 'legacy.pipeline_requires_review',
        ),
        true,
      );
      expect(imported.source.metadata['legacy'], true);
      expect(imported.source.metadata['compatibility'], 'manualRequired');
      expect(imported.original, input);
    }
  });
  test(
    'source identity preserves original spelling independently of URL base',
    () {
      for (final id in [
        'HTTPS://Books.Test:443/path',
        'https://books.test/%7e',
      ]) {
        final imported = LegacySourceImporter().import({'bookSourceUrl': id});
        expect(imported.source.id, id);
        expect(imported.requiresManualWork, false);
        expect(
          imported.source.metadata.containsKey('legacyBaseUrlUnavailable'),
          false,
        );
      }
    },
  );
  test('non-URL source IDs preserve identity with a reviewed static request anchor', () {
    for (final id in ['local-source-A', ' local-source-A ', 'local-source-B']) {
      final imported = LegacySourceImporter().import({
        'bookSourceUrl': id,
        'searchUrl': 'https://books.test:8443/search?q={{key}}&page={{page}},{"method":"GET"}',
        'ruleSearch': {'bookList': 'tag.a'},
      });
      expect(imported.source.id, id);
      expect(imported.source.baseUrl.toString(), 'https://books.test:8443');
      expect(imported.source.metadata['legacyBaseUrlUnavailable'], true);
      expect(imported.requiresManualWork, true);
      expect(
        imported.issues.map((e) => e.code),
        contains('legacy.base_url_requires_review'),
      );
      expect(imported.original['bookSourceUrl'], id);
    }
  });
  test(
    'uncertain search hosts and missing anchors reject without mislabeling IDs',
    () {
      for (final url in [
        null,
        '/search',
        '@JS:"https://books.test/search"',
        'https://{{key}}.test/search',
        'https://books.test/{{key}}',
        'https://user:pass@books.test/search',
        'https://books.test/search?q={{key.trim()}}',
        'https://books.test/search#fragment',
      ]) {
        expect(
          () => LegacySourceImporter().import({
            'bookSourceUrl': 'local-id',
            'searchUrl': url,
          }),
          throwsA(
            isA<FormatException>().having(
              (e) => e.message,
              'message',
              contains('Cannot determine'),
            ),
          ),
          reason: '$url',
        );
      }
      expect(
        () => LegacySourceImporter().import({'bookSourceUrl': ''}),
        throwsFormatException,
      );
    },
  );
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
