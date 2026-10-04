import 'dart:convert';
import 'dart:async';
import 'dart:collection';
import 'dart:io';

import 'contracts.dart';

import 'package:enough_convert/gbk.dart';

typedef CharsetDecoder = String Function(List<int> bytes, String charset);

class NetworkResponse {
  const NetworkResponse(
    this.url,
    this.status,
    this.headers,
    this.body, {
    this.message = '',
    this.bytes = const [],
    this.multiHeaders = const {},
    this.cookies = const [],
  });
  final Uri url;
  final int status;
  final Map<String, String> headers;
  final String body;
  final String message;
  final List<int> bytes;
  final Map<String, List<String>> multiHeaders;
  final List<String> cookies;
  Map<String, Object?> toJson() => {
    'url': url.toString(),
    'status': status,
    'headers': headers,
    'body': body,
    'message': message,
    'bytes': bytes,
    'multiHeaders': multiHeaders,
    'cookies': cookies,
  };
}

class NetworkClient {
  NetworkClient({
    this.charsetDecoder,
    this.maxConcurrentRequests = 4,
    this.minRequestInterval = Duration.zero,
  }) {
    if (maxConcurrentRequests < 1 || minRequestInterval.isNegative) {
      throw ArgumentError('Invalid network scheduling limits');
    }
  }
  final int maxConcurrentRequests;
  final Duration minRequestInterval;
  int _active = 0;
  final Queue<Completer<void>> _waiters = Queue();
  DateTime _nextRequest = DateTime.fromMillisecondsSinceEpoch(0);

  List<Map<String, Object?>> exportCookies() => _cookies
      .where((c) => !c.expired)
      .map(
        (c) => {
          'name': c.cookie.name,
          'value': c.cookie.value,
          'domain': c.domain,
          'path': c.path,
          'hostOnly': c.hostOnly,
          'secure': c.cookie.secure,
          'httpOnly': c.cookie.httpOnly,
          'maxAge': c.cookie.maxAge,
          'expires': c.cookie.expires?.toUtc().toIso8601String(),
          'created': c.created.toUtc().toIso8601String(),
        },
      )
      .toList();
  void restoreCookies(List<Map<String, Object?>> records) {
    final restored = <_StoredCookie>[];
    for (final record in records) {
      final domain = record['domain'] as String;
      final path = record['path'] as String;
      if (domain.isEmpty || !path.startsWith('/')) {
        throw const EngineException('invalid_session', 'Invalid cookie scope');
      }
      final cookie = Cookie(record['name'] as String, record['value'] as String)
        ..secure = record['secure'] as bool? ?? false
        ..httpOnly = record['httpOnly'] as bool? ?? false
        ..maxAge = record['maxAge'] as int?;
      if (record['expires'] != null) {
        cookie.expires = DateTime.parse(record['expires'] as String);
      }
      restored.add(
        _StoredCookie(
          cookie,
          domain,
          path,
          record['hostOnly'] as bool? ?? true,
          DateTime.parse(record['created'] as String),
        ),
      );
    }
    _cookies
      ..clear()
      ..addAll(restored.where((c) => !c.expired));
  }

  String cookieHeader(Uri uri) {
    _cookies.removeWhere((c) => c.expired);
    return _cookies
        .where((c) => c.matches(uri))
        .map((c) => '${c.cookie.name}=${c.cookie.value}')
        .join('; ');
  }

  /// Import cookies with their supplied domain/path/security attributes.
  /// Invalid unrelated domains are ignored. This is also used by adapters.
  void importCookies(Uri uri, Iterable<Cookie> cookies) {
    for (final cookie in cookies) {
      final domain =
          cookie.domain?.replaceFirst(RegExp(r'^\.'), '').toLowerCase() ??
          uri.host.toLowerCase();
      if (uri.host != domain && !uri.host.endsWith('.$domain')) {
        continue;
      }
      final requestPath = uri.path;
      final defaultPath =
          (!requestPath.startsWith('/') || requestPath.lastIndexOf('/') <= 0)
          ? '/'
          : requestPath.substring(0, requestPath.lastIndexOf('/'));
      final path = cookie.path?.startsWith('/') == true
          ? cookie.path!
          : defaultPath;
      _cookies.removeWhere(
        (c) =>
            c.cookie.name == cookie.name &&
            c.domain == domain &&
            c.path == path,
      );
      _cookies.add(_StoredCookie(cookie, domain, path, cookie.domain == null));
    }
  }

