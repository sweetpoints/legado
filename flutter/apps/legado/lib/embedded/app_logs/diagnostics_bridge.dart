import 'dart:async';

import 'package:flutter/services.dart';

class DiagnosticsSnapshot {
  DiagnosticsSnapshot(Map<String, Object?> value)
    : raw = Map.unmodifiable(value) {
    if (raw['schemaVersion'] != 1 ||
        binding.isEmpty ||
        revision < 1 ||
        number('sequence') < 0 ||
        rows.length > 100 ||
        rows.map((r) => r['id']).toSet().length != rows.length ||
        rows.any(
          (r) =>
              r['id'] is! String ||
              (r['text'] as String).length > 512 ||
              (r['time'] as String).length > 80,
        ) ||
        number('scrollIndex') < 0 ||
        number('scrollIndex') > 100000 ||
        offset < 0 ||
        !offset.isFinite ||
        offset > 10000000) {
      throw const FormatException('diagnostics_snapshot');
    }
  }
  final Map<String, Object?> raw;
  String get binding => raw['binding']! as String;
  int get revision => raw['revision']! as int;
  bool flag(String key) => raw[key] == true;
  int number(String key) => raw[key]! as int;
  double get offset => (raw['scrollOffset']! as num).toDouble();
  List<Map<String, Object?>> get rows => (raw['rows']! as List)
      .map((r) => Map<String, Object?>.from(r as Map))
      .toList(growable: false);
}

class DiagnosticsBridge {
  DiagnosticsBridge({this.crash = false});
  final bool crash;
  String get path => crash ? 'crash_logs' : 'app_logs';
  MethodChannel get channel => MethodChannel('legado/$path');
  final _states = StreamController<DiagnosticsSnapshot>.broadcast();
  Stream<DiagnosticsSnapshot> get states => _states.stream;
  DiagnosticsSnapshot? latest;
  StreamSubscription<Object?>? _subscription;
  final _retired = <String>{};
  Future<void> _queue = Future.value();
  int _sequence = 0;
  bool _closed = false;
  void accept(Object? raw) {
    final next = DiagnosticsSnapshot(Map<String, Object?>.from(raw! as Map));
    final old = latest;
    if (_closed ||
        _retired.contains(next.binding) ||
        (old?.binding == next.binding && old!.revision >= next.revision)) {
      return;
    }
    if (old != null && old.binding != next.binding) {
      _retired.add(old.binding);
      _sequence = 0;
    }
    _sequence = next.number('sequence');
    latest = next;
    _states.add(next);
  }

  Future<void> start() async {
    _subscription = EventChannel('legado/$path/state')
        .receiveBroadcastStream()
        .listen((raw) {
          try {
            accept(raw);
          } catch (e, s) {
            _states.addError(e, s);
          }
        }, onError: _states.addError);
    await refresh();
  }

  Future<void> refresh() async =>
      accept(await channel.invokeMethod<Object?>('snapshot'));
  Future<void> command(String op, [Map<String, Object?> args = const {}]) {
    final owner = latest?.binding;
    final frozen = Map<String, Object?>.unmodifiable(args);
    final work = _queue.then((_) async {
      if (_closed || owner == null || latest?.binding != owner) {
        throw StateError('retired_diagnostics');
      }
      try {
        accept(
          await channel.invokeMethod<Object?>('command', {
            'binding': owner,
            'sequence': ++_sequence,
            'op': op,
            ...frozen,
          }),
        );
      } catch (_) {
        // A paused host may reject before consuming the sequence. Resnapshot its
        // native receipt, never replay the rejected action or poison later input.
        try {
          await refresh();
        } catch (_) {
          /* A retired view stays rejected. */
        }
        rethrow;
      }
    });
    _queue = work.catchError((Object _) {});
    return work;
  }

  Future<void> close() async {
    _closed = true;
    await _subscription?.cancel();
    await _states.close();
  }
}
