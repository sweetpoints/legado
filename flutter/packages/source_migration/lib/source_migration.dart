/// Conservative migration candidates; generated results are never verified.
library;

import 'dart:convert';

import 'package:source_legacy/source_legacy.dart';

class ScriptMigration {
  ScriptMigration(this.original, this.candidate, this.issues);
  final String original;
  final String? candidate;
  final List<LegacyIssue> issues;
  bool get requiresManualWork => issues.isNotEmpty;
}

class MigrationCandidate {
  MigrationCandidate(this.original, this.candidate, this.issues);
  final Map<String, Object?> original;
  final Map<String, Object?> candidate;
  final List<LegacyIssue> issues;
  String get status => issues.isEmpty ? 'unverified' : 'manualRequired';
  Map<String, Object?> toJson() => {
    'reportVersion': 1,
    'status': status,
    'original': original,
    'candidate': candidate,
    'issues': issues.map((e) => e.toJson()).toList(),
    'verified': false,
  };
}

class SourceMigrator {
  MigrationCandidate migrate(Map<String, Object?> input) {
    final imported = LegacySourceImporter().import(input);
    final candidate = imported.source.toJson();
    final issues = List<LegacyIssue>.from(imported.issues);
    void migrateRule(Map container, String key, String path, String oldPath) {
      final value = container[key];
      if (value is! String || !value.startsWith('@js:')) return;
      final result = migrateScript(value.substring(4));
      if (result.candidate != null) {
        container[key] = '@js:${result.candidate}';
        issues.removeWhere(
          (e) => e.path == oldPath && e.code == 'legacy.rule_requires_review',
        );
      } else {
        issues.addAll(
          result.issues.map((e) => LegacyIssue(path, e.code, e.message)),
        );
      }
    }

    final stages = candidate['stages'] as Map;
    for (final entry in stages.entries) {
      final stage = entry.value as Map;
      final oldName = switch (entry.key) {
        'search' => 'ruleSearch',
        'explore' => 'ruleExplore',
        'info' => 'ruleBookInfo',
        'toc' => 'ruleToc',
        _ => 'ruleContent',
      };
      final fields = stage['fields'] as Map;
      for (final key in fields.keys.toList()) {
        final oldKey = entry.key == 'toc'
            ? switch (key) {
                'title' => 'chapterName',
                'url' => 'chapterUrl',
                _ => key,
              }
            : key == 'latestChapterTitle'
            ? 'lastChapter'
            : key;
        migrateRule(
          fields,
          key as String,
          'stages.${entry.key}.fields.$key',
          '$oldName.$oldKey',
        );
      }
      migrateRule(
        stage,
        'list',
        'stages.${entry.key}.list',
        '$oldName.${entry.key == 'toc' ? 'chapterList' : 'bookList'}',
      );
      migrateRule(
        stage,
        'nextPage',
        'stages.${entry.key}.nextPage',
        '$oldName.${entry.key == 'toc' ? 'nextTocUrl' : 'nextContentUrl'}',
      );
    }
    final metadata = Map<String, Object?>.from(
      candidate['metadata'] as Map? ?? {},
    );
    metadata['compatibility'] = issues.isEmpty
        ? 'unverified'
        : 'manualRequired';
    metadata['legacy'] = issues.isNotEmpty || metadata['legacyMainJs'] == true;
    candidate['metadata'] = metadata;
    return MigrationCandidate(
      imported.original,
      Map<String, Object?>.from(jsonDecode(jsonEncode(candidate)) as Map),
      List.unmodifiable(issues),
    );
  }

