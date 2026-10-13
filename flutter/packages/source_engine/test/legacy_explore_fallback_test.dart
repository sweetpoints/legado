import 'package:source_engine/source_engine.dart';
import 'package:test/test.dart';

import 'engine_test.dart' show NoScripts;

class _Host implements ScriptHost {
  final urls = <String>[];
  @override
  Future<Object?> call(String method, List<Object?> args) async {
    final r = args.first as Map;
    final Object? value;
    if (method == 'legacyRequest.fetch') {
      urls.add(r['urlRule'] as String);
      value = {
        'url': r['urlRule'],
        'body': 'menuB-body',
        'status': 200,
        'headers': <String, List<String>>{},
      };
    } else if (r['mode'] == 'elements') {
      expect(r['rule'], 'searchRows');
      value = ['row'];
    } else {
      expect(r['rule'], 'searchName');
      value = 'Menu B Book';
    }
    return {
      'value': value,
      'variables': <String, String>{},
      'variableScope': r['variableScope'],
    };
  }
}

void main() {
  for (final explore in [<String, Object?>{}, null]) {
    test('native blank Explore uses Search extraction while retaining selected Menu B', () async {
      final raw = {
        'ruleExplore': explore,
        'ruleSearch': {'bookList': 'searchRows', 'name': 'searchName'},
        'exploreUrl': 'A::https://fixture.invalid/menuA\nB::https://fixture.invalid/menuB',
      };
      final source = SourceDefinition(
        id: 'fixture',
        name: 'Fixture',
        baseUrl: Uri.parse('https://fixture.invalid/'),
        metadata: {'legacyOriginal': raw},
        stages: {
          'search': SourceStage(
            url: '/search',
            list: 'searchRows',
            fields: {'name': 'searchName'},
          ),
          if (explore != null)
            'explore': SourceStage(
              url: '{{exploreUrl}}',
              fields: {'name': 'wrongExploreName'},
            ),
        },
      );
      final host = _Host();
      final engine = SourceEngine(
        runtime: NoScripts(),
        platform: host,
        legacyPageFetcher: const HostLegacyPageFetcher(),
        legacyRuleEvaluator: const HostLegacyRuleEvaluator(),
      );
      try {
        expect(
          await engine.execute(
            source,
            'explore',
            input: {
              'taskId': 'task',
              'exploreUrl': 'https://fixture.invalid/menuB',
            },
          ),
          [
            {'name': 'Menu B Book'},
          ],
        );
        expect(host.urls, ['https://fixture.invalid/menuB']);
        expect(raw['ruleExplore'], explore);
        expect(
          source.stages['explore']?.fields['name'],
          explore == null ? null : 'wrongExploreName',
        );
      } finally {
        await engine.close();
      }
    });
  }
  test(
    'WebView rule support is an explicit independent platform capability',
    () {
      expect(
        const HostLegacyRuleEvaluator(allowScripts: true)
            .supportsRule('@webjs:document.body.textContent'),
        false,
      );
      expect(
        const HostLegacyRuleEvaluator(allowWebScripts: true)
            .supportsRule('@webjs:document.body.textContent'),
        true,
      );
      expect(
        const HostLegacyRuleEvaluator(allowWebScripts: true)
            .supportsRule('@js:result'),
        false,
      );
    },
  );
}
