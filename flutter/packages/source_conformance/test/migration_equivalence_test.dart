import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:source_migration/source_migration.dart';
import 'package:source_v8/source_v8.dart';
import 'package:test/test.dart';

class _NoHost implements ScriptHost {
  @override
  Future<Object?> call(String method, List<Object?> arguments) =>
      throw UnsupportedError(method);
}

void main() {
  late V8Runtime legacy;
  late V8Runtime modern;
  setUp(() {
    legacy = V8Runtime(prelude: legacyScriptPrelude);
    modern = V8Runtime();
  });
  tearDown(() async {
    await legacy.close();
    await modern.close();
  });
  const scripts = [
    'return java.base64Encode("a",0);',
    'return java.base64Decode("6Q==","ISO-8859-1");',
    'return java.base64DecodeToByteArray("/w==");',
    'return java.bytesToStr(java.strToBytes("中文","GBK"),"GBK");',
    'return java.hexDecodeToByteArray("fff");',
    'return java.md5Encode("abc");',
    'return java.md5Encode16("abc");',
    'return java.digestHex("abc","SHA-256");',
    'return java.digestBase64Str("abc","SHA-256");',
    'return java.encodeURI("中文 +","GBK");',
  ];
  for (final script in scripts) {
    test('actual V8 utility migration preserves result: $script', () async {
      final migration = SourceMigrator().migrateScript(script);
      expect(migration.issues, isEmpty);
      expect(migration.candidate, isNotNull);
      final expected = await legacy.evaluate(
        script,
        ScriptContext(host: LegacyScriptHost(_NoHost())),
      );
      final actual = await modern.evaluate(
        migration.candidate!,
        ScriptContext(host: SourceUtilityHost(_NoHost())),
      );
      expect(actual, expected);
    });
  }
}
