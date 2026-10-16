import 'dart:async';
import 'dart:convert';

import 'package:app_core/app_core.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:source_v8/source_v8.dart';

import '../features/sources/models/network_book.dart';

export '../features/sources/models/network_book.dart';

typedef BookEngineFactory = SourceEngine Function(SourceDefinition source);

class BookPreview {
  const BookPreview(this.book, this.chapters);
  final NetworkBook book;
  final List<ReaderChapter> chapters;

  LegacyBookRecord toLegacyBookRecord({LegacyBookRecord? existing}) =>
      LegacyBookRecord({
        ...?existing?.toJson(),
        ...book.metadata,
        'bookUrl': book.url,
        'name': book.name,
        'origin': book.sourceId,
        if (existing != null) 'durChapterIndex': existing.chapterIndex,
        if (existing != null) 'durChapterPos': existing.characterOffset,
      });
}

/// Independent Dart/V8 composition. No dependency on the Android App bridge.
/// Legacy features requiring its original native host fail explicitly.
class BookSourceService {
  BookSourceService(this.repository, {BookEngineFactory? engineFactory})
    : _factory = engineFactory;

  final LibraryRepository repository;
  final BookEngineFactory? _factory;
  final _sessions = <String, Future<_SourceSession>>{};
  final _tails = <String, Future<void>>{};
  bool _closed = false;

  static List<Map<String, Object?>> parseImport(String text) {
    final value = jsonDecode(text);
    final items = value is List ? value : [value];
    final ids = <String>{};
    return items.map((item) {
      if (item is! Map<String, dynamic>) {
        throw const FormatException('书源必须为 JSON 对象，列表中不能包含 null');
      }
      final copy = Map<String, Object?>.from(item);
      final id = sourceId(copy);
      if (!ids.add(id)) throw FormatException('重复的书源标识：$id');
      definition(copy); // Validate structure without deleting unknown fields.
      return copy;
    }).toList();
  }

  static String sourceId(Map<String, Object?> source) {
    final value = source.containsKey('bookSourceUrl')
        ? source['bookSourceUrl']
        : source['id'];
    if (value is! String || value.isEmpty) {
      throw const FormatException('书源标识不能为空');
    }
    return value;
  }

  static SourceDefinition definition(Map<String, Object?> source) =>
      source.containsKey('bookSourceUrl')
      ? LegacySourceImporter().import(source).source
      : SourceDefinition.fromJson(source);

  Future<int> importJson(String text) async {
    final parsed = parseImport(text);
    for (final source in parsed) {
      await saveSource(source);
    }
    return parsed.length;
  }

  Future<void> saveSource(Map<String, Object?> source) async {
    definition(source);
    final id = sourceId(source);
    await _serial(id, () async {
      await repository.putSource(source);
      await _discard(id);
    });
  }

  Future<void> deleteSource(String id) => _serial(id, () async {
    await repository.removeSource(id);
    await repository.setSetting(_sessionKey(id), null);
    await _discard(id);
  });

  Future<void> setEnabled(Map<String, Object?> source, bool enabled) =>
      saveSource({...source, 'enabled': enabled});

  Future<String> exportJson() async =>
      const JsonEncoder.withIndent('  ').convert(await repository.sources());

  Future<List<NetworkBook>> search(
    String sourceId,
    String key, {
    int page = 1,
  }) async {
    if (key.trim().isEmpty) return [];
    final records = await _execute(sourceId, 'search', {
      'key': key,
      'page': page,
    });
    return records
        .map((record) => NetworkBook(sourceId: sourceId, metadata: record))
        .toList();
  }

  Future<List<Map<String, Object?>>> exploreMenu(String id) async {
    final source = await _source(id);
    final imported = definition(source);
    final menu = imported.metadata['legacyExploreItems'];
    if (menu is List) {
      return menu
          .map((item) => Map<String, Object?>.from(item as Map))
          .toList();
    }
    if (source['exploreUrl'] is String &&
        (source['exploreUrl'] as String).isNotEmpty) {
      throw const EngineException(
        'legacy_requires_migration',
        '动态发现菜单尚需跨平台执行适配',
      );
    }
    return [];
  }

  Future<List<NetworkBook>> explore(
    String id,
    String url, {
    int page = 1,
  }) async => (await _execute(id, 'explore', {
    'exploreUrl': url,
    'page': page,
  })).map((record) => NetworkBook(sourceId: id, metadata: record)).toList();

  Future<BookPreview> loadDetail(NetworkBook selected) async {
    if (selected.url.isEmpty) {
      throw const EngineException('invalid_book', '书籍缺少 bookUrl');
    }
    final info = await _execute(selected.sourceId, 'info', {
      ...selected.metadata,
      'bookUrl': selected.url,
      'book': selected.metadata,
    });
    final book = selected.copyWith(
      metadata: {
        ...selected.metadata,
        if (info.isNotEmpty) ...info.first,
        'bookUrl': selected.url,
      },
    );
    final rows = await _execute(book.sourceId, 'toc', {
      ...book.metadata,
      'book': book.metadata,
      'tocUrl': book.metadata['tocUrl'] ?? book.url,
    });
    final chapters = <ReaderChapter>[];
    for (final row in rows) {
      final url = row['url']?.toString() ?? row['chapterUrl']?.toString() ?? '';
      if (url.isEmpty) {
        throw const EngineException('invalid_chapter', '目录项缺少章节地址');
      }
      chapters.add(
        ReaderChapter(
          url: url,
          title:
              row['title']?.toString() ?? row['chapterName']?.toString() ?? '',
          index: chapters.length,
          isVolume: row['isVolume'] == true,
        ),
      );
    }
    // Preview stays outside Room/the bookshelf until the explicit Add action.
    return BookPreview(book, List.unmodifiable(chapters));
  }

