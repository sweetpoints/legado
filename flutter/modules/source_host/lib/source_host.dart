import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:source_migration/source_migration.dart';

import 'session_store.dart';

/// Protocol v1. Long requests are cancellable by task ID; failures never fall back.
class SourceHost {
  SourceHost(
    this.createEngine, {
    MethodChannel? channel,
    this.sessionStore,
    this.legacyRuleHostEnabled = false,
    this.legacyScriptRuleHostEnabled = false,
    this.legacyPageFetchEnabled = false,
  }) : channel = channel ?? const MethodChannel('legado/source_engine');
  final SourceEngine Function(SourceDefinition) createEngine;
  final MethodChannel channel;
  final SourceSessionStore? sessionStore;
  final bool legacyRuleHostEnabled;
  final bool legacyScriptRuleHostEnabled;
  final bool legacyPageFetchEnabled;
  final Map<String, CancellationToken> _tasks = {};
  final Map<String, _CachedEngine> _engines = {};
  final Map<String, Future<void>> _queues = {};
  final Set<String> _active = {};
  bool _closed = false;

  bool _issueAffectsOperation(LegacyIssue issue, String operation) {
    if (!legacyRuleHostEnabled) return true;
    const scopes = {
      'ruleSearch': 'search',
      'searchUrl': 'search',
      'ruleExplore': 'explore',
      'exploreUrl': 'explore',
      'ruleBookInfo': 'info',
      'ruleToc': 'toc',
      'ruleContent': 'content',
    };
    for (final entry in scopes.entries) {
      if (issue.path == entry.key ||
          issue.path.startsWith('${entry.key}.') ||
          issue.path.startsWith('${entry.key}[')) {
        return operation == entry.value;
      }
    }
    // These are separate Android UI/comment entry points, not request headers
    // or reading-stage hooks. Their original configuration remains available.
    if (issue.code == 'legacy.capability_requires_review' &&
        {
          'loginUrl',
          'loginUi',
          'ruleReview',
          'exploreScreen',
        }.contains(issue.path)) {
      return false;
    }
    // Metadata extraction is shared by all media types; the actual source type
    // is preserved for the Android reader, rather than rejecting its search.
    if (issue.code == 'legacy.non_text_source' &&
        {'search', 'explore', 'info', 'toc'}.contains(operation)) {
      return false;
    }
    return true;
  }

  bool _hostedRequestIssue(LegacyIssue issue, String operation, Map input) {
    if (!legacyPageFetchEnabled) return false;
    if ({
      'legacy.request_options',
      'legacy.dynamic_header',
      'legacy.cookie_policy_requires_review',
    }.contains(issue.code)) {
      return true;
    }
    return issue.code == 'legacy.explore_menu_requires_review' &&
        operation == 'explore' &&
        input['exploreUrl'] is String;
  }

  bool _hostedRuleIssue(LegacyIssue issue, Map<String, Object?> original) {
    if (legacyScriptRuleHostEnabled &&
        issue.code == 'legacy.capability_requires_review' &&
        issue.path == 'jsLib' &&
        (original['mainJs'] is! String ||
            (original['mainJs'] as String).trim().isEmpty)) {
      return true;
    }
    if (!{
      'legacy.rule_requires_review',
      'legacy.regex_mode',
    }.contains(issue.code)) {
      return false;
    }
    final dot = issue.path.indexOf('.');
    if (dot < 0) return false;
    final group = original[issue.path.substring(0, dot)];
    if (group is! Map) return false;
    final rule = group[issue.path.substring(dot + 1)];
    return rule is String &&
        HostLegacyRuleEvaluator.canEvaluate(
          rule,
          allowScripts: legacyScriptRuleHostEnabled,
        );
  }

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
    if (call.method == 'evaluateAuxiliary') return _auxiliary(args, id);
    if (call.method == 'checkAuxiliarySyntax') return _syntax(args, id);
    if (call.method == 'clearSourceState') return _clearSource(args, id);
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
                  _issueAffectsOperation(issue, args['operation'] as String) &&
                  !(legacy.source.metadata['legacyBaseUrlUnavailable'] ==
                          true &&
                      issue.code == 'legacy.base_url_requires_review' &&
                      issue.path == 'bookSourceUrl') &&
                  !(legacyRuleHostEnabled && _hostedRuleIssue(issue, raw)) &&
                  !_hostedRequestIssue(
                    issue,
                    args['operation'] as String,
                    args['input'] as Map? ?? {},
                  ),
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

  void _auxRequest(Map<String, Object?> args, String? id) {
    if (_closed) throw PlatformException(code: 'host_closed');
    if (args['protocolVersion'] != 1 || id == null || id.isEmpty) {
      throw PlatformException(
        code: 'invalid_request',
        message: 'Protocol v1 and taskId required',
      );
    }
    if (_tasks.containsKey(id)) {
      throw PlatformException(code: 'duplicate_task');
    }
  }

