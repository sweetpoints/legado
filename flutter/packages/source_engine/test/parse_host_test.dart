import 'dart:io';

import 'package:source_engine/source_engine.dart';
import 'package:test/test.dart';

class HostProbe implements ScriptRuntime {
  HostProbe(this.probe);
  final Future<Object?> Function(ScriptHost) probe;
  @override
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  }) => probe(context.host);
  @override
  Future<void> close() async {}
}

void main() {
  final source = SourceDefinition(
    id: 'parse',
    name: 'Parse',
    baseUrl: Uri.parse('https://example.org/catalog/'),
    script: 'probe',
  );
  test(
    'parse host JSON, CSS, XPath and legacy scalar and node contracts',
    () async {
      final engine = SourceEngine(
        runtime: HostProbe((host) async {
          expect(
            await host.call('parse.getString', [
              r'@json:$.items[*].name',
              {
                'items': [
                  {'name': 'A'},
                  {'name': 'B'},
                ],
              },
            ]),
            'A\nB',
          );
          expect(
            await host.call('parse.getStringList', [
              r'@json:$.items[*].name',
              {
                'items': [
                  {'name': 'A'},
                  {'name': 'B'},
                ],
              },
            ]),
            ['A', 'B'],
          );
          expect(
            await host.call('parse.getElement', [
              r'@json:$.items[*]',
              {
                'items': [
                  {'name': 'A'},
                ],
              },
            ]),
            {'name': 'A'},
          );
          expect(
            await host.call('parse.getElements', [
              '@css:a',
              '<a href="/1">A</a>',
            ]),
            ['<a href="/1">A</a>'],
          );
          expect(
            await host.call('parse.getElement', [
              '@xpath://a',
              '<a href="/1">A</a>',
            ]),
            '<a href="/1">A</a>',
          );
          expect(
            await host.call('parse.getStringList', [
              '@legacy:tag.a@href',
              '<a href="../1">A</a><a href="../1">B</a>',
              true,
            ]),
            ['https://example.org/1'],
          );
          expect(
            await host.call('parse.getString', [
              '@legacy:text',
              '<p>A <b>B</b></p>',
            ]),
            'A B',
          );
          expect(
            await host.call('parse.getElement', ['@legacy:tag.a', '<a>A</a>']),
            '<a>A</a>',
          );
          expect(await host.call('parse.getString', ['', '<p>A</p>']), '');
          expect(
            await host.call('parse.getStringList', [
              '@xpath://a/@href',
              '<a href="one">A</a><a href="two">B</a>',
            ]),
            ['one', 'two'],
          );
          return [
            {'verified': true},
          ];
        }),
      );
      try {
        expect(await engine.execute(source, 'info'), [
          {'verified': true},
        ]);
      } finally {
        await engine.close();
      }
    },
  );
  test('source headers inherit for stages and JS, explicit headers replace', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((req) async {
      req.response.write(
        '${req.headers.value('x-global') ?? '-'}:${req.headers.value('x-explicit') ?? '-'}',
      );
      await req.response.close();
    });
    final base = Uri.parse('http://127.0.0.1:${server.port}');
    final stageEngine = SourceEngine(runtime: HostProbe((_) async => []));
    final scriptEngine = SourceEngine(
      runtime: HostProbe((host) async {
        expect(await host.call('net.get', ['/']), 'yes:-');
        expect(
          (await host.call('net.request', [
            {'url': '/'},
          ]) as Map)['body'],
          'yes:-',
        );
        expect(
          (await host.call('net.request', [
            {'url': '/', 'inheritHeaders': false},
          ]) as Map)['body'],
          '-:-',
        );
        expect(
          (await host.call('net.request', [
            {
              'url': '/',
              'headers': {'x-explicit': 'only'},
            },
          ]) as Map)['body'],
          '-:only',
        );
        return [
          {'verified': true},
        ];
      }),
    );
    try {
      final stage = SourceDefinition(
        id: 'headers',
        name: 'Headers',
        baseUrl: base,
        headers: {'x-global': 'yes'},
        stages: {
          'content': SourceStage(url: '/', fields: {'content': '@regex:(.+)'}),
        },
      );
      expect(await stageEngine.execute(stage, 'content'), [
        {'content': 'yes:-'},
      ]);
      expect(
        await scriptEngine.execute(
          SourceDefinition(
            id: 'headers',
            name: 'Headers',
            baseUrl: base,
            headers: {'x-global': 'yes'},
            script: 'probe',
          ),
          'info',
        ),
        [
          {'verified': true},
        ],
      );
    } finally {
      await stageEngine.close();
      await scriptEngine.close();
      await server.close(force: true);
    }
  });
  test('headers reject CRLF and invalid names', () {
    expect(
      () => SourceDefinition(
        id: 'bad',
        name: 'Bad',
        baseUrl: Uri.parse('https://example.org'),
        headers: {'x-test': 'one\r\ntwo'},
      ),
      throwsA(isA<EngineException>()),
    );
  });
  test('parse nested JS rejected without runtime reentry', () async {
    final engine = SourceEngine(
      runtime: HostProbe(
        (host) =>
            host.call('parse.getString', ['@js:source.net.get("/")', 'input']),
      ),
    );
    try {
      await expectLater(
        engine.execute(source, 'info'),
        throwsA(
          isA<EngineException>().having(
            (e) => e.code,
            'code',
            'unsupported_rule',
          ),
        ),
      );
    } finally {
      await engine.close();
    }
  });
}