  /// Browser Cookie headers lack original attributes. Imported entries are
  /// host-only, HTTPS-secure when appropriate, scoped to the response directory.
  void importBrowserCookieHeader(Uri uri, String header) {
    final cookies = <Cookie>[];
    for (final part in header.split(';')) {
      final equals = part.indexOf('=');
      if (equals <= 0) continue;
      try {
        cookies.add(
          Cookie(
            part.substring(0, equals).trim(),
            part.substring(equals + 1).trim(),
          )..secure = uri.scheme == 'https',
        );
      } on FormatException {
        continue;
      }
    }
    importCookies(uri, cookies);
  }

  Future<void> _acquire(CancellationToken? token) async {
    token?.throwIfCancelled();
    if (_active < maxConcurrentRequests) {
      _active++;
      return;
    }
    final waiter = Completer<void>();
    _waiters.add(waiter);
    await Future.any([waiter.future, if (token != null) token.whenCancelled]);
    if (token?.isCancelled ?? false) {
      if (!_waiters.remove(waiter) && waiter.isCompleted) _release();
      token!.throwIfCancelled();
    }
  }

  void _release() {
    if (_waiters.isEmpty) {
      _active--;
    } else {
      _waiters.removeFirst().complete();
    }
  }

  final CharsetDecoder? charsetDecoder;
  final HttpClient _client = HttpClient();
  final List<_StoredCookie> _cookies = [];
  Future<NetworkResponse> request(
    Uri uri, {
    String method = 'GET',
    Map<String, String> headers = const {},
    String? body,
    Duration timeout = const Duration(seconds: 30),
    CancellationToken? cancellation,
    int maxRedirects = 5,
    bool followRedirects = true,
  }) async {
    await _acquire(cancellation);
    try {
      final now = DateTime.now();
      final scheduled = _nextRequest.isAfter(now) ? _nextRequest : now;
      _nextRequest = scheduled.add(minRequestInterval);
      final wait = scheduled.difference(now);
      if (wait > Duration.zero) {
        await Future.any([
          Future<void>.delayed(wait),
          if (cancellation != null) cancellation.whenCancelled,
        ]);
      }
      cancellation?.throwIfCancelled();
      return await _request(
        uri,
        method: method,
        headers: headers,
        body: body,
        timeout: timeout,
        cancellation: cancellation,
        maxRedirects: maxRedirects,
        followRedirects: followRedirects,
      );
    } finally {
      _release();
    }
  }

