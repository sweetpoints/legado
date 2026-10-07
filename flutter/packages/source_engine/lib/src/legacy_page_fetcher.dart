import 'contracts.dart';
import 'network.dart';

/// Optional native implementation of the original URL/request/network pipeline.
abstract interface class LegacyPageFetcher {
  Future<NetworkResponse> fetch(
    SourceDefinition source,
    String operation,
    Map<String, Object?> input,
    ScriptContext context, {
    String? nextUrl,
    CancellationToken? cancellation,
  });
}

class HostLegacyPageFetcher implements LegacyPageFetcher {
  const HostLegacyPageFetcher();
  @override
  Future<NetworkResponse> fetch(
    SourceDefinition source,
    String operation,
    Map<String, Object?> input,
    ScriptContext context, {
    String? nextUrl,
    CancellationToken? cancellation,
  }) async {
    cancellation?.throwIfCancelled();
    final original = source.metadata['legacyOriginal'];
    if (original is! Map) {
      throw const EngineException(
        'legacy_request_source_required',
        'Native legacy requests require the original source snapshot',
      );
    }
    final raw =
        nextUrl ??
        switch (operation) {
          'search' => original['searchUrl'],
          'explore' => input['exploreUrl'],
          'info' => input['bookUrl'],
          'toc' => input['tocUrl'] ?? input['bookUrl'],
          'content' => input['chapterUrl'],
          _ => null,
        };
    if (raw is! String || raw.trim().isEmpty) {
      throw const EngineException(
        'missing_input',
        'Legacy request URL is missing',
      );
    }
    final task = context.variables['taskId'];
    if (task is! String || task.isEmpty) {
      throw const EngineException(
        'invalid_request',
        'Native legacy request requires a task identity',
      );
    }
    final response = await context.host.call('legacyRequest.fetch', [
      {
        'urlRule': raw,
        'source': original,
        'operation': operation,
        'baseUrl': context.variables['baseUrl'],
        'variables': {
          ...context.variables,
          ...Map<String, Object?>.from(
            context.variables['legacyVariables'] as Map? ?? {},
          ),
        },
        if (input['key'] != null) 'key': input['key'],
        if (input['page'] != null) 'page': input['page'],
      },
      {'__sourceTaskId': task, '__sourceHostCallback': false},
    ]);
    cancellation?.throwIfCancelled();
    if (response is! Map ||
        response['value'] is! Map ||
        response['variables'] is! Map) {
      throw const EngineException(
        'invalid_legacy_request_result',
        'Native request response requires value and variables',
      );
    }
    final variables = response['variables'] as Map;
    if (variables.entries.any((e) => e.key is! String || e.value is! String)) {
      throw const EngineException(
        'invalid_legacy_request_result',
        'Native request variables must be strings',
      );
    }
    for (final e in variables.entries) {
      await context.host.call('variables.put', [e.key, e.value]);
    }
    final value = response['value'] as Map;
    if (value['url'] is! String ||
        value['body'] is! String ||
        value['status'] is! int ||
        value['headers'] is! Map) {
      throw const EngineException(
        'invalid_legacy_request_result',
        'Invalid native HTTP response shape',
      );
    }
    final multi = <String, List<String>>{};
    for (final e in (value['headers'] as Map).entries) {
      if (e.key is! String ||
          e.value is! List ||
          (e.value as List).any((x) => x is! String)) {
        throw const EngineException(
          'invalid_legacy_request_result',
          'Native HTTP headers must be string lists',
        );
      }
      multi[e.key as String] = List<String>.from(e.value as List);
    }
    return NetworkResponse(
      Uri.parse(value['url'] as String),
      value['status'] as int,
      {for (final e in multi.entries) e.key: e.value.join(', ')},
      value['body'] as String,
      multiHeaders: multi,
    );
  }
}
