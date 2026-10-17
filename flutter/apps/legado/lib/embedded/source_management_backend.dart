import 'dart:async';
import 'dart:convert';

import 'package:app_core/app_core.dart';
import 'package:flutter/services.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../features/sources/source_providers.dart';
import '../services/book_source_service.dart';

part 'source_management_backend.g.dart';

/// The same management UI uses either the standalone repository or Android Room.
/// Embedded operation never opens a database or constructs a script engine.
abstract interface class SourceManagementBackend {
  Stream<List<Map<String, Object?>>> get sources;
  Future<String> exportJson();
  Future<int> importJson(String json);
  Future<void> update(String json, {required String expectedId});
  Future<void> setEnabled(Map<String, Object?> source, bool enabled);
  Future<void> deleteSource(String id);
  Future<void> close();
}

typedef SourceManagementBinding = ({
  LibraryRepository? repository,
  SourceManagementBackend? backend,
});

@riverpod
SourceManagementBackend sourceManagementBackend(
  Ref ref,
  SourceManagementBinding binding,
) {
  final injected = binding.backend;
  if (injected != null) {
    return injected; // Caller owns injected backend lifetime.
  }
  final repository = binding.repository;
  if (repository == null) throw StateError('source_management_backend_missing');
  final backend = RepositorySourceManagementBackend(
    repository,
    ref.watch(bookSourceServiceProvider(repository)),
  );
  ref.onDispose(() => unawaited(backend.close()));
  return backend;
}

@riverpod
Stream<List<Map<String, Object?>>> sourceManagementList(
  Ref ref,
  SourceManagementBinding binding,
) => ref.watch(sourceManagementBackendProvider(binding)).sources;

class RepositorySourceManagementBackend implements SourceManagementBackend {
  RepositorySourceManagementBackend(this.repository, this.service);
  final LibraryRepository repository;
  final BookSourceService service;
  final _lifetime = StreamController<void>.broadcast();
  bool _closed = false;
  void _check() {
    if (_closed) throw StateError('source_management_backend_closed');
  }

  @override
  Stream<List<Map<String, Object?>>> get sources {
    _check();
    return _snapshots(
      fetch: repository.sources,
      changes: repository.changes,
      lifetime: _lifetime.stream,
    );
  }

  @override
  Future<String> exportJson() {
    _check();
    return service.exportJson();
  }

  @override
  Future<int> importJson(String json) {
    _check();
    return service.importJson(json);
  }

  @override
  Future<void> update(String json, {required String expectedId}) async {
    _check();
    final records = BookSourceService.parseImport(json);
    if (records.length != 1 ||
        BookSourceService.sourceId(records.single) != expectedId) {
      throw const FormatException('source_identity_immutable');
    }
    await service.importJson(json);
  }

  @override
  Future<void> setEnabled(Map<String, Object?> source, bool enabled) {
    _check();
    return service.setEnabled(source, enabled);
  }

  @override
  Future<void> deleteSource(String id) {
    _check();
    return service.deleteSource(id);
  }

  @override
  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    _lifetime.add(null);
    await _lifetime.close();
    // The shared service belongs to bookSourceServiceProvider, not this view.
  }
}

class MethodChannelSourceManagementBackend implements SourceManagementBackend {
  MethodChannelSourceManagementBackend({
    this.channel = const MethodChannel('legado/embedded_ui'),
    this.changes = const EventChannel('legado/embedded_ui/changes'),
  });
  final MethodChannel channel;
  final EventChannel changes;
  static const maxJsonBytes = 2 * 1024 * 1024;
  static const maxSources = 1000;
  static const maxListJsonBytes = 16 * 1024 * 1024;
  static const maxListedSources = 10000;
  final _lifetime = StreamController<void>.broadcast();
  bool _closed = false;
  void _check() {
    if (_closed) throw StateError('source_management_backend_closed');
  }

