import 'dart:async';

import 'package:flutter/services.dart';

const dictionaryFields = ['Name', 'UrlRule', 'ShowRule'];

class DictionaryEditorSnapshot {
  DictionaryEditorSnapshot(Object? value) {
    if (value is! Map) {
      throw const FormatException('dictionary_editor_snapshot');
    }
    raw = Map<String, Object?>.from(value);
    generation = value['generation'] as int;
    revision = value['revision'] as int;
    loading = value['loading'] as bool;
    saving = value['saving'] as bool;
    finished = value['finished'] as bool;
    confirmExit = value['confirmExit'] as bool;
    loadFailed = value['loadFailed'] as bool;
    canEdit = value['canEdit'] as bool;
    error = value['error'] as String?;
    focus = value['focus'] as String?;
    if (focus != null && !dictionaryFields.contains(focus)) {
      throw const FormatException('dictionary_focus');
    }
    for (final key in dictionaryFields) {
      final text = value[key] as String;
      final start = value['${key}Start'] as int;
      final end = value['${key}End'] as int;
      if (start < 0 || start > text.length || end < 0 || end > text.length) {
        throw const FormatException('dictionary_selection');
      }
    }
  }
  late final Map<String, Object?> raw;
  late final int generation, revision;
  late final bool loading, saving, finished, confirmExit, loadFailed, canEdit;
  late final String? focus, error;
  String text(String key) => raw[key] as String;
  int start(String key) => raw['${key}Start'] as int;
  int end(String key) => raw['${key}End'] as int;
}

/// Commands serialize after field ACKs; retired engines cannot apply queued edits.
class DictionaryRuleEditorBridge {
  DictionaryRuleEditorBridge({
    this.channel = const MethodChannel('legado/dictionary_rule_editor'),
    this.events = const EventChannel('legado/dictionary_rule_editor/state'),
  });
  final MethodChannel channel;
  final EventChannel events;
  final snapshots = StreamController<DictionaryEditorSnapshot>.broadcast();
  StreamSubscription<Object?>? _events;
  DictionaryEditorSnapshot? latest;
  final _retired = <int>{};
  final _rejected = <String>{};
  Future<void> _queue = Future.value();
  bool _closed = false;
  bool get hasRejectedField => _rejected.isNotEmpty;
  void accept(Object? value) {
    if (_closed) return;
    final next = DictionaryEditorSnapshot(value);
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
        throw StateError('dictionary_editor_retired');
      }
      if (field == null &&
          !['cancel', 'keep', 'discard'].contains(op) &&
          _rejected.isNotEmpty) {
        throw StateError('dictionary_field_unacknowledged');
      }
      try {
        final ack = DictionaryEditorSnapshot(
          await channel.invokeMethod<Object?>('command', immutable),
        );
        if (_closed ||
            ack.generation != generation ||
            latest?.generation != generation) {
          throw StateError('dictionary_editor_retired');
        }
        if (field != null) {
          if (ack.text(field) != args['text'] ||
              ack.start(field) != args['start'] ||
              ack.end(field) != args['end']) {
            throw StateError('dictionary_field_unacknowledged');
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
