import 'dart:async';

import 'package:flutter/services.dart';

class TxtTocManagerSnapshot {
  TxtTocManagerSnapshot(Map<String, Object?> value)
    : raw = Map.unmodifiable(value) {
    if (raw['schemaVersion'] != 1 ||
        binding.isEmpty ||
        revision < 1 ||
        rows.map((r) => r.id).toSet().length != rows.length) {
      throw const FormatException('txt_toc_manager_snapshot');
    }
    for (final field in ['query', 'onlineInput']) {
      final text = raw[field]! as String;
      final start = raw['${field}Start']! as int;
      final end = raw['${field}End']! as int;
      if (start < 0 || end < 0 || start > text.length || end > text.length) {
        throw const FormatException('txt_toc_manager_selection');
      }
    }
  }
  final Map<String, Object?> raw;
  String get binding => raw['binding']! as String;
  int get revision => raw['revision']! as int;
  bool flag(String key) => raw[key] == true;
  String text(String key) => raw[key] as String? ?? '';
  int number(String key) => raw[key]! as int;
  String label(String key) => (raw['labels']! as Map)[key]! as String;
  Set<int> get selected => (raw['selected']! as List).cast<int>().toSet();
  List<int> get visibleSelection =>
      rows.where((r) => selected.contains(r.id)).map((r) => r.id).toList();
  List<String> get history => (raw['history']! as List).cast<String>();
  String? get dialog => (raw['deleteIds']! as List).isNotEmpty
      ? 'delete'
      : flag('online')
      ? 'online'
      : raw['exportUrl'] != null
      ? 'export'
      : raw['passphrase'] != null
      ? 'passphrase'
      : null;
  List<({int id, String name, String? example, bool enabled})> get rows =>
      (raw['rows']! as List)
          .map((value) {
            final row = value as Map;
            return (
              id: row['id']! as int,
              name: row['name']! as String,
              example: row['example'] as String?,
              enabled: row['enabled']! as bool,
            );
          })
          .toList(growable: false);
}

class TxtTocManagerBridge {
  static const channel = MethodChannel('legado/txt_toc_manager');
  static const events = EventChannel('legado/txt_toc_manager/state');
  final _states = StreamController<TxtTocManagerSnapshot>.broadcast();
  final _retired = <String>{};
  TxtTocManagerSnapshot? latest;
  StreamSubscription<Object?>? _subscription;
  Future<void> _queue = Future.value();
  int _sequence = 0;
  bool _closed = false;
  String? _rejected;
  Stream<TxtTocManagerSnapshot> get states => _states.stream;
  String? get rejectedField => _rejected;
  TxtTocManagerSnapshot _decode(Object? raw) =>
      TxtTocManagerSnapshot(Map<String, Object?>.from(raw! as Map));
  void _accept(TxtTocManagerSnapshot next) {
    final old = latest;
    if (_closed || _retired.contains(next.binding)) return;
    if (old != null &&
        old.binding == next.binding &&
        old.revision >= next.revision) {
      return;
    }
    if (old != null && old.binding != next.binding) {
      _retired.add(old.binding);
      _rejected = null;
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
  Future<void> command(String op, [Map<String, Object?> args = const {}]) =>
      _enqueue(op, args, false);

  /// Hardware Back resolves modal/root ownership only after preceding ACKs settle.
  Future<void> navigateBack() => _enqueue('back', const {}, true);

  Future<void> _enqueue(
    String requested,
    Map<String, Object?> args,
    bool modalAware,
  ) {
    final binding = latest?.binding;
    final values = Map<String, Object?>.unmodifiable(args);
    final field = (requested == 'query' || requested == 'onlineInput')
        ? requested
        : null;
    final task = _queue.then((_) async {
      if (_closed || binding == null || latest?.binding != binding) {
        throw StateError('txt_toc_manager_view_changed');
      }
      final op = modalAware && latest?.dialog != null
          ? 'cancelModal'
          : requested;
      // Decide after preceding field commands settle. The user's modal cancellation
      // explicitly discards a rejected background query, with its own native ACK.
      if (modalAware && _rejected == 'query') {
        final discarded = _decode(
          await channel.invokeMethod<Object?>('command', {
            'op': 'cancelQuery',
            'binding': binding,
            'sequence': ++_sequence,
          }),
        );
        if (_closed ||
            latest?.binding != binding ||
            discarded.binding != binding) {
          throw StateError('txt_toc_manager_view_changed');
        }
        _rejected = null;
        _accept(discarded);
      }
      final cancel =
          ['cancelGesture', 'scroll'].contains(op) ||
          (op == 'cancelQuery' &&
              (_rejected == null || _rejected == 'query')) ||
          (op == 'cancelModal' &&
              (_rejected == null || _rejected == 'onlineInput'));
      if (_rejected != null && _rejected != field && !cancel) {
        throw StateError('txt_toc_manager_field_not_acknowledged');
      }
      try {
        final ack = _decode(
          await channel.invokeMethod<Object?>('command', {
            ...values,
            'op': op,
            'binding': binding,
            'sequence': ++_sequence,
          }),
        );
        if (_closed || latest?.binding != binding || ack.binding != binding) {
          throw StateError('txt_toc_manager_view_changed');
        }
        if (field != null) {
          if (ack.text(field) != values['text'] ||
              ack.number('${field}Start') != values['start'] ||
              ack.number('${field}End') != values['end']) {
            throw StateError('txt_toc_manager_field_not_acknowledged');
          }
          _rejected = null;
        }
        if ((op == 'cancelModal' && ack.dialog == null) ||
            op == 'cancelQuery') {
          _rejected = null;
        }
        _accept(ack);
      } catch (_) {
        if (!_closed &&
            latest?.binding == binding &&
            field != null &&
            (_rejected == null || _rejected == field)) {
          _rejected = field;
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
