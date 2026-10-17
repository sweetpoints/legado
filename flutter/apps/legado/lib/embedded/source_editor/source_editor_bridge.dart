import 'dart:async';

import 'package:flutter/services.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import 'source_editor_state.dart';

part 'source_editor_bridge.g.dart';

@riverpod
SourceEditorBridge sourceEditorBridge(Ref ref) {
  final bridge = SourceEditorBridge();
  ref.onDispose(() => unawaited(bridge.close()));
  return bridge;
}

@riverpod
Stream<SourceEditorSnapshot> sourceEditorState(Ref ref) =>
    ref.watch(sourceEditorBridgeProvider).snapshots();

/// A previously rejected edit must be explicitly recovered before saving.
class SourceEditorWriteBlocked implements Exception {
  const SourceEditorWriteBlocked();
}

/// Native draft state is authoritative; every edit precedes later navigation.
class SourceEditorBridge {
  SourceEditorBridge({
    this.channel = const MethodChannel('legado/source_editor'),
    this.events = const EventChannel('legado/source_editor/state'),
  });
  final MethodChannel channel;
  final EventChannel events;
  bool _closed = false;
  // Checked inside the queue so even a save tapped before the rejection
  // returns cannot silently persist the older native draft.
  final _rejectedFields = <String>{};
  bool get hasRejectedFields => _rejectedFields.isNotEmpty;
  Future<void> _commands = Future.value();
  final _lifetime = StreamController<void>.broadcast();
  Future<void> command(String op, [Map<String, Object?> arguments = const {}]) {
    if (_closed) return Future.error(StateError('source_editor_closed'));
    const codec = StandardMessageCodec();
    final immutable = codec.decodeMessage(
      codec.encodeMessage({'op': op, ...arguments}),
    );
    final next = _commands.then((_) async {
      if (_closed) throw StateError('source_editor_closed');
      if (op == 'save' && hasRejectedFields) {
        throw const SourceEditorWriteBlocked();
      }
      final fieldKey = '${(immutable as Map)['tab']}:${immutable['key']}';
      try {
        await channel.invokeMethod<void>('command', immutable);
      } catch (_) {
        if (op == 'field') _rejectedFields.add(fieldKey);
        rethrow;
      }
      if (op == 'field') _rejectedFields.remove(fieldKey);
      if (op == 'retry') _rejectedFields.clear();
    });
    _commands = next.then<void>((_) {}, onError: (Object _, StackTrace _) {});
    return next;
  }

  static SourceEditorSnapshot _decode(Object? value) {
    try {
      return SourceEditorSnapshot.fromMap(editorMap(value));
    } on FormatException {
      rethrow;
    } catch (_) {
      throw const FormatException('invalid_source_editor_snapshot');
    }
  }

  Stream<SourceEditorSnapshot> snapshots() {
    if (_closed) return Stream.error(StateError('source_editor_closed'));
    late StreamController<SourceEditorSnapshot> controller;
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
                StateError('source_editor_state_disconnected'),
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
