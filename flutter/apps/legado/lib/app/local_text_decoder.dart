import 'dart:convert';
import 'dart:typed_data';

import 'package:enough_convert/gbk.dart';

/// Explicit choices are useful when a short legacy file cannot be identified.
enum LocalTextEncoding { utf8, utf16LittleEndian, utf16BigEndian, gbk }

typedef DecodedLocalText = ({String text, LocalTextEncoding encoding});

class LocalTextDecodeException implements Exception {
  const LocalTextDecodeException(this.code);
  final String code;
}

/// Strict local TXT decoding. It never replaces invalid input with U+FFFD.
///
/// BOMs are authoritative. Without a BOM, valid UTF-8 has priority. GBK automatic
/// recognition requires complete reversible byte pairs and positive GB2312
/// Chinese evidence; recognizable mixed/truncated UTF-8 is rejected. Ambiguous
/// legacy data can be decoded only using an explicit encoding choice.
abstract final class LocalTextDecoder {
  static const maximumBytes = 20 * 1024 * 1024;

  /// Reads at most [maximumBytes], including streams with absent/false metadata.
  static Future<Uint8List> readBounded(
    Stream<List<int>> input, {
    int? declaredLength,
  }) async {
    if (declaredLength != null && declaredLength > maximumBytes) {
      throw const LocalTextDecodeException('textTooLarge');
    }
    final output = BytesBuilder(copy: true);
    await for (final chunk in input) {
      if (output.length + chunk.length > maximumBytes) {
        throw const LocalTextDecodeException('textTooLarge');
      }
      output.add(chunk);
    }
    return output.takeBytes();
  }

  static DecodedLocalText decode(
    Uint8List bytes, {
    LocalTextEncoding? encoding,
  }) {
    if (bytes.length > maximumBytes) {
      throw const LocalTextDecodeException('textTooLarge');
    }
    try {
      final bom = _bom(bytes);
      if (bom != null) {
        if (encoding != null && encoding != bom.encoding) {
          throw const LocalTextDecodeException('textEncodingConflict');
        }
        return _decodeAs(bytes, bom.encoding, bom.length);
      }
      if (encoding != null) return _decodeAs(bytes, encoding, 0);
      try {
        return _decodeAs(bytes, LocalTextEncoding.utf8, 0);
      } on FormatException {
        // A malformed BOM/prefix is not permission to reinterpret UTF-8 as GBK.
      }
      if (_isTruncatedUtf8(bytes) || _hasUtf8UnicodeEvidence(bytes)) {
        _unsupported();
      }
      final gbkText = _decodeAs(bytes, LocalTextEncoding.gbk, 0);
      var pairs = 0;
      var commonChinese = 0;
      for (var i = 0; i < bytes.length; i++) {
        if (bytes[i] < 0x80) continue;
        final lead = bytes[i];
        final trail = bytes[++i]; // _decodeAs has validated every pair.
        pairs++;
        if (lead >= 0xb0 && lead <= 0xf7 && trail >= 0xa1 && trail <= 0xfe) {
          commonChinese++;
        }
      }
      if (pairs == 0 || commonChinese == 0 || commonChinese * 2 < pairs) {
        _unsupported();
      }
      return gbkText;
    } on FormatException {
      throw const LocalTextDecodeException('unsupportedText');
    }
  }

  static ({LocalTextEncoding encoding, int length})? _bom(Uint8List bytes) {
    bool starts(List<int> prefix) =>
        bytes.length >= prefix.length &&
        Iterable<int>.generate(prefix.length)
            .every((i) => bytes[i] == prefix[i]);
    if (starts([0xff, 0xfe, 0, 0]) || starts([0, 0, 0xfe, 0xff])) {
      _unsupported(); // UTF-32 must never be interpreted as UTF-16.
    }
    if (starts([0xef, 0xbb, 0xbf])) {
      return (encoding: LocalTextEncoding.utf8, length: 3);
    }
    if (starts([0xff, 0xfe])) {
      return (encoding: LocalTextEncoding.utf16LittleEndian, length: 2);
    }
    if (starts([0xfe, 0xff])) {
      return (encoding: LocalTextEncoding.utf16BigEndian, length: 2);
    }
    return null;
  }

