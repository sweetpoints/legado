import 'dart:convert';
import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_host/main.dart' show createSourceEngine;
import 'package:source_host/source_host.dart';

class _Login implements ScriptHost {
  final outcomes = <Map>[];
  final calls = <String>[];
  @override
  Future<Object?> call(String method, List<Object?> arguments) async {
    final args = arguments.sublist(0, arguments.length - 1);
    calls.add(method);
    switch (method) {
      case 'loginCheck.begin':
        if (args[0] == 'initUrl') {
          expect(args, ['initUrl', [], null]);
          return {'status': 'done', 'token': 'init-token', 'value': null};
        }
        expect(args, ['getStrResponse', [], null]);
        return {
          'status': 'script',
          'token': 'login-token',
          'sequence': 1,
          'script': "java.put('resumed','yes'); Promise.resolve('https://fixture.invalid/final')",
          'bindings': {
            'baseUrl': 'https://fixture.invalid/',
            'result': 'url-input',
          },
        };
      case 'loginCheck.stepCall':
        expect(args, [
          'login-token',
          1,
          'put',
          ['resumed', 'yes'],
        ]);
        return 'yes';
      case 'loginCheck.continue':
        outcomes.add(args[2] as Map);
        return {
          'status': 'done',
          'token': 'login-token',
          'value': {
            '__legacyResponseKind': 'str',
            'url': 'https://fixture.invalid/final',
            'body': 'After',
            'status': 403,
            'multiHeaders': {
              'X-Fixture': ['one', 'two'],
            },
          },
        };
      case 'loginCheck.abort':
        expect(args.single, anyOf('init-token', 'login-token'));
        return null;
      default:
        throw StateError('Unexpected callback $method');
    }
  }
}

