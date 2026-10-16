import 'dart:async';
import 'dart:convert';

import 'package:app_core/app_core.dart';
import 'package:dio/dio.dart';
import 'package:xml/xml.dart';

import 'rss_models.dart';

/// Versioned single-record commit keeps read/star state and source imports atomic.
class RssStore {
  RssStore(this.repository);
  final LibraryRepository repository;
  static const storageKey = 'rss.library.v1';
  Future<void> _pending = Future.value();

  Future<Map<String, Object?>> _load() async {
    final text = await repository.setting(storageKey);
    if (text == null) {
      return {'sources': <Object?>[], 'articles': <String, Object?>{}};
    }
    final value = jsonDecode(text);
    if (value is! Map<String, dynamic> ||
        value['sources'] is! List ||
        value['articles'] is! Map) {
      throw const FormatException('rssCorruptStorage');
    }
    return Map<String, Object?>.of(value);
  }

  Future<T> _commit<T>(Future<T> Function(Map<String, Object?>) mutation) {
    final next = _pending.then((_) async {
      final data = await _load();
      final result = await mutation(data);
      await repository.setSetting(storageKey, jsonEncode(data));
      return result;
    });
    _pending = next.then<void>((_) {}, onError: (Object _, StackTrace _) {});
    return next;
  }

  Future<List<RssSource>> sources() async =>
      ((await _load())['sources'] as List)
          .map((e) => RssSource.fromJson(Map<String, Object?>.from(e as Map)))
          .toList();

  Future<void> importSources(String text) async {
    final decoded = jsonDecode(text);
    final list = decoded is List ? decoded : [decoded];
    // Validate the entire batch before committing any record.
    final imported = list.map((e) {
      if (e is! Map) throw const FormatException('rssInvalidSource');
      return RssSource.fromJson(Map<String, Object?>.from(e));
    }).toList();
    await _commit((data) async {
      final existing = (data['sources'] as List)
          .map((e) => Map<String, Object?>.from(e as Map))
          .toList();
      for (final source in imported) {
        final i = existing.indexWhere(
          (e) => (e['sourceUrl'] ?? e['url']) == source.url,
        );
        if (i < 0) {
          existing.add(source.toJson());
        } else {
          existing[i] = {...existing[i], ...source.toJson()};
        }
      }
      data['sources'] = existing;
    });
  }

  Future<List<RssArticle>> articles(String source) async {
    final all = (await _load())['articles'] as Map;
    return ((all[source] as List?) ?? [])
        .map((e) => RssArticle.fromJson(Map<String, Object?>.from(e as Map)))
        .toList();
  }

  Future<void> saveArticles(String source, List<RssArticle> fetched) => _commit(
    (data) async {
      final all = Map<String, Object?>.from(data['articles'] as Map);
      final old = ((all[source] as List?) ?? [])
          .map((e) => RssArticle.fromJson(Map<String, Object?>.from(e as Map)))
          .toList();
      final merged = <RssArticle>[];
      final seen = <String>{};
      for (final article in fetched) {
        if (!seen.add(article.id)) continue;
        final previous = old.where((e) => e.id == article.id).firstOrNull;
        merged.add(
          article.copyWith(
            read: previous?.read ?? false,
            starred: previous?.starred ?? false,
            original: {...?previous?.original, ...article.original},
          ),
        );
      }
      // Keep older articles for offline reading and bookmarks.
      merged.addAll(old.where((e) => !seen.contains(e.id)));
      all[source] = merged.map((e) => e.toJson()).toList();
      data['articles'] = all;
    },
  );

  Future<void> mark(String source, String id, {bool? read, bool? starred}) =>
      _commit((data) async {
        final all = Map<String, Object?>.from(data['articles'] as Map);
        final values = ((all[source] as List?) ?? []).map((e) {
          final value = Map<String, Object?>.from(e as Map);
          if (value['id'] == id) {
            if (read != null) value['read'] = read;
            if (starred != null) value['starred'] = starred;
          }
          return value;
        }).toList();
        all[source] = values;
        data['articles'] = all;
      });
}

class RssFeedClient {
  RssFeedClient(this.dio);
  final Dio dio;
  static const maxBytes = 5 * 1024 * 1024;

