import 'dart:convert';

import 'package:source_engine/source_engine.dart';

/// Immutable identity/field facade; mutable source state stays in the host.
String portableLegacySourcePrelude(Map<String, Object?> original) =>
    '''
(() => {
 const data = ${jsonEncode(original)};
 globalThis.source = new Proxy(Object.create(null), {get:(_,name) => {
   if(name==='getKey')return ()=>data.bookSourceUrl;
   if(name==='getTag')return ()=>data.bookSourceName || data.bookSourceUrl;
   if(name==='getVariable')return ()=>__sourceHostSync('sourceState.getVariable',[]);
   if(name==='setVariable'||name==='putVariable')return value=>__sourceHostSync('sourceState.putVariable',[value]);
   if(name==='variable')return __sourceHostSync('sourceState.getVariable',[]);
   return Object.prototype.hasOwnProperty.call(data,name)?data[name]:undefined;
 }});
 globalThis.sourceApi=globalThis.source;
})();
''';

/// Caller-owned JSON state can be persisted with the source engine session.
class PortableLegacySourceStateHost implements ScriptHost {
  const PortableLegacySourceStateHost(this.delegate, this.state);
  final ScriptHost delegate;
  final Map<String, Object?> state;
  @override
  Future<Object?> call(String method, List<Object?> args) async {
    switch (method) {
      case 'sourceState.getVariable':
        if (args.isNotEmpty) {
          throw const EngineException(
            'invalid_request',
            'getVariable expects no arguments',
          );
        }
        return state['variable'];
      case 'sourceState.putVariable':
        if (args.length != 1 ||
            (args.single != null && args.single is! String)) {
          throw const EngineException(
            'invalid_request',
            'putVariable expects a nullable string',
          );
        }
        state['variable'] = args.single;
        return null;
      case 'javaHost.log':
      case 'javaHost.logType':
      case 'javaHost.toast':
      case 'javaHost.longToast':
        if (args.length != 1) {
          throw const EngineException(
            'invalid_request',
            'Legacy diagnostic expects one argument',
          );
        }
        return null;
      default:
        return delegate.call(method, args);
    }
  }
}

/// Explicit capabilities, checked both for entry scripts and the exact embedded
/// source comment they eval. This does not certify arbitrary Java/JVM APIs.
class PortableLegacyScriptCapabilities {
  const PortableLegacyScriptCapabilities(this.original);
  final Map<String, Object?> original;
  static const javaMethods = {
    'ajax',
    'ajaxAll',
    'connect',
    'get',
    'put',
    'getString',
    'getStringList',
    'getElement',
    'getElements',
    'log',
    'logType',
    'toast',
    'longToast',
    'base64Encode',
    'base64Decode',
    'md5Encode',
    'encodeURI',
    'strToBytes',
    'bytesToStr',
  };
  static const sourceMethods = {
    'getKey',
    'getTag',
    'getVariable',
    'setVariable',
    'putVariable',
  };
  bool supports(String script) => _supports(script, allowComment: true);
  bool _supports(String script, {required bool allowComment}) {
    final evalComment = RegExp(
      r'eval\s*\(\s*String\s*\(\s*source\.bookSourceComment\s*\)\s*\)',
    );
    if (evalComment.hasMatch(script)) {
      final comment = original['bookSourceComment'];
      if (!allowComment ||
          comment is! String ||
          !_supports(comment, allowComment: false)) {
        return false;
      }
      script = script.replaceAll(evalComment, '');
    }
    final code = legacyCodeOutsideLiterals(script);
    if (code == null) return false;
    if (RegExp(
      r'\b(?:eval|Function|Java|JavaAdapter|Packages|importClass|importPackage|globalThis|global|this|self|document|window)\b',
    ).hasMatch(code)) {
      return false;
    }
    for (final call in RegExp(
      r'\bjava\s*\.\s*([A-Za-z_$][\w$]*)',
    ).allMatches(code)) {
      if (!javaMethods.contains(call[1])) return false;
    }
    if (RegExp(r'\bjava\s*\[').hasMatch(code)) return false;
    for (final access in RegExp(
      r'\bsource(?:Api)?\s*\.\s*([A-Za-z_$][\w$]*)',
    ).allMatches(code)) {
      final name = access[1]!;
      if (!sourceMethods.contains(name) &&
          name != 'variable' &&
          !original.containsKey(name)) {
        return false;
      }
    }
    if (RegExp(r'\borg\b').hasMatch(code)) {
      final stripped = code.replaceAll(
        RegExp(
          r'org\s*\.\s*jsoup\s*\.\s*Jsoup\s*\.\s*(?:parseBodyFragment|parse)\s*\(',
        ),
        '(',
      );
      if (RegExp(r'\borg\b').hasMatch(stripped)) return false;
    }
    return true;
  }
}

/// Masks JS strings, comments and regexp literals before host capability checks.
/// A regexp is admitted only in an operand position; malformed literals fail
/// conservatively instead of confusing quoted pattern characters with strings.
String? legacyCodeOutsideLiterals(String script) {
  final out = StringBuffer();
  String? last;
  for (var i = 0; i < script.length; i++) {
    final c = script[i];
    if (c == '`') return null;
    if (c == '"' || c == "'") {
      final quote = c;
      var closed = false;
      for (i++; i < script.length; i++) {
        if (script[i] == r'\') {
          i++;
          continue;
        }
        if (script[i] == quote) {
          closed = true;
          break;
        }
      }
      if (!closed) return null;
      out.write(' ');
      last = 'v';
      continue;
    }
    if (c == '/' && i + 1 < script.length) {
      if (script[i + 1] == '/') {
        while (i < script.length && script[i] != '\n') {
          i++;
        }
        out.write('\n');
        continue;
      }
      if (script[i + 1] == '*') {
        final end = script.indexOf('*/', i + 2);
        if (end < 0) return null;
        i = end + 1;
        out.write(' ');
        continue;
      }
      if (last == null || '([{,:;=!?&|+-*%^~<>'.contains(last)) {
        var closed = false;
        var bracket = false;
        for (i++; i < script.length; i++) {
          final token = script[i];
          if (token == r'\') {
            i++;
            continue;
          }
          if (token == '[') bracket = true;
          if (token == ']') bracket = false;
          if (token == '/' && !bracket) {
            closed = true;
            break;
          }
          if (token == '\n' || token == '\r') return null;
        }
        if (!closed) return null;
        while (i + 1 < script.length &&
            RegExp(r'[A-Za-z]').hasMatch(script[i + 1])) {
          i++;
        }
        out.write(' ');
        last = 'v';
        continue;
      }
    }
    out.write(c);
    if (c.trim().isNotEmpty) last = c;
  }
  return out.toString();
}
