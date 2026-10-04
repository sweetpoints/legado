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
              .map((t) => _normalTextNode(t.data))
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
    final bracketMatch = RegExp(r'\[(!?)([-\d,: ]+)\]$').firstMatch(selector);
    if (bracketMatch != null) {
      bracket = true;
      exclude = bracketMatch[1] == '!';
      indices = bracketMatch[2];
      selector = selector.substring(0, bracketMatch.start);
    } else {
      final match = RegExp(
        r'([.!])((?: *-? *(?:[0-9] *)+)(?:: *-? *(?:[0-9] *)+)*)$',
      ).firstMatch(selector);
      if (match != null) {
        exclude = match[1] == '!';
        // The original reverse scanner skips ASCII spaces only within indices.
        indices = match[2]!.replaceAll(' ', '');
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
  // Jsoup 1.23.2 Element.preserveWhitespace examines the parent and five
  // ancestors. Preservation belongs to each text node, not the whole document.
  static const _preserving = {
    'pre',
    'plaintext',
    'title',
    'textarea',
    'script',
  };
  static const _textBoundaries = {
    'button',
    'input',
    'select',
    'textarea',
    'option',
    'output',
    'progress',
    'meter',
    'img',
    'picture',
    'audio',
    'video',
    'canvas',
    'object',
    'embed',
    'iframe',
  };
  static bool _preserve(Node? node) {
    for (
      var level = 0;
      level < 6 && node is Element;
      level++, node = node.parentNode
    ) {
      if (_preserving.contains(node.localName)) return true;
    }
    return false;
  }

  static String _javaTrim(String value) => value
      .replaceFirst(RegExp(r'^[\x00-\x20]+'), '')
      .replaceFirst(RegExp(r'[\x00-\x20]+$'), '');
  static bool _lastSpace(_TextAccumulator buffer) => buffer.lastSpace;
  static void _appendNormalized(_TextAccumulator buffer, String value) {
    var lastWhite = false;
    var reachedNonWhite = false;
    final stripLeading = _lastSpace(buffer);
    for (final rune in value.runes) {
      if ([32, 9, 10, 12, 13, 160].contains(rune)) {
        if ((stripLeading && !reachedNonWhite) || lastWhite) continue;
        buffer.write(' ');
        lastWhite = true;
      } else if (rune != 8203 && rune != 173) {
        buffer.writeCharCode(rune);
        lastWhite = false;
        reachedNonWhite = true;
      }
    }
  }

  static String _normalTextNode(String value) {
    final buffer = _TextAccumulator();
    _appendNormalized(buffer, value);
    return _javaTrim(buffer.toString());
  }

  static void _appendText(_TextAccumulator buffer, Text node) {
    if (_preserve(node.parentNode)) {
      buffer.write(node.data);
    } else {
      _appendNormalized(buffer, node.data);
    }
  }

  static String _ownText(Element e) {
    final buffer = _TextAccumulator();
    for (final child in e.nodes) {
      if (child is Text) {
        _appendText(buffer, child);
      } else if (child is Element &&
          child.localName == 'br' &&
          !_lastSpace(buffer)) {
        buffer.write(' ');
      }
    }
    return _javaTrim(buffer.toString());
  }

  static String _text(Element e) {
    final buffer = _TextAccumulator();
    bool hasText(Element element) => element.nodes.any(
      (node) => node is Text
          ? node.data.trim().isNotEmpty
          : node is Element && hasText(node),
    );
    void visit(Node node) {
      if (node is Text) {
        _appendText(buffer, node);
        return;
      }
      if (node is! Element ||
          node.localName == 'script' ||
          node.localName == 'style') {
        return;
      }
      final boundary = _textBoundaries.contains(node.localName);
      if (buffer.isNotEmpty &&
          (_blocks.contains(node.localName) ||
              node.localName == 'br' ||
              (boundary && node.nodes.isNotEmpty && hasText(node))) &&
          !_lastSpace(buffer)) {
        buffer.write(' ');
      }
      for (final child in node.nodes) {
        visit(child);
      }
      final siblings = node.parentNode?.nodes;
      final index = siblings?.indexOf(node) ?? -1;
      final next = siblings != null && index >= 0 && index + 1 < siblings.length
          ? siblings[index + 1]
          : null;
      final trailing =
          boundary ||
          _blocks.contains(node.localName) ||
          node.children.any((c) => _blocks.contains(c.localName));
      if (trailing &&
          (next is Text ||
              next is Element && !_blocks.contains(next.localName)) &&
          !_lastSpace(buffer)) {
        buffer.write(' ');
      }
    }

    visit(e);
    return _javaTrim(buffer.toString());
  }
}

/// Tracks the last character without repeatedly materializing a long chapter.
class _TextAccumulator {
  final _buffer = StringBuffer();
  bool lastSpace = false;
  bool get isNotEmpty => _buffer.isNotEmpty;
  void write(String value) {
    if (value.isEmpty) return;
    _buffer.write(value);
    lastSpace = value.endsWith(' ');
  }

  void writeCharCode(int code) {
    _buffer.writeCharCode(code);
    lastSpace = code == 32;
  }

  @override
  String toString() => _buffer.toString();
}
