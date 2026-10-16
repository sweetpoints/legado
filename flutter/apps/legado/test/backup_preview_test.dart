import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/main.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  test('Material 3 text contrast is at least 4.5 in both themes', () {
    for (final brightness in Brightness.values) {
      final theme = readerTheme(brightness);
      expect(theme.useMaterial3, isTrue);
      final s = theme.colorScheme;
      for (final pair in [
        (s.onSurface, s.surface),
        (s.onPrimary, s.primary),
        (s.onSecondaryContainer, s.secondaryContainer),
        (s.onErrorContainer, s.errorContainer),
      ]) {
        final a = pair.$1.computeLuminance(), b = pair.$2.computeLuminance();
        expect(
          ((a > b ? a : b) + .05) / ((a > b ? b : a) + .05),
          greaterThanOrEqualTo(4.5),
        );
      }
    }
  });
}
