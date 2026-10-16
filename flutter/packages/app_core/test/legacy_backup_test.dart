import 'dart:convert';

import 'package:app_core/app_core.dart';
import 'package:test/test.dart';

void main() {
  final book = <String, Object?>{
    'bookUrl': 'https://example.invalid/book',
    'name': '旧书架',
    'author': '作者',
    'durChapterIndex': 3,
    'durChapterPos': 27,
    'unknownFutureField': {
      'nested': [null, 1, '😀'],
    },
  };
  LegacyBackupDocument parse(Object? value) =>
      LegacyBackupDocument.parse({'bookshelf.json': jsonEncode(value)});
  test('preserves source text, unknown files and nested fields', () {
    final files = {
      'bookshelf.json': ' \n${jsonEncode([book])}\n',
      'future/file.json': 'not interpreted',
      'servers.json': 'encrypted payload',
    };
    final document = LegacyBackupDocument.parse(files);
    expect(document.files, files);
    expect(document.books.single.toJson(), book);
    expect(document.books.single.characterOffset, 27);
    files.clear();
    (document.books.single.toJson()['unknownFutureField'] as Map).clear();
    expect(document.books.single.toJson(), book);
    expect(document.files.length, 3);
  });
  test('absent and empty bookshelf remain distinguishable', () {
    expect(
      LegacyBackupDocument.parse({}).files.containsKey('bookshelf.json'),
      isFalse,
    );
    expect(parse([]).files.containsKey('bookshelf.json'), isTrue);
  });
  test('rejects null or malformed lists and entries', () {
    for (final value in [
      null,
      {},
      [book, null],
      [book, 'bad'],
    ]) {
      expect(() => parse(value), throwsFormatException);
    }
    expect(
      () => LegacyBackupDocument.parse({'bookSource.json': '[{},]'}),
      throwsFormatException,
    );
  });
  test('rejects duplicate identities', () {
    expect(() => parse([book, book]), throwsFormatException);
  });
  test('defaults absent positions and rejects corrupted positions', () {
    final minimal = {'bookUrl': 'local://book', 'name': '书'};
    expect(parse([minimal]).books.single.characterOffset, 0);
    for (final position in [-1, 2.5, '4', null]) {
      expect(
        () => parse([
          {...minimal, 'durChapterPos': position},
        ]),
        throwsFormatException,
      );
    }
  });
  test('retains opaque identities and UTF-16 offsets', () {
    final value = {
      ...book,
      'bookUrl': '本地图书/书 名.epub',
      'durChapterPos': 'a😀'.length,
    };
    expect(parse([value]).books.single.characterOffset, 3);
    expect(parse([value]).books.single.url, value['bookUrl']);
  });
  test('rejects unsafe archive entry names', () {
    for (final name in [
      '',
      '/file',
      '../file',
      'a/../b',
      'a\\b',
      'C:/file',
      'a//b',
    ]) {
      expect(
        () => LegacyBackupDocument.parse({name: '[]'}),
        throwsFormatException,
      );
    }
  });
}
