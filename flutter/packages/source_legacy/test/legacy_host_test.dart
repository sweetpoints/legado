import 'package:test/test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';

class RecordingHost implements ScriptHost {
  final calls = <Map<String, Object?>>[];
  int status = 302;
  @override
  Future<Object?> call(String method, List<Object?> args) async {
    if (method != 'net.request') throw StateError(method);
    final request = Map<String, Object?>.from(args.single as Map);
    calls.add(request);
    return {
      'url': request['url'],
      'status': status,
      'headers': {'location': '/next', 'x-test': 'yes'},
      'body': 'body',
    };
  }
}

void main() {
  late RecordingHost delegate;
  late LegacyScriptHost host;
  setUp(() {
    delegate = RecordingHost();
    host = LegacyScriptHost(delegate);
  });
  test('base64 defaults flags and signed byte contracts', () async {
    expect(await host.call('java.base64Encode', ['abc']), 'YWJj');
    expect(await host.call('java.base64Encode', ['abc', 0]), 'YWJj\n');
    expect(await host.call('java.base64Encode', ['a', 1]), 'YQ\n');
    expect(await host.call('java.base64Encode', ['a', 5]), 'YQ\r\n');
    expect(await host.call('java.base64Decode', ['Y Q ==\n']), 'a');
    expect(await host.call('java.base64Decode', [null]), null);
    expect(await host.call('java.base64Decode', ['']), '');
    expect(await host.call('java.base64DecodeToByteArray', ['']), null);
    expect(await host.call('java.base64DecodeToByteArray', ['/w==']), [-1]);
    expect(await host.call('java.base64DecodeToByteArray', ['_w', 8]), [-1]);
    await expectLater(
      host.call('java.base64DecodeToByteArray', ['_w', 0]),
      throwsFormatException,
    );
    await expectLater(
      host.call('java.base64Encode', ['x', 32]),
      throwsArgumentError,
    );
  });
  test(
    'wrap lines at 76 characters and append correct final newline',
    () async {
      final value =
          await host.call('java.base64Encode', ['x' * 60, 0]) as String;
      expect(value.split('\n').map((s) => s.length).toList(), [76, 4, 0]);
    },
  );
  test('bytes charset conversion and invalid charset boundaries', () async {
    expect(await host.call('java.strToBytes', ['中', 'GBK']), [-42, -48]);
    expect(
      await host.call('java.bytesToStr', [
        [-42, -48],
        'GBK',
      ]),
      '中',
    );
    expect(await host.call('java.base64Decode', ['1tA=', 'GBK']), '中');
    final utf16 = await host.call('java.strToBytes', ['中', 'UTF-16']);
    expect(utf16, [-2, -1, 78, 45]);
    expect(await host.call('java.bytesToStr', [utf16, 'UTF-16']), '中');
    await expectLater(
      host.call('java.bytesToStr', [
        [256],
      ]),
      throwsArgumentError,
    );
    await expectLater(
      host.call('java.strToBytes', ['x', 'unknown']),
      throwsUnsupportedError,
    );
  });
  test('hex and actual digest names including md5 sixteen substring', () async {
    expect(await host.call('java.hexEncodeToString', ['中']), 'e4b8ad');
    expect(await host.call('java.hexDecodeToString', ['E4B8AD']), '中');
    expect(await host.call('java.hexDecodeToByteArray', ['f']), [15]);
    expect(await host.call('java.md5Encode16', ['abc']), '3cd24fb0d6963f7d');
    expect(
      await host.call('java.digestHex', ['abc', 'SHA-256']),
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    );
    expect(
      await host.call('java.digestBase64Str', ['abc', 'MD5']),
      'kAFQmDzST7DWlj99KOF/cg==',
    );
    await expectLater(
      host.call('java.digestHex', ['abc', 'unknown']),
      throwsUnsupportedError,
    );
  });
  test('encodeURI is Java form encoding rather than JS URI encoding', () async {
    expect(
      await host.call('java.encodeURI', ['a b+~*中文']),
      'a+b%2B%7E*%E4%B8%AD%E6%96%87',
    );
    expect(await host.call('java.encodeURI', ['中', 'GBK']), '%D6%D0');
    expect(await host.call('java.encodeURI', ['x', 'unknown']), '');
    await expectLater(
      host.call('java.decodeURI', ['x']),
      throwsUnsupportedError,
    );
  });
  test(
    'variable get is distinct from HTTP overload, methods preserve options',
    () async {
      expect(await host.call('java.get', ['missing']), '');
      await host.call('java.put', ['name', 'value']);
      expect(await host.call('java.get', ['name']), 'value');
      final get = await host.call('java.get', [
        'https://books.test',
        {'X': 'v'},
        1234,
      ]) as Map;
      expect(get['__legacyResponseKind'], 'jsoup');
      expect(delegate.calls.last, {
        'url': 'https://books.test',
        'method': 'GET',
        'headers': {'X': 'v'},
        'followRedirects': false,
        'timeoutMs': 1234,
      });
      await host.call('java.post', [
        'https://books.test',
        'data',
        '{"X":"v"}',
        2000,
      ]);
      expect(delegate.calls.last['body'], 'data');
      expect(delegate.calls.last['followRedirects'], false);
      await host.call('java.head', ['https://books.test', null]);
      expect(delegate.calls.last['method'], 'HEAD');
    },
  );
  test('Jsoup error status throws, connect and ajax expose body', () async {
    delegate.status = 404;
    await expectLater(
      host.call('java.get', ['https://books.test', null]),
      throwsA(
        isA<EngineException>().having(
          (e) => e.code,
          'code',
          'legacy.http_error',
        ),
      ),
    );
    expect(
      (await host.call('java.connect', ['https://books.test'])
          as Map)['status'],
      404,
    );
    expect(await host.call('java.ajax', ['https://books.test']), 'body');
  });
  test('unmappable encodings replace per codepoint', () async {
    expect(await host.call('java.strToBytes', ['中文', 'ISO-8859-1']), [63, 63]);
    expect(await host.call('java.strToBytes', ['😀', 'ASCII']), [63]);
    expect(
      await host.call('java.bytesToStr', [
        [-1],
        'UTF-8',
      ]),
      '\uFFFD',
    );
  });
  test('new utility namespace uses matching contracts', () async {
    final utility = SourceUtilityHost(delegate);
    expect(await utility.call('crypto.md5Short', ['abc']), '3cd24fb0d6963f7d');
    expect(await utility.call('encoding.hexDecode', ['e4b8ad']), '中');
    expect(
      await utility.call('encoding.base64EncodeWithFlags', ['a', 1]),
      'YQ\n',
    );
    expect(
      await utility.call('encoding.formDecode', ['%D6%D0+a%2Bb', 'GBK']),
      '中 a+b',
    );
    await expectLater(
      Future.sync(() => utility.call('encoding.formDecode', ['%XX'])),
      throwsFormatException,
    );
  });
  test(
    'ajax list and timeout connect header string and ordered ajaxAll',
    () async {
      expect(
        await host.call('java.ajax', [
          ['https://one.test', 'https://two.test'],
          2500,
        ]),
        'body',
      );
      expect(delegate.calls.last['url'], 'https://one.test');
      expect(delegate.calls.last['timeoutMs'], 2500);
      expect(delegate.calls.last['followRedirects'], true);
      final connect = await host.call('java.connect', [
        'https://books.test',
        '{"X":"v"}',
        3000,
      ]) as Map;
      expect(connect['__legacyResponseKind'], 'str');
      final all = await host.call('java.ajaxAll', [
        ['https://one.test', 'https://two.test'],
        false,
      ]) as List;
      expect(all.map((e) => (e as Map)['url']), [
        'https://one.test',
        'https://two.test',
      ]);
      await expectLater(
        host.call('java.ajaxAll', [[], true]),
        throwsUnsupportedError,
      );
      await expectLater(
        host.call('java.connect', ['https://books.test', {}]),
        throwsUnsupportedError,
      );
      await expectLater(
        host.call('java.ajax', ['https://books.test,{options}']),
        throwsUnsupportedError,
      );
    },
  );
}
