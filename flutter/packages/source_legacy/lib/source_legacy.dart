/// Conservative compatibility adapters for historical Legado sources.
library;

import 'dart:convert';

import 'src/main_js.dart';

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

/// Resolve rule containers using the old Gson adapters, without mutating the
/// original source. Raw arrays are null rules; an encoded array is an invalid
/// reflective object. BookList's execution fallback must not replace imported
/// menu/field structure or delete an explore stage.
Map<String, Object?>? legacyRuleObject(
  Map<String, Object?> source,
  String key,
) {
  Map<String, Object?>? decode(Object? value) {
    if (value == null || value is List) return null;
    if (value is String) {
      value = jsonDecode(value);
      if (value == null) return null;
    }
    if (value is! Map || value.keys.any((key) => key is! String)) {
      throw const FormatException('Expected a legacy rule object');
    }
    return Map<String, Object?>.from(value);
  }

  return decode(source[key]);
}

/// Operation gating only: the complete migration report keeps every issue.
/// UI capabilities do not block reading; actual request and script capabilities
/// still apply globally unless their concrete host has implemented them.
bool legacyIssueAffectsOperation(LegacyIssue issue, String operation) {
  const scopes = {
    'ruleSearch': 'search',
    'searchUrl': 'search',
    'ruleExplore': 'explore',
    'exploreUrl': 'explore',
    'ruleBookInfo': 'info',
    'ruleToc': 'toc',
    'ruleContent': 'content',
  };
  for (final entry in scopes.entries) {
    if (issue.path == entry.key ||
        issue.path.startsWith('${entry.key}.') ||
        issue.path.startsWith('${entry.key}[')) {
      return operation == entry.value;
    }
  }
  if (issue.code == 'legacy.capability_requires_review' &&
      const {
        'loginUrl',
        'loginUi',
        'ruleReview',
        'exploreScreen',
      }.contains(issue.path)) {
    return false;
  }
  if (issue.code == 'legacy.non_text_source' &&
      const {'search', 'explore', 'info', 'toc'}.contains(operation)) {
    return false;
  }
  return true;
}

