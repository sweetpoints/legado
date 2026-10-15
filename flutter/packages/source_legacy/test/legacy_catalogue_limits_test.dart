import 'package:source_legacy/source_legacy.dart';
import 'package:test/test.dart';

void main() {
  Map<String, Object?> source(String menu) => {
    'bookSourceUrl': 'https://fixture.invalid',
    'bookSourceName': 'Legacy fixture',
    'exploreUrl': menu,
    'ruleExplore': {'bookList': '.book', 'name': 'a@text'},
    'ruleToc': {
      'chapterList': '.chapter@li',
      'chapterName': 'a@text',
      'chapterUrl': 'a@href',
      'nextTocUrl': 'a.next@href',
    },
  };
  test('legacy catalogues retain the bounded 1000 page allowance', () {
    final imported = LegacySourceImporter().import(
      source('[{"title":"分类","url":"/list"}]'),
    );
    expect(imported.source.stages['toc']!.maxPages, 1000);
    expect(imported.source.stages['explore']!.maxPages, 20);
  });
  test('final menu comma is accepted without changing quoted punctuation', () {
    final imported = LegacySourceImporter().import(
      source('[{"title":"分类,]","url":"/list?x=,]"},\n]'),
    );
    expect(imported.issues.where((i) => i.path == 'exploreUrl'), isEmpty);
    expect(imported.source.metadata['legacyOriginal'], isNotNull);
  });
  test('null and missing menu items still report malformed input', () {
    for (final menu in [
      '[null]',
      '[,]',
      '[{},,]',
      '[{"title":"分类","url":"/list"},null,]',
    ]) {
      final imported = LegacySourceImporter().import(source(menu));
      expect(
        imported.issues.where((i) => i.path == 'exploreUrl'),
        isNotEmpty,
        reason: menu,
      );
    }
  });
}
