import 'dart:convert';
import 'dart:io';

import 'package:source_engine/source_engine.dart';
import 'package:test/test.dart';

void main() {
  for (final status in [301, 302, 303, 307, 308]) {
    for (final chunked in [false, true]) {
      test(
        'POST $status ${chunked ? 'mixed-case chunked' : 'mixed-case length'} redirect framing',
        () async {
          final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
          final requests = <Map<String, Object?>>[];
          server.listen((request) async {
            final body = await utf8.decoder.bind(request).join();
            requests.add({
              'method': request.method,
              'body': body,
              'headers': {
                for (final name in [
                  'content-length',
                  'transfer-encoding',
                  'content-type',
                  'content-encoding',
                  'content-language',
                  'content-location',
                  'x-book-source',
                  'authorization',
                ])
                  name: request.headers.value(name),
              },
            });
            if (request.uri.path == '/start') {
              request.response.statusCode = status;
              request.response.headers.set('location', '/end');
            } else {
              request.response.write('complete');
            }
            await request.response.close();
          });
          final client = NetworkClient();
          try {
            final response = await client.request(
              Uri.parse('http://127.0.0.1:${server.port}/start'),
              method: 'POST',
              body: 'hello',
              headers: {
                if (chunked)
                  'tRaNsFeR-EnCoDiNg': 'chunked'
                else
                  'cOnTeNt-LeNgTh': '5',
                'CoNtEnT-TyPe': 'text/plain',
                'CONTENT-Encoding': 'identity',
                'Content-LANGUAGE': 'en',
                'Content-LOCATION': '/payload',
                'X-Book-Source': 'preserve',
                'Authorization': 'Bearer fixture',
              },
            );
            expect(response.body, 'complete');
            expect(requests.length, 2);
            expect(requests.first['method'], 'POST');
            expect(requests.first['body'], 'hello');
            final redirected = requests.last;
            final headers = redirected['headers'] as Map;
            expect(headers['x-book-source'], 'preserve');
            expect(headers['authorization'], 'Bearer fixture');
            if (status <= 303) {
              expect(redirected['method'], 'GET');
              expect(redirected['body'], '');
              // Empty GET framing may omit length or explicitly state zero.
              expect(headers['content-length'], anyOf(isNull, '0'));
              for (final name in [
                'transfer-encoding',
                'content-type',
                'content-encoding',
                'content-language',
                'content-location',
              ]) {
                expect(headers[name], null, reason: name);
              }
            } else {
              expect(redirected['method'], 'POST');
              expect(redirected['body'], 'hello');
              expect(headers['content-type'], 'text/plain');
              expect(headers['content-encoding'], 'identity');
              expect(headers['content-language'], 'en');
              expect(headers['content-location'], '/payload');
              expect(
                headers[chunked ? 'transfer-encoding' : 'content-length'],
                chunked ? 'chunked' : '5',
              );
            }
          } finally {
            client.close();
            await server.close(force: true);
          }
        },
      );
    }
  }
}
