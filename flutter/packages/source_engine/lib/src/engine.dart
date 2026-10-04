import 'dart:convert';
import 'dart:io' show Cookie;

import 'contracts.dart';
import 'network.dart';
import 'rules.dart';

class SourceEngine {
  SourceEngine({
    required this.runtime,
    NetworkClient? network,
    this.platform,
    this.hostAdapter,
  }) : _providedNetwork = network;
  final ScriptRuntime runtime;
  final ScriptHost? platform;
  final ScriptHost Function(ScriptHost)? hostAdapter;
  final NetworkClient? _providedNetwork;
  final Map<String, NetworkClient> _sessions = {};
  final Map<String, Map<String, Object?>> _variables = {};
  final Map<String, List<Map<String, Object?>>> _pendingCookies = {};
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
    final stage = source.stages[operation];
    if (stage == null) {
      throw EngineException('missing_stage', 'Source has no $operation stage');
    }
    final url = stage.url.replaceAllMapped(
      RegExp(r'\{\{([A-Za-z][A-Za-z0-9_]*)\}\}'),
      (m) {
        final value = input[m[1]];
        if (value == null) {
          throw EngineException('missing_input', 'Missing ${m[1]}');
        }
        // URL-valued inputs are full URLs; other values are encoded components.
        return m[1]!.endsWith('Url')
            ? value.toString()
            : Uri.encodeComponent(value.toString());
      },
    );
    if (stage.maxPages < 1 || stage.maxPages > 1000) {
      throw const EngineException('invalid_source', 'maxPages must be 1..1000');
    }
    final rules = RuleEvaluator(runtime);
    final results = <Map<String, Object?>>[];
    final visited = <String>{};
    var current = source.baseUrl.resolve(url);
    for (var page = 0; page < stage.maxPages; page++) {
      if (!visited.add(current.toString())) {
        throw const EngineException(
          'pagination_cycle',
          'Next page repeats an already fetched URL',
        );
      }
      final response = await network.request(
        current,
        cancellation: cancellation,
      );
      if (response.status >= 400) {
        throw EngineException('http_error', 'HTTP ${response.status}');
      }

      final rows = stage.list == null
          ? [response.body]
          : await rules.evaluate(
              stage.list!,
              response.body,
              context,
              cancellation: cancellation,
              elements: true,
            );

      for (final row in rows) {
        cancellation?.throwIfCancelled();
        final fields = <String, Object?>{};
        for (final entry in stage.fields.entries) {
          final values = await rules.evaluate(
            entry.value,
            row,
            context,
            cancellation: cancellation,
          );
          var value = values.map(RuleEvaluator.text).join('\n');
          if ((entry.key.endsWith('Url') || entry.key == 'url') &&
              value.isNotEmpty) {
            value = response.url.resolve(value).toString();
          }
          fields[entry.key] = value;
        }
        results.add(fields);
      }
      if (stage.nextPage == null) break;
      final links = await rules.evaluate(
        stage.nextPage!,
        response.body,
        context,
        cancellation: cancellation,
      );
      final next = links
          .map(RuleEvaluator.text)
          .where((x) => x.isNotEmpty)
          .firstOrNull;
      if (next == null) break;
      current = response.url.resolve(next);
      if (page == stage.maxPages - 1) {
        throw const EngineException(
          'pagination_limit',
          'Next page exceeds maxPages',
        );
      }
    }
    if (operation == 'content' && results.length > 1) {
      final merged = Map<String, Object?>.from(results.first);
      merged['content'] = results
          .map((r) => r['content']?.toString() ?? '')
          .join('\n');
      return [merged];
    }

    return results;
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
  );
  final NetworkClient network;
  final Uri baseUrl;
  final Map<String, Object?> variables;
  final CancellationToken? cancellation;
  final ScriptHost? platform;
  @override
  Future<Object?> call(String method, List<Object?> arguments) async {
    cancellation?.throwIfCancelled();
    switch (method) {
      case 'net.get':
        return (await network.request(
          baseUrl.resolve(arguments[0].toString()),
          cancellation: cancellation,
        )).body;
      case 'net.request':
        final options = Map<String, Object?>.from(arguments[0] as Map);
        final headers = (options['headers'] as Map? ?? {}).map(
          (k, v) => MapEntry(k.toString(), v.toString()),
        );
        final response = await network.request(
          baseUrl.resolve(options['url'].toString()),
          method: options['method'] as String? ?? 'GET',
          headers: headers,
          body: options['body'] as String?,
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
      case 'cookies.get':
        return network.cookieHeader(baseUrl.resolve(arguments[0].toString()));
      case 'variables.get':
        return variables[arguments[0].toString()];
      case 'variables.put':
        variables[arguments[0].toString()] = arguments[1];
        return arguments[1];
      case 'encoding.base64Encode':
        return base64.encode(utf8.encode(arguments[0].toString()));
      case 'encoding.base64Decode':
        return utf8.decode(base64.decode(arguments[0].toString()));
      default:
        if (platform != null) return platform!.call(method, arguments);
        throw EngineException('unsupported_host_api', 'Unknown source.$method');
    }
  }
}
