import 'dart:async';

import 'package:flutter/services.dart';

class FavoritesSnapshot {
  FavoritesSnapshot(Object? value) {
    if (value is! Map) throw const FormatException('favorites_snapshot');
    generation = value['generation'] as int;
    revision = value['revision'] as int;
    toolbar = value['toolbar'] as bool;
    loaded = value['loaded'] as bool;
    busy = value['busy'] as bool;
    error = value['error'] as bool;
    groups = List<String>.from(value['groups'] as List);
    group = value['group'] as String?;
    rows = (value['rows'] as List)
        .map((e) => FavoriteRow(Map.from(e as Map)))
        .toList();
    positions = Map<String, Object?>.from(value['positions'] as Map);
    confirmation = value['confirmation'] == null
        ? null
        : Map<String, Object?>.from(value['confirmation'] as Map);
  }
  late final int generation, revision;
  late final bool toolbar, loaded, busy, error;
  late final List<String> groups;
  late final String? group;
  late final List<FavoriteRow> rows;
  late final Map<String, Object?> positions;
  late final Map<String, Object?>? confirmation;
  double position(String group, {double extent = 100}) {
    final entry = positions[group] as Map?;
    return ((entry?['index'] as int? ?? 0) * extent +
            (entry?['offset'] as int? ?? 0))
        .toDouble();
  }
}

class FavoriteRow {
  FavoriteRow(Map<String, Object?> value)
    : id = value['id'] as String,
      group = value['group'] as String,
      title = value['title'] as String,
      date = value['date'] as String?,
      image = value['image'] as bool,
      imageKey = value['imageKey'] as String;
  final String id, group, title, imageKey;
  final String? date;
  final bool image;
}

/// Ordered commands and generation/revision fences protect native ownership across restoration.
class RssFavoritesBridge {
  RssFavoritesBridge({
    this.channel = const MethodChannel('legado/rss_favorites'),
    this.events = const EventChannel('legado/rss_favorites/state'),
  });
  final MethodChannel channel;
  final EventChannel events;
  final snapshots = StreamController<FavoritesSnapshot>.broadcast();
  StreamSubscription<Object?>? _events;
  FavoritesSnapshot? latest;
  final _retired = <int>{};
  bool _closed = false;
  Future<void> _queue = Future.value();
  void accept(Object? value) {
    if (_closed) return;
    final next = FavoritesSnapshot(value);
    if (_retired.contains(next.generation)) return;
    if (latest?.generation == next.generation &&
        next.revision < latest!.revision) {
      return;
    }
    if (latest != null && latest!.generation != next.generation) {
      _retired.add(latest!.generation);
    }
    latest = next;
    snapshots.add(next);
  }

  Future<void> start() async {
    _events ??= events.receiveBroadcastStream().listen(
      (value) {
        try {
          accept(value);
        } catch (error, stack) {
          if (!_closed) snapshots.addError(error, stack);
        }
      },
      onDone: () {
        _events = null;
        if (!_closed) snapshots.addError(StateError('favorites_disconnected'));
      },
      onError: (Object e, StackTrace s) {
        if (!_closed) snapshots.addError(e, s);
      },
    );
    // A late initial response is filtered by its native generation/revision.
    accept(await channel.invokeMethod<Object?>('snapshot'));
  }

  Future<void> command(String op, Map<String, Object?> args) {
    final generation = latest?.generation;
    final immutable = Map<String, Object?>.unmodifiable({
      ...args,
      'op': op,
      'generation': generation,
    });
    final next = _queue.then((_) async {
      if (_closed || generation == null || latest?.generation != generation) {
        throw StateError('favorites_binding_retired');
      }
      accept(await channel.invokeMethod<Object?>('command', immutable));
    });
    _queue = next.then<void>((_) {}, onError: (Object _, StackTrace _) {});
    return next;
  }

  Future<void> close() async {
    _closed = true;
    await _events?.cancel();
    await snapshots.close();
  }
}
