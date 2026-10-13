import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_host/source_host.dart';

class _NoScript implements ScriptRuntime {
  @override
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  }) => throw StateError('No scripts');
  @override
  Future<void> close() async {}
}

class _Native implements ScriptHost {
  final calls = <Map>[];
  @override
  Future<Object?> call(String method, List<Object?> args) async {
    expect(method, 'legacyRequest.fetch');
    expect((args.last as Map)['__sourceHostCallback'], false);
    calls.add(args.first as Map);
    return {
      'value': {
        'url': 'https://server.test/search',
        'status': 200,
        'headers': <String, List<String>>{},
        'body': '<article><h2>Native</h2><a href="/book">Go</a></article>',
      },
      'variables': <String, String>{},
    };
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('native fetch capability executes dynamic original request without migration', () async {
    final native = _Native();
    final host = SourceHost(
      (_) => SourceEngine(
        runtime: _NoScript(),
        platform: native,
        legacyPageFetcher: const HostLegacyPageFetcher(),
      ),
      legacyRuleHostEnabled: true,
      legacyPageFetchEnabled: true,
    );
    final original = {
      'bookSourceUrl': 'https://server.test/',
      'bookSourceName': 'Fixture',
      'searchUrl': 'https://server.test/search,{"method":"POST","body":"q={{key}}","charset":"GBK"}',
      'header': '@js:({"X-Source":"dynamic"})',
      'enabledCookieJar': false,
      'ruleSearch': {
        'bookList': 'tag.article',
        'name': 'tag.h2@text',
        'bookUrl': 'tag.a@href',
      },
    };
    try {
      final results = await host.handle(
        MethodCall('execute', {
          'protocolVersion': 1,
          'taskId': 'fixture',
          'sourceJson': jsonEncode(original),
          'operation': 'search',
          'input': {'key': '中文'},
        }),
      ) as List;
      expect(results.single, {
        'name': 'Native',
        'bookUrl': 'https://server.test/book',
      });
      expect(native.calls.single['urlRule'], original['searchUrl']);
      expect((native.calls.single['source'] as Map)['enabledCookieJar'], false);
      expect(
        (native.calls.single['source'] as Map)['header'],
        original['header'],
      );
    } finally {
      await host.close();
    }
  });
}
