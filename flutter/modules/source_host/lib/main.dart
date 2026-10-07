import 'package:flutter/widgets.dart';
import 'package:flutter/foundation.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:source_platform/source_platform.dart';
import 'package:source_v8/source_v8.dart';

import 'source_host.dart';
import 'session_store.dart';
import 'task_host.dart';

@pragma('vm:entry-point')
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final host = SourceHost(
    createSourceEngine,
    sessionStore: const PlatformSessionStore(),
    legacyRuleHostEnabled: defaultTargetPlatform == TargetPlatform.android,
    legacyScriptRuleHostEnabled:
        defaultTargetPlatform == TargetPlatform.android,
    legacyPageFetchEnabled: defaultTargetPlatform == TargetPlatform.android,
    legacyWebRuleHostEnabled: defaultTargetPlatform == TargetPlatform.android,
  );
  await host.attach(
    initialize: () async {
      final probe = V8Runtime();
      final cancellation = CancellationToken();
      try {
        const expectedVersion = '15.4.80.25';
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
  bool? useNativeLegacyHttp,
}) => SourceEngine(
  runtime:
      runtime ??
      _SourceRuntime(
        legacy: source.metadata['legacy'] == true,
        useNativeHttp:
            useNativeLegacyHttp ??
            defaultTargetPlatform == TargetPlatform.android,
      ),
  requestAdapter: adaptLegacyRequest,
  legacyPageFetcher: defaultTargetPlatform == TargetPlatform.android
      ? const HostLegacyPageFetcher()
      : null,
  legacyRuleEvaluator: defaultTargetPlatform == TargetPlatform.android
      ? const HostLegacyRuleEvaluator(allowScripts: true, allowWebScripts: true)
      : null,
  platform: SourceUtilityHost(platform ?? SourcePlatform(sourceId: source.id)),
);

class _SourceRuntime implements AuxiliaryScriptRuntime, SourceRuntimeState {
  _SourceRuntime({required this.legacy, this.useNativeHttp = false})
    : runtime = V8Runtime(
        prelude: legacy ? legacyScriptPrelude : '',
        persistent: true,
      );
  final bool legacy;
  final bool useNativeHttp;
  final V8Runtime runtime;
  final variables = <String, String>{};
  ScriptContext _context(ScriptContext context) => ScriptContext(
    variables: context.variables,
    host: legacy
        ? LegacyScriptHost(
            TaskScriptHost(context.host, context.variables['taskId']),
            variables: variables,
            useNativeHttp: useNativeHttp,
          )
        : TaskScriptHost(context.host, context.variables['taskId']),
    timeout: context.timeout,
  );
  @override
  Future<ScriptDiagnostic?> checkSyntax(
    String code, {
    CancellationToken? cancellation,
  }) => runtime.checkSyntax(code, cancellation: cancellation);
  @override
  Future<Object?> evaluateAuxiliary(
    String code,
    ScriptContext context, {
    String prelude = '',
    CancellationToken? cancellation,
  }) => runtime.evaluateAuxiliary(
    code,
    _context(context),
    prelude: prelude,
    cancellation: cancellation,
  );
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
              TaskScriptHost(context.host, context.variables['taskId']),
              variables: variables,
              useNativeHttp: useNativeHttp,
            )
          : TaskScriptHost(context.host, context.variables['taskId']),
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

class _StartupHost implements ScriptHost {
  @override
  Future<Object?> call(String method, List<Object?> arguments) async =>
      throw StateError('Startup self-check cannot invoke host APIs');
}
