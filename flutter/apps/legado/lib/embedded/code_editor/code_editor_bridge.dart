import 'dart:async';

import 'package:flutter/services.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import 'code_editor_state.dart';

part 'code_editor_bridge.g.dart';

@riverpod
CodeEditorBridge codeEditorBridge(Ref ref) {
  final bridge = CodeEditorBridge();
  ref.onDispose(() => unawaited(bridge.close()));
  return bridge;
}

@riverpod
Stream<CodeEditorSnapshot> codeEditorState(Ref ref) =>
    ref.watch(codeEditorBridgeProvider).snapshots();

/// A previously rejected edit must be explicitly recovered before saving.
class CodeEditorWriteBlocked implements Exception {
  const CodeEditorWriteBlocked();
}

/// Native draft state is authoritative; every edit precedes later navigation.
class CodeEditorBridge {
  CodeEditorBridge({
    this.channel = const MethodChannel('legado/code_editor'),
    this.events = const EventChannel('legado/code_editor/state'),
  });
  final MethodChannel channel;
  final EventChannel events;
  bool _closed = false;
  // Checked inside the queue so even a save tapped before the rejection
  // returns cannot silently persist the older native draft.
  bool _rejectedSearch = false;
  bool get hasRejectedSearch => _rejectedSearch;
  Future<void> _commands = Future.value();
  final _lifetime = StreamController<void>.broadcast();
  Future<void> command(String op, [Map<String, Object?> arguments = const {}]) {
    if (_closed) return Future.error(StateError('code_editor_closed'));
    const codec = StandardMessageCodec();
    final immutable = codec.decodeMessage(
      codec.encodeMessage({'op': op, ...arguments}),
    );
    final next = _commands.then((_) async {
      if (_closed) throw StateError('code_editor_closed');
      final name = (immutable as Map)['name'];
      if (op == 'action' &&
          hasRejectedSearch &&
          const {
            'SAVE',
            'DEBUG',
            'LOGIN',
            'PREVIOUS',
            'NEXT',
            'REPLACE',
            'REPLACE_ALL',
          }.contains(name)) {
        throw const CodeEditorWriteBlocked();
      }
      try {
        await channel.invokeMethod<void>('command', immutable);
      } catch (_) {
        if (op == 'search') _rejectedSearch = true;
        rethrow;
      }
      if (op == 'search' || op == 'retry') _rejectedSearch = false;
    });
    _commands = next.then<void>((_) {}, onError: (Object _, StackTrace _) {});
    return next;
  }

  static CodeEditorSnapshot _decode(Object? value) {
    try {
      return CodeEditorSnapshot.fromMap(codeEditorMap(value));
    } on FormatException {
      rethrow;
    } catch (_) {
      throw const FormatException('invalid_code_editor_snapshot');
    }
  }

  Stream<CodeEditorSnapshot> snapshots() {
    if (_closed) return Stream.error(StateError('code_editor_closed'));
    late StreamController<CodeEditorSnapshot> controller;
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
              controller.addError(StateError('code_editor_state_disconnected'));
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