  static DecodedLocalText _decodeAs(
    Uint8List bytes,
    LocalTextEncoding encoding,
    int start,
  ) {
    final String text;
    switch (encoding) {
      case LocalTextEncoding.utf8:
        // Dart removes one initial UTF-8 BOM itself; slicing first would remove
        // a second intentional leading U+FEFF as well.
        text = utf8.decode(bytes, allowMalformed: false);
      case LocalTextEncoding.utf16LittleEndian:
      case LocalTextEncoding.utf16BigEndian:
        if ((bytes.length - start).isOdd) _unsupported();
        final data = ByteData.sublistView(bytes);
        final order = encoding == LocalTextEncoding.utf16LittleEndian
            ? Endian.little
            : Endian.big;
        final units = <int>[];
        for (var i = start; i < bytes.length; i += 2) {
          final unit = data.getUint16(i, order);
          if (unit >= 0xd800 && unit <= 0xdbff) {
            if (i + 4 > bytes.length) _unsupported();
            final tail = data.getUint16(i + 2, order);
            if (tail < 0xdc00 || tail > 0xdfff) _unsupported();
            units.addAll([unit, tail]);
            i += 2;
          } else {
            if (unit >= 0xdc00 && unit <= 0xdfff) _unsupported();
            units.add(unit);
          }
        }
        text = String.fromCharCodes(units);
      case LocalTextEncoding.gbk:
        final content = Uint8List.sublistView(bytes, start);
        for (var i = 0; i < content.length; i++) {
          final lead = content[i];
          if (lead < 0x80) continue;
          if (lead < 0x81 || lead > 0xfe || i + 1 >= content.length) {
            _unsupported();
          }
          final trail = content[++i];
          if (trail < 0x40 || trail > 0xfe || trail == 0x7f) _unsupported();
        }
        text = gbk.decode(content, allowInvalid: false);
        final encoded = gbk.encode(text);
        if (encoded.length != content.length) _unsupported();
        for (var i = 0; i < encoded.length; i++) {
          if (encoded[i] != content[i]) _unsupported();
        }
    }
    for (final rune in text.runes) {
      if ((rune < 0x20 &&
              rune != 9 &&
              rune != 10 &&
              rune != 12 &&
              rune != 13) ||
          rune == 0x7f ||
          (rune >= 0x80 && rune <= 0x9f) ||
          rune == 0xfffe ||
          rune == 0xffff) {
        _unsupported();
      }
    }
    return (text: text, encoding: encoding);
  }

  /// Recognizes an otherwise valid UTF-8 stream ending inside a code point.
  static bool _isTruncatedUtf8(Uint8List bytes) {
    for (var i = 0; i < bytes.length;) {
      final lead = bytes[i];
      if (lead < 0x80) {
        i++;
        continue;
      }
      final count = lead >= 0xc2 && lead <= 0xdf
          ? 2
          : lead >= 0xe0 && lead <= 0xef
          ? 3
          : lead >= 0xf0 && lead <= 0xf4
          ? 4
          : 0;
      if (count == 0) return false;
      for (var j = 1; j < count; j++) {
        if (i + j >= bytes.length) return true;
        final value = bytes[i + j];
        if (value < 0x80 ||
            value > 0xbf ||
            (j == 1 &&
                ((lead == 0xe0 && value < 0xa0) ||
                    (lead == 0xed && value > 0x9f) ||
                    (lead == 0xf0 && value < 0x90) ||
                    (lead == 0xf4 && value > 0x8f)))) {
          return false;
        }
      }
      i += count;
    }
    return false;
  }

  /// Multiple valid UTF-8 non-ASCII characters (or one supplementary scalar)
  /// amid invalid UTF-8 indicate mixed/damaged text rather than reliable GBK.
  static bool _hasUtf8UnicodeEvidence(Uint8List bytes) {
    var run = 0;
    for (var i = 0; i < bytes.length;) {
      final lead = bytes[i];
      final count = lead >= 0xc2 && lead <= 0xdf
          ? 2
          : lead >= 0xe0 && lead <= 0xef
          ? 3
          : lead >= 0xf0 && lead <= 0xf4
          ? 4
          : 0;
      var valid = count != 0 && i + count <= bytes.length;
      if (valid) {
        for (var j = 1; j < count; j++) {
          final value = bytes[i + j];
          if (value < 0x80 ||
              value > 0xbf ||
              (j == 1 &&
                  ((lead == 0xe0 && value < 0xa0) ||
                      (lead == 0xed && value > 0x9f) ||
                      (lead == 0xf0 && value < 0x90) ||
                      (lead == 0xf4 && value > 0x8f)))) {
            valid = false;
            break;
          }
        }
      }
      if (valid) {
        if (count == 4 || ++run >= 2) return true;
        i += count;
      } else {
        run = 0;
        i++;
      }
    }
    return false;
  }

  static Never _unsupported() => throw const FormatException(
    'Unsupported, ambiguous or malformed TXT encoding',
  );
}
