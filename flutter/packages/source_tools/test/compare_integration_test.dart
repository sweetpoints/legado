import 'dart:convert';
import 'dart:io';

import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:source_migration/source_migration.dart';
import 'package:source_tools/source_tools.dart';
import 'package:source_v8/source_v8.dart';
import 'package:test/test.dart';

import '../../source_conformance/test/support/fixture_server.dart';

class _NoHost implements ScriptHost {
  @override
  Future<Object?> call(String method, List<Object?> arguments) =>
      throw UnsupportedError(method);
}

void main() {
  late FixtureServer server;
  late Directory directory;
  setUp(() async {
    server = await FixtureServer.start();
    directory = await Directory.systemTemp.createTemp('source-compare-v8-');
  });
  tearDown(() async {
    await server.close();
    await directory.delete(recursive: true);
  });

  Map<String, Object?> original() => {
    'bookSourceUrl': server.baseUrl.toString(),
    'bookSourceName': '固定比较书源',
    'searchUrl': '/value',
    'ruleSearch': {
      'name': '@js:java.base64Encode("proof")',
      'value': r'$.value',
    },
  };

  Future<Object?> execute(
    SourceDefinition source,
    String stage,
    Map<String, Object?> variables,
  ) async {
    final runtime = V8Runtime(
      prelude: source.metadata['legacy'] == true ? legacyScriptPrelude : '',
    );
    final engine = createCliEngine(source, runtime);
    try {
      return await engine.execute(source, stage, input: variables);
    } finally {
      await engine.close();
    }
  }

  test(
    'whole-source migration executes modern host and does not expose java',
    () async {
      final imported = LegacySourceImporter().import(original());
      final report = SourceMigrator().migrate(original());
      expect(report.issues, isEmpty);
      final candidate = SourceDefinition.fromJson(report.candidate);
      expect(candidate.metadata['legacy'], isNot(true));
      final runtime = V8Runtime(
        prelude: candidate.metadata['legacy'] == true
            ? legacyScriptPrelude
            : '',
      );
      final engine = createCliEngine(candidate, runtime);
      try {
        expect(
          await runtime.evaluate('typeof java', ScriptContext(host: _NoHost())),
          'undefined',
        );
        final newResult = await engine.execute(candidate, 'search');
        final baseline = await execute(imported.source, 'search', {});
        expect(newResult, baseline);
        expect(newResult, [
          {'name': 'cHJvb2Y=', 'value': '异步结果'},
        ]);
      } finally {
        await engine.close();
      }
    },
  );

  test(
    'CLI compare uses actual V8 modes and emits narrow equivalence evidence',
    () async {
      final input = File('${directory.path}/legacy.json')
        ..writeAsStringSync(jsonEncode(original()));
      final migrated = SourceMigrator().migrate(original());
      expect(migrated.issues, isEmpty);
      final candidate = File('${directory.path}/candidate.json')
        ..writeAsStringSync(jsonEncode(migrated.candidate));
      final report = File('${directory.path}/comparison.json');
      final cli = SourceCli(
        importLegacy: (value) async =>
            LegacySourceImporter().import(value).source,
        migrate: (value) async => SourceMigrator().migrate(value).toJson(),
        execute: execute,
      );
      final result = await cli.run([
        'compare',
        input.path,
        candidate.path,
        'search',
        '--report',
        report.path,
      ]);
      expect(result.exitCode, 0);
      expect(result.json['caseEquivalent'], true);
      expect(result.json['sourceVerified'], false);
      expect(result.json['verified'], false);
      expect(result.json['jvmCompared'], false);
      expect(result.json['baseline'], 'flutterLegacyCompatibility');
      final oldSide = result.json['legacy'] as Map;
      final newSide = result.json['candidate'] as Map;
      expect(oldSide['executionMode'], 'legacy');
      expect(newSide['executionMode'], 'modern');
      expect(oldSide['status'], 'success');
      expect(newSide['status'], 'success');
      expect(newSide['resultSha256'], oldSide['resultSha256']);
      expect(jsonDecode(report.readAsStringSync()), result.json);
      expect(report.readAsStringSync(), isNot(contains('异步结果')));
      expect(server.requests, ['/value', '/value']);
    },
  );
}