  Future<NetworkResponse> _request(
    Uri uri, {
    String method = 'GET',
    Map<String, String> headers = const {},
    String? body,
    Duration timeout = const Duration(seconds: 30),
    CancellationToken? cancellation,
    int maxRedirects = 5,
    bool followRedirects = true,
  }) async {
    cancellation?.throwIfCancelled();
    HttpClientRequest? active;
    var finished = false;
    var timedOut = false;
    cancellation?.whenCancelled.then((_) {
      if (!finished) {
        active?.abort(const EngineException('cancelled', 'Task cancelled'));
      }
    });
    Future<NetworkResponse> run() async {
      var current = uri;
      var verb = method.toUpperCase();
      var payload = body;
      var forwarded = Map<String, String>.from(headers);
      for (var count = 0; count <= maxRedirects; count++) {
        cancellation?.throwIfCancelled();
        if (!['http', 'https'].contains(current.scheme)) {
          throw const EngineException(
            'invalid_url',
            'Only HTTP(S) requests supported',
          );
        }
        final req = await _client.openUrl(verb, current);
        active = req;
        if (timedOut) {
          req.abort();
          throw const EngineException('timeout', 'Network request timed out');
        }
        if (cancellation?.isCancelled ?? false) {
          req.abort();
          cancellation!.throwIfCancelled();
        }
        req.followRedirects = false;
        forwarded.forEach(req.headers.set);
        _cookies.removeWhere((c) => c.expired);
        req.cookies.addAll(
          _cookies.where((c) => c.matches(current)).map((c) => c.cookie),
        );
        if (payload != null) req.add(utf8.encode(payload));
        final response = await req.close();
        importCookies(current, response.cookies);
        if (followRedirects &&
            [301, 302, 303, 307, 308].contains(response.statusCode) &&
            response.headers.value('location') != null) {
          await response.drain<void>();
          if (count == maxRedirects) {
            throw const EngineException('redirect_limit', 'Too many redirects');
          }
          final next = current.resolve(response.headers.value('location')!);
          if (next.origin != current.origin) {
            forwarded.removeWhere(
              (k, v) => ['authorization', 'cookie'].contains(k.toLowerCase()),
            );
          }
          if (response.statusCode == 303 ||
              ([301, 302].contains(response.statusCode) && verb == 'POST')) {
            verb = 'GET';
            payload = null;
          }
          current = next;
          continue;
        }
        final bytes = await response.fold<List<int>>(
          [],
          (a, b) => a..addAll(b),
        );
        cancellation?.throwIfCancelled();
        final headerCharset = response.headers.contentType?.charset
            ?.toLowerCase();
        final prefix = latin1.decode(bytes.take(4096).toList());
        final meta = RegExp(
          r'''<meta\s[^>]*charset\s*=\s*["']?([^\s"'/>;]+)''',
          caseSensitive: false,
        ).firstMatch(prefix)?[1]?.toLowerCase();
        final bom =
            bytes.length >= 3 &&
            bytes[0] == 0xef &&
            bytes[1] == 0xbb &&
            bytes[2] == 0xbf;
        final charset = headerCharset ?? (bom ? 'utf-8' : meta) ?? 'utf-8';
        final encoding = ['gbk', 'gb2312', 'cp936'].contains(charset)
            ? gbk
            : Encoding.getByName(charset);
        if (encoding == null && charsetDecoder == null) {
          throw EngineException(
            'unsupported_charset',
            'Unsupported charset $charset',
          );
        }
        final resultHeaders = <String, String>{};
        response.headers.forEach((k, v) => resultHeaders[k] = v.join(', '));
        return NetworkResponse(
          current,
          response.statusCode,
          resultHeaders,
          encoding?.decode(bom ? bytes.sublist(3) : bytes) ??
              charsetDecoder!(bytes, charset),
          message: response.reasonPhrase,
          bytes: bytes,
          multiHeaders: {
            for (final name in resultHeaders.keys)
              name: response.headers[name] ?? [],
          },
          cookies: response.cookies.map((c) => c.toString()).toList(),
        );
      }
      throw const EngineException('redirect_limit', 'Too many redirects');
    }

    try {
      return await run().timeout(
        timeout,
        onTimeout: () {
          timedOut = true;
          active?.abort();
          throw const EngineException('timeout', 'Network request timed out');
        },
      );
    } catch (e) {
      if (cancellation?.isCancelled ?? false) {
        throw const EngineException('cancelled', 'Task cancelled');
      }
      rethrow;
    } finally {
      finished = true;
    }
  }

  void close() => _client.close(force: true);
}

class _StoredCookie {
  _StoredCookie(
    this.cookie,
    this.domain,
    this.path,
    this.hostOnly, [
    DateTime? created,
  ]) : created = created ?? DateTime.now();
  final Cookie cookie;
  final String domain;
  final String path;
  final bool hostOnly;
  final DateTime created;
  bool get expired => cookie.maxAge != null
      ? DateTime.now().difference(created).inSeconds >= cookie.maxAge!
      : (cookie.expires != null && cookie.expires!.isBefore(DateTime.now()));
  bool matches(Uri uri) =>
      (hostOnly
          ? uri.host == domain
          : (uri.host == domain || uri.host.endsWith('.$domain'))) &&
      ((uri.path.isEmpty ? '/' : uri.path) == path ||
          (uri.path.isEmpty ? '/' : uri.path).startsWith(
            path.endsWith('/') ? path : '$path/',
          )) &&
      (!cookie.secure || uri.scheme == 'https');
}
