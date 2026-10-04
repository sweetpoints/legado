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
    if (method != 'net.get') throw UnsupportedError(method);
    return (await network.request(
      baseUrl.resolve(arguments.single as String),
      cancellation: cancellation,
    )).body;
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
}