  static List<Map<String, Object?>> validate(
    String json, {
    bool listResponse = false,
  }) {
    final byteLimit = listResponse ? maxListJsonBytes : maxJsonBytes;
    final countLimit = listResponse ? maxListedSources : maxSources;
    if (utf8.encode(json).length > byteLimit) {
      throw const FormatException('source_json_too_large');
    }
    final decoded = jsonDecode(json);
    if (decoded is List && decoded.length > countLimit) {
      throw const FormatException('too_many_sources');
    }
    if (listResponse) {
      if (decoded is! List) {
        throw const FormatException('invalid_sources_response');
      }
      final records = <Map<String, Object?>>[];
      final identities = <String>{};
      for (final item in decoded) {
        if (item is! Map<String, dynamic>) {
          throw const FormatException('invalid_sources_response');
        }
        final record = Map<String, Object?>.from(item);
        if (!identities.add(BookSourceService.sourceId(record))) {
          throw const FormatException('duplicate_source_identity');
        }
        // Existing native records remain repairable even when rules are invalid
        // or require capabilities absent from the Dart importer.
        records.add(record);
      }
      return records;
    }
    return BookSourceService.parseImport(json);
  }

  Future<List<Map<String, Object?>>> _list() async {
    _check();
    final json = await channel.invokeMethod<String>('sources.list');
    if (json == null) throw const FormatException('invalid_sources_response');
    return validate(json, listResponse: true);
  }

  @override
  Stream<List<Map<String, Object?>>> get sources {
    _check();
    return _snapshots(
      fetch: _list,
      changes: changes.receiveBroadcastStream().where(
        (event) => event == 'sources.changed',
      ),
      lifetime: _lifetime.stream,
    );
  }

  @override
  Future<String> exportJson() async =>
      const JsonEncoder.withIndent('  ').convert(await _list());
  @override
  Future<int> importJson(String json) async {
    _check();
    final records = validate(json);
    await channel.invokeMethod<void>('sources.import', {'json': json});
    return records.length;
  }

  @override
  Future<void> update(String json, {required String expectedId}) async {
    _check();
    final records = validate(json);
    if (records.length != 1 ||
        BookSourceService.sourceId(records.single) != expectedId) {
      throw const FormatException('source_identity_immutable');
    }
    await channel.invokeMethod<void>('sources.update', {
      'json': json,
      'expectedId': expectedId,
    });
  }

  @override
  Future<void> setEnabled(Map<String, Object?> source, bool enabled) async {
    _check();
    await channel.invokeMethod<void>('sources.setEnabled', {
      'id': BookSourceService.sourceId(source),
      'enabled': enabled,
    });
  }

  @override
  Future<void> deleteSource(String id) async {
    _check();
    await channel.invokeMethod<void>('sources.remove', {'id': id});
  }

  Future<void> closeNavigation() async {
    _check();
    await channel.invokeMethod<void>('navigation.close');
  }

  @override
  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    _lifetime.add(null);
    await _lifetime.close();
  }
}

/// Subscribe before fetching, serialize snapshots and remember changes arriving
/// during a request. A stale first response can never consume a later change.
Stream<List<Map<String, Object?>>> _snapshots({
  required Future<List<Map<String, Object?>>> Function() fetch,
  required Stream<Object?> changes,
  required Stream<void> lifetime,
}) {
  late StreamController<List<Map<String, Object?>>> controller;
  StreamSubscription<Object?>? events;
  StreamSubscription<void>? disposal;
  var stopped = false, running = false, dirty = false;
  Future<void> refresh() async {
    dirty = true;
    if (running || stopped) return;
    running = true;
    while (dirty && !stopped) {
      dirty = false;
      try {
        final value = await fetch();
        if (!stopped) controller.add(value);
      } catch (error, stack) {
        if (!stopped) controller.addError(error, stack);
      }
    }
    running = false;
  }

  Future<void> stop() async {
    if (stopped) return;
    stopped = true;
    await events?.cancel();
    await disposal?.cancel();
  }

  controller = StreamController(
    onListen: () {
      disposal = lifetime.listen((_) {
        unawaited(stop().then((_) => controller.close()));
      });
      events = changes.listen(
        (_) => unawaited(refresh()),
        onError: (Object error, StackTrace stack) {
          if (!stopped) controller.addError(error, stack);
        },
      );
      unawaited(refresh());
    },
    onCancel: stop,
  );
  return controller.stream;
}
