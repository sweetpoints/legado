import 'package:source_engine/source_engine.dart';
import 'package:test/test.dart';

import 'support/fixture_server.dart';

void main() {
  late FixtureServer server;
  late NetworkClient network;
  setUp(() async {
    server = await FixtureServer.start();
    network = NetworkClient();
  });
  tearDown(() async {
    network.close();
    await server.close();
  });

  test(
    'session created during search reaches subsequent book requests',
    () async {
      final denied = await network.request(server.baseUrl.resolve('/book'));
      expect(denied.status, 401);
      final search = await network.request(server.baseUrl.resolve('/search'));
      expect(search.status, 200);
      expect(search.body, contains('测试书籍'));
      for (final path in ['/book', '/toc', '/chapter/1']) {
        final result = await network.request(server.baseUrl.resolve(path));
        expect(result.status, 200, reason: path);
      }
      expect(server.requests, [
        '/book',
        '/search',
        '/book',
        '/toc',
        '/chapter/1',
      ]);
    },
  );

  test('cancelled pending response produces a task cancellation', () async {
    final token = CancellationToken();
    final pending = network.request(
      server.baseUrl.resolve('/slow'),
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
    // A failed task must not poison the shared client.
    final next = await network.request(server.baseUrl.resolve('/value'));
    expect(next.status, 200);
  });

  test('network timeout is distinct from user cancellation', () async {
    await expectLater(
      network.request(
        server.baseUrl.resolve('/slow'),
        timeout: const Duration(milliseconds: 30),
      ),
      throwsA(isA<EngineException>().having((e) => e.code, 'code', 'timeout')),
    );
  });
}
