import 'dart:io';

import 'package:source_engine/source_engine.dart';
import 'package:test/test.dart';

import 'engine_test.dart' show NoScripts, NoHost;

void main() {
  final evaluator = RuleEvaluator(NoScripts());
  final context = ScriptContext(host: NoHost());
  const input =
      '<section id="root"><div class="book"> A <b>B</b><br>C <a href="/a">one</a><a href="/a">two</a></div><div class="book">D</div><div class="book">E</div></section>';
  test('legacy shorthand chaining, index lists and exclude', () async {
    expect(
      await evaluator.evaluate('@legacy:class.book.0@ownText', input, context),
      ['A C'],
    );
    expect(
      await evaluator.evaluate('@legacy:class.book.-1@text', input, context),
      ['E'],
    );
    expect(
      await evaluator.evaluate('@legacy:class.book!0@text', input, context),
      ['D', 'E'],
    );
    expect(
      await evaluator.evaluate('@legacy:class.book.2:1@text', input, context),
      ['E', 'D'],
    );
    expect(
      await evaluator.evaluate('@legacy:class.book[-1:0]@text', input, context),
      ['E', 'D', 'A B C onetwo'],
    );
    expect(
      await evaluator.evaluate('@legacy:class.book[!0,2]@text', input, context),
      ['D'],
    );
    expect(
      await evaluator.evaluate(
        '@legacy:id.root@children.1@text',
        input,
        context,
      ),
      ['D'],
    );
    expect(
      await evaluator.evaluate(
        '@legacy:class.book.0@tag.a@href',
        input,
        context,
      ),
      ['/a'],
    );
  });
  test('legacy extraction includes row root, direct text nodes, no destructive html', () async {
    final rows = await evaluator.evaluate(
      '@legacy:class.book',
      input,
      context,
      elements: true,
    );
    expect(
      await evaluator.evaluate(
        '@legacy:class.book@textNodes',
        rows.first,
        context,
      ),
      ['A\nC'],
    );
    expect(
      await evaluator.evaluate('@legacy:tag.div@ownText', rows.first, context),
      ['A C'],
    );
    const markup = '<div><script>x</script><style>y</style><b>A</b></div>';
    final nodes = await evaluator.evaluate(
      '@legacy:tag.div',
      markup,
      context,
      elements: true,
    );
    expect(await evaluator.evaluate('@legacy:html', nodes.first, context), [
      '<div><b>A</b></div>',
    ]);
    expect(
      (await evaluator.evaluate(
        '@legacy:all',
        nodes.first,
        context,
      )).single.toString(),
      contains('<script>x</script>'),
    );
  });
  test('session cookie export restore preserves scope and expiry', () {
    final client = NetworkClient();
    client.importCookies(Uri.parse('https://example.org/a/login'), [
      Cookie('token', 'value')
        ..secure = true
        ..path = '/a',
    ]);
    final restored = NetworkClient();
    restored.restoreCookies(client.exportCookies());
    expect(
      restored.cookieHeader(Uri.parse('https://example.org/a/chapter')),
      'token=value',
    );
    expect(
      restored.cookieHeader(Uri.parse('http://example.org/a/chapter')),
      '',
    );
    expect(
      restored.cookieHeader(Uri.parse('https://sub.example.org/a/chapter')),
      '',
    );
    expect(restored.cookieHeader(Uri.parse('https://example.org/b')), '');
    client.close();
    restored.close();
  });
}
