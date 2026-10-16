import 'dart:convert';
import 'dart:ffi';
import 'dart:io';

import 'package:pdfrx/pdfrx.dart';

/// pdfrx's test loader does not consume Flutter's current native-assets JSON.
/// Use the actual PDFium build-hook output for the tester ABI, with no download,
/// arbitrary system-library fallback, mocked parser, or skipped native assertion.
void configurePdfiumForFlutterTest() {
  final abi = Abi.current().toString();
  final target = switch (abi) {
    'macos_arm64' => 'mac-arm64',
    'macos_x64' => 'mac-x64',
    'linux_arm64' => 'linux-arm64',
    'linux_x64' => 'linux-x64',
    'windows_arm64' => 'win-arm64',
    'windows_x64' => 'win-x64',
    _ => throw UnsupportedError('Unsupported PDF test ABI: $abi'),
  };
  var root = Directory.current;
  while (!Directory('${root.path}/.dart_tool/hooks_runner/pdfium_dart')
      .existsSync()) {
    if (root.parent.path == root.path) {
      throw StateError('PDFium native build-hook outputs are missing');
    }
    root = root.parent;
  }
  final hooks = Directory('${root.path}/.dart_tool/hooks_runner/pdfium_dart');
  for (final file in hooks.listSync(recursive: true).whereType<File>()) {
    if (!file.path.endsWith('output.json')) continue;
    final data = jsonDecode(file.readAsStringSync()) as Map;
    for (final asset in data['assets'] as List? ?? const []) {
      final encoding = (asset as Map)['encoding'] as Map;
      final path = encoding['file'] as String?;
      if (encoding['id'] == 'package:pdfium_dart/libpdfium' &&
          path != null &&
          path.replaceAll('\\', '/').contains('/$target/') &&
          File(path).existsSync()) {
        Pdfrx.pdfiumModulePath = path;
        return;
      }
    }
  }
  throw StateError('No PDFium native build output matches the tester ABI $abi');
}
