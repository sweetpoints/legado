import 'dart:async';

import 'package:flutter/services.dart';

class HighlightSnapshot {
  HighlightSnapshot(Map<String, Object?> value)
    : raw = Map.unmodifiable(value) {
    if (raw['schemaVersion'] != 1 || binding.isEmpty || revision < 1) {
      throw const FormatException('highlight_snapshot');
    }
    if (raw.containsKey('name')) {
      final text = raw['name']! as String;
      for (final key in ['nameStart', 'nameEnd']) {
        final offset = raw[key]! as int;
        if (offset < 0 || offset > text.length) {
          throw const FormatException('highlight_selection');
        }
      }
    }
    if (raw.containsKey('rows')) {
      final index = raw['scrollIndex'];
      final offset = raw['scrollOffset'];
      if (index is! int ||
          index < 0 ||
          offset is! num ||
          !offset.isFinite ||
          offset < 0 ||
          offset >= 80) {
        throw const FormatException('highlight_scroll');
      }
    }
    if (raw.containsKey('rows') &&
        rows.map((r) => r['uuid']).toSet().length != rows.length) {
      throw const FormatException('highlight_identity');
    }
  }
  final Map<String, Object?> raw;
  String get binding => raw['binding']! as String;
  int get revision => raw['revision']! as int;
  bool flag(String key) => raw[key] == true;
  String text(String key) => raw[key] as String? ?? '';
  int number(String key) => raw[key]! as int;
  String label(String key) => (raw['labels']! as Map)[key]! as String;
  List<String> strings(String key) =>
      (raw[key] as List? ?? const []).cast<String>();
  List<Map<String, Object?>> get rows => (raw['rows'] as List? ?? const [])
      .map((r) => Map<String, Object?>.from(r as Map))
      .toList(growable: false);
}

class HighlightBridge {
  HighlightBridge({this.groups = false});
  final bool groups;
  String get path => groups ? 'highlight_groups' : 'highlight_manager';
  MethodChannel get channel => MethodChannel('legado/$path');
  EventChannel get events => EventChannel('legado/$path/state');
  final _states = StreamController<HighlightSnapshot>.broadcast();
  Stream<HighlightSnapshot> get states => _states.stream;
  final _retired = <String>{};
  HighlightSnapshot? latest;
  StreamSubscription<Object?>? _subscription;
  Future<void> _queue = Future.value();
  int _sequence = 0;
  bool _closed = false, _rejected = false;
  bool get rejectedName => _rejected;
  HighlightSnapshot _decode(Object? value) =>
      HighlightSnapshot(Map<String, Object?>.from(value! as Map));
  void _accept(HighlightSnapshot next) {
    final old = latest;
    if (_closed || _retired.contains(next.binding)) return;
    if (old != null &&
        old.binding == next.binding &&
        old.revision >= next.revision) {
      return;
    }
    if (old != null && old.binding != next.binding) {
      _retired.add(old.binding);
      _rejected = false;
    }
    latest = next;
    _states.add(next);
  }

  Future<void> start() async {
    _subscription = events.receiveBroadcastStream().listen((raw) {
      try {
        _accept(_decode(raw));
      } catch (error, stack) {
        _states.addError(error, stack);
      }
    }, onError: _states.addError);
    _accept(_decode(await channel.invokeMethod<Object?>('snapshot')));
  }

  Future<void> refresh() async =>
      _accept(_decode(await channel.invokeMethod<Object?>('snapshot')));
  Future<void> command(String op, [Map<String, Object?> args = const {}]) {
    final owner = latest?.binding;
    final frozen = Map<String, Object?>.unmodifiable(args);
    final task = _queue.then((_) async {
      if (_closed || owner == null || latest?.binding != owner) {
        throw StateError('highlight_binding_changed');
      }
      if (_rejected && !['name', 'cancel', 'back'].contains(op)) {
        throw StateError('highlight_name_unacknowledged');
      }
      try {
        final ack = _decode(
          await channel.invokeMethod<Object?>('command', {
            ...frozen,
            'op': op,
            'binding': owner,
            'sequence': ++_sequence,
          }),
        );
        if (_closed || latest?.binding != owner || ack.binding != owner) {
          throw StateError('highlight_binding_changed');
        }
        if (op == 'scroll') {
          final maximum = (ack.rows.length - 1).clamp(0, ack.rows.length);
          if (ack.number('scrollIndex') !=
                  (frozen['index']! as int).clamp(0, maximum) ||
              ack.raw['scrollOffset'] != frozen['offset']) {
            throw StateError('highlight_scroll_unacknowledged');
          }
        }
        if (op == 'name') {
          if (ack.text('name') != frozen['text'] ||
              ack.number('nameStart') != frozen['start'] ||
              ack.number('nameEnd') != frozen['end']) {
            throw StateError('highlight_name_unacknowledged');
          }
          _rejected = false;
        }
        if (['cancel', 'back'].contains(op) && ack.text('stage') == 'None') {
          _rejected = false;
        }
        _accept(ack);
      } catch (_) {
        if (!_closed && latest?.binding == owner && op == 'name') {
          _rejected = true;
        }
        rethrow;
      }
    });
    _queue = task.catchError((Object _) {});
    return task;
  }

  Future<void> close() async {
    _closed = true;
    await _subscription?.cancel();
    await _states.close();
  }
}
