import 'dart:convert';

/// Legado's default UTF-8 form-body encoding, applied after template expansion.
/// Each component preserves its bytes only when entirely already form encoded.
/// Leading empty segments disappear exactly as in AnalyzeUrl.encodeParams.
String encodeLegacyFormUtf8Body(String input) {
  final output = StringBuffer();
  var position = 0;
  while (position <= input.length) {
    if (output.isNotEmpty) output.write('&');
    var end = input.indexOf('&', position);
    if (end < 0) end = input.length;
    final equals = input.indexOf('=', position);
    if (equals < 0 || equals > end) {
      output.write(_component(input.substring(position, end)));
    } else {
      output.write(_component(input.substring(position, equals)));
      output.write('=');
      output.write(_component(input.substring(equals + 1, end)));
    }
    position = end + 1;
  }
  return output.toString();
}

final _alreadyEncoded = RegExp(r'^(?:[A-Za-z0-9*._-]|%[0-9A-Fa-f]{2})*$');
String _component(String input) {
  final matched = _alreadyEncoded.firstMatch(input);
  if (matched != null && matched.end == input.length) return input;
  final output = StringBuffer();
  // Java's UTF-8 CharsetEncoder replaces malformed UTF-16 with '?', whereas
  // Dart's UTF-8 codec replaces it with U+FFFD. Preserve Java behavior here.
  final units = input.codeUnits;
  final normalized = <int>[];
  for (var index = 0; index < units.length; index++) {
    final unit = units[index];
    if (unit >= 0xd800 && unit <= 0xdbff) {
      if (index + 1 < units.length &&
          units[index + 1] >= 0xdc00 &&
          units[index + 1] <= 0xdfff) {
        normalized.add(unit);
        normalized.add(units[++index]);
      } else {
        normalized.add(0x3f);
      }
    } else if (unit >= 0xdc00 && unit <= 0xdfff) {
      normalized.add(0x3f);
    } else {
      normalized.add(unit);
    }
  }
  for (final byte in utf8.encode(String.fromCharCodes(normalized))) {
    if ((byte >= 65 && byte <= 90) ||
        (byte >= 97 && byte <= 122) ||
        (byte >= 48 && byte <= 57) ||
        const [42, 45, 46, 95].contains(byte)) {
      output.writeCharCode(byte);
    } else if (byte == 32) {
      output.write('+');
    } else {
      output.write('%${byte.toRadixString(16).toUpperCase().padLeft(2, '0')}');
    }
  }
  return output.toString();
}