/// Imports the structural format without claiming every legacy semantic works.
class LegacySourceImporter {
  LegacyImport import(Map<String, Object?> input) {
    final original = Map<String, Object?>.from(
      jsonDecode(jsonEncode(input)) as Map,
    );
    final issues = <LegacyIssue>[];
    final id = input['bookSourceUrl'];
    if (id is! String || id.isEmpty) {
      throw const FormatException(
        'bookSourceUrl must be a non-empty source identifier',
      );
    }
    final idUri = Uri.tryParse(id);
    final legacyBaseUrlUnavailable =
        idUri == null ||
        !['http', 'https'].contains(idUri.scheme) ||
        idUri.host.isEmpty;
    final base = legacyBaseUrlUnavailable
        ? _searchRequestAnchor(input['searchUrl'])
        : idUri;
    if (base == null) {
      throw const FormatException(
        'Cannot determine an HTTP(S) request base for this source identifier; a static absolute searchUrl is required',
      );
    }
    if (legacyBaseUrlUnavailable) {
      issues.add(
        const LegacyIssue(
          'bookSourceUrl',
          'legacy.base_url_requires_review',
          'Source identity is not a request base URL; absolute stage URLs require review.',
        ),
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
    final mainJs = input['mainJs'];
    final hasMainJs = mainJs is String && mainJs.trim().isNotEmpty;
    final stages = <String, SourceStage>{};
    final exploreItems = <Map<String, Object?>>[];
    final mapping = {
      'search': ('ruleSearch', 'searchUrl', 'bookList'),
      'explore': ('ruleExplore', 'exploreUrl', 'bookList'),
      'info': ('ruleBookInfo', null, null),
      'toc': ('ruleToc', null, 'chapterList'),
      'content': ('ruleContent', null, null),
    };
    for (final entry
        in hasMainJs
            ? <MapEntry<String, (String, String?, String?)>>[]
            : mapping.entries) {
      final (ruleKey, urlKey, listKey) = entry.value;
      Map<String, Object?>? raw;
      try {
        raw = legacyRuleObject(input, ruleKey);
      } on FormatException {
        issues.add(
          LegacyIssue(
            ruleKey,
            'legacy.invalid_rule_object',
            'Expected a rule object.',
          ),
        );
        continue;
      }
      if (raw == null) continue;
      final fields = <String, String>{};
      String? list;
      String? nextPage;
      var hasAppConfiguration = false;
      for (final rule in raw.entries) {
        // These are App configuration, not extraction or executable hooks.
        // The original Gson StringJsonDeserializer accepts every JSON value;
        // preserve it verbatim in legacyOriginal instead of creating a field.
        if ((entry.key == 'search' && rule.key == 'checkKeyWord') ||
            (entry.key == 'content' && rule.key == 'imageStyle')) {
          hasAppConfiguration = true;
          continue;
        }
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
        final pipelineHook = [
          'init',
          'checkKeyWord',
          'webJs',
          'sourceRegex',
          'replaceRegex',
          'imageStyle',
          'imageDecode',
          'payAction',
          'contentBatch',
          'callBackJs',
        ].contains(rule.key);
        if (pipelineHook) {
          issues.add(
            LegacyIssue(
              '$ruleKey.${rule.key}',
              'legacy.pipeline_requires_review',
              'This legacy pipeline hook or pagination behavior requires explicit implementation.',
            ),
          );
        }
        var text = (rule.value as String).trim();
        final lower = text.toLowerCase();
        if (lower.startsWith('<js>') && lower.endsWith('</js>')) {
          text = '@js:${text.substring(4, text.length - 5)}';
        } else if (lower.startsWith('@js:')) {
          text = '@js:${text.substring(4)}';
        }
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
            (listKey != null &&
                rule.key == listKey &&
                _portableV8Script(text)) ||
            _simpleExtractionScript(text) ||
            RegExp(
              r'^@js:\s*(?:return\s+)?java\.(?:ajax|ajaxAll|connect|get|post|head|put|base64Encode|base64Decode|base64DecodeToByteArray|strToBytes|bytesToStr|hexDecodeToByteArray|hexDecodeToString|hexEncodeToString|md5Encode|md5Encode16|digestHex|digestBase64Str|encodeURI)\([^()]*\)\s*;?\s*$',
            ).hasMatch(text);
        final literalReplacement =
            !pipelineHook &&
            _legacyScalarReplacementField(entry.key, rule.key.toString()) &&
            _simpleLiteralReplacement(text);
        if (!simpleLegacyScript &&
            !_supportedLegacyCssCombination(text) &&
            !literalReplacement &&
            RegExp(
              r'@js:|<js>|@webjs:|@put:|@get:|##|&&|\|\||%%|\{\{|^//|^@XPath:',
              caseSensitive: false,
            ).hasMatch(text)) {
          issues.add(
            LegacyIssue(
              '$ruleKey.${rule.key}',
              'legacy.rule_requires_review',
              'Unsupported rule dialect, embedded script, host dependency, or variable behavior requires semantic review.',
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
      if (hasAppConfiguration &&
          fields.isEmpty &&
          list == null &&
          nextPage == null) {
        continue;
      }
      var url = urlKey == null
          ? switch (entry.key) {
              'info' => '{{bookUrl}}',
              'toc' => '{{tocUrl}}',
              _ => '{{chapterUrl}}',
            }
          : input[urlKey]?.toString() ?? '';
      if (url.isEmpty && entry.key != 'explore') {
        issues.add(
          LegacyIssue(
            urlKey ?? entry.key,
            'legacy.missing_url',
            'Stage URL is missing.',
          ),
        );
      }
      final selectedExplore = entry.key == 'explore';
      final request = selectedExplore
          ? _LegacyRequest('')
          : _legacyRequest(url, urlKey ?? entry.key, requestHeaders, issues);
      if (selectedExplore) {
        exploreItems.addAll(
          _legacyExploreMenu(input['exploreUrl'], requestHeaders, issues),
        );
      }
      url = selectedExplore ? '{{exploreUrl}}' : request.url;
      stages[entry.key] = SourceStage(
        url: url,
        list: list,
        fields: fields,
        nextPage: nextPage,
        method: selectedExplore ? 'GET' : request.method,
        body: selectedExplore ? null : request.body,
        bodyEncoding: selectedExplore ? 'raw' : request.bodyEncoding,
        bodyTemplateMode: selectedExplore ? 'raw' : request.bodyTemplateMode,
        legacyRequestInput: selectedExplore ? 'exploreUrl' : null,
        legacyPageTemplates: selectedExplore
            ? exploreItems.any(
                (item) => _legacyPageTemplatesPresent(
                  item['url'] as String,
                  knownUrlInputs: true,
                ),
              )
            : request.legacyPageTemplates,
        headers: selectedExplore ? null : request.headers,
      );
    }
    // mainJs owns stage extraction, but Android still consumes these content
    // hooks outside that execution path. Keep their migration boundary explicit.
    Map<String, Object?>? contentRules;
    if (hasMainJs) {
      try {
        contentRules = legacyRuleObject(input, 'ruleContent');
      } on FormatException {
        issues.add(
          const LegacyIssue(
            'ruleContent',
            'legacy.invalid_rule_object',
            'Expected a rule object.',
          ),
        );
      }
    }
    if (hasMainJs && contentRules != null) {
      for (final hook in ['imageDecode', 'payAction', 'callBackJs']) {
        final value = contentRules[hook];
        if (value == null || value == '') continue;
        issues.add(
          LegacyIssue(
            'ruleContent.$hook',
            value is String
                ? 'legacy.pipeline_requires_review'
                : 'legacy.invalid_rule',
            value is String
                ? 'This App-side content hook requires explicit implementation.'
                : 'Rule must be a string.',
          ),
        );
      }
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
      if (key == 'mainJs' && hasMainJs) continue;
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
    final rawSourceType = input['bookSourceType'];
    // The original IntJsonDeserializer accepts only numbers; other JSON
    // shapes return null and leave the primitive BookSource default (text).
    final sourceType = rawSourceType is num ? rawSourceType.toInt() : 0;
    // File download fields are handled by the positional mainJs adapter and
    // Android's file-book pipeline. Other media and declarative files remain
    // outside the supported legacy stage contract.
    if (sourceType != 0 && !(hasMainJs && sourceType == 3)) {
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
        id: id,
        name: input['bookSourceName']?.toString() ?? base.host,
        baseUrl: base,
        stages: stages,
        script: hasMainJs ? wrapLegacyMainJs(mainJs, original) : null,
        headers: requestHeaders,
        metadata: {
          'legacy': true,
          if (hasMainJs) 'legacyMainJs': true,
          if (legacyBaseUrlUnavailable) 'legacyBaseUrlUnavailable': true,
          'legacyOriginal': original,
          if (exploreItems.isNotEmpty) 'legacyExploreItems': exploreItems,
          'compatibility': issues.isEmpty ? 'unverified' : 'manualRequired',
        },
      ),
      List.unmodifiable(issues),
    );
  }
}

// A source key may be a local label. Use only a proven transport origin as an
// anchor; callers must not infer relative stage URLs from this search origin.
Uri? _searchRequestAnchor(Object? value) {
  if (value is! String || value.isEmpty) return null;
  final split = RegExp(r'\s*,\s*(?=\{)').firstMatch(value);
  final url = split == null ? value : value.substring(0, split.start);
  if (RegExp(r'@js:|<js>|<[^<>]*>', caseSensitive: false).hasMatch(url)) {
    return null;
  }
  final queryAt = url.indexOf('?');
  final path = queryAt < 0 ? url : url.substring(0, queryAt);
  if (path.contains('{{') || path.contains('}}')) return null;
  final knownTemplates = RegExp(
    r'\{\{(key|page|bookUrl|tocUrl|chapterUrl|baseUrl|exploreUrl)\}\}',
  );
  final staticUrl = url.replaceAllMapped(
    RegExp(r'\{\{([\s\S]*?)\}\}'),
    (m) => knownTemplates.hasMatch(m[0]!) || _legacyPageExpression(m[1]!)
        ? 'placeholder'
        : m[0]!,
  );
  if (staticUrl.contains('{{') ||
      staticUrl.contains('}}') ||
      RegExp(r'\s').hasMatch(staticUrl)) {
    return null;
  }
  final parsed = Uri.tryParse(staticUrl);
  if (parsed == null ||
      !['http', 'https'].contains(parsed.scheme) ||
      parsed.host.isEmpty ||
      parsed.userInfo.isNotEmpty ||
      parsed.hasFragment) {
    return null;
  }
  return Uri(
    scheme: parsed.scheme,
    host: parsed.host,
    port: parsed.hasPort ? parsed.port : null,
  );
}

List<Map<String, Object?>> _legacyExploreMenu(
  Object? menu,
  Map<String, String> defaults,
  List<LegacyIssue> issues,
) {
  void malformed() => issues.add(
    const LegacyIssue(
      'exploreUrl',
      'legacy.explore_menu_requires_review',
      'Explore menu must be a static title/url array or title::URL lines.',
    ),
  );
  if (menu == null || menu == '') return [];
  final items = <Map<String, Object?>>[];
  Object? decoded = menu;
  if (menu is String && menu.trim().startsWith('[')) {
    try {
      decoded = jsonDecode(menu);
    } on FormatException {
      malformed();
      return [];
    }
  }
  if (decoded is List) {
    for (final item in decoded) {
      if (item is! Map ||
          item['title'] is! String ||
          item['url'] is! String ||
          item.keys.any((k) => k != 'title' && k != 'url' && k != 'style') ||
          (item['style'] != null && item['style'] is! Map)) {
        malformed();
        continue;
      }
      items.add({
        'title': item['title'] as String,
        'url': item['url'] as String,
        if (item.containsKey('style'))
          'style': jsonDecode(jsonEncode(item['style'])),
      });
    }
  } else if (menu is String) {
    if (RegExp(r'^\s*(?:@js:|<js>)', caseSensitive: false).hasMatch(menu)) {
      malformed();
      return [];
    }
    if (menu.contains('::') &&
        !RegExp(r'^https?://', caseSensitive: false).hasMatch(menu)) {
      for (final line in menu.split(RegExp(r'(?:&&|\n)+'))) {
        if (line.isEmpty) continue;
        final separator = line.indexOf('::');
        if (separator <= 0 || separator + 2 == line.length) {
          malformed();
          continue;
        }
        if (line.indexOf('::', separator + 2) >= 0) {
          malformed();
        }
        items.add({
          'title': line.substring(0, separator),
          'url': line.substring(separator + 2),
        });
      }
    } else {
      // Preserve the previously supported plain default URL contract.
      items.add({'title': '', 'url': menu});
    }
  } else {
    malformed();
    return [];
  }
  for (var i = 0; i < items.length; i++) {
    final url = items[i]['url'] as String;
    _legacyRequest(url, 'exploreUrl[$i].url', defaults, issues);
    final rawUrl = url.split(RegExp(r',\s*(?=\{)')).first;
    final parsedUrl = Uri.tryParse(
      rawUrl.replaceAll(RegExp(r'\{\{[^}]*\}\}'), 'input'),
    );
    if (url.isEmpty ||
        parsedUrl == null ||
        parsedUrl.userInfo.isNotEmpty ||
        (parsedUrl.hasScheme &&
            !['http', 'https'].contains(parsedUrl.scheme))) {
      malformed();
    }
  }
  return items;
}

/// Materializes a selected legacy category request before stage templating.
/// Unsupported options fail explicitly; exception text never includes source data.
SourceStage adaptLegacyRequest(
  SourceDefinition source,
  SourceStage stage,
  Map<String, Object?> input,
) {
  final key = stage.legacyRequestInput;
  if (key == null) return stage;
  final selected = input[key];
  if (key != 'exploreUrl' || selected is! String || selected.isEmpty) {
    throw const EngineException(
      'legacy_request_requires_migration',
      'A supported selected legacy request is required',
    );
  }
  final issues = <LegacyIssue>[];
  final parsed = _legacyRequest(selected, key, source.headers, issues);
  final uri = Uri.tryParse(
    parsed.url.replaceAll(RegExp(r'\{\{[^}]*\}\}'), 'input'),
  );
  if (issues.isNotEmpty ||
      uri == null ||
      uri.userInfo.isNotEmpty ||
      (uri.hasScheme && !['http', 'https'].contains(uri.scheme))) {
    throw const EngineException(
      'legacy_request_requires_migration',
      'Selected legacy request contains unsupported behavior',
    );
  }
  return SourceStage(
    url: parsed.url,
    method: parsed.method,
    body: parsed.body,
    headers: parsed.headers,
    bodyEncoding: parsed.bodyEncoding,
    bodyTemplateMode: parsed.bodyTemplateMode,
    legacyPageTemplates: parsed.legacyPageTemplates,
    list: stage.list,
    fields: stage.fields,
    nextPage: stage.nextPage,
    maxPages: stage.maxPages,
  );
}

class _LegacyRequest {
  _LegacyRequest(
    this.url, {
    this.method = 'GET',
    this.body,
    this.headers,
    this.bodyEncoding = 'raw',
    this.bodyTemplateMode = 'raw',
    this.legacyPageTemplates = false,
  });
  final String url;
  final String method;
  final String? body;
  final String bodyEncoding;
  final String bodyTemplateMode;
  final bool legacyPageTemplates;
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
  if (RegExp(r'@js:|<js>', caseSensitive: false).hasMatch(value)) {
    issue('Script-generated requests require migration.');
    return _LegacyRequest(value);
  }
  final separator = RegExp(r',\s*(?=\{)').firstMatch(value);
  final url = separator == null ? value : value.substring(0, separator.start);
  _validateLegacyTemplates(url, issue, allowChoices: true);
  var legacyPageTemplates = _legacyPageTemplatesPresent(
    url,
    knownUrlInputs: true,
  );
  if (separator == null) {
    return _LegacyRequest(value, legacyPageTemplates: legacyPageTemplates);
  }
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
  if (body != null) {
    _validateLegacyTemplates(body, issue);
    legacyPageTemplates =
        legacyPageTemplates || _legacyPageTemplatesPresent(body);
  }
  if (body != null && (body.contains('@js:') || body.contains('<js>'))) {
    issue('Script body requires migration.');
  }
  var bodyEncoding = 'raw';
  var bodyTemplateMode = 'raw';
  if (method == 'POST') {
    if (body?.contains('{{') ?? false) {
      if (options['body'] is String) {
        bodyTemplateMode = 'legacyJsonString';
      } else {
        issue(
          'Templates in object or array bodies require review of the outer JSON substitution layer.',
        );
      }
    }
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
        headers['Content-Type'] = 'application/x-www-form-urlencoded';
        final templated = body?.contains('{{') ?? false;
        if (templated && !RegExp(r'^[A-Za-z0-9*._-]+=').hasMatch(body!)) {
          issue(
            'Templated form bodies require a fixed non-empty parameter name.',
          );
        } else if (!templated &&
            (body?.isNotEmpty ?? false) &&
            body!.trim().isNotEmpty &&
            encodeLegacyFormUtf8Body(body).isEmpty) {
          issue(
            'Empty encoded form with a nonblank body uses a different legacy request branch.',
          );
        } else if (options['charset'] == null || options['charset'] == '') {
          bodyEncoding = 'legacyFormUtf8';
        }
      }
    }
  }
  return _LegacyRequest(
    url,
    method: method,
    body: method == 'POST' ? body : null,
    bodyEncoding: bodyEncoding,
    bodyTemplateMode: bodyTemplateMode,
    legacyPageTemplates: legacyPageTemplates,
    headers: headers,
  );
}

bool _legacyPageExpression(String expression) {
  final match = RegExp(r'^ *page *[+-] *(0|[1-9][0-9]*) *$')
      .firstMatch(expression);
  if (match == null) return false;
  final offset = int.tryParse(match[1]!);
  return offset != null && offset <= 9007199254740991;
}

bool _legacyPageTemplatesPresent(String value, {bool knownUrlInputs = false}) =>
    (knownUrlInputs &&
        RegExp(
          r'\{\{(key|page|bookUrl|tocUrl|chapterUrl|baseUrl|exploreUrl)\}\}',
        ).hasMatch(value)) ||
    RegExp(r'\{\{([\s\S]*?)\}\}')
        .allMatches(value)
        .any((m) => _legacyPageExpression(m[1]!)) ||
    RegExp(r'<[^<>]*,[^<>]*>').hasMatch(value);

void _validateLegacyTemplates(
  String value,
  void Function(String) issue, {
  bool allowChoices = false,
}) {
  final templates = RegExp(r'\{\{([\s\S]*?)\}\}');
  for (final template in templates.allMatches(value)) {
    if (![
          'key',
          'page',
          'bookUrl',
          'tocUrl',
          'chapterUrl',
          'baseUrl',
          'exploreUrl',
        ].contains(template[1]) &&
        !_legacyPageExpression(template[1]!)) {
      issue('Only known input placeholders can be converted.');
    }
  }
  final remainder = value.replaceAll(templates, '');
  if (remainder.contains('{{')) {
    issue('Unbalanced input placeholders require review.');
  }
  final angles = RegExp(r'<[^<>]*>');
  for (final angle in angles.allMatches(value)) {
    if (!allowChoices || !angle[0]!.contains(',') || angle[0]!.contains('{{')) {
      issue(
        'Only static URL page choices with comma-separated alternatives are supported.',
      );
    }
  }
  final withoutAngles = value.replaceAll(angles, '');
  if (withoutAngles.contains('<') || withoutAngles.contains('>')) {
    issue('Nested or unbalanced page-choice delimiters require review.');
  }
}

// Match the original App call sites that use AnalyzeRule.getString. Content
// has a separate formatting contract; next-page/kind/download rules use lists.
bool _legacyScalarReplacementField(String stage, String field) =>
    switch (stage) {
      'search' || 'explore' => const {
        'name',
        'author',
        'wordCount',
        'lastChapter',
        'intro',
        'coverUrl',
        'bookUrl',
      }.contains(field),
      'info' => const {
        'name',
        'author',
        'wordCount',
        'lastChapter',
        'intro',
        'coverUrl',
        'tocUrl',
      }.contains(field),
      'toc' => const {
        'chapterName',
        'chapterUrl',
        'updateTime',
        'isVolume',
        'isVip',
        'isPay',
      }.contains(field),
      _ => false,
    };

/// A replacement subset with identical Java/Dart regex semantics: literal
/// matches and literal output, on an otherwise supported scalar extractor.
/// It only removes a review issue; it never certifies a source as verified.
bool _simpleLiteralReplacement(String rule) {
  final parts = rule.split('##');
  if (parts.length != 3) return false;
  final selector = parts[0].trim();
  final pattern = parts[1];
  final replacement = parts[2];
  if (pattern.isEmpty ||
      RegExp(r"""[.\^$*+?{}\[\]\\|()#'"\r\n\u2028\u2029]""")
          .hasMatch(pattern) ||
      RegExp(r"""[$\\#&'"\r\n\u2028\u2029]""").hasMatch(replacement)) {
    return false;
  }
  if (RegExp(
    r'@js:|<js>|@webjs:|@put:|@get:|@xpath:|@regex:|@json:|&&|\|\||%%|\{\{|^//',
    caseSensitive: false,
  ).hasMatch(rule)) {
    return false;
  }
  // CSS has already been normalized to the explicit legacy dialect. Do not
  // use the replacement suffix to admit opaque/unknown @ rules or regex mode.
  if (!selector.startsWith('@legacy:')) return false;
  final extraction = selector.substring(8).trim();
  if (extraction.isEmpty ||
      extraction.startsWith(':') ||
      extraction.startsWith('//') ||
      extraction.startsWith(r'$')) {
    return false;
  }
  if (extraction.startsWith('@') &&
      !['@text', '@ownText', '@textNodes'].contains(extraction)) {
    return false;
  }
  // Other outputs (HTML, attributes or implicit nodes) have additional
  // scalar serialization/unescape contracts outside this replacement subset.
  return RegExp(r'@(text|ownText|textNodes)$').hasMatch(extraction);
}

/// Entire JS list rules are opaque to the rule splitter. Host-dependent scripts
/// retain review until their specific bridge contract is established.
bool _portableV8Script(String rule) {
  if (!rule.startsWith('@js:')) return false;
  final script = rule.substring(4);
  if (script.trim().isEmpty ||
      RegExp(
        r'@(?:get|put|webjs):|<js>|\{\{',
        caseSensitive: false,
      ).hasMatch(script)) {
    return false;
  }
  final code = _jsOutsideStrings(script);
  if (code == null || code.contains('##')) return false;
  return !RegExp(
    r'(?:^|[^\w$.])(?:java|Packages|Java|JavaAdapter|importClass|importPackage|source|sourceApi|book|chapter|cookie|cache|src)\b(?!\s*:)|\b(?:eval|Function)\s*\(|\b(?:globalThis|this)\s*(?:\[|\.\s*(?:java|source|sourceApi|Packages|Java)\b)',
  ).hasMatch(code.replaceAll(RegExp(r'\.\s+'), '.'));
}

String? _jsOutsideStrings(String input) {
  final out = StringBuffer();
  String? quote;
  var escape = false;
  for (var i = 0; i < input.length; i++) {
    final c = input[i];
    if (quote != null) {
      if (escape) {
        escape = false;
      } else if (c == r'\') {
        escape = true;
      } else if (c == quote) {
        quote = null;
      }
      continue;
    }
    if (c == '`') {
      return null; // Interpolated templates need their own host analysis.
    }
    if (c == "'" || c == '"') {
      quote = c;
      out.write(' ');
      continue;
    }
    if (input.startsWith('//', i)) {
      final end = input.indexOf('\n', i + 2);
      if (end < 0) break;
      i = end;
      out.write(' ');
      continue;
    }
    if (input.startsWith('/*', i)) {
      final end = input.indexOf('*/', i + 2);
      if (end < 0) return null;
      i = end + 1;
      out.write(' ');
      continue;
    }
    out.write(c);
  }
  return quote == null ? out.toString() : null;
}

/// The old RuleAnalyzer selects one operator family. Keep that boundary until
/// mixed-family precedence has a separate equivalence contract. Quotes and
/// selector brackets are scanned so literal operator text is never a branch.
bool _supportedLegacyCssCombination(String rule) {
  if (!rule.toLowerCase().startsWith('@legacy:')) return false;
  final input = rule.substring(8);
  final parts = <String>[];
  final operators = <String>{};
  final brackets = <String>[];
  var start = 0;
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
    if (c == "'" || c == '"') {
      quote = c;
      continue;
    }
    if ('([{'.contains(c)) brackets.add(c);
    if (')]}'.contains(c)) {
      if (brackets.isEmpty ||
          '([{'.indexOf(brackets.removeLast()) != ')]}'.indexOf(c)) {
        return false;
      }
    }
    if (brackets.isEmpty && i + 1 < input.length) {
      final op = input.substring(i, i + 2);
      if (const {'||', '&&', '%%'}.contains(op)) {
        parts.add(input.substring(start, i));
        operators.add(op);
        i++;
        start = i + 1;
        continue;
      }
    }
  }
  if (escape || quote != null || brackets.isNotEmpty || operators.length > 1) {
    return false;
  }
  parts.add(input.substring(start));
  if (RegExp(
    r'@js:|<js>|@webjs:|@put:|@get:|##|\{\{|@xpath:|@regex:|@json:',
    caseSensitive: false,
  ).hasMatch(input)) {
    return false;
  }
  for (var part in parts) {
    part = part.trim();
    if (part.toLowerCase().startsWith('@legacy:')) part = part.substring(8);
    if (part.isEmpty ||
        part.startsWith('//') ||
        part.startsWith(':') ||
        part.startsWith(r'$')) {
      return false;
    }
    if (part.startsWith('@') &&
        !const {
          '@text',
          '@ownText',
          '@textNodes',
          '@html',
          '@all',
          '@children',
        }.contains(part)) {
      return false;
    }
    // HTML extraction can mutate the old tree; `all` can also return an empty
    // singleton. Neither contract is covered by this operator subset.
    if (operators.isNotEmpty &&
        RegExp(r'(?:^|@)(?:html|all)$').hasMatch(part)) {
      return false;
    }
  }
  return true;
}

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
