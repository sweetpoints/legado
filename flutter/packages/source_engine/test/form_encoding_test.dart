import 'dart:io';
import 'dart:convert';

import 'package:source_engine/source_engine.dart';
import 'package:test/test.dart';

import 'engine_test.dart' show NoScripts;
import 'legacy_base_guard_test.dart' show RecordingNetwork;

void main() {
  test(
    'default form golden matches old encodeParams and Java URLEncoder UTF8',
    () {
      const golden = {
        'q=hello world': 'q=hello+world',
        'q=a+b': 'q=a%2Bb',
        'q=%E4%B8%AD': 'q=%E4%B8%AD',
        'q=a%20 b': 'q=a%2520+b',
        'q=~*': 'q=%7E*',
        'q=a=b': 'q=a%3Db',
        '&q=1': 'q=1',
        '&&q=1': 'q=1',
        'q=1&': 'q=1&',
        'q=1&&x=2': 'q=1&&x=2',
        '&&': '',
        '=x': '=x',
        'q=中文': 'q=%E4%B8%AD%E6%96%87',
        'q=a\n': 'q=a%0A',
      };
      for (final entry in golden.entries) {
        expect(
          encodeLegacyFormUtf8Body(entry.key),
          entry.value,
          reason: entry.key,
        );
      }
      expect(encodeLegacyFormUtf8Body('q=\ud800'), 'q=%3F');
      expect(encodeLegacyFormUtf8Body('q=😀'), 'q=%F0%9F%98%80');
    },
  );
  SourceDefinition source(SourceStage stage) => SourceDefinition(
    id: 'form',
    name: 'Form',
    baseUrl: Uri.parse('https://example.org'),
    stages: {'content': stage},
  );
  test('bodyEncoding roundtrip/default and unknown mode validation', () {
    final encoded = source(
      SourceStage(
        url: '/post',
        method: 'POST',
        body: 'q={{key}}',
        bodyEncoding: 'legacyFormUtf8',
      ),
    );
    expect(
      SourceDefinition.fromJson(encoded.toJson()).toJson(),
      encoded.toJson(),
    );
    expect(SourceStage.fromJson({'url': '/'}).bodyEncoding, 'raw');
    expect(
      () => source(SourceStage(url: '/post', bodyEncoding: 'wrong')),
      throwsA(
        isA<EngineException>().having((e) => e.code, 'code', 'invalid_source'),
      ),
    );
  });
  test(
    'template substitution precedes form encoding on actual HTTP wire',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      final received = <String>[];
      server.listen((req) async {
        received.add(await utf8.decoder.bind(req).join());
        req.response.write('accepted');
        await req.response.close();
      });
      final engine = SourceEngine(runtime: NoScripts());
      final config = SourceDefinition(
        id: 'wire',
        name: 'Wire',
        baseUrl: Uri.parse('http://127.0.0.1:${server.port}'),
        stages: {
          'content': SourceStage(
            url: '/post',
            method: 'POST',
            body: 'q={{key}}&page={{page}}&enabled={{enabled}}',
            bodyEncoding: 'legacyFormUtf8',
            headers: {
              'Content-Type':
                  'application/x-www-form-urlencoded; charset=UTF-8',
            },
            fields: {'content': '@regex:(.+)'},
          ),
        },
      );
      try {
        expect(
          await engine.execute(
            config,
            'content',
            input: {'key': '中 文+%', 'page': 2, 'enabled': true},
          ),
          [
            {'content': 'accepted'},
          ],
        );
        expect(received, ['q=%E4%B8%AD+%E6%96%87%2B%25&page=2&enabled=true']);
      } finally {
        await engine.close();
        await server.close(force: true);
      }
    },
  );
  test(
    'unsafe legacy template values reject before any network request',
    () async {
      final network = RecordingNetwork();
      final engine = SourceEngine(runtime: NoScripts(), network: network);
      final config = source(
        SourceStage(
          url: '/post',
          method: 'POST',
          body: 'q={{key}}',
          bodyEncoding: 'legacyFormUtf8',
        ),
      );
      try {
        for (final value in [
          'a"b',
          r'a\b',
          'a\nb',
          'a\u0000b',
          'a\u007fb',
          <String>['a'],
          {'a': 'b'},
          double.nan,
          double.infinity,
          2.0,
          2.5,
          1e30,
          9007199254740992,
          -9007199254740992,
        ]) {
          await expectLater(
            engine.execute(config, 'content', input: {'key': value}),
            throwsA(
              isA<EngineException>().having(
                (e) => e.code,
                'code',
                'legacy_body_template_requires_migration',
              ),
            ),
          );
        }
        await expectLater(
          engine.execute(config, 'content', input: {'key': null}),
          throwsA(
            isA<EngineException>().having(
              (e) => e.code,
              'code',
              'missing_input',
            ),
          ),
        );
        expect(network.calls, isEmpty);
        await engine.execute(
          config,
          'content',
          input: {'key': 9007199254740991},
        );
        await engine.execute(
          config,
          'content',
          input: {'key': -9007199254740991},
        );
        expect(network.calls, hasLength(2));
        // Raw modern body retains its existing input behavior.
        await engine.execute(
          source(SourceStage(url: '/post', method: 'POST', body: 'q={{key}}')),
          'content',
          input: {'key': 'a"b'},
        );
        expect(network.calls, hasLength(3));
      } finally {
        await engine.close();
      }
    },
  );
}
