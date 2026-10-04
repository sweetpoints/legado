import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:enough_convert/gbk.dart';
import 'package:source_engine/source_engine.dart';

/// Actual legacy overloads supported by the importer and compatibility runtime.
const legacySupportedMethods = {
  'ajax',
  'ajaxAll',
  'connect',
  'get',
  'post',
  'head',
  'put',
  'base64Encode',
  'base64Decode',
  'base64DecodeToByteArray',
  'strToBytes',
  'bytesToStr',
  'hexDecodeToByteArray',
  'hexDecodeToString',
  'hexEncodeToString',
  'md5Encode',
  'md5Encode16',
  'digestHex',
  'digestBase64Str',
  'encodeURI',
};

/// StrResponse and Jsoup response method facades. Dart only transports JSON;
/// response methods are materialized in JS after the synchronous host returns.
const legacyScriptPrelude = r"""
(() => {
  function response(value) {
    if (Array.isArray(value)) return value.map(response);
    if (!value || typeof value !== 'object' || !value.__legacyResponseKind) return value;
    const rawHeaders = value.headers || {};
    function header(name) {
      const key = Object.keys(rawHeaders).find(k => k.toLowerCase() === String(name).toLowerCase());
      return key === undefined ? null : rawHeaders[key];
    }
    function callable(value) {
      const fn = () => value;
      return new Proxy(fn, {
        get(target, name) {
          if (name === 'toJSON') return () => value;
          if (name === 'toString') return () => value == null ? '' : String(value);
          if (name === Symbol.toPrimitive) return () => value;
          if (value != null && name in Object(value)) {
            const member = Object(value)[name];
            return typeof member === 'function' ? member.bind(value) : member;
          }
          return Reflect.get(target, name);
        }
      });
    }
    const headers = Object.assign(Object.create(null), rawHeaders);
    Object.defineProperty(headers, 'get', {value: header});
    Object.defineProperty(headers, 'toString', {value: () => Object.entries(rawHeaders).map(([k,v]) => k + ': ' + v).join('\n')});
    const out = {
      body: callable(value.body), url: callable(value.url),
      code: () => value.status, statusCode: () => value.status,
      message: () => value.message || '', statusMessage: () => value.message || '',
      headers: callable(headers), header, hasHeader: name => header(name) !== null,
      isSuccessful: () => value.status >= 200 && value.status < 300,
      callTime: () => value.callTime || 0,
      bodyAsBytes: () => value.bytes,
      multiHeaders: () => value.multiHeaders || {},
      cookies: () => value.cookieMap || {}, cookie: name => (value.cookieMap || {})[name] ?? null,
      hasCookie: name => Object.prototype.hasOwnProperty.call(value.cookieMap || {}, name),
      raw: () => {throw new Error('legacy.unsupported_response_api: raw');},
      errorBody: () => {throw new Error('legacy.unsupported_response_api: errorBody');},
      toString: () => 'Response{code=' + value.status + ', url=' + value.url + '}'
    };
    return out;
  }
  globalThis.java = new Proxy(Object.create(null), {
    get(_target, name) {
      return (...args) => response(__sourceHostSync('java.' + String(name), args));
    }
  });
})();
""";

/// Legacy host: known JVM operations implemented by Dart, not arbitrary Java.
class LegacyScriptHost implements ScriptHost {
  LegacyScriptHost(this.delegate, {Map<String, String>? variables})
    : variables = variables ?? <String, String>{};
  final ScriptHost delegate;
  final Map<String, String> variables;

