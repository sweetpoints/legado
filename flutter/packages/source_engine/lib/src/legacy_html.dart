import 'package:html/dom.dart';
import 'package:html/parser.dart' as html;

import 'contracts.dart';

/// Explicit supported subset of Legado's JSoup traversal dialect.
/// Selector syntax not supported by Dart's CSS selector implementation throws.
class LegacyHtmlRule {
  static const outputs = {'text', 'textNodes', 'ownText', 'html', 'all'};
  static List<Object?> evaluate(
    String rule,
    Object? input, {
    bool elements = false,
  }) {
    final root = input is Element
        ? input
        : html.parse(input?.toString() ?? '').documentElement!;
    final chain = _split(rule.trim().replaceFirst(RegExp(r'^@+'), ''));
    if (chain.isEmpty) return [];
    var nodes = <Element>[root];
    var output = chain.last;
    // List mode traverses all segments; scalar mode always extracts the last.
    final extract = !elements;
    final selectors = extract ? chain.take(chain.length - 1) : chain;
    for (final selector in selectors) {
      nodes = [for (final node in nodes) ..._select(node, selector)];
    }
    if (!extract) return nodes;
    if (output == 'html' || output == 'all') {
      final clones = nodes.map((e) => e.clone(true)).toList();
      if (output == 'html') {
        for (final clone in clones) {
          for (final e in clone.querySelectorAll('script, style')) {
            e.remove();
          }
        }
      }
      final joined = clones.map((e) => e.outerHtml).join('\n');
      return output == 'all' || joined.isNotEmpty ? [joined] : [];
    }
    final values = <String>[];
    for (final node in nodes) {
      final value = switch (output) {
        'text' => _text(node),
        'ownText' => _ownText(node),
        'textNodes' =>
          node.nodes
              .whereType<Text>()
              .map((t) => t.data.trim())
              .where((s) => s.isNotEmpty)
              .join('\n'),
        _ => node.attributes[output] ?? '',
      };
      if (value.isEmpty ||
          (!outputs.contains(output) && value.trim().isEmpty)) {
        continue;
      }
      if (outputs.contains(output) || !values.contains(value)) {
        values.add(value);
      }
    }
    return values;
  }

  static List<String> _split(String value) {
    final parts = <String>[];
    var start = 0, depth = 0;
    String? quote;
    for (var i = 0; i < value.length; i++) {
      final c = value[i];
      if (quote != null) {
        if (c == quote && (i == 0 || value[i - 1] != r'\')) quote = null;
        continue;
      }
      if (c == '"' || c == "'") {
        quote = c;
        continue;
      }
      if (c == '[' || c == '(') depth++;
      if (c == ']' || c == ')') depth--;
      if (c == '@' && depth == 0) {
        parts.add(value.substring(start, i));
        start = i + 1;
      }
    }
    parts.add(value.substring(start));
    return parts.where((p) => p.isNotEmpty).toList();
  }

  static List<Element> _select(Element root, String rule) {
    var selector = rule.trim();
    String? indices;
    var exclude = false;
    var bracket = false;
    final bracketMatch = RegExp(r'\[(!?)([-\d,:\s]+)\]$').firstMatch(selector);
    if (bracketMatch != null) {
      bracket = true;
      exclude = bracketMatch[1] == '!';
      indices = bracketMatch[2];
      selector = selector.substring(0, bracketMatch.start);
    } else {
      final match = RegExp(r'([.!])(-?\d+(?::-?\d+)*)$').firstMatch(selector);
      if (match != null) {
        exclude = match[1] == '!';
        indices = match[2];
        selector = selector.substring(0, match.start);
      }
    }
    final all = [root, ...root.querySelectorAll('*')];
    List<Element> nodes;
    if (selector.isEmpty || selector == 'children') {
      nodes = root.children.toList();
    } else if (selector.startsWith('class.')) {
      final name = selector.substring(6);
      nodes = all.where((e) => e.classes.contains(name)).toList();
    } else if (selector.startsWith('tag.')) {
      final name = selector.substring(4).toLowerCase();
      nodes = all.where((e) => e.localName == name).toList();
    } else if (selector.startsWith('id.')) {
      final id = selector.substring(3);
      nodes = all.where((e) => e.id == id).toList();
    } else if (selector.startsWith('text.')) {
      final text = selector.substring(5).toLowerCase();
      nodes = all
          .where((e) => _ownText(e).toLowerCase().contains(text))
          .toList();
    } else {
      nodes = root.querySelectorAll(selector);
      final wrapper = Element.tag('legacy-wrapper')..append(root.clone(true));
      if (wrapper.querySelectorAll(selector).contains(wrapper.children.first)) {
        nodes.insert(0, root);
      }
    }
    if (indices == null) return nodes;
    final selected = <int>{};
    final length = nodes.length;
    int normalize(int n) => n < 0 ? n + length : n;
    for (final token in indices.split(bracket ? ',' : ':')) {
      if (!bracket || !token.contains(':')) {
        final index = normalize(int.parse(token.trim()));
        if (index >= 0 && index < length) selected.add(index);
        continue;
      }
      final parts = token.split(':');
      if (parts.length > 3) {
        throw const EngineException(
          'invalid_rule',
          'Index range has too many components',
        );
      }
      int endpoint(String part, int fallback) =>
          part.trim().isEmpty ? fallback : normalize(int.parse(part.trim()));
      var start = endpoint(parts[0], 0), end = endpoint(parts[1], length - 1);
      if (length == 0 ||
          (start < 0 && end < 0) ||
          (start >= length && end >= length)) {
        continue;
      }
      start = start.clamp(0, length - 1);
      end = end.clamp(0, length - 1);
      var step = parts.length == 3 ? int.parse(parts[2].trim()) : 1;
      if (step == 0) step = length;
      if (step < 0) step = (-step < length) ? step + length : 1;
      if (start <= end) {
        for (var i = start; i <= end; i += step) {
          selected.add(i);
        }
      } else {
        for (var i = start; i >= end; i -= step) {
          selected.add(i);
        }
      }
    }
    return exclude
        ? [
            for (var i = 0; i < length; i++)
              if (!selected.contains(i)) nodes[i],
          ]
        : [for (final i in selected) nodes[i]];
  }

  static const _blocks = {
    'address',
    'article',
    'aside',
    'blockquote',
    'div',
    'dl',
    'dt',
    'dd',
    'fieldset',
    'figcaption',
    'figure',
    'footer',
    'form',
    'h1',
    'h2',
    'h3',
    'h4',
    'h5',
    'h6',
    'header',
    'hr',
    'li',
    'main',
    'nav',
    'ol',
    'p',
    'pre',
    'section',
    'table',
    'tr',
    'td',
    'ul',
  };
  static String _normalize(String text) =>
      text.replaceAll(RegExp(r'[\s\u00a0]+'), ' ').trim();
  static String _ownText(Element e) => _normalize(
    e.nodes
        .map(
          (n) => n is Text
              ? n.data
              : n is Element && n.localName == 'br'
              ? ' '
              : '',
        )
        .join(),
  );
  static String _text(Element e) {
    final buffer = StringBuffer();
    void visit(Node node) {
      if (node is Text) {
        buffer.write(node.data);
      } else if (node is Element) {
        if (node.localName == 'script' || node.localName == 'style') return;
        if (_blocks.contains(node.localName) || node.localName == 'br') {
          buffer.write(' ');
        }
        for (final child in node.nodes) {
          visit(child);
        }
        if (_blocks.contains(node.localName)) buffer.write(' ');
      }
    }

    visit(e);
    return _normalize(buffer.toString());
  }
}
