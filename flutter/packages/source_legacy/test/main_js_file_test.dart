import 'package:source_legacy/source_legacy.dart';
import 'package:test/test.dart';

void main() {
  test('only mainJs file source bypasses non-text pipeline review', () {
    for (final type in [0, 1, 2, 3, 4]) {
      for (final script in [
        '',
        'function getBookInfo(book){return {downloadUrls:["/book.epub"]};}',
      ]) {
        final imported = LegacySourceImporter().import({
          'bookSourceUrl': 'https://files.example',
          'bookSourceName': 'Files',
          'bookSourceType': type,
          'mainJs': script,
        });
        expect(
          imported.issues.any((e) => e.code == 'legacy.non_text_source'),
          type != 0 && !(type == 3 && script.isNotEmpty),
        );
        expect(imported.original['bookSourceType'], type);
      }
    }
  });
}
