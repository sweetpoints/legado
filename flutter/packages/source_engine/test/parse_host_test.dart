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
