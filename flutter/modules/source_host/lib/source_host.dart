import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';

/// Protocol v1. Long requests are cancellable by task ID; failures never fall back.
class SourceHost {
  SourceHost(this.createEngine, {MethodChannel? channel})
    : channel = channel ?? const MethodChannel('legado/source_engine');
  final SourceEngine Function(SourceDefinition) createEngine;
  final MethodChannel channel;
  final Map<String, CancellationToken> _tasks = {};
  final Map<String, SourceEngine> _engines = {};
  final Map<String, int> _engineUsers = {};

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
    final id = args['taskId'] as String?;
    if (call.method == 'cancel') {
      _tasks[id]?.cancel();
      // Browser UI jobs are also keyed by this task ID.
      try {
        await const MethodChannel('legado/source_host_platform')
            .invokeMethod<void>('cancelBrowser', {'taskId': id});
      } on MissingPluginException {
        // Headless/unit-test hosts need no browser adapter.
      }
      return null;
    }
    if (call.method != 'execute') throw MissingPluginException(call.method);
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
    String? engineKey;
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
        if (legacy.requiresManualWork) {
          throw PlatformException(
            code: 'legacy_requires_migration',
            message: 'This legacy source requires migration or unsupported capabilities',
            details: legacy.issues.map((issue) => issue.toJson()).toList(),
          );
        }
        source = legacy.source;
      }
      final key = "${source.id}:${source.metadata['legacy'] == true}";
      engineKey = key;
      _engineUsers[key] = (_engineUsers[key] ?? 0) + 1;
      final engine = _engines.putIfAbsent(key, () => createEngine(source));
      final result = await engine.execute(
        source,
        args['operation'] as String,
        input: {
          ...Map<String, Object?>.from(args['input'] as Map? ?? {}),
          'taskId': id,
        },
        cancellation: cancellation,
      );
      cancellation.throwIfCancelled();
      return result;
    } on EngineException catch (error) {
      throw PlatformException(code: error.code, message: error.message);
    } on FormatException catch (error) {
      throw PlatformException(code: 'invalid_source', message: error.message);
    } on PlatformException {
      rethrow;
    } catch (error) {
      throw PlatformException(code: 'engine_failed', message: error.toString());
    } finally {
      _tasks.remove(id);
      if (engineKey != null) {
        _engineUsers[engineKey] = (_engineUsers[engineKey] ?? 1) - 1;
      }
      // Keep idle network/script sessions bounded without evicting active tasks.
      while (_engines.length > 32) {
        final idle = _engines.keys.where(
          (key) => (_engineUsers[key] ?? 0) == 0,
        );
        if (idle.isEmpty) break;
        final key = idle.first;
        final engine = _engines.remove(key)!;
        _engineUsers.remove(key);
        await engine.close();
      }
    }
  }

  Future<void> close() async {
    for (final token in _tasks.values) {
      token.cancel();
    }
    channel.setMethodCallHandler(null);
    await Future.wait(_engines.values.map((engine) => engine.close()));
    _engines.clear();
    _engineUsers.clear();
  }
}
