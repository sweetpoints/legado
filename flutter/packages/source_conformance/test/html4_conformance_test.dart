import 'dart:convert';
import 'dart:io';

import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:source_migration/source_migration.dart';
import 'package:source_v8/source_v8.dart';
import 'package:test/test.dart';

import 'support/fixture_server.dart';

class _NoHost implements ScriptHost {
  @override
  Future<Object?> call(String method, List<Object?> arguments) =>
      throw UnsupportedError(method);
}

void main() {
  final golden = jsonDecode(
    File('test/support/commons_text_golden.json').readAsStringSync(),
  ) as Map;
  for (final item in golden['cases'] as List) {
    test('actual V8 Commons Text HTML4 golden ${jsonEncode(item['input'])}', () async {
      final input = item['input'] as String;
      final legacy = SourceEngine(
        runtime: V8Runtime(prelude: legacyScriptPrelude),
        hostAdapter: LegacyScriptHost.new,
      );
      final modern = V8Runtime();
      try {
        final source = SourceDefinition(
          id: 'html4',
          name: 'golden',
          baseUrl: Uri.parse('https://fixture.invalid/base/'),
          script:
              'async function search(){globalThis.result=JSON.stringify({value:${jsonEncode(input)}});return {scalar:java.getString("\$.value"),raw:java.getString("\$.value",false),list:java.getStringList("\$.value")};}',
        );
        final result = (await legacy.execute(source, 'search')).single;
        expect(result, {
          'scalar': item['expected'],
          'raw': input,
          'list': [input],
        });
        expect(
          await modern.evaluate(
            'return await source.encoding.unescapeHtml4(${jsonEncode(input)});',
            ScriptContext(host: SourceUtilityHost(_NoHost())),
          ),
          item['expected'],
        );
      } finally {
        await legacy.close();
        await modern.close();
      }
    });
  }
  test(
    'HTML4 decode precedes legacy URL blank fallback and resolution',
    () async {
      final engine = SourceEngine(
        runtime: V8Runtime(prelude: legacyScriptPrelude),
        hostAdapter: LegacyScriptHost.new,
      );
      try {
        final source = SourceDefinition(
          id: 'urls',
          name: 'urls',
          baseUrl: Uri.parse('https://fixture.invalid/base/'),
          script: r'''async function search(){globalThis.result=JSON.stringify({blank:'&nbsp;',link:'next?a=1&amp;b=2'});return {blank:java.getString('$.blank',null,true),link:java.getString('$.link',null,true)};}''',
        );
        expect((await engine.execute(source, 'search')).single, {
          'blank': 'https://fixture.invalid/base/',
          'link': 'https://fixture.invalid/base/next?a=1&b=2',
        });
      } finally {
        await engine.close();
      }
    },
  );
  test(
    'whole-source migrated getString runs modern HTML4 API with no java',
    () async {
      final server = await FixtureServer.start();
      final original = <String, Object?>{
        'bookSourceUrl': server.baseUrl.toString(),
        'bookSourceName': 'HTML4固定书源',
        'searchUrl': '/entities',
        'ruleSearch': {'name': r'@js:java.getString("$.value")'},
      };
      final migrated = SourceMigrator().migrate(original);
      expect(migrated.issues, isEmpty);
      final candidate = SourceDefinition.fromJson(migrated.candidate);
      expect(candidate.metadata['legacy'], false);
      final oldEngine = SourceEngine(
        runtime: V8Runtime(prelude: legacyScriptPrelude),
        hostAdapter: LegacyScriptHost.new,
      );
      final modern = V8Runtime();
      final newEngine = SourceEngine(
        runtime: modern,
        platform: SourceUtilityHost(_NoHost()),
      );
      try {
        expect(
          await modern.evaluate('typeof java', ScriptContext(host: _NoHost())),
          'undefined',
        );
        final baseline = await oldEngine.execute(
          LegacySourceImporter().import(original).source,
          'search',
        );
        expect(baseline, [
          {'name': '© \u0080 &apos; &'},
        ]);
        expect(await newEngine.execute(candidate, 'search'), baseline);
      } finally {
        await oldEngine.close();
        await newEngine.close();
        await server.close();
      }
    },
  );
}
