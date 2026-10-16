import 'dart:convert';

/// A read-only staging document. Parsing is not a restore or a database commit.
/// Unknown fields and files remain intact for a future lossless restore/export.
class LegacyBackupDocument {
  LegacyBackupDocument._(this.files, this.books);

  final Map<String, String> files;
  final List<LegacyBookRecord> books;

  static const listFiles = {
    'bookshelf.json',
    'bookMemo.json',
    'bookmark.json',
    'highlight.json',
    'highlightRule.json',
    'bookGroup.json',
    'bookSource.json',
    'rssSources.json',
    'rssStar.json',
    'replaceRule.json',
    'readRecord.json',
    'searchHistory.json',
    'sourceSub.json',
    'txtTocRule.json',
    'httpTTS.json',
    'keyboardAssists.json',
    'dictRule.json',
    'autoTask.json',
  };

  /// [files] contains already extracted UTF-8 text, never untrusted paths to read.
  /// A missing list is absent; a present null or malformed list is an error.
  factory LegacyBackupDocument.parse(Map<String, String> files) {
    final copied = Map<String, String>.from(files);
    for (final name in copied.keys) {
      final parts = name.split('/');
      if (name.isEmpty ||
          name.startsWith('/') ||
          name.contains('\\') ||
          name.contains(':') ||
          parts.any((p) => p.isEmpty || p == '.' || p == '..')) {
        throw FormatException('Invalid backup entry name', name);
      }
    }
    final books = <LegacyBookRecord>[];
    for (final name in listFiles) {
      if (!copied.containsKey(name)) continue;
      final value = jsonDecode(copied[name]!);
      if (value is! List) throw FormatException('$name must contain a list');
      for (var index = 0; index < value.length; index++) {
        final item = value[index];
        if (item is! Map<String, dynamic>) {
          throw FormatException('$name[$index] must contain an object');
        }
        if (name == 'bookshelf.json') books.add(LegacyBookRecord(item));
      }
    }
    final identities = <String>{};
    for (final book in books) {
      if (!identities.add(book.url)) {
        throw FormatException('Duplicate bookshelf bookUrl', book.url);
      }
    }
    return LegacyBackupDocument._(
      Map.unmodifiable(copied),
      List.unmodifiable(books),
    );
  }
}

/// Android's persisted character offset is UTF-16, matching Dart String offsets.
/// Chapter identity and the offset are kept independently of screen pagination.
class LegacyBookRecord {
  LegacyBookRecord(Map<String, Object?> input)
    : _json = jsonEncode(input),
      url = _requiredString(input, 'bookUrl'),
      name = _requiredString(input, 'name'),
      author = _optionalString(input, 'author'),
      chapterIndex = _position(input, 'durChapterIndex'),
      characterOffset = _position(input, 'durChapterPos');

  final String _json;
  final String url;
  final String name;
  final String author;
  final int chapterIndex;
  final int characterOffset;

  /// A detached copy prevents nested caller mutations from altering the record.
  Map<String, Object?> toJson() => jsonDecode(_json) as Map<String, Object?>;

  static String _requiredString(Map<String, Object?> input, String key) {
    final value = input[key];
    if (value is! String || value.isEmpty) {
      throw FormatException('Book $key must be a non-empty string');
    }
    return value;
  }

  static String _optionalString(Map<String, Object?> input, String key) {
    final value = input[key];
    if (value == null) return '';
    if (value is! String) throw FormatException('Book $key must be a string');
    return value;
  }

  static int _position(Map<String, Object?> input, String key) {
    if (!input.containsKey(key)) return 0;
    final value = input[key];
    if (value is! int || value < 0) {
      throw FormatException('Book $key must be a non-negative integer');
    }
    return value;
  }
}
