import 'legacy_variable_scope.dart';

import 'dart:convert';
import 'dart:io' show Cookie;

import 'package:html/dom.dart' show Element;

import 'contracts.dart';
import 'network.dart';
import 'rules.dart';
import 'form_encoding.dart';
import 'page_templates.dart';
import 'html4.dart';
import 'legacy_rule_host.dart';
import 'legacy_page_fetcher.dart';

typedef SourceRequestAdapter = SourceStage Function(
  SourceDefinition source,
  SourceStage stage,
  Map<String, Object?> input,
);

class SourceEngine {
  SourceEngine({
    required this.runtime,
    NetworkClient? network,
    this.platform,
    this.hostAdapter,
    this.requestAdapter,
    this.legacyRuleEvaluator,
    this.legacyPageFetcher,
  }) : _providedNetwork = network;
  final ScriptRuntime runtime;
  final ScriptHost? platform;
  final ScriptHost Function(ScriptHost)? hostAdapter;
  final SourceRequestAdapter? requestAdapter;
  final LegacyRuleEvaluator? legacyRuleEvaluator;
  final LegacyPageFetcher? legacyPageFetcher;
  final NetworkClient? _providedNetwork;
  final Map<String, NetworkClient> _sessions = {};
  final Map<String, Map<String, Object?>> _variables = {};
  final Map<String, List<Map<String, Object?>>> _pendingCookies = {};
  Future<Object?> evaluateAuxiliary(
    SourceDefinition source,
    String script, {
    Map<String, Object?> bindings = const {},
    String prelude = '',
    Duration timeout = const Duration(seconds: 10),
    CancellationToken? cancellation,
  }) async {
    cancellation?.throwIfCancelled();
    final auxiliary = runtime;
    if (auxiliary is! AuxiliaryScriptRuntime) {
      throw const EngineException(
        'unsupported_runtime',
        'Runtime has no auxiliary execution',
      );
    }
    final network =
        _providedNetwork ??
        _sessions.putIfAbsent(source.id, () => NetworkClient());
    final pending = _pendingCookies.remove(source.id);
    if (pending != null) network.restoreCookies(pending);
    final variables = _variables.putIfAbsent(source.id, () => {});
    final baseHost = _EngineHost(
      network,
      source.baseUrl,
      variables,
      cancellation,
      platform,
      runtime,
      source.headers,
    );
    return auxiliary.evaluateAuxiliary(
      script,
      ScriptContext(
        variables: {
          ...bindings,
          'sourceId': source.id,
          'baseUrl': bindings['baseUrl'] ?? source.baseUrl.toString(),
        },
        host: hostAdapter?.call(baseHost) ?? baseHost,
        timeout: timeout,
      ),
      prelude: prelude,
      cancellation: cancellation,
    );
  }

  Future<ScriptDiagnostic?> checkAuxiliarySyntax(
    String script, {
    CancellationToken? cancellation,
  }) {
    final auxiliary = runtime;
    if (auxiliary is! AuxiliaryScriptRuntime) {
      throw const EngineException(
        'unsupported_runtime',
        'Runtime has no compile-only parser',
      );
    }
    return auxiliary.checkSyntax(script, cancellation: cancellation);
  }

