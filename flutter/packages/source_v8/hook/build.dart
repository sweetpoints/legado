import 'dart:io';

import 'package:code_assets/code_assets.dart';
import 'package:crypto/crypto.dart';
import 'package:hooks/hooks.dart';

import 'artifacts.dart';

void main(List<String> args) async {
  await build(args, (input, output) async {
    if (!input.config.buildCodeAssets) return;
    final code = input.config.code;
    final os = code.targetOS;
    final arch = code.targetArchitecture;
    final target = artifactTarget(os, arch);
    final configuredRoot = input.userDefines.path('artifact_root');
    if (input.userDefines['artifact_root'] != null && configuredRoot == null) {
      throw FormatException('source_v8.artifact_root must be a path string');
    }
    final root = Directory.fromUri(
      configuredRoot ??
          input.packageRoot.resolve('.cache/self-built/$v8Revision/'),
    );
    if (configuredRoot == null) {
      final result = await Process.run('python3', [
        input.packageRoot.resolve('../../tool/v8/prepare_sdk.py').toFilePath(),
        '--target',
        target,
      ]);
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
    final name = os == OS.macOS ? 'libsource_v8.dylib' : 'libsource_v8.so';
    final destination = input.outputDirectory.resolve(name);
    final copied = await artifact.binary.copy(destination.toFilePath());
    // Bridge linking can publish new files while another hook is bundling.
    // Authenticate the actual bundled bytes, not only the earlier source file.
    if ((await sha256.bind(copied.openRead()).first).toString() !=
        artifact.target['sha256']) {
      await copied.delete();
      throw StateError('V8 bridge artifact changed while copying; retry SDK preparation');
    }
    output.dependencies.addAll(artifact.dependencies);
    output.dependencies.addAll([
      for (final name in [
        'prepare_sdk.py',
        'prebuilt.py',
        'link_sdk.py',
        'toolchains.py',
        'pins.json',
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
