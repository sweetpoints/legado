import 'dart:async';

import 'package:flutter/services.dart';

const replaceFields = [
  'Name',
  'Group',
  'Pattern',
  'Replacement',
  'Scope',
  'ExcludeScope',
  'Timeout',
  'Sample',
];

class ReplaceText {
  ReplaceText(Map value)
    : text = value['text'] as String,
      start = value['start'] as int,
      end = value['end'] as int,
      external = value['external'] as bool,
      label = value['label'] as String {
    if (start < 0 || end < 0 || start > text.length || end > text.length) {
      throw const FormatException('replace_selection');
    }
  }
  final String text, label;
  final int start, end;
  final bool external;
}

class ReplaceSnapshot {
  ReplaceSnapshot(Object? value) {
    if (value is! Map) throw const FormatException('replace_snapshot');
    raw = Map<String, Object?>.from(value);
    generation = raw['generation'] as int;
    revision = raw['revision'] as int;
    fields = {
      for (final key in replaceFields)
        key: ReplaceText((raw['fields'] as Map)[key] as Map),
    };
    focus = raw['focus'] as String?;
    if (focus != null && !replaceFields.contains(focus)) {
      throw const FormatException('replace_focus');
    }
    for (final key in [
      'loaded',
      'busy',
      'editable',
      'finished',
      'exit',
      'editorOpen',
      'editorReturning',
      'regex',
      'title',
      'source',
      'content',
      'previewFailed',
    ]) {
      raw[key] as bool;
    }
    raw['preview'] as String;
    raw['scroll'] as int;
    raw['rows'] as int;
    keys = (raw['keys'] as List)
        .map(
          (e) =>
              (key: (e as Map)['key'] as String, value: e['value'] as String),
        )
        .toList(growable: false);
  }
  late final Map<String, Object?> raw;
  late final Map<String, ReplaceText> fields;
  late final int generation, revision;
  late final String? focus;
  late final List<({String key, String value})> keys;
  bool flag(String key) => raw[key] as bool;
}

class ReplaceEditorBridge {
  ReplaceEditorBridge({
    this.channel = const MethodChannel('legado/replace_editor'),
    this.events = const EventChannel('legado/replace_editor/state'),
  });
  final MethodChannel channel;
  final EventChannel events;
  final snapshots = StreamController<ReplaceSnapshot>.broadcast();
  StreamSubscription<Object?>? _events;
  ReplaceSnapshot? latest;
  final _retired = <int>{};
  final _rejected = <String>{};
  Future<void> _queue = Future.value();
  bool _closed = false;
  bool get hasRejectedField => _rejected.isNotEmpty;
  void accept(Object? value) {
    if (_closed) return;
    final next = ReplaceSnapshot(value);
    if (_retired.contains(next.generation) ||
        (latest?.generation == next.generation &&
            next.revision < latest!.revision)) {
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
        throw StateError('replace_retired');
      }
      if (field == null &&
          !['close', 'keep', 'discard'].contains(op) &&
          _rejected.isNotEmpty) {
        throw StateError('replace_unacknowledged');
      }
      try {
        final value = await channel.invokeMethod<Object?>('command', immutable);
        final ack = ReplaceSnapshot(value);
        if (_closed ||
            ack.generation != generation ||
            latest?.generation != generation) {
          throw StateError('replace_retired');
        }
        if (field != null) {
          final actual = ack.fields[field]!;
          var expected = args['text'] as String;
          if (field == 'Sample') {
            expected = expected.substring(0, expected.length.clamp(0, 300));
            if (expected.isNotEmpty &&
                expected.codeUnitAt(expected.length - 1) >= 0xd800 &&
                expected.codeUnitAt(expected.length - 1) <= 0xdbff) {
              expected = expected.substring(0, expected.length - 1);
            }
          }
          final expectedStart = (args['start'] as int).clamp(
            0,
            expected.length,
          );
          final expectedEnd = (args['end'] as int).clamp(0, expected.length);
          if (actual.text != expected ||
              actual.start != expectedStart ||
              actual.end != expectedEnd) {
            throw StateError('replace_unacknowledged');
          }
          _rejected.remove(field);
        }
        accept(value);
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
