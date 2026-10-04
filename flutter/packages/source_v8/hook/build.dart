import 'dart:io';
import 'dart:convert';
import 'dart:typed_data';

import 'android_sources.dart';

import 'package:archive/archive_io.dart';
import 'package:crypto/crypto.dart';
import 'package:code_assets/code_assets.dart';
import 'package:hooks/hooks.dart';
import 'package:native_toolchain_c/native_toolchain_c.dart';

const _release = 'v14.3.92-15';
const _hashes = {
  'libv8_monolith-mac.zip':
      'a891419fed3247ecfff0f550faeed4875f7890eb344bcd800ea00ad31634576a',
  'include-android.zip':
      'b29196492799c368a15b873537c9520e942b5d3cce93860a9239666a9d879400',
  'libv8_monolith-android-arm64.zip':
      'efff3aabe98d8350ed543750c3c6baddbb513d3985ce3718d29ca25420188e5f',
  'libv8_monolith-android-arm.zip':
      '635e8f8138b055414dbf3c48a6b0f606ac222de90c76fa183ae5ca9bc02ea784',
  'libv8_monolith-android-x64.zip':
      'ba602afbe12a481ea6830a4acd7556075c6d8544b44c58397b218d023ad3f706',
};
// Gitiles puts the current request time in tar metadata. Verify source trees,
// not unstable gzip/tar bytes. Names and complete file bytes are authenticated.
Future<String> sourceTreeDigest(Archive archive) async {
  final names = <String>{};
  for (final entry in archive) {
    if (entry.isSymbolicLink ||
        entry.name.startsWith('/') ||
        entry.name.contains('\\') ||
        entry.name.split('/').contains('..')) {
      throw StateError('Unexpected source archive path or symbolic link');
    }
    if (!names.add(entry.name)) {
      throw StateError('Duplicate source archive path');
    }
  }
  final files = archive.where((entry) => entry.isFile).toList()
    ..sort((a, b) => a.name.compareTo(b.name));
  List<int> length(int value) =>
      (ByteData(8)..setUint64(0, value)).buffer.asUint8List();
  Stream<List<int>> chunks() async* {
    for (final entry in files) {
      final name = utf8.encode(entry.name);
      final content = entry.content;
      yield length(name.length);
      yield name;
      yield length(content.length);
      yield content;
    }
  }

  return (await sha256.bind(chunks()).first).toString();
}

Future<Directory> artifact(
  Directory cache,
  String name, {
  String? url,
  String? digest,
}) async {
  final expected = digest ?? _hashes[name]!;
  await cache.create(recursive: true);
  // Build hooks for multiple ABIs run in separate processes. Serialize shared
  // cache publication so compilers never read files being extracted elsewhere.
  final lock = await File('${cache.path}/$name.lock')
      .open(mode: FileMode.append);
  try {
    await lock.lock(FileLock.blockingExclusive);
    return await _lockedArtifact(cache, name, url: url, expected: expected);
  } finally {
    await lock.close();
  }
}

Future<Directory> _lockedArtifact(
  Directory cache,
  String name, {
  String? url,
  required String expected,
}) async {
  final directory = Directory('${cache.path}/$name.extracted');
  final marker = File('${directory.path}/.verified');
  if (await marker.exists() && await marker.readAsString() == expected) {
    return directory;
  }
  final client = HttpClient()..autoUncompress = false;
  Directory? staging;
  try {
    final request = await client.getUrl(
      Uri.parse(
        url ??
            'https://github.com/haroel/v8-build/releases/download/$_release/$name',
      ),
    );
    final response = await request.close();
    if (response.statusCode != 200) {
      throw StateError('V8 artifact HTTP ${response.statusCode}');
    }
    final bytes = <int>[];
    await for (final chunk in response) {
      bytes.addAll(chunk);
    }
    final isZip = name.endsWith('.zip');
    // ZIP bytes have deterministic pins. Authenticate before decompression.
    if (isZip && sha256.convert(bytes).toString() != expected) {
      throw StateError('V8 artifact checksum mismatch: $name');
    }
    final archive = isZip
        ? ZipDecoder().decodeBytes(bytes)
        : TarDecoder().decodeBytes(GZipDecoder().decodeBytes(bytes));
    if (!isZip && await sourceTreeDigest(archive) != expected) {
      throw StateError('V8 artifact checksum mismatch: $name');
    }
    staging = await cache.createTemp('$name.staging-');
    await extractArchiveToDisk(archive, staging.path);
    await File('${staging.path}/.verified')
        .writeAsString(expected, flush: true);
    if (await directory.exists()) await directory.delete(recursive: true);
    await staging.rename(directory.path);
    staging = null;
    return directory;
  } finally {
    client.close();
    if (staging != null && await staging.exists()) {
      await staging.delete(recursive: true);
    }
  }
}

