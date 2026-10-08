import 'package:source_migration/source_migration.dart';
import 'package:test/test.dart';

void main() {
  test(
    'literal replacement migration preserves originals and is only unverified',
    () {
      final input = <String, Object?>{
        'bookSourceUrl': 'https://books.test',
        'searchUrl': '/search',
        'ruleSearch': {'name': 'p@text##旧##新'},
      };
      final result = SourceMigrator().migrate(input);
      expect(result.original, input);
      expect(result.issues, isEmpty);
      expect(result.status, 'unverified');
      expect(
        result.candidate['metadata'],
        containsPair('compatibility', 'unverified'),
      );
      expect(
        (result.candidate['stages'] as Map)['search']['fields']['name'],
        '@legacy:p@text##旧##新',
      );
    },
  );
  test('literal replacement does not remove independent pipeline issues', () {
    final result = SourceMigrator().migrate({
      'bookSourceUrl': 'https://books.test',
      'searchUrl': '/search',
      'ruleSearch': {'name': 'p@text##旧##新'},
      'ruleContent': {'imageDecode': 'script'},
    });
    expect(result.status, 'manualRequired');
    expect(result.issues.map((i) => i.code), [
      'legacy.pipeline_requires_review',
    ]);
    expect(result.candidate['metadata'], containsPair('legacy', true));
  });
  test('mixed mainJs App hooks cannot become an unverified migration', () {
    for (final hook in ['imageDecode', 'payAction', 'callBackJs']) {
      final input = <String, Object?>{
        'bookSourceUrl': 'https://books.test',
        'mainJs': 'function getContent() { return "text"; }',
        'ruleContent': {
          'content': '@js:throw "owned by mainJs";',
          hook: 'script',
        },
      };
      final result = SourceMigrator().migrate(input);
      expect(result.original, input);
      expect(result.status, 'manualRequired');
      expect(result.issues.single.path, 'ruleContent.$hook');
      expect(result.issues.single.code, 'legacy.pipeline_requires_review');
      expect(result.candidate['metadata'], containsPair('legacy', true));
    }
  });
  test('debug and rendering configuration survives migration without pipeline claims', () {
    for (final mainJs in [false, true]) {
      final input = <String, Object?>{
        'bookSourceUrl': 'https://books.test',
        if (mainJs) 'mainJs': 'function getContent(){return "text";}',
        'ruleSearch': {'checkKeyWord': 'reader'},
        'ruleContent': {
          'imageStyle': {'style': 'FULL'},
        },
      };
      final result = SourceMigrator().migrate(input);
      expect(result.original, input);
      expect(result.issues, isEmpty);
      expect(result.status, 'unverified');
      expect(
        result.candidate['metadata'],
        containsPair('compatibility', 'unverified'),
      );
      expect((result.candidate['metadata'] as Map)['legacyOriginal'], input);
      for (final stage in (result.candidate['stages'] as Map).values) {
        expect(stage['fields'], isNot(contains('checkKeyWord')));
        expect(stage['fields'], isNot(contains('imageStyle')));
      }
    }
  });
}
