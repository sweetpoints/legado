import 'package:source_engine/source_engine.dart';
import 'package:test/test.dart';

import 'engine_test.dart' show NoScripts;

class _CoverHost implements ScriptHost {
  _CoverHost(this.cover);
  final String cover;

  @override
  Future<Object?> call(String method, List<Object?> args) async {
    expect(method, 'legacyRule.evaluate');
    final request = args.first as Map;
    expect(request['mode'], 'cover');
    expect(request['isUrl'], false);
    expect(request['baseUrl'], 'https://fixture.invalid/details/redirect');
    return {
      'value': cover,
      'variables': <String, String>{},
      'variableScope': request['variableScope'],
    };
  }
}

class _Page extends NetworkClient {
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
    Uri.parse('https://fixture.invalid/details/redirect'),
    200,
    {},
    'Original response',
  );
}

void main() {
  for (final cover in [
    'https://image.invalid/a.jpg, {"headers":{"X-Image":"value"}}',
    'https://fixture.invalid/img/a.jpg,{"headers":{"Referer":"https://fixture.invalid/details/redirect"}}',
    'data:image/png;base64,iVBORw0KGgo=',
  ]) {
    test(
      'native cover URL/options pass through without a second URI encoding',
      () async {
        final engine = SourceEngine(
          runtime: NoScripts(),
          network: _Page(),
          platform: _CoverHost(cover),
          legacyRuleEvaluator: const HostLegacyRuleEvaluator(),
        );
        try {
          final rows = await engine.execute(
            SourceDefinition(
              id: 'fixture',
              name: 'Fixture',
              baseUrl: Uri.parse('https://fixture.invalid/'),
              metadata: {'legacyOriginal': <String, Object?>{}},
              stages: {
                'info': SourceStage(
                  url: '/details/original',
                  fields: {'coverUrl': 'imageRule'},
                ),
              },
            ),
            'info',
            input: {'taskId': 'cover-task'},
          );
          expect(rows.single['coverUrl'], cover);
        } finally {
          await engine.close();
        }
      },
    );
  }
}
