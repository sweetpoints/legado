import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:test/test.dart';

class _NoHost implements ScriptHost {
  const _NoHost();
  @override
  Future<Object?> call(String method, List<Object?> args) async =>
      throw const EngineException('unsupported_host_api', 'No fallback');
}

void main() {
  test('capabilities mask regex quotes, comments and strings and recursively validate only bound source comment', () {
    final capabilities = PortableLegacyScriptCapabilities({
      'bookSourceComment': 'var d=org.jsoup.Jsoup.parse(java.ajax("https://fixture.invalid")); source.setVariable("1");',
    });
    expect(
      capabilities.supports(
        r'''eval(String(source.bookSourceComment)); var v=result.match(/&v=(.*?)"/)[1]; java.ajax(baseUrl);''',
      ),
      true,
    );
    expect(
      capabilities.supports(
        r'''var text="java.bad() org.fake"; /* org.fake */ result;''',
      ),
      true,
    );
    for (final code in [
      'org.jsoup.Jsoup.connect(baseUrl)',
      'org.foo.parse(result)',
      'java.bad()',
      'eval(java.ajax(baseUrl))',
      'Function("return 1")()',
      'globalThis["java"]',
      'var x=/unterminated',
    ]) {
      expect(capabilities.supports(code), false, reason: code);
    }
    expect(
      const PortableLegacyScriptCapabilities({
        'bookSourceComment': 'java.bad()',
      }).supports('eval(String(source.bookSourceComment))'),
      false,
    );
  });
  test('source variable state has nullable-string contracts and native capabilities stay typed errors', () async {
    final state = <String, Object?>{};
    final host = PortableLegacySourceStateHost(const _NoHost(), state);
    expect(await host.call('sourceState.getVariable', []), isNull);
    await host.call('sourceState.putVariable', ['设置源站[1-3]：1']);
    expect(await host.call('sourceState.getVariable', []), '设置源站[1-3]：1');
    await expectLater(
      host.call('sourceState.putVariable', [1]),
      throwsA(isA<EngineException>()),
    );
    await expectLater(
      host.call('browser.open', ['https://fixture.invalid']),
      throwsA(isA<EngineException>()),
    );
  });
}
