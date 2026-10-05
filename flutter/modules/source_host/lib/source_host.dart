import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:source_migration/source_migration.dart';

import 'session_store.dart';

/// Protocol v1. Long requests are cancellable by task ID; failures never fall back.
class SourceHost {
  SourceHost(this.createEngine, {MethodChannel? channel, this.sessionStore})
    : channel = channel ?? const MethodChannel('legado/source_engine');
  final SourceEngine Function(SourceDefinition) createEngine;
  final MethodChannel channel;
  final SourceSessionStore? sessionStore;
  final Map<String, CancellationToken> _tasks = {};
  final Map<String, _CachedEngine> _engines = {};
  final Map<String, Future<void>> _queues = {};
  final Set<String> _active = {};
  bool _closed = false;

  Future<void> attach({Future<void> Function()? initialize}) async {
    channel.setMethodCallHandler(handle);
    try {
      await initialize?.call();
      await channel.invokeMethod<void>('ready', {'protocolVersion': 1});
    } catch (error) {
      await channel.invokeMethod<void>('startupError', {
        'protocolVersion': 1,
        'code': 'runtime_initialization_failed',
        'message': error.toString(),
      });
    }
  }

  Future<Object?> handle(MethodCall call) async {
    final args = Map<String, Object?>.from(call.arguments as Map? ?? {});
    final id = args['taskId'] is String ? args['taskId'] as String : null;
    if (call.method == 'shutdown') {
      await close();
      return null;
    }
    if (call.method == 'cancel') {
      _tasks[id]?.cancel();
      try {
        await const MethodChannel('legado/source_host_platform')
            .invokeMethod<void>('cancelBrowser', {'taskId': id});
      } on MissingPluginException {
        // Headless hosts need no browser adapter.
      }
      return null;
    }
    if (call.method == 'migrate') return _migrate(args);
    if (call.method == 'evaluate') return _evaluate(args, id);
    if (call.method != 'execute') throw MissingPluginException(call.method);
    if (_closed) throw PlatformException(code: 'host_closed');
    if (args['protocolVersion'] != 1 || id == null || id.isEmpty) {
      throw PlatformException(
        code: 'invalid_request',
        message: 'Protocol v1 and taskId required',
      );
    }
    if (_tasks.containsKey(id)) {
      throw PlatformException(
        code: 'duplicate_task',
        message: 'Task ID is already running',
      );
    }
    final cancellation = CancellationToken();
    _tasks[id] = cancellation;
    try {
      final raw = Map<String, Object?>.from(
        jsonDecode(args['sourceJson'] as String) as Map,
      );
      final SourceDefinition source;
      if (raw.containsKey('schemaVersion')) {
        source = SourceDefinition.fromJson(raw);
      } else {
        final legacy = LegacySourceImporter().import(raw);
        final blockingIssues = legacy.issues
            .where(
              (issue) =>
                  !(legacy.source.metadata['legacyBaseUrlUnavailable'] ==
                          true &&
                      issue.code == 'legacy.base_url_requires_review' &&
                      issue.path == 'bookSourceUrl'),
            )
            .toList();
        // This one reviewed identity risk is enforced by the engine's absolute
        // stage URL guard; other unsupported capabilities remain blocking.
        if (blockingIssues.isNotEmpty) {
          throw PlatformException(
            code: 'legacy_requires_migration',
            message: 'This legacy source requires migration or unsupported capabilities',
            details: blockingIssues.map((issue) => issue.toJson()).toList(),
          );
        }
        source = legacy.source;
      }
      final key = '${source.id}:${source.metadata['legacy'] == true}';
      return await _serialize(
        key,
        cancellation,
        () => _execute(source, key, args['operation'] as String, {
          ...Map<String, Object?>.from(args['input'] as Map? ?? {}),
          'taskId': id,
        }, cancellation),
      );
    } on EngineException catch (error) {
      throw PlatformException(code: error.code, message: error.message);
    } on FormatException {
      throw PlatformException(
        code: 'invalid_source',
        message: 'Source JSON or configuration is invalid',
      );
    } on PlatformException {
      rethrow;
    } catch (error) {
      throw PlatformException(code: 'engine_failed', message: error.toString());
    } finally {
      _tasks.remove(id);
    }
  }

