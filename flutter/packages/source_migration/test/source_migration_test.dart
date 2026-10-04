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
          .migrateScript('return java.base64Decode(java.ajax("/value"));')
          .candidate,
      'return (await source.encoding.base64DecodeWithFlags((await source.net.get("/value"))));',
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
      'ruleContent': {'content': '@js:return java.ajax("/value");'},
    };
    final result = migrator.migrate(original);
    expect(result.original, original);
    expect(result.status, 'unverified');
    expect(result.toJson()['verified'], false);
    final stages = result.candidate['stages'] as Map;
    expect(
      (stages['content']['fields'] as Map)['content'],
      '@js:return (await source.net.get("/value"));',
    );
  });
  test('ajax arrays require explicit review without changing string calls', () {
    for (final script in [
      'return java.ajax(["/value", "/missing"]);',
      'return java.ajax(["/value"], 1000);',
      'return java.ajax(Array.from(urls));',
    ]) {
      final result = migrator.migrateScript(script);
      expect(result.original, script);
      expect(result.candidate, isNull, reason: script);
      expect(
        result.issues.map((e) => e.code),
        contains('migration.ajax_array_requires_review'),
        reason: script,
      );
    }
    for (final script in ['return java.ajax("/value");']) {
      expect(migrator.migrateScript(script).issues, isEmpty, reason: script);
    }
  });
  test('unresolved ajax inputs stay manual including extraction arrays and aliases', () {
    for (final script in [
      'return java.ajax(url);',
      'const urls = ["/value"]; return java.ajax(urls);',
      'const a = ["/value"]; const b = a; return java.ajax(b);',
      'const urls = java.getStringList("a@href"); return java.ajax(urls);',
      'return java.ajax("/value" + suffix);',
    ]) {
      final result = migrator.migrateScript(script);
      expect(result.candidate, isNull, reason: script);
      expect(
        result.issues.map((e) => e.code),
        contains('migration.ambiguous_overload'),
        reason: script,
      );
    }
  });
  test(
    'bare source bindings and reads conflict with modern host namespace',
    () {
      for (final script in [
        'const source = "a"; return java.base64Encode(source);',
        'source = "a"; return java.base64Encode(source);',
        'return java.base64Encode(source.name);',
        'return source;',
      ]) {
        final result = migrator.migrateScript(script);
        expect(result.candidate, isNull, reason: script);
        expect(
          result.issues.map((e) => e.code),
          contains('migration.host_binding_conflict'),
          reason: script,
        );
      }
      final result = migrator.migrateScript(
        '/* source is a legacy object */ return java.base64Encode(obj.source + "source");',
      );
      expect(result.issues, isEmpty);
      expect(result.candidate, contains('source.encoding.base64Encode'));
      expect(result.candidate, contains('obj.source + "source"'));
    },
  );
  test('final source metadata follows resolved issues without claiming verification', () {
    final original = <String, Object?>{
      'bookSourceUrl': 'https://books.test',
      'ruleContent': {
        'content': '@js:const value = java.md5Encode("book"); return value;',
      },
    };
    final result = migrator.migrate(original);
    expect(result.issues, isEmpty);
    expect(result.status, 'unverified');
    expect(result.candidate['metadata'], containsPair('legacy', false));
    expect(
      result.candidate['metadata'],
      containsPair('compatibility', 'unverified'),
    );
    expect(result.toJson()['verified'], false);
    expect(result.original, original);
    expect(
      (original['ruleContent'] as Map)['content'],
      contains('java.md5Encode'),
    );
  });
  test('partial source candidates retain legacy mode and original rules', () {
    final original = <String, Object?>{
      'bookSourceUrl': 'https://books.test',
      'ruleContent': {'content': '@js:return java.ajax("/value");'},
      'ruleBookInfo': {'name': '@js:return java.ajax(["/value"]);'},
    };
    final result = migrator.migrate(original);
    expect(result.status, 'manualRequired');
    expect(result.candidate['metadata'], containsPair('legacy', true));
    expect(
      result.candidate['metadata'],
      containsPair('compatibility', 'manualRequired'),
    );
    expect(result.toJson()['verified'], false);
    final stages = result.candidate['stages'] as Map;
    expect(stages['content']['fields']['content'], contains('source.net.get'));
    expect(
      stages['info']['fields']['name'],
      '@js:return java.ajax(["/value"]);',
    );
    expect(result.original, original);
  });
}
