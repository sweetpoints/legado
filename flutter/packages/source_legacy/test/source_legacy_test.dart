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
        'searchUrl': '/search,{"method":"post","headers":{"X-Extra":true,"X-Replace":"new"},"body":{"key":"fixed"}}',
        'ruleSearch': {'bookList': 'class.book'},
      };
      final imported = LegacySourceImporter().import(input);
      final stage = imported.source.stages['search']!;
      expect(stage.url, '/search');
      expect(stage.method, 'POST');
      expect(stage.body, '{"key":"fixed"}');
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
  test('fixed form keeps literals for encoding at execution', () {
    final imported = LegacySourceImporter().import({
      'bookSourceUrl': 'https://books.test',
      'searchUrl': '/search,{"method":"POST","body":"key=中文&space=a b&plus=a+b&encoded=%E4%B8%AD"}',
      'ruleSearch': {'bookList': 'class.book'},
    });
    expect(
      imported.source.stages['search']!.body,
      'key=中文&space=a b&plus=a+b&encoded=%E4%B8%AD',
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
        '/search?p={{page * 2}}',
        '/search?q={{key.trim()}}',
        '/search?p={{page',
        '/search?p=<1>',
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
      '/category/{{page * 2}}',
      '/category/a,{"retry":1}',
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
        'https://books.test/search?q={{page * 2}}',
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
  test('fixed shape templated form stays raw until legacy UTF8 encoding', () {
    for (final body in [
      'q={{key}}&page={{page}}',
      'key=中文&space=a b',
      '',
      '&&a=1',
    ]) {
      final imported = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'searchUrl': '/search,${jsonEncode({'method': 'POST', 'body': body})}',
        'ruleSearch': {'bookList': 'tag.a'},
      });
      final stage = imported.source.stages['search']!;
      expect(imported.requiresManualWork, false, reason: body);
      expect(stage.body, body);
      expect(stage.bodyEncoding, 'legacyFormUtf8');
      expect(
        stage.headers!['Content-Type'],
        'application/x-www-form-urlencoded',
      );
    }
    for (final body in ['{{key}}', '&&', 'prefix{{key}}', 'q={{unknown}}']) {
      final imported = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'searchUrl': '/search,${jsonEncode({'method': 'POST', 'body': body})}',
        'ruleSearch': {'bookList': 'tag.a'},
      });
      expect(imported.requiresManualWork, true, reason: body);
    }
  });
  test(
    'static JSON and line explore menus validate URLs independently of titles',
    () {
      final items = [
        {'title': '@JS:display, {text}', 'url': '/a'},
        {'title': 'B', 'url': '/b?page={{page}}'},
      ];
      for (final menu in [jsonEncode(items), 'A::/a\nB::/b?page={{page}}']) {
        final imported = LegacySourceImporter().import({
          'bookSourceUrl': 'https://books.test',
          'exploreUrl': menu,
          'ruleExplore': {'bookList': 'tag.a'},
        });
        expect(imported.requiresManualWork, false, reason: menu);
        final recorded = imported.source.metadata['legacyExploreItems'] as List;
        expect(recorded.map((e) => (e as Map)['url']), [
          '/a',
          '/b?page={{page}}',
        ]);
        expect(imported.source.stages['explore']!.url, '{{exploreUrl}}');
        expect(imported.original['exploreUrl'], menu);
      }
    },
  );
  test('malformed menus and per-category requests remain manual', () {
    for (final menu in [
      '[{broken}]',
      '[{"title":"A","url":3}]',
      jsonEncode([
        {'title': 'A', 'url': '/a,{"retry":1}'},
      ]),
      'A::/a,{"retry":1}',
      'A::@JS:"/a"',
      'A::/a?q={{key.trim()}}',
      'A::',
    ]) {
      final imported = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'exploreUrl': menu,
        'ruleExplore': {'bookList': 'tag.a'},
      });
      expect(imported.requiresManualWork, true, reason: menu);
      expect(imported.source.stages['explore']!.method, 'GET');
      expect(imported.source.stages['explore']!.body, null);
    }
  });
  test('IPv6 URLs are not confused with explore title delimiters', () {
    for (final menu in ['https://[::1]/path', '分类::http://[::1]/path']) {
      final imported = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'exploreUrl': menu,
        'ruleExplore': {'bookList': 'tag.a'},
      });
      expect(
        imported.requiresManualWork,
        !menu.startsWith('https:'),
        reason: menu,
      );
      if (!menu.startsWith('https:')) {
        expect(
          imported.issues.map((e) => e.code),
          contains('legacy.explore_menu_requires_review'),
        );
      }
      final items = imported.source.metadata['legacyExploreItems'] as List;
      expect(
        items.single,
        menu.startsWith('https:')
            ? {'title': '', 'url': 'https://[::1]/path'}
            : {'title': '分类', 'url': 'http://[::1]/path'},
      );
      expect(imported.original['exploreUrl'], menu);
    }
  });
  test('legacy string body templates retain outer JSON provenance for all content types', () {
    for (final options in [
      {'method': 'POST', 'body': 'q={{key}}'},
      {'method': 'POST', 'body': '{"q":"{{key}}"}'},
      {
        'method': 'POST',
        'body': '{{key}}',
        'headers': {'Content-Type': 'text/plain'},
      },
    ]) {
      final imported = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'searchUrl': '/search,${jsonEncode(options)}',
        'ruleSearch': {'bookList': 'tag.a'},
      });
      expect(imported.requiresManualWork, false, reason: '$options');
      expect(
        imported.source.stages['search']!.bodyTemplateMode,
        'legacyJsonString',
      );
    }
    for (final body in [
      {'q': '{{key}}'},
      ['{{key}}'],
    ]) {
      final imported = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'searchUrl': '/search,${jsonEncode({'method': 'POST', 'body': body})}',
        'ruleSearch': {'bookList': 'tag.a'},
      });
      expect(imported.requiresManualWork, true);
      expect(imported.source.stages['search']!.bodyTemplateMode, 'raw');
    }
    final fixed = LegacySourceImporter().import({
      'bookSourceUrl': 'https://books.test',
      'searchUrl': '/search,{"method":"POST","body":"q=fixed"}',
      'ruleSearch': {'bookList': 'tag.a'},
    });
    expect(fixed.source.stages['search']!.bodyTemplateMode, 'raw');
  });
  test(
    'explore presentation style is preserved without affecting URL validation',
    () {
      final style = <String, Object?>{
        'layout_flexGrow': 1,
        'nested': {'label': '@JS:display {{notAnInput}}'},
      };
      final items = [
        {'title': 'A', 'url': '/a', 'style': style},
        {'title': 'B', 'url': '/b', 'style': null},
      ];
      final imported = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'exploreUrl': items,
        'ruleExplore': {'bookList': 'tag.a'},
      });
      expect(imported.requiresManualWork, false);
      final recorded = imported.source.metadata['legacyExploreItems'] as List;
      expect(recorded, items);
      style['layout_flexGrow'] = 9;
      expect(
        (recorded.first as Map)['style'],
        containsPair('layout_flexGrow', 1),
      );
      expect(
        (imported.original['exploreUrl'] as List).first['style'],
        containsPair('layout_flexGrow', 1),
      );
      final badUrl = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'exploreUrl': jsonEncode([
          {
            'title': 'A',
            'url': '/a,{"retry":1}',
            'style': {'layout_flexGrow': 1},
          },
        ]),
        'ruleExplore': {'bookList': 'tag.a'},
      });
      expect(
        badUrl.issues.map((e) => e.code),
        contains('legacy.request_options'),
      );
    },
  );
  test('explore style type and unknown behavior fields remain manual', () {
    for (final extra in [
      {'style': 'wide'},
      {'style': []},
      {'style': 1},
      {'type': 'action'},
      {'action': 'js'},
    ]) {
      final imported = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'exploreUrl': jsonEncode([
          {'title': 'A', 'url': '/a', ...extra},
        ]),
        'ruleExplore': {'bookList': 'tag.a'},
      });
      expect(imported.requiresManualWork, true, reason: '$extra');
      expect(
        imported.issues.map((e) => e.code),
        contains('legacy.explore_menu_requires_review'),
      );
    }
  });
  test('finite page expressions and URL choices import explicitly', () {
    for (final url in [
      '/search?p={{page + 1}}',
      '/search?p={{page-0}}',
      '/search?p=<a,b,c>',
    ]) {
      final imported = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'searchUrl': url,
        'ruleSearch': {'bookList': 'tag.a'},
      });
      expect(imported.requiresManualWork, false, reason: url);
      expect(imported.source.stages['search']!.legacyPageTemplates, true);
    }
    for (final url in [
      '/search?p={{page+01}}',
      '/search?p={{page+9007199254740992}}',
      '/search?p={{page*2}}',
      '/search?p=<a>',
      '/search?p=<{{key}},fallback>',
      '/search?p=<{{page+1}},fallback>',
      '/search?p=<a,<b,c>>',
      '/search?p=<a,b',
    ]) {
      final imported = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'searchUrl': url,
        'ruleSearch': {'bookList': 'tag.a'},
      });
      expect(imported.requiresManualWork, true, reason: url);
    }
    final body = LegacySourceImporter().import({
      'bookSourceUrl': 'https://books.test',
      'searchUrl':
          '/search,${jsonEncode({'method': 'POST', 'body': 'q={{page + 1}}'})}',
      'ruleSearch': {'bookList': 'tag.a'},
    });
    expect(body.requiresManualWork, false);
    expect(body.source.stages['search']!.legacyPageTemplates, true);
    expect(body.source.stages['search']!.bodyTemplateMode, 'legacyJsonString');
    final xml = LegacySourceImporter().import({
      'bookSourceUrl': 'https://books.test',
      'searchUrl':
          '/search,${jsonEncode({'method': 'POST', 'body': '<q>{{key}}</q>'})}',
      'ruleSearch': {'bookList': 'tag.a'},
    });
    expect(xml.requiresManualWork, true);
  });
  test(
    'selected category request adapter preserves extraction and finite options',
    () {
      final imported = LegacySourceImporter().import({
        'bookSourceUrl': 'https://books.test',
        'header': {'X-Base': 'base'},
        'exploreUrl': jsonEncode([
          {
            'title': 'B',
            'url':
                '/b,${jsonEncode({
                  'method': 'POST',
                  'body': 'q={{page+1}}',
                  'headers': {'X-Item': 'b'},
                })}',
          },
        ]),
        'ruleExplore': {
          'bookList': 'tag.a',
          'name': '@text',
          'nextTocUrl': 'tag.a@href',
        },
      });
      expect(imported.requiresManualWork, false);
      final stage = imported.source.stages['explore']!;
      expect(stage.legacyRequestInput, 'exploreUrl');
      final selected =
          (imported.source.metadata['legacyExploreItems'] as List).single['url']
              as String;
      final adapted = adaptLegacyRequest(imported.source, stage, {
        'exploreUrl': selected,
      });
      expect(adapted.url, '/b');
      expect(adapted.method, 'POST');
      expect(adapted.body, 'q={{page+1}}');
      expect(adapted.headers, containsPair('X-Base', 'base'));
      expect(adapted.headers, containsPair('X-Item', 'b'));
      expect(adapted.legacyPageTemplates, true);
      expect(adapted.legacyRequestInput, null);
      expect(adapted.fields, stage.fields);
      expect(adapted.list, stage.list);
      expect(adapted.nextPage, stage.nextPage);
      expect(
        () => adaptLegacyRequest(imported.source, stage, {
          'exploreUrl': '/b,{"retry":1}',
        }),
        throwsA(
          isA<EngineException>().having(
            (e) => e.code,
            'code',
            'legacy_request_requires_migration',
          ),
        ),
      );
      expect(
        adaptLegacyRequest(
          imported.source,
          const SourceStage(url: '/modern'),
          {},
        ).url,
        '/modern',
      );
    },
  );
  test('reviewed local source anchor permits query page arithmetic', () {
    final imported = LegacySourceImporter().import({
      'bookSourceUrl': 'local-id',
      'searchUrl': 'https://books.test/search?p={{page + 1}}',
      'ruleSearch': {'bookList': 'tag.a'},
    });
    expect(imported.source.id, 'local-id');
    expect(imported.issues.map((e) => e.code), [
      'legacy.base_url_requires_review',
    ]);
  });
  test('known legacy URL inputs enable page protection without changing modern stages', () {
    final imported = LegacySourceImporter().import({
      'bookSourceUrl': 'https://books.test',
      'searchUrl': '/search?q={{key}}',
      'ruleSearch': {'bookList': 'tag.a'},
      'exploreUrl': 'A::/a?q={{key}}',
      'ruleExplore': {'bookList': 'tag.a'},
    });
    expect(imported.requiresManualWork, false);
    expect(imported.source.stages['search']!.legacyPageTemplates, true);
    final selected = adaptLegacyRequest(
      imported.source,
      imported.source.stages['explore']!,
      {'exploreUrl': '/a?q={{key}}'},
    );
    expect(selected.legacyPageTemplates, true);
    expect(
      const SourceStage(url: '/search?q={{key}}').legacyPageTemplates,
      false,
    );
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
