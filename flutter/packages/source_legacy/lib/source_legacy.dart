/// Conservative compatibility adapters for historical Legado sources.
library;

import 'dart:convert';

import 'package:crypto/crypto.dart';
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
        if (RegExp(
              r'^(?:class|tag|id)\.|\.\d+(?:@|$)|@(?:ownText|textNodes|all)(?:@|$)',
            ).hasMatch(text) ||
            [
              'text',
              'html',
              'all',
              'ownText',
              'textNodes',
              'children',
            ].contains(text) ||
            (!text.startsWith('@js:') && '@'.allMatches(text).length > 1)) {
          issues.add(
            LegacyIssue(
              '$ruleKey.${rule.key}',
              'legacy.jsoup_dsl',
              'Legacy JSoup shorthand, chained extraction, and indexes require explicit conversion.',
            ),
          );
        }

        if (!text.startsWith('@') &&
            !text.startsWith(r'$') &&
            !text.startsWith('//') &&
            !text.startsWith(':')) {
          text = '@css:$text';
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
          r'^@js:\s*(?:return\s+)?java\.(?:ajax|base64Encode|base64Decode|md5Encode|get|put)\([^()]*\)\s*;?\s*$',
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

/// Bootstrap for V8's synchronous host callback. Async work is performed on
/// the parent isolate while the script worker waits; utility results are values.
const legacyScriptPrelude = r"""
globalThis.java = new Proxy(Object.create(null), {
  get(_target, name) {
    return (...args) => __sourceHostSync('java.' + String(name), args);
  }
});
""";

/// Dart host backing the runtime's synchronous callback bridge.
/// Only listed overloads are supported, never arbitrary JVM interoperability.
class LegacyScriptHost implements ScriptHost {
  LegacyScriptHost(this.delegate, {Map<String, String>? variables})
    : variables = variables ?? <String, String>{};
  final ScriptHost delegate;
  final Map<String, String> variables;
  @override
  Future<Object?> call(String method, List<Object?> arguments) async {
    String stringAt(int n) {
      if (arguments.length <= n || arguments[n] is! String) {
        throw ArgumentError('$method requires string argument $n');
      }
      return arguments[n] as String;
    }

    switch (method) {
      case 'java.base64Encode':
        if (arguments.length != 1) {
          throw UnsupportedError('legacy.unsupported_overload: $method');
        }
        return base64Encode(utf8.encode(stringAt(0)));
      case 'java.base64Decode':
        if (arguments.length != 1) {
          throw UnsupportedError('legacy.unsupported_overload: $method');
        }
        return utf8.decode(base64Decode(stringAt(0)));
      case 'java.md5Encode':
        if (arguments.length != 1) {
          throw UnsupportedError('legacy.unsupported_overload: $method');
        }
        return md5.convert(utf8.encode(stringAt(0))).toString();
      case 'java.get':
        if (arguments.length != 1) {
          throw UnsupportedError('legacy.unsupported_overload: $method');
        }
        return variables[stringAt(0)] ?? '';
      case 'java.put':
        if (arguments.length != 2) {
          throw UnsupportedError('legacy.unsupported_overload: $method');
        }
        final key = stringAt(0);
        final value = stringAt(1);
        variables[key] = value;
        return value;
      case 'java.ajax':
        if (arguments.length != 1 ||
            arguments[0] is! String ||
            (arguments[0] as String).contains(',')) {
          throw UnsupportedError(
            'legacy.unsupported_overload: java.ajax requires one plain URL',
          );
        }
        return delegate.call('net.get', arguments);
      case 'java.getRequest':
      case 'java.post':
      case 'java.connect':
        throw UnsupportedError(
          'legacy.sync_network_unsupported: migrate to async source.net API',
        );
      default:
        if (method.startsWith('java.')) {
          throw UnsupportedError('legacy.unsupported_api: $method');
        }
        return delegate.call(method, arguments);
    }
  }
}