  @override
  Future<Object?> call(String method, List<Object?> arguments) async {
    if (!method.startsWith('java.')) return delegate.call(method, arguments);
    final name = method.substring(5);
    void arity(int min, [int? max]) {
      if (arguments.length < min || arguments.length > (max ?? min)) {
        throw UnsupportedError('legacy.unsupported_overload: $method');
      }
    }

    String str(int n) {
      if (n >= arguments.length || arguments[n] is! String) {
        throw ArgumentError('$method requires string argument $n');
      }
      return arguments[n] as String;
    }

    final arg = arguments.isEmpty ? null : arguments.first;
    switch (name) {
      case 'base64Encode':
        arity(1, 2);
        final flags = arguments.length == 2 ? _flags(arguments[1]) : 2;
        final bytes = _encode(str(0), 'UTF-8');
        return _base64Encode(bytes, flags);
      case 'base64Decode':
      case 'base64DecodeToByteArray':
        arity(1, 2);
        if (arg == null) return null;
        if (arg == '') return name == 'base64Decode' ? '' : null;
        final setting = arguments.length == 2 ? arguments[1] : null;
        if (name == 'base64DecodeToByteArray' &&
            (arg as String).trim().isEmpty) {
          return null;
        }
        final flags = setting is num ? _flags(setting) : 0;
        if (setting != null && setting is! num && setting is! String) {
          throw ArgumentError('Expected Base64 flags or charset');
        }
        if (name == 'base64DecodeToByteArray' && setting is String) {
          throw UnsupportedError('legacy.unsupported_overload: $method');
        }
        final bytes = _base64Decode(str(0), flags);
        return name == 'base64DecodeToByteArray'
            ? _signed(bytes)
            : _decode(bytes, setting is String ? setting : 'UTF-8');
      case 'strToBytes':
        arity(1, 2);
        return _signed(
          _encode(str(0), arguments.length == 2 ? str(1) : 'UTF-8'),
        );
      case 'bytesToStr':
        arity(1, 2);
        return _decode(_bytes(arg), arguments.length == 2 ? str(1) : 'UTF-8');
      case 'hexDecodeToByteArray':
      case 'hexDecodeToString':
        arity(1);
        final bytes = _hexDecode(str(0));
        return name == 'hexDecodeToByteArray'
            ? _signed(bytes)
            : _decode(bytes, 'UTF-8');
      case 'hexEncodeToString':
        arity(1);
        return _hex(_encode(str(0), 'UTF-8'));
      case 'md5Encode':
      case 'md5Encode16':
        arity(1);
        final digest = md5.convert(_encode(str(0), 'UTF-8')).toString();
        return name == 'md5Encode16' ? digest.substring(8, 24) : digest;
      case 'digestHex':
      case 'digestBase64Str':
        arity(2);
        final bytes = _digest(str(1)).convert(_encode(str(0), 'UTF-8')).bytes;
        return name == 'digestHex' ? _hex(bytes) : base64Encode(bytes);
      case 'encodeURI':
        arity(1, 2);
        // The Kotlin implementation catches unsupported charset errors and
        // returns the empty string. This is form encoding, not JS encodeURI.
        try {
          return legacyFormEncode(
            str(0),
            arguments.length == 2 ? str(1) : 'UTF-8',
          );
        } on UnsupportedError {
          return '';
        }
      case 'get':
        if (arguments.length == 1) return variables[str(0)] ?? '';
        arity(2, 3);
        return _request(
          str(0),
          'GET',
          arguments[1],
          arguments.length == 3 ? arguments[2] : null,
          followRedirects: false,
          kind: 'jsoup',
        );
      case 'put':
        arity(2);
        final value = str(1);
        variables[str(0)] = value;
        return value;
      case 'ajax':
        arity(1, 2);
        final url = arg is List
            ? (arg.isEmpty ? 'null' : arg.first.toString())
            : arg.toString();
        final response = await _request(
          url,
          'GET',
          null,
          arguments.length == 2 ? arguments[1] : null,
        );
        return response['body'];
      case 'connect':
        arity(1, 3);
        final headers = arguments.length > 1 ? arguments[1] : null;
        if (headers != null && headers is! String) {
          throw UnsupportedError(
            'legacy.unsupported_overload: connect headers must be JSON string or null',
          );
        }
        return _request(
          str(0),
          'GET',
          headers,
          arguments.length == 3 ? arguments[2] : null,
        );
      case 'ajaxAll':
        arity(1, 2);
        if (arguments.length == 2 && arguments[1] != false) {
          throw UnsupportedError('legacy.unsupported_skip_rate_limit');
        }
        if (arg is! List || arg.any((e) => e is! String)) {
          throw ArgumentError('ajaxAll requires string URL array');
        }
        return Future.wait(
          arg.map((url) => _request(url as String, 'GET', null, null)),
        );
      case 'post':
        arity(3, 4);
        return _request(
          str(0),
          'POST',
          arguments[2],
          arguments.length == 4 ? arguments[3] : null,
          body: str(1),
          followRedirects: false,
          kind: 'jsoup',
        );
      case 'head':
        arity(2, 3);
        return _request(
          str(0),
          'HEAD',
          arguments[1],
          arguments.length == 3 ? arguments[2] : null,
          followRedirects: false,
          kind: 'jsoup',
        );
      default:
        throw UnsupportedError('legacy.unsupported_api: $method');
    }
  }

