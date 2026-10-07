import 'dart:convert';
import 'dart:io';

import 'legacy_dom.dart';
import 'legacy_cookie.dart';

import 'package:crypto/crypto.dart';
import 'package:enough_convert/gbk.dart';
import 'package:source_engine/source_engine.dart';

/// Actual legacy overloads supported by the importer and compatibility runtime.
const legacySupportedMethods = {
  'createSymmetricCrypto',
  'aesBase64DecodeToString',
  'desEncodeToBase64String',
  'getWebViewUA',
  'HMacHex',
  'HMacBase64',
  'androidId',
  'randomUUID',
  'toNumChapter',
  'log',
  'logType',
  'toast',
  'longToast',
  'timeFormat',
  'timeFormatUTC',
  't2s',
  's2t',
  'getCookie',
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
  'getString',
  'getStringList',
  'getElement',
  'getElements',
  'encodeURI',
  'cacheContent',
  'showBrowser',
  'startBrowser',
  'startBrowserAwait',
  'openUrl',
  'openVideoPlayer',
};

/// StrResponse and Jsoup response method facades. Dart only transports JSON;
/// response methods are materialized in JS after the synchronous host returns.
const legacyScriptPrelude =
    legacyCookiePrelude +
    legacyDomPrelude +
    r"""
(() => {
  function response(value) {
    if (Array.isArray(value)) return value.map(response);
    if (!value || typeof value !== 'object' || !value.__legacyResponseKind) return value;
    const rawHeaders = value.__legacyResponseKind === 'str' && value.multiHeaders
      ? Object.fromEntries(Object.entries(value.multiHeaders).map(([k,v]) => [k, v.length ? v[v.length - 1] : '']))
      : value.headers || {};
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
  function element(value) {
    if (value && (value.__legacyDom || value.__legacyDomList)) return __legacyDomMaterialize(value);
    if (typeof value !== 'string') return value;
    const rootTag = /^<([A-Za-z][A-Za-z0-9:_-]*)/.exec(value);
    if (!rootTag) throw new Error('legacy.invalid_element_serialization');
    const rootRule = 'tag.' + rootTag[1] + '.0';
    const query = (method, rule) => __sourceHostSync('parse.' + method, ['@legacy:' + rootRule + rule, value, false, globalThis.baseUrl]);
    const out = {
      text: () => query('getString', '@text'),
      attr: name => query('getString', '@' + String(name)),
      outerHtml: () => value, toString: () => value, toJSON: () => value,
      select: selector => elementList(query('getElements', '@' + String(selector))),
      selectFirst: selector => elementList(query('getElements', '@' + String(selector))).first(),
      html: () => {throw new Error('legacy.unsupported_element_api: html');}
    };
    return out;
  }
  function elementList(values) {
    if (values && values.__legacyDomList) return __legacyDomMaterialize(values);
    const list = (values || []).map(element);
    Object.defineProperties(list, {
      size: {value: () => list.length}, get: {value: index => list[index]},
      first: {value: () => list[0] || null}, last: {value: () => list[list.length-1] || null},
      text: {value: () => list.map(e => e.text()).join(' ')},
      attr: {value: name => list.length ? list[0].attr(name) : ''},
      select: {value: selector => elementList(list.flatMap(e => __sourceHostSync('parse.getElements', ['@legacy:'+String(selector), e.outerHtml(), false, globalThis.baseUrl])))},
      html: {value: () => {throw new Error('legacy.unsupported_element_api: html');}}
    });
    return list;
  }
  globalThis.java = new Proxy(Object.create(null), {
    get(_target, name) {
      return (...args) => {
        if (['getString','getStringList','getElement','getElements'].includes(String(name))) {
          const unescape = String(name) === 'getString' && args.length === 2 && typeof args[1] === 'boolean' ? args[1] : true;
          if (String(name) === 'getString' && args.length === 2 && typeof args[1] === 'boolean') args = [args[0]];
          if (['getElement','getElements'].includes(String(name)) && args.length !== 1) {
            throw new Error('legacy.unsupported_overload: ' + String(name));
          }
          const content = args.length > 1 && args[1] != null ? args[1] : globalThis.result;
          args = [args[0], content, args.length > 2 ? args[2] : false, globalThis.baseUrl];
          if (String(name) === 'getString') args.push(unescape);
        }
        if (globalThis.__legacyUseNativeHttp === true) {
          if (globalThis.__legacyHeaderEvaluation === true && ['get','put'].includes(String(name))
              && !(String(name) === 'get' && args.length !== 1)) {
            return __sourceHostSync('javaHttp.header' + (String(name) === 'get' ? 'Get' : 'Put'), args);
          }
          if (['ajax','connect','ajaxAll'].includes(String(name))) {
            const plan = __sourceHostSync('javaHttp.prepareHeader', [String(name), args]);
            if (plan !== null) {
              if (!plan || typeof plan.script !== 'string' || !Number.isSafeInteger(plan.count) || plan.count < 0) {
                throw new Error('legacy.invalid_header_plan');
              }
              const evaluations = [];
              for (let index = 0; index < plan.count; index++) {
                const previous = globalThis.__legacyHeaderEvaluation;
                globalThis.__legacyHeaderEvaluation = true;
                try {
                  const value = eval(plan.script);
                  if (value && typeof value.then === 'function') {
                    throw new Error('legacy.async_header_requires_await');
                  }
                  evaluations.push({value, failed:false});
                } catch (error) {
                  if (String(error).includes('legacy.async_header_requires_await')) throw error;
                  // BaseSource.getHeaderMap also retains default headers when its rule fails.
                  evaluations.push({value:null, failed:true});
                } finally {
                  globalThis.__legacyHeaderEvaluation = previous;
                }
              }
              return response(__sourceHostSync('javaHttp.' + String(name) + 'Resolved', [args, evaluations]));
            }
          }
        }
        const value = __sourceHostSync('java.' + String(name), args);
        if (String(name) === 'createSymmetricCrypto') {
          if (!value || !value.__legacyCryptoState) throw new Error('legacy.invalid_crypto_state');
          let state = value.__legacyCryptoState;
          const crypto = Object.create(null);
          const invoke = (operation, values) => {
            const result = __sourceHostSync('javaHost.cryptoCall', [state, operation, values]);
            if (!result || !result.state) throw new Error('legacy.invalid_crypto_state');
            state = result.state;
            return result.value;
          };
          for (const operation of ['encrypt','encryptHex','encryptBase64','decrypt','decryptStr']) {
            crypto[operation] = (...values) => invoke(operation, values);
          }
          crypto.setIv = iv => {invoke('setIv', [iv]); return crypto;};
          return crypto;
        }

        if (String(name) === 'log') return args[0];
        if (String(name) === 'getElement') return element(value);
        if (String(name) === 'getElements') return elementList(value);
        return response(value);
      };
    }
  });
})();
""";

