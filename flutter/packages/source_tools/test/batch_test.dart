import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:source_tools/source_tools.dart';
import 'package:test/test.dart';

void main() {
  late Directory temp;
  late SourceCli cli;
  setUp(() async {
    temp = await Directory.systemTemp.createTemp('source_batch_test_');
    cli = SourceCli(
      migrate: (input) async {
        if (input['invalid'] == true) {
          throw const FormatException('secret https://private.example');
        }
        return {
          'status': input['manual'] == true ? 'manualRequired' : 'ready',
          'original': input,
          'candidate': {'name': input['name']},
          'issues': input['manual'] == true
              ? [
                  {
                    'code': 'legacy.webview',
                    'path': 'ruleContent',
                    'message': 'Requires browser',
                  },
                ]
              : [],
          'verified': false,
        };
      },
      execute: (_, _, _) async => throw StateError('Audit must not execute'),
    );
  });
  tearDown(() async => temp.delete(recursive: true));
  Future<File> input() async {
    return File('${temp.path}/original.json')..writeAsStringSync(
      jsonEncode([
        {'name': 'private name', 'bookSourceUrl': 'https://private.example'},
        {'name': 'manual', 'manual': true},
        42,
        {'invalid': true},
      ]),
    );
  }

  test(
    'mixed audit accounts for every entry and hashes exact original bytes',
    () async {
      final file = await input();
      final bytes = await file.readAsBytes();
      final result = await cli.run(['audit', file.path]);
      expect(result.exitCode, 3);
      expect(result.json['sourceCount'], 4);
      expect(result.json['inputSha256'], sha256.convert(bytes).toString());
      expect(result.json['statusCounts'], {
        'unverified': 1,
        'manualRequired': 1,
        'noExecution': 2,
      });
      expect(result.json['issueCountsByCapability'], {
        'ruleContent': 1,
        'input.invalid_source': 2,
      });
      expect(jsonEncode(result.json), isNot(contains('private')));
      expect(result.json['executed'], false);
    },
  );
  test('batch preserves input, writes all reports, no candidate for invalid entries', () async {
    final file = await input();
    final before = await file.readAsString();
    final output = '${temp.path}/batch';
    final result = await cli.run(['migrate', file.path, '--output', output]);
    expect(result.exitCode, 3);
    expect(await File(file.path).readAsString(), before);
    for (var i = 0; i < 4; i++) {
      expect(
        await File('$output/${i.toString().padLeft(5, '0')}.report.json')
            .exists(),
        true,
      );
    }
    expect(await File('$output/00000.candidate.json').exists(), true);
    expect(await File('$output/00002.candidate.json').exists(), false);
    expect(await File('$output/audit.json').exists(), true);
  });
  test(
    'batch rejects all report collisions before creating output directory',
    () async {
      final file = await input();
      final output = '${temp.path}/batch';
      expect(
        (await cli.run([
          'migrate',
          file.path,
          '--output',
          output,
          '--report',
          file.path,
        ])).exitCode,
        64,
      );
      expect(await Directory(output).exists(), false);
      expect(
        (await cli.run([
          'migrate',
          file.path,
          '--output',
          output,
          '--report',
          '$output/00000.report.json',
        ])).exitCode,
        64,
      );
      expect(await Directory(output).exists(), false);
      await Directory(output).create();
      expect(
        (await cli.run(['migrate', file.path, '--output', output])).exitCode,
        73,
      );
    },
  );
}