  /// A restricted structural transformation, not a general JS transpiler.
  /// Only straight-line statements without functions/control flow are eligible.
  /// Strings and comments retain exact bytes. Unknown/dynamic APIs fail closed.
  ScriptMigration migrateScript(String script) {
    final issues = <LegacyIssue>[];
    void issue(String code, String message) {
      issues.add(LegacyIssue('script', code, message));
    }

    final tokens = <_Token>[];
    try {
      tokens.addAll(_lex(script));
    } on FormatException catch (e) {
      issue('migration.syntax_requires_review', e.message.toString());
      return ScriptMigration(script, null, issues);
    }
    for (var i = 0; i < tokens.length; i++) {
      if (tokens[i].text == 'source' && (i == 0 || tokens[i - 1].text != '.')) {
        issue(
          'migration.host_binding_conflict',
          'The legacy source identifier conflicts with the new host namespace.',
        );
      }
    }
    const forbidden = {
      'function',
      'class',
      'if',
      'else',
      'for',
      'while',
      'do',
      'switch',
      'try',
      'catch',
      'finally',
      'throw',
      'yield',
      'eval',
      'Function',
      'Packages',
      'import',
      'with',
      'new',
    };
    if (tokens.any(
      (t) =>
          forbidden.contains(t.text) ||
          ['{', '}', '=>', '`', '/'].contains(t.text),
    )) {
      issue(
        'migration.complex_script',
        'Control flow, functions, dynamic execution, object blocks, templates, and regex require manual migration.',
      );
    }
    final edits = <_Edit>[];
    final seen = <int>{};
    const methods = {
      'ajax': 'net.get',
      'base64Encode': 'encoding.base64Encode',
      'base64Decode': 'encoding.base64DecodeWithFlags',
      'base64DecodeToByteArray': 'encoding.base64DecodeBytes',
      'strToBytes': 'encoding.strToBytes',
      'bytesToStr': 'encoding.bytesToStr',
      'hexDecodeToByteArray': 'encoding.hexDecodeBytes',
      'hexDecodeToString': 'encoding.hexDecode',
      'hexEncodeToString': 'encoding.hexEncode',
      'md5Encode': 'crypto.md5',
      'md5Encode16': 'crypto.md5Short',
      'digestHex': 'crypto.digestHex',
      'digestBase64Str': 'crypto.digestBase64',
      'encodeURI': 'encoding.formEncode',
      'getString': 'parse.getString',
      'getStringList': 'parse.getStringList',
      'get': 'variables.get',
      'put': 'variables.put',
    };
    for (var i = 0; i < tokens.length; i++) {
      if (tokens[i].text != 'java') continue;
      if (i + 3 >= tokens.length ||
          tokens[i + 1].text != '.' ||
          tokens[i + 3].text != '(' ||
          (i > 0 && tokens[i - 1].text == '.')) {
        issue(
          'migration.dynamic_java',
          'Only direct java.method(...) calls are eligible.',
        );
        continue;
      }
      var method = methods[tokens[i + 2].text];
      if (method == null) {
        issue(
          'migration.unsupported_api',
          'No migration mapping for java.${tokens[i + 2].text}.',
        );
        continue;
      }
      // Rewrite the member call node only. Await is parenthesized so property
      // access/concatenation still use the resolved value instead of a Promise.
      var depth = 0;
      var end = -1;
      for (var j = i + 3; j < tokens.length; j++) {
        if (tokens[j].text == '(') depth++;
        if (tokens[j].text == ')' && --depth == 0) {
          end = j;
          break;
        }
      }
      if (end < 0) {
        issue('migration.syntax_requires_review', 'Unbalanced call.');
        continue;
      }
      var argumentDepth = 0;
      var argumentCount = end == i + 4 ? 0 : 1;
      for (var j = i + 4; j < end; j++) {
        if (['(', '['].contains(tokens[j].text)) argumentDepth++;
        if ([')', ']'].contains(tokens[j].text)) argumentDepth--;
        if (tokens[j].text == ',' && argumentDepth == 0) argumentCount++;
      }
      final name = tokens[i + 2].text;
      final twoRequired = [
        'put',
        'digestHex',
        'digestBase64Str',
      ].contains(name);
      final optionalTwo = [
        'base64Encode',
        'base64Decode',
        'base64DecodeToByteArray',
        'strToBytes',
        'bytesToStr',
        'encodeURI',
        'ajax',
      ].contains(name);
      final invalidArity = twoRequired
          ? argumentCount != 2
          : optionalTwo
          ? argumentCount < 1 || argumentCount > 2
          : argumentCount != 1;
      if (invalidArity) {
        issue(
          'migration.unsupported_overload',
          'Only the documented simple overload of java.${tokens[i + 2].text} can be converted.',
        );
        continue;
      }
      final args = <List<_Token>>[];
      var splitStart = i + 4;
      var splitDepth = 0;
      for (var j = i + 4; j < end; j++) {
        if (['(', '['].contains(tokens[j].text)) splitDepth++;
        if ([')', ']'].contains(tokens[j].text)) splitDepth--;
        if (tokens[j].text == ',' && splitDepth == 0) {
          args.add(tokens.sublist(splitStart, j));
          splitStart = j + 1;
        }
      }
      if (splitStart < end) args.add(tokens.sublist(splitStart, end));
      if (name == 'ajax' &&
          args.isNotEmpty &&
          (args.first.first.text == '[' || args.first.first.text == 'Array')) {
        issue(
          'migration.ajax_array_requires_review',
          'Legacy ajax selects the first array item; array inputs require explicit migration.',
        );
        continue;
      }
      if (name == 'ajax' &&
          argumentCount == 1 &&
          (args.single.length != 1 || args.single.single.text != '<string>')) {
        issue(
          'migration.ambiguous_overload',
          'Ajax conversion requires a literal URL string; dynamic inputs may be arrays.',
        );
        continue;
      }
      String rawArg(int n) =>
          script.substring(args[n].first.start, args[n].last.end);
      if (name == 'base64Encode' && argumentCount == 2) {
        method = 'encoding.base64EncodeWithFlags';
      }
      if (name == 'base64Decode' && argumentCount == 2) {
        if (args[1].length == 1 && args[1].single.text == '<string>') {
          method = 'encoding.base64DecodeWithCharset';
        } else if (args[1].length == 1 &&
            int.tryParse(args[1].single.text) != null) {
          method = 'encoding.base64DecodeWithFlags';
        } else {
          issue(
            'migration.ambiguous_overload',
            'Dynamic base64Decode charset/flags requires review.',
          );
          continue;
        }
      }
      if (name == 'ajax' && argumentCount == 2) {
        if (args[0].length != 1 ||
            args[0].single.text != '<string>' ||
            args[1].length != 1 ||
            (args[1].single.text != 'null' &&
                int.tryParse(args[1].single.text) == null)) {
          issue(
            'migration.ambiguous_overload',
            'Timed ajax conversion requires literal URL and integer/null timeout.',
          );
          continue;
        }
        edits.add(
          _Edit(
            tokens[i].start,
            tokens[end].end,
            '(await source.net.request({url:${rawArg(0)},timeoutMs:${rawArg(1)}})).body',
          ),
        );
        seen.add(i);
        continue;
      }
      if (name == 'getString' || name == 'getStringList') {
        if (args.single.length != 1 || args.single.single.text != '<string>') {
          issue(
            'migration.dynamic_rule',
            'Only literal extraction rules can be converted automatically.',
          );
          continue;
        }
        final raw = rawArg(0);
        String? decoded;
        if (raw.startsWith('"')) {
          try {
            decoded = jsonDecode(raw) as String;
          } on FormatException {
            /* Kept manual when literal decoding fails. */
          }
        } else if (!raw.substring(1, raw.length - 1).contains(r'\')) {
          decoded = raw.substring(1, raw.length - 1);
        }
        if (decoded == null ||
            decoded.contains('@js:') ||
            decoded.contains('<js>')) {
          issue(
            'migration.dynamic_rule',
            'Escaped or nested script rule requires review.',
          );
          continue;
        }
        final rule = decoded.toLowerCase().startsWith('@css:')
            ? '@legacy:${decoded.substring(5)}'
            : [
                '@text',
                '@ownText',
                '@textNodes',
                '@html',
                '@all',
                '@children',
              ].contains(decoded)
            ? '@legacy:$decoded'
            : decoded.startsWith('@') || decoded.startsWith(r'$')
            ? decoded
            : '@legacy:$decoded';
        var value = decoded.isEmpty
            ? (name == 'getString' ? jsonEncode('') : 'null')
            : '(await source.$method(${jsonEncode(rule)},result,false,baseUrl))';
        if (name == 'getString' && decoded.isNotEmpty) {
          value = '(await source.encoding.unescapeHtml4($value))';
        }
        edits.add(_Edit(tokens[i].start, tokens[end].end, value));
        seen.add(i);
        continue;
      }
      final alreadyAwaited = i > 0 && tokens[i - 1].text == 'await';
      if (alreadyAwaited) {
        issue(
          'migration.already_async',
          'Already-awaited legacy calls require review of the existing async contract.',
        );
        continue;
      }
      edits.add(
        _Edit(
          tokens[i].start,
          tokens[i + 2].end,
          '${tokens[i + 2].text == 'get' ? '((await ' : '(await '}source.$method',
        ),
      );
      if (!alreadyAwaited) {
        edits.add(
          _Edit(
            tokens[end].end,
            tokens[end].end,
            tokens[i + 2].text == 'get' ? ') ?? "")' : ')',
          ),
        );
      }
      seen.add(i);
    }
    // Reject aliases/shadowing or malformed declarations, preventing accidental
    // conversion when java is a user variable rather than the legacy host.
    for (var i = 0; i < tokens.length; i++) {
      if (tokens[i].text == 'java' && !seen.contains(i)) {
        issue(
          'migration.java_binding',
          'A java reference could be an alias or binding.',
        );
      }
      if (tokens[i].text == '=' && i > 0 && tokens[i - 1].text == 'java') {
        issue('migration.java_binding', 'Shadowing java is unsupported.');
      }
    }
    if (issues.isNotEmpty) {
      return ScriptMigration(script, null, List.unmodifiable(issues));
    }
    edits.sort((a, b) => b.start.compareTo(a.start));
    var output = script;
    for (final edit in edits) {
      output = output.replaceRange(edit.start, edit.end, edit.value);
    }
    return ScriptMigration(script, output, const []);
  }
}

class _Token {
  _Token(this.text, this.start, this.end);
  final String text;
  final int start;
  final int end;
}

class _Edit {
  _Edit(this.start, this.end, this.value);
  final int start;
  final int end;
  final String value;
}

List<_Token> _lex(String text) {
  final result = <_Token>[];
  var i = 0;
  final stack = <String>[];
  while (i < text.length) {
    final start = i;
    final c = text[i];
    if (RegExp(r'\s').hasMatch(c)) {
      i++;
      continue;
    }
    if (text.startsWith('//', i)) {
      i = text.indexOf('\n', i);
      if (i < 0) break;
      continue;
    }
    if (text.startsWith('/*', i)) {
      final end = text.indexOf('*/', i + 2);
      if (end < 0) throw const FormatException('Unclosed comment');
      i = end + 2;
      continue;
    }
    if (c == '"' || c == "'") {
      i++;
      var closed = false;
      while (i < text.length) {
        if (text[i] == '\\') {
          i += 2;
          continue;
        }
        if (text[i] == c) {
          i++;
          closed = true;
          break;
        }
        if (text[i] == '\n') throw const FormatException('Unclosed string');
        i++;
      }
      if (!closed) throw const FormatException('Unclosed string');
      result.add(_Token('<string>', start, i));
      continue;
    }
    if (RegExp(r'[a-zA-Z_$0-9]').hasMatch(c)) {
      i++;
      while (i < text.length && RegExp(r'[a-zA-Z_$0-9]').hasMatch(text[i])) {
        i++;
      }
    } else if (text.startsWith('=>', i)) {
      i += 2;
    } else {
      i++;
    }
    final token = text.substring(start, i);
    if (['(', '['].contains(token)) stack.add(token);
    if ([')', ']'].contains(token)) {
      if (stack.isEmpty || stack.removeLast() != (token == ')' ? '(' : '[')) {
        throw const FormatException('Unbalanced delimiters');
      }
    }
    result.add(_Token(token, start, i));
  }
  if (stack.isNotEmpty) throw const FormatException('Unbalanced delimiters');
  return result;
}
