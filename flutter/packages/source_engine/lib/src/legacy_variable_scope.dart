import 'dart:convert';

import 'contracts.dart';

/// Legacy entity variables travel independently from modern source-global state.
Map<String, String> legacyEntityVariables(Object? value) {
  if (value == null || value == '') return {};
  if (value is String) {
    try {
      value = jsonDecode(value);
    } on FormatException {
      throw const EngineException(
        'invalid_legacy_variable_scope',
        'Entity variable JSON is invalid',
      );
    }
  }
  if (value is! Map ||
      value.entries.any((e) => e.key is! String || e.value is! String)) {
    throw const EngineException(
      'invalid_legacy_variable_scope',
      'Entity variables require string keys and values',
    );
  }
  return Map<String, String>.from(value);
}

Map<String, Object?>? legacyVariableScope(ScriptContext context) {
  final value = context.variables['legacyVariableScope'];
  return value is Map<String, Object?> ? value : null;
}

Map<String, String> legacyScopeReads(Map<String, Object?> scope) {
  final result = <String, String>{};
  for (final layer in ['source', 'book', 'chapter']) {
    for (final entry in (scope[layer] as Map<String, String>).entries) {
      // Old get falls through an empty chapter/book value to the next layer.
      if (entry.value.isNotEmpty) result[entry.key] = entry.value;
    }
  }
  return result;
}

Map<String, Object?> legacyScopeBindings(ScriptContext context) {
  final scope = legacyVariableScope(context);
  return {
    for (final entry in context.variables.entries)
      if (entry.key != 'legacyVariableScope') entry.key: entry.value,
    ...(scope == null
        ? Map<String, Object?>.from(
            context.variables['legacyVariables'] as Map? ?? {},
          )
        : {
            for (final entry in legacyScopeReads(scope).entries)
              if (!context.variables.containsKey(entry.key) &&
                  entry.key != 'legacyVariableScope')
                entry.key: entry.value,
          }),
  };
}

Future<void> applyLegacyScopeReply(ScriptContext context, Map response) async {
  final dirty = legacyEntityVariables(response['variables']);
  final local = legacyVariableScope(context);
  if (local == null) {
    for (final entry in dirty.entries) {
      await context.host.call('variables.put', [entry.key, entry.value]);
    }
    return;
  }
  final reply = response['variableScope'];
  if (reply == null) {
    // Older custom adapters can provide dirty values; keep them entity-local.
    (local[local['target']] as Map<String, String>).addAll(dirty);
    return;
  }
  if (reply is! Map ||
      reply['id'] != local['id'] ||
      reply['target'] != local['target']) {
    throw const EngineException(
      'invalid_legacy_variable_scope',
      'Host changed the variable scope identity',
    );
  }
  if (['source', 'book', 'chapter'].any((layer) => reply[layer] is! Map)) {
    throw const EngineException(
      'invalid_legacy_variable_scope',
      'Host scope requires all three variable layers',
    );
  }
  final validated = {
    for (final layer in ['source', 'book', 'chapter'])
      layer: legacyEntityVariables(reply[layer]),
  };
  for (final entry in validated.entries) {
    final target = local[entry.key] as Map<String, String>;
    target
      ..clear()
      ..addAll(entry.value);
  }
}
