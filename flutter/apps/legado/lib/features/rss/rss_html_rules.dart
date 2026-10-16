import 'package:html/parser.dart' as html;
import 'package:source_engine/source_engine.dart';

import 'rss_models.dart';

/// CSS/Legado traversal reuses the source engine's portable DOM evaluator.
/// Script expressions and browser verification remain explicit capabilities.
class RssHtmlRules {
  final _rules = RuleEvaluator(_NoScripts());
  final _context = const ScriptContext(host: _NoHost());
  static bool hasScript(String value) => RegExp(
    r'@(?:web)?js:|<js>|<script\b',
    caseSensitive: false,
  ).hasMatch(value);

  Future<List<Object?>> evaluate(
    String rule,
    Object? input, {
    bool elements = false,
  }) async {
    if (hasScript(rule) ||
        rule.contains('{{') ||
        rule.startsWith('//') ||
        rule.startsWith('@xpath:')) {
      throw const FormatException('rssScriptCapability');
    }
    if (rule.trim().isEmpty) return [];
    try {
      if ('##'.allMatches(rule).length == 1) rule = '$rule##';
      return await _rules.evaluate(
        '@legacy:$rule',
        input,
        _context,
        elements: elements,
      );
    } on FormatException {
      rethrow;
    } catch (_) {
      throw const FormatException('rssUnsupportedRule');
    }
  }

  Future<String> scalar(String rule, Object? input) async =>
      (await evaluate(rule, input)).map((e) => e.toString()).join('\n');

  List<({String title, String url})> categories(RssSource source) {
    final value = source.original['sortUrl']?.toString().trim() ?? '';
    if (value.isEmpty) return [(title: source.name, url: source.url)];
    if (hasScript(value)) throw const FormatException('rssScriptCapability');
    return value.split('\n').where((e) => e.trim().isNotEmpty).map((line) {
      final index = line.indexOf('::');
      return (
        title: index < 0 ? line.trim() : line.substring(0, index).trim(),
        url: index < 0 ? line.trim() : line.substring(index + 2).trim(),
      );
    }).toList();
  }

  String categoryUrl(RssSource source, String value, {int page = 1}) {
    var resolved = value.replaceAll('{{page}}', '$page');
    if (resolved.contains('{{') || hasScript(resolved)) {
      throw const FormatException('rssScriptCapability');
    }
    resolved = Uri.parse(source.url).resolve(resolved).toString();
    return httpUrl(resolved, Uri.parse(source.url));
  }

  Future<List<RssArticle>> articles(
    RssSource source,
    String document,
    Uri base,
  ) async {
    final original = source.original;
    final nodes = await evaluate(
      original['ruleArticles']?.toString() ?? '',
      document,
      elements: true,
    );
    final entries = <RssArticle>[];
    for (final node in nodes) {
      final link = httpUrl(
        await scalar(original['ruleLink']?.toString() ?? 'a@href', node),
        base,
      );
      if (link.isEmpty) throw const FormatException('rssInvalidArticleLink');
      final title = await scalar(
        original['ruleTitle']?.toString() ?? 'a@text',
        node,
      );
      final imageRule = original['ruleImage']?.toString() ?? '';
      final image = imageRule.isEmpty
          ? ''
          : httpUrl(await scalar(imageRule, node), base);
      entries.add(
        RssArticle(
          id: link,
          title: title,
          link: link,
          content: '',
          published: await scalar(
            original['rulePubDate']?.toString() ?? '',
            node,
          ),
          original: {
            'rssDetailPending': true,
            if (image.isNotEmpty) 'rssImages': [image],
          },
        ),
      );
    }
    return entries;
  }

  Future<String> nextPage(RssSource source, String document, Uri base) async {
    final rule = source.original['ruleNextPage']?.toString() ?? '';
    if (rule.isEmpty) return '';
    return httpUrl(await scalar(rule, document), base);
  }

  Future<RssArticle> content(
    RssSource source,
    RssArticle article,
    String document,
    Uri base,
  ) async {
    final rule = source.original['ruleContent']?.toString() ?? '';
    if (hasScript(rule)) throw const FormatException('rssScriptCapability');
    String content;
    if (rule.contains('{{')) {
      final buffer = StringBuffer();
      var end = 0;
      for (final match in RegExp(
        r'\{\{@@(.*?)\}\}',
        dotAll: true,
      ).allMatches(rule)) {
        buffer.write(rule.substring(end, match.start));
        buffer.write(await scalar(match[1]!, document));
        end = match.end;
      }
      buffer.write(rule.substring(end));
      content = buffer.toString();
      if (content.contains('{{')) {
        throw const FormatException('rssScriptCapability');
      }
    } else {
      content = rule.isEmpty ? document : await scalar(rule, document);
    }
    final fragment = html.parseFragment(content);
    for (final node in fragment.querySelectorAll(
      'script, style, iframe, object, embed',
    )) {
      node.remove();
    }
    final images = <String>{};
    for (final image in fragment.querySelectorAll('img')) {
      final url = httpUrl(
        image.attributes['data-original'] ?? image.attributes['src'] ?? '',
        base,
      );
      if (url.isNotEmpty) images.add(url);
    }
    return article.copyWith(
      content: fragment.outerHtml,
      original: {
        ...article.original,
        'rssDetailPending': false,
        'rssImages': images.toList(),
      },
    );
  }

  static String httpUrl(String value, Uri base) {
    if (value.trim().isEmpty) return '';
    final uri = base.resolve(value.trim());
    return ['http', 'https'].contains(uri.scheme) && uri.host.isNotEmpty
        ? uri.toString()
        : '';
  }
}

class _NoScripts implements ScriptRuntime {
  @override
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  }) async => throw const FormatException('rssScriptCapability');
  @override
  Future<void> close() async {}
}

class _NoHost implements ScriptHost {
  const _NoHost();
  @override
  Future<Object?> call(String method, List<Object?> arguments) async =>
      throw const FormatException('rssScriptCapability');
}
