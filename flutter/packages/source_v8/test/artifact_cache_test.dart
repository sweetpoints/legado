import 'dart:async';
import 'dart:io';
import 'dart:isolate';

import 'package:archive/archive.dart';
import 'package:crypto/crypto.dart';
import 'package:test/test.dart';

import '../hook/build.dart' show artifact;

void main() {
  test(
    'parallel hook processes publish one complete authenticated cache',
    () async {
      final cache = await Directory.systemTemp.createTemp('source-v8-cache-');
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      final zip = ZipEncoder().encode(
        Archive()..add(ArchiveFile.string('include/api.h', 'complete API')),
      );
      final digest = sha256.convert(zip).toString();
      var requests = 0;
      server.listen((request) async {
        requests++;
        await Future<void>.delayed(const Duration(milliseconds: 40));
        request.response.add(zip);
        await request.response.close();
      });
      final url =
          'http://${server.address.address}:${server.port}/artifact.zip';
      try {
        final library = (await Isolate.resolvePackageUri(
          Uri.parse('package:source_v8/source_v8.dart'),
        ))!;
        final fixture = library
            .resolve('../test/fixtures/artifact_worker.dart')
            .toFilePath();
        final results = await Future.wait(
          List.generate(
            2,
            (_) => Process.run(Platform.resolvedExecutable, [
              fixture,
              cache.path,
              url,
              digest,
            ]),
          ),
        );
        for (final result in results) {
          expect(result.exitCode, 0, reason: result.stderr.toString());
          expect(result.stdout, 'complete API');
        }
        expect(requests, 1);
        expect(
          cache.listSync().whereType<Directory>().map((d) => d.path),
          everyElement(endsWith('fixture.zip.extracted')),
        );
      } finally {
        await server.close(force: true);
        await cache.delete(recursive: true);
      }
    },
  );
  test('failed authentication never publishes partial cache', () async {
    final cache = await Directory.systemTemp.createTemp(
      'source-v8-failed-cache-',
    );
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((request) async {
      request.response.add([1, 2, 3]);
      await request.response.close();
    });
    try {
      await expectLater(
        artifact(
          cache,
          'fixture.zip',
          url: 'http://${server.address.address}:${server.port}/artifact.zip',
          digest: '0' * 64,
        ),
        throwsStateError,
      );
      expect(
        Directory('${cache.path}/fixture.zip.extracted').existsSync(),
        isFalse,
      );
      expect(cache.listSync().whereType<Directory>(), isEmpty);
    } finally {
      await server.close(force: true);
      await cache.delete(recursive: true);
    }
  });
}
