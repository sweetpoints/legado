import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/features/pdf/pdf_licenses.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('ships all exact native third-party licenses and exposes the standard license page', () async {
    const prefix = 'assets/notices/pdfium/';
    final provenance = jsonDecode(
      await rootBundle.loadString('${prefix}provenance.json'),
    ) as Map;
    final notices = provenance['notices'] as Map;
    expect(notices.length, 29);
    expect(
      notices.keys,
      containsAll([
        'licenses/pdfium.txt',
        'licenses/cpu_features.txt',
        'licenses/libunwind.txt',
        'licenses/catapult.txt',
        'licenses/icu.txt',
        'licenses/freetype.txt',
        'licenses/libjpeg_turbo.ijg',
      ]),
    );
    for (final entry in notices.entries) {
      final data = await rootBundle.load('$prefix${entry.key}');
      expect(
        sha256
            .convert(
              data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes),
            )
            .toString(),
        (entry.value as Map)['sha256'],
        reason: '${entry.key} is preserved verbatim',
      );
    }
    registerPdfLicenses();
    registerPdfLicenses(); // Startup registration is idempotent.
    final entries = await LicenseRegistry.licenses
        .where(
          (entry) => entry.packages.any((name) => name.startsWith('PDFium (')),
        )
        .toList();
    expect(entries.length, 29);
    expect(entries.every((entry) => entry.paragraphs.isNotEmpty), isTrue);
  });
}