  Future<LegacyBookRecord> addToShelf(BookPreview preview) async {
    final existing = await repository.book(preview.book.url);
    final record = preview.toLegacyBookRecord(existing: existing);
    // Store the catalogue first; a failed write never advertises a ready shelf row.
    await repository.putChapters(record.url, preview.chapters);
    await repository.putBook(record);
    return record;
  }

  Future<String> fetchChapter(BookPreview preview, int index) async {
    final chapter = preview.chapters[index];
    final cached = await repository.content(preview.book.url, chapter.url);
    if (cached != null) return cached;
    final rows = await _execute(preview.book.sourceId, 'content', {
      ...preview.book.metadata,
      'book': preview.book.metadata,
      'chapterUrl': chapter.url,
      'chapterTitle': chapter.title,
      'chapter': chapter.toJson(),
    });
    final content = rows
        .map((row) => row['content']?.toString() ?? '')
        .join('\n');
    if (content.isEmpty) throw const EngineException('empty_content', '正文为空');
    await repository.putContent(preview.book.url, chapter.url, content);
    return content;
  }

  Future<Map<String, Object?>> _source(String id) async {
    for (final source in await repository.sources()) {
      if (sourceId(source) == id) return source;
    }
    throw const EngineException('source_not_found', '书源不存在');
  }

  Future<List<Map<String, Object?>>> _execute(
    String id,
    String operation,
    Map<String, Object?> input,
  ) => _serial(id, () async {
    final original = await _source(id);
    if (original['enabled'] == false) {
      throw const EngineException('source_disabled', '书源已停用');
    }
    if (original.containsKey('bookSourceUrl')) {
      final issues = LegacySourceImporter()
          .import(original)
          .issues
          .where(
            (issue) =>
                issue.code != 'legacy.base_url_requires_review' &&
                legacyIssueAffectsOperation(issue, operation),
          )
          .toList();
      if (issues.isNotEmpty) {
        throw EngineException(
          'legacy_requires_migration',
          issues.map((issue) => '${issue.path}: ${issue.message}').join('\n'),
        );
      }
    }
    final session = await _sessions.putIfAbsent(id, () => _create(original));
    try {
      return await session.engine.execute(
        session.source,
        operation,
        input: input,
      );
    } finally {
      await repository.setSetting(
        _sessionKey(id),
        jsonEncode({
          'engine': session.engine.exportSession(id),
          'legacyVariables': session.variables,
        }),
      );
    }
  });

  Future<_SourceSession> _create(Map<String, Object?> original) async {
    final source = definition(original);
    final variables = <String, String>{};
    final engine =
        _factory?.call(source) ??
        SourceEngine(
          runtime: V8Runtime(
            prelude: source.metadata['legacy'] == true
                ? legacyScriptPrelude
                : '',
            persistent: true,
          ),
          requestAdapter: adaptLegacyRequest,
          platform: SourceUtilityHost(const _UnavailableHost()),
          hostAdapter: source.metadata['legacy'] == true
              ? (host) => LegacyScriptHost(host, variables: variables)
              : null,
        );
    try {
      final saved = await repository.setting(_sessionKey(source.id));
      if (saved != null) {
        final state = jsonDecode(saved) as Map;
        engine.importSession(
          source.id,
          Map<String, Object?>.from(state['engine'] as Map),
        );
        variables.addAll(
          Map<String, String>.from(state['legacyVariables'] as Map),
        );
      }
      return _SourceSession(source, engine, variables);
    } catch (error) {
      await engine.close();
      throw EngineException('session_restore_failed', '书源会话无法恢复：$error');
    }
  }

  Future<T> _serial<T>(String id, Future<T> Function() action) {
    if (_closed) return Future.error(StateError('BookSourceService is closed'));
    final future = (_tails[id] ?? Future<void>.value()).then((_) => action());
    _tails[id] = future.then<void>(
      (_) {},
      onError: (Object _, StackTrace _) {},
    );
    return future;
  }

  static String _sessionKey(String id) =>
      'book-source-session:${Uri.encodeComponent(id)}';
  Future<void> _discard(String id) async {
    final pending = _sessions.remove(id);
    if (pending != null) await (await pending).engine.close();
  }

  Future<void> close() async {
    _closed = true;
    await Future.wait(_tails.values);
    for (final id in _sessions.keys.toList()) {
      await _discard(id);
    }
  }
}

class _SourceSession {
  const _SourceSession(this.source, this.engine, this.variables);
  final SourceDefinition source;
  final SourceEngine engine;
  final Map<String, String> variables;
}

class _UnavailableHost implements ScriptHost {
  const _UnavailableHost();
  @override
  Future<Object?> call(String method, List<Object?> arguments) async =>
      throw EngineException('unsupported_host_api', '当前平台尚未实现 $method');
}
