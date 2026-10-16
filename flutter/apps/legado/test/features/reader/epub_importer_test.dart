import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/features/reader/epub_importer.dart';

import 'reader_test.dart' show MemoryLibrary;

// Independent stored ZIP fixture encoder, deliberately not importer internals.
Uint8List zip(Map<String, List<int>> entries) {
  final local = BytesBuilder();
  final central = BytesBuilder();
  for (final entry in entries.entries) {
    final name = utf8.encode(entry.key);
    final data = entry.value;
    var crc = 0xffffffff;
    for (final byte in data) {
      crc ^= byte;
      for (var i = 0; i < 8; i++) {
        crc = (crc >> 1) ^ ((crc & 1) == 0 ? 0 : 0xedb88320);
      }
    }
    crc ^= 0xffffffff;
    final header = ByteData(30);
    header.setUint32(0, 0x04034b50, Endian.little);
    header.setUint16(4, 20, Endian.little);
    header.setUint16(6, 0x800, Endian.little);
    header.setUint32(14, crc, Endian.little);
    header.setUint32(18, data.length, Endian.little);
    header.setUint32(22, data.length, Endian.little);
    header.setUint16(26, name.length, Endian.little);
    final directory = ByteData(46);
    directory.setUint32(0, 0x02014b50, Endian.little);
    directory.setUint16(4, 20, Endian.little);
    directory.setUint16(6, 20, Endian.little);
    directory.setUint16(8, 0x800, Endian.little);
    directory.setUint32(16, crc, Endian.little);
    directory.setUint32(20, data.length, Endian.little);
    directory.setUint32(24, data.length, Endian.little);
    directory.setUint16(28, name.length, Endian.little);
    directory.setUint32(42, local.length, Endian.little);
    central.add(directory.buffer.asUint8List());
    central.add(name);
    local.add(header.buffer.asUint8List());
    local.add(name);
    local.add(data);
  }
  final end = ByteData(22);
  end.setUint32(0, 0x06054b50, Endian.little);
  end.setUint16(8, entries.length, Endian.little);
  end.setUint16(10, entries.length, Endian.little);
  end.setUint32(12, central.length, Endian.little);
  end.setUint32(16, local.length, Endian.little);
  return Uint8List.fromList([
    ...local.takeBytes(),
    ...central.takeBytes(),
    ...end.buffer.asUint8List(),
  ]);
}

Map<String, List<int>> fixture() => {
  'mimetype': utf8.encode('application/epub+zip'),
  'META-INF/container.xml': File('test/fixtures/epub/container.xml')
      .readAsBytesSync(),
  for (final path in [
    'book.opf',
    'nav.xhtml',
    'text/one.xhtml',
    'text/two.xhtml',
  ])
    'EPUB/$path': File('test/fixtures/epub/EPUB/$path').readAsBytesSync(),
};

void replace(
  Map<String, List<int>> files,
  String path,
  String from,
  String to,
) {
  files[path] = utf8.encode(utf8.decode(files[path]!).replaceFirst(from, to));
}

Matcher failure(EpubImportFailure kind) =>
    isA<EpubImportException>().having((e) => e.failure, 'failure', kind);

