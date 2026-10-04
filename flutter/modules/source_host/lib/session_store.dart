import 'dart:convert';

import 'package:source_platform/source_platform.dart';

abstract interface class SourceSessionStore {
  Future<Map<String, Object?>?> read(String sourceId, bool legacy);
  Future<void> write(
    String sourceId,
    bool legacy,
    Map<String, Object?> session,
  );
}

/// Session persistence is separate from script-accessible key/value storage.
class PlatformSessionStore implements SourceSessionStore {
  const PlatformSessionStore();
  String _key(bool legacy) =>
      '__engine.session.v1.${legacy ? 'legacy' : 'modern'}';
  @override
  Future<Map<String, Object?>?> read(String sourceId, bool legacy) async {
    final value = await SourcePlatform(sourceId: sourceId)
        .read(sourceId, _key(legacy));
    if (value == null) return null;
    return Map<String, Object?>.from(jsonDecode(value) as Map);
  }

  @override
  Future<void> write(
    String sourceId,
    bool legacy,
    Map<String, Object?> session,
  ) =>
      SourcePlatform(sourceId: sourceId)
          .writeSession(sourceId, _key(legacy), jsonEncode(session));
}

/// Runtime-specific state (legacy java.get/put variables) is isolated per source.
abstract interface class SourceRuntimeState {
  Map<String, Object?> exportRuntimeState();
  void importRuntimeState(Map<String, Object?> state);
}
