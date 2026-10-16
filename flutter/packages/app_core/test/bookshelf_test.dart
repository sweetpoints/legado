import 'package:app_core/app_core.dart';
import 'package:test/test.dart';

void main() {
  final groups = [
    LegacyBookGroup({'groupId': 1, 'groupName': 'First'}),
  ];
  final books = [
    LegacyBookRecord({
      'bookUrl': 'b',
      'name': 'Beta',
      'author': 'A',
      'group': 1,
      'durChapterTime': 20,
      'latestChapterTime': 10,
      'order': 0,
    }),
    LegacyBookRecord({
      'bookUrl': 'a',
      'name': 'Alpha',
      'author': 'Z',
      'group': 64,
      'type': 256,
      'durChapterTime': 10,
      'latestChapterTime': 30,
      'order': 1,
    }),
    LegacyBookRecord({'bookUrl': 'preview', 'name': 'Preview', 'type': 1024}),
  ];
  test('all six Android sort ids retain meaning', () {
    final expected = [
      ['b', 'a'],
      ['a', 'b'],
      ['a', 'b'],
      ['b', 'a'],
      ['a', 'b'],
      ['b', 'a'],
    ];
    for (final sort in BookshelfSort.values) {
      expect(
        selectBookshelfBooks(books, groups, sort: sort).map((b) => b.url),
        expected[sort.index],
      );
    }
  });
  test('group masks, orphan bits and built-in filters retain semantics', () {
    expect(selectBookshelfBooks(books, groups, groupId: 1).single.url, 'b');
    expect(selectBookshelfBooks(books, groups, groupId: 0).single.url, 'a');
    expect(selectBookshelfBooks(books, groups, groupId: -2).single.url, 'a');
    expect(selectBookshelfBooks(books, groups, groupId: -5).single.url, 'a');
    expect(selectBookshelfBooks(books, groups, groupId: -4), isEmpty);
    expect(selectBookshelfBooks(books, groups, groupId: -99), isEmpty);
  });
}