/// Legacy host: known JVM operations implemented by Dart, not arbitrary Java.
class LegacyScriptHost implements ScriptHost {
  LegacyScriptHost(
    this.delegate, {
    Map<String, String>? variables,
    this.useNativeHttp = false,
  }) : variables = variables ?? <String, String>{};
  final bool useNativeHttp;
  final ScriptHost delegate;
  final Map<String, String> variables;

  @override
  Future<Object?> call(String method, List<Object?> arguments) async {
    if (!method.startsWith('java.')) return delegate.call(method, arguments);
    final name = method.substring(5);
    if (useNativeHttp &&
        const {
          'ajax',
          'get',
          'post',
          'head',
          'connect',
          'ajaxAll',
        }.contains(name) &&
        !(name == 'get' && arguments.length == 1)) {
      // Android owns the original overloads, URL options, headers and request
      // compilation. Never retry a native failure through another transport.
      return delegate.call('javaHttp.$name', arguments);
    }

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
      case 'createSymmetricCrypto':
        arity(2, 3);
        str(0);
        final key = arguments[1];
        final iv = arguments.length == 3 ? arguments[2] : null;
        void bytes(Object? value) {
          if (value == null) return;
          if (value is! List ||
              value.any((b) => b is! int || b < -128 || b > 255)) {
            throw ArgumentError('Legacy crypto bytes required');
          }
        }
        if (key is String) {
          if (iv != null && iv is! String) {
            throw ArgumentError('Legacy string IV required');
          }
        } else {
          bytes(key);
          bytes(iv);
        }
        return delegate.call('javaHost.cryptoCreate', arguments);
      case 'aesBase64DecodeToString':
      case 'desEncodeToBase64String':
        arity(4);
        for (var i = 0; i < 4; i++) {
          str(i);
        }
        return delegate.call('javaHost.$name', arguments);
      case 'getWebViewUA':
        arity(0);
        return delegate.call('javaHost.getWebViewUA', arguments);

      case 'HMacHex':
      case 'HMacBase64':
        arity(3);
        str(0);
        str(1);
        str(2);
        return delegate.call('javaHost.$name', arguments);
      case 'androidId':
      case 'randomUUID':
        arity(0);
        return delegate.call('javaHost.$name', arguments);
      case 'toNumChapter':
        arity(1);
        if (arg != null) str(0);
        return delegate.call('javaHost.toNumChapter', arguments);

      case 'log':
      case 'logType':
      case 'toast':
      case 'longToast':
        arity(1);
        return delegate.call('javaHost.$name', arguments);
      case 't2s':
      case 's2t':
        arity(1);
        str(0);
        return delegate.call('javaHost.$name', arguments);
      case 'getCookie':
        arity(1, 2);
        str(0);
        if (arguments.length == 2 && arguments[1] != null) str(1);
        return delegate.call('javaHost.getCookie', arguments);
      case 'timeFormat':
      case 'timeFormatUTC':
        arity(name == 'timeFormat' ? 1 : 3);
        final time = arguments[0];
        if (time is! num ||
            !time.isFinite ||
            (time is! int && time != time.truncateToDouble()) ||
            time < -9223372036854775808 ||
            time > 9223372036854775807) {
          throw ArgumentError('Time must be signed integer milliseconds');
        }
        if (name == 'timeFormatUTC') {
          str(1);
          final offset = arguments[2];
          if (offset is! num ||
              !offset.isFinite ||
              offset != offset.truncateToDouble() ||
              offset < -2147483648 ||
              offset > 2147483647) {
            throw ArgumentError(
              'UTC offset must be signed integer milliseconds',
            );
          }
        }
        return delegate.call('javaHost.$name', arguments);
      case 'cacheContent':
        arity(2, 3);
        // Native callbacks accept both implicit batch context and explicit ID.
        if (arguments.length == 3) str(0);
        str(arguments.length - 1);
        return delegate.call('batch.cacheContent', arguments);
      case 'openUrl':
        arity(1, 2);
        str(0);
        if (arguments.length == 2 && arguments[1] != null) str(1);
        return delegate.call('browser.openUrl', arguments);
      case 'openVideoPlayer':
        arity(2, 3);
        str(0);
        str(1);
        if (arguments.length == 3 && arguments[2] is! bool) {
          throw ArgumentError('isFloat must be boolean');
        }
        return delegate.call('browser.video', arguments);
      case 'showBrowser':
        arity(1, 4);
        str(0);
        for (var i = 1; i < arguments.length; i++) {
          if (arguments[i] != null) str(i);
        }
        return delegate.call('browser.show', arguments);
      case 'startBrowser':
        arity(2, 3);
        str(0);
        str(1);
        if (arguments.length == 3 && arguments[2] != null) str(2);
        return delegate.call('browser.start', arguments);
      case 'startBrowserAwait':
        arity(2, 4);
        final url = str(0);
        final title = str(1);
        if (arguments.length > 2 && arguments[2] is! bool) {
          throw ArgumentError('refetchAfterSuccess must be boolean');
        }
        if (arguments.length == 4 && arguments[3] != null) str(3);
        final raw = await delegate.call('browser.open', [
          url,
          title,
          {
            'refetchAfterSuccess': arguments.length > 2 ? arguments[2] : true,
            if (arguments.length == 4) 'html': arguments[3],
          },
        ]);
        if (raw is! Map) {
          throw const EngineException(
            'invalid_host_response',
            'Browser response required',
          );
        }
        if (raw['refetch'] == true) {
          return _request(url, 'GET', null, null);
        }
        if (raw['body'] is! String) {
          throw const EngineException(
            'invalid_host_response',
            'Browser body required',
          );
        }
        return {
          ...Map<String, Object?>.from(raw),
          'url': raw['url'] ?? url,
          'status': 200,
          '__legacyResponseKind': 'str',
        };

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
      case 'getString':
      case 'getStringList':
      case 'getElement':
      case 'getElements':
        arity(2, name == 'getString' ? 5 : 4);
        final rule = arguments[0];
        if (rule == null || rule == '') {
          return switch (name) {
            'getString' => '',
            'getStringList' => null,
            'getElements' => <Object?>[],
            _ => null,
          };
        }
        if (rule is! String) {
          throw UnsupportedError(
            'legacy.unsupported_overload: non-string rule',
          );
        }
        if (arguments.length > 2 && arguments[2] is! bool) {
          throw ArgumentError('isUrl must be boolean');
        }
        final normalized = rule.toLowerCase().startsWith('@css:')
            ? '@legacy:${rule.substring(5)}'
            : [
                '@text',
                '@ownText',
                '@textNodes',
                '@html',
                '@all',
                '@children',
              ].contains(rule)
            ? '@legacy:$rule'
            : rule.startsWith('@') || rule.startsWith(r'$')
            ? rule
            : '@legacy:$rule';
        if (arguments.length > 4 && arguments[4] is! bool) {
          throw ArgumentError('unescape must be boolean');
        }
        final parsed = await delegate.call('parse.$name', [
          normalized,
          arguments[1],
          name == 'getString'
              ? false
              : (arguments.length > 2 ? arguments[2] : false),
          arguments.length > 3 ? arguments[3] : null,
        ]);
        if (name != 'getString') return parsed;
        var text = parsed?.toString() ?? '';
        if (arguments.length < 5 || arguments[4] == true) {
          text = unescapeHtml4(text);
        }
        if (arguments.length > 2 && arguments[2] == true) {
          final base = arguments.length > 3 ? arguments[3] : null;
          if (base == null) {
            if (text.trim().isEmpty) return '';
            // Reuse the delegate's URL context with the already-decoded value.
            return delegate.call('parse.getString', [
              r'$.value',
              {'value': text},
              true,
              null,
            ]);
          }
          final uri = Uri.parse(base.toString());
          if (!uri.isAbsolute) {
            throw const EngineException(
              'invalid_url',
              'parse base URL must be absolute',
            );
          }
          if (text.trim().isEmpty) return base.toString();
          return uri.resolve(text).toString();
        }
        return text;
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
    final inheritHeaders = kind == 'str' && headers == null;
    final requestHeaders = _headers(headers);
    if (kind == 'jsoup' &&
        !requestHeaders.keys.any((k) => k.toLowerCase() == 'user-agent')) {
      requestHeaders['User-Agent'] = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36';
    }
    if (kind == 'jsoup' &&
        method == 'POST' &&
        !requestHeaders.keys.any((k) => k.toLowerCase() == 'content-type')) {
      requestHeaders['Content-Type'] =
          'application/x-www-form-urlencoded; charset=UTF-8';
    }
    final start = DateTime.now();
    final raw = await delegate.call('net.request', [
      {
        'url': url,
        'method': method,
        'inheritHeaders': inheritHeaders,
        if (!inheritHeaders) 'headers': requestHeaders,
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

/// New asynchronous utility namespace. Kept beside the compatibility codecs
/// during migration so both contracts use the same tested implementation.
/// This adapter never creates a `java` object in the JS environment.
class SourceUtilityHost implements ScriptHost {
  SourceUtilityHost(this.delegate) : _utilities = LegacyScriptHost(delegate);
  final ScriptHost delegate;
  final LegacyScriptHost _utilities;
  static const methods = <String, String>{
    'encoding.base64EncodeWithFlags': 'base64Encode',
    'encoding.base64DecodeWithCharset': 'base64Decode',
    'encoding.base64DecodeWithFlags': 'base64Decode',
    'encoding.base64DecodeBytes': 'base64DecodeToByteArray',
    'encoding.strToBytes': 'strToBytes',
    'encoding.bytesToStr': 'bytesToStr',
    'encoding.hexEncode': 'hexEncodeToString',
    'encoding.hexDecode': 'hexDecodeToString',
    'encoding.hexDecodeBytes': 'hexDecodeToByteArray',
    'encoding.formEncode': 'encodeURI',
    'crypto.md5': 'md5Encode',
    'crypto.md5Short': 'md5Encode16',
    'crypto.digestHex': 'digestHex',
    'crypto.digestBase64': 'digestBase64Str',
  };
  @override
  Future<Object?> call(String method, List<Object?> arguments) {
    final mapped = methods[method];
    if (mapped != null) return _utilities.call('java.$mapped', arguments);
    if (method == 'encoding.unescapeHtml4') {
      if (arguments.length != 1 || arguments[0] is! String) {
        throw ArgumentError('unescapeHtml4 requires one string');
      }
      return Future.value(unescapeHtml4(arguments.single as String));
    }
    if (method == 'encoding.formDecode') {
      if (arguments.isEmpty ||
          arguments.length > 2 ||
          arguments[0] is! String ||
          (arguments.length == 2 && arguments[1] is! String)) {
        throw ArgumentError(
          'formDecode requires a string and optional charset',
        );
      }
      return Future.value(
        legacyFormDecode(
          arguments[0] as String,
          arguments.length == 2 ? arguments[1] as String : 'UTF-8',
        ),
      );
    }
    return delegate.call(method, arguments);
  }
}

/// Matching form decoding for the new API. No old java.decodeURI is claimed.
String legacyFormDecode(String value, [String charset = 'UTF-8']) {
  final out = StringBuffer();
  for (var i = 0; i < value.length;) {
    if (value[i] == '+') {
      out.write(' ');
      i++;
      continue;
    }
    if (value[i] != '%') {
      out.write(value[i]);
      i++;
      continue;
    }
    final bytes = <int>[];
    while (i < value.length && value[i] == '%') {
      if (i + 2 >= value.length ||
          !RegExp(r'^[0-9a-fA-F]{2}$')
              .hasMatch(value.substring(i + 1, i + 3))) {
        throw const FormatException('Malformed percent escape');
      }
      bytes.add(int.parse(value.substring(i + 1, i + 3), radix: 16));
      i += 3;
    }
    out.write(_decode(bytes, charset));
  }
  return out.toString();
}
