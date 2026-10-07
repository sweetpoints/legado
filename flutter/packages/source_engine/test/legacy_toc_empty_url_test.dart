import 'package:source_engine/source_engine.dart';
import 'package:test/test.dart';

import 'engine_test.dart' show NoScripts;

class _Host implements ScriptHost {
  @override
  Future<Object?> call(String method, List<Object?> args) async {
    final p = args.first as Map;
    final value = p['mode'] == 'elements'
        ? ['row']
        : p['rule'] == 'href'
        ? (p['isUrl'] == true ? p['baseUrl'] : '')
        : 'Chapter';
    return {
      'value': value,
      'variables': <String, String>{},
      'variableScope': p['variableScope'],
    };
  }
}

class _Pages extends NetworkClient {
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
  }) async => NetworkResponse(uri, 200, {}, 'row');
}

void main() {
  test('legacy toc empty href survives until App volume fallback', () async {
    final source = SourceDefinition(
      id: 'source',
      name: 'Fixture',
      baseUrl: Uri.parse('https://fixture.invalid/'),
      metadata: {'legacyOriginal': <String, Object?>{}},
      stages: {
        'toc': SourceStage(
          url: '/toc',
          list: 'rows',
          fields: {'title': 'title', 'url': 'href'},
        ),
      },
    );
    final engine = SourceEngine(
      runtime: NoScripts(),
      network: _Pages(),
      platform: _Host(),
      legacyRuleEvaluator: const HostLegacyRuleEvaluator(),
    );
    try {
      expect(await engine.execute(source, 'toc', input: {'taskId': 'task'}), [
        {'title': 'Chapter', 'url': ''},
      ]);
    } finally {
      await engine.close();
    }
  });
}