  SourceDefinition _auxIdentity(
    Map<String, Object?> args,
    String identity,
    Map<String, Object?> bindings,
  ) {
    Object? descriptor = args['sourceJson'];
    if (descriptor is String) descriptor = jsonDecode(descriptor);
    if (descriptor != null && descriptor is! Map) {
      throw const FormatException('Source descriptor must be an object');
    }
    final data = descriptor is Map
        ? Map<String, Object?>.from(descriptor)
        : <String, Object?>{};
    final location =
        data['baseUrl'] ??
        bindings['baseUrl'] ??
        'https://script.legado.invalid/';
    final base = Uri.tryParse(location.toString());
    if (base == null ||
        !['http', 'https'].contains(base.scheme) ||
        base.host.isEmpty) {
      throw const EngineException(
        'invalid_request',
        'Auxiliary baseUrl must be absolute HTTP(S)',
      );
    }
    final rawHeaders = data['headers'];
    if (rawHeaders != null && rawHeaders is! Map) {
      throw const FormatException('Static headers required');
    }
    return SourceDefinition(
      id: identity,
      name: data['name']?.toString() ?? identity,
      baseUrl: base,
      headers: Map<String, String>.from(rawHeaders as Map? ?? {}),
      metadata: const {'legacy': true},
    );
  }

  Future<Map<String, Object?>> _auxiliary(
    Map<String, Object?> args,
    String? id,
  ) async {
    _auxRequest(args, id);
    if (args['script'] is! String ||
        args['bindings'] is! Map ||
        (args['sourceId'] != null && args['sourceId'] is! String) ||
        (args['prelude'] != null && args['prelude'] is! String)) {
      throw PlatformException(
        code: 'invalid_request',
        message: 'Script, JSON bindings and optional owner/prelude required',
      );
    }
    final timeout = args['timeoutMs'] ?? 10000;
    if (timeout is! int || timeout < 1 || timeout > 300000) {
      throw PlatformException(
        code: 'invalid_request',
        message: 'Invalid auxiliary timeout',
      );
    }
    final token = CancellationToken();
    _tasks[id!] = token;
    try {
      final bindings = Map<String, Object?>.from(
        jsonDecode(jsonEncode(args['bindings'])) as Map,
      );
      final requested = args['sourceId'] as String?;
      final retained = requested != null && requested.isNotEmpty;
      final owner = retained ? requested : 'auxiliary:$id';
      final source = _auxIdentity(args, owner, bindings);
      final key = '__aux:$owner';
      return await _serialize(key, token, () async {
        _CachedEngine? entry = retained ? _engines[key] : null;
        // Auxiliary descriptors describe this call, not the retained VM recipe.
        // The runtime handles prelude changes; pages and headers must preserve
        // source globals while evaluateAuxiliary receives the current descriptor.
        entry ??= _CachedEngine(key, createEngine(source));
        if (retained) _engines[key] = entry;
        _active.add(key);
        try {
          final value = await entry.engine.evaluateAuxiliary(
            source,
            args['script'] as String,
            bindings: {...bindings, 'taskId': id},
            prelude: args['prelude'] as String? ?? '',
            timeout: Duration(milliseconds: timeout),
            cancellation: token,
          );
          token.throwIfCancelled();
          return {'value': value};
        } finally {
          _active.remove(key);
          if (!retained) await entry.engine.close();
          while (_engines.length > 32) {
            final idle = _engines.keys.where(
              (candidate) => !_active.contains(candidate),
            );
            if (idle.isEmpty) break;
            final removed = _engines.remove(idle.first)!;
            await removed.engine.close();
          }
        }
      });
    } on EngineException catch (error) {
      throw PlatformException(code: error.code, message: error.message);
    } on PlatformException {
      rethrow;
    } catch (_) {
      throw PlatformException(
        code: 'invalid_request',
        message: 'Invalid JSON auxiliary request',
      );
    } finally {
      _tasks.remove(id);
    }
  }

  Future<Map<String, Object?>?> _syntax(
    Map<String, Object?> args,
    String? id,
  ) async {
    _auxRequest(args, id);
    if (args['script'] is! String) {
      throw PlatformException(code: 'invalid_request');
    }
    final token = CancellationToken();
    _tasks[id!] = token;
    final engine = createEngine(
      SourceDefinition(
        id: 'syntax:$id',
        name: 'syntax',
        baseUrl: Uri.parse('https://script.legado.invalid/'),
      ),
    );
    try {
      return (await engine.checkAuxiliarySyntax(
        args['script'] as String,
        cancellation: token,
      ))?.toJson();
    } on EngineException catch (error) {
      throw PlatformException(code: error.code, message: error.message);
    } finally {
      _tasks.remove(id);
      await engine.close();
    }
  }

  Future<Object?> _clearSource(Map<String, Object?> args, String? id) async {
    _auxRequest(args, id);
    final owner = args['sourceId'];
    if (owner is! String || owner.isEmpty) {
      throw PlatformException(code: 'invalid_request');
    }
    final token = CancellationToken();
    _tasks[id!] = token;
    try {
      await _serialize('__aux:$owner', token, () async {
        final entry = _engines.remove('__aux:$owner');
        await entry?.engine.close();
      });
    } finally {
      _tasks.remove(id);
    }
    return null;
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
