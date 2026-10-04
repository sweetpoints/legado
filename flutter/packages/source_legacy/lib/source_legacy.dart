/// Conservative compatibility adapters for historical Legado sources.
library;

import 'dart:convert';

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
        final simpleLegacyScript = RegExp(
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
      final url = urlKey == null
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
      if (url.contains(',') || url.contains('@js:')) {
        issues.add(
          LegacyIssue(
            urlKey ?? entry.key,
            'legacy.request_options',
            'Legacy URL request options require conversion.',
          ),
        );
      }
      stages[entry.key] = SourceStage(
        url: url,
        list: list,
        fields: fields,
        nextPage: nextPage,
      );
    }
    for (final key in [
      'mainJs',
      'jsLib',
      'header',
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
