import 'dart:async';

import 'package:flutter/services.dart';

class NamedGroupRow {
  NamedGroupRow(Map value)
    : id = value['id'] as String,
      name = value['name'] as String;
  final String id, name;
}

class NamedGroupsSnapshot {
  NamedGroupsSnapshot(Object? value) {
    if (value is! Map) throw const FormatException('named_groups_snapshot');
    raw = Map<String, Object?>.from(value);
    generation = value['generation'] as int;
    revision = value['revision'] as int;
    kind = value['kind'] as String;
    if (!['book', 'rss', 'replace'].contains(kind)) {
      throw const FormatException('named_groups_kind');
    }
    showDone = value['showDone'] as bool;
    loading = value['loading'] as bool;
    busy = value['busy'] as bool;
    editing = value['editing'] as bool;
    recoveryFailed = value['recoveryFailed'] as bool;
    mutationUncertain = value['mutationUncertain'] as bool;
    original = value['original'] as String?;
    error = value['error'] as String?;
    name = value['name'] as String;
    start = value['start'] as int;
    end = value['end'] as int;
    if (start < 0 || start > name.length || end < 0 || end > name.length) {
      throw const FormatException('named_groups_selection');
    }
    groups = (value['groups'] as List)
        .map((v) => NamedGroupRow(v as Map))
        .toList(growable: false);
    if (groups.map((g) => g.id).toSet().length != groups.length) {
      throw const FormatException('named_groups_ids');
    }
  }
  late final Map<String, Object?> raw;
  late final int generation, revision, start, end;
  late final String kind, name;
  late final String? original, error;
  late final bool showDone,
      loading,
      busy,
      editing,
      recoveryFailed,
      mutationUncertain;
  late final List<NamedGroupRow> groups;
}

class NamedGroupsBridge {
  NamedGroupsBridge({
    this.channel = const MethodChannel('legado/named_groups'),
    this.events = const EventChannel('legado/named_groups/state'),
  });
  final MethodChannel channel;
  final EventChannel events;
  final snapshots = StreamController<NamedGroupsSnapshot>.broadcast();
  StreamSubscription<Object?>? _events;
  NamedGroupsSnapshot? latest;
  final _retired = <int>{};
  Future<void> _queue = Future.value();
  bool _closed = false, _rejected = false;
  bool get hasRejectedField => _rejected;
  void accept(Object? value) {
    if (_closed) return;
    final next = NamedGroupsSnapshot(value);
    if (_retired.contains(next.generation) ||
        (latest?.generation == next.generation &&
            next.revision < latest!.revision)) {
      return;
    }
    if (latest != null && latest!.generation != next.generation) {
      _retired.add(latest!.generation);
      _rejected = false;
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
    final field = op == 'field';
    final next = _queue.then((_) async {
      if (_closed || generation == null || latest?.generation != generation) {
        throw StateError('named_groups_retired');
      }
      if (!field && !['cancel', 'back'].contains(op) && _rejected) {
        throw StateError('named_groups_unacknowledged');
      }
      try {
        final ack = NamedGroupsSnapshot(
          await channel.invokeMethod<Object?>('command', immutable),
        );
        if (_closed ||
            ack.generation != generation ||
            latest?.generation != generation) {
          throw StateError('named_groups_retired');
        }
        if (field &&
            (ack.name != args['text'] ||
                ack.start != args['start'] ||
                ack.end != args['end'])) {
          throw StateError('named_groups_unacknowledged');
        }
        if (field || ['cancel', 'back'].contains(op)) _rejected = false;
        accept(ack.raw);
      } catch (_) {
        if (field && !_closed && latest?.generation == generation) {
          _rejected = true;
        }
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
