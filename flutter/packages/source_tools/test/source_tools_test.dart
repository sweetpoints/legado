import 'dart:convert';
import 'dart:io';

import 'package:source_tools/source_tools.dart';
import 'package:test/test.dart';

void main() {
  late Directory temp;
  late SourceCli cli;
  const source = {
    'schemaVersion': 1,
    'id': 'sample',
    'name': 'Sample',
    'baseUrl': 'https://example.org',
    'stages': <String, Object?>{},
  };
  setUp(() async {
    temp = await Directory.systemTemp.createTemp('source_tools_test_');
    cli = SourceCli(
      migrate: (input) async => {
        'status': 'unverified',
        'candidate': source,
        'original': input,
        'verified': false,
      },
      execute: (source, stage, variables) async => {
        'stage': stage,
        'variables': variables,
      },
    );
  });
  tearDown(() async => temp.delete(recursive: true));
  Future<String> write(Object? value, [String name = 'source.json']) async {
    final file = File('${temp.path}/$name');
    await file.writeAsString(jsonEncode(value));
    return file.path;
  }

  test('validates source and rejects unsupported versions', () async {
    expect((await cli.run(['validate', await write(source)])).exitCode, 0);
    expect(
      (await cli.run([
        'validate',
        await write({...source, 'schemaVersion': 9}),
      ])).exitCode,
      2,
    );
  });
  test(
    'migration preserves original and leaves candidate unverified',
    () async {
      final input = await write({'bookSourceName': 'Old'});
      final before = await File(input).readAsString();
      final output = '${temp.path}/candidate.json';
      final result = await cli.run(['migrate', input, '--output', output]);
      expect(result.exitCode, 3);
      expect(await File(input).readAsString(), before);
      expect(jsonDecode(await File(output).readAsString()), source);
      expect(
        jsonDecode(
          await File('$output.report.json').readAsString(),
        )['verified'],
        false,
      );
    },
  );
  test('migration refuses original path and existing report before writing candidate', () async {
    final input = await write(source);
    expect((await cli.run(['migrate', input, '--output', input])).exitCode, 64);
    final output = '${temp.path}/candidate.json';
    final report = await write({'keep': true}, 'report.json');
    expect(
      (await cli.run([
        'migrate',
        input,
        '--output',
        output,
        '--report',
        report,
      ])).exitCode,
      73,
    );
    expect(await File(output).exists(), false);
    expect(jsonDecode(await File(report).readAsString()), {'keep': true});
  });
  test('execute forwards stage and typed variables', () async {
    final result = await cli.run([
      'execute',
      await write(source),
      'search',
      '--variables',
      '{"keyword":"book","page":2}',
    ]);
    expect(result.exitCode, 0);
    expect(result.json['result'], {
      'stage': 'search',
      'variables': {'keyword': 'book', 'page': 2},
    });
  });
  test('malformed inputs produce JSON errors and stable usage codes', () async {
    expect(
      (await cli.run(['execute', await write(source), 'bogus'])).exitCode,
      64,
    );
    expect((await cli.run(['validate', await write([])])).exitCode, 2);
    expect((await cli.run(['validate', '${temp.path}/missing'])).exitCode, 74);
    expect(
      (await cli.run([
        'validate',
        await write({...source, 'id': 42}),
      ])).json['ok'],
      false,
    );
  });
}
