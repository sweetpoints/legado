import 'dart:async';

import 'package:flutter/services.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import 'source_debugger_state.dart';

part 'source_debugger_bridge.g.dart';

@riverpod
SourceDebuggerBridge sourceDebuggerBridge(Ref ref) {
  final bridge = SourceDebuggerBridge();
  ref.onDispose(() => unawaited(bridge.close()));
  return bridge;
}

@riverpod
Stream<SourceDebuggerSnapshot> sourceDebuggerState(Ref ref) =>
    ref.watch(sourceDebuggerBridgeProvider).snapshots();

/// Native debug sessions remain authoritative. Commands preserve input ordering.
class SourceDebuggerBridge {
  SourceDebuggerBridge({
    this.channel = const MethodChannel('legado/source_debugger'),
    this.events = const EventChannel('legado/source_debugger/state'),
  });
  final MethodChannel channel;
  final EventChannel events;
  bool _closed = false;
  int? _generation;
  bool _queryRejected = false;
  Future<void> _commands = Future.value();
  final _lifetime = StreamController<void>.broadcast();
  Future<void> command(
    String op,
    int generation, [
    Map<String, Object?> arguments = const {},
  ]) {
    if (_closed) return Future.error(StateError('source_debugger_closed'));
    const codec = StandardMessageCodec();
    final immutable = codec.decodeMessage(
      codec.encodeMessage({'op': op, ...arguments, 'generation': generation}),
    );
    final next = _commands.then((_) async {
      if (_closed) throw StateError('source_debugger_closed');
      if (generation != _generation) {
        throw StateError('source_debugger_stale_generation');
      }
      if (_queryRejected &&
          const {'run', 'sort', 'prefix', 'detail'}.contains(op)) {
        throw StateError('source_debugger_query_rejected');
      }
      try {
        await channel.invokeMethod<void>('command', immutable);
      } catch (_) {
        if (op == 'query') _queryRejected = true;
        rethrow;
      }
      if (op == 'query' || op == 'retry') _queryRejected = false;
    });
    _commands = next.then<void>((_) {}, onError: (Object _, StackTrace _) {});
    return next;
  }

  SourceDebuggerSnapshot _decode(Object? value) {
    try {
      final snapshot = SourceDebuggerSnapshot.fromMap(value);
      if (_generation != snapshot.generation) _queryRejected = false;
      _generation = snapshot.generation;
      return snapshot;
    } on FormatException {
      rethrow;
    } catch (_) {
      throw const FormatException('invalid_source_debugger_snapshot');
    }
  }

  Stream<SourceDebuggerSnapshot> snapshots() {
    if (_closed) return Stream.error(StateError('source_debugger_closed'));
    late StreamController<SourceDebuggerSnapshot> controller;
    StreamSubscription<Object?>? subscription;
    StreamSubscription<void>? lifetime;
    var disposed = false;
    var revision = 0;
    void deliver(Object? value) {
      if (disposed) return;
      try {
        controller.add(_decode(value));
      } catch (error, stack) {
        controller.addError(error, stack);
      }
    }

    Future<void> stop() async {
      if (disposed) return;
      disposed = true;
      await subscription?.cancel();
      await lifetime?.cancel();
    }

    controller = StreamController(
      onListen: () {
        lifetime = _lifetime.stream.listen(
          (_) => unawaited(stop().then((_) => controller.close())),
        );
        subscription = events.receiveBroadcastStream().listen(
          (value) {
            revision++;
            deliver(value);
          },
          onError: (Object error, StackTrace stack) {
            revision++;
            if (!disposed) controller.addError(error, stack);
          },
          onDone: () {
            revision++;
            if (!disposed) {
              controller.addError(
                StateError('source_debugger_state_disconnected'),
              );
              unawaited(stop().then((_) => controller.close()));
            }
          },
        );
        final initialRevision = revision;
        unawaited(
          channel
              .invokeMethod<Object?>('snapshot')
              .then(
                (value) {
                  if (!disposed && revision == initialRevision) deliver(value);
                },
                onError: (Object error, StackTrace stack) {
                  if (!disposed && revision == initialRevision) {
                    controller.addError(error, stack);
                  }
                },
              ),
        );
      },
      onCancel: stop,
    );
    return controller.stream;
  }

  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    _lifetime.add(null);
    await _lifetime.close();
  }
}
