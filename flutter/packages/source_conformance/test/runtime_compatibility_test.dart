import 'dart:convert';

import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:source_v8/source_v8.dart';
import 'package:test/test.dart';

import 'support/fixture_server.dart';

class _NetworkHost implements ScriptHost {
  _NetworkHost(this.network, this.baseUrl, {this.cancellation});
  final NetworkClient network;
  final Uri baseUrl;
  final CancellationToken? cancellation;
  @override
  Future<Object?> call(String method, List<Object?> arguments) async {
    if (method == 'net.get') {
      return (await network.request(
        baseUrl.resolve(arguments.single as String),
        cancellation: cancellation,
      )).body;
    }
    if (method == 'net.request') {
      final options = Map<String, Object?>.from(arguments.single as Map);
      return (await network.request(
        baseUrl.resolve(options['url'] as String),
        method: options['method'] as String? ?? 'GET',
        headers: (options['headers'] as Map? ?? {}).map(
          (k, v) => MapEntry(k.toString(), v.toString()),
        ),
        body: options['body'] as String?,
        timeout: Duration(milliseconds: options['timeoutMs'] as int? ?? 30000),
        followRedirects: options['followRedirects'] as bool? ?? true,
        cancellation: cancellation,
      )).toJson();
    }
    throw UnsupportedError(method);
  }
}

