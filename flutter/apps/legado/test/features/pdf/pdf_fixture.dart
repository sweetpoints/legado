import 'dart:convert';
import 'dart:typed_data';

/// Independent, original two-page PDF; exact offsets exercise a real PDF parser.
Uint8List createPdfFixture() {
  final output = BytesBuilder();
  void write(String text) => output.add(ascii.encode(text));
  write('%PDF-1.4\n');
  final objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Resources << /Font << /F1 5 0 R >> >> /Contents 6 0 R >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Resources << /Font << /F1 5 0 R >> >> /Contents 7 0 R >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    for (var page = 1; page <= 2; page++)
      (() {
        final stream =
            'BT /F1 24 Tf 30 300 Td (Original PDF page $page) Tj ET\n';
        return '<< /Length ${stream.length} >>\nstream\n${stream}endstream';
      })(),
  ];
  final offsets = [0];
  for (var index = 0; index < objects.length; index++) {
    offsets.add(output.length);
    write('${index + 1} 0 obj\n${objects[index]}\nendobj\n');
  }
  final xref = output.length;
  write('xref\n0 ${objects.length + 1}\n0000000000 65535 f \n');
  for (final offset in offsets.skip(1)) {
    write('${offset.toString().padLeft(10, '0')} 00000 n \n');
  }
  write(
    'trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n',
  );
  return output.takeBytes();
}
