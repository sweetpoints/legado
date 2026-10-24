import 'dart:async';

import 'package:flutter/services.dart';

const highlightFields = ['name', 'group', 'pattern', 'scope'];

class HighlightEditorSnapshot {
  HighlightEditorSnapshot(Object? value) {
    if (value is! Map) throw const FormatException('highlight_editor_snapshot');
    raw = Map<String, Object?>.from(value);
    generation = value['generation'] as int;
    revision = value['revision'] as int;
    fields = Map<String, Object?>.from(value['fields'] as Map);
    for (final key in highlightFields) {
      final field = fields[key] as Map;
      final text = field['text'] as String;
      final start = field['start'] as int, end = field['end'] as int;
      if (start < 0 || start > text.length || end < 0 || end > text.length) {
        throw const FormatException('highlight_selection');
      }
    }
    final focus = value['focus'];
    if (focus != null && !highlightFields.contains(focus)) {
      throw const FormatException('highlight_focus');
    }
    final preview = value['preview'] as Map;
    if (preview['png'] is! Uint8List ||
        (preview['png'] as Uint8List).length > 256 * 1024) {
      throw const FormatException('highlight_preview');
    }
  }
  late final Map<String, Object?> raw, fields;
  late final int generation, revision;
  String text(String key) => (fields[key] as Map)['text'] as String;
  int start(String key) => (fields[key] as Map)['start'] as int;
  int end(String key) => (fields[key] as Map)['end'] as int;
  bool flag(String key) => raw[key] == true;
  String label(String key) => (raw['labels'] as Map)[key] as String;
}

class HighlightRuleEditorBridge {
  HighlightRuleEditorBridge({
    this.channel = const MethodChannel('legado/highlight_rule_editor'),
    this.events = const EventChannel('legado/highlight_rule_editor/state'),
  });
  final MethodChannel channel;
  final EventChannel events;
  final snapshots = StreamController<HighlightEditorSnapshot>.broadcast();
  StreamSubscription<Object?>? _events;
  HighlightEditorSnapshot? latest;
  final _retired = <int>{};
  final _rejected = <String>{};
  Future<void> _queue = Future.value();
  bool _closed = false;
  bool get hasRejectedField => _rejected.isNotEmpty;
  void accept(Object? value) {
    if (_closed) return;
    final next = HighlightEditorSnapshot(value);
    if (_retired.contains(next.generation) ||
        latest?.generation == next.generation &&
            next.revision < latest!.revision) {
      return;
    }
    if (latest != null && latest!.generation != next.generation) {
      _retired.add(latest!.generation);
      _rejected.clear();
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
        throw StateError('highlight_editor_retired');
      }
      if (field == null && op != 'cancel' && _rejected.isNotEmpty) {
        throw StateError('highlight_field_unacknowledged');
      }
      try {
        final ack = HighlightEditorSnapshot(
          await channel.invokeMethod<Object?>('command', immutable),
        );
        if (_closed ||
            ack.generation != generation ||
            latest?.generation != generation) {
          throw StateError('highlight_editor_retired');
        }
        if (field != null) {
          if (ack.text(field) != args['text'] ||
              ack.start(field) != args['start'] ||
              ack.end(field) != args['end']) {
            throw StateError('highlight_field_unacknowledged');
          }
          _rejected.remove(field);
        }
        accept(ack.raw);
      } catch (_) {
        if (field != null && latest?.generation == generation) {
          _rejected.add(field);
        }
        rethrow;
      }
    });
    _queue = next.catchError((Object _) {});
    return next;
  }

  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    await _events?.cancel();
    await snapshots.close();
  }
}
