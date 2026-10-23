import 'dart:async';

import 'package:flutter/services.dart';

/// No database is opened in this UI. Native snapshots are the only authority.
class RuleSubscriptionBridge {
  RuleSubscriptionBridge({
    MethodChannel? methods,
    MethodChannel? navigation,
    Stream<Object?>? events,
  }) : _methods = methods ?? const MethodChannel('legado/rule_subscriptions'),
       _navigation = navigation ?? const MethodChannel('legado/embedded_ui') {
    _subscription =
        (events ??
                const EventChannel('legado/rule_subscriptions/state')
                    .receiveBroadcastStream())
            .listen(_accept, onError: _states.addError);
  }
  final MethodChannel _methods;
  final MethodChannel _navigation;
  final _states = StreamController<Map<String, dynamic>>.broadcast();
  late final StreamSubscription<Object?> _subscription;
  Future<void> _tail = Future.value();
  Map<String, dynamic>? latest;
  final Set<String> _rejected = {};
  bool get hasRejectedFields => _rejected.isNotEmpty;
  bool _closed = false;
  String? _generation;
  Stream<Map<String, dynamic>> get states => _states.stream;
  void _accept(Object? value) {
    if (_closed || value is! Map) {
      return;
    }
    final next = Map<String, dynamic>.from(value);
    final generation = next['generation'] as String;
    _generation ??= generation;
    if (_generation != generation) {
      return;
    }
    final previous = latest;
    if (previous != null &&
        previous['generation'] == next['generation'] &&
        (next['sequence'] as num) <= (previous['sequence'] as num)) {
      return;
    }
    latest = next;
    _states.add(next);
  }

  Future<void> load() async =>
      _accept(await _methods.invokeMethod<Object?>('snapshot'));
  Future<void> command(String op, [Map<String, Object?> args = const {}]) {
    final done = Completer<void>();
    final field = {
      'name',
      'url',
      'interval',
      'type',
      'automatic',
      'silent',
    }.contains(op);
    _tail = _tail.then((_) async {
      try {
        if (_closed) throw StateError('closed');
        if (_rejected.isNotEmpty && !field && op != 'cancel') {
          throw StateError('unacknowledged_fields');
        }
        if (latest == null && op == 'back') {
          await _navigation.invokeMethod<void>('navigation.close');
          done.complete();
          return;
        }
        final state = latest ?? (throw StateError('not_loaded'));
        final reply = await _methods.invokeMethod<Object?>('command', {
          ...args,
          'op': op,
          'generation': state['generation'],
          'revision': state['revision'],
        });
        if (reply is! Map ||
            reply['generation'] != state['generation'] ||
            (reply['revision'] as num) != (state['revision'] as num) + 1) {
          throw StateError('invalid_acknowledgement');
        }
        _accept(reply);
        if (field) _rejected.remove(op);
        if (op == 'cancel') _rejected.clear();
        done.complete();
      } catch (error, stack) {
        if (field) _rejected.add(op);
        done.completeError(error, stack);
      }
    });
    return done.future;
  }

  Future<void> close() async {
    _closed = true;
    await _subscription.cancel();
    await _states.close();
  }
}
