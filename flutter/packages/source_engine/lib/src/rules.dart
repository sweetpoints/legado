import 'dart:convert';

import 'package:html/dom.dart';
import 'package:html/parser.dart' as html;
import 'package:json_path/json_path.dart';
import 'package:xpath_selector_html_parser/xpath_selector_html_parser.dart';

import 'contracts.dart';
import 'legacy_html.dart';

/// New-version rule execution. Legacy syntax is translated by source_legacy.
class RuleEvaluator {
  RuleEvaluator(this.runtime);
  final ScriptRuntime runtime;
  Future<List<Object?>> evaluate(
    String rule,
    Object? input,
    ScriptContext context, {
    CancellationToken? cancellation,
    bool elements = false,
  }) async {
    cancellation?.throwIfCancelled();
    // JS is an opaque expression: its operators are never rule separators.
    if (rule.toLowerCase().startsWith('@js:')) {
      final result = await runtime.evaluate(
        rule.substring(4),
        ScriptContext(
          variables: {...context.variables, 'result': _serialize(input)},
          host: context.host,
          timeout: context.timeout,
        ),
        cancellation: cancellation,
      );
      return result is List ? List<Object?>.from(result) : [?result];
    }
    final legacy = rule.toLowerCase().startsWith('@legacy:');
    String childRule(String part) =>
        legacy && !part.toLowerCase().startsWith('@legacy:')
        ? '@legacy:$part'
        : part;
    final fallback = _split(rule, '||');
    if (fallback.length > 1) {
      for (final part in fallback) {
        final found = await evaluate(
          childRule(part),
          input,
          context,
          cancellation: cancellation,
          elements: elements,
        );
        if (found.any((x) => x != null && x.toString().isNotEmpty)) {
          return found;
        }
      }
      return [];
    }
    final concat = _split(rule, '&&');
    if (concat.length > 1) {
      final values = <Object?>[];
      for (final part in concat) {
        values.addAll(
          await evaluate(
            childRule(part),
            input,
            context,
            cancellation: cancellation,
            elements: elements,
          ),
        );
      }
      return values;
    }
    if (legacy) {
      final interleave = _split(rule, '%%');
      if (interleave.length > 1) {
        final batches = <List<Object?>>[];
        for (final part in interleave) {
          final batch = await evaluate(
            childRule(part),
            input,
            context,
            cancellation: cancellation,
            elements: elements,
          );
          if (batch.isNotEmpty) batches.add(batch);
        }
        return [
          if (batches.isNotEmpty)
            for (var i = 0; i < batches.first.length; i++)
              for (final batch in batches)
                if (i < batch.length) batch[i],
        ];
      }
    }
    final replacements = _split(rule, '##');
    final selector = replacements.first.trim();
    if (replacements.length != 1 && replacements.length != 3) {
      throw const EngineException(
        'invalid_rule',
        'Replacement requires ##pattern##replacement',
      );
    }
    List<Object?> values;
    final lower = selector.toLowerCase();
    if (lower.startsWith('@legacy:')) {
      values = LegacyHtmlRule.evaluate(
        selector.substring(8),
        input,
        elements: elements,
      );
    } else if (lower.startsWith('@json:') || selector.startsWith(r'$')) {
      final data = input is String ? jsonDecode(input) : input;
      values = JsonPath(
        lower.startsWith('@json:') ? selector.substring(6) : selector,
      ).read(data).map((x) => x.value).toList();
    } else if (lower.startsWith('@xpath:')) {
      final root = HtmlXPath.html(_serialize(input).toString());
      final selected = root.query(selector.substring(7));
      values = selected.attrs.isNotEmpty
          ? selected.attrs.where((a) => a != null).toList()
          : selected.nodes
                .map<Object?>(
                  (x) => elements && x.node is Element ? x.node : x.text,
                )
                .toList();
    } else if (lower.startsWith('@regex:')) {
      values = RegExp(selector.substring(7), multiLine: true)
          .allMatches(_serialize(input).toString())
          .map((m) => m.group(m.groupCount > 0 ? 1 : 0))
          .toList();
    } else {
      var css = lower.startsWith('@css:') ? selector.substring(5) : selector;
      final at = css.lastIndexOf('@');
      var output = 'node';
      if (at >= 0) {
        output = css.substring(at + 1);
        css = css.substring(0, at);
      }
      final root = input is Element
          ? input
          : html.parse(_serialize(input).toString());
      final nodes = css.isEmpty ? [root] : root.querySelectorAll(css);
      values = nodes
          .map<Object?>(
            (e) => switch (output) {
              'text' => e.text,
              'html' => e is Element ? e.innerHtml : (e as Document).outerHtml,
              'node' => e,
              _ => e.attributes[output],
            },
          )
          .where((e) => e != null)
          .toList();
    }
    if (replacements.length == 3) {
      final regex = RegExp(replacements[1], multiLine: true);
      values = values
          .map(
            (v) => text(v).replaceAllMapped(
              regex,
              (m) => replacements[2].replaceAllMapped(RegExp(r'\$(\d+)'), (g) {
                final n = int.parse(g[1]!);
                return n <= m.groupCount ? m[n] ?? '' : '';
              }),
            ),
          )
          .toList();
    }
    return values;
  }

  static String text(Object? value) => value is Element
      ? value.text
      : value is Map || value is List
      ? jsonEncode(value)
      : value?.toString() ?? '';
  static Object? _serialize(Object? value) =>
      value is Element ? value.outerHtml : value;
  static List<String> _split(String input, String delimiter) {
    final out = <String>[];
    var start = 0;
    var depth = 0;
    String? quote;
    var escape = false;
    for (var i = 0; i < input.length; i++) {
      final c = input[i];
      if (escape) {
        escape = false;
        continue;
      }
      if (c == r'\') {
        escape = true;
        continue;
      }
      if (quote != null) {
        if (c == quote) quote = null;
        continue;
      }
      if (c == '"' || c == "'") {
        quote = c;
        continue;
      }
      if (c == '(' || c == '[' || c == '{') depth++;
      if (c == ')' || c == ']' || c == '}') depth--;
      if (depth == 0 && input.startsWith(delimiter, i)) {
        out.add(input.substring(start, i));
        i += delimiter.length - 1;
        start = i + 1;
      }
    }
    out.add(input.substring(start));
    return out;
  }
}
