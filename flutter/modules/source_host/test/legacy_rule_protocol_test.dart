import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_host/source_host.dart';

class _Runtime implements ScriptRuntime {
  @override
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  }) => throw StateError('pure host fixture must not execute JavaScript');
  @override
  Future<void> close() async {}
}

class _Network extends NetworkClient {
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
  }) async =>
      NetworkResponse(uri, 200, {}, '<article><h2>Fixture</h2></article>');
}

class _Parser implements ScriptHost {
  final requests = <Map>[];
  @override
  Future<Object?> call(String method, List<Object?> args) async {
    expect(method, 'legacyRule.evaluate');
    final request = args.first as Map;
    requests.add(request);
    return {
      'value': request['mode'] == 'elements'
          ? ['<article><h2>Fixture</h2></article>']
          : '<h2>Fixture</h2>',
      'variables': <String, String>{},
    };
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final raw = <String, Object?>{
    'bookSourceUrl': 'https://books.test/',
    'bookSourceName': 'Fixture',
    'searchUrl': '/search',
    'ruleSearch': {'bookList': '//article', 'name': '//h2'},
  };
  MethodCall request(Map<String, Object?> source) => MethodCall('execute', {
    'protocolVersion': 1,
    'taskId': 'fixture-task',
    'sourceJson': jsonEncode(source),
    'operation': 'search',
    'input': <String, Object?>{},
  });
  test(
    'portable hosts retain unsupported original XPath node-scalar boundary',
    () async {
      var constructed = 0;
      final host = SourceHost((_) {
        constructed++;
        return SourceEngine(runtime: _Runtime(), network: _Network());
      });
      try {
        await expectLater(
          host.handle(request(raw)),
          throwsA(
            isA<PlatformException>().having(
              (e) => e.code,
              'code',
              'legacy_requires_migration',
            ),
          ),
        );
        expect(constructed, 0);
      } finally {
        await host.close();
      }
    },
  );
  test(
    'explicit Android parser capability admits only pure rule review issues',
    () async {
      final parser = _Parser();
      final host = SourceHost(
        (_) => SourceEngine(
          runtime: _Runtime(),
          network: _Network(),
          platform: parser,
          legacyRuleEvaluator: const HostLegacyRuleEvaluator(),
        ),
        legacyRuleHostEnabled: true,
      );
      try {
        final result = await host.handle(request(raw)) as List;
        expect(result.single, {'name': '<h2>Fixture</h2>'});
        expect(parser.requests.map((r) => r['rule']), ['//article', '//h2']);
        expect(parser.requests.map((r) => r['mode']), ['elements', 'scalar']);
        await expectLater(
          host.handle(request({...raw, 'jsLib': 'unimplementedStageLibrary'})),
          throwsA(
            isA<PlatformException>().having(
              (e) => e.code,
              'code',
              'legacy_requires_migration',
            ),
          ),
        );
      } finally {
        await host.close();
      }
    },
  );
  test('outer script capability admits declaration-stage library and mixed JS only', () async {
    final parser = _Parser();
    final host = SourceHost(
      (_) => SourceEngine(
        runtime: _Runtime(),
        network: _Network(),
        platform: parser,
        legacyRuleEvaluator: const HostLegacyRuleEvaluator(allowScripts: true),
      ),
      legacyRuleHostEnabled: true,
      legacyScriptRuleHostEnabled: true,
    );
    try {
      final scripted = {
        ...raw,
        'jsLib': 'var fixtureLibraryLoaded = true;',
        'ruleSearch': {
          'bookList': '//article',
          'name': 'tag.h2@text@js:result.toUpperCase()',
        },
      };
      expect((await host.handle(request(scripted)) as List).single, {
        'name': '<h2>Fixture</h2>',
      });
      expect(
        parser.requests.last['rule'],
        'tag.h2@text@js:result.toUpperCase()',
      );
      await expectLater(
        host.handle(request({...scripted, 'header': '@js:({})'})),
        throwsA(
          isA<PlatformException>().having(
            (e) => e.code,
            'code',
            'legacy_requires_migration',
          ),
        ),
      );
    } finally {
      await host.close();
    }
  });
  test(
    'normal search ignores unrequested explore and reader UI capabilities',
    () async {
      final parser = _Parser();
      final host = SourceHost(
        (_) => SourceEngine(
          runtime: _Runtime(),
          network: _Network(),
          platform: parser,
          legacyRuleEvaluator: const HostLegacyRuleEvaluator(
            allowScripts: true,
          ),
        ),
        legacyRuleHostEnabled: true,
        legacyScriptRuleHostEnabled: true,
      );
      final configured = {
        ...raw,
        'ruleExplore': [],
        'loginUrl': 'https://books.test/login',
        'ruleReview': {'review': '@js:unimplementedReviewHook()'},
        'bookSourceType': 2,
        'ruleContent': {'webJs': 'unimplementedBrowserHook()'},
      };
      try {
        expect((await host.handle(request(configured)) as List).single, {
          'name': '<h2>Fixture</h2>',
        });
        await expectLater(
          host.handle(
            MethodCall('execute', {
              'protocolVersion': 1,
              'taskId': 'content',
              'sourceJson': jsonEncode(configured),
              'operation': 'content',
              'input': <String, Object?>{},
            }),
          ),
          throwsA(
            isA<PlatformException>().having(
              (e) => e.code,
              'code',
              'legacy_requires_migration',
            ),
          ),
        );
      } finally {
        await host.close();
      }
    },
  );
}
