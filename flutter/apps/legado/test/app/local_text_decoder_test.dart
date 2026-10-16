import 'dart:convert';
import 'dart:typed_data';

import 'package:enough_convert/gbk.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/foundation.dart' show compute;
import 'package:legado_app/app/local_text_decoder.dart';

Uint8List utf16(String value, {required bool little, bool bom = true}) {
  final result = BytesBuilder();
  if (bom) result.add(little ? [0xff, 0xfe] : [0xfe, 0xff]);
  for (final unit in value.codeUnits) {
    result.add(little ? [unit & 255, unit >> 8] : [unit >> 8, unit & 255]);
  }
  return result.takeBytes();
}

Matcher failsWith(String code) => throwsA(
  isA<LocalTextDecodeException>().having((e) => e.code, 'code', code),
);

void main() {
  const text = '第一章 起点\r\n你好，世界。\nEmoji 😀 and café\t结尾';

  test(
    'decoding and typed errors cross the background isolate boundary',
    () async {
      final result = await compute(
        LocalTextDecoder.decode,
        Uint8List.fromList(gbk.encode('第一章 中文正文')),
      );
      expect(result.text, '第一章 中文正文');
      await expectLater(
        compute(LocalTextDecoder.decode, Uint8List.fromList([0xff])),
        failsWith('unsupportedText'),
      );
    },
  );

  test('strict UTF8 preserves Chinese, emoji, accents and line endings', () {
    final decoded = LocalTextDecoder.decode(
      Uint8List.fromList(utf8.encode(text)),
    );
    expect(decoded, (text: text, encoding: LocalTextEncoding.utf8));
  });

  test('UTF8 BOM is stripped exactly once and remains authoritative', () {
    final decoded = LocalTextDecoder.decode(
      Uint8List.fromList([0xef, 0xbb, 0xbf, ...utf8.encode('\uFEFF$text')]),
    );
    expect(decoded.text, '\uFEFF$text');
    expect(
      () => LocalTextDecoder.decode(
        Uint8List.fromList([0xef, 0xbb, 0xbf, ...gbk.encode('你好世界')]),
      ),
      failsWith('unsupportedText'),
    );
  });

  for (final little in [true, false]) {
    test(
      'BOM UTF16 ${little ? 'LE' : 'BE'} supports valid surrogate pairs',
      () {
        expect(LocalTextDecoder.decode(utf16(text, little: little)), (
          text: text,
          encoding: little
              ? LocalTextEncoding.utf16LittleEndian
              : LocalTextEncoding.utf16BigEndian,
        ));
      },
    );
    test(
      'UTF16 ${little ? 'LE' : 'BE'} rejects truncated units and isolated surrogates',
      () {
        final odd = utf16('你好', little: little);
        for (final input in [
          Uint8List.sublistView(odd, 0, odd.length - 1),
          utf16(String.fromCharCodes([0xd800]), little: little),
          utf16(String.fromCharCodes([0xdc00]), little: little),
          utf16(String.fromCharCodes([0xd800, 0x4e00]), little: little),
        ]) {
          expect(
            () => LocalTextDecoder.decode(input),
            failsWith('unsupportedText'),
          );
        }
      },
    );
  }

  test('fixed real GBK bytes decode Chinese and ASCII without replacement', () {
    final bytes = Uint8List.fromList([
      0xb5,
      0xda,
      0xd2,
      0xbb,
      0xd5,
      0xc2,
      0x20,
      0xc4,
      0xe3,
      0xba,
      0xc3,
      0xa3,
      0xac,
      0xca,
      0xc0,
      0xbd,
      0xe7,
      0xa1,
      0xa3,
      0x0d,
      0x0a,
      0x41,
      0x53,
      0x43,
      0x49,
      0x49,
    ]);
    expect(LocalTextDecoder.decode(bytes), (
      text: '第一章 你好，世界。\r\nASCII',
      encoding: LocalTextEncoding.gbk,
    ));
  });

  test('short common GBK Chinese can be identified', () {
    expect(
      LocalTextDecoder.decode(Uint8List.fromList(gbk.encode('你好'))).text,
      '你好',
    );
  });

  test(
    'ASCII and empty files are UTF8 rather than guessed legacy encodings',
    () {
      for (final value in ['', 'ASCII\nline\t1', '\n']) {
        expect(
          LocalTextDecoder.decode(Uint8List.fromList(utf8.encode(value))),
          (text: value, encoding: LocalTextEncoding.utf8),
        );
      }
    },
  );

  test(
    'explicit encoding resolves rare GBK and BOM-less UTF16 without guessing',
    () {
      final rare = Uint8List.fromList(gbk.encode('龘'));
      expect(() => LocalTextDecoder.decode(rare), failsWith('unsupportedText'));
      expect(
        LocalTextDecoder.decode(rare, encoding: LocalTextEncoding.gbk).text,
        '龘',
      );
      expect(
        LocalTextDecoder.decode(
          utf16('你好😀', little: true, bom: false),
          encoding: LocalTextEncoding.utf16LittleEndian,
        ).text,
        '你好😀',
      );
      expect(
        () => LocalTextDecoder.decode(utf16('ASCII', little: true, bom: false)),
        failsWith('unsupportedText'),
      );
    },
  );

  test('conflicting explicit encoding cannot override a BOM', () {
    expect(
      () => LocalTextDecoder.decode(
        utf16('你好', little: true),
        encoding: LocalTextEncoding.gbk,
      ),
      failsWith('unsupportedText'),
    );
  });

  test('UTF32 BOM is explicitly unsupported', () {
    for (final bytes in [
      [0xff, 0xfe, 0, 0, 0x41, 0, 0, 0],
      [0, 0, 0xfe, 0xff, 0, 0, 0, 0x41],
    ]) {
      expect(
        () => LocalTextDecoder.decode(Uint8List.fromList(bytes)),
        failsWith('unsupportedText'),
      );
    }
  });

  test('truncated UTF8 cannot accidentally be accepted as GBK', () {
    for (final value in ['中', '😀', '正文中文']) {
      final encoded = utf8.encode(value);
      for (var missing = 1; missing < (value == '😀' ? 4 : 3); missing++) {
        expect(
          () => LocalTextDecoder.decode(
            Uint8List.fromList(encoded.sublist(0, encoded.length - missing)),
          ),
          failsWith('unsupportedText'),
        );
      }
    }
  });

  test('mixed UTF8 and GBK is rejected instead of producing readable-looking mojibake', () {
    for (final bytes in [
      [...utf8.encode('第一章 正文'), ...gbk.encode('你好世界')],
      [...gbk.encode('你好世界'), 0x0a, ...utf8.encode('正文中文')],
      [...utf8.encode('😀'), ...gbk.encode('你好世界这里正文')],
      [...utf8.encode('éé'), ...gbk.encode('你好世界这里正文')],
    ]) {
      expect(
        () => LocalTextDecoder.decode(Uint8List.fromList(bytes)),
        failsWith('unsupportedText'),
      );
    }
  });

  test(
    'malformed GBK, GB18030 four-byte sequences and binary controls rejected',
    () {
      for (final bytes in [
        [0xc4],
        [0xff, 0xff],
        [0x81, 0x7f],
        [0x81, 0x30, 0x81, 0x30],
        [0xd6, 0xd0, 0x00],
        [0x41, 0x00, 0x42],
        [0xd6, 0xd0, 0xce, 0xc4, 0x81],
        [0x80],
        [0x01, 0x02, 0x03],
      ]) {
        expect(
          () => LocalTextDecoder.decode(Uint8List.fromList(bytes)),
          failsWith('unsupportedText'),
        );
      }
    },
  );

  test('explicit GBK still requires valid, reversible bytes', () {
    expect(
      () => LocalTextDecoder.decode(
        Uint8List.fromList([0x81, 0x30]),
        encoding: LocalTextEncoding.gbk,
      ),
      failsWith('unsupportedText'),
    );
  });

  test(
    'read bound rejects declared oversize before consuming any stream chunk',
    () async {
      var read = false;
      Stream<List<int>> input() async* {
        read = true;
        yield [0x41];
      }

      await expectLater(
        LocalTextDecoder.readBounded(
          input(),
          declaredLength: LocalTextDecoder.maximumBytes + 1,
        ),
        failsWith('textTooLarge'),
      );
      expect(read, isFalse);
    },
  );

  test(
    'read bound applies to absent or false metadata and stops the stream',
    () async {
      for (final declaredLength in [null, 0, 1]) {
        var reachedAfterOverflow = false;
        Stream<List<int>> input() async* {
          yield Uint8List(LocalTextDecoder.maximumBytes);
          yield [0x41];
          reachedAfterOverflow = true;
          yield [0x42];
        }

        await expectLater(
          LocalTextDecoder.readBounded(input(), declaredLength: declaredLength),
          failsWith('textTooLarge'),
        );
        expect(reachedAfterOverflow, isFalse);
      }
    },
  );

  test(
    'read bound allows exactly 20 MiB and assembles detached chunks',
    () async {
      final chunk = Uint8List(LocalTextDecoder.maximumBytes);
      final result = await LocalTextDecoder.readBounded(Stream.value(chunk));
      expect(result.length, LocalTextDecoder.maximumBytes);
      chunk[0] = 10;
      expect(result[0], 0);
      expect(
        () => LocalTextDecoder.decode(
          Uint8List(LocalTextDecoder.maximumBytes + 1),
        ),
        failsWith('textTooLarge'),
      );
    },
  );
}
