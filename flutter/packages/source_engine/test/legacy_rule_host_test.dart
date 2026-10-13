import 'package:source_engine/source_engine.dart';
import 'package:test/test.dart';

import 'engine_test.dart' show NoScripts;

class _Host implements ScriptHost {
  final requests = <Map<String, Object?>>[];
  final writes = <String, String>{};
  @override
  Future<Object?> call(String method, List<Object?> args) async {
    if (method == 'variables.put') {
      writes[args[0] as String] = args[1] as String;
      return null;
    }
    expect(method, 'legacyRule.evaluate');
    expect((args.last as Map)['__sourceTaskId'], 'task');
    expect((args.last as Map)['__sourceHostCallback'], false);
    final request = Map<String, Object?>.from(args.first as Map);
    requests.add(request);
    return {
      'value': request['mode'] == 'scalar' ? 'A&amp;B' : ['/one', '/two'],
      'variables': {'token': 'saved'},
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
  }) async => NetworkResponse(uri, 200, {}, '<h2>A&amp;amp;B</h2>');
}

void main() {
  final source = SourceDefinition(
    id: 'fixture',
    name: 'Fixture',
    baseUrl: Uri.parse('https://books.test/'),
    metadata: {
      'legacy': true,
      'legacyOriginal': {'bookSourceUrl': 'https://books.test/'},
    },
    stages: {
      'info': SourceStage(
        url: '/',
        fields: {
          'name': '@legacy:tag.h2@text',
          'kind': '@legacy:tag.h2@text',
          'downloadUrls': '@legacy:tag.h2@text',
        },
      ),
    },
  );
  test('host projection preserves quoted prefixes and synchronizes only written variables', () async {
    final host = _Host();
    final result = await const HostLegacyRuleEvaluator().evaluate(
      '@legacy:a[data-token="@legacy:literal"]@text&&@legacy:tag.p@text',
      '<p>x</p>',
      ScriptContext(
        host: host,
        variables: {'taskId': 'task', 'baseUrl': 'https://books.test/dir/'},
      ),
      source: source,
      operation: 'info',
      scalar: true,
    );
    expect(result, ['A&amp;B']);
    expect(
      host.requests.single['rule'],
      'a[data-token="@legacy:literal"]@text&&tag.p@text',
    );
    expect(host.requests.single['mode'], 'scalar');
    expect(host.requests.single['baseUrl'], 'https://books.test/dir/');
    expect(host.writes, {'token': 'saved'});
  });
  test('engine host output is not decoded twice and old list field shapes remain distinct', () async {
    final host = _Host();
    final engine = SourceEngine(
      runtime: NoScripts(),
      network: _Pages(),
      legacyRuleEvaluator: const HostLegacyRuleEvaluator(),
      platform: host,
    );
    try {
      expect(
        (await engine.execute(
          source,
          'info',
          input: {'taskId': 'task'},
        )).single,
        {
          'name': 'A&amp;B',
          'kind': '/one,/two',
          'downloadUrls': ['/one', '/two'],
          'variable': {'token': 'saved'},
        },
      );
      expect((host.requests[1]['variables'] as Map)['token'], 'saved');
      expect(
        (engine.exportSession(source.id)['variables'] as Map)['token'],
        isNull,
      );
    } finally {
      await engine.close();
    }
  });
  test(
    'opaque node references pass to the same task without HTML reparsing',
    () async {
      final host = _Host();
      const node = {'__legacyRuleNodeRef': 'task-scoped-node'};
      await const HostLegacyRuleEvaluator().evaluate(
        '@XPath:../@id',
        node,
        ScriptContext(
          host: host,
          variables: {'taskId': 'task', 'baseUrl': 'https://books.test/'},
        ),
        source: source,
        operation: 'search',
        scalar: true,
      );
      expect(host.requests.single['input'], node);
      expect(host.requests.single['rule'], '@XPath:../@id');
    },
  );
  test(
    'missing task and pre-cancelled evaluation do not invoke Android',
    () async {
      final host = _Host();
      final evaluator = const HostLegacyRuleEvaluator();
      await expectLater(
        evaluator.evaluate(
          '@legacy:text',
          'x',
          ScriptContext(host: host),
          source: source,
          operation: 'info',
        ),
        throwsA(isA<EngineException>()),
      );
      final token = CancellationToken()..cancel();
      await expectLater(
        evaluator.evaluate(
          '@legacy:text',
          'x',
          ScriptContext(host: host, variables: {'taskId': 'task'}),
          source: source,
          operation: 'info',
          cancellation: token,
        ),
        throwsA(isA<EngineException>()),
      );
      expect(host.requests, isEmpty);
    },
  );
  test('JS-bearing expressions cannot enter synchronous pure-rule host', () {
    for (final rule in [
      '@js:result',
      '<js>result</js>',
      '@webjs:document.body',
      '@legacy:{{page+1}}',
    ]) {
      expect(HostLegacyRuleEvaluator.canEvaluate(rule), isFalse);
    }
    expect(HostLegacyRuleEvaluator.canEvaluate('@legacy:@get:{token}'), isTrue);
    expect(HostLegacyRuleEvaluator.canEvaluate('//a/@href'), isTrue);
    expect(
      const HostLegacyRuleEvaluator(allowScripts: true)
          .supportsRule('tag.h2@text@js:result.toUpperCase()'),
      isTrue,
    );
    expect(
      const HostLegacyRuleEvaluator(allowScripts: true)
          .supportsRule('@webjs:document.body'),
      isFalse,
    );
  });
}
