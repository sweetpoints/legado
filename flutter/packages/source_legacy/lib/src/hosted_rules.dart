import 'package:source_engine/source_engine.dart';

import 'portable_rules.dart';
import 'portable_source.dart';
import 'portable_dom.dart';

/// Adds only implemented source/HTTP/DOM capabilities to the portable grammar.
class HostedLegacyRuleEvaluator extends PortableLegacyRuleEvaluator {
  HostedLegacyRuleEvaluator(super.runtime, this.capabilities)
    : super(scriptCapability: capabilities.supports);
  final PortableLegacyScriptCapabilities capabilities;
  static final _template = RegExp(r'@get:\{(\w+)\}|\{\{(@[^{}]+)\}\}');
  @override
  bool supportsRule(String rule) {
    var raw = rule.trim();
    if (raw.startsWith('@legacy:')) raw = raw.substring(8);
    if (_template.hasMatch(raw)) {
      final remainder = raw.replaceAll(_template, '');
      return !RegExp(
        r'@(?:get|put|js|webjs):|\{\{|##',
        caseSensitive: false,
      ).hasMatch(remainder);
    }
    return super.supportsRule(rule);
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
    final wrapped = ScriptContext(
      variables: context.variables,
      host: _ScopedHost(context),
      timeout: context.timeout,
    );
    var raw = rule.trim();
    if (raw.startsWith('@legacy:')) raw = raw.substring(8);
    if (_template.hasMatch(raw)) {
      if (!supportsRule(rule)) {
        throw const EngineException(
          'unsupported_legacy_rule',
          'Legacy rule template contains unimplemented tokens',
        );
      }
      final out = StringBuffer();
      var at = 0;
      for (final match in _template.allMatches(raw)) {
        out.write(raw.substring(at, match.start));
        if (match[1] != null) {
          out.write(await wrapped.host.call('java.get', [match[1]]));
        } else {
          final selector = match[2]!.substring(1);
          final values = await super.evaluate(
            '@legacy:$selector',
            input,
            wrapped,
            source: source,
            operation: operation,
            scalar: true,
            unescape: unescape,
            cancellation: cancellation,
          );
          out.write(values.map(RuleEvaluator.text).join('\n'));
        }
        at = match.end;
      }
      out.write(raw.substring(at));
      cancellation?.throwIfCancelled();
      return [out.toString()];
    }
    final result = await super.evaluate(
      rule,
      input,
      wrapped,
      source: source,
      operation: operation,
      elements: elements,
      element: element,
      formatContent: formatContent,
      resolveCover: resolveCover,
      scalar: scalar,
      isUrl: isUrl,
      unescape: unescape,
      cancellation: cancellation,
    );
    if (elements || element) {
      final materialized = <Object?>[];
      for (final value in result) {
        materialized.addAll(
          PortableLegacyDomHost.restoreMarker(value) ?? [value],
        );
      }
      return materialized;
    }
    return result;
  }
}

class _ScopedHost implements ScriptHost {
  _ScopedHost(this.context);
  final ScriptContext context;
  @override
  Future<Object?> call(String method, List<Object?> args) async {
    if ((method == 'java.getElements' || method == 'java.getElement') &&
        args.length >= 2 &&
        args.first is String) {
      return PortableLegacyDomHost.legacyElements(
        args.first as String,
        args[1],
        single: method == 'java.getElement',
      );
    }
    final scope = context.variables['legacyVariableScope'];
    if (scope is Map &&
        (method == 'java.get' || method == 'java.put') &&
        args.isNotEmpty &&
        args.first is String &&
        (method == 'java.put' ? args.length == 2 : args.length == 1)) {
      final key = args.first as String;
      if (method == 'java.put') {
        final target = scope[scope['target']];
        if (target is! Map) {
          throw const EngineException(
            'invalid_legacy_variable_scope',
            'Missing legacy entity scope',
          );
        }
        final value = args[1]?.toString() ?? '';
        target[key] = value;
        return value;
      }
      for (final layer in ['chapter', 'book', 'source']) {
        final value = (scope[layer] as Map?)?[key];
        if (value is String && value.isNotEmpty) return value;
      }
    }
    return context.host.call(method, args);
  }
}