void main() {
  test('actual namespaced EPUB imports spine order, nav titles and safe Unicode text', () async {
    final library = MemoryLibrary();
    final book = await EpubImporter.importBytes(
      library,
      name: '文件名.epub',
      bytes: zip(fixture()),
    );
    expect(book.name, '跨平台阅读 😀');
    expect(book.author, '作者甲, Author B');
    final chapters = await library.chapters(book.url);
    expect(chapters.map((c) => c.title), ['第一章 · 导航标题', '第二章 · 导航标题']);
    expect(chapters.map((c) => c.index), [0, 1]);
    final text = (await library.content(book.url, chapters.first.url))!;
    expect(text, '第一章\n\n你好😀，世界！\n\n第二段\n换行 & 符号。');
    expect(text, isNot(contains('不应出现')));
    expect(text, isNot(contains('隐藏正文')));
    final offset = text.indexOf('世界');
    await library.updateProgress(book.url, 0, offset);
    expect(library.records[book.url]!.characterOffset, offset);
    expect(text.substring(offset), startsWith('世界'));
  });

  test('repacking archive and renaming file preserves content identity and progress', () async {
    final library = MemoryLibrary();
    final files = fixture();
    final first = await EpubImporter.importBytes(
      library,
      name: 'first.epub',
      bytes: zip(files),
    );
    await library.updateProgress(first.url, 1, 3);
    final reordered = Map<String, List<int>>.fromEntries(
      files.entries.toList().reversed,
    );
    final second = await EpubImporter.importBytes(
      library,
      name: 'second.epub',
      bytes: zip(reordered),
    );
    expect(second.url, first.url);
    expect(second.chapterIndex, 1);
    expect(second.characterOffset, 3);
    expect(library.records.length, 1);
  });

  test('relative parent paths normalize inside archive and NCX supplies EPUB 2 titles', () async {
    final library = MemoryLibrary();
    final files = fixture();
    files.remove('EPUB/nav.xhtml');
    replace(
      files,
      'EPUB/book.opf',
      'href="text/one.xhtml"',
      'href="text/../text/one.xhtml"',
    );
    replace(
      files,
      'EPUB/book.opf',
      '<opf:item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>',
      '<opf:item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>',
    );
    replace(
      files,
      'EPUB/book.opf',
      '<opf:spine><opf:itemref idref="nav" linear="no"/>',
      '<opf:spine toc="ncx">',
    );
    files['EPUB/toc.ncx'] = utf8.encode(
      '<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap><navPoint><navLabel><text>NCX 第一章</text></navLabel><content src="text/one.xhtml#start"/></navPoint></navMap></ncx>',
    );
    final book = await EpubImporter.importBytes(
      library,
      name: 'book',
      bytes: zip(files),
    );
    expect((await library.chapters(book.url)).first.title, 'NCX 第一章');
  });

  for (final malicious in [
    'https://example.com/one.xhtml',
    'data:text/html,evil',
    '../../../outside.xhtml',
    '%2e%2e/%2e%2e/outside.xhtml',
    '%2Fetc/passwd',
    'text%5cone.xhtml',
  ]) {
    test(
      'rejects non-local or escaping manifest $malicious before publication',
      () async {
        final library = MemoryLibrary();
        final files = fixture();
        replace(files, 'EPUB/book.opf', 'text/one.xhtml', malicious);
        await expectLater(
          EpubImporter.importBytes(library, name: 'book', bytes: zip(files)),
          throwsA(failure(EpubImportFailure.invalidPackage)),
        );
        expect(library.records, isEmpty);
        expect(library.texts, isEmpty);
      },
    );
  }

  test(
    'encrypted resources fail explicitly and do not publish a book',
    () async {
      final files = fixture()
        ..['META-INF/encryption.xml'] = utf8.encode(
          '<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#"/></encryption>',
        );
      final library = MemoryLibrary();
      await expectLater(
        EpubImporter.importBytes(library, name: 'book', bytes: zip(files)),
        throwsA(failure(EpubImportFailure.encrypted)),
      );
      expect(library.records, isEmpty);
    },
  );

  for (final scenario in [
    'missing-container',
    'bad-container',
    'missing-spine',
    'missing-content',
    'duplicate-spine',
    'entity',
    'empty',
    'non-text',
  ]) {
    test('rejects $scenario without a partially imported book', () async {
      final library = MemoryLibrary();
      final files = fixture();
      switch (scenario) {
        case 'missing-container':
          files.remove('META-INF/container.xml');
        case 'bad-container':
          files['META-INF/container.xml'] = utf8.encode('<container>');
        case 'missing-spine':
          replace(files, 'EPUB/book.opf', '<opf:spine>', '<opf:notSpine>');
          replace(files, 'EPUB/book.opf', '</opf:spine>', '</opf:notSpine>');
        case 'missing-content':
          files.remove('EPUB/text/two.xhtml');
        case 'duplicate-spine':
          replace(files, 'EPUB/book.opf', 'idref="two"', 'idref="one"');
        case 'entity':
          files['META-INF/container.xml'] = utf8.encode(
            '<!DOCTYPE container [<!ENTITY x SYSTEM "file:///etc/passwd">]><container>&x;</container>',
          );
        case 'empty':
          files['EPUB/text/one.xhtml'] = utf8.encode(
            '<html><body><script>bad()</script></body></html>',
          );
          files['EPUB/text/two.xhtml'] = utf8.encode(
            '<html><body><img src="cover.png"/></body></html>',
          );
        case 'non-text':
          replace(
            files,
            'EPUB/book.opf',
            'id="one" href="text/one.xhtml" media-type="application/xhtml+xml"',
            'id="one" href="text/one.xhtml" media-type="image/svg+xml"',
          );
      }
      await expectLater(
        EpubImporter.importBytes(library, name: 'book', bytes: zip(files)),
        throwsA(isA<EpubImportException>()),
      );
      expect(library.records, isEmpty);
      expect(library.texts, isEmpty);
    });
  }

  test('UTF-16 XHTML preserves surrogate pairs', () async {
    final files = fixture();
    final text = '<html><body><h1>Unicode</h1><p>正文😀尾部</p></body></html>';
    final data = ByteData(2 + text.length * 2)
      ..setUint16(0, 0xfeff, Endian.little);
    for (var i = 0; i < text.length; i++) {
      data.setUint16(2 + i * 2, text.codeUnitAt(i), Endian.little);
    }
    files['EPUB/text/one.xhtml'] = data.buffer.asUint8List();
    final library = MemoryLibrary();
    final book = await EpubImporter.importBytes(
      library,
      name: 'unicode',
      bytes: zip(files),
    );
    final chapter = (await library.chapters(book.url)).first;
    expect(await library.content(book.url, chapter.url), 'Unicode\n\n正文😀尾部');
  });

  test('absolute URI cannot alias an in-archive resource', () async {
    final files = fixture();
    replace(files, 'EPUB/book.opf', 'text/one.xhtml', '%2Ftext/one.xhtml');
    final library = MemoryLibrary();
    await expectLater(
      EpubImporter.importBytes(library, name: 'unsafe', bytes: zip(files)),
      throwsA(failure(EpubImportFailure.invalidPackage)),
    );
    expect(library.records, isEmpty);
  });

  test('corrupt ZIP and storage failure never create a shelf book', () async {
    final library = MemoryLibrary();
    await expectLater(
      EpubImporter.importBytes(
        library,
        name: 'book',
        bytes: Uint8List.fromList([1, 2, 3]),
      ),
      throwsA(failure(EpubImportFailure.invalidArchive)),
    );
    library.failContentWrite = true;
    await expectLater(
      EpubImporter.importBytes(library, name: 'book', bytes: zip(fixture())),
      throwsStateError,
    );
    expect(library.records, isEmpty);
  });
}
