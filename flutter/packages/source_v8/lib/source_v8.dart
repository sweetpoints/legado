import 'dart:async';
import 'dart:convert';
import 'dart:ffi';
import 'dart:isolate';

import 'package:ffi/ffi.dart';
import 'package:source_engine/source_engine.dart';

import 'source_v8_bindings_generated.dart' as native;

/// Embedded V8. Each evaluation has an isolated JS context and watchdog.
class V8Runtime implements ScriptRuntime {
  V8Runtime({this.prelude = '', this.heapLimitMb = 64});
  final String prelude;
  final int heapLimitMb;
  bool _closed = false;
  final Set<Future<void>> _active = {};
  final Set<void Function()> _cancel = {};
  String get version => native.sv8_version().cast<Utf8>().toDartString();
  @override
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  }) async {
    if (_closed) throw StateError('V8Runtime is closed');
    cancellation?.throwIfCancelled();
    final done = Completer<void>();
    _active.add(done.future);
    final receive = ReceivePort();
    final result = Completer<Object?>();
    Pointer<Void>? handle;
    Timer? syncTimer;
    bool cancelled = false;
    bool disposed = false;
    void cancel() {
      cancelled = true;
      if (handle != null && !disposed) native.sv8_cancel(handle!);
    }

    _cancel.add(cancel);
    unawaited(
      cancellation?.whenCancelled.then((_) {
            if (!done.isCompleted) cancel();
          }) ??
          Future<void>.value(),
    );
    late SendPort worker;
    bool spawned = false;
    Future<void> dispatch(Map request, {required bool sync}) async {
      Object? value;
      bool failed = false;
      try {
        value = await context.host.call(
          request['method'] as String,
          List<Object?>.from(request['args'] as List),
        );
        jsonEncode(value);
      } catch (e) {
        value = e.toString();
        failed = true;
      }
      if (disposed) return;
      if (sync) {
        final text = jsonEncode(value).toNativeUtf8();
        try {
          native.sv8_sync_reply(
            handle!,
            request['id'] as int,
            text.cast(),
            failed ? 1 : 0,
          );
        } finally {
          calloc.free(text);
        }
      } else {
        worker.send(['resolve', request['id'], jsonEncode(value), failed]);
      }
    }

    final subscription = receive.listen((dynamic message) {
      final data = message as List;
      if (data.length == 2 &&
          !['done', 'error', 'requests', 'disposed'].contains(data[0])) {
        disposed = true;
        handle = null;
        syncTimer?.cancel();
        if (!result.isCompleted) {
          result.completeError(
            EngineException('runtime_worker_error', data[0].toString()),
          );
        }
        if (!done.isCompleted) done.complete();
        return;
      }
      switch (data[0]) {
        case 'ready':
          worker = data[1] as SendPort;
          handle = Pointer<Void>.fromAddress(data[2] as int);
          syncTimer = Timer.periodic(const Duration(milliseconds: 5), (_) {
            if (disposed) return;
            final requests = _read(native.sv8_sync_poll(handle!)) as List;
            for (final request in requests) {
              unawaited(dispatch(request as Map, sync: true));
            }
          });
          if (cancelled) cancel();
          worker.send(['start']);
        case 'requests':
          for (final request in data[1] as List) {
            unawaited(dispatch(request as Map, sync: false));
          }
        case 'done':
          disposed = true;
          handle = null;
          syncTimer?.cancel();
          worker.send(['dispose']);
          if (!result.isCompleted) result.complete(data[1]);
        case 'error':
          disposed = true;
          handle = null;
          syncTimer?.cancel();
          worker.send(['dispose']);
          if (!result.isCompleted) {
            result.completeError(
              EngineException(
                cancelled
                    ? 'cancelled'
                    : data[1] == 'execution_timeout'
                    ? 'script_timeout'
                    : 'script_error',
                data[1].toString(),
              ),
            );
          }
        case 'disposed':
          disposed = true;
          handle = null;
          syncTimer?.cancel();
          if (!done.isCompleted) done.complete();
      }
    });
    try {
      await Isolate.spawn(_worker, [
        receive.sendPort,
        code,
        jsonEncode(context.variables),
        prelude,
        context.timeout.inMilliseconds,
        heapLimitMb,
      ], onError: receive.sendPort);
      spawned = true;
      return await result.future;
    } finally {
      // Native worker owns destruction; never dispose while host callbacks run.
      cancel();
      if (spawned) await done.future;
      syncTimer?.cancel();
      await subscription.cancel();
      receive.close();
      _cancel.remove(cancel);
      _active.remove(done.future);
    }
  }

  @override
  Future<void> close() async {
    _closed = true;
    for (final cancel in _cancel.toList()) {
      cancel();
    }
    await Future.wait(_active.toList());
  }
}

Object? _read(Pointer<Char> value) {
  try {
    return jsonDecode(value.cast<Utf8>().toDartString());
  } finally {
    native.sv8_free(value);
  }
}

void _worker(List<Object?> args) {
  final send = args[0] as SendPort;
  final messages = ReceivePort();
  final runtime = native.sv8_create(args[4] as int, args[5] as int);
  Timer? poll;
  bool finished = false;
  void finish(String kind, Object? value) {
    if (finished) return;
    finished = true;
    poll?.cancel();
    send.send([kind, value]);
  }

  void check() {
    try {
      final state = _read(native.sv8_poll(runtime)) as Map;
      switch (state['status']) {
        case 'done':
          finish('done', state['value']);
        case 'error':
          finish('error', state['error']);
        case 'pending':
          if ((state['requests'] as List).isNotEmpty) {
            send.send(['requests', state['requests']]);
          }
      }
    } catch (e) {
      finish('error', e.toString());
    }
  }

  messages.listen((dynamic message) {
    final data = message as List;
    try {
      if (data[0] == 'dispose') {
        native.sv8_destroy(runtime);
        send.send(['disposed']);
        messages.close();
        return;
      }
      if (data[0] == 'start') {
        final code = (args[1] as String).toNativeUtf8();
        final vars = (args[2] as String).toNativeUtf8();
        final prelude = (args[3] as String).toNativeUtf8();
        try {
          native.sv8_start(runtime, code.cast(), vars.cast(), prelude.cast());
        } finally {
          calloc.free(code);
          calloc.free(vars);
          calloc.free(prelude);
        }
        check();
        if (!finished) {
          poll = Timer.periodic(
            const Duration(milliseconds: 5),
            (_) => check(),
          );
        }
      } else if (data[0] == 'resolve' && !finished) {
        final value = (data[2] as String).toNativeUtf8();
        try {
          native.sv8_resolve(
            runtime,
            data[1] as int,
            value.cast(),
            data[3] == true ? 1 : 0,
          );
        } finally {
          calloc.free(value);
        }
        check();
      }
    } catch (e) {
      finish('error', e.toString());
    }
  });
  send.send(['ready', messages.sendPort, runtime.address]);
}
