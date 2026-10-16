import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

bool _registered = false;

/// Call at startup so Flutter's standard license page includes native PDFium,
/// ICU, FreeType and every bundled upstream license even before opening a PDF.
void registerPdfLicenses() {
  if (_registered) return;
  _registered = true;
  LicenseRegistry.addLicense(() async* {
    const prefix = 'assets/notices/pdfium/';
    final manifest = jsonDecode(
      await rootBundle.loadString('${prefix}provenance.json'),
    ) as Map;
    for (final entry in (manifest['notices'] as Map).entries) {
      final data = await rootBundle.load('$prefix${entry.key}');
      final bytes = data.buffer.asUint8List(
        data.offsetInBytes,
        data.lengthInBytes,
      );
      final text = (entry.value as Map)['encoding'] == 'latin1'
          ? latin1.decode(bytes)
          : utf8.decode(bytes);
      yield LicenseEntryWithLineBreaks(['PDFium (${entry.key})'], text);
    }
  });
}
