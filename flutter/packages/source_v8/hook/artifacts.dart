import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:code_assets/code_assets.dart';

const v8Revision = 'e422f6ef0c7b877b04e4872fd0bd3a1cc2ec2eee';
const v8Version = '15.4.80.24';
const depotToolsRevision = '8a5434051036b32412a2ecb10c213a72e3f3ccb9';
const bridgeAbi = 1;

String artifactTarget(OS os, Architecture architecture) {
  if (os == OS.macOS && architecture == Architecture.arm64) {
    return 'macos-arm64';
  }
  if (os == OS.android && architecture == Architecture.arm64) {
    return 'android-arm64';
  }
  if (os == OS.android && architecture == Architecture.x64) {
    return 'android-x64';
  }
  throw UnsupportedError(
    'Official V8 supports macOS arm64 and Android arm64/x64; requested $os/$architecture',
  );
}

/// These labels and the length-prefixed digest are shared with build.py.
Map<String, File> bridgeSources(Directory packageRoot) => {
  'src/source_v8.cpp': File('${packageRoot.path}/src/source_v8.cpp'),
  'src/source_v8.h': File('${packageRoot.path}/src/source_v8.h'),
  'src/android_exports.map': File(
    '${packageRoot.path}/src/android_exports.map',
  ),
  'tool/v8/source_v8.gni': File(
    '${packageRoot.path}/../../tool/v8/source_v8.gni',
  ),
};

Future<String> bridgeDigest(Map<String, File> files) async {
  final labels = files.keys.toList()..sort();
  List<int> length(int value) =>
      (ByteData(8)..setUint64(0, value)).buffer.asUint8List();
  Stream<List<int>> chunks() async* {
    for (final label in labels) {
      final name = utf8.encode(label);
      final content = await files[label]!.readAsBytes();
      yield length(name.length);
      yield name;
      yield length(content.length);
      yield content;
    }
  }

  return (await sha256.bind(chunks()).first).toString();
}

class VerifiedArtifact {
  VerifiedArtifact(this.binary, this.dependencies, this.target);
  final File binary;
  final List<Uri> dependencies;
  final Map<String, Object?> target;
}

Map<String, Object?> _object(Object? value, String field) {
  if (value is! Map<String, Object?>) {
    throw FormatException('V8 manifest $field must be an object');
  }
  return value;
}

void _expect(Object? actual, Object expected, String field) {
  if (actual != expected) {
    throw StateError('V8 manifest $field mismatch: expected $expected');
  }
}

/// Authenticate a local build and reject stale bridge code or wrong targets.
/// No network access or third-party binary fallback is allowed.
Future<VerifiedArtifact> verifyArtifact(
  Directory artifactRoot,
  Directory packageRoot,
  String targetName,
) async {
  final manifestFile = File('${artifactRoot.path}/manifest.json');
  if (!await manifestFile.exists()) {
    throw StateError(
      'Official self-built V8 manifest is missing: ${manifestFile.path}. '
      'Run flutter/tool/v8/build.py for $targetName, or configure '
      'hooks.user_defines.source_v8.artifact_root in the root pubspec.yaml.',
    );
  }
  final manifest = _object(
    jsonDecode(await manifestFile.readAsString()),
    'root',
  );
  _expect(manifest['schemaVersion'], 1, 'schemaVersion');
  final v8 = _object(manifest['v8'], 'v8');
  _expect(v8['revision'], v8Revision, 'v8.revision');
  _expect(v8['version'], v8Version, 'v8.version');
  final depot = _object(manifest['depotTools'], 'depotTools');
  _expect(depot['revision'], depotToolsRevision, 'depotTools.revision');
  final bridge = _object(manifest['bridge'], 'bridge');
  _expect(bridge['abi'], bridgeAbi, 'bridge.abi');
  final sources = bridgeSources(packageRoot);
  _expect(
    bridge['sourceSha256'],
    await bridgeDigest(sources),
    'bridge.sourceSha256 (rebuild after changing bridge sources)',
  );
  final targets = _object(manifest['targets'], 'targets');
  final target = _object(targets[targetName], 'targets.$targetName');
  if (targetName == 'android-arm64' || targetName == 'android-x64') {
    _expect(target['minApi'], 26, '$targetName.minApi');
  } else if (targetName == 'macos-arm64') {
    _expect(target['minMacOS'], '13.0', '$targetName.minMacOS');
  } else {
    throw UnsupportedError('Official V8 build does not support $targetName');
  }
  final path = target['binary'];
  if (path is! String ||
      path.isEmpty ||
      path.startsWith('/') ||
      path.contains('\\') ||
      path.split('/').any((part) => part == '..' || part.isEmpty)) {
    throw FormatException('V8 manifest binary must be a relative file path');
  }
  final binary = File('${artifactRoot.path}/$path');
  if (!await binary.exists()) {
    throw StateError(
      'Official self-built V8 binary is missing: ${binary.path}',
    );
  }
  final root = await artifactRoot.resolveSymbolicLinks();
  if (!(await binary.resolveSymbolicLinks()).startsWith('$root/')) {
    throw StateError('V8 binary resolves outside the artifact directory');
  }
  final size = target['size'];
  if (size is! int || size <= 0) {
    throw FormatException('V8 manifest binary size must be a positive integer');
  }
  _expect(await binary.length(), size, '$targetName.size');
  final digest = (await sha256.bind(binary.openRead()).first).toString();
  _expect(target['sha256'], digest, '$targetName.sha256');
  return VerifiedArtifact(binary, [
    manifestFile.uri,
    binary.uri,
    ...sources.values.map((file) => file.uri),
  ], target);
}
