import 'dart:convert';
import 'dart:io';

import 'package:source_engine/source_engine.dart';
import 'package:source_tools/source_tools.dart';
import 'package:test/test.dart';

void main() {
  late Directory temp;
  late String oldPath, newPath;
  const source = {
    'schemaVersion': 1,
    'id': 'a',
    'name': 'A',
    'baseUrl': 'https://private.example',
    'stages': <String, Object?>{},
  };
  setUp(() async {
    temp = await Directory.systemTemp.createTemp('compare_');
    oldPath = '${temp.path}/old.json';
    newPath = '${temp.path}/new.json';
    await File(oldPath).writeAsString('{"bookSourceName":"private"}');
    await File(newPath).writeAsString(jsonEncode(source));
  });
  tearDown(() async => temp.delete(recursive: true));
  SourceCli cli(ExecuteSource execute) => SourceCli(
    migrate: (_) async => throw StateError('Must not migrate'),
    importLegacy: (_) async => SourceDefinition.fromJson({
      ...source,
      'metadata': {'legacy': true},
    }),
    execute: execute,
  );
  List<String> args([List<String> extra = const []]) => [
    'compare',
    oldPath,
    newPath,
    'content',
    ...extra,
  ];

  test(
    'case equality ignores key order, isolates inputs, saves safe report',
    () async {
      var calls = 0;
      final tool = cli((s, stage, variables) async {
        expect(stage, 'content');
        expect(variables['term'], 'private');
        variables['term'] = 'changed';
        calls++;
        return s.metadata['legacy'] == true
            ? [
                {'name': 'private result', 'count': 2},
              ]
            : [
                {'count': 2, 'name': 'private result'},
              ];
      });
      final report = '${temp.path}/report.json';
      final result = await tool.run(
        args(['--variables', '{"term":"private"}', '--report', report]),
      );
      expect(calls, 2);
      expect(result.exitCode, 0);
      expect(result.json['caseEquivalent'], true);
      for (final key in ['sourceVerified', 'verified', 'jvmCompared']) {
        expect(result.json[key], false);
      }
      expect(result.json['baseline'], 'flutterLegacyCompatibility');
      expect(result.json['comparisonScope'], 'stageResult');
      expect(result.json['stateCompared'], false);
      expect((result.json['legacy'] as Map)['executionMode'], 'legacy');
      expect((result.json['candidate'] as Map)['executionMode'], 'modern');
      expect(jsonEncode(result.json), isNot(contains('private')));
      expect(jsonDecode(await File(report).readAsString()), result.json);
      expect(result.json['legacyInputSha256'], hasLength(64));
      expect(
        await File(oldPath).readAsString(),
        '{"bookSourceName":"private"}',
      );
    },
  );
  test('array ordering and numeric type matter', () async {
    for (final value in [
      [2, 1],
      [1.0, 2],
    ]) {
      final result = await cli(
        (s, _, _) async => s.metadata['legacy'] == true ? [1, 2] : value,
      ).run(args());
      expect(result.exitCode, 4);
      expect(result.json['caseEquivalent'], false);
    }
  });
  test(
    'both failures independently captured with no exception messages',
    () async {
      var calls = 0;
      final result = await cli((s, _, _) async {
        calls++;
        if (s.metadata['legacy'] == true) {
          throw EngineException('unsupported_host_api', 'private URL');
        }
        throw StateError('private details');
      }).run(args());
      expect(calls, 2);
      expect(result.exitCode, 1);
      expect(result.json['comparisonScope'], 'stageResult');
      expect(result.json['stateCompared'], false);
      expect(
        (result.json['legacy'] as Map)['errorCode'],
        'unsupported_host_api',
      );
      expect(
        (result.json['candidate'] as Map)['errorCode'],
        'execution_failed',
      );
      expect(jsonEncode(result.json), isNot(contains('private')));
    },
  );
  test('failed baseline still executes candidate', () async {
    final result = await cli((s, _, _) async {
      if (s.metadata['legacy'] == true) throw StateError('private');
      return [];
    }).run(args());
    expect(result.exitCode, 1);
    expect(result.json['comparisonScope'], 'stageResult');
    expect(result.json['stateCompared'], false);
    expect((result.json['candidate'] as Map)['status'], 'success');
  });
  test('legacy candidate allowed and identified', () async {
    await File(newPath).writeAsString(
      jsonEncode({
        ...source,
        'metadata': {'legacy': true},
      }),
    );
    final result = await cli((_, _, _) async => []).run(args());
    expect(result.exitCode, 0);
    expect((result.json['candidate'] as Map)['executionMode'], 'legacy');
    expect(result.json['sourceVerified'], false);
  });
  test('report collisions checked before executing', () async {
    var calls = 0;
    final tool = cli((_, _, _) async {
      calls++;
      return [];
    });
    for (final path in [oldPath, newPath]) {
      expect((await tool.run(args(['--report', path]))).exitCode, 64);
    }
    final report = File('${temp.path}/report.json');
    await report.writeAsString('preserved');
    expect((await tool.run(args(['--report', report.path]))).exitCode, 73);
    expect(calls, 0);
    expect(await report.readAsString(), 'preserved');
  });
  test('missing importer gives explicit error', () async {
    final tool = SourceCli(
      migrate: (_) async => {},
      execute: (_, _, _) async => [],
    );
    final result = await tool.run(args());
    expect(result.exitCode, 1);
    expect((result.json['error'] as Map)['code'], 'legacy_import_unavailable');
  });
  test('invalid input/options are sanitized and never execute', () async {
    var calls = 0;
    final tool = cli((_, _, _) async {
      calls++;
      return [];
    });
    expect((await tool.run(args(['--unknown', 'private']))).exitCode, 64);
    expect((await tool.run(args(['--variables', '[]']))).exitCode, 2);
    await File(newPath).writeAsString('private malformed JSON');
    final result = await tool.run(args());
    expect(result.exitCode, 2);
    expect(jsonEncode(result.json), isNot(contains('private')));
    expect(calls, 0);
  });
}
