import 'contracts.dart';

/// Expands the finite, non-JavaScript subset of old page templates.
/// Plain identifier placeholders remain for the caller's usual substitution.
String expandLegacyPageTemplate(
  String template,
  Map<String, Object?> input, {
  bool urlChoices = false,
}) {
  const maxSafe = 9007199254740991;
  int page() {
    final value = input['page'];
    if (value is! int || value < 1 || value > maxSafe) {
      throw const EngineException(
        'legacy_page_requires_migration',
        'Legacy page templates require a positive JS-safe integer page',
      );
    }
    return value;
  }

  Never unsupported() => throw const EngineException(
    'legacy_page_requires_migration',
    'Unsupported legacy page expression',
  );
  final choicePattern = RegExp(r'<([^<>]*,[^<>]*)>');
  if (urlChoices) {
    for (final match in choicePattern.allMatches(template)) {
      if (match[0]!.contains('{{') || match[0]!.contains('}}')) {
        unsupported();
      }
    }
    final outsideChoices = template.replaceAll(choicePattern, '');
    if (outsideChoices.contains('<') || outsideChoices.contains('>')) {
      unsupported();
    }
  }
  var result = template.replaceAllMapped(RegExp(r'\{\{([\s\S]*?)\}\}'), (
    match,
  ) {
    final expression = match[1]!;
    final arithmetic = RegExp(r'^ *page *([+-]) *(0|[1-9][0-9]*) *$')
        .firstMatch(expression);
    if (arithmetic != null) {
      final offset = int.tryParse(arithmetic[2]!);
      if (offset == null || offset > maxSafe) unsupported();
      final value = arithmetic[1] == '+' ? page() + offset : page() - offset;
      if (value < -maxSafe || value > maxSafe) unsupported();
      return value.toString();
    }
    if (!RegExp(r'^[A-Za-z][A-Za-z0-9_]*$').hasMatch(expression)) {
      unsupported();
    }
    if (expression == 'page') page();
    return match[0]!;
  });
  // An incomplete placeholder must not become a silently encoded URL.
  final withoutPlaceholders = result.replaceAll(
    RegExp(r'\{\{[A-Za-z][A-Za-z0-9_]*\}\}'),
    '',
  );
  if (withoutPlaceholders.contains('{{') ||
      withoutPlaceholders.contains('}}')) {
    unsupported();
  }
  if (urlChoices) {
    result = result.replaceAllMapped(choicePattern, (match) {
      final choices = match[1]!.split(',');
      final index = page() - 1;
      return _javaTrim(
        choices[index < choices.length ? index : choices.length - 1],
      );
    });
  }
  return result;
}

String _javaTrim(String value) {
  var start = 0;
  var end = value.length;
  while (start < end && value.codeUnitAt(start) <= 0x20) {
    start++;
  }
  while (end > start && value.codeUnitAt(end - 1) <= 0x20) {
    end--;
  }
  return value.substring(start, end);
}
