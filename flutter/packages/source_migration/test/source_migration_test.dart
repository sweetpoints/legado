import 'package:test/test.dart';
import 'package:source_migration/source_migration.dart';

void main() {
  final migrator = SourceMigrator();
  test(
    'straight-line calls migrate with precedence and original preserved',
    () {
      final script =
          'const body = java.ajax("https://books.test"); return body.trim();';
      final result = migrator.migrateScript(script);
      expect(result.original, script);
      expect(
        result.candidate,
        'const body = (await source.net.get("https://books.test")); return body.trim();',
      );
      expect(result.issues, isEmpty);
    },
  );
  test('comments and strings are never rewritten', () {
    final script = '/* java.ajax("x") */ return "java.ajax(x)";';
    expect(migrator.migrateScript(script).candidate, script);
  });
  test('unsafe constructs require manual work and no partial candidate', () {
    for (final script in [
      'function f(){return java.ajax(url)}',
      'const java = other; return java.ajax(url);',
      'return java[method](url);',
      'return eval("java.ajax(url)");',
      'return java.lang.String(x);',
      'return java.get(url, headers);',
      'return java.ajax(url, 5000);',
      'return /java.ajax/.test(x);',
      'return `java.ajax(${1})`;',
      'return java.ajax("unclosed);',
      'return await java.ajax(url).trim();',
    ]) {
      final result = migrator.migrateScript(script);
      expect(result.candidate, isNull, reason: script);
      expect(result.requiresManualWork, isTrue, reason: script);
    }
  });
  test('missing variable default is preserved', () {
    expect(
      migrator.migrateScript('return java.get("missing");').candidate,
      'return ((await source.variables.get("missing")) ?? "");',
    );
  });
  test('nested utility calls preserve parentheses', () {
    expect(
      migrator
          .migrateScript('return java.base64Decode(java.ajax(url));')
          .candidate,
      'return (await source.encoding.base64DecodeWithFlags((await source.net.get(url))));',
    );
  });
  test(
    'literal timed ajax and parse migrations preserve explicit contracts',
    () {
      expect(
        migrator
            .migrateScript('return java.ajax("https://books.test", 1000);')
            .candidate,
        'return (await source.net.request({url:"https://books.test",timeoutMs:1000})).body;',
      );
      expect(
        migrator
            .migrateScript('return java.getString("class.title@text");')
            .candidate,
        'return (await source.parse.getString("@legacy:class.title@text",result,false,baseUrl));',
      );
      expect(
        migrator.migrateScript('return java.getStringList("");').candidate,
        'return null;',
      );
      expect(
        migrator
            .migrateScript('return java.base64Decode(value, charset);')
            .candidate,
        isNull,
      );
      expect(
        migrator
            .migrateScript('return java.getElement("class.title").text();')
            .candidate,
        isNull,
      );
    },
  );
  test('source migration output is unverified and retains original', () {
    final original = <String, Object?>{
      'bookSourceUrl': 'https://books.test',
      'ruleContent': {'content': '@js:return java.ajax(url);'},
    };
    final result = migrator.migrate(original);
    expect(result.original, original);
    expect(result.status, 'unverified');
    expect(result.toJson()['verified'], false);
    final stages = result.candidate['stages'] as Map;
    expect(
      (stages['content']['fields'] as Map)['content'],
      '@js:return (await source.net.get(url));',
    );
  });
}
