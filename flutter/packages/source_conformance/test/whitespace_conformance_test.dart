import 'dart:convert';
import 'dart:io';

import 'package:source_engine/source_engine.dart';
import 'package:source_v8/source_v8.dart';
import 'package:test/test.dart';

void main() {
  final fixture = jsonDecode(
    File('test/support/jsoup_whitespace_golden.json').readAsStringSync(),
  ) as Map;
  for (final (index, raw) in (fixture['cases'] as List).indexed) {
    final sample = raw as Map;
    test('actual V8 Jsoup whitespace golden $index ${sample['rule']}', () async {
      final engine = SourceEngine(runtime: V8Runtime());
      final definition = SourceDefinition(
        id: 'whitespace',
        name: 'whitespace',
        baseUrl: Uri.parse('https://fixture.invalid'),
        script:
            'async function search(){return {value:await source.parse.getString(${jsonEncode(sample['rule'])},${jsonEncode(sample['html'])})};}',
      );
      try {
        expect(
          (await engine.execute(definition, 'search')).single['value'],
          sample['expected'],
        );
      } finally {
        await engine.close();
      }
    });
  }
}
