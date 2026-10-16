import 'dart:convert';

import 'legacy_backup.dart';

/// Lossless Android BookGroup record. Positive ids are membership bits; negative
/// ids are built-in filters and must never be assigned to a book's bit mask.
class LegacyBookGroup {
  LegacyBookGroup(Map<String, Object?> input)
    : _json = jsonEncode(input),
      id = _integer(input, 'groupId'),
      name = input['groupName'] as String? ?? '',
      order = _integer(input, 'order', fallback: 0),
      sort = _integer(input, 'bookSort', fallback: -1),
      show = input['show'] as bool? ?? true;

  final String _json;
  final int id;
  final String name;
  final int order;
  final int sort;
  final bool show;
  bool get isAssignable => id > 0 && (id & (id - 1)) == 0;
  Map<String, Object?> toJson() => jsonDecode(_json) as Map<String, Object?>;

  static int _integer(Map<String, Object?> input, String key, {int? fallback}) {
    final value = input[key] ?? fallback;
    if (value is! int) {
      throw FormatException('Book group $key must be an integer');
    }
    return value;
  }
}

/// Android's persisted bookSort values, including -1 on a group for inheritance.
enum BookshelfSort { recentlyRead, updated, title, manual, activity, author }

abstract interface class BookshelfLibraryRepository {
  Future<List<LegacyBookGroup>> bookGroups();
  Future<LegacyBookGroup> createBookGroup(String name);
  Future<void> updateBookGroup(LegacyBookGroup group);
  Future<void> removeBookGroup(int id);
  Future<void> setBookGroups(String bookUrl, List<int> groupIds);

  /// Reorders a visible subset while leaving other books in their existing slots.
  Future<void> reorderBooks(List<String> orderedUrls);
}

int bookInteger(LegacyBookRecord book, String key) =>
    (book.toJson()[key] as num?)?.toInt() ?? 0;

bool isLocalBook(LegacyBookRecord book) {
  final json = book.toJson();
  return bookInteger(book, 'type') & 256 != 0 ||
      json['origin'] == 'local' ||
      json['origin'] == 'loc_book' ||
      book.url.startsWith('local:');
}

/// Native filter ids are retained. Zero is Flutter's explicit ungrouped view;
/// membership uses all known positive bits and ignores missing/future group ids.
List<LegacyBookRecord> selectBookshelfBooks(
  Iterable<LegacyBookRecord> books,
  List<LegacyBookGroup> groups, {
  int groupId = -1,
  BookshelfSort sort = BookshelfSort.recentlyRead,
}) {
  final known = groups.where((g) => g.id > 0).fold(0, (mask, g) => mask | g.id);
  final selected = books.where((book) {
    final type = bookInteger(book, 'type');
    if (type & 1024 != 0) return false; // Android BookType.notShelf.
    final grouped = bookInteger(book, 'group') & known != 0;
    return switch (groupId) {
      -1 => true,
      0 => !grouped,
      -2 => isLocalBook(book),
      -3 => type & 32 != 0,
      -6 => type & 4 != 0,
      -11 => type & 16 != 0,
      -4 || -100 => !isLocalBook(book) && type & (32 | 4) == 0 && !grouped,
      -5 => isLocalBook(book) && !grouped,
      > 0 => bookInteger(book, 'group') & groupId != 0,
      _ => false,
    };
  }).toList();
  int activity(LegacyBookRecord book) {
    final read = bookInteger(book, 'durChapterTime');
    final updated = bookInteger(book, 'latestChapterTime');
    return read > updated ? read : updated;
  }

  selected.sort((a, b) {
    final comparison = switch (sort) {
      BookshelfSort.recentlyRead => bookInteger(
        b,
        'durChapterTime',
      ).compareTo(bookInteger(a, 'durChapterTime')),
      BookshelfSort.updated => bookInteger(
        b,
        'latestChapterTime',
      ).compareTo(bookInteger(a, 'latestChapterTime')),
      BookshelfSort.title => a.name.compareTo(b.name),
      BookshelfSort.manual => bookInteger(
        a,
        'order',
      ).compareTo(bookInteger(b, 'order')),
      BookshelfSort.activity => activity(b).compareTo(activity(a)),
      BookshelfSort.author => a.author.compareTo(b.author),
    };
    return comparison == 0 ? a.url.compareTo(b.url) : comparison;
  });
  return selected;
}
