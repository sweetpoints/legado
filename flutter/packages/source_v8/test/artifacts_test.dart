import 'dart:convert';
import 'dart:io';
import 'dart:isolate';

import 'package:crypto/crypto.dart';
import 'package:test/test.dart';

import '../hook/artifacts.dart';

void main() {
  late Directory directory;
  late Directory package;
  late File binary;
  late Map<String, Object?> manifest;
  setUp(() async {
    directory = await Directory.systemTemp.createTemp('v8-local-artifact-');
    final uri = (await Isolate.resolvePackageUri(
      Uri.parse('package:source_v8/source_v8.dart'),
    ))!;
    package = Directory.fromUri(uri.resolve('../'));
    binary = File('${directory.path}/macos-arm64/libsource_v8.dylib');
    await binary.parent.create();
    await binary.writeAsBytes([1, 2, 3, 4]);
    manifest = {
      'schemaVersion': 1,
      'v8': {'revision': v8Revision, 'version': v8Version},
      'depotTools': {'revision': depotToolsRevision},
      'bridge': {
        'abi': bridgeAbi,
        'sourceSha256': await bridgeDigest(bridgeSources(package)),
      },
      'targets': {
        'macos-arm64': {
          'binary': 'macos-arm64/libsource_v8.dylib',
          'sha256': sha256.convert([1, 2, 3, 4]).toString(),
          'size': 4,
          'minMacOS': '13.0',
        },
      },
    };
  });
  tearDown(() => directory.delete(recursive: true));
  Future<VerifiedArtifact> verify() async {
    await File('${directory.path}/manifest.json')
        .writeAsString(jsonEncode(manifest));
    return verifyArtifact(directory, package, 'macos-arm64');
  }

  test(
    'authenticates local binary and declares all source dependencies',
    () async {
      final artifact = await verify();
      expect(artifact.binary.path, binary.path);
      expect(artifact.dependencies, hasLength(6));
    },
  );
  test('rejects a binary with changed contents', () async {
    await binary.writeAsBytes([4, 3, 2, 1]);
    await expectLater(verify(), throwsStateError);
  });
  test('rejects stale bridge source digest', () async {
    (manifest['bridge'] as Map)['sourceSha256'] = '0' * 64;
    await expectLater(verify(), throwsStateError);
  });
  test('rejects another official V8 revision', () async {
    (manifest['v8'] as Map)['revision'] = '0' * 40;
    await expectLater(verify(), throwsStateError);
  });
  test('rejects binary traversal', () async {
    final targets = manifest['targets'] as Map;
    (targets['macos-arm64'] as Map)['binary'] = '../outside';
    await expectLater(verify(), throwsFormatException);
  });
  test('rejects binary symlink outside artifact root', () async {
    final outside = await Directory.systemTemp.createTemp('v8-outside-');
    try {
      final file = File('${outside.path}/library');
      await file.writeAsBytes([1, 2, 3, 4]);
      await binary.delete();
      await Link(binary.path).create(file.path);
      await expectLater(verify(), throwsStateError);
    } finally {
      await outside.delete(recursive: true);
    }
  });
  test('missing local build fails without downloading a fallback', () async {
    await expectLater(
      verifyArtifact(directory, package, 'macos-arm64'),
      throwsA(
        isA<StateError>().having(
          (error) => error.toString(),
          'message',
          contains('Run flutter/tool/v8/build.py'),
        ),
      ),
    );
  });
  test(
    'source digest separates filenames and contents unambiguously',
    () async {
      final first = File('${directory.path}/first');
      final second = File('${directory.path}/second');
      await first.writeAsString('bc');
      await second.writeAsString('c');
      expect(
        await bridgeDigest({'a': first}),
        isNot(await bridgeDigest({'ab': second})),
      );
    },
  );
}