  Future<Map<String, Object?>> _evaluate(
    Map<String, Object?> args,
    String? id,
  ) async {
    if (_closed) throw PlatformException(code: 'host_closed');
    if (args['protocolVersion'] != 1 ||
        id == null ||
        id.isEmpty ||
        args['sourceJson'] is! String ||
        args['script'] is! String ||
        args['bindings'] is! Map) {
      throw PlatformException(
        code: 'invalid_request',
        message: 'Protocol v1, taskId, sourceJson, script and JSON bindings required',
      );
    }
    if (_tasks.containsKey(id)) {
      throw PlatformException(
        code: 'duplicate_task',
        message: 'Task ID is already running',
      );
    }
    if (args.containsKey('ephemeral') && args['ephemeral'] is! bool) {
      throw PlatformException(
        code: 'invalid_request',
        message: 'ephemeral must be a boolean',
      );
    }
    final ephemeral = args['ephemeral'] == true;
    Map<String, Object?> bindings;
    try {
      bindings = Map<String, Object?>.from(
        jsonDecode(jsonEncode(args['bindings'])) as Map,
      );
    } catch (_) {
      throw PlatformException(
        code: 'invalid_request',
        message: 'Bindings must contain JSON values',
      );
    }
    final token = CancellationToken();
    _tasks[id] = token;
    try {
      final decoded = jsonDecode(args['sourceJson'] as String);
      if (decoded is! Map) {
        throw const FormatException('Source object required');
      }
      final raw = Map<String, Object?>.from(decoded);
      final SourceDefinition identity;
      if (raw.containsKey('schemaVersion')) {
        identity = SourceDefinition.fromJson(raw);
      } else {
        final sourceId = raw['bookSourceUrl'];
        final base = sourceId is String ? Uri.tryParse(sourceId) : null;
        if (base == null ||
            !['http', 'https'].contains(base.scheme) ||
            base.host.isEmpty) {
          throw PlatformException(
            code: 'legacy_requires_migration',
            message: 'Auxiliary legacy scripts require an explicit HTTP(S) source identity',
          );
        }
        if (raw['jsLib'] != null && raw['jsLib'].toString().trim().isNotEmpty) {
          throw PlatformException(
            code: 'legacy_requires_migration',
            message: 'Auxiliary legacy jsLib requires explicit migration',
          );
        }
        final staticHeaders = <String, String>{};
        final header = raw['header'];
        if (header != null && header.toString().trim().isNotEmpty) {
          Object? decodedHeader = header;
          if (header is String) {
            try {
              decodedHeader = jsonDecode(header);
            } on FormatException {
              throw PlatformException(
                code: 'legacy_requires_migration',
                message: 'Auxiliary legacy dynamic headers require migration',
              );
            }
          }
          if (decodedHeader is! Map ||
              decodedHeader.keys.any((key) => key is! String) ||
              decodedHeader.values.any((value) => value is! String)) {
            throw PlatformException(
              code: 'legacy_requires_migration',
              message: 'Auxiliary legacy headers require a static string map',
            );
          }
          staticHeaders.addAll(Map<String, String>.from(decodedHeader));
        }
        identity = SourceDefinition(
          id: sourceId as String,
          name: raw['bookSourceName']?.toString() ?? sourceId,
          baseUrl: base,
          metadata: const {'legacy': true},
          headers: staticHeaders,
        );
      }
      final scriptSource = SourceDefinition(
        id: identity.id,
        name: identity.name,
        baseUrl: identity.baseUrl,
        metadata: identity.metadata,
        headers: identity.headers,
        script:
            'async function search(input){return [{value: await eval(${jsonEncode(args['script'])})}];}',
      );
      final key = '${identity.id}:${identity.metadata['legacy'] == true}';
      if (ephemeral) {
        return await _serialize('__ephemeral:$id', token, () async {
          final engine = createEngine(scriptSource);
          try {
            final records = await engine.execute(
              scriptSource,
              'search',
              input: {...bindings, 'taskId': id},
              cancellation: token,
            );
            token.throwIfCancelled();
            return {'value': records.single['value']};
          } finally {
            await engine.close();
          }
        });
      }
      return await _serialize(key, token, () async {
        final records = await _execute(
          scriptSource,
          key,
          'search',
          {...bindings, 'taskId': id},
          token,
          fingerprintSource: identity,
          preserveCachedConfiguration: !raw.containsKey('schemaVersion'),
        );
        return {'value': records.single['value']};
      });
    } on EngineException catch (error) {
      throw PlatformException(code: error.code, message: error.message);
    } on PlatformException {
      rethrow;
    } on FormatException {
      throw PlatformException(
        code: 'invalid_source',
        message: 'Source JSON or configuration is invalid',
      );
    } on TypeError {
      throw PlatformException(
        code: 'invalid_source',
        message: 'Source fields have invalid types',
      );
    } catch (_) {
      throw PlatformException(
        code: 'engine_failed',
        message: 'Auxiliary script evaluation failed',
      );
    } finally {
      _tasks.remove(id);
    }
  }

