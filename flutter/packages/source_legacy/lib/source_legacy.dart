/// Conservative compatibility adapters for historical Legado sources.
library;

import 'dart:convert';

import 'src/legacy_host.dart';
export 'src/legacy_host.dart';

import 'package:source_engine/source_engine.dart';

class LegacyIssue {
  const LegacyIssue(this.path, this.code, this.message);
  final String path;
  final String code;
  final String message;
  Map<String, Object?> toJson() => {
    'path': path,
    'code': code,
    'message': message,
  };
}

class LegacyImport {
  LegacyImport(this.original, this.source, this.issues);
  final Map<String, Object?> original;
  final SourceDefinition source;
  final List<LegacyIssue> issues;
  bool get requiresManualWork => issues.isNotEmpty;
}

/// Imports the structural format without claiming every legacy semantic works.
class LegacySourceImporter {
  LegacyImport import(Map<String, Object?> input) {
    final original = Map<String, Object?>.from(
      jsonDecode(jsonEncode(input)) as Map,
    );
    final issues = <LegacyIssue>[];
    final base = Uri.tryParse(input['bookSourceUrl']?.toString() ?? '');
    if (base == null ||
        !['http', 'https'].contains(base.scheme) ||
        base.host.isEmpty) {
      throw const FormatException(
        'bookSourceUrl must be an absolute HTTP(S) URL',
      );
    }
    final requestHeaders = <String, String>{};
    if (input['header'] != null && input['header'] != '') {
      try {
        final raw = input['header'] is String
            ? jsonDecode(input['header'] as String)
            : input['header'];
        if (raw is! Map ||
            raw.entries.any((e) => e.key is! String || e.value is! String)) {
          throw const FormatException(
            'Static headers must be a string-to-string object',
          );
        }
        requestHeaders.addAll(Map<String, String>.from(raw));
      } on FormatException {
        issues.add(
          const LegacyIssue(
            'header',
            'legacy.dynamic_header',
            'Dynamic or invalid headers require migration.',
          ),
        );
      }
    }
    final stages = <String, SourceStage>{};
    final mapping = {
      'search': ('ruleSearch', 'searchUrl', 'bookList'),
      'explore': ('ruleExplore', 'exploreUrl', 'bookList'),
      'info': ('ruleBookInfo', null, null),
      'toc': ('ruleToc', null, 'chapterList'),
      'content': ('ruleContent', null, null),
    };
    for (final entry in mapping.entries) {
      final (ruleKey, urlKey, listKey) = entry.value;
      final raw = input[ruleKey];
      if (raw == null) continue;
      if (raw is! Map) {
        issues.add(
          LegacyIssue(
            ruleKey,
            'legacy.invalid_rule_object',
            'Expected a rule object.',
          ),
        );
        continue;
      }
      final fields = <String, String>{};
      String? list;
      String? nextPage;
      for (final rule in raw.entries) {
        if (rule.value == null || rule.value == '') continue;
        if (rule.value is! String) {
          issues.add(
            LegacyIssue(
              '$ruleKey.${rule.key}',
              'legacy.invalid_rule',
              'Rule must be a string.',
            ),
          );
          continue;
        }
        if ([
          'init',
          'checkKeyWord',
          'webJs',
          'sourceRegex',
          'replaceRegex',
          'imageStyle',
          'imageDecode',
          'payAction',
        ].contains(rule.key)) {
          issues.add(
            LegacyIssue(
              '$ruleKey.${rule.key}',
              'legacy.pipeline_requires_review',
              'This legacy pipeline hook or pagination behavior requires explicit implementation.',
            ),
          );
        }
        var text = rule.value as String;
        if (text.toLowerCase().startsWith('@css:')) {
          text = '@legacy:${text.substring(5)}';
        } else if ([
          '@text',
          '@ownText',
          '@textNodes',
          '@html',
          '@all',
          '@children',
        ].contains(text)) {
          text = '@legacy:$text';
        }
        if (!text.startsWith('@') &&
            !text.startsWith(r'$') &&
            !text.startsWith('//') &&
            !text.startsWith(':')) {
          text = '@legacy:$text';
        }
        if (text.startsWith(':')) {
          issues.add(
            LegacyIssue(
              '$ruleKey.${rule.key}',
              'legacy.regex_mode',
              'All-in-one legacy regex mode requires review.',
            ),
          );
        }
        final simpleLegacyScript =
            _simpleExtractionScript(text) ||
            RegExp(
              r'^@js:\s*(?:return\s+)?java\.(?:ajax|ajaxAll|connect|get|post|head|put|base64Encode|base64Decode|base64DecodeToByteArray|strToBytes|bytesToStr|hexDecodeToByteArray|hexDecodeToString|hexEncodeToString|md5Encode|md5Encode16|digestHex|digestBase64Str|encodeURI)\([^()]*\)\s*;?\s*$',
            ).hasMatch(text);
        if (!simpleLegacyScript &&
            RegExp(
              r'@js:|<js>|@webjs:|@put:|@get:|##|&&|\|\||%%|\{\{|^//|^@XPath:',
              caseSensitive: false,
            ).hasMatch(text)) {
          issues.add(
            LegacyIssue(
              '$ruleKey.${rule.key}',
              'legacy.rule_requires_review',
              'Compound, script, XPath, or variable rules require semantic review.',
            ),
          );
        }
        if (rule.key == 'nextTocUrl' || rule.key == 'nextContentUrl') {
          nextPage = text;
        } else if (rule.key == listKey) {
          list = text;
        } else {
          final fieldName = entry.key == 'toc'
              ? switch (rule.key) {
                  'chapterName' => 'title',
                  'chapterUrl' => 'url',
                  _ => rule.key.toString(),
                }
              : rule.key == 'lastChapter'
              ? 'latestChapterTitle'
              : rule.key.toString();
          fields[fieldName] = text;
        }
      }
      var url = urlKey == null
          ? switch (entry.key) {
              'info' => '{{bookUrl}}',
              'toc' => '{{tocUrl}}',
              _ => '{{chapterUrl}}',
            }
          : input[urlKey]?.toString() ?? '';
      if (url.isEmpty) {
        issues.add(
          LegacyIssue(
            urlKey ?? entry.key,
            'legacy.missing_url',
            'Stage URL is missing.',
          ),
        );
      }
      final request = _legacyRequest(
        url,
        urlKey ?? entry.key,
        requestHeaders,
        issues,
      );
      url = request.url;
      stages[entry.key] = SourceStage(
        url: url,
        list: list,
        fields: fields,
        nextPage: nextPage,
        method: request.method,
        body: request.body,
        headers: request.headers,
      );
    }
    for (final key in [
      'mainJs',
      'jsLib',
      'loginUrl',
      'loginUi',
      'loginCheckJs',
      'coverDecodeJs',
      'concurrentRate',
      'exploreScreen',
      'ruleReview',
    ]) {
      if (input[key] != null && input[key] != '') {
        issues.add(
          LegacyIssue(
            key,
            'legacy.capability_requires_review',
            'Preserved in original; this capability is not converted automatically.',
          ),
        );
      }
    }
    if (input.containsKey('enabledCookieJar') &&
        input['enabledCookieJar'] != true) {
      issues.add(
        const LegacyIssue(
          'enabledCookieJar',
          'legacy.cookie_policy_requires_review',
          'Disabled legacy cookie jar must not silently use the new automatic jar.',
        ),
      );
    }
    if ((input['bookSourceType'] ?? 0) != 0) {
      issues.add(
        const LegacyIssue(
          'bookSourceType',
          'legacy.non_text_source',
          'Non-text sources require a dedicated pipeline.',
        ),
      );
    }
    return LegacyImport(
      original,
      SourceDefinition(
        id: base.toString(),
        name: input['bookSourceName']?.toString() ?? base.host,
        baseUrl: base,
        stages: stages,
        headers: requestHeaders,
        metadata: {
          'legacy': true,
          'legacyOriginal': original,
          'compatibility': issues.isEmpty ? 'unverified' : 'manualRequired',
        },
      ),
      List.unmodifiable(issues),
    );
  }
}