  Future<List<Map<String, Object?>>> execute(
    SourceDefinition source,
    String operation, {
    Map<String, Object?> input = const {},
    CancellationToken? cancellation,
  }) async {
    cancellation?.throwIfCancelled();
    if (!['search', 'explore', 'info', 'toc', 'content'].contains(operation)) {
      throw EngineException('unsupported_operation', operation);
    }
    final network =
        _providedNetwork ??
        _sessions.putIfAbsent(
          source.id,
          () => NetworkClient(
            maxConcurrentRequests:
                source.metadata['maxConcurrentRequests'] as int? ?? 4,
            minRequestInterval: Duration(
              milliseconds: source.metadata['requestIntervalMs'] as int? ?? 0,
            ),
          ),
        );
    final pendingCookies = _pendingCookies.remove(source.id);
    if (pendingCookies != null) network.restoreCookies(pendingCookies);
    final vars = _variables.putIfAbsent(source.id, () => {});
    final baseHost = _EngineHost(
      network,
      source.baseUrl,
      vars,
      cancellation,
      platform,
      runtime,
      source.headers,
    );
    final host = hostAdapter?.call(baseHost) ?? baseHost;
    final context = ScriptContext(
      variables: {
        ...input,
        'sourceId': source.id,
        'baseUrl': source.baseUrl.toString(),
      },
      host: host,
    );
    if (source.script != null) {
      final function =
          {
            'info': 'getBookInfo',
            'toc': 'getChapters',
            'content': 'getContent',
          }[operation] ??
          operation;
      final result = await runtime.evaluate(
        '(async()=>{ ${source.script}\n if(typeof $function !== "function") throw new Error("Missing function $function"); return await $function(${jsonEncode(input)}); })()',
        context,
        cancellation: cancellation,
      );
      if (operation == 'content' && result is String) {
        return [
          {'content': result},
        ];
      }
      return _records(result);
    }
    var selectedStage = source.stages[operation];
    if (selectedStage == null) {
      throw EngineException('missing_stage', 'Source has no $operation stage');
    }
    final nativeFetcher = source.metadata['legacyOriginal'] is Map
        ? legacyPageFetcher
        : null;
    if (nativeFetcher == null && selectedStage.legacyRequestInput != null) {
      final adapter = requestAdapter;
      if (adapter == null) {
        throw const EngineException(
          'legacy_request_adapter_required',
          'Legacy request input requires a compatibility request adapter',
        );
      }
      selectedStage = adapter(source, selectedStage, input);
      if (selectedStage.legacyRequestInput != null) {
        throw const EngineException(
          'legacy_request_adapter_required',
          'Compatibility request adapter must resolve legacyRequestInput',
        );
      }
    }
    final stage = selectedStage;
    var url = source.baseUrl.toString();
    String? body;
    if (nativeFetcher == null) {
      final urlTemplate = stage.legacyPageTemplates
          ? expandLegacyPageTemplate(stage.url, input, urlChoices: true)
          : stage.url;
      url = urlTemplate.replaceAllMapped(
        RegExp(r'\{\{([A-Za-z][A-Za-z0-9_]*)\}\}'),
        (m) {
          final value = input[m[1]];
          if (value == null) {
            throw EngineException('missing_input', 'Missing ${m[1]}');
          }
          if (stage.legacyPageTemplates &&
              RegExp(r'[<>]').hasMatch(value.toString())) {
            throw const EngineException(
              'legacy_page_requires_migration',
              'Legacy URL input must not introduce page choice syntax',
            );
          }
          // URL-valued inputs are full URLs; other values are encoded components.
          return m[1]!.endsWith('Url')
              ? value.toString()
              : Uri.encodeComponent(value.toString());
        },
      );
      if (source.metadata['legacyBaseUrlUnavailable'] == true) {
        final explicit = Uri.tryParse(url);
        // Uri.isAbsolute excludes URLs containing fragments; an HTTP request
        // target is usable when it has an explicit HTTP(S) scheme and host.
        if (explicit == null ||
            !['http', 'https'].contains(explicit.scheme) ||
            explicit.host.isEmpty) {
          throw const EngineException(
            'legacy_base_url_required',
            'Legacy source ID has no HTTP base; the stage must provide an absolute HTTP(S) URL',
          );
        }
      }
      final bodyTemplate = stage.body != null && stage.legacyPageTemplates
          ? expandLegacyPageTemplate(stage.body!, input)
          : stage.body;
      final substitutedBody = bodyTemplate?.replaceAllMapped(
        RegExp(r'\{\{([A-Za-z][A-Za-z0-9_]*)\}\}'),
        (m) {
          final value = input[m[1]];
          if (value == null) {
            throw EngineException('missing_input', 'Missing ${m[1]}');
          }
          if (stage.bodyEncoding == 'legacyFormUtf8' ||
              stage.bodyTemplateMode == 'legacyJsonString') {
            if (value is! String && value is! bool && value is! int ||
                value is int &&
                    (value < -9007199254740991 || value > 9007199254740991)) {
              throw const EngineException(
                'legacy_body_template_requires_migration',
                'Legacy body placeholders require strings, booleans or JS-safe integers',
              );
            }
            if (RegExp(r'["\\<>\x00-\x1f\x7f]').hasMatch(value.toString())) {
              throw const EngineException(
                'legacy_body_template_requires_migration',
                'Legacy body placeholder would change the old JSON request options',
              );
            }
          }
          return value.toString();
        },
      );
      body = substitutedBody != null && stage.bodyEncoding == 'legacyFormUtf8'
          ? encodeLegacyFormUtf8Body(substitutedBody)
          : substitutedBody;
    }
    if (stage.maxPages < 1 || stage.maxPages > 1000) {
      throw const EngineException('invalid_source', 'maxPages must be 1..1000');
    }
    final original = source.metadata['legacyOriginal'];
    String? legacyHook(String group, String name) {
      if (original is! Map) return null;
      Object? container = original[group];
      if (container is String) {
        try {
          container = jsonDecode(container);
        } on FormatException {
          return null;
        }
      }
      if (container is! Map) return null;
      final value = container[name];
      if (value == null) return null;
      if (value is! String) {
        throw EngineException(
          'invalid_legacy_hook',
          '$group.$name must be a string',
        );
      }
      return value;
    }

    final infoInit = operation == 'info'
        ? legacyHook('ruleBookInfo', 'init')
        : null;
    final contentReplace = operation == 'content'
        ? legacyHook('ruleContent', 'replaceRegex')
        : null;
    final legacyHost = original is Map ? legacyRuleEvaluator : null;
    final scopedLegacy = legacyHost != null;
    final bookSnapshot = input['book'] is Map
        ? Map<String, Object?>.from(input['book'] as Map)
        : <String, Object?>{
            'bookUrl': input['bookUrl'],
            'name': input['name'] ?? '',
          };
    final chapterSnapshot = input['chapter'] is Map
        ? Map<String, Object?>.from(input['chapter'] as Map)
        : <String, Object?>{
            'url': input['chapterUrl'],
            'title': input['chapterTitle'] ?? '',
          };
    final bookVariables = scopedLegacy
        ? legacyEntityVariables(bookSnapshot['variable'] ?? input['variable'])
        : <String, String>{};
    final chapterVariables = scopedLegacy
        ? legacyEntityVariables(chapterSnapshot['variable'])
        : <String, String>{};
    final sourceVariables = <String, String>{};
    var rowSequence = 0;
    final seededScopes = <String>{};
    final bookWasSeeded = bookVariables.isNotEmpty;
    Map<String, Object?> scope(
      String id,
      String target,
      Map<String, String> book,
      Map<String, String> chapter,
    ) {
      final identity = '${input['taskId'] ?? source.id}:$operation:$id';
      if ((target == 'chapter' ? chapter : book).isNotEmpty) {
        seededScopes.add(identity);
      }
      return {
        'id': identity,
        'target': target,
        'source': sourceVariables,
        'book': book,
        'chapter': chapter,
      };
    }

    final requestScope = scope(
      'request',
      'book',
      bookVariables,
      <String, String>{},
    );
    final entityScope = scope(
      'entity',
      operation == 'content' ? 'chapter' : 'book',
      bookVariables,
      operation == 'content' ? chapterVariables : <String, String>{},
    );
    ScriptContext scopedContext(
      ScriptContext base,
      Map<String, Object?> varsScope,
      Map<String, Object?> book,
      Map<String, Object?>? chapter,
    ) => ScriptContext(
      variables: {
        ...base.variables,
        'legacyVariableScope': varsScope,
        'legacyVariables': legacyScopeReads(varsScope),
        'book': {...book, 'variable': jsonEncode(varsScope['book'])},
        if (chapter != null)
          'chapter': {...chapter, 'variable': jsonEncode(varsScope['chapter'])},
      },
      host: base.host,
      timeout: base.timeout,
    );
    void attachEntityVariables(
      Map<String, Object?> record,
      Map<String, Object?> varsScope,
    ) {
      final own = varsScope[varsScope['target']] as Map<String, String>;
      if (own.isNotEmpty || seededScopes.contains(varsScope['id'])) {
        record['variable'] = Map<String, String>.from(own);
      }
      if ((operation == 'toc' || operation == 'content') &&
          (bookWasSeeded || bookVariables.isNotEmpty)) {
        record['bookVariable'] = Map<String, String>.from(bookVariables);
      }
    }

    for (final hook in [
      if (infoInit != null && _trimLegacyLine(infoInit).isNotEmpty) infoInit,
      if (contentReplace != null && contentReplace.isNotEmpty) contentReplace,
    ]) {
      if (legacyHost == null || !legacyHost.supportsRule(hook)) {
        throw const EngineException(
          'legacy_pipeline_host_required',
          'Legacy initialization and replacement require the original rule host',
        );
      }
    }
    final rules = RuleEvaluator(runtime);
    final results = <Map<String, Object?>>[];
    final visited = <String>{};
    var current = source.baseUrl.resolve(url);
    String? nativeNext;
    ScriptContext? firstPageContext;
    Object? firstPageBody;
    for (var page = 0; page < stage.maxPages; page++) {
      final requestKey = nativeFetcher == null
          ? current.toString()
          : nativeNext ?? 'initial';
      if (!visited.add(requestKey)) {
        // Original BookChapterList/BookContent stop on a repeated next URL.
        // Modern sources retain their explicit cycle-error contract.
        if (nativeFetcher != null) break;
        throw const EngineException(
          'pagination_cycle',
          'Next page repeats an already fetched URL',
        );
      }
      final response = nativeFetcher != null
          ? await nativeFetcher.fetch(
              source,
              operation,
              input,
              ScriptContext(
                variables: {
                  ...context.variables,
                  'baseUrl': current.toString(),
                  'legacyVariables': vars,
                  if (scopedLegacy) 'legacyVariableScope': requestScope,
                },
                host: context.host,
                timeout: context.timeout,
              ),
              nextUrl: nativeNext,
              cancellation: cancellation,
            )
          : await network.request(
              current,
              headers: stage.headers ?? source.headers,
              method: stage.method,
              body: body,
              charset: stage.charset,
              cancellation: cancellation,
            );
      if (nativeFetcher != null && page == 0) {
        visited.add(response.url.toString());
      }
      if (response.status >= 400) {
        throw EngineException('http_error', 'HTTP ${response.status}');
      }

      final pageHost = _EngineHost(
        network,
        response.url,
        vars,
        cancellation,
        platform,
        runtime,
        source.headers,
      );
      var pageContext = ScriptContext(
        variables: {
          ...context.variables,
          'baseUrl': response.url.toString(),
          if (source.metadata['legacyOriginal'] is Map) 'legacyVariables': vars,
        },
        host: hostAdapter?.call(pageHost) ?? pageHost,
        timeout: context.timeout,
      );
      if (scopedLegacy) {
        pageContext = scopedContext(
          pageContext,
          entityScope,
          bookSnapshot,
          operation == 'content' ? chapterSnapshot : null,
        );
      }
      firstPageContext ??= pageContext;
      firstPageBody ??= response.body;
      var ruleContext = pageContext;
      Future<List<Object?>> evaluateRule(
        String rule,
        Object? value, {
        bool elements = false,
        bool element = false,
        bool formatContent = false,
        bool scalar = false,
        bool isUrl = false,
        bool unescape = true,
      }) => legacyHost == null || !legacyHost.supportsRule(rule)
          ? rules.evaluate(
              rule,
              value,
              ruleContext,
              cancellation: cancellation,
              elements: elements,
            )
          : legacyHost.evaluate(
              rule,
              value,
              ruleContext,
              source: source,
              operation: operation,
              elements: elements,
              element: element,
              formatContent: formatContent,
              scalar: scalar,
              isUrl: isUrl,
              unescape: unescape,
              cancellation: cancellation,
            );
      Object? pageInput = response.body;
      if (infoInit != null && _trimLegacyLine(infoInit).isNotEmpty) {
        final initialized = await evaluateRule(
          infoInit,
          pageInput,
          element: true,
        );
        if (initialized.isEmpty || initialized.single == null) {
          throw const EngineException(
            'legacy_init_empty',
            'Legacy detail initialization returned null content',
          );
        }
        pageInput = initialized.single;
      }
      final rows = stage.list == null
          ? [pageInput]
          : await evaluateRule(stage.list!, pageInput, elements: true);

      for (final row in rows) {
        cancellation?.throwIfCancelled();
        Map<String, Object?>? rowScope;
        var rowBook = bookSnapshot;
        Map<String, Object?>? rowChapter = operation == 'content'
            ? chapterSnapshot
            : null;
        if (scopedLegacy) {
          if (operation == 'search' || operation == 'explore') {
            rowScope = scope(
              'row:${rowSequence++}',
              'book',
              Map<String, String>.from(bookVariables),
              <String, String>{},
            );
            rowBook = {'name': '', 'author': '', 'bookUrl': ''};
          } else if (operation == 'toc') {
            rowScope = scope(
              'row:${rowSequence++}',
              'chapter',
              bookVariables,
              <String, String>{},
            );
            rowChapter = {
              'title': '',
              'url': '',
              'bookUrl': bookSnapshot['bookUrl'],
            };
          } else {
            rowScope = entityScope;
          }
          ruleContext = scopedContext(
            pageContext,
            rowScope,
            rowBook,
            rowChapter,
          );
        }
        final fields = <String, Object?>{};
        for (final entry in stage.fields.entries) {
          if (original is Map &&
              ((operation == 'info' && entry.key == 'init') ||
                  (operation == 'content' &&
                      (entry.key == 'replaceRegex' || entry.key == 'title')))) {
            continue;
          }
          final isLink = {
            'bookUrl',
            'tocUrl',
            'url',
            'chapterUrl',
          }.contains(entry.key);
          final listField = {'kind', 'downloadUrls'}.contains(entry.key);
          final values = await evaluateRule(
            entry.value,
            row,
            scalar: !listField,
            formatContent:
                operation == 'content' &&
                entry.key == 'content' &&
                legacyHost != null,
            isUrl: isLink,
            unescape: operation != 'content' && !listField,
          );
          final legacyScalar =
              (source.metadata['legacy'] == true ||
                  source.metadata['legacyOriginal'] is Map) &&
              entry.value.trimLeft().toLowerCase().startsWith('@legacy:');
          // AnalyzeRule.getString(isUrl=true) uses JSoup.getString0.
          // Joining selected links would create a different URI.
          final firstLegacyLink =
              row is! Map &&
              legacyScalar &&
              {'bookUrl', 'tocUrl', 'url', 'chapterUrl'}.contains(entry.key);
          var value = (firstLegacyLink ? values.take(1) : values)
              .map(RuleEvaluator.text)
              .join('\n');
          // Old getString unescapes once after joining/replacement. Content
          // formatting and kind/downloadUrls/nextPage use different old paths.
          // Keep this provenance-bound; modern rules and string lists are raw.
          if ((legacyHost == null || !legacyHost.supportsRule(entry.value)) &&
              (source.metadata['legacy'] == true ||
                  source.metadata['legacyOriginal'] is Map) &&
              operation != 'content' &&
              entry.key != 'kind' &&
              entry.key != 'downloadUrls' &&
              entry.value.trimLeft().toLowerCase().startsWith('@legacy:')) {
            value = unescapeHtml4(value);
          }
          if ((entry.key.endsWith('Url') || entry.key == 'url') &&
              value.isNotEmpty) {
            value = response.url.resolve(value).toString();
          }
          if (legacyHost?.supportsRule(entry.value) == true && listField) {
            fields[entry.key] = entry.key == 'downloadUrls'
                ? values
                : values.map(RuleEvaluator.text).join(',');
          } else {
            fields[entry.key] = value;
          }
          if (scopedLegacy) {
            if (operation == 'toc' || operation == 'content') {
              rowChapter?[entry.key] = fields[entry.key];
            } else {
              rowBook[entry.key] = fields[entry.key];
            }
            ruleContext = scopedContext(
              pageContext,
              rowScope!,
              rowBook,
              rowChapter,
            );
          }
        }
        if (rowScope != null) attachEntityVariables(fields, rowScope);
        results.add(fields);
      }
      if (stage.nextPage == null) break;
      ruleContext = pageContext;
      final links = await evaluateRule(
        stage.nextPage!,
        pageInput,
        isUrl: true,
        unescape: false,
      );
      final next = links
          .map(RuleEvaluator.text)
          .where((x) => x.isNotEmpty)
          .firstOrNull;
      if (next == null) break;
      if (nativeFetcher != null) {
        nativeNext = next;
        current = response.url;
      } else {
        current = response.url.resolve(next);
      }
      if (page == stage.maxPages - 1) {
        throw const EngineException(
          'pagination_limit',
          'Next page exceeds maxPages',
        );
      }
    }
    if (operation == 'content' && results.isNotEmpty) {
      final merged = Map<String, Object?>.from(results.first);
      var content = results
          .map((r) => r['content']?.toString() ?? '')
          .join('\n');
      if (contentReplace != null && contentReplace.isNotEmpty) {
        content = content.split('\n').map(_trimLegacyLine).join('\n');
        final replaced = await legacyHost!.evaluate(
          contentReplace,
          content,
          firstPageContext!,
          source: source,
          operation: operation,
          scalar: true,
          cancellation: cancellation,
        );
        content = replaced.map(RuleEvaluator.text).join('\n');
        if (input['__legacyOnLineTxt'] == true) {
          content = content.split('\n').map((line) => '　　$line').join('\n');
        }
      }
      merged['content'] = content;
      // Original BookContent evaluates its title only after whole-text replacement,
      // against the first page parser. An optional title failure does not erase content.
      final titleRule = original is Map ? stage.fields['title'] : null;
      if (titleRule != null && _trimLegacyLine(titleRule).isNotEmpty) {
        try {
          final titles =
              legacyHost != null && legacyHost.supportsRule(titleRule)
              ? await legacyHost.evaluate(
                  titleRule,
                  firstPageBody,
                  firstPageContext!,
                  source: source,
                  operation: operation,
                  scalar: true,
                  cancellation: cancellation,
                )
              : await rules.evaluate(
                  titleRule,
                  firstPageBody,
                  firstPageContext!,
                  cancellation: cancellation,
                );
          merged['title'] = titles.map(RuleEvaluator.text).join('\n');
        } catch (_) {
          cancellation?.throwIfCancelled();
        }
      }
      if (scopedLegacy) attachEntityVariables(merged, entityScope);
      return [merged];
    }

    if (scopedLegacy && (operation == 'info' || operation == 'toc')) {
      for (final record in results) {
        if (operation == 'info' &&
            (bookWasSeeded || bookVariables.isNotEmpty)) {
          record['variable'] = Map<String, String>.from(bookVariables);
        } else if (operation == 'toc' &&
            (bookWasSeeded || bookVariables.isNotEmpty)) {
          record['bookVariable'] = Map<String, String>.from(bookVariables);
        }
      }
    }
    return results;
  }

