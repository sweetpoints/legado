import 'dart:convert';

import 'package:app_core/app_core.dart';
import 'package:crypto/crypto.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

part 'reader_bookmarks.g.dart';

/// Android Bookmark uses time as its key and bookName/bookAuthor for ownership.
/// New entries additionally bind exact opaque book and chapter identities.
final class ReaderBookmark {
  ReaderBookmark(
    Map<String, Object?> fields, {
    required this.id,
    required this.legacy,
    this.ambiguous = false,
  }) : _json = jsonEncode(fields);
  final String _json;
  final String id;
  final bool legacy, ambiguous;
  Map<String, Object?> toJson() => jsonDecode(_json) as Map<String, Object?>;
  String get chapterName => toJson()['chapterName'] as String? ?? '';
  String get note => toJson()['content'] as String? ?? '';
  String get excerpt => toJson()['bookText'] as String? ?? '';
  int? get position =>
      toJson()['chapterPos'] is int ? toJson()['chapterPos'] as int : null;

  /// New bookmarks require chapter URL equality. Legacy records never silently
  /// jump into a different book that merely shares its title and author.
  int? chapterIn(List<ReaderChapter> chapters) {
    if (ambiguous || position == null || position! < 0) return null;
    final fields = toJson();
    if (!legacy) {
      final matching = chapters.indexWhere(
        (c) => !c.isVolume && c.url == fields['chapterUrl'],
      );
      return matching < 0 ? null : matching;
    }
    final byIndex = chapters.indexWhere(
      (c) => !c.isVolume && c.index == fields['chapterIndex'],
    );
    if (byIndex >= 0 &&
        (chapterName.isEmpty || chapters[byIndex].title == chapterName)) {
      return byIndex;
    }
    final byTitle = [
      for (var i = 0; i < chapters.length; i++)
        if (!chapters[i].isVolume && chapters[i].title == chapterName) i,
    ];
    return byTitle.length == 1 ? byTitle.single : null;
  }
}

@Riverpod(name: 'readerBookmarksProvider')
Future<List<ReaderBookmark>> readerBookmarks(
  Ref ref,
  ({LibraryRepository repository, LegacyBookRecord book}) query,
) => ReaderBookmarks(query.repository).list(query.book);

/// Stored through LibraryRepository.setting, which Drift commits durably and
/// includes in its versioned backup extension. Legacy backup bytes stay intact.
final class ReaderBookmarks {
  ReaderBookmarks(this.repository);
  final LibraryRepository repository;
  static const settingKey = 'reader.bookmarks.v1';
  static final _gates = Expando<_Gate>();
  _Gate get _gate => _gates[repository] ??= _Gate();

  Future<Map<String, Object?>> _document() async {
    final text = await repository.setting(settingKey);
    if (text == null) {
      return {
        'version': 1,
        'items': <Object?>[],
        'deletedLegacyIds': <String>[],
      };
    }
    final document = jsonDecode(text);
    if (document is! Map<String, dynamic> ||
        document['version'] != 1 ||
        document['items'] is! List ||
        document['deletedLegacyIds'] is! List) {
      throw const FormatException('Unsupported bookmark document');
    }
    if ((document['items'] as List).any(
          (item) => item is! Map<String, dynamic>,
        ) ||
        (document['deletedLegacyIds'] as List).any((id) => id is! String)) {
      throw const FormatException('Invalid bookmark document');
    }
    return document;
  }

  Future<List<Map<String, Object?>>> _legacy() async {
    final text = repository is LegacyBackupFileReader
        ? await (repository as LegacyBackupFileReader).backupFile(
            'bookmark.json',
          )
        : (await repository.backupFiles())['bookmark.json'];
    if (text == null) return [];
    final items = jsonDecode(text);
    if (items is! List || items.any((item) => item is! Map<String, dynamic>)) {
      throw const FormatException('Invalid legacy bookmark list');
    }
    return items.cast<Map<String, dynamic>>();
  }

  String _legacyId(Map<String, Object?> value) {
    final time = value['time'];
    if (time is int) return 'legacy:$time';
    Object? canonical(Object? value) {
      if (value is Map<String, Object?>) {
        return {
          for (final key in value.keys.toList()..sort())
            key: canonical(value[key]),
        };
      }
      if (value is List) return value.map(canonical).toList();
      return value;
    }

    return 'legacy:${sha256.convert(utf8.encode(jsonEncode(canonical(value))))}';
  }

