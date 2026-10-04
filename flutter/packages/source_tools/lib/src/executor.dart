import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';

/// Shared CLI assembly, accepting a runtime to make adapter contracts testable.
SourceEngine createCliEngine(SourceDefinition source, ScriptRuntime runtime) {
  final legacy = source.metadata['legacy'] == true;
  final legacyVariables = <String, String>{};
  return SourceEngine(
    runtime: runtime,
    platform: SourceUtilityHost(const _UnavailablePlatform()),
    hostAdapter: legacy
        ? (host) => LegacyScriptHost(host, variables: legacyVariables)
        : null,
  );
}

class _UnavailablePlatform implements ScriptHost {
  const _UnavailablePlatform();
  @override
  Future<Object?> call(String method, List<Object?> arguments) async {
    throw EngineException(
      'unsupported_host_api',
      'Platform capability unavailable in CLI: $method',
    );
  }
}