class _BusyLogin extends _Login {
  final started = Completer<void>();
  @override
  Future<Object?> call(String method, List<Object?> args) async {
    if (method == 'analyze.get') {
      if (!started.isCompleted) started.complete();
      return args.first;
    }
    return super.call(method, args);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'actual V8 keeps callable response fields and serializable login result',
    () async {
      final source = SourceDefinition(
        id: 'login-owner',
        name: 'Login fixture',
        baseUrl: Uri.parse('https://fixture.invalid/'),
        metadata: {'legacy': true},
      );
      final platform = _Login();
      final engine = createSourceEngine(
        source,
        platform: platform,
        useNativeLegacyHttp: true,
      );
      try {
        final value = await engine.evaluateAuxiliary(
          source,
          "const r=__legacyLoginRequest('getStrResponse',[]);({body:r.body(),url:r.url(),status:r.code(),header:r.headers().get('x-fixture'),serialized:r})",
          bindings: {'taskId': 'login-task', 'sourceData': <String, Object?>{}},
        );
        expect(value, {
          'body': 'After',
          'url': 'https://fixture.invalid/final',
          'status': 403,
          'header': 'two',
          'serialized': {
            '__legacyResponseKind': 'str',
            'url': 'https://fixture.invalid/final',
            'body': 'After',
            'status': 403,
            'multiHeaders': {
              'X-Fixture': ['one', 'two'],
            },
          },
        });
        expect(platform.outcomes, [
          {'ok': true, 'value': 'https://fixture.invalid/final'},
        ]);
        expect(platform.calls, [
          'loginCheck.begin',
          'loginCheck.stepCall',
          'loginCheck.continue',
          'loginCheck.abort',
        ]);
      } finally {
        await engine.close();
      }
    },
  );
  test('early captured library methods use the active login frame and restore globals', () async {
    final source = SourceDefinition(
      id: 'captured-login-owner',
      name: 'Captured login',
      baseUrl: Uri.parse('https://fixture.invalid/'),
      metadata: {'legacy': true},
    );
    final platform = _Login();
    final engine = createSourceEngine(
      source,
      platform: platform,
      useNativeLegacyHttp: true,
    );
    try {
      final value = await engine.evaluateAuxiliary(
        source,
        "const r=__legacyRunLoginCheck('libraryLogin()',result);({body:r.body(),restored:result.body,active:typeof __legacyLoginCheckActive})",
        bindings: {
          'taskId': 'captured-task',
          'result': {
            '__legacyResponseKind': 'str',
            'url': 'https://fixture.invalid/original',
            'body': 'Before',
            'status': 200,
            'multiHeaders': <String, Object?>{},
          },
        },
        prelude: "const savedInit=java.initUrl;const savedGet=java.getStrResponse;function libraryLogin(){savedInit();result=savedGet();return result;}",
      );
      expect(value, {
        'body': 'After',
        'restored': 'Before',
        'active': 'undefined',
      });
      expect(platform.calls, [
        'loginCheck.begin',
        'loginCheck.abort',
        'loginCheck.begin',
        'loginCheck.stepCall',
        'loginCheck.continue',
        'loginCheck.abort',
      ]);
    } finally {
      await engine.close();
    }
  });
  test(
    'hard cancelled login restores its frame before the next source call',
    () async {
      final source = SourceDefinition(
        id: 'cancel-login-owner',
        name: 'Cancel login',
        baseUrl: Uri.parse('https://fixture.invalid/'),
        metadata: {'legacy': true},
      );
      final platform = _BusyLogin();
      final engine = createSourceEngine(
        source,
        platform: platform,
        useNativeLegacyHttp: true,
      );
      final token = CancellationToken();
      final input = {
        '__legacyResponseKind': 'str',
        'url': 'https://fixture.invalid/original',
        'body': 'Before',
        'status': 200,
        'multiHeaders': <String, Object?>{},
      };
      try {
        final pending = engine.evaluateAuxiliary(
          source,
          "__legacyRunLoginCheck(\"__sourceHostSync('analyze.get',['started']);while(true){}\",result)",
          bindings: {'taskId': 'cancel-login-task', 'result': input},
          cancellation: token,
        );
        await platform.started.future.timeout(const Duration(seconds: 5));
        token.cancel();
        await expectLater(
          pending,
          throwsA(
            isA<EngineException>().having((e) => e.code, 'code', 'cancelled'),
          ),
        );
        expect(
          await engine.evaluateAuxiliary(
            source,
            "({active:typeof __legacyLoginCheckActive,body:__legacyRunLoginCheck('result',result).body()})",
            bindings: {'taskId': 'fresh-login-task', 'result': input},
          ),
          {'active': 'undefined', 'body': 'Before'},
        );
      } finally {
        await engine.close();
      }
    },
  );
  for (final enabled in [false, true]) {
    test(
      'login check gate requires native fetch capability: $enabled',
      () async {
        var factoryReached = false;
        final host = SourceHost(
          (_) {
            factoryReached = true;
            throw const EngineException(
              'fixture_factory_reached',
              'Gate passed to native execution',
            );
          },
          legacyRuleHostEnabled: true,
          legacyScriptRuleHostEnabled: true,
          legacyWebRuleHostEnabled: true,
          legacyPageFetchEnabled: enabled,
        );
        final raw = {
          'bookSourceUrl': 'https://fixture.invalid/',
          'bookSourceName': 'Login gate fixture',
          'loginCheckJs': 'result',
          'searchUrl': 'https://fixture.invalid/search',
          'ruleSearch': {
            'bookList': 'tag.article',
            'name': 'tag.b@text',
            'bookUrl': 'tag.a@href',
          },
        };
        try {
          await expectLater(
            host.handle(
              MethodCall('execute', {
                'protocolVersion': 1,
                'taskId': 'gate-task',
                'sourceJson': jsonEncode(raw),
                'operation': 'search',
                'input': {'key': 'fixture', 'page': 1},
              }),
            ),
            throwsA(
              isA<PlatformException>().having(
                (e) => e.code,
                'code',
                enabled
                    ? 'fixture_factory_reached'
                    : 'legacy_requires_migration',
              ),
            ),
          );
          expect(factoryReached, enabled);
        } finally {
          await host.close();
        }
      },
    );
  }
}
