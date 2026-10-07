import 'package:test/test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';

class _ParseHost implements ScriptHost {
  Object? value;
  _ParseHost(this.value);
  @override
  Future<Object?> call(String method, List<Object?> arguments) async => value;
}

void main() {
  test(
    'HTML4 named table includes basic Latin1 and extended entities',
    () async {
      final host = SourceUtilityHost(_ParseHost(null));
      expect(
        await host.call('encoding.unescapeHtml4', [
          '&quot;&amp;&lt;&gt;&nbsp;&eacute;&Alpha;&euro;&spades;',
        ]),
        '"&<>\u00a0éΑ€♠',
      );
      expect(
        await host.call('encoding.unescapeHtml4', [
          '&apos; &AMP; &copy &NotEqualTilde; &amp;lt;',
        ]),
        '&apos; &AMP; &copy &NotEqualTilde; &lt;',
      );
    },
  );
  test(
    'HTML4 numeric entities require semicolons and retain raw code points',
    () async {
      final host = SourceUtilityHost(_ParseHost(null));
      expect(
        await host.call('encoding.unescapeHtml4', [
          '&#65; &#x41; &#X1F600; &#128; &#0; &#xD800;',
        ]),
        'A A 😀 \u0080 \u0000 \ud800',
      );
      expect(
        await host.call('encoding.unescapeHtml4', [
          '&#65 &#x41 &#12a; &#x; &#2147483648;',
        ]),
        '&#65 &#x41 &#12a; &#x; &#2147483648;',
      );
      expect(
        () => host.call('encoding.unescapeHtml4', ['&#1114112;']),
        throwsArgumentError,
      );
      expect(
        () => host.call('encoding.unescapeHtml4', [null]),
        throwsArgumentError,
      );
    },
  );
  test(
    'legacy scalar unescapes while lists and opt-out retain entities',
    () async {
      final delegate = _ParseHost('A&amp;B');
      final host = LegacyScriptHost(delegate);
      expect(await host.call('java.getString', [r'$.title', '{}']), 'A&B');
      expect(
        await host.call('java.getString', [
          r'$.title',
          '{}',
          false,
          null,
          false,
        ]),
        'A&amp;B',
      );
      delegate.value = ['A&amp;B'];
      expect(await host.call('java.getStringList', [r'$.title', '{}']), [
        'A&amp;B',
      ]);
    },
  );
  test(
    'legacy scalar URL empty result falls back but empty rule stays empty',
    () async {
      final host = LegacyScriptHost(_ParseHost(''));
      expect(
        await host.call('java.getString', [
          'tag.a@href',
          '<p>x</p>',
          true,
          'https://books.test/base/',
        ]),
        'https://books.test/base/',
      );
      expect(
        await host.call('java.getString', [
          '',
          '<p>x</p>',
          true,
          'https://books.test/base/',
        ]),
        '',
      );
      expect(
        await host.call('java.getString', [
          'tag.a@href',
          '<p>x</p>',
          false,
          'https://books.test/base/',
        ]),
        '',
      );
      final listHost = LegacyScriptHost(_ParseHost(<String>[]));
      expect(
        await listHost.call('java.getStringList', [
          'tag.a@href',
          '<p>x</p>',
          true,
          'https://books.test/base/',
        ]),
        isEmpty,
      );
    },
  );
  test(
    'scalar URL unescaping precedes blank fallback and resolution',
    () async {
      final delegate = _ParseHost('&nbsp;');
      final host = LegacyScriptHost(delegate);
      expect(
        await host.call('java.getString', [
          r'$.url',
          '{}',
          true,
          'https://books.test/base/',
        ]),
        'https://books.test/base/',
      );
      delegate.value = 'next?a=1&amp;b=2';
      expect(
        await host.call('java.getString', [
          r'$.url',
          '{}',
          true,
          'https://books.test/base/',
        ]),
        'https://books.test/base/next?a=1&b=2',
      );
      await expectLater(
        host.call('java.getString', [r'$.url', '{}', true, '/relative']),
        throwsA(
          isA<EngineException>().having((e) => e.code, 'code', 'invalid_url'),
        ),
      );
      delegate.value = '';
      expect(await host.call('java.getString', [r'$.url', '{}', true]), '');
    },
  );
}
