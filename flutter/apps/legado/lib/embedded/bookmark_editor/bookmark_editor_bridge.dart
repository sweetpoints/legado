import 'dart:async';

import 'package:flutter/services.dart';

const bookmarkFields = ['text', 'content'];

class BookmarkEditorSnapshot {
  BookmarkEditorSnapshot(Object? value) {
    if (value is! Map) {
      throw const FormatException('bookmark_editor_snapshot');
    }
    raw = Map<String, Object?>.from(value);
    generation = value['generation'] as int;
    revision = value['revision'] as int;
    loading = value['loading'] as bool;
    saving = value['saving'] as bool;
    finished = value['finished'] as bool;
    loadFailed = value['loadFailed'] as bool;
    canEdit = value['canEdit'] as bool;
    error = value['error'] as String?;
    focus = value['focus'] as String?;
    if (focus != null && !bookmarkFields.contains(focus)) {
      throw const FormatException('bookmark_focus');
    }
    for (final key in bookmarkFields) {
      final text = value[key] as String;
      final start = value['${key}Start'] as int;
      final end = value['${key}End'] as int;
      if (start < 0 || start > text.length || end < 0 || end > text.length) {
        throw const FormatException('bookmark_selection');
      }
    }
  }
  late final Map<String, Object?> raw;
  late final int generation, revision;
  late final bool loading, saving, finished, loadFailed, canEdit;
  late final String? focus, error;
  String text(String key) => raw[key] as String;
  int start(String key) => raw['${key}Start'] as int;
  int end(String key) => raw['${key}End'] as int;
}

/// Commands serialize after field ACKs; retired engines cannot apply queued edits.
class BookmarkEditorBridge {
  BookmarkEditorBridge({
    this.channel = const MethodChannel('legado/bookmark_editor'),
    this.events = const EventChannel('legado/bookmark_editor/state'),
  });
  final MethodChannel channel;
  final EventChannel events;
  final snapshots = StreamController<BookmarkEditorSnapshot>.broadcast();
  StreamSubscription<Object?>? _events;
  BookmarkEditorSnapshot? latest;
  final _retired = <int>{};
  final _rejected = <String>{};
  Future<void> _queue = Future.value();
  bool _closed = false;
  bool get hasRejectedField => _rejected.isNotEmpty;
  void accept(Object? value) {
    if (_closed) return;
    final next = BookmarkEditorSnapshot(value);
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
    final next = (op == 'cancel' ? Future<void>.value() : _queue).then((
      _,
    ) async {
      if (_closed || generation == null || latest?.generation != generation) {
        throw StateError('bookmark_editor_retired');
      }
      if (field == null && op != 'cancel' && _rejected.isNotEmpty) {
        throw StateError('bookmark_field_unacknowledged');
      }
      try {
        final ack = BookmarkEditorSnapshot(
          await channel.invokeMethod<Object?>('command', immutable),
        );
        if (_closed ||
            ack.generation != generation ||
            latest?.generation != generation) {
          throw StateError('bookmark_editor_retired');
        }
        if (field != null) {
          if (ack.text(field) != args['text'] ||
              ack.start(field) != args['start'] ||
              ack.end(field) != args['end']) {
            throw StateError('bookmark_field_unacknowledged');
          }
          _rejected.remove(field);
        }
        accept(ack.raw);
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