class _LegacyRequest {
  _LegacyRequest(this.url, {this.method = 'GET', this.body, this.headers});
  final String url;
  final String method;
  final String? body;
  final Map<String, String>? headers;
}

_LegacyRequest _legacyRequest(
  String value,
  String path,
  Map<String, String> defaults,
  List<LegacyIssue> issues,
) {
  void issue(String message) =>
      issues.add(LegacyIssue(path, 'legacy.request_options', message));
  if (value.contains('@js:') || value.contains('<js>')) {
    issue('Script-generated requests require migration.');
    return _LegacyRequest(value);
  }
  final separator = RegExp(r',\s*(?=\{)').firstMatch(value);
  if (separator == null) return _LegacyRequest(value);
  final url = value.substring(0, separator.start);
  Map<String, Object?> options;
  try {
    final raw = jsonDecode(value.substring(separator.end));
    if (raw is! Map) throw const FormatException('Expected options object');
    options = Map<String, Object?>.from(raw);
  } on FormatException {
    issue('Request options must be strict literal JSON.');
    return _LegacyRequest(url);
  }
  for (final key in options.keys) {
    if (!['method', 'body', 'headers', 'charset'].contains(key)) {
      issue('Unsupported request option: $key');
    }
  }
  if (options['charset'] != null && options['charset'] != '') {
    issue(
      'Legacy charset controls query/form percent encoding; it cannot map to the new response charset.',
    );
  }
  var method = 'GET';
  if (options['method'] != null) {
    if (options['method'] is! String) {
      issue('Request method must be a literal string.');
    } else {
      if ((options['method'] as String).contains('{{')) {
        issue(
          'Method placeholders require interpolation before method selection.',
        );
      }
      method = switch ((options['method'] as String).toUpperCase()) {
        'POST' => 'POST',
        'HEAD' => 'HEAD',
        _ => 'GET',
      };
    }
  }
  final headers = Map<String, String>.from(defaults);
  if (options['headers'] != null) {
    try {
      final raw = options['headers'] is String
          ? jsonDecode(options['headers'] as String)
          : options['headers'];
      if (raw is! Map ||
          raw.entries.any(
            (e) => e.key is! String || e.value is Map || e.value is List,
          )) {
        throw const FormatException('Expected flat header map');
      }
      for (final entry in raw.entries) {
        final name = entry.key.toString();
        final headerValue = entry.value?.toString() ?? 'null';
        if (name.contains('{{') || headerValue.contains('{{')) {
          issue(
            'Header placeholders require interpolation before request construction.',
          );
          continue;
        }
        headers[name] = headerValue;
      }
    } on FormatException {
      issue('Request headers must be a literal flat object or JSON string.');
    }
  }
  String? body = options['body'] == null
      ? null
      : options['body'] is String
      ? options['body'] as String
      : jsonEncode(options['body']);
  if (body != null && (body.contains('@js:') || body.contains('<js>'))) {
    issue('Script body requires migration.');
  }
  for (final template in RegExp(
    r'\{\{(.*?)\}\}',
  ).allMatches('$url ${body ?? ''}')) {
    if (![
      'key',
      'page',
      'bookUrl',
      'tocUrl',
      'chapterUrl',
      'baseUrl',
    ].contains(template[1])) {
      issue('Only known input placeholders can be converted.');
    }
  }
  if (method == 'POST') {
    if (headers.keys.any(
      (k) => k.toLowerCase() == 'content-type' && k != 'Content-Type',
    )) {
      issue(
        'Legacy Content-Type lookup is case sensitive; normalize explicitly before migration.',
      );
    }
    final explicitType = headers['Content-Type'];
    if (explicitType != null &&
        RegExp(
          r'charset\s*=\s*(?!utf-?8)',
          caseSensitive: false,
        ).hasMatch(explicitType)) {
      issue(
        'Non-UTF8 body Content-Type requires separate request-encoding migration.',
      );
    }
    final trimmed = body?.trim() ?? '';
    final jsonOrXml =
        (trimmed.startsWith('{') && trimmed.endsWith('}')) ||
        (trimmed.startsWith('[') && trimmed.endsWith(']')) ||
        (trimmed.startsWith('<') && trimmed.endsWith('>'));
    if (explicitType == null || explicitType.isEmpty) {
      if (trimmed.startsWith('{{')) {
        issue(
          'Body placeholders can change inferred Content-Type after substitution.',
        );
      }
      if (jsonOrXml) {
        headers['Content-Type'] = 'application/json; charset=UTF-8';
      } else {
        headers['Content-Type'] =
            'application/x-www-form-urlencoded; charset=UTF-8';
        if (body?.contains('{{') ?? false) {
          issue('Templated form body needs encoding after substitution.');
        } else {
          body = _fixedForm(body ?? '');
        }
      }
    }
  }
  return _LegacyRequest(
    url,
    method: method,
    body: method == 'POST' ? body : null,
    headers: headers,
  );
}

