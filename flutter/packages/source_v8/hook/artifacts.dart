import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:code_assets/code_assets.dart';

const v8Revision = 'c45871fec706a6e7b715e607065bb4578b23ce9f';
const v8Version = '15.4.80.25';
const depotToolsRevision = '8a5434051036b32412a2ecb10c213a72e3f3ccb9';
const bridgeAbi = 1;

String artifactTarget(OS os, Architecture architecture, {IOSSdk? iosSdk}) {
  if (os == OS.iOS && architecture == Architecture.arm64) {
    if (iosSdk == IOSSdk.iPhoneOS) return 'ios-arm64';
    if (iosSdk == IOSSdk.iPhoneSimulator) return 'ios-simulator-arm64';
    throw UnsupportedError('iOS device/simulator SDK is required');
  }
  if (os == OS.linux && architecture == Architecture.arm64) {
    return 'linux-arm64';
  }
  if (os == OS.linux && architecture == Architecture.x64) return 'linux-x64';
  if (os == OS.macOS && architecture == Architecture.arm64) {
    return 'macos-arm64';
  }
  if (os == OS.macOS && architecture == Architecture.x64) {
    return 'macos-x64';
  }
  if (os == OS.android && architecture == Architecture.arm64) {
    return 'android-arm64';
  }
  if (os == OS.android && architecture == Architecture.x64) {
    return 'android-x64';
  }
  throw UnsupportedError(
    'Official V8 supports iOS ARM64 device/simulator, macOS/Linux arm64/x64 and Android arm64/x64; requested $os/$architecture',
  );
}

File _packageFile(Directory root, String relative) =>
    File.fromUri(root.uri.resolve(relative));

/// These labels and the length-prefixed digest are shared with build.py.
Map<String, File> bridgeSources(Directory packageRoot) => {
  'src/source_v8.cpp': _packageFile(packageRoot, 'src/source_v8.cpp'),
  'src/source_v8.h': _packageFile(packageRoot, 'src/source_v8.h'),
  'src/android_exports.map': _packageFile(
    packageRoot,
    'src/android_exports.map',
  ),
  'tool/v8/source_v8.gni': _packageFile(
    packageRoot,
    '../../tool/v8/source_v8.gni',
  ),
  'tool/v8/link_sdk.py': _packageFile(packageRoot, '../../tool/v8/link_sdk.py'),
  'tool/v8/toolchain-pins.json': _packageFile(
    packageRoot,
    '../../tool/v8/toolchain-pins.json',
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
      'SDK-linked V8 bridge manifest is missing: ${manifestFile.path}. '
      'Run flutter/tool/v8/prepare_sdk.py --target $targetName, or configure '
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
  final isIOS = targetName.startsWith('ios-');
  final isLinux = targetName.startsWith('linux-');
  final releasePinFile = _packageFile(
    packageRoot,
    '../../tool/v8/${isIOS
        ? 'ios-release-pin.json'
        : isLinux
        ? 'linux-release-pin.json'
        : 'release-pin.json'}',
  );
  final releasePin = _object(
    jsonDecode(await releasePinFile.readAsString()),
    'release pin',
  );
  final sdk = _object(target['sdkProvenance'], 'sdkProvenance');
  _expect(sdk['target'], targetName, 'sdkProvenance.target');
  _expect(
    sdk['releaseManifestSha256'],
    releasePin['releaseManifestSha256'] as String,
    'sdkProvenance.releaseManifestSha256',
  );
  final manifestHashes = _object(
    releasePin['sdkManifestSha256'],
    'sdkManifestSha256',
  );
  _expect(
    sdk['manifestSha256'],
    manifestHashes[targetName] as String,
    'sdkProvenance.manifestSha256',
  );
  if (targetName == 'android-arm64' || targetName == 'android-x64') {
    _expect(target['minApi'], 26, '$targetName.minApi');
  } else if (targetName == 'macos-arm64' || targetName == 'macos-x64') {
    _expect(target['minMacOS'], '13.0', '$targetName.minMacOS');
  } else if (targetName == 'ios-arm64' || targetName == 'ios-simulator-arm64') {
    _expect(target['minIOS'], '15.0', '$targetName.minIOS');
    final packager = _packageFile(packageRoot, '../../tool/v8/ios_sdk.py');
    _expect(
      target['iosPackagerSha256'],
      (await sha256.bind(packager.openRead()).first).toString(),
      '$targetName.iosPackagerSha256',
    );
  } else if (isLinux) {
    _expect(target['minGlibc'], '2.31', '$targetName.minGlibc');
    final inputHashes = _object(
      target['linuxConsumerInputsSha256'],
      'linuxConsumerInputsSha256',
    );
    for (final name in [
      'linux_sdk.py',
      'linux_allocator_boundary.cpp',
      'linux-pins.json',
      'linux-release-pin.json',
      'prebuilt.py',
      'toolchains.py',
    ]) {
      final file = _packageFile(packageRoot, '../../tool/v8/$name');
      _expect(
        inputHashes[name],
        (await sha256.bind(file.openRead()).first).toString(),
        '$targetName.linuxConsumerInputsSha256.$name',
      );
    }
    final linuxPins = _object(
      jsonDecode(
        await _packageFile(
          packageRoot,
          '../../tool/v8/linux-pins.json',
        ).readAsString(),
      ),
      'linux pins',
    );
    final sysroots = _object(linuxPins['sysroots'], 'linux sysroots');
    final sysroot = _object(sysroots[targetName], 'linux sysroot');
    _expect(
      target['linuxSysrootSha256'],
      sysroot['sha256'] as String,
      '$targetName.linuxSysrootSha256',
    );
    _expect(
      target['linuxSysrootFilesSha256'],
      sysroot['filesSha256'] as String,
      '$targetName.linuxSysrootFilesSha256',
    );
    _expect(
      target['linuxUndefinedSymbolsSha256'],
      _object(
            linuxPins['undefinedSymbolsSha256'],
            'undefinedSymbolsSha256',
          )[targetName]
          as String,
      '$targetName.linuxUndefinedSymbolsSha256',
    );
    final compilerHost = target['linuxCompilerHost'];
    final toolchainPins = _object(
      jsonDecode(
        await _packageFile(
          packageRoot,
          '../../tool/v8/toolchain-pins.json',
        ).readAsString(),
      ),
      'toolchain pins',
    );
    final hosts = _object(toolchainPins['hosts'], 'compiler hosts');
    final expectedCompiler = _object(hosts[compilerHost], 'compiler host');
    final compiler = _object(
      target['linuxCompilerPackage'],
      'linuxCompilerPackage',
    );
    for (final key in ['url', 'sha256', 'size', 'filesSha256']) {
      _expect(
        compiler[key],
        expectedCompiler[key]!,
        '$targetName.linuxCompilerPackage.$key',
      );
    }
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
    throw StateError('SDK-linked V8 bridge binary is missing: ${binary.path}');
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
    releasePinFile.uri,
    binary.uri,
    ...sources.values.map((file) => file.uri),
    if (isLinux) ...[
      for (final name in [
        'linux_sdk.py',
        'linux_allocator_boundary.cpp',
        'linux-pins.json',
        'linux-release-pin.json',
        'prebuilt.py',
        'toolchains.py',
      ])
        _packageFile(packageRoot, '../../tool/v8/$name').uri,
    ],
    if (isIOS) ...[
      for (final name in ['ios_sdk.py', 'ios-pins.json'])
        _packageFile(packageRoot, '../../tool/v8/$name').uri,
    ],
  ], target);
}
