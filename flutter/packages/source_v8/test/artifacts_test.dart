import 'dart:convert';
import 'dart:io';
import 'dart:isolate';

import 'package:crypto/crypto.dart';
import 'package:code_assets/code_assets.dart';
import 'package:test/test.dart';

import '../hook/artifacts.dart';

void main() {
  test('Android and macOS select both pinned architectures', () {
    expect(artifactTarget(OS.android, Architecture.arm64), 'android-arm64');
    expect(artifactTarget(OS.android, Architecture.x64), 'android-x64');
    expect(artifactTarget(OS.macOS, Architecture.arm64), 'macos-arm64');
    expect(artifactTarget(OS.macOS, Architecture.x64), 'macos-x64');
    expect(
      () => artifactTarget(OS.windows, Architecture.ia32),
      throwsUnsupportedError,
    );
  });
  test('Windows exposes reviewed x64 and rejects unreviewed ARM64', () {
    expect(artifactTarget(OS.windows, Architecture.x64), 'windows-x64');
    expect(
      () => artifactTarget(OS.windows, Architecture.arm64),
      throwsUnsupportedError,
    );
  });
  test('Linux selects both authenticated SDK architectures', () {
    expect(artifactTarget(OS.linux, Architecture.arm64), 'linux-arm64');
    expect(artifactTarget(OS.linux, Architecture.x64), 'linux-x64');
    expect(
      () => artifactTarget(OS.linux, Architecture.ia32),
      throwsUnsupportedError,
    );
  });
  test('iOS ARM64 requires an explicit device or simulator SDK', () {
    expect(
      artifactTarget(OS.iOS, Architecture.arm64, iosSdk: IOSSdk.iPhoneOS),
      'ios-arm64',
    );
    expect(
      artifactTarget(
        OS.iOS,
        Architecture.arm64,
        iosSdk: IOSSdk.iPhoneSimulator,
      ),
      'ios-simulator-arm64',
    );
    expect(
      () => artifactTarget(OS.iOS, Architecture.arm64),
      throwsUnsupportedError,
    );
    expect(
      () => artifactTarget(
        OS.iOS,
        Architecture.x64,
        iosSdk: IOSSdk.iPhoneSimulator,
      ),
      throwsUnsupportedError,
    );
  });
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
    final releasePin = jsonDecode(
      await File('${package.path}/../../tool/v8/release-pin.json')
          .readAsString(),
    ) as Map;
    Map<String, Object?> sdkOrigin(String target) => {
      'target': target,
      'releaseManifestSha256': releasePin['releaseManifestSha256'],
      'manifestSha256': (releasePin['sdkManifestSha256'] as Map)[target],
    };
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
          'sdkProvenance': sdkOrigin('macos-arm64'),
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
      expect(artifact.dependencies, hasLength(9));
      for (final dependency in artifact.dependencies) {
        expect(
          await File.fromUri(dependency).exists(),
          isTrue,
          reason:
              'Hook dependency must resolve to an existing file: $dependency',
        );
      }
    },
  );
  test('authenticates Android x64 and enforces its API floor', () async {
    final android = File('${directory.path}/android-x64/libsource_v8.so');
    await android.parent.create();
    await android.writeAsBytes([1, 2, 3, 4]);
    final releasePin = jsonDecode(
      await File('${package.path}/../../tool/v8/release-pin.json')
          .readAsString(),
    ) as Map;
    final entry = <String, Object>{
      'binary': 'android-x64/libsource_v8.so',
      'sha256': sha256.convert([1, 2, 3, 4]).toString(),
      'size': 4,
      'minApi': 26,
      'sdkProvenance': {
        'target': 'android-x64',
        'releaseManifestSha256': releasePin['releaseManifestSha256'],
        'manifestSha256':
            (releasePin['sdkManifestSha256'] as Map)['android-x64'],
      },
    };
    (manifest['targets'] as Map)['android-x64'] = entry;
    final file = File('${directory.path}/manifest.json');
    await file.writeAsString(jsonEncode(manifest));
    expect(
      (await verifyArtifact(directory, package, 'android-x64')).binary.path,
      android.path,
    );
    entry['minApi'] = 21;
    await file.writeAsString(jsonEncode(manifest));
    await expectLater(
      verifyArtifact(directory, package, 'android-x64'),
      throwsStateError,
    );
  });
  test('Windows requires reviewed toolchain, current consumer and exact SDK provenance', () async {
    final windows = File('${directory.path}/source_v8.dll');
    await windows.writeAsBytes([1, 2, 3, 4]);
    final release = jsonDecode(
      await File.fromUri(
        package.uri.resolve('../../tool/v8/windows-release-pin.json'),
      ).readAsString(),
    ) as Map;
    final review = jsonDecode(
      await File.fromUri(
        package.uri.resolve('../../tool/v8/windows-reviewed-toolchain.json'),
      ).readAsString(),
    ) as Map;
    final hashes = <String, Object>{};
    for (final name in windowsConsumerInputs) {
      hashes[name] =
          (await sha256
                  .bind(
                    File.fromUri(package.uri.resolve('../../tool/v8/$name'))
                        .openRead(),
                  )
                  .first)
              .toString();
    }
    final entry = <String, Object>{
      'binary': 'source_v8.dll',
      'size': 4,
      'sha256': sha256.convert([1, 2, 3, 4]).toString(),
      'sdkProvenance': {
        'target': 'windows-x64',
        'releaseManifestSha256': release['releaseManifestSha256'],
        'manifestSha256': (release['sdkManifestSha256'] as Map)['windows-x64'],
      },
      'windowsToolchainLockSha256': review['lockSha256'],
      'windowsConsumerInputsSha256': hashes,
    };
    (manifest['targets'] as Map)['windows-x64'] = entry;
    final file = File('${directory.path}/manifest.json');
    await file.writeAsString(jsonEncode(manifest));
    final artifact = await verifyArtifact(directory, package, 'windows-x64');
    expect(artifact.binary.path, windows.path);
    for (final dependency in artifact.dependencies) {
      expect(await File.fromUri(dependency).exists(), isTrue);
    }
    entry['windowsToolchainLockSha256'] = '0' * 64;
    await file.writeAsString(jsonEncode(manifest));
    await expectLater(
      verifyArtifact(directory, package, 'windows-x64'),
      throwsStateError,
    );
    entry['windowsToolchainLockSha256'] = review['lockSha256'];
    hashes['windows_sdk.py'] = '0' * 64;
    await file.writeAsString(jsonEncode(manifest));
    await expectLater(
      verifyArtifact(directory, package, 'windows-x64'),
      throwsStateError,
    );
  });
  test('macOS x64 uses its own SDK provenance and deployment floor', () async {
    final pin = jsonDecode(
      await File('${package.path}/../../tool/v8/release-pin.json')
          .readAsString(),
    ) as Map;
    final x64 = File('${directory.path}/macos-x64/libsource_v8.dylib');
    await x64.parent.create();
    await x64.writeAsBytes([1, 2, 3, 4]);
    final entry = Map<String, Object>.from(
      (manifest['targets'] as Map)['macos-arm64'] as Map,
    );
    entry['binary'] = 'macos-x64/libsource_v8.dylib';
    entry['sdkProvenance'] = {
      'target': 'macos-x64',
      'releaseManifestSha256': pin['releaseManifestSha256'],
      'manifestSha256': (pin['sdkManifestSha256'] as Map)['macos-x64'],
    };
    (manifest['targets'] as Map)['macos-x64'] = entry;
    final file = File('${directory.path}/manifest.json');
    await file.writeAsString(jsonEncode(manifest));
    expect(
      (await verifyArtifact(directory, package, 'macos-x64')).binary.path,
      x64.path,
    );
    entry['minMacOS'] = '12.0';
    await file.writeAsString(jsonEncode(manifest));
    await expectLater(
      verifyArtifact(directory, package, 'macos-x64'),
      throwsStateError,
    );
    entry['minMacOS'] = '13.0';
    (entry['sdkProvenance'] as Map)['manifestSha256'] =
        (pin['sdkManifestSha256'] as Map)['macos-arm64'];
    await file.writeAsString(jsonEncode(manifest));
    await expectLater(
      verifyArtifact(directory, package, 'macos-x64'),
      throwsStateError,
    );
  });
  test('iOS artifact authenticates its SDK environment and packager', () async {
    const target = 'ios-simulator-arm64';
    final pin = jsonDecode(
      await File('${package.path}/../../tool/v8/ios-release-pin.json')
          .readAsString(),
    ) as Map;
    final file = File('${directory.path}/$target/libsource_v8.dylib');
    await file.parent.create();
    await file.writeAsBytes([1, 2, 3, 4]);
    final packager = File('${package.path}/../../tool/v8/ios_sdk.py');
    final packagerHash = (await sha256.bind(packager.openRead()).first)
        .toString();
    final entry = <String, Object>{
      'binary': '$target/libsource_v8.dylib',
      'sha256': sha256.convert([1, 2, 3, 4]).toString(),
      'size': 4,
      'minIOS': '15.0',
      'iosPackagerSha256': packagerHash,
      'sdkProvenance': {
        'target': target,
        'releaseManifestSha256': pin['releaseManifestSha256'],
        'manifestSha256': (pin['sdkManifestSha256'] as Map)[target],
      },
    };
    (manifest['targets'] as Map)[target] = entry;
    final manifestFile = File('${directory.path}/manifest.json');
    await manifestFile.writeAsString(jsonEncode(manifest));
    expect(
      (await verifyArtifact(directory, package, target)).binary.path,
      file.path,
    );
    entry['iosPackagerSha256'] = '0' * 64;
    await manifestFile.writeAsString(jsonEncode(manifest));
    await expectLater(
      verifyArtifact(directory, package, target),
      throwsStateError,
    );
    entry['iosPackagerSha256'] = packagerHash;
    (entry['sdkProvenance'] as Map)['manifestSha256'] =
        (pin['sdkManifestSha256'] as Map)['ios-arm64'];
    await manifestFile.writeAsString(jsonEncode(manifest));
    await expectLater(
      verifyArtifact(directory, package, target),
      throwsStateError,
    );
  });
  test(
    'Linux artifact authenticates all consumer, sysroot and compiler inputs',
    () async {
      const target = 'linux-x64';
      final toolRoot = '${package.path}/../../tool/v8';
      final pin = jsonDecode(
        await File('$toolRoot/linux-release-pin.json').readAsString(),
      ) as Map;
      final linux = jsonDecode(
        await File('$toolRoot/linux-pins.json').readAsString(),
      ) as Map;
      final compilers = jsonDecode(
        await File('$toolRoot/toolchain-pins.json').readAsString(),
      ) as Map;
      final file = File('${directory.path}/$target/libsource_v8.so');
      await file.parent.create();
      await file.writeAsBytes([1, 2, 3, 4]);
      final inputs = <String, String>{};
      for (final name in [
        'linux_sdk.py',
        'linux_allocator_boundary.cpp',
        'linux-pins.json',
        'linux-release-pin.json',
        'prebuilt.py',
        'toolchains.py',
      ]) {
        inputs[name] =
            (await sha256.bind(File('$toolRoot/$name').openRead()).first)
                .toString();
      }
      final sysroot = (linux['sysroots'] as Map)[target] as Map;
      final entry = <String, Object>{
        'binary': '$target/libsource_v8.so',
        'sha256': sha256.convert([1, 2, 3, 4]).toString(),
        'size': 4,
        'minGlibc': '2.31',
        'linuxConsumerInputsSha256': inputs,
        'linuxCompilerHost': 'linux-x64',
        'linuxCompilerPackage': Map<String, Object>.from(
          (compilers['hosts'] as Map)['linux-x64'] as Map,
        ),
        'linuxSysrootSha256': sysroot['sha256'],
        'linuxSysrootFilesSha256': sysroot['filesSha256'],
        'linuxUndefinedSymbolsSha256':
            (linux['undefinedSymbolsSha256'] as Map)[target],
        'sdkProvenance': {
          'target': target,
          'releaseManifestSha256': pin['releaseManifestSha256'],
          'manifestSha256': (pin['sdkManifestSha256'] as Map)[target],
        },
      };
      (manifest['targets'] as Map)[target] = entry;
      final manifestFile = File('${directory.path}/manifest.json');
      Future<VerifiedArtifact> verifyLinux() async {
        await manifestFile.writeAsString(jsonEncode(manifest));
        return verifyArtifact(directory, package, target);
      }

      final artifact = await verifyLinux();
      expect(artifact.binary.path, file.path);
      expect(
        artifact.dependencies,
        contains(
          package.uri.resolve('../../tool/v8/linux_allocator_boundary.cpp'),
        ),
      );
      for (final key in [
        'minGlibc',
        'linuxSysrootSha256',
        'linuxSysrootFilesSha256',
        'linuxUndefinedSymbolsSha256',
      ]) {
        final original = entry[key]!;
        entry[key] = 'changed';
        await expectLater(verifyLinux(), throwsStateError);
        entry[key] = original;
      }
      for (final name in inputs.keys.toList()) {
        final original = inputs[name]!;
        inputs[name] = '0' * 64;
        await expectLater(verifyLinux(), throwsStateError);
        inputs[name] = original;
      }
      final compiler = entry['linuxCompilerPackage'] as Map;
      compiler['sha256'] = '0' * 64;
      await expectLater(verifyLinux(), throwsStateError);
    },
  );
  test('rejects a binary with changed contents', () async {
    await binary.writeAsBytes([4, 3, 2, 1]);
    await expectLater(verify(), throwsStateError);
  });
  test('rejects a bridge linked from an unreviewed SDK manifest', () async {
    (((manifest['targets'] as Map)['macos-arm64'] as Map)['sdkProvenance']
            as Map)['manifestSha256'] =
        '0' * 64;
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
          contains('Run flutter/tool/v8/prepare_sdk.py'),
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
