import 'dart:async';

class EngineException implements Exception {
  const EngineException(this.code, this.message);
  final String code;
  final String message;
  @override
  String toString() => '$code: $message';
}

class CancellationToken {
  final Completer<void> _cancelled = Completer<void>();
  bool get isCancelled => _cancelled.isCompleted;
  Future<void> get whenCancelled => _cancelled.future;
  void cancel() {
    if (!isCancelled) _cancelled.complete();
  }

  void throwIfCancelled() {
    if (isCancelled) throw const EngineException('cancelled', 'Task cancelled');
  }
}

abstract interface class ScriptHost {
  Future<Object?> call(String method, List<Object?> arguments);
}

class ScriptContext {
  const ScriptContext({
    this.variables = const {},
    required this.host,
    this.timeout = const Duration(seconds: 30),
  });
  final Map<String, Object?> variables;
  final ScriptHost host;
  final Duration timeout;
}

abstract interface class ScriptRuntime {
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  });
  Future<void> close();
}

class SourceStage {
  const SourceStage({
    required this.url,
    this.list,
    this.fields = const {},
    this.nextPage,
    this.maxPages = 20,
    this.method = 'GET',
    this.bodyEncoding = 'raw',
    this.body,
    this.headers,
    this.charset,
  });
  final String url;
  final String method;
  final String bodyEncoding;
  final String? body;
  final Map<String, String>? headers;
  final String? charset;
  final String? list;
  final String? nextPage;
  final int maxPages;
  final Map<String, String> fields;
  factory SourceStage.fromJson(Map<String, Object?> json) => SourceStage(
    url: json['url'] as String? ?? '',
    list: json['list'] as String?,
    nextPage: json['nextPage'] as String?,
    maxPages: json['maxPages'] as int? ?? 20,
    method: json['method'] as String? ?? 'GET',
    bodyEncoding: json['bodyEncoding'] as String? ?? 'raw',
    body: json['body'] as String?,
    headers: json['headers'] == null
        ? null
        : Map<String, String>.from(json['headers'] as Map),
    charset: json['charset'] as String?,
    fields: (json['fields'] as Map? ?? {}).map(
      (k, v) => MapEntry(k.toString(), v.toString()),
    ),
  );
  Map<String, Object?> toJson() => {
    'url': url,
    if (list != null) 'list': list,
    'fields': fields,
    if (nextPage != null) 'nextPage': nextPage,
    'maxPages': maxPages,
    'method': method,
    'bodyEncoding': bodyEncoding,
    if (body != null) 'body': body,
    if (headers != null) 'headers': headers,
    if (charset != null) 'charset': charset,
  };
}

class SourceDefinition {
  SourceDefinition({
    required this.id,
    required this.name,
    required this.baseUrl,
    this.schemaVersion = 1,
    this.stages = const {},
    this.metadata = const {},
    this.script,
    this.headers = const {},
  }) {
    if (schemaVersion != 1) {
      throw EngineException(
        'unsupported_version',
        'Unsupported source schema $schemaVersion',
      );
    }
    if (id.isEmpty || name.isEmpty) {
      throw const EngineException(
        'invalid_source',
        'id and name must be nonempty',
      );
    }
    if (!['http', 'https'].contains(baseUrl.scheme) || baseUrl.host.isEmpty) {
      throw const EngineException(
        'invalid_source',
        'baseUrl must be an absolute HTTP(S) URL',
      );
    }
    for (final entry in headers.entries) {
      if (!RegExp(r"^[!#$%&'*+.^_`|~0-9A-Za-z-]+$").hasMatch(entry.key) ||
          entry.value.contains('\r') ||
          entry.value.contains('\n')) {
        throw const EngineException('invalid_source', 'Invalid request header');
      }
    }
    for (final option in ['maxConcurrentRequests', 'requestIntervalMs']) {
      final value = metadata[option];
      if (value != null &&
          (value is! int ||
              value < (option == 'maxConcurrentRequests' ? 1 : 0))) {
        throw EngineException(
          'invalid_source',
          '$option must be a valid nonnegative integer (concurrency >= 1)',
        );
      }
    }
    for (final stage in stages.values) {
      if (!['raw', 'legacyFormUtf8'].contains(stage.bodyEncoding)) {
        throw const EngineException(
          'invalid_source',
          'Unsupported bodyEncoding',
        );
      }
      if (!RegExp(r"^[!#$%&'*+.^_`|~0-9A-Za-z-]+$").hasMatch(stage.method)) {
        throw const EngineException('invalid_source', 'Invalid HTTP method');
      }
      for (final entry in (stage.headers ?? {}).entries) {
        if (!RegExp(r"^[!#$%&'*+.^_`|~0-9A-Za-z-]+$").hasMatch(entry.key) ||
            entry.value.contains('\r') ||
            entry.value.contains('\n')) {
          throw const EngineException('invalid_source', 'Invalid stage header');
        }
      }
      if (stage.maxPages < 1 || stage.maxPages > 1000) {
        throw const EngineException(
          'invalid_source',
          'maxPages must be 1..1000',
        );
      }
    }
    for (final key in stages.keys) {
      if (!['search', 'explore', 'info', 'toc', 'content'].contains(key)) {
        throw EngineException('invalid_source', 'Unknown stage $key');
      }
    }
  }
  final String id;
  final String name;
  final Uri baseUrl;
  final int schemaVersion;
  final Map<String, SourceStage> stages;
  final Map<String, Object?> metadata;
  final String? script;
  final Map<String, String> headers;
  factory SourceDefinition.fromJson(Map<String, Object?> json) =>
      SourceDefinition(
        id: json['id'] as String? ?? '',
        name: json['name'] as String? ?? '',
        baseUrl: Uri.parse(json['baseUrl'] as String? ?? ''),
        schemaVersion: json['schemaVersion'] as int? ?? 1,
        stages: (json['stages'] as Map? ?? {}).map(
          (k, v) => MapEntry(
            k.toString(),
            SourceStage.fromJson(Map<String, Object?>.from(v as Map)),
          ),
        ),
        metadata: Map<String, Object?>.from(json['metadata'] as Map? ?? {}),
        script: json['script'] as String?,
        headers: Map<String, String>.from(json['headers'] as Map? ?? {}),
      );
  Map<String, Object?> toJson() => {
    'schemaVersion': schemaVersion,
    'id': id,
    'name': name,
    'baseUrl': baseUrl.toString(),
    'stages': stages.map((k, v) => MapEntry(k, v.toJson())),
    'metadata': metadata,
    if (script != null) 'script': script,
    'headers': headers,
  };
}
