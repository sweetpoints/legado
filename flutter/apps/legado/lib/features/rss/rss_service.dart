import 'dart:async';
import 'dart:convert';

import 'package:app_core/app_core.dart';
import 'package:dio/dio.dart';
import 'package:xml/xml.dart';

import 'rss_models.dart';
import 'rss_html_rules.dart';

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

  /// Existing app records win exact-identity conflicts. Reading never mutates
  /// either app settings or the retained original backup entry.
  Future<({List<RssSource> sources, int restoredCount, int conflictCount})>
  sourceView() async {
    final data = await _load();
    final native = (data['sources'] as List)
        .map((e) => RssSource.fromJson(Map<String, Object?>.from(e as Map)))
        .toList();
    final legacyState = _legacyState(data);
    final tombstones = Set<String>.from(
      legacyState['deletedSourceUrls'] as List,
    );
    final repository = this.repository;
    final text = repository is LegacyBackupFileReader
        ? await (repository as LegacyBackupFileReader).backupFile(
            'rssSources.json',
          )
        : (await repository.backupFiles())['rssSources.json'];
    if (text == null) {
      return (sources: native, restoredCount: 0, conflictCount: 0);
    }
    final Object? decoded;
    try {
      decoded = jsonDecode(text);
    } on FormatException {
      throw const FormatException('rssInvalidRestore');
    }
    if (decoded is! List) throw const FormatException('rssInvalidRestore');
    final seen = <String>{};
    final currentIds = native.map((e) => e.url).toSet();
    var conflicts = 0;
    var restored = 0;
    final merged = [...native];
    for (final value in decoded) {
      if (value is! Map<String, dynamic>) {
        throw const FormatException('rssInvalidRestore');
      }
      final source = RssSource.fromJson(value);
      if (!seen.add(source.url)) {
        throw const FormatException('rssDuplicateRestoreSource');
      }
      if (tombstones.contains(source.url)) continue;
      if (currentIds.contains(source.url)) {
        conflicts++;
        continue;
      }
      merged.add(source);
      restored++;
    }
    return (sources: merged, restoredCount: restored, conflictCount: conflicts);
  }

  Future<List<RssSource>> sources() async => (await sourceView()).sources;

  Map<String, Object?> _legacyState(Map<String, Object?> data) {
    final value = data['legacyRestore'];
    if (value == null) return {'version': 1, 'deletedSourceUrls': <String>[]};
    if (value is! Map ||
        value['version'] != 1 ||
        value['deletedSourceUrls'] is! List ||
        (value['deletedSourceUrls'] as List).any((e) => e is! String)) {
      throw const FormatException('rssCorruptStorage');
    }
    return Map<String, Object?>.from(value);
  }

  /// Deletion is an explicit user action; retain a tombstone so restored legacy
  /// sources cannot reappear on the next read or process restart.
  Future<void> removeSource(String identity) => _commit((data) async {
    data['sources'] = (data['sources'] as List)
        .where((e) => ((e as Map)['sourceUrl'] ?? e['url']) != identity)
        .toList();
    final state = _legacyState(data);
    state['deletedSourceUrls'] = {
      ...Set<String>.from(state['deletedSourceUrls'] as List),
      identity,
    }.toList();
    data['legacyRestore'] = state;
    // Offline articles and bookmarks stay available if the source is re-added.
  });

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
      final state = _legacyState(data);
      final importedIds = imported.map((e) => e.url).toSet();
      state['deletedSourceUrls'] = (state['deletedSourceUrls'] as List)
          .where((e) => !importedIds.contains(e))
          .toList();
      data['legacyRestore'] = state;
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

  Future<void> saveArticleContent(String source, RssArticle article) =>
      _commit((data) async {
        final all = Map<String, Object?>.from(data['articles'] as Map);
        all[source] = ((all[source] as List?) ?? []).map((e) {
          final old = RssArticle.fromJson(Map<String, Object?>.from(e as Map));
          return old.id != article.id
              ? old.toJson()
              : article
                    .copyWith(
                      read: old.read,
                      starred: old.starred,
                      original: {...old.original, ...article.original},
                    )
                    .toJson();
        }).toList();
        data['articles'] = all;
      });

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

  final htmlRules = RssHtmlRules();

  Future<List<RssArticle>> fetch(
    RssSource source, {
    CancelToken? cancelToken,
    String? categoryUrl,
  }) async {
    if (source.requiresRuleEngine) {
      if ((source.original['ruleArticles']?.toString() ?? '').isEmpty) {
        throw const FormatException('rssUnsupportedRule');
      }
      if (RssHtmlRules.hasScript(source.original['ruleArticles'].toString())) {
        throw const FormatException('rssScriptCapability');
      }
      var url = htmlRules.categoryUrl(
        source,
        categoryUrl ?? htmlRules.categories(source).first.url,
      );
      final visited = <String>{};
      final articles = <String, RssArticle>{};
      for (var page = 0; page < 20; page++) {
        if (!visited.add(url)) {
          throw const FormatException('rssPaginationCycle');
        }
        final response = await _document(source, url, cancelToken: cancelToken);
        for (final article in await htmlRules.articles(
          source,
          response.text,
          response.base,
        )) {
          articles[article.id] = article;
        }
        final next = await htmlRules.nextPage(
          source,
          response.text,
          response.base,
        );
        if (next.isEmpty) return articles.values.toList();
        url = next;
      }
      throw const FormatException('rssPageLimit');
    }
    final document = await _document(
      source,
      source.url,
      cancelToken: cancelToken,
    );
    return parseFeed(document.text, document.base);
  }

  Future<RssArticle> fetchArticle(
    RssSource source,
    RssArticle article, {
    CancelToken? cancelToken,
  }) async {
    if (article.original['rssDetailPending'] != true) return article;
    final document = await _document(
      source,
      article.link,
      cancelToken: cancelToken,
    );
    return htmlRules.content(source, article, document.text, document.base);
  }

  Future<({String text, Uri base})> _document(
    RssSource source,
    String url, {
    CancelToken? cancelToken,
  }) async {
    final headers = <String, Object?>{
      'Accept': 'application/rss+xml, application/atom+xml, application/xml, text/xml',
    };
    final sourceHeader = source.original['header'];
    if (sourceHeader is String && sourceHeader.trim().isNotEmpty) {
      if (sourceHeader.replaceAll('{{baseUrl}}', url).contains('{{') ||
          sourceHeader.contains('<js>') ||
          sourceHeader.startsWith('@js:')) {
        throw const FormatException('rssLegacyRules');
      }
      final decoded = jsonDecode(sourceHeader.replaceAll('{{baseUrl}}', url));
      if (decoded is! Map || decoded.values.any((value) => value is! String)) {
        throw const FormatException('rssInvalidSource');
      }
      for (final entry in decoded.entries) {
        headers[entry.key.toString()] = entry.value;
      }
    }
    final response = await dio.get<ResponseBody>(
      url,
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
    return (
      text: latin ? latin1.decode(bytes) : utf8.decode(bytes),
      base: response.realUri,
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
