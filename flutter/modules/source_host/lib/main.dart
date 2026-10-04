import 'package:flutter/widgets.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:source_platform/source_platform.dart';
import 'package:source_v8/source_v8.dart';

import 'source_host.dart';

@pragma('vm:entry-point')
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final host = SourceHost(
    (source) => SourceEngine(
      runtime: _SourceRuntime(legacy: source.metadata['legacy'] == true),
      platform: SourcePlatform(sourceId: source.id),
    ),
  );
  await host.attach(
    initialize: () async {
      final probe = V8Runtime();
      final cancellation = CancellationToken();
      try {
        final result = await probe
            .evaluate(
              '1 + 1',
              ScriptContext(
                host: _StartupHost(),
                timeout: const Duration(seconds: 5),
              ),
              cancellation: cancellation,
            )
            .timeout(const Duration(seconds: 10));
        if (result != 2) throw StateError('V8 startup self-check failed');
      } finally {
        cancellation.cancel();
        await probe.close().timeout(const Duration(seconds: 5));
      }
    },
  );
}

class _SourceRuntime implements ScriptRuntime {
  _SourceRuntime({required this.legacy})
    : runtime = V8Runtime(prelude: legacy ? legacyScriptPrelude : '');
  final bool legacy;
  final V8Runtime runtime;
  final variables = <String, String>{};
  @override
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  }) => runtime.evaluate(
    code,
    ScriptContext(
      variables: context.variables,
      host: legacy
          ? LegacyScriptHost(
              _TaskHost(context.host, context.variables['taskId']),
              variables: variables,
            )
          : _TaskHost(context.host, context.variables['taskId']),
      timeout: context.timeout,
    ),
    cancellation: cancellation,
  );
  @override
  Future<void> close() => runtime.close();
}

/// Attach cancellation identity without requiring source authors to pass it.
class _TaskHost implements ScriptHost {
  _TaskHost(this.delegate, this.taskId);
  final ScriptHost delegate;
  final Object? taskId;
  @override
  Future<Object?> call(String method, List<Object?> arguments) {
    if (method == 'browser.open' && arguments.length < 3) {
      return delegate.call(method, [
        arguments.first,
        arguments.length > 1 ? arguments[1] : '',
        taskId,
      ]);
    }
    return delegate.call(method, arguments);
  }
}

class _StartupHost implements ScriptHost {
  @override
  Future<Object?> call(String method, List<Object?> arguments) async =>
      throw StateError('Startup self-check cannot invoke host APIs');
}