void main() {
  late FixtureServer server;
  late NetworkClient network;
  late V8Runtime runtime;
  setUp(() async {
    server = await FixtureServer.start();
    network = NetworkClient();
    runtime = V8Runtime(prelude: legacyScriptPrelude);
  });
  tearDown(() async {
    await runtime.close();
    network.close();
    await server.close();
  });

  test(
    'legacy java.ajax remains a synchronous value over asynchronous HTTP',
    () async {
      final result = await runtime.evaluate(
        'const text = java.ajax("/value"); return JSON.parse(text).value;',
        ScriptContext(
          host: LegacyScriptHost(_NetworkHost(network, server.baseUrl)),
        ),
      );
      expect(result, '异步结果');
      expect(server.requests, ['/value']);
    },
  );

  test('legacy encoding and variables round trip actual V8 values', () async {
    final result = await runtime.evaluate(
      'java.put("token", "中文"); return java.base64Decode(java.base64Encode(java.get("token")));',
      ScriptContext(
        host: LegacyScriptHost(_NetworkHost(network, server.baseUrl)),
      ),
    );
    expect(result, '中文');
  });

  test(
    'async JS host wait cancels and next evaluation remains usable',
    () async {
      final token = CancellationToken();
      final pending = runtime.evaluate(
        'return await source.net.get("/slow");',
        ScriptContext(
          host: _NetworkHost(network, server.baseUrl, cancellation: token),
        ),
        cancellation: token,
      );
      final assertion = expectLater(
        pending,
        throwsA(
          isA<EngineException>().having((e) => e.code, 'code', 'cancelled'),
        ),
      );
      await Future<void>.delayed(const Duration(milliseconds: 30));
      token.cancel();
      await assertion;
      final next = await runtime.evaluate(
        'return await source.net.get("/value");',
        ScriptContext(host: _NetworkHost(network, server.baseUrl)),
      );
      expect(jsonDecode(next as String), {'value': '异步结果'});
    },
  );
  test('connect JSON headers and response facade preserve methods and coercion', () async {
    final result = await runtime.evaluate(
      r'''const r=java.connect("/echo", '{"X-Request":"connect"}', 1000); '''
      'return {body:JSON.parse(r.body()), coerced:String(r.body), url:r.url(), '
      'code:r.code(), ok:r.isSuccessful(), header:r.headers().get("X-FIXTURE")};',
      ScriptContext(
        host: LegacyScriptHost(_NetworkHost(network, server.baseUrl)),
      ),
    ) as Map;
    expect(result['body'], {'method': 'GET', 'body': '', 'header': 'connect'});
    expect(jsonDecode(result['coerced'] as String), result['body']);
    expect(result['url'], server.baseUrl.resolve('/echo').toString());
    expect(result['code'], 200);
    expect(result['ok'], true);
    expect(result['header'], 'present');
  });

  test(
    'get overload dispatch separates variable access from HTTP response',
    () async {
      final result = await runtime.evaluate(
        'java.put("key","variable"); '
        'const r=java.get("/redirect", null, 1000); '
        'return {variable:java.get("key"), status:r.statusCode(), '
        'location:r.header("LOCATION"), body:r.body()};',
        ScriptContext(
          host: LegacyScriptHost(_NetworkHost(network, server.baseUrl)),
        ),
      );
      expect(result, {
        'variable': 'variable',
        'status': 302,
        'location': '/value',
        'body': 'redirect response',
      });
      expect(server.requests, ['/redirect']);
    },
  );

  test(
    'post body and header map reach HTTP and head omits response body',
    () async {
      final result = await runtime.evaluate(
        'const p=java.post("/echo","payload",{"X-Request":"post"},1000); '
        'const h=java.head("/echo",null,1000); '
        'return {post:JSON.parse(p.body()), header:p.header("x-fixture"), '
        'headStatus:h.statusCode(), headBody:h.body()};',
        ScriptContext(
          host: LegacyScriptHost(_NetworkHost(network, server.baseUrl)),
        ),
      );
      expect(result, {
        'post': {'method': 'POST', 'body': 'payload', 'header': 'post'},
        'header': 'present',
        'headStatus': 200,
        'headBody': '',
      });
    },
  );

  test('ajax list overload and batched response methods retain order', () async {
    final result = await runtime.evaluate(
      'const list=java.ajaxAll(["/value","/missing"],false); '
      'return {single:JSON.parse(java.ajax(["/value","/missing"],1000)).value, '
      'batch:list.map(r=>({code:r.code(),body:r.body(),ok:r.isSuccessful()}))};',
      ScriptContext(
        host: LegacyScriptHost(_NetworkHost(network, server.baseUrl)),
      ),
    ) as Map;
    expect(result['single'], '异步结果');
    expect(result['batch'], [
      {'code': 200, 'body': '{"value":"异步结果"}', 'ok': true},
      {'code': 404, 'body': 'not found', 'ok': false},
    ]);
  });

  test(
    'byte codecs and Android Base64 flags preserve signed bytes and wrapping',
    () async {
      final result = await runtime.evaluate(
        'return {bytes:java.strToBytes("中文"), '
        'gbk:java.bytesToStr(java.strToBytes("中文","GBK"),"GBK"), '
        'utf16:java.bytesToStr(java.strToBytes("中文","UTF-16"),"UTF-16"), '
        'latin:java.base64Decode("6Q==","ISO-8859-1"), '
        'signed:java.base64DecodeToByteArray("/w=="), '
        'empty:java.base64DecodeToByteArray("  "), '
        'oddHex:java.hexDecodeToByteArray("fff"), '
        'defaultFlag:java.base64Encode("a",0), '
        'crlf:java.base64Encode("a",4), '
        'noPadding:java.base64Encode("a",3), '
        'wrapped:java.base64Encode("a".repeat(60),0)};',
        ScriptContext(
          host: LegacyScriptHost(_NetworkHost(network, server.baseUrl)),
        ),
      ) as Map;
      expect(result['bytes'], [-28, -72, -83, -26, -106, -121]);
      expect(result['gbk'], '中文');
      expect(result['utf16'], '中文');
      expect(result['latin'], 'é');
      expect(result['signed'], [-1]);
      expect(result['empty'], isNull);
      expect(result['oddHex'], [15, -1]);
      expect(result['defaultFlag'], 'YQ==\n');
      expect(result['crlf'], 'YQ==\r\n');
      expect(result['noPadding'], 'YQ');
      final encoded = base64Encode(List.filled(60, 97));
      expect(
        result['wrapped'],
        '${encoded.substring(0, 76)}\n${encoded.substring(76)}\n',
      );
    },
  );

  test('cancel sync ajaxAll after both HTTP requests have started', () async {
    final token = CancellationToken();
    final pending = runtime.evaluate(
      'return java.ajaxAll(["/slow","/slow"],false).map(r=>r.body());',
      ScriptContext(
        host: LegacyScriptHost(
          _NetworkHost(network, server.baseUrl, cancellation: token),
        ),
      ),
      cancellation: token,
    );
    final assertion = expectLater(
      pending,
      throwsA(
        isA<EngineException>().having((e) => e.code, 'code', 'cancelled'),
      ),
    );
    final deadline = DateTime.now().add(const Duration(seconds: 5));
    while (server.requests.where((p) => p == '/slow').length < 2 &&
        DateTime.now().isBefore(deadline)) {
      await Future<void>.delayed(const Duration(milliseconds: 5));
    }
    expect(server.requests.where((p) => p == '/slow'), hasLength(2));
    token.cancel();
    await assertion;
    final recovery = await runtime.evaluate(
      'return java.ajax("/value");',
      ScriptContext(
        host: LegacyScriptHost(_NetworkHost(network, server.baseUrl)),
      ),
    );
    expect(jsonDecode(recovery as String), {'value': '异步结果'});
  });
  test(
    'URL-safe Base64 and unsupported overloads have explicit outcomes',
    () async {
      final result = await runtime.evaluate(
        r'''
      const errors=[];
      for(const call of [()=>java.base64Encode("a",32), ()=>java.ajaxAll([],true),
          ()=>java.connect("/echo",{}), ()=>java.base64DecodeToByteArray("YQ==","UTF-8")]) {
        try { call(); errors.push(false); } catch(e) { errors.push(true); }
      }
      return {url:java.base64Encode("💩",10), decoded:java.base64Decode("8J-SqQ==",8),
        bytes:java.bytesToStr([-61,-87],"UTF-8"), errors};
    ''',
        ScriptContext(
          host: LegacyScriptHost(_NetworkHost(network, server.baseUrl)),
        ),
      );
      expect(result, {
        'url': '8J-SqQ==',
        'decoded': '💩',
        'bytes': 'é',
        'errors': [true, true, true, true],
      });
      expect(server.requests, isEmpty);
    },
  );
  test(
    'response properties support string operations, bytes and cookies',
    () async {
      final result = await runtime.evaluate(
        r'''
      const r=java.get("/search",null);
      let unsupported=false;
      try { r.raw(); } catch(e) { unsupported=String(e).includes('unsupported_response_api'); }
      return {body:r.body, length:r.body.length, trimmed:r.body.trim(),
        bytes:r.bodyAsBytes(), cookies:r.cookies(), cookie:r.cookie('session'),
        present:r.hasCookie('session'), missing:r.cookie('missing'),
        setCookie:r.multiHeaders()['set-cookie'], unsupported};
    ''',
        ScriptContext(
          host: LegacyScriptHost(_NetworkHost(network, server.baseUrl)),
        ),
      ) as Map;
      final body = result['body'] as String;
      expect(result['length'], body.length);
      expect(result['trimmed'], body.trim());
      expect(
        result['bytes'],
        utf8.encode(body).map((b) => b > 127 ? b - 256 : b).toList(),
      );
      expect(result['cookies'], {'session': 'fixture'});
      expect(result['cookie'], 'fixture');
      expect(result['present'], true);
      expect(result['missing'], isNull);
      expect((result['setCookie'] as List).single, contains('session=fixture'));
      expect(result['unsupported'], true);
    },
  );
}