  static String _trimLegacyLine(String value) {
    bool whitespace(int c) =>
        (c >= 0x09 && c <= 0x0d) ||
        (c >= 0x1c && c <= 0x20) ||
        c == 0xa0 ||
        c == 0x1680 ||
        (c >= 0x2000 && c <= 0x200a) ||
        c == 0x2028 ||
        c == 0x2029 ||
        c == 0x202f ||
        c == 0x205f ||
        c == 0x3000;
    var start = 0, end = value.length;
    while (start < end && whitespace(value.codeUnitAt(start))) {
      start++;
    }
    while (end > start && whitespace(value.codeUnitAt(end - 1))) {
      end--;
    }
    return value.substring(start, end);
  }

  List<Map<String, Object?>> _records(Object? result) {
    if (result == null) return [];
    final rows = result is List ? result : [result];
    return rows.map((e) {
      if (e is! Map) {
        throw const EngineException(
          'invalid_result',
          'Script stages must return an object or array of objects',
        );
      }
      return Map<String, Object?>.from(e);
    }).toList();
  }

  Map<String, Object?> exportSession(String sourceId) => {
    'variables': Map<String, Object?>.from(_variables[sourceId] ?? {}),
    'cookies':
        (_providedNetwork ?? _sessions[sourceId])?.exportCookies() ??
        _pendingCookies[sourceId] ??
        [],
  };
  void importSession(String sourceId, Map<String, Object?> session) {
    final variables = Map<String, Object?>.from(
      session['variables'] as Map? ?? {},
    );
    // Ensure storage boundaries remain JSON serializable.
    jsonEncode(variables);
    final records = (session['cookies'] as List? ?? [])
        .map((e) => Map<String, Object?>.from(e as Map))
        .toList();
    final network = _providedNetwork ?? _sessions[sourceId];
    if (network == null) {
      final validator = NetworkClient();
      try {
        validator.restoreCookies(records);
        _pendingCookies[sourceId] = validator.exportCookies();
      } finally {
        validator.close();
      }
    } else {
      network.restoreCookies(records);
    }
    _variables[sourceId] = variables;
  }