  Future<Map<String, Object?>> _request(
    String url,
    String method,
    Object? headers,
    Object? timeout, {
    String? body,
    bool followRedirects = true,
    String kind = 'str',
  }) async {
    if (url.contains(',')) {
      throw UnsupportedError('legacy.url_options_require_migration');
    }
    if (timeout != null && (timeout is! int || timeout <= 0)) {
      throw ArgumentError('Legacy timeout must be positive milliseconds');
    }
    final start = DateTime.now();
    final raw = await delegate.call('net.request', [
      {
        'url': url,
        'method': method,
        'headers': _headers(headers),
        'followRedirects': followRedirects,
        'body': ?body,
        'timeoutMs': ?timeout,
      },
    ]);
    if (raw is! Map) {
      throw StateError('net.request must return a response object');
    }
    if (kind == 'jsoup' && (raw['status'] as int? ?? 0) >= 400) {
      throw EngineException('legacy.http_error', 'HTTP ${raw['status']}');
    }
    final cookieMap = <String, String>{};
    for (final value in (raw['cookies'] as List? ?? [])) {
      try {
        final cookie = Cookie.fromSetCookieValue(value.toString());
        cookieMap[cookie.name] = cookie.value;
      } on FormatException {
        continue;
      }
    }
    return {
      ...Map<String, Object?>.from(raw),
      'cookieMap': cookieMap,
      if (raw['bytes'] is List)
        'bytes': _signed((raw['bytes'] as List).cast<int>()),
      '__legacyResponseKind': kind,
      'callTime': DateTime.now().difference(start).inMilliseconds,
    };
  }
}

Map<String, String> _headers(Object? input) {
  if (input == null) return {};
  final raw = input is String ? jsonDecode(input) : input;
  if (raw is! Map || raw.entries.any((e) => e.key == null || e.value == null)) {
    throw ArgumentError(
      'Request headers must be an object with non-null values',
    );
  }
  return raw.map((k, v) => MapEntry(k.toString(), v.toString()));
}

int _flags(Object? value) {
  if (value is! int || value < 0 || value & ~31 != 0) {
    throw ArgumentError('Invalid Android Base64 flags');
  }
  return value;
}

String _base64Encode(List<int> bytes, int flags) {
  var text = flags & 8 != 0 ? base64UrlEncode(bytes) : base64Encode(bytes);
  if (flags & 1 != 0) text = text.replaceAll('=', '');
  if (flags & 2 == 0 && text.isNotEmpty) {
    final newline = flags & 4 != 0 ? '\r\n' : '\n';
    final chunks = <String>[];
    for (var i = 0; i < text.length; i += 76) {
      chunks.add(
        text.substring(i, i + 76 > text.length ? text.length : i + 76),
      );
    }
    text = '${chunks.join(newline)}$newline';
  }
  return text;
}

List<int> _base64Decode(String text, int flags) {
  final cleaned = text.replaceAll(RegExp(r'\s'), '');
  if (flags & 8 == 0 && RegExp(r'[-_]').hasMatch(cleaned)) {
    throw const FormatException('URL-safe alphabet requires flag 8');
  }
  return base64Decode(base64.normalize(cleaned));
}

List<int> _bytes(Object? value) {
  if (value is! List || value.any((e) => e is! int || e < -128 || e > 255)) {
    throw ArgumentError('Expected a Java byte array (-128..255)');
  }
  return value.cast<int>().map((e) => e & 255).toList();
}

