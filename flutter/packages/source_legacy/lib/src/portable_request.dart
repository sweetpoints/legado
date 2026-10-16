import 'package:source_engine/source_engine.dart';

import '../source_legacy.dart' show adaptLegacyRequest;
import 'portable_source.dart';

/// Legacy URL programs plus the existing conservative literal request compiler.
/// Browser options and arbitrary embedded expressions remain explicit failures.
class PortableLegacyPageFetcher implements LegacyPageFetcher {
  const PortableLegacyPageFetcher(this.runtime, this.capabilities);
  final AuxiliaryScriptRuntime runtime;
  final PortableLegacyScriptCapabilities capabilities;
  static String? script(String raw) {
    raw = raw.trim();
    if (raw.startsWith('<js>')) {
      if (!raw.endsWith('</js>')) return null;
      return raw.substring(4, raw.length - 5);
    }
    return raw.startsWith('@js:') ? raw.substring(4) : null;
  }

  bool supportsUrl(String raw) {
    final code = script(raw);
    if (code != null) return capabilities.supports(code);
    return !RegExp(
      r'@js:|<js>|@webjs:|\{\{(?![A-Za-z]\w*(?:\s*[+-]\s*\d+)?\}\})',
      caseSensitive: false,
    ).hasMatch(raw);
  }

  @override
  Future<NetworkResponse> fetch(
    SourceDefinition source,
    String operation,
    Map<String, Object?> input,
    ScriptContext context, {
    String? nextUrl,
    CancellationToken? cancellation,
  }) async {
    final original = source.metadata['legacyOriginal'] as Map;
    Object? raw =
        nextUrl ??
        switch (operation) {
          'search' => original['searchUrl'],
          'explore' => input['exploreUrl'],
          'info' => input['bookUrl'],
          'toc' => input['tocUrl'] ?? input['bookUrl'],
          'content' => input['chapterUrl'],
          _ => null,
        };
    if (raw is! String || raw.trim().isEmpty || !supportsUrl(raw)) {
      throw const EngineException(
        'legacy_request_requires_migration',
        'Legacy URL program or expression requires an implemented request capability',
      );
    }
    final code = script(raw);
    if (code != null) {
      raw = await runtime.evaluateAuxiliary(
        code,
        ScriptContext(
          variables: {
            ...context.variables,
            ...input,
            'page': input['page'] ?? 1,
          },
          host: context.host,
          timeout: context.timeout,
        ),
        cancellation: cancellation,
      );
      if (raw is! String || raw.isEmpty) {
        throw const EngineException(
          'invalid_url',
          'Legacy URL program must return a non-empty string',
        );
      }
    }
    final stage = adaptLegacyRequest(
      source,
      const SourceStage(url: '', legacyRequestInput: 'exploreUrl'),
      {'exploreUrl': raw},
    );
    String expand(String value, {required bool url}) {
      value = expandLegacyPageTemplate(value, {
        'page': input['page'] ?? 1,
        ...input,
      }, urlChoices: url);
      return value.replaceAllMapped(RegExp(r'\{\{([A-Za-z]\w*)\}\}'), (match) {
        final key = match[1]!;
        final replacement = input[key];
        if (replacement == null) {
          throw EngineException('missing_input', 'Missing request input $key');
        }
        if (replacement is! String &&
            replacement is! num &&
            replacement is! bool) {
          throw const EngineException(
            'invalid_request',
            'Request placeholders require scalar data',
          );
        }
        return url && !key.endsWith('Url')
            ? Uri.encodeComponent(replacement.toString())
            : replacement.toString();
      });
    }

    final text = expand(stage.url, url: true);
    final uri = Uri.parse(text);
    if (source.metadata['legacyBaseUrlUnavailable'] == true &&
        (!uri.hasScheme || uri.host.isEmpty)) {
      throw const EngineException(
        'legacy_base_url_required',
        'Opaque source identities require an absolute request URL',
      );
    }
    final target = Uri.parse(
      context.variables['baseUrl']?.toString() ?? source.baseUrl.toString(),
    ).resolveUri(uri);
    var body = stage.body == null ? null : expand(stage.body!, url: false);
    if (body != null && stage.bodyEncoding == 'legacyFormUtf8') {
      body = encodeLegacyFormUtf8Body(body);
    }
    final reply = await context.host.call('net.request', [
      {
        'url': target.toString(),
        'method': stage.method,
        'headers': stage.headers ?? source.headers,
        'body': ?body,
        if (stage.charset != null) 'charset': stage.charset,
      },
    ]);
    cancellation?.throwIfCancelled();
    if (reply is! Map ||
        reply['body'] is! String ||
        reply['url'] is! String ||
        reply['status'] is! int ||
        reply['headers'] is! Map) {
      throw const EngineException(
        'invalid_legacy_request_result',
        'Portable request returned an invalid HTTP response',
      );
    }
    return NetworkResponse(
      Uri.parse(reply['url'] as String),
      reply['status'] as int,
      Map<String, String>.from(reply['headers'] as Map),
      reply['body'] as String,
      multiHeaders: (reply['multiHeaders'] as Map? ?? {}).map(
        (key, value) =>
            MapEntry(key as String, List<String>.from(value as List)),
      ),
    );
  }
}