String _fixedForm(String input) => input
    .split('&')
    .map((part) {
      final split = part.indexOf('=');
      String encode(String text) =>
          RegExp(r'^(?:[A-Za-z0-9*._-]|%[0-9A-Fa-f]{2})*$').hasMatch(text)
          ? text
          : legacyFormEncode(text);
      return split < 0
          ? encode(part)
          : '${encode(part.substring(0, split))}=${encode(part.substring(split + 1))}';
    })
    .join('&');

bool _simpleExtractionScript(String script) {
  final match = RegExp(
    r"""^@js:\s*(?:return\s+)?java\.(getString|getStringList)\(\s*("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')\s*\)\s*;?\s*$""",
  ).firstMatch(script);
  if (match == null) return false;
  final literal = match[2]!;
  String rule;
  if (literal.startsWith('"')) {
    try {
      rule = jsonDecode(literal) as String;
    } on FormatException {
      return false;
    }
  } else {
    final content = literal.substring(1, literal.length - 1);
    if (content.contains(r'\')) return false;
    rule = content;
  }
  // Only already-covered extraction syntax; script nesting, replacements,
  // variables and compound rule semantics remain reviewable separately.
  return !RegExp(
    r'@js:|<js>|@webjs:|@put:|@get:|##|&&|\|\||%%|\{\{|^//|^@XPath:',
    caseSensitive: false,
  ).hasMatch(rule);
}