void main(List<String> args) async {
  await build(args, (input, output) async {
    if (!input.config.buildCodeAssets) return;
    final os = input.config.code.targetOS;
    final arch = input.config.code.targetArchitecture;
    final cache = Directory.fromUri(
      input.packageRoot.resolve('.cache/$_release/'),
    );
    late String include;
    late String library;
    final sources = <String>['src/source_v8.cpp'];
    final includes = <String>[];
    final flags = <String>[];
    final defines = <String, String?>{};
    if (os == OS.macOS && arch == Architecture.arm64) {
      final dir = await artifact(cache, 'libv8_monolith-mac.zip');
      final framework =
          '${dir.path}/libv8_monolith.xcframework/macos-arm64/libv8_monolith.framework';
      include = '$framework/Headers';
      library = '$framework/libv8_monolith';
    } else if (os == OS.android &&
        [
          Architecture.arm64,
          Architecture.arm,
          Architecture.x64,
        ].contains(arch)) {
      final headers = await artifact(cache, 'include-android.zip');
      final dir = await artifact(
        cache,
        'libv8_monolith-android-${arch.name}.zip',
      );
      include = '${headers.path}/include';
      final libs = dir
          .listSync(recursive: true)
          .whereType<File>()
          .where((f) => f.path.endsWith('libv8_monolith.a'))
          .toList();
      if (libs.length != 1) {
        throw StateError('Expected exactly one V8 static archive');
      }
      library = libs.single.path;
      // Match V8's exact Chromium C++ ABI and relative vtables.
      final libcxx = await artifact(
        cache,
        'libcxx.tar.gz',
        url: 'https://chromium.googlesource.com/external/github.com/llvm/llvm-project/libcxx.git/+archive/89b5f99ebd15557154c4d718717173d6fa7b13d3.tar.gz',
        digest:
            '10e38e4f09c6abac4988f16e54afdaa7453bc1e561187c5dd9985ececedb16cf',
      );
      final libcxxabi = await artifact(
        cache,
        'libcxxabi.tar.gz',
        url: 'https://chromium.googlesource.com/external/github.com/llvm/llvm-project/libcxxabi.git/+archive/8e720a3a3ae30fcfffe436aae418c91acacc34d0.tar.gz',
        digest:
            '2501259fa63e66d6813c3ae5d97aa9faa058acf87d67ddce80a3cd20a39439c2',
      );
      final llvmLibc = await artifact(
        cache,
        'llvm-libc.tar.gz',
        url: 'https://chromium.googlesource.com/external/github.com/llvm/llvm-project/libc.git/+archive/27b37b761098b8a3af5a4b2e6de04a1473c9544f.tar.gz',
        digest:
            'd35af28de89bba1b3662098d6530828d01761125225606e2d3947f4913a1858c',
      );
      includes.addAll([
        'src/libcxx_config',
        '${libcxx.path}/include',
        '${libcxxabi.path}/include',
        '${libcxx.path}/src',
        llvmLibc.path,
      ]);
      sources.addAll(libcxxSources.map((s) => '${libcxx.path}/$s'));
      sources.addAll(libcxxabiSources.map((s) => '${libcxxabi.path}/src/$s'));
      flags.addAll([
        '-nostdinc++',
        '-nostdlib++',
        '-x',
        'c++',
        '-fexperimental-relative-c++-abi-vtables',
        '-Wl,--no-undefined',
        '-Wl,--exclude-libs,ALL',
        '-Wl,--version-script=${input.packageRoot.resolve('src/android_exports.map').toFilePath()}',
      ]);
      defines.addAll({
        '_LIBCPP_BUILDING_LIBRARY': null,
        'LIBCXXABI_BUILDING_LIBRARY': null,
        'LIBCXX_BUILDING_LIBCXXABI': null,
        '_LIBCPP_DISABLE_VISIBILITY_ANNOTATIONS': null,
        '_LIBCPP_INSTRUMENTED_WITH_ASAN': '0',
        '_LIBCPP_HARDENING_MODE': '_LIBCPP_HARDENING_MODE_NONE',
        'LIBCXXABI_SILENT_TERMINATE': null,
        'HAVE___CXA_THREAD_ATEXIT_IMPL': null,
      });
    } else {
      throw UnsupportedError(
        'Pinned V8 build supports macOS arm64 and Android arm/arm64/x64; requested $os/$arch',
      );
    }
    await CBuilder.library(
      name: 'source_v8',
      assetName: 'source_v8_bindings_generated.dart',
      sources: sources,
      includes: [...includes, include],
      defines: defines,
      frameworks: os == OS.macOS ? ['CoreFoundation'] : const [],
      libraries: os == OS.android ? ['log', 'dl', 'm', 'atomic'] : const [],
      language: os == OS.android ? Language.c : Language.cpp,
      std: os == OS.android ? 'c++23' : 'c++20',
      flags: [
        if (os == OS.macOS) '-fno-rtti',
        ...flags,
        '-Wl,$library',
        if (os == OS.macOS) '-Wl,-framework,CoreFoundation',
      ],
      cppLinkStdLib: os == OS.android ? null : 'c++',
    ).run(input: input, output: output);
  });
}
