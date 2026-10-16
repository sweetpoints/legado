import 'package:html/dom.dart' show Element;

import 'package:source_engine/source_engine.dart';

/// Portable old-rule pipeline, deliberately excluding browser/native hosts.
/// The script suffix is opaque after its first delimiter; JS strings containing
/// rule punctuation never re-enter the rule parser.
class PortableLegacyRuleEvaluator implements LegacyTocFanOutEvaluator {
  const PortableLegacyRuleEvaluator(this.runtime, {this.scriptCapability});
  final bool Function(String)? scriptCapability;
  final ScriptRuntime runtime;

  @override
  bool supportsRule(String rule) =>
      canEvaluate(rule, scriptCapability: scriptCapability);

  static bool canEvaluate(
    String rule, {
    bool Function(String)? scriptCapability,
  }) {
    try {
      _Plan.parse(rule, scriptCapability: scriptCapability);
      return true;
    } on EngineException {
      return false;
    }
  }

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
    bool resolveCover = false,
    bool scalar = false,
    bool isUrl = false,
    bool unescape = true,
    CancellationToken? cancellation,
  }) async {
    final plan = _Plan.parse(rule, scriptCapability: scriptCapability);
    cancellation?.throwIfCancelled();
    var values = plan.selector.isEmpty
        ? <Object?>[input]
        : await RuleEvaluator(runtime).evaluate(
            plan.selector,
            input,
            context,
            elements: elements && plan.script == null,
            cancellation: cancellation,
          );
    if (scalar && !elements && !element) {
      values = [values.map(RuleEvaluator.text).join('\n')];
    }
    if (plan.pattern != null) {
      values = values.map((value) {
        final text = RuleEvaluator.text(value);
        if (plan.firstMatch) {
          final match = plan.pattern!.firstMatch(text);
          return match == null ? '' : _replacement(plan.replacement, match);
        }
        return text.replaceAllMapped(
          plan.pattern!,
          (match) => _replacement(plan.replacement, match),
        );
      }).toList();
    }
    if (plan.script != null) {
      final value = values.length == 1 ? values.single : values;
      final scriptContext = ScriptContext(
        variables: {
          ...context.variables,
          'result': _serialize(value),
          'src': _serialize(input),
        },
        host: context.host,
        timeout: context.timeout,
      );
      final Object? output;
      if (RegExp(r'^\s*return\b').hasMatch(plan.script!)) {
        output = await runtime.evaluate(
          plan.script!,
          scriptContext,
          cancellation: cancellation,
        );
      } else if (runtime case final AuxiliaryScriptRuntime auxiliary) {
        output = await auxiliary.evaluateAuxiliary(
          plan.script!,
          scriptContext,
          cancellation: cancellation,
        );
      } else {
        throw const EngineException(
          'unsupported_runtime',
          'Legacy program completion requires auxiliary runtime evaluation',
        );
      }
      cancellation?.throwIfCancelled();
      values = output is List ? List<Object?>.from(output) : [?output];
    }
    if (elements || element) return values;
    return values.map((value) {
      var text = RuleEvaluator.text(value);
      if (unescape && !formatContent) text = unescapeHtml4(text);
      if ((isUrl || resolveCover) && text.isNotEmpty) {
        final split = RegExp(r'\s*,\s*(?=\{)').firstMatch(text);
        final address = split == null ? text : text.substring(0, split.start);
        final base =
            context.variables['baseUrl']?.toString() ??
            source.baseUrl.toString();
        text =
            '${Uri.parse(base).resolve(address)}${split == null ? '' : text.substring(split.start)}';
      }
      return text;
    }).toList();
  }
}

class _Plan {
  const _Plan(
    this.selector,
    this.pattern,
    this.replacement,
    this.script, {
    this.firstMatch = false,
  });
  final bool firstMatch;
  final String selector;
  final RegExp? pattern;
  final String replacement;
  final String? script;

