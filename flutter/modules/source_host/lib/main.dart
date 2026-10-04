import 'package:flutter/widgets.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:source_platform/source_platform.dart';
import 'package:source_v8/source_v8.dart';

import 'source_host.dart';
import 'session_store.dart';

@pragma('vm:entry-point')
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final host = SourceHost(
    createSourceEngine,
    sessionStore: const PlatformSessionStore(),
  );
  await host.attach(
    initialize: () async {
      final probe = V8Runtime();
      final cancellation = CancellationToken();
      try {
        const expectedVersion = '15.4.80.24';
        final actualVersion = probe.version;
        if (actualVersion != expectedVersion) {
          throw StateError(
            'V8 runtime version mismatch: expected $expectedVersion, loaded $actualVersion',
          );
        }
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

/// Shared production composition for the module and its integration tests.
SourceEngine createSourceEngine(
  SourceDefinition source, {
  ScriptRuntime? runtime,
  ScriptHost? platform,
}) => SourceEngine(
  runtime: runtime ?? _SourceRuntime(legacy: source.metadata['legacy'] == true),
  platform: SourceUtilityHost(platform ?? SourcePlatform(sourceId: source.id)),
);

class _SourceRuntime implements ScriptRuntime, SourceRuntimeState {
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
  Map<String, Object?> exportRuntimeState() => {
    'legacyVariables': Map<String, String>.from(variables),
  };
  @override
  void importRuntimeState(Map<String, Object?> state) {
    final saved = state['legacyVariables'];
    if (saved is! Map) return;
    variables.clear();
    for (final entry in saved.entries) {
      if (entry.key is! String || entry.value is! String) {
        throw const FormatException('Invalid legacy variables');
      }
      variables[entry.key as String] = entry.value as String;
    }
  }

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