List<int> _signed(List<int> bytes) =>
    bytes.map((e) => e > 127 ? e - 256 : e).toList();
String _hex(List<int> bytes) =>
    bytes.map((e) => e.toRadixString(16).padLeft(2, '0')).join();
List<int> _hexDecode(String value) {
  // Hutool HexUtil supports odd length by prepending a zero nibble.
  if (value.length.isOdd) value = '0$value';
  if (!RegExp(r'^[0-9a-fA-F]*$').hasMatch(value)) {
    throw const FormatException('Invalid hex string');
  }
  return [
    for (var i = 0; i < value.length; i += 2)
      int.parse(value.substring(i, i + 2), radix: 16),
  ];
}

Hash _digest(String name) => switch (name.toUpperCase().replaceAll('-', '')) {
  'MD5' => md5,
  'SHA1' => sha1,
  'SHA224' => sha224,
  'SHA256' => sha256,
  'SHA384' => sha384,
  'SHA512' => sha512,
  _ => throw UnsupportedError('legacy.unsupported_digest: $name'),
};
String _charset(String name) =>
    name.toUpperCase().replaceAll('-', '').replaceAll('_', '');
List<int> _encode(String value, String charset) {
  switch (_charset(charset)) {
    case 'UTF8':
      return utf8.encode(value);
    case 'GBK':
    case 'GB2312':
    case 'CP936':
      return gbk.encode(value);
    case 'ISO88591':
    case 'LATIN1':
      return value.runes.map((c) => c < 256 ? c : 63).toList();
    case 'ASCII':
    case 'USASCII':
      return value.runes.map((c) => c < 128 ? c : 63).toList();
    case 'UTF16':
      return [254, 255, ..._encode(value, 'UTF16BE')];
    case 'UTF16BE':
      return [
        for (final c in value.codeUnits) ...[c >> 8, c & 255],
      ];
    case 'UTF16LE':
      return [
        for (final c in value.codeUnits) ...[c & 255, c >> 8],
      ];
    default:
      throw UnsupportedError('legacy.unsupported_charset: $charset');
  }
}

String _decode(List<int> bytes, String charset) {
  switch (_charset(charset)) {
    case 'UTF8':
      return utf8.decode(bytes, allowMalformed: true);
    case 'GBK':
    case 'GB2312':
    case 'CP936':
      return gbk.decode(bytes);
    case 'ISO88591':
    case 'LATIN1':
      return latin1.decode(bytes);
    case 'ASCII':
    case 'USASCII':
      return String.fromCharCodes(bytes.map((c) => c < 128 ? c : 0xfffd));
    case 'UTF16':
      if (bytes.length >= 2 && bytes[0] == 255 && bytes[1] == 254) {
        return _decode(bytes.sublist(2), 'UTF16LE');
      }
      if (bytes.length >= 2 && bytes[0] == 254 && bytes[1] == 255) {
        return _decode(bytes.sublist(2), 'UTF16BE');
      }
      return _decode(bytes, 'UTF16BE');
    case 'UTF16BE':
    case 'UTF16LE':
      final little = _charset(charset) == 'UTF16LE';
      return String.fromCharCodes([
        for (var i = 0; i + 1 < bytes.length; i += 2)
          little
              ? bytes[i] | (bytes[i + 1] << 8)
              : (bytes[i] << 8) | bytes[i + 1],
        if (bytes.length.isOdd) 0xfffd,
      ]);
    default:
      throw UnsupportedError('legacy.unsupported_charset: $charset');
  }
}

/// Java URLEncoder's form escaping (including '+' for spaces).
String legacyFormEncode(String value, [String charset = 'UTF-8']) {
  final out = StringBuffer();
  for (final b in _encode(value, charset)) {
    if ((b >= 65 && b <= 90) ||
        (b >= 97 && b <= 122) ||
        (b >= 48 && b <= 57) ||
        [45, 46, 42, 95].contains(b)) {
      out.writeCharCode(b);
    } else if (b == 32) {
      out.write('+');
    } else {
      out.write('%${b.toRadixString(16).toUpperCase().padLeft(2, '0')}');
    }
  }
  return out.toString();
}
