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

  Future<List<Map<String, Object?>>> exploreMenu(
    String id,
  ) => _serial(id, () async {
    final original = await _source(id);
    if (original['enabled'] == false) {
      throw const EngineException('source_disabled', 'Source is disabled');
    }
    final source = definition(original);
    final staticMenu = source.metadata['legacyExploreItems'];
    if (staticMenu is List) {
      return staticMenu
          .map((item) => Map<String, Object?>.from(item as Map))
          .toList();
    }
    final raw = original['exploreUrl'];
    if (raw == null || raw == '') return [];
    final code = raw is String ? PortableLegacyPageFetcher.script(raw) : null;
    final capabilities = PortableLegacyScriptCapabilities(original);
    if (code == null || !capabilities.supports(code)) {
      throw const EngineException(
        'legacy_requires_migration',
        'Dynamic explore menu requires unavailable script capabilities',
      );
    }
    final session = await _sessions.putIfAbsent(id, () => _create(original));
    try {
      Object? result = await session.engine.evaluateAuxiliary(
        session.source,
        code,
      );
      if (result is String) result = jsonDecode(result);
      if (result is! List || result.length > 1000) {
        throw const EngineException(
          'invalid_explore_menu',
          'Explore menu must return a bounded JSON array',
        );
      }
      final items = <Map<String, Object?>>[];
      for (final row in result) {
        if (row is! Map || row['title'] is! String || row['url'] is! String) {
          throw const EngineException(
            'invalid_explore_menu',
            'Explore items require string title and URL',
          );
        }
        // Headers remain in the original source; the picker receives actionable
        // entries, avoiding duplicate empty identities for group headings.
        if ((row['url'] as String).isNotEmpty) {
          items.add(Map<String, Object?>.from(row));
        }
      }
      return items;
    } finally {
      await _saveSession(id, session);
    }
  });

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
      for (final key in ['bookUrl', 'tocUrl', 'chapterUrl']) {
        final value = input[key];
        if (value is String && RegExp(r',\s*(?=\{)').hasMatch(value)) {
          throw const EngineException(
            'legacy_request_requires_migration',
            'URL request options require an implemented portable request compiler',
          );
        }
      }
      final issues = LegacySourceImporter()
          .import(original)
          .issues
          .where(
            (issue) =>
                issue.code != 'legacy.base_url_requires_review' &&
                !_portableRuleIssue(original, issue) &&
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
      await _saveSession(id, session);
    }
  });

  Future<void> _saveSession(String id, _SourceSession session) =>
      repository.setSetting(
        _sessionKey(id),
        jsonEncode({
          'engine': session.engine.exportSession(id),
          'legacyVariables': session.variables,
          'sourceState': session.sourceState,
        }),
      );

  static bool _needsHosted(Map<String, Object?> original) =>
      RegExp(r'\borg\.jsoup\b').hasMatch(jsonEncode(original)) ||
      PortableLegacyPageFetcher.script(
            original['searchUrl']?.toString() ?? '',
          ) !=
          null ||
      PortableLegacyPageFetcher.script(
            original['exploreUrl']?.toString() ?? '',
          ) !=
          null;

  static bool _portableRuleIssue(
    Map<String, Object?> original,
    LegacyIssue issue,
  ) {
    final hosted = _needsHosted(original);
    final capabilities = PortableLegacyScriptCapabilities(original);
    if (hosted &&
        issue.code == 'legacy.request_options' &&
        issue.path == 'searchUrl') {
      final code = PortableLegacyPageFetcher.script(
        original['searchUrl']?.toString() ?? '',
      );
      return code != null && capabilities.supports(code);
    }
    if (hosted &&
        issue.code == 'legacy.explore_menu_requires_review' &&
        issue.path == 'exploreUrl') {
      final code = PortableLegacyPageFetcher.script(
        original['exploreUrl']?.toString() ?? '',
      );
      return code != null && capabilities.supports(code);
    }
    if (issue.code != 'legacy.rule_requires_review' &&
        !(hosted &&
            issue.code == 'legacy.pipeline_requires_review' &&
            issue.path == 'ruleBookInfo.init')) {
      return false;
    }
    final path = issue.path.split('.');
    if (path.length != 2) return false;
    final rule = legacyRuleObject(original, path.first)?[path.last];
    if (rule is! String) return false;
    return hosted
        ? HostedLegacyRuleEvaluator(
            _ValidationRuntime(),
            capabilities,
          ).supportsRule(rule)
        : PortableLegacyRuleEvaluator.canEvaluate(rule);
  }

  Future<_SourceSession> _create(Map<String, Object?> original) async {
    final source = definition(original);
    final variables = <String, String>{};
    final sourceState = <String, Object?>{
      if (original['variable'] is String) 'variable': original['variable'],
    };
    final hosted = _needsHosted(original);
    final capabilities = PortableLegacyScriptCapabilities(original);
    final runtime = V8Runtime(
      prelude: source.metadata['legacy'] == true
          ? '$legacyScriptPrelude\n${hosted ? portableLegacySourcePrelude(original) : ''}'
          : '',
      persistent: true,
    );
    final engine =
        _factory?.call(source) ??
        SourceEngine(
          runtime: runtime,
          requestAdapter: adaptLegacyRequest,
          legacyRuleEvaluator: source.metadata['legacy'] == true
              ? hosted
                    ? HostedLegacyRuleEvaluator(runtime, capabilities)
                    : PortableLegacyRuleEvaluator(runtime)
              : null,
          legacyPageFetcher: hosted
              ? PortableLegacyPageFetcher(runtime, capabilities)
              : null,
          platform: SourceUtilityHost(
            hosted
                ? PortableLegacySourceStateHost(
                    const PortableLegacyDomHost(_UnavailableHost()),
                    sourceState,
                  )
                : const _UnavailableHost(),
          ),
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
        if (state['sourceState'] is Map) {
          sourceState.addAll(
            Map<String, Object?>.from(state['sourceState'] as Map),
          );
        }
        if (sourceState['variable'] != null &&
            sourceState['variable'] is! String) {
          throw const FormatException('Invalid source variable state');
        }
      }
      return _SourceSession(source, engine, variables, sourceState);
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
  const _SourceSession(
    this.source,
    this.engine,
    this.variables,
    this.sourceState,
  );
  final Map<String, Object?> sourceState;
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

class _ValidationRuntime implements ScriptRuntime {
  @override
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  }) async => throw StateError('Capability inspection cannot run scripts');
  @override
  Future<void> close() async {}
}
