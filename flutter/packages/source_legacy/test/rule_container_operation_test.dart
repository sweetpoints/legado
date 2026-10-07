import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:test/test.dart';

class _NoScripts implements ScriptRuntime {
  @override
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  }) => throw StateError('No script in CSS projection');
  @override
  Future<void> close() async {}
}

class _RecordedPage extends NetworkClient {
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
  }) async => NetworkResponse(
    uri,
    200,
    {},
    '<ul class="rank_booklist"><li><dt>Recorded title</dt></li></ul>',
  );
}

void main() {
  test(
    'old Gson container golden distinguishes arrays from encoded arrays',
    () {
      for (final value in [
        null,
        <Object?>[],
        [1],
        'null',
      ]) {
        expect(legacyRuleObject({'ruleToc': value}, 'ruleToc'), isNull);
      }
      expect(
        legacyRuleObject({'ruleToc': '{"chapterList":"class.row"}'}, 'ruleToc'),
        {'chapterList': 'class.row'},
      );
      for (final value in ['[]', true, 42, 'not-json']) {
        expect(
          () => legacyRuleObject({'ruleToc': value}, 'ruleToc'),
          throwsFormatException,
        );
      }
    },
  );
  test(
    'BookList explore fallback preserves original and drops search-only config',
    () {
      for (final value in [
        null,
        [],
        {},
        {'bookList': '  '},
      ]) {
        final original = <String, Object?>{
          'ruleExplore': value,
          'ruleSearch': {
            'bookList': 'class.row',
            'name': 'tag.dt@text',
            'checkKeyWord': 'reader',
          },
        };
        final before = jsonEncode(original);
        expect(legacyRuleObject(original, 'ruleExplore'), {
          'bookList': 'class.row',
          'name': 'tag.dt@text',
        });
        expect(jsonEncode(original), before);
      }
      expect(
        legacyRuleObject({
          'ruleExplore': {'bookList': 'class.custom'},
          'ruleSearch': {'bookList': 'class.row'},
        }, 'ruleExplore'),
        {'bookList': 'class.custom'},
      );
    },
  );
  test('non-string explore lists never falsely fall back to search', () {
    for (final value in [1, true, [], {}]) {
      final source = <String, Object?>{
        'ruleExplore': {'bookList': value},
        'ruleSearch': {'bookList': 'class.search'},
      };
      expect(legacyRuleObject(source, 'ruleExplore'), {'bookList': value});
    }
    final imported = LegacySourceImporter().import({
      'bookSourceUrl': 'https://recorded.test',
      'mainJs': 'function getContent(){return "text";}',
      'ruleContent': [],
    });
    expect(imported.issues, isEmpty);
  });

  test('operation gates retain hooks and globals without blocking unrelated reading', () {
    const info = LegacyIssue(
      'ruleBookInfo.init',
      'legacy.pipeline_requires_review',
      'hook',
    );
    const content = LegacyIssue(
      'ruleContent.replaceRegex',
      'legacy.pipeline_requires_review',
      'hook',
    );
    const explore = LegacyIssue(
      'ruleExplore',
      'legacy.invalid_rule_object',
      'container',
    );
    const login = LegacyIssue(
      'loginUrl',
      'legacy.capability_requires_review',
      'UI',
    );
    expect(legacyIssueAffectsOperation(info, 'info'), true);
    expect(legacyIssueAffectsOperation(content, 'content'), true);
    for (final issue in [info, content, explore, login]) {
      expect(legacyIssueAffectsOperation(issue, 'search'), false);
    }
    for (final issue in const [
      LegacyIssue('header', 'legacy.dynamic_header', 'request'),
      LegacyIssue('jsLib', 'legacy.capability_requires_review', 'library'),
      LegacyIssue(
        'enabledCookieJar',
        'legacy.cookie_policy_requires_review',
        'cookies',
      ),
    ]) {
      expect(legacyIssueAffectsOperation(issue, 'search'), true);
    }
  });
  test('array containers and numeric strings do not block synthetic real CSS pipeline', () async {
    final original = <String, Object?>{
      'bookSourceUrl': 'https://recorded.test',
      'bookSourceType': '0',
      'searchUrl': '/search',
      'ruleExplore': [],
      'ruleSearch': {
        'bookList': 'class.rank_booklist@li',
        'name': 'tag.dt@text',
      },
    };
    final imported = LegacySourceImporter().import(original);
    expect(imported.issues, isEmpty);
    expect(imported.original, original);
    expect(imported.source.metadata['compatibility'], 'unverified');
    final engine = SourceEngine(
      runtime: _NoScripts(),
      network: _RecordedPage(),
    );
    try {
      expect(await engine.execute(imported.source, 'search'), [
        {'name': 'Recorded title'},
      ]);
    } finally {
      await engine.close();
    }
  });

  // The public original stays in ignored corpus storage. This optional test
  // executes its actual CSS list/name selector projection, not the full source
  // or its unrelated JS/hooks; Android acceptance covers the complete source.
  final corpus = Platform.environment['LEGACY_PUBLIC_CORPUS'];
  test(
    'public fixed source 978 array shape and recorded CSS selector projection',
    () async {
      final bytes = File('$corpus/source-978.json').readAsBytesSync();
      expect(
        sha256.convert(bytes).toString(),
        '0e8978f81298b24985765f211a5abd7e0a715d0213a66cff0df9fcd2281125da',
      );
      final input = jsonDecode(utf8.decode(bytes));
      final original = Map<String, Object?>.from(
        input is List ? input.single as Map : input as Map,
      );
      expect(original['ruleExplore'], isA<List>());
      final imported = LegacySourceImporter().import(original);
      expect(
        imported.issues.any(
          (issue) =>
              issue.path == 'ruleExplore' &&
              issue.code == 'legacy.invalid_rule_object',
        ),
        false,
      );
      expect(
        imported.issues.any((issue) => issue.code == 'legacy.non_text_source'),
        false,
      );
      expect(imported.original, original);
      final stage = imported.source.stages['search']!;
      final projection = SourceDefinition(
        id: imported.source.id,
        name: imported.source.name,
        baseUrl: imported.source.baseUrl,
        metadata: imported.source.metadata,
        stages: {
          'search': SourceStage(
            url: '/recorded',
            list: stage.list,
            fields: {'name': stage.fields['name']!},
          ),
        },
      );
      final engine = SourceEngine(
        runtime: _NoScripts(),
        network: _RecordedPage(),
      );
      try {
        expect(await engine.execute(projection, 'search'), [
          {'name': 'Recorded title'},
        ]);
      } finally {
        await engine.close();
      }
    },
    skip: corpus == null
        ? 'Public corpus path is only required for local fixed-sample acceptance'
        : false,
  );
}
