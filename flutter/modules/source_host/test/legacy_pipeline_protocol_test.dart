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
  }) async => throw StateError('No JS in this fixture');
  @override
  Future<void> close() async {}
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
  }) async =>
      NetworkResponse(uri, 200, {}, '<section><h2>before</h2></section>');
}

class _Parser implements ScriptHost {
  final modes = <String>[];
  @override
  Future<Object?> call(String method, List<Object?> args) async {
    expect(method, 'legacyRule.evaluate');
    final r = args.first as Map, mode = r['mode'] as String;
    modes.add(mode);
    return {
      'value': switch (mode) {
        'element' => {'__legacyRuleValueRef': 'native-init-container'},
        'content' => ' before ',
        _ => r['rule'] == '##before##after' ? 'after' : 'Initialized',
      },
      'variables': <String, String>{},
    };
  }
}

Map<String, Object?> _raw(String group, Map<String, Object?> rules) => {
  'bookSourceUrl': 'https://fixture.invalid/',
  'bookSourceName': 'Fixture',
  group: rules,
};
MethodCall _request(Map<String, Object?> source, String operation) =>
    MethodCall('execute', {
      'protocolVersion': 1,
      'taskId': 'task-$operation',
      'sourceJson': jsonEncode(source),
      'operation': operation,
      'input': {
        'bookUrl': 'https://fixture.invalid/info',
        'chapterUrl': 'https://fixture.invalid/content',
      },
    });
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  for (final operation in ['info', 'content']) {
    test(
      'only implemented $operation hook reaches the original parser',
      () async {
        final parser = _Parser();
        final host = SourceHost(
          (_) => SourceEngine(
            runtime: _Runtime(),
            network: _Pages(),
            platform: parser,
            legacyRuleEvaluator: const HostLegacyRuleEvaluator(),
          ),
          legacyRuleHostEnabled: true,
        );
        try {
          final raw = operation == 'info'
              ? _raw('ruleBookInfo', {
                  'init': 'tag.section',
                  'name': 'tag.h2@text',
                })
              : _raw('ruleContent', {
                  'content': 'tag.h2@text',
                  'replaceRegex': '##before##after',
                });
          expect(
            await host.handle(_request(raw, operation)),
            operation == 'info'
                ? [
                    {'name': 'Initialized'},
                  ]
                : [
                    {'content': 'after'},
                  ],
          );
          expect(
            parser.modes,
            operation == 'info' ? ['element', 'scalar'] : ['content', 'scalar'],
          );
        } finally {
          await host.close();
        }
      },
    );
  }
  test(
    'no-host and unrelated pipeline capabilities still block execution',
    () async {
      for (final rules in [
        {'init': 'tag.section', 'name': 'tag.h2@text'},
        {'name': 'tag.h2@text', 'webJs': 'return document.body;'},
      ]) {
        final host = SourceHost(
          (_) => throw StateError('must not create engine'),
          legacyRuleHostEnabled: rules.containsKey('webJs'),
        );
        try {
          await expectLater(
            host.handle(_request(_raw('ruleBookInfo', rules), 'info')),
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
      }
    },
  );
}
