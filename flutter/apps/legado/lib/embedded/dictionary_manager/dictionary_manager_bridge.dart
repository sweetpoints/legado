import 'dart:async';

import 'package:flutter/services.dart';

class DictionaryManagerSnapshot {
  DictionaryManagerSnapshot(Map<String, Object?> data)
    : binding = data['binding']! as String,
      revision = data['revision']! as int,
      loading = data['loading']! as bool,
      busy = data['busy']! as bool,
      error = data['error']! as bool,
      rules = (data['rules']! as List)
          .map((e) {
            final row = Map<String, Object?>.from(e as Map);
            return (
              name: row['name']! as String,
              enabled: row['enabled']! as bool,
            );
          })
          .toList(growable: false),
      selected = (data['selected']! as List).cast<String>().toSet(),
      deleteName = data['deleteName'] as String?,
      online = data['online']! as bool,
      input = data['onlineInput']! as String,
      history = (data['history']! as List).cast<String>(),
      exportUrl = data['exportUrl'] as String?,
      summary = data['exportSummary']! as String,
      canEncode = data['canEncode']! as bool,
      passphrase = data['passphrase'] as String? {
    if (data['schemaVersion'] != 1 ||
        binding.isEmpty ||
        revision < 1 ||
        rules.map((e) => e.name).toSet().length != rules.length ||
        !selected.every((name) => rules.any((row) => row.name == name))) {
      throw const FormatException('dictionary_manager_snapshot');
    }
  }
  final String binding;
  final int revision;
  final bool loading, busy, error, online, canEncode;
  final List<({String name, bool enabled})> rules;
  final Set<String> selected;
  final String? deleteName, exportUrl, passphrase;
  final String input, summary;
  final List<String> history;
}

class DictionaryManagerBridge {
  static const channel = MethodChannel('legado/dictionary_manager');
  static const events = EventChannel('legado/dictionary_manager/state');
  final _states = StreamController<DictionaryManagerSnapshot>.broadcast();
  final _retired = <String>{};
  DictionaryManagerSnapshot? latest;
  StreamSubscription<Object?>? _subscription;
  Future<void> _queue = Future.value();
  int _sequence = 0;
  bool _closed = false, _rejected = false;
  Stream<DictionaryManagerSnapshot> get states => _states.stream;
  DictionaryManagerSnapshot _decode(Object? raw) =>
      DictionaryManagerSnapshot(Map<String, Object?>.from(raw! as Map));
  void _accept(DictionaryManagerSnapshot state) {
    final old = latest;
    if (_closed || _retired.contains(state.binding)) return;
    if (old != null &&
        old.binding == state.binding &&
        old.revision >= state.revision) {
      return;
    }
    if (old != null && old.binding != state.binding) {
      _retired.add(old.binding);
      _rejected = false;
    }
    latest = state;
    _states.add(state);
  }

  Future<void> refresh() async {
    _accept(_decode(await channel.invokeMethod<Object?>('snapshot')));
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

  Future<void> command(String op, [Map<String, Object?> args = const {}]) {
    final binding = latest?.binding;
    final captured = Map<String, Object?>.unmodifiable(args);
    final task = _queue.then((_) async {
      if (_closed || binding == null || latest?.binding != binding) {
        throw StateError('dictionary_view_changed');
      }
      if (_rejected && op != 'input' && op != 'back' && op != 'online') {
        throw StateError('dictionary_input_not_acknowledged');
      }
      try {
        final ack = _decode(
          await channel.invokeMethod<Object?>('command', {
            ...captured,
            'op': op,
            'binding': binding,
            'sequence': ++_sequence,
          }),
        );
        if (_closed || latest?.binding != binding || ack.binding != binding) {
          throw StateError('dictionary_view_changed');
        }
        if (op == 'input') {
          if (ack.input != captured['value']) {
            throw StateError('dictionary_input_not_acknowledged');
          }
          _rejected = false;
        }
        if ((op == 'online' && captured['value'] == false) ||
            (op == 'back' && !ack.online)) {
          _rejected = false;
        }
        _accept(ack);
      } catch (_) {
        if (op == 'input') _rejected = true;
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