  /// Offline preview only: never creates an engine or changes saved sources.
  Map<String, Object?> _migrate(Map<String, Object?> args) {
    if (_closed) throw PlatformException(code: 'host_closed');
    if (args['protocolVersion'] != 1 || args['sourceJson'] is! String) {
      throw PlatformException(
        code: 'invalid_request',
        message: 'Protocol v1 and sourceJson required',
      );
    }
    try {
      final decoded = jsonDecode(args['sourceJson'] as String);
      if (decoded is! Map || decoded.containsKey('schemaVersion')) {
        throw const FormatException('Legacy source object required');
      }
      final result = SourceMigrator().migrate(
        Map<String, Object?>.from(decoded),
      );
      return {
        'protocolVersion': 1,
        ...result.toJson(),
        'requiresManualWork': result.issues.isNotEmpty,
        'verified': false,
        'executed': false,
      };
    } on EngineException catch (error) {
      throw PlatformException(code: error.code, message: error.message);
    } on FormatException {
      throw PlatformException(
        code: 'invalid_source',
        message: 'A valid legacy source JSON object is required',
      );
    } on TypeError {
      throw PlatformException(
        code: 'invalid_source',
        message: 'Legacy source fields have invalid types',
      );
    }
  }

  /// Serialize mutations of one source session; different sources remain concurrent.
  Future<T> _serialize<T>(
    String key,
    CancellationToken token,
    Future<T> Function() action,
  ) {
    final previous = _queues[key] ?? Future<void>.value();
    final result = Completer<T>();
    var started = false;
    unawaited(
      token.whenCancelled.then((_) {
        if (!started && !result.isCompleted) {
          result.completeError(
            const EngineException('cancelled', 'Task cancelled'),
          );
        }
      }),
    );
    final tail = previous.then((_) async {
      started = true;
      try {
        token.throwIfCancelled();
        final value = await action();
        if (!result.isCompleted) result.complete(value);
      } catch (error, stack) {
        if (!result.isCompleted) result.completeError(error, stack);
      }
    });
    _queues[key] = tail;
    unawaited(
      tail.then((_) {
        if (identical(_queues[key], tail)) _queues.remove(key);
      }),
    );
    return result.future;
  }

  Future<List<Map<String, Object?>>> _execute(
    SourceDefinition source,
    String key,
    String operation,
    Map<String, Object?> input,
    CancellationToken token, {
    SourceDefinition? fingerprintSource,
    bool preserveCachedConfiguration = false,
  }) async {
    final fingerprint = jsonEncode(
      _canonical((fingerprintSource ?? source).toJson()),
    );
    var entry = _engines[key];
    if (entry != null &&
        entry.fingerprint != fingerprint &&
        !preserveCachedConfiguration) {
      // The source queue ensures no old task is using the replaced runtime.
      _engines.remove(key);
      await entry.engine.close();
      entry = null;
    }
    if (entry == null) {
      final engine = createEngine(source);
      try {
        final stored = await sessionStore?.read(
          source.id,
          source.metadata['legacy'] == true,
        );
        if (stored != null &&
            stored['formatVersion'] == 1 &&
            stored['origin'] == source.baseUrl.origin) {
          engine.importSession(
            source.id,
            Map<String, Object?>.from(stored['engine'] as Map),
          );
          final runtime = engine.runtime;
          if (runtime is SourceRuntimeState && stored['runtime'] is Map) {
            (runtime as SourceRuntimeState).importRuntimeState(
              Map<String, Object?>.from(stored['runtime'] as Map),
            );
          }
        }
      } catch (_) {
        await engine.close();
        throw const EngineException(
          'session_restore_failed',
          'Persisted source session could not be restored',
        );
      }
      entry = _CachedEngine(fingerprint, engine);
      _engines[key] = entry;
    }
    _active.add(key);
    try {
      final result = await entry.engine.execute(
        source,
        operation,
        input: input,
        cancellation: token,
      );
      token.throwIfCancelled();
      return result;
    } finally {
      try {
        final runtime = entry.engine.runtime;
        await sessionStore?.write(
          source.id,
          source.metadata['legacy'] == true,
          {
            'formatVersion': 1,
            'origin': source.baseUrl.origin,
            'engine': entry.engine.exportSession(source.id),
            if (runtime is SourceRuntimeState)
              'runtime': (runtime as SourceRuntimeState).exportRuntimeState(),
          },
        );
      } catch (_) {
        throw const EngineException(
          'session_write_failed',
          'Source session could not be committed',
        );
      } finally {
        _active.remove(key);
        while (_engines.length > 32) {
          final idle = _engines.keys.where((key) => !_active.contains(key));
          if (idle.isEmpty) break;
          final removed = _engines.remove(idle.first)!;
          await removed.engine.close();
        }
      }
    }
  }

  Future<void> close() async {
    _closed = true;
    for (final token in _tasks.values) {
      token.cancel();
    }
    channel.setMethodCallHandler(null);
    await Future.wait(_queues.values.toList());
    await Future.wait(_engines.values.map((entry) => entry.engine.close()));
    _engines.clear();
  }
}

class _CachedEngine {
  const _CachedEngine(this.fingerprint, this.engine);
  final String fingerprint;
  final SourceEngine engine;
}

Object? _canonical(Object? value) {
  if (value is Map) {
    final keys = value.keys.map((key) => key.toString()).toList()..sort();
    return {for (final key in keys) key: _canonical(value[key])};
  }
  if (value is List) return value.map(_canonical).toList();
  return value;
}