  Future<List<RssArticle>> fetch(
    RssSource source, {
    CancelToken? cancelToken,
  }) async {
    if (source.requiresRuleEngine) {
      throw const FormatException('rssLegacyRules');
    }
    final headers = <String, Object?>{
      'Accept': 'application/rss+xml, application/atom+xml, application/xml, text/xml',
    };
    final sourceHeader = source.original['header'];
    if (sourceHeader is String && sourceHeader.trim().isNotEmpty) {
      if (sourceHeader.contains('{{') ||
          sourceHeader.contains('<js>') ||
          sourceHeader.startsWith('@js:')) {
        throw const FormatException('rssLegacyRules');
      }
      final decoded = jsonDecode(sourceHeader);
      if (decoded is! Map || decoded.values.any((value) => value is! String)) {
        throw const FormatException('rssInvalidSource');
      }
      for (final entry in decoded.entries) {
        headers[entry.key.toString()] = entry.value;
      }
    }
    final response = await dio.get<ResponseBody>(
      source.url,
      cancelToken: cancelToken,
      options: Options(
        responseType: ResponseType.stream,
        receiveTimeout: const Duration(seconds: 30),
        sendTimeout: const Duration(seconds: 15),
        headers: headers,
      ),
    );
    final body = response.data;
    if (body == null) throw const FormatException('rssEmptyResponse');
    final bytes = <int>[];
    await for (final chunk in body.stream) {
      if (cancelToken?.isCancelled ?? false) {
        throw cancelToken!.cancelError!;
      }
      if (bytes.length + chunk.length > maxBytes) {
        throw const FormatException('rssTooLarge');
      }
      bytes.addAll(chunk);
    }
    if (cancelToken?.isCancelled ?? false) {
      throw cancelToken!.cancelError!;
    }
    // RSS/Atom default to UTF-8; honor an explicit Latin-1 declaration.
    final declaration = latin1.decode(bytes.take(200).toList());
    final contentType = response.headers.value('content-type') ?? '';
    final latin = RegExp(
      r'(?:iso-8859-1|latin-1)',
      caseSensitive: false,
    ).hasMatch('$declaration $contentType');
    return parseFeed(
      latin ? latin1.decode(bytes) : utf8.decode(bytes),
      response.realUri,
    );
  }
}

List<RssArticle> parseFeed(String text, Uri base) {
  late XmlDocument document;
  try {
    document = XmlDocument.parse(text);
  } on XmlException {
    throw const FormatException('rssInvalidFeed');
  }
  final root = document.rootElement;
  final atom = root.name.local == 'feed';
  final channel = root.childElements
      .where((e) => e.name.local == 'channel')
      .firstOrNull;
  if (!atom && (root.name.local != 'rss' || channel == null)) {
    throw const FormatException('rssInvalidFeed');
  }
  String value(XmlElement e, List<String> names) {
    for (final name in names) {
      final match = e.childElements
          .where((e) => e.name.local == name)
          .firstOrNull;
      if (match != null && match.innerText.trim().isNotEmpty) {
        return match.innerText.trim();
      }
    }
    return '';
  }

  final entries = (atom ? root : channel!).childElements.where(
    (e) => e.name.local == (atom ? 'entry' : 'item'),
  );
  final result = <RssArticle>[];
  final seen = <String>{};
  for (final entry in entries) {
    var entryBase = base;
    for (final element in [
      ...entry.ancestors.whereType<XmlElement>().toList().reversed,
      entry,
    ]) {
      final relative = element.getAttribute('xml:base');
      if (relative != null) entryBase = entryBase.resolve(relative);
    }
    final linkElement = entry.childElements
        .where(
          (e) =>
              e.name.local == 'link' &&
              (e.getAttribute('rel') ?? 'alternate') == 'alternate',
        )
        .firstOrNull;
    final rawLink = atom
        ? linkElement?.getAttribute('href') ?? ''
        : value(entry, ['link']);
    final resolved = rawLink.isEmpty ? '' : entryBase.resolve(rawLink);
    final link = resolved is Uri && ['https', 'http'].contains(resolved.scheme)
        ? resolved.toString()
        : '';
    final id = value(entry, atom ? ['id'] : ['guid']);
    final title = value(entry, ['title']);
    final content = value(
      entry,
      atom ? ['content', 'summary'] : ['encoded', 'description'],
    );
    final stableId = id.isNotEmpty
        ? id
        : link.isNotEmpty
        ? link
        : '$title\u0000$content';
    if (!seen.add(stableId)) continue;
    result.add(
      RssArticle(
        id: stableId,
        title: title,
        link: link,
        content: content,
        published: value(entry, ['published', 'updated', 'pubDate', 'date']),
      ),
    );
  }
  return result;
}