  Future<List<ReaderBookmark>> list(LegacyBookRecord book) =>
      _gate.run(() async {
        final document = await _document();
        final deleted = (document['deletedLegacyIds'] as List)
            .cast<String>()
            .toSet();
        final legacy = await _legacy();
        final sameName = (await repository.books())
            .where((b) => b.name == book.name && b.author == book.author)
            .toList();
        final ambiguous =
            sameName.length > 1 ||
            (sameName.length == 1 && sameName.single.url != book.url);
        final rows = <ReaderBookmark>[
          for (final item
              in (document['items'] as List).cast<Map<String, dynamic>>())
            if (item['bookUrl'] == book.url)
              ReaderBookmark(item, id: item['id'] as String, legacy: false),
          for (final item in legacy)
            if (item['bookName'] == book.name &&
                (item['bookAuthor'] ?? '') == book.author &&
                !deleted.contains(_legacyId(item)))
              ReaderBookmark(
                item,
                id: _legacyId(item),
                legacy: true,
                ambiguous: ambiguous,
              ),
        ];
        rows.sort((a, b) {
          final x = a.toJson()['chapterIndex'];
          final y = b.toJson()['chapterIndex'];
          final chapter = (x is int ? x : 0).compareTo(y is int ? y : 0);
          return chapter != 0
              ? chapter
              : (a.position ?? 0).compareTo(b.position ?? 0);
        });
        return List.unmodifiable(rows);
      });

  Future<void> add(
    LegacyBookRecord book,
    ReaderChapter chapter,
    int offset,
    String text, {
    String note = '',
  }) => _gate.run(() async {
    if (chapter.isVolume || chapter.url.isEmpty || chapter.index < 0) {
      throw const FormatException('Invalid bookmark chapter');
    }
    final document = await _document();
    final items = (document['items'] as List)
        .cast<Map<String, dynamic>>()
        .toList();
    final position = safeOffset(text, offset);
    final same = items
        .where(
          (item) =>
              item['bookUrl'] == book.url &&
              item['chapterUrl'] == chapter.url &&
              item['chapterPos'] == position,
        )
        .firstOrNull;
    if (same != null) {
      if (same['content'] != note) {
        same['content'] = note;
        document['items'] = items;
        await repository.setSetting(settingKey, jsonEncode(document));
      }
      return;
    }
    final time = DateTime.now().microsecondsSinceEpoch;
    final identity = sha256.convert(
      utf8.encode(jsonEncode([book.url, chapter.url, position, time])),
    );
    var end = (position + 160).clamp(0, text.length);
    end = safeOffset(text, end);
    items.add({
      'id': 'flutter:$identity',
      'time': time ~/ 1000,
      'bookUrl': book.url,
      'bookName': book.name,
      'bookAuthor': book.author,
      'chapterUrl': chapter.url,
      'chapterIndex': chapter.index,
      'chapterPos': position,
      'chapterName': chapter.title,
      'bookText': text.substring(position, end),
      'content': note,
    });
    document['items'] = items;
    await repository.setSetting(settingKey, jsonEncode(document));
  });

  Future<void> remove(ReaderBookmark bookmark) => _gate.run(() async {
    final document = await _document();
    if (bookmark.legacy) {
      document['deletedLegacyIds'] = {
        ...(document['deletedLegacyIds'] as List).cast<String>(),
        bookmark.id,
      }.toList();
    } else {
      document['items'] = (document['items'] as List)
          .where((item) => (item as Map)['id'] != bookmark.id)
          .toList();
    }
    await repository.setSetting(settingKey, jsonEncode(document));
  });

  static int safeOffset(String text, int value) {
    var offset = value.clamp(0, text.length);
    if (offset > 0 &&
        offset < text.length &&
        text.codeUnitAt(offset - 1) >= 0xd800 &&
        text.codeUnitAt(offset - 1) <= 0xdbff &&
        text.codeUnitAt(offset) >= 0xdc00 &&
        text.codeUnitAt(offset) <= 0xdfff) {
      offset--;
    }
    return offset;
  }
}

final class _Gate {
  Future<void> _tail = Future.value();
  Future<T> run<T>(Future<T> Function() operation) {
    final result = _tail.then((_) => operation());
    _tail = result.then<void>((_) {}, onError: (Object _, StackTrace _) {});
    return result;
  }
}