  static Never invalid(String message) =>
      throw EngineException('unsupported_legacy_rule', message);
  static _Plan parse(String raw, {bool Function(String)? scriptCapability}) {
    var rule = raw.trim();
    if (rule.startsWith('@legacy:')) rule = rule.substring(8);
    if (rule.startsWith('@css:')) rule = rule.substring(5);
    if (rule.startsWith('<js>')) {
      if (!rule.endsWith('</js>')) invalid('Missing </js> rule terminator');
      return _Plan(
        '',
        null,
        '',
        _script(rule.substring(4, rule.length - 5), scriptCapability),
      );
    }
    var scriptAt = -1;
    String? quote;
    var escaped = false;
    var brackets = 0;
    var regexMode = false;
    for (var i = 0; i < rule.length; i++) {
      final c = rule[i];
      if (escaped) {
        escaped = false;
        continue;
      }
      if (c == r'\') {
        escaped = true;
        continue;
      }
      if (quote != null) {
        if (c == quote) quote = null;
        continue;
      }
      if (!regexMode && (c == '"' || c == "'")) {
        quote = c;
        continue;
      }
      if (c == '[') brackets++;
      if (c == ']') brackets--;
      if (brackets < 0) invalid('Unbalanced rule selector');
      if (brackets == 0 && rule.startsWith('@js:', i)) {
        scriptAt = i;
        break;
      }
      if (brackets == 0 && rule.startsWith('##', i)) {
        regexMode = true;
        i++;
      }
    }
    if (quote != null || brackets != 0 || escaped) {
      invalid('Incomplete rule selector');
    }
    String? script;
    if (scriptAt >= 0) {
      script = _script(rule.substring(scriptAt + 4), scriptCapability);
      rule = rule.substring(0, scriptAt).trimRight();
    }
    if (RegExp(
      r'@(?:put|get|webjs):|\{\{|</?js>',
      caseSensitive: false,
    ).hasMatch(rule)) {
      invalid('Rule requires an unimplemented legacy template/host');
    }
    final parts = <String>[];
    var start = 0;
    escaped = false;
    brackets = 0;
    quote = null;
    for (var i = 0; i < rule.length; i++) {
      final c = rule[i];
      if (escaped) {
        escaped = false;
        continue;
      }
      if (c == r'\') {
        escaped = true;
        continue;
      }
      if (quote != null) {
        if (c == quote) quote = null;
        continue;
      }
      if (parts.isEmpty && (c == '"' || c == "'")) {
        quote = c;
        continue;
      }
      if (c == '[') brackets++;
      if (c == ']') brackets--;
      if (brackets == 0 && rule.startsWith('##', i)) {
        parts.add(rule.substring(start, i));
        start = i + 2;
        i++;
      }
    }
    parts.add(rule.substring(start));
    if (parts.length > 3 && !(parts.length == 4 && parts.last == '#')) {
      invalid(
        'First-match/extra replacement delimiters require separate implementation',
      );
    }
    if (parts.length == 4 && RegExp(r'\(\?(?:[=!]|<[=!])').hasMatch(parts[1])) {
      invalid('First-match lookaround requires original Java matcher context');
    }
    final selector = parts.first.trim();
    if (selector.startsWith('@') &&
        !selector.startsWith('@json:') &&
        !selector.startsWith('@xpath:') &&
        !const [
          '@text',
          '@ownText',
          '@textNodes',
          '@html',
          '@all',
          '@children',
        ].contains(selector)) {
      invalid('Unknown legacy rule token');
    }
    RegExp? pattern;
    if (parts.length > 1) {
      if (parts[1].isEmpty ||
          RegExp(r'\(\?[a-zA-Z>]|\(\?<(?!!|=)|\\[pPQERX]|[+*?]\+')
              .hasMatch(parts[1])) {
        invalid('Regex dialect requires Java-specific review');
      }
      try {
        pattern = RegExp(parts[1]);
      } on FormatException {
        invalid('Malformed legacy replacement regex');
      }
    }
    return _Plan(
      selector.isEmpty
          ? ''
          : (selector.startsWith('@') || selector.startsWith(r'$')
                ? selector
                : '@legacy:$selector'),
      pattern,
      parts.length > 2 ? parts[2] : '',
      script,
      firstMatch: parts.length == 4,
    );
  }

  static String _script(
    String script,
    bool Function(String)? scriptCapability,
  ) {
    if (script.trim().isEmpty) invalid('Empty JavaScript rule');
    if (scriptCapability != null) {
      if (!scriptCapability(script)) {
        invalid('Script requires unavailable hosted capabilities');
      }
      return script;
    }
    // This first portable stage supports pure V8 transforms; host-dependent
    // scripts retain their importer issue instead of silently losing bindings.
    final outside = StringBuffer();
    String? quote;
    var escaped = false;
    for (var i = 0; i < script.length; i++) {
      final c = script[i];
      if (quote != null) {
        if (escaped) {
          escaped = false;
        } else if (c == r'\') {
          escaped = true;
        } else if (c == quote) {
          quote = null;
        }
        if (quote == '`' &&
            c == r'$' &&
            i + 1 < script.length &&
            script[i + 1] == '{') {
          invalid(
            'Template interpolation requires complete script host analysis',
          );
        }
        continue;
      }
      if (c == '"' || c == "'" || c == '`') {
        quote = c;
        outside.write(' ');
        continue;
      }
      outside.write(c);
    }
    if (quote != null) invalid('Unterminated JavaScript string');
    if (RegExp(
      r'\b(?:java|org|Packages|source|sourceApi|book|chapter|cookie|cache|eval|Function|globalThis|global|this|self)\b|<js>|@webjs:',
      caseSensitive: false,
    ).hasMatch(outside.toString())) {
      invalid('JavaScript requires an unimplemented legacy host');
    }
    return script;
  }
}

String _replacement(String replacement, Match match) {
  final out = StringBuffer();
  for (var i = 0; i < replacement.length; i++) {
    final c = replacement[i];
    if (c == r'\') {
      if (++i >= replacement.length) {
        throw const EngineException(
          'invalid_legacy_replacement',
          'Unterminated replacement escape',
        );
      }
      out.write(replacement[i]);
    } else if (c == r'$') {
      if (i + 1 >= replacement.length ||
          !RegExp(r'[0-9]').hasMatch(replacement[i + 1])) {
        throw const EngineException(
          'invalid_legacy_replacement',
          'Expected numeric Java replacement group',
        );
      }
      var group = int.parse(replacement[++i]);
      while (i + 1 < replacement.length &&
          RegExp(r'[0-9]').hasMatch(replacement[i + 1])) {
        final next = group * 10 + int.parse(replacement[i + 1]);
        if (next > match.groupCount) break;
        group = next;
        i++;
      }
      if (group > match.groupCount) {
        throw const EngineException(
          'invalid_legacy_replacement',
          'Replacement group does not exist',
        );
      }
      out.write(match[group] ?? '');
    } else {
      out.write(c);
    }
  }
  return out.toString();
}

Object? _serialize(Object? value) => switch (value) {
  Element() => value.outerHtml,
  List() => value.map(_serialize).toList(),
  Map() => value.map((key, value) => MapEntry(key, _serialize(value))),
  _ => value,
};
