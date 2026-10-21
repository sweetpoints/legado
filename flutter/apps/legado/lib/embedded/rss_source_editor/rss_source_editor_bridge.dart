import 'dart:async';

import 'package:flutter/services.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import 'rss_source_editor_state.dart';

part 'rss_source_editor_bridge.g.dart';

@riverpod
RssEditorBridge rssEditorBridge(Ref ref) {
  final bridge = RssEditorBridge();
  ref.onDispose(() => unawaited(bridge.close()));
  return bridge;
}

@riverpod
Stream<RssEditorSnapshot> rssEditorState(Ref ref) =>
    ref.watch(rssEditorBridgeProvider).snapshots();

class RssEditorWriteBlocked implements Exception {
  const RssEditorWriteBlocked();
}

/// The native private draft owns every accepted field and selection.
class RssEditorBridge {
  RssEditorBridge({
    this.channel = const MethodChannel('legado/rss_source_editor'),
    this.events = const EventChannel('legado/rss_source_editor/state'),
  });
  final MethodChannel channel;
  final EventChannel events;
  bool _closed = false;
  int? _generation;
  final _rejectedFields = <String>{};
  bool get hasRejectedFields => _rejectedFields.isNotEmpty;
  RssEditorSnapshot? _latest;
  final _retiredGenerations = <int>{};
  void Function(RssEditorSnapshot)? _emit;
  RssEditorSnapshot? get latestSnapshot => _latest;
  Future<void> _commands = Future.value();
  final _lifetime = StreamController<void>.broadcast();
  Future<RssEditorSnapshot?> command(
    String op,
    int generation, [
    Map<String, Object?> arguments = const {},
  ]) {
    if (_closed) return Future.error(StateError('rss_source_editor_closed'));
    const codec = StandardMessageCodec();
    final immutable = codec.decodeMessage(
      codec.encodeMessage({'op': op, ...arguments, 'generation': generation}),
    );
    final next = _commands.then((_) async {
      if (_closed) throw StateError('rss_source_editor_closed');
      if (generation != _generation) {
        throw StateError('rss_source_editor_stale_generation');
      }
      if (hasRejectedFields &&
          const {
            'save',
            'native',
            'editor',
            'paste',
            'clearCookie',
            'insert',
            'undo',
            'redo',
          }.contains(op)) {
        throw const RssEditorWriteBlocked();
      }
      try {
        final value = await channel.invokeMethod<Object?>('command', immutable);
        if (const {
          'field',
          'option',
          'expanded',
          'autoComplete',
        }.contains(op)) {
          final ack = _decode(value);
          if (ack.generation != generation) {
            throw const FormatException('invalid_rss_source_editor_ack');
          }
          if (_accept(ack)) _emit?.call(ack);
          if (op == 'field' && _generation == generation) {
            _rejectedFields.remove((immutable as Map)['key']);
          }
          return ack;
        }
      } catch (_) {
        if (op == 'field' && _generation == generation) {
          _rejectedFields.add((immutable as Map)['key'] as String);
        }
        rethrow;
      }
      return null;
    });
    _commands = next.then<void>((_) {}, onError: (Object _, StackTrace _) {});
    return next;
  }

  RssEditorSnapshot _decode(Object? value) {
    try {
      return RssEditorSnapshot.fromMap(value);
    } on FormatException {
      rethrow;
    } catch (_) {
      throw const FormatException('invalid_rss_source_editor_snapshot');
    }
  }

  bool _accept(RssEditorSnapshot snapshot) {
    if (_retiredGenerations.contains(snapshot.generation)) return false;
    if (_generation == snapshot.generation &&
        _latest != null &&
        snapshot.revision < _latest!.revision) {
      return false;
    }
    if (_generation != snapshot.generation) {
      if (_generation != null) _retiredGenerations.add(_generation!);
      _rejectedFields.clear();
    }
    _generation = snapshot.generation;
    _latest = snapshot;
    return true;
  }

  Stream<RssEditorSnapshot> snapshots() {
    if (_closed) return Stream.error(StateError('rss_source_editor_closed'));
    late StreamController<RssEditorSnapshot> controller;
    StreamSubscription<Object?>? subscription;
    StreamSubscription<void>? lifetime;
    var disposed = false;
    var revision = 0;
    void deliver(Object? value) {
      if (disposed) return;
      try {
        final snapshot = _decode(value);
        if (_accept(snapshot)) controller.add(snapshot);
      } catch (error, stack) {
        controller.addError(error, stack);
      }
    }

    Future<void> stop() async {
      if (disposed) return;
      disposed = true;
      _emit = null;
      await subscription?.cancel();
      await lifetime?.cancel();
    }

    controller = StreamController(
      onListen: () {
        _emit = (snapshot) {
          if (!disposed) controller.add(snapshot);
        };
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
                StateError('rss_source_editor_state_disconnected'),
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
