import 'dart:async';

import 'package:flutter/services.dart';

class HighlightColorSnapshot {
  HighlightColorSnapshot(Object? value) {
    if (value is! Map) throw const FormatException('highlight_color_snapshot');
    raw = Map<String, Object?>.from(value);
    generation = value['generation'] as int;
    revision = value['revision'] as int;
    sequence = value['sequence'] as int;
    nonce = value['nonce'] as String;
    color = value['value'] as int;
    hex = value['hex'] as String;
    invalid = value['invalid'] as bool;
    finished = value['finished'] as bool;
    withAlpha = value['withAlpha'] as bool;
    presets = (value['presets'] as List).cast<int>();
    if (nonce.isEmpty ||
        color < -0x80000000 ||
        color > 0xffffffff ||
        presets.isEmpty) {
      throw const FormatException('highlight_color_value');
    }
  }
  late final Map<String, Object?> raw;
  late final String nonce, hex;
  late final int generation, revision, sequence, color;
  late final bool invalid, finished, withAlpha;
  late final List<int> presets;
  String label(String key) => (raw['labels'] as Map)[key] as String;
}

/// Separate transaction/channel: opening a color window never retires the editor binding.
class HighlightColorPickerBridge {
  HighlightColorPickerBridge({
    this.channel = const MethodChannel('legado/highlight_rule_color'),
    this.events = const EventChannel('legado/highlight_rule_color/state'),
  });
  final MethodChannel channel;
  final EventChannel events;
  final snapshots = StreamController<HighlightColorSnapshot>.broadcast();
  StreamSubscription<Object?>? _events;
  HighlightColorSnapshot? latest;
  Future<void> _queue = Future.value();
  final _retired = <int>{};
  int _sequence = 0;
  bool _closed = false, _rejected = false;
  bool get hasRejectedInput => _rejected;
  void accept(Object? value) {
    if (_closed) return;
    final next = HighlightColorSnapshot(value);
    if (latest != null && next.nonce != latest!.nonce) {
      throw StateError('highlight_color_nonce');
    }
    if (_retired.contains(next.generation) ||
        latest?.generation == next.generation &&
            next.revision < latest!.revision) {
      return;
    }
    if (latest == null || latest!.generation != next.generation) {
      if (latest != null) _retired.add(latest!.generation);
      _sequence = next.sequence;
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
    final generation = latest?.generation, nonce = latest?.nonce;
    final sequence = ++_sequence;
    final immutable = Map<String, Object?>.unmodifiable({
      ...args,
      'op': op,
      'generation': generation,
      'nonce': nonce,
      'sequence': sequence,
    });
    final input = ['hex', 'channel', 'preset'].contains(op);
    final next = _queue.then((_) async {
      if (_closed ||
          generation == null ||
          latest?.generation != generation ||
          latest?.nonce != nonce) {
        throw StateError('highlight_color_retired');
      }
      if (!input && op != 'cancel' && _rejected) {
        throw StateError('highlight_color_unacknowledged');
      }
      try {
        final ack = HighlightColorSnapshot(
          await channel.invokeMethod<Object?>('command', immutable),
        );
        if (_closed ||
            ack.generation != generation ||
            ack.nonce != nonce ||
            ack.sequence != sequence ||
            latest?.generation != generation) {
          throw StateError('highlight_color_unacknowledged');
        }
        if (op == 'hex' && ack.hex != args['text']) {
          throw StateError('highlight_color_unacknowledged');
        }
        if (input) _rejected = false;
        accept(ack.raw);
      } catch (_) {
        if (input && latest?.generation == generation) _rejected = true;
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
