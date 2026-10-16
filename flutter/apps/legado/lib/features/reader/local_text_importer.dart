import 'dart:convert';

import 'package:app_core/app_core.dart';
import 'package:crypto/crypto.dart';

/// Imports decoded text without normalizing line endings or UTF-16 positions.
/// The caller chooses the file encoding before passing its decoded text here.
class LocalTextImporter {
  static Future<LegacyBookRecord> importText(
    LibraryRepository repository, {
    required String name,
    required String text,
    String? author,
  }) async {
    if (name.trim().isEmpty || text.trim().isEmpty) {
      throw const FormatException('书名和正文不能为空');
    }
    final url = 'local:txt:${sha256.convert(utf8.encode(text))}';
    final existing = await repository.book(url);
    if (existing != null) return existing;
    final headings = RegExp(
      r'^[ \t\u3000]*(?:第[零〇一二三四五六七八九十百千万两\d]+[章回节卷部篇][^\r\n]{0,80}|(?:chapter|CHAPTER)\s+\d+[^\r\n]{0,80})[ \t]*\r?$',
      multiLine: true,
      caseSensitive: false,
    ).allMatches(text).toList();
    final sections = <({int start, String title})>[];
    if (headings.isEmpty || headings.first.start > 0) {
      sections.add((start: 0, title: headings.isEmpty ? name.trim() : '前言'));
    }
    for (final heading in headings) {
      sections.add((start: heading.start, title: heading.group(0)!.trim()));
    }
    final chapters = <ReaderChapter>[];
    for (var index = 0; index < sections.length; index++) {
      final section = sections[index];
      final end = index + 1 < sections.length
          ? sections[index + 1].start
          : text.length;
      final chapter = ReaderChapter(
        url: '$url#${section.start}',
        title: section.title,
        index: index,
      );
      await repository.putContent(
        url,
        chapter.url,
        text.substring(section.start, end),
      );
      chapters.add(chapter);
    }
    await repository.putChapters(url, chapters);
    final book = LegacyBookRecord({
      'bookUrl': url,
      'name': name.trim(),
      'author': author?.trim() ?? '',
      'origin': 'local',
      'originName': '本地 TXT',
      'type': 0,
      'durChapterIndex': 0,
      'durChapterPos': 0,
      'totalChapterNum': chapters.length,
    });
    await repository.putBook(book);
    return book;
  }
}
