import 'dart:convert';
import 'dart:io';

/// A reproducible source site: search establishes a session; subsequent stages
/// require it. Additional routes expose asynchronous host requests and cancellation.
class FixtureServer {
  FixtureServer._(this._server);
  final HttpServer _server;
  final List<String> requests = [];
  Uri get baseUrl =>
      Uri.parse('http://${_server.address.address}:${_server.port}');

  static Future<FixtureServer> start() async {
    final fixture = FixtureServer._(
      await HttpServer.bind(InternetAddress.loopbackIPv4, 0),
    );
    fixture._server.listen(fixture._handle);
    return fixture;
  }

  Future<void> close() => _server.close(force: true);

  Future<void> _handle(HttpRequest request) async {
    requests.add(request.uri.path);
    final response = request.response;
    try {
      if (request.uri.path == '/slow') {
        await Future<void>.delayed(const Duration(milliseconds: 350));
        response.write('late result');
      } else if (request.uri.path == '/value') {
        response.headers.contentType = ContentType.json;
        response.write(jsonEncode({'value': '异步结果'}));
      } else if (request.uri.path == '/search') {
        response.headers.contentType = ContentType.html;
        response.cookies.add(Cookie('session', 'fixture'));
        response.write('''<html><body><article class="book">
<a class="title" href="/book">测试书籍</a><span class="author">作者甲</span>
</article></body></html>''');
      } else if (!request.cookies.any(
        (cookie) => cookie.name == 'session' && cookie.value == 'fixture',
      )) {
        response.statusCode = HttpStatus.unauthorized;
        response.write('missing session');
      } else {
        response.headers.contentType = ContentType.html;
        switch (request.uri.path) {
          case '/book':
            response.write(
              '''<h1>测试书籍</h1><a class="toc" href="/toc">目录</a>''',
            );
          case '/toc':
            response.write('''<ol><li><a href="/chapter/1">第一章</a></li>
<li><a href="/chapter/2">第二章</a></li></ol>''');
          case '/chapter/1':
            response.write('<main>第一章正文：你好，世界。</main>');
          case '/chapter/2':
            response.write('<main>第二章正文。</main>');
          default:
            response.statusCode = HttpStatus.notFound;
            response.write('unknown fixture path');
        }
      }
      await response.close();
    } on SocketException {
      // A cancelled request can close the socket before the fixture responds.
    } on HttpException {
      // Force-closing teardown must not report an unrelated fixture failure.
    }
  }
}
