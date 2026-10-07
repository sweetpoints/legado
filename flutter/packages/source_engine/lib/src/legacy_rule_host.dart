import 'package:html/dom.dart';

import 'contracts.dart';

/// Optional platform evaluator for the original Legado rule language.
/// Platforms without one keep the explicitly supported portable rule dialects.
abstract interface class LegacyRuleEvaluator {
  bool supportsRule(String rule);
  Future<List<Object?>> evaluate(
    String rule,
    Object? input,
    ScriptContext context, {
    required SourceDefinition source,
    required String operation,
    bool elements = false,
    bool element = false,
    bool formatContent = false,
    bool scalar = false,
    bool isUrl = false,
    bool unescape = true,
    CancellationToken? cancellation,
  });
}

/// JSON-only transport to the Android parser; no JVM objects enter Dart/V8.
class HostLegacyRuleEvaluator implements LegacyRuleEvaluator {
  const HostLegacyRuleEvaluator({this.allowScripts = false});
  final bool allowScripts;

  /// JS-bearing rules stay on V8, rather than reentering it from a host RPC.
  static bool canEvaluate(String rule, {bool allowScripts = false}) =>
      !RegExp(r'@webjs:', caseSensitive: false).hasMatch(rule) &&
      (allowScripts ||
          !RegExp(r'@js:|<js>|\{\{', caseSensitive: false).hasMatch(rule));
  @override
  bool supportsRule(String rule) =>
      canEvaluate(rule, allowScripts: allowScripts);
  @override
  Future<List<Object?>> evaluate(
    String rule,
    Object? input,
    ScriptContext context, {
    required SourceDefinition source,
    required String operation,
    bool elements = false,
    bool element = false,
    bool formatContent = false,
    bool scalar = false,
    bool isUrl = false,
    bool unescape = true,
    CancellationToken? cancellation,
  }) async {
    cancellation?.throwIfCancelled();
    final original = source.metadata['legacyOriginal'];
    if (original is! Map) {
      throw const EngineException(
        'legacy_rule_source_required',
        'Platform legacy rule evaluation requires the original source snapshot',
      );
    }
    final taskId = context.variables['taskId'];
    if (taskId is! String || taskId.isEmpty) {
      throw const EngineException(
        'invalid_request',
        'Legacy rule host requires a task identity',
      );
    }
    final response = await context.host.call('legacyRule.evaluate', [
      {
        'rule': _originalDialect(rule),
        'input': input is Element ? input.outerHtml : input,
        'source': original,
        'operation': operation,
        'mode': element
            ? 'element'
            : formatContent
            ? 'content'
            : elements
            ? 'elements'
            : scalar
            ? 'scalar'
            : 'list',
        'isUrl': isUrl,
        'unescape': unescape,
        'baseUrl': context.variables['baseUrl'],
        'variables': {
          ...context.variables,
          ...Map<String, Object?>.from(
            context.variables['legacyVariables'] as Map? ?? {},
          ),
        },
      },
      {'__sourceTaskId': taskId, '__sourceHostCallback': false},
    ]);
    cancellation?.throwIfCancelled();
    if (response is! Map ||
        !response.containsKey('value') ||
        response['variables'] is! Map) {
      throw const EngineException(
        'invalid_legacy_rule_result',
        'Legacy host result requires value and variables',
      );
    }
    final written = response['variables'] as Map;
    if (written.entries.any((e) => e.key is! String || e.value is! String)) {
      throw const EngineException(
        'invalid_legacy_rule_result',
        'Legacy variables must be string values',
      );
    }
    for (final entry in written.entries) {
      await context.host.call('variables.put', [entry.key, entry.value]);
    }
    final value = response['value'];
    if (value == null) return [];
    if (scalar || element || formatContent) return [value];
    if (value is! List) {
      throw const EngineException(
        'invalid_legacy_rule_result',
        'Legacy list rule returned a non-list value',
      );
    }
    return List<Object?>.from(value);
  }

  static String _originalDialect(String rule) {
    // Imported CSS leaves use @legacy:. Strip it only at the start or after a
    // top-level composition operator, never inside CSS/JS quoted data.
    final out = StringBuffer();
    var start = true, depth = 0, escaped = false;
    String? quote;
    for (var i = 0; i < rule.length; i++) {
      final c = rule[i];
      if (escaped) {
        out.write(c);
        escaped = false;
        continue;
      }
      if (c == r'\') {
        out.write(c);
        escaped = true;
        continue;
      }
      if (quote != null) {
        out.write(c);
        if (c == quote) quote = null;
        continue;
      }
      if (c == '"' || c == "'") {
        out.write(c);
        quote = c;
        start = false;
        continue;
      }
      if (start && rule.substring(i).toLowerCase().startsWith('@legacy:')) {
        i += 7;
        start = false;
        continue;
      }
      if (c == '(' || c == '[' || c == '{') depth++;
      if (c == ')' || c == ']' || c == '}') depth--;
      if (depth == 0 &&
          (rule.startsWith('&&', i) ||
              rule.startsWith('||', i) ||
              rule.startsWith('%%', i))) {
        out.write(rule.substring(i, i + 2));
        i++;
        start = true;
        continue;
      }
      out.write(c);
      if (c.trim().isNotEmpty) start = false;
    }
    return out.toString();
  }
}
