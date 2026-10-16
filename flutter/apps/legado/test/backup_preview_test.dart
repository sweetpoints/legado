import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/main.dart';

void main() {
  testWidgets('shows progress and clears stale preview on invalid input', (
    tester,
  ) async {
    await tester.pumpWidget(const LegadoApp());
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byType(TextField),
      jsonEncode([
        {
          'bookUrl': 'local://sample',
          'name': '旧书架',
          'durChapterIndex': 3,
          'durChapterPos': 19,
        },
      ]),
    );
    await tester.tap(find.text('预览'));
    await tester.pumpAndSettle();
    expect(find.text('旧书架'), findsOneWidget);
    expect(find.textContaining('字符位置 19'), findsOneWidget);
    await tester.enterText(find.byType(TextField), '[null]');
    await tester.tap(find.text('预览'));
    await tester.pumpAndSettle();
    expect(find.text('旧书架'), findsNothing);
    expect(find.textContaining('无法读取书架'), findsOneWidget);
  });
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
