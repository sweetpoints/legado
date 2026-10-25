import 'dart:async';

import 'package:flutter/services.dart';

class AllBookmarksRow {
  AllBookmarksRow(Map value)
    : id = value['id'] as String,
      bookName = value['bookName'] as String,
      bookAuthor = value['bookAuthor'] as String,
      chapter = value['chapter'] as String,
      original = value['original'] as String,
      content = value['content'] as String;
  final String id, bookName, bookAuthor, chapter, original, content;
}

class AllBookmarksSnapshot {
  AllBookmarksSnapshot(Object? value) {
    if (value is! Map) throw const FormatException('all_bookmarks_snapshot');
    raw = Map<String, Object?>.from(value);
    generation = value['generation'] as int;
    revision = value['revision'] as int;
    loaded = value['loaded'] as bool;
    exporting = value['exporting'] as bool;
    pending = value['pending'] as bool;
    error = value['error'] as String?;
    index = value['scroll'] as int;
    offset = value['offset'] as int;
    rows = (value['rows'] as List)
        .map((v) => AllBookmarksRow(v as Map))
        .toList(growable: false);
    if (rows.map((r) => r.id).toSet().length != rows.length ||
        index < 0 ||
        offset < 0) {
      throw const FormatException('all_bookmarks_identity');
    }
  }
  late final Map<String, Object?> raw;
  late final int generation, revision, index, offset;
  late final bool loaded, exporting, pending;
  late final String? error;
  late final List<AllBookmarksRow> rows;
}

class AllBookmarksBridge {
  AllBookmarksBridge({
    this.channel = const MethodChannel('legado/all_bookmarks'),
    this.events = const EventChannel('legado/all_bookmarks/state'),
  });
  final MethodChannel channel;
  final EventChannel events;
  final snapshots = StreamController<AllBookmarksSnapshot>.broadcast();
  AllBookmarksSnapshot? latest;
  StreamSubscription<Object?>? _events;
  final _retired = <int>{};
  bool _closed = false;
  Future<void> _queue = Future.value();
  void accept(Object? value) {
    if (_closed) return;
    final next = AllBookmarksSnapshot(value);
    if (_retired.contains(next.generation) ||
        (latest?.generation == next.generation &&
            next.revision <= latest!.revision)) {
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
      (v) {
        try {
          accept(v);
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
    final values = Map<String, Object?>.unmodifiable({
      ...args,
      'op': op,
      'generation': generation,
    });
    final next = _queue.then((_) async {
      if (_closed || generation == null || latest?.generation != generation) {
        throw StateError('all_bookmarks_retired');
      }
      final reply = AllBookmarksSnapshot(
        await channel.invokeMethod<Object?>('command', values),
      );
      if (_closed ||
          reply.generation != generation ||
          latest?.generation != generation) {
        throw StateError('all_bookmarks_retired');
      }
      accept(reply.raw);
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
