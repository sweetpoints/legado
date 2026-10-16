import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:test/test.dart';

void main() {
  const host = PortableLegacyDomHost(_NoHostConst());
  Future<Object?> call(
    Object? marker,
    String operation, [
    List<Object?> args = const [],
  ]) => host.call('javaHost.domCall', [marker, operation, args]);
  test(
    'org parse protocol supports ordered selectors text html and attributes',
    () async {
      final doc = await host.call('orgJsoup.parse', [
        '<p class="author"> A <b>B</b> </p><ul><li><a href="one">One</a></li><li><a href="two">Two</a></li></ul>',
        'https://fixture.invalid/',
      ]);
      final authors = await call(doc, 'select', ['.author']);
      expect(await call(authors, 'text'), 'A B');
      expect(await call(authors, 'html'), ' A <b>B</b> ');
      final links = await call(doc, 'select', ['li a']);
      expect(await call(links, 'text'), 'One Two');
      expect(await call(links, 'attr', ['href']), 'one');
      expect(
        await call(links, 'toString'),
        '<a href="one">One</a>\n<a href="two">Two</a>',
      );
    },
  );
  test('schema2 identifiers survive read-only forest selection without host leases', () async {
    final parsed = await host.call('orgJsoup.parse', ['<p>One</p>']) as Map;
    final original = Map<String, Object?>.from(parsed['__legacyDom'] as Map);
    final rows = original['nodes'] as List;
    final marker = {
      '__legacyDom': {
        ...original,
        'schemaVersion': 2,
        'documentId': 'identity',
        'ids': List.generate(rows.length, (i) => 'node-$i'),
        'roots': [0],
      },
    };
    final selected = await call(marker, 'select', ['p']) as Map;
    final result = selected['__legacyDomList'] as Map;
    expect(result['documentId'], 'identity');
    expect(result['ids'], (marker['__legacyDom'] as Map)['ids']);
    expect(await call(selected, 'text'), 'One');
  });
  test(
    'mutators connection leases XML and malformed forests return typed errors',
    () async {
      final doc = await host.call('orgJsoup.parse', ['<p>Original</p>']);
      for (final action in [
        () => call(doc, 'remove'),
        () => call(doc, 'attr', ['x', 'mutated']),
        () => host.call('orgJsoup.connect', ['https://fixture.invalid']),
        () => host.call('orgJsoup.parse', [
          '<xml/>',
          {'__legacyOrgParser': 'xml'},
        ]),
      ]) {
        await expectLater(action(), throwsA(isA<EngineException>()));
      }
      expect(await call(doc, 'text'), 'Original');
      for (final invalidValue in [
        42,
        <String, Object?>{'not': 'text'},
      ]) {
        await expectLater(
          call({
            '__legacyDom': {
              'schemaVersion': 1,
              'index': 0,
              'nodes': [
                {'kind': 'text', 'value': invalidValue, 'children': []},
              ],
            },
          }, 'text'),
          throwsA(
            isA<EngineException>().having(
              (e) => e.code,
              'code',
              'legacy.invalid_dom',
            ),
          ),
        );
      }

      await expectLater(
        call({
          '__legacyDom': {
            'schemaVersion': 1,
            'index': 0,
            'nodes': [
              {
                'kind': 'element',
                'tag': 'x',
                'attributes': {},
                'children': [1],
              },
              {
                'kind': 'element',
                'tag': 'x',
                'attributes': {},
                'children': [0],
              },
            ],
          },
        }, 'text'),
        throwsA(
          isA<EngineException>().having(
            (e) => e.code,
            'code',
            'legacy.invalid_dom',
          ),
        ),
      );
    },
  );
}

class _NoHostConst implements ScriptHost {
  const _NoHostConst();
  @override
  Future<Object?> call(String method, List<Object?> args) async =>
      throw StateError(method);
}
