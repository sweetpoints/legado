import 'dart:convert';
import 'dart:io';

import 'package:code_assets/code_assets.dart';
import 'package:crypto/crypto.dart';
import 'package:hooks/hooks.dart';

import 'artifacts.dart';

Future<String> pythonExecutable() async {
  final configured = Platform.environment['SOURCE_V8_PYTHON'];
  final candidates = configured != null
      ? [configured]
      : ['python3', '/opt/homebrew/bin/python3', '/usr/local/bin/python3'];
  for (final candidate in candidates) {
    try {
      final version = await Process.run(candidate, [
        '-c',
        'import sys; sys.exit(0 if sys.version_info >= (3, 12) else 1)',
      ]);
      if (version.exitCode == 0) return candidate;
    } on ProcessException {
      // Xcode often resolves python3 to Apple's old system Python. Try the
      // installed tool interpreter, never weaken SDK extraction or hashing.
    }
  }
  throw StateError(
    'V8 SDK preparation requires Python 3.12+. '
    'Set SOURCE_V8_PYTHON to its absolute executable path.',
  );
}

void main(List<String> args) async {
  await build(args, (input, output) async {
    if (!input.config.buildCodeAssets) return;
    final code = input.config.code;
    final os = code.targetOS;
    final arch = code.targetArchitecture;
    final target = artifactTarget(
      os,
      arch,
      iosSdk: os == OS.iOS ? code.iOS.targetSdk : null,
    );
    final configuredRoot = input.userDefines.path('artifact_root');
    if (input.userDefines['artifact_root'] != null && configuredRoot == null) {
      throw FormatException('source_v8.artifact_root must be a path string');
    }
    var root = Directory.fromUri(
      configuredRoot ??
          input.packageRoot.resolve('.cache/self-built/$v8Revision/'),
    );
    if (configuredRoot == null) {
      final result = await Process.run(await pythonExecutable(), [
        input.packageRoot
            .resolve(
              os == OS.iOS
                  ? '../../tool/v8/ios_sdk.py'
                  : '../../tool/v8/prepare_sdk.py',
            )
            .toFilePath(),
        os == OS.iOS ? '--prepare-dynamic' : '--target',
        target,
      ]);
      if (os == OS.iOS && result.exitCode == 0) {
        final receipt =
            jsonDecode(result.stdout as String) as Map<String, dynamic>;
        root = Directory(receipt['artifactRoot'] as String);
      }
      if (result.exitCode != 0) {
        throw StateError(
          'Pinned SDK bridge preparation failed: ${result.stderr}',
        );
      }
    }
    final artifact = await verifyArtifact(
      root,
      Directory.fromUri(input.packageRoot),
      target,
    );
    if (os == OS.android && code.android.targetNdkApi < 26) {
      throw UnsupportedError('Official V8 artifact requires Android API 26+');
    }
    final name = os == OS.macOS || os == OS.iOS
        ? 'libsource_v8.dylib'
        : 'libsource_v8.so';
    final destination = input.outputDirectory.resolve(name);
    final copied = await artifact.binary.copy(destination.toFilePath());
    // Bridge linking can publish new files while another hook is bundling.
    // Authenticate the actual bundled bytes, not only the earlier source file.
    if ((await sha256.bind(copied.openRead()).first).toString() !=
        artifact.target['sha256']) {
      await copied.delete();
      throw StateError(
        'V8 bridge artifact changed while copying; retry SDK preparation',
      );
    }
    output.dependencies.addAll(artifact.dependencies);
    output.dependencies.addAll([
      for (final name in [
        'prepare_sdk.py',
        'prebuilt.py',
        'link_sdk.py',
        'toolchains.py',
        'pins.json',
        if (os == OS.iOS) ...[
          'ios_sdk.py',
          'ios-pins.json',
          'ios-release-pin.json',
        ],
        'toolchain-pins.json',
      ])
        input.packageRoot.resolve('../../tool/v8/$name'),
    ]);
    output.assets.code.add(
      CodeAsset(
        package: input.packageName,
        name: 'source_v8_bindings_generated.dart',
        file: destination,
        linkMode: DynamicLoadingBundled(),
      ),
    );
  });
}
