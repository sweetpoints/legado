import 'dart:convert';

import 'package:source_engine/source_engine.dart';
import 'package:test/test.dart';

import 'engine_test.dart' show NoScripts;

class _Pages extends NetworkClient {
  final requests = <Uri>[];
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
  }) async {
    requests.add(uri);
    return NetworkResponse(
      uri,
      200,
      {},
      uri.path == '/two' ? 'second-body' : 'first-body',
    );
  }
}

class _Host implements ScriptHost {
  _Host(this.extract);
  final Object? Function(Map<String, Object?>) extract;
  final calls = <Map<String, Object?>>[];
  @override
  Future<Object?> call(String method, List<Object?> args) async {
    expect(method, 'legacyRule.evaluate');
    expect((args.last as Map)['__sourceHostCallback'], false);
    final request = Map<String, Object?>.from(args.first as Map);
    calls.add(request);
    return {'value': extract(request), 'variables': <String, String>{}};
  }
}

SourceDefinition _source(
  String operation,
  Map<String, Object?> originalRule,
  Map<String, String> fields, {
  String? nextPage,
}) => SourceDefinition(
  id: 'fixture',
  name: 'Fixture',
  baseUrl: Uri.parse('https://fixture.invalid/'),
  metadata: {
    'legacyOriginal': {
      operation == 'info' ? 'ruleBookInfo' : 'ruleContent': originalRule,
    },
  },
  stages: {
    operation: SourceStage(url: '/one', fields: fields, nextPage: nextPage),
  },
);
SourceEngine _engine(_Host host, _Pages pages) => SourceEngine(
  runtime: NoScripts(),
  network: pages,
  platform: host,
  legacyRuleEvaluator: const HostLegacyRuleEvaluator(allowScripts: true),
);
void main() {
  test('encoded legacy rule object keeps init execution rather than losing raw provenance', () async {
    final host = _Host(
      (request) => request['mode'] == 'element'
          ? {'__legacyRuleValueRef': 'original-container'}
          : 'Encoded title',
    );
    final engine = _engine(host, _Pages());
    final definition = _source(
      'info',
      {'init': 'tag.section'},
      {'name': '@legacy:tag.h2@text'},
    );
    final encoded = SourceDefinition.fromJson({
      ...definition.toJson(),
      'metadata': {
        'legacyOriginal': {
          'ruleBookInfo': jsonEncode({'init': 'tag.section'}),
        },
      },
    });
    try {
      expect(await engine.execute(encoded, 'info', input: {'taskId': 'task'}), [
        {'name': 'Encoded title'},
      ]);
      expect(host.calls.map((r) => r['mode']), ['element', 'scalar']);
    } finally {
      await engine.close();
    }
  });
  test('detail init is original getElement before fields and preserves its whole value', () async {
    const reference = {'__legacyRuleValueRef': 'task-owned-reference'};
    final host = _Host((r) {
      if (r['mode'] == 'element') {
        expect(r['rule'], 'tag.section');
        expect(r['input'], 'first-body');
        return reference;
      }
      expect(r['input'], reference);
      expect(r['mode'], 'scalar');
      return 'Initialized title';
    });
    final engine = _engine(host, _Pages());
    try {
      final result = await engine.execute(
        _source(
          'info',
          {'init': 'tag.section'},
          {'init': '@legacy:tag.section', 'name': '@legacy:tag.h2@text'},
        ),
        'info',
        input: {'taskId': 'task'},
      );
      expect(result, [
        {'name': 'Initialized title'},
      ]);
      expect(host.calls.map((r) => r['mode']), ['element', 'scalar']);
    } finally {
      await engine.close();
    }
  });
  test(
    'null init fails instead of extracting from the original page',
    () async {
      final engine = _engine(_Host((_) => null), _Pages());
      try {
        await expectLater(
          engine.execute(
            _source(
              'info',
              {'init': '@js:null'},
              {'name': '@legacy:tag.h2@text'},
            ),
            'info',
            input: {'taskId': 'task'},
          ),
          throwsA(
            isA<EngineException>().having(
              (e) => e.code,
              'code',
              'legacy_init_empty',
            ),
          ),
        );
      } finally {
        await engine.close();
      }
    },
  );
  test('replacement runs once after every formatted page and title uses first page afterwards', () async {
    var contents = 0;
    late _Host host;
    host = _Host((r) {
      final rule = r['rule'];
      if (r['mode'] == 'content') {
        contents++;
        return contents == 1 ? ' \u00a0Alpha \n \ufeff \n\u0085' : '　Beta　';
      }
      if (rule == 'next') return contents == 1 ? ['/two'] : <String>[];
      if (rule == '##Alpha##Replaced') {
        expect(contents, 2);
        expect(r['input'], 'Alpha\n\ufeff\n\u0085\nBeta');
        expect(r['baseUrl'], 'https://fixture.invalid/one');
        return 'Replaced\nBeta';
      }
      expect(rule, '@js:titleAfterReplace');
      expect(r['input'], 'first-body');
      expect(host.calls[host.calls.length - 2]['rule'], '##Alpha##Replaced');
      return 'Final title';
    });
    final pages = _Pages(), engine = _engine(host, pages);
    try {
      final result = await engine.execute(
        _source(
          'content',
          {'replaceRegex': '##Alpha##Replaced'},
          {
            'title': '@js:titleAfterReplace',
            'replaceRegex': '##Alpha##Replaced',
            'content': '@legacy:tag.p@text',
          },
          nextPage: 'next',
        ),
        'content',
        input: {'taskId': 'task', '__legacyOnLineTxt': true},
      );
      expect(result, [
        {'content': '　　Replaced\n　　Beta', 'title': 'Final title'},
      ]);
      expect(pages.requests.map((u) => u.path), ['/one', '/two']);
      expect(
        host.calls.where((r) => r['rule'] == '##Alpha##Replaced'),
        hasLength(1),
      );
    } finally {
      await engine.close();
    }
  });
  test('portable callers explicitly require a host instead of silently treating init as a field', () async {
    final engine = SourceEngine(runtime: NoScripts(), network: _Pages());
    try {
      await expectLater(
        engine.execute(
          _source(
            'info',
            {'init': 'tag.section'},
            {'init': '@legacy:tag.section', 'name': '@legacy:tag.h2@text'},
          ),
          'info',
        ),
        throwsA(
          isA<EngineException>().having(
            (e) => e.code,
            'code',
            'legacy_pipeline_host_required',
          ),
        ),
      );
    } finally {
      await engine.close();
    }
  });
}
