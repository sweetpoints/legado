import 'dart:async';
import 'dart:convert';
import 'dart:ffi';
import 'dart:isolate';

import 'package:ffi/ffi.dart';
import 'package:source_engine/source_engine.dart';

import 'source_v8_bindings_generated.dart' as native;

final Object _hostDispatchZone = Object();

/// Embedded V8. Each evaluation has an isolated JS context and watchdog.
class V8Runtime implements AuxiliaryScriptRuntime {
  V8Runtime({
    this.prelude = '',
    this.heapLimitMb = 64,
    this.persistent = false,
  });
  final String prelude;
  final int heapLimitMb;
  final bool persistent;
  Pointer<Void>? _session;
  String? _sessionPrelude;
  Future<void> _tail = Future<void>.value();
  bool _closed = false;
  final Set<Future<void>> _active = {};
  final Set<void Function()> _cancel = {};
  String get version => native.sv8_version().cast<Utf8>().toDartString();
  @override
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  }) => _schedule(() => _evaluate(code, context, cancellation: cancellation));

  @override
  Future<Object?> evaluateAuxiliary(
    String code,
    ScriptContext context, {
    String prelude = '',
    CancellationToken? cancellation,
  }) => _schedule(
    () => _evaluate(
      code,
      context,
      cancellation: cancellation,
      mode: 'aux',
      extraPrelude: prelude,
    ),
  );

  @override
  Future<ScriptDiagnostic?> checkSyntax(
    String code, {
    CancellationToken? cancellation,
  }) async {
    // Compilation uses an ephemeral context and never runs source/prelude/host code.
    final parser = V8Runtime(heapLimitMb: heapLimitMb);
    try {
      final value = await parser._evaluate(
        code,
        ScriptContext(host: _NoHost()),
        cancellation: cancellation,
        mode: 'syntax',
      );
      if (value == null) return null;
      final diagnostic = value as Map;
      return ScriptDiagnostic(
        diagnostic['message'] as String,
        diagnostic['lineNumber'] as int,
        diagnostic['columnNumber'] as int,
      );
    } finally {
      await parser.close();
    }
  }

  Future<Object?> _schedule(Future<Object?> Function() action) {
    if (Zone.current[_hostDispatchZone] == true) {
      return Future<Object?>.error(
        const EngineException(
          'nested_script_requires_migration',
          'Host callbacks cannot enter a script VM',
        ),
      );
    }
    if (!persistent) return action();
    final result = _tail.then((_) => action());
    _tail = result.then<void>((_) {}, onError: (Object _, StackTrace _) {});
    return result;
  }

  Future<Object?> _evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
    String mode = 'execute',
    String extraPrelude = '',
  }) async {
    if (_closed) throw StateError('V8Runtime is closed');
    cancellation?.throwIfCancelled();
    if (context.timeout.inMilliseconds < 1 ||
        context.timeout.inMilliseconds > 300000) {
      throw const EngineException(
        'invalid_request',
        'Script timeout must be 1..300000ms',
      );
    }
    // Bindings are data only; native control fields and host namespaces are owned here.
    final variables = jsonDecode(jsonEncode(context.variables)) as Map;
    for (final key in variables.keys) {
      if (key is! String ||
          key.startsWith('__source') ||
          key.startsWith('__sv8') ||
          ['source', 'java', 'globalThis'].contains(key)) {
        throw const EngineException(
          'invalid_bindings',
          'Reserved runtime binding',
        );
      }
    }
    final effectivePrelude = mode == 'syntax' ? '' : '$prelude\n$extraPrelude';
    if (persistent) {
      if (_session != null && _sessionPrelude != effectivePrelude) {
        native.sv8_destroy(_session!);
        _session = null;
      }
      _session ??= native.sv8_create(
        context.timeout.inMilliseconds,
        heapLimitMb,
      );
      _sessionPrelude = effectivePrelude;
    }
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
        value = await runZoned(
          () => context.host.call(
            request['method'] as String,
            List<Object?>.from(request['args'] as List),
          ),
          zoneValues: {_hostDispatchZone: true},
        );
        jsonEncode(value);
      } catch (e) {
        String? code;
        if (e is EngineException) {
          code = e.code;
        } else {
          try {
            code = (e as dynamic).code as String?;
          } catch (_) {}
        }
        value = code == 'nested_script_requires_migration'
            ? {
                '__sourceErrorCode': code,
                'message': 'Nested script host callback requires migration',
              }
            : e.toString();
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
            if (cancelled) native.sv8_cancel(handle!);
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
                    : data.length > 2 &&
                          data[2] is String &&
                          (data[2] as String).isNotEmpty
                    ? data[2] as String
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
        jsonEncode({
          '__sv8Protocol': 1,
          'mode': mode,
          'bindings': variables,
          'timeoutMs': context.timeout.inMilliseconds,
        }),
        effectivePrelude,
        context.timeout.inMilliseconds,
        heapLimitMb,
        _session?.address ?? 0,
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
    await _tail;
    if (_session != null) {
      native.sv8_destroy(_session!);
      _session = null;
    }
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
  final retained = (args[6] as int) != 0;
  final runtime = retained
      ? Pointer<Void>.fromAddress(args[6] as int)
      : native.sv8_create(args[4] as int, args[5] as int);
  Timer? poll;
  bool finished = false;
  void finish(String kind, Object? value, {String? code}) {
    if (finished) return;
    finished = true;
    poll?.cancel();
    send.send([kind, value, code]);
  }

  void check() {
    try {
      final state = _read(native.sv8_poll(runtime)) as Map;
      switch (state['status']) {
        case 'done':
          finish('done', state['value']);
        case 'error':
          finish('error', state['error'], code: state['code'] as String?);
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
        if (!retained) native.sv8_destroy(runtime);
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

class _NoHost implements ScriptHost {
  @override
  Future<Object?> call(String method, List<Object?> arguments) async =>
      throw const EngineException(
        'syntax_host_forbidden',
        'Syntax compilation cannot invoke hosts',
      );
}
