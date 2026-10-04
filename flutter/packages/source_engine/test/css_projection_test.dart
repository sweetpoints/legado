import 'package:html/dom.dart';
import 'package:source_engine/source_engine.dart';
import 'package:test/test.dart';

import 'engine_test.dart' show NoScripts, NoHost;

void main() {
  final rules = RuleEvaluator(NoScripts());
  final context = ScriptContext(host: NoHost());
  const input =
      '<a href="mailto:user@example.org" data-url="https://example.org/@writer" class="email@marker">Email</a><a href="/plain">Plain</a>';
  test(
    'quoted email and URL selectors without projection return nodes',
    () async {
      for (final rule in [
        '@css:a[href*="@"]',
        "@css:a[data-url='https://example.org/@writer']",
      ]) {
        final nodes = await rules.evaluate(
          rule,
          input,
          context,
          elements: true,
        );
        expect(nodes, hasLength(1));
        expect((nodes.single as Element).text, 'Email');
      }
    },
  );
  test('projection after quoted attribute and functional pseudo', () async {
    expect(
      await rules.evaluate(
        '@css:a[href="mailto:user@example.org"]@href',
        input,
        context,
      ),
      ['mailto:user@example.org'],
    );
    expect(
      await rules.evaluate('@css:a:not([href*="@"])@text', input, context),
      ['Plain'],
    );
    final nodes = await rules.evaluate(
      '@css:a:not([href*="@"])',
      input,
      context,
      elements: true,
    );
    expect((nodes.single as Element).text, 'Plain');
  });
  test(
    'escaped @ remains in CSS selector before real output delimiter',
    () async {
      expect(
        await rules.evaluate(r'@css:.email\@marker@text', input, context),
        ['Email'],
      );
      final nodes = await rules.evaluate(
        r'@css:.email\@marker',
        input,
        context,
        elements: true,
      );
      expect((nodes.single as Element).text, 'Email');
    },
  );
  test(
    'quoted and escaped selectors retain fallback concat replacement semantics',
    () async {
      expect(
        await rules.evaluate(
          '@css:a[href="none@missing"]@text||@css:a[href*="@"]@text',
          input,
          context,
        ),
        ['Email'],
      );
      expect(
        await rules.evaluate(
          r'@css:.email\@marker@text&&@css:a:not([href*="@"])@text',
          input,
          context,
        ),
        ['Email', 'Plain'],
      );
      expect(
        await rules.evaluate(
          '@css:a[href*="@"]@text##Email##Contact',
          input,
          context,
        ),
        ['Contact'],
      );
    },
  );
  test('malformed quote and bracket still fail selector validation', () async {
    await expectLater(
      rules.evaluate('@css:a[href="unterminated@value', input, context),
      throwsA(isA<FormatException>()),
    );
    await expectLater(
      rules.evaluate('@css:a[href*="@"', input, context),
      throwsA(isA<FormatException>()),
    );
  });
}