  Future<void> close() async {
    for (final session in _sessions.values) {
      session.close();
    }
    _providedNetwork?.close();
    await runtime.close();
  }
}

class _EngineHost implements ScriptHost {
  _EngineHost(
    this.network,
    this.baseUrl,
    this.variables,
    this.cancellation,
    this.platform,
    this.runtime,
    this.defaultHeaders,
  );
  final NetworkClient network;
  final Uri baseUrl;
  final Map<String, Object?> variables;
  final CancellationToken? cancellation;
  final ScriptHost? platform;
  final ScriptRuntime runtime;
  final Map<String, String> defaultHeaders;
  @override
  Future<Object?> call(String method, List<Object?> arguments) async {
    cancellation?.throwIfCancelled();
    switch (method) {
      case 'net.get':
        return (await network.request(
          baseUrl.resolve(arguments[0].toString()),
          headers: defaultHeaders,
          cancellation: cancellation,
        )).body;
      case 'net.request':
        final options = Map<String, Object?>.from(arguments[0] as Map);
        final headers = options.containsKey('headers')
            ? Map<String, String>.from(options['headers'] as Map? ?? {})
            : (options['inheritHeaders'] == false
                  ? <String, String>{}
                  : defaultHeaders);
        final response = await network.request(
          baseUrl.resolve(options['url'].toString()),
          method: options['method'] as String? ?? 'GET',
          headers: headers,
          body: options['body'] as String?,
          charset: options['charset'] as String?,
          timeout: Duration(
            milliseconds: options['timeoutMs'] as int? ?? 30000,
          ),
          cancellation: cancellation,
          followRedirects: options['followRedirects'] as bool? ?? true,
        );
        return response.toJson();
      case 'browser.open':
        if (platform == null) {
          throw const EngineException(
            'unsupported_host_api',
            'Browser adapter not installed',
          );
        }
        final result = await platform!.call(method, arguments);
        cancellation?.throwIfCancelled();
        if (result is Map && result['url'] is String) {
          final uri = Uri.parse(result['url'] as String);
          if (!['http', 'https'].contains(uri.scheme) || uri.host.isEmpty) {
            throw const EngineException(
              'invalid_url',
              'Browser result must include absolute HTTP(S) URL',
            );
          }
          if (result['cookies'] is List) {
            network.importCookies(
              uri,
              (result['cookies'] as List).map(
                (value) => Cookie.fromSetCookieValue(value as String),
              ),
            );
          } else if (result['cookie'] is String) {
            network.importBrowserCookieHeader(uri, result['cookie'] as String);
          }
        }
        return result;
      case 'parse.getString':
      case 'parse.getStringList':
      case 'parse.getElement':
      case 'parse.getElements':
        if (arguments.length < 2) {
          throw const EngineException(
            'invalid_arguments',
            'parse calls require rule and content',
          );
        }
        final rule = arguments[0]?.toString() ?? '';
        if (rule.toLowerCase().contains('@js:') || rule.contains('<js>')) {
          throw const EngineException(
            'unsupported_rule',
            'Nested JS rules are not supported in parse host calls',
          );
        }
        final elementMode =
            method == 'parse.getElement' || method == 'parse.getElements';
        final values = rule.isEmpty
            ? <Object?>[]
            : await RuleEvaluator(runtime).evaluate(
                rule,
                arguments[1],
                ScriptContext(host: this),
                cancellation: cancellation,
                elements: elementMode,
              );
        if (elementMode) {
          final serialized = values
              .map((v) => v is Element ? v.outerHtml : v)
              .toList();
          return method == 'parse.getElement'
              ? serialized.firstOrNull
              : serialized;
        }
        var strings = values.map(RuleEvaluator.text).toList();
        if (arguments.length > 2 && arguments[2] == true) {
          final relativeBase = arguments.length > 3 && arguments[3] != null
              ? Uri.parse(arguments[3].toString())
              : baseUrl;
          if (!relativeBase.isAbsolute) {
            throw const EngineException(
              'invalid_url',
              'parse base URL must be absolute',
            );
          }
          strings = strings
              .where((v) => v.trim().isNotEmpty)
              .map((v) => relativeBase.resolve(v).toString())
              .toSet()
              .toList();
        }
        return method == 'parse.getString' ? strings.join('\n') : strings;
      case 'cookies.get':
        return network.cookieHeader(baseUrl.resolve(arguments[0].toString()));
      case 'variables.get':
        return variables[arguments[0].toString()];
      case 'variables.put':
        variables[arguments[0].toString()] = arguments[1];
        return arguments[1];
      case 'encoding.base64Encode':
        if (arguments.length > 1) {
          if (platform != null) return platform!.call(method, arguments);
          throw const EngineException(
            'unsupported_host_api',
            'Extended base64 requires utility adapter',
          );
        }
        return base64.encode(utf8.encode(arguments[0].toString()));
      case 'encoding.base64Decode':
        if (arguments.length > 1) {
          if (platform != null) return platform!.call(method, arguments);
          throw const EngineException(
            'unsupported_host_api',
            'Extended base64 requires utility adapter',
          );
        }
        return utf8.decode(base64.decode(arguments[0].toString()));
      default:
        if (platform != null) return platform!.call(method, arguments);
        throw EngineException('unsupported_host_api', 'Unknown source.$method');
    }
  }
}
