import 'dart:async';

import 'package:flutter/services.dart';

class FavoriteConfigSnapshot {
  FavoriteConfigSnapshot(Object? value) {
    if (value is! Map) throw const FormatException('favorite_config_snapshot');
    generation = value['generation'] as int;
    revision = value['revision'] as int;
    title = value['title'] as String;
    group = value['group'] as String;
    focus = value['focus'] as String;
    if (focus != 'title' && focus != 'group') {
      throw const FormatException('focus');
    }
    titleStart = value['titleStart'] as int;
    titleEnd = value['titleEnd'] as int;
    groupStart = value['groupStart'] as int;
    groupEnd = value['groupEnd'] as int;
    loaded = value['loaded'] as bool;
    loading = value['loading'] as bool;
    busy = value['busy'] as bool;
    canEdit = value['canEdit'] as bool;
    error = value['error'] as bool;
    finished = value['finished'] as bool;
  }
  late final int generation,
      revision,
      titleStart,
      titleEnd,
      groupStart,
      groupEnd;
  late final String title, group, focus;
  late final bool loaded, loading, busy, canEdit, error, finished;
}

/// Private text stays in native draft storage. Only an acknowledged field clears its rejection.
class RssFavoriteConfigBridge {
  RssFavoriteConfigBridge({
    this.channel = const MethodChannel('legado/rss_favorite_config'),
    this.events = const EventChannel('legado/rss_favorite_config/state'),
  });
  final MethodChannel channel;
  final EventChannel events;
  final snapshots = StreamController<FavoriteConfigSnapshot>.broadcast();
  StreamSubscription<Object?>? _events;
  FavoriteConfigSnapshot? latest;
  final _retired = <int>{};
  final _rejected = <String>{};
  Future<void> _queue = Future.value();
  bool _closed = false;
  bool get hasRejectedField => _rejected.isNotEmpty;
  void accept(Object? value) {
    if (_closed) return;
    final next = FavoriteConfigSnapshot(value);
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
        } catch (e, s) {
          if (!_closed) snapshots.addError(e, s);
        }
      },
      onError: (Object e, StackTrace s) {
        if (!_closed) snapshots.addError(e, s);
      },
    );
    accept(await channel.invokeMethod<Object?>('snapshot'));
  }

  Future<void> command(String op, [Map<String, Object?> args = const {}]) {
    final generation = latest?.generation;
    final immutable = Map<String, Object?>.unmodifiable({
      ...args,
      'op': op,
      'generation': generation,
    });
    final field = op == 'field' ? args['key'] as String? : null;
    final next = _queue.then((_) async {
      if (_closed || generation == null || latest?.generation != generation) {
        throw StateError('favorite_config_retired');
      }
      if (field == null && op != 'cancel' && _rejected.isNotEmpty) {
        throw StateError('favorite_config_field_unacknowledged');
      }
      try {
        final ack = FavoriteConfigSnapshot(
          await channel.invokeMethod<Object?>('command', immutable),
        );
        if (_closed ||
            ack.generation != generation ||
            latest?.generation != generation) {
          throw StateError('favorite_config_retired');
        }
        if (field != null) {
          final text = field == 'title' ? ack.title : ack.group;
          final start = field == 'title' ? ack.titleStart : ack.groupStart;
          final end = field == 'title' ? ack.titleEnd : ack.groupEnd;
          if (text != args['text'] ||
              start != args['start'] ||
              end != args['end']) {
            throw StateError('favorite_config_field_unacknowledged');
          }
          _rejected.remove(field);
        }
        accept({
          'generation': ack.generation,
          'revision': ack.revision,
          'title': ack.title,
          'group': ack.group,
          'focus': ack.focus,
          'titleStart': ack.titleStart,
          'titleEnd': ack.titleEnd,
          'groupStart': ack.groupStart,
          'groupEnd': ack.groupEnd,
          'loaded': ack.loaded,
          'loading': ack.loading,
          'busy': ack.busy,
          'canEdit': ack.canEdit,
          'error': ack.error,
          'finished': ack.finished,
        });
      } catch (_) {
        if (field != null) _rejected.add(field);
        rethrow;
      }
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
