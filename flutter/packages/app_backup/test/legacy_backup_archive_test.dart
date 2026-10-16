import 'dart:convert';
import 'dart:typed_data';

import 'package:app_backup/app_backup.dart';
import 'package:archive/archive.dart';
import 'package:test/test.dart';

Uint8List zip(Map<String, List<int>> files, {bool stored = false}) {
  final archive = Archive();
  for (final entry in files.entries) {
    archive.add(
      stored
          ? ArchiveFile.noCompress(entry.key, entry.value.length, entry.value)
          : ArchiveFile.bytes(entry.key, entry.value),
    );
  }
  return Uint8List.fromList(ZipEncoder().encode(archive));
}

Uint8List textZip(Map<String, String> files) =>
    zip(files.map((key, value) => MapEntry(key, utf8.encode(value))));

List<int> centralOffsets(Uint8List bytes) {
  final data = ByteData.sublistView(bytes);
  final end = bytes.length - 22;
  var offset = data.getUint32(end + 16, Endian.little);
  final result = <int>[];
  while (offset < end) {
    result.add(offset);
    offset +=
        46 +
        data.getUint16(offset + 28, Endian.little) +
        data.getUint16(offset + 30, Endian.little) +
        data.getUint16(offset + 32, Endian.little);
  }
  return result;
}

void main() {
  const shelf =
      '[{"bookUrl":"book:1","name":"Test","durChapterPos":8,"future":{"v":1}}]';

  test('BOM text parsed and original bytes, unknown media and opaque servers retained', () {
    final original = {
      'bookshelf.json': utf8.encode('\uFEFF$shelf'),
      'bookSource.json': utf8.encode('[]'),
      'covers/封面.png': [0, 255, 137, 13, 10],
      'servers.json': [0xff, 0x80, 0, 1],
      'future/new.bin': [9, 8, 7],
      'future/data.json': utf8.encode('{future-version-payload}'),
    };
    final staged = LegacyBackupArchive.decode(zip(original));
    expect(staged.document.books.single.characterOffset, 8);
    expect(staged.document.books.single.toJson()['future'], {'v': 1});
    expect(staged.document.files['bookshelf.json'], shelf);
    expect(
      staged.retainedEntries.keys,
      containsAll(['servers.json', 'future/new.bin']),
    );
    final roundtrip = LegacyBackupArchive.decode(
      LegacyBackupArchive.encode(staged),
    );
    expect(roundtrip.entries, original);
    expect(
      () => staged.entries['servers.json']![0] = 0,
      throwsUnsupportedError,
    );
    expect(() => staged.entries.clear(), throwsUnsupportedError);
  });

  test('export combines new repository text and retained imported bytes', () {
    final old = LegacyBackupArchive.decode(
      zip({
        'bookshelf.json': utf8.encode(shelf),
        'covers/a.png': [1, 2, 3],
      }),
    );
    final output = LegacyBackupArchive.encodeFiles({
      'bookshelf.json': '[]',
    }, retainedEntries: old.retainedEntries);
    final staged = LegacyBackupArchive.decode(output);
    expect(staged.document.books, isEmpty);
    expect(staged.entries['covers/a.png'], [1, 2, 3]);
    expect(
      () => LegacyBackupArchive.encodeFiles(
        {'bookshelf.json': '[]'},
        retainedEntries: {'bookshelf.json': Uint8List(0)},
      ),
      throwsFormatException,
    );
  });

  test('stored and deflated entries decode identically', () {
    final files = {
      'bookshelf.json': utf8.encode(shelf),
      'extra': List.filled(1024, 65),
    };
    expect(
      LegacyBackupArchive.decode(zip(files, stored: true)).entries,
      LegacyBackupArchive.decode(zip(files)).entries,
    );
  });

  for (final name in [
    '../outside',
    '/root/file',
    'C:/file',
    'a//b',
    'a/./b',
    'a/../b',
    'nul.txt',
    'COM1',
    'a/file.',
    'a/file ',
    'a\u0000b',
    'a:b',
    'a?b',
    'a<b',
  ]) {
    test('reject unsafe cross-platform path $name', () {
      expect(
        () => LegacyBackupArchive.decode(
          textZip({'bookshelf.json': '[]', name: 'bad'}),
        ),
        throwsFormatException,
      );
    });
  }

  test('raw backslash path rejected even if encoders normally sanitize it', () {
    final bytes = textZip({'bookshelf.json': '[]', 'a/b': 'x'});
    final central = centralOffsets(bytes).last;
    final local = ByteData.sublistView(bytes)
        .getUint32(central + 42, Endian.little);
    bytes[central + 47] = 92;
    bytes[local + 31] = 92;
    expect(() => LegacyBackupArchive.decode(bytes), throwsFormatException);
  });

  test('canonical Unicode path aliases rejected on macOS as well', () {
    expect(
      () => LegacyBackupArchive.decode(
        textZip({
          'bookshelf.json': '[]',
          'covers/café.png': 'x',
          'covers/cafe\u0301.png': 'y',
        }),
      ),
      throwsFormatException,
    );
  });

  test('case collisions, implicit directory aliases and file/directory conflicts rejected', () {
    for (final files in [
      {'bookshelf.json': '[]', 'BOOKSHELF.JSON': '[]'},
      {'bookshelf.json': '[]', 'Covers/a': 'x', 'covers/b': 'x'},
      {'bookshelf.json': '[]', 'covers': 'x', 'covers/a': 'x'},
      {'bookshelf.json': '[]', 'covers/a': 'x', 'covers': 'x'},
    ]) {
      expect(
        () => LegacyBackupArchive.decode(textZip(files)),
        throwsFormatException,
      );
    }
  });

  test(
    'duplicate central names rejected instead of decoder silently coalescing',
    () {
      final bytes = textZip({'bookshelf.json': '[]', 'aa': 'x', 'bb': 'x'});
      final offsets = centralOffsets(bytes);
      final data = ByteData.sublistView(bytes);
      final last = offsets.last;
      final local = data.getUint32(last + 42, Endian.little);
      bytes.setRange(last + 46, last + 48, utf8.encode('aa'));
      bytes.setRange(local + 30, local + 32, utf8.encode('aa'));
      expect(() => LegacyBackupArchive.decode(bytes), throwsFormatException);
    },
  );

  test(
    'symlink and special unix modes rejected before interpreting payload',
    () {
      for (final mode in [0xa1ff, 0x11a4, 0x61a4]) {
        final bytes = textZip({'bookshelf.json': '[]'});
        final data = ByteData.sublistView(bytes);
        final central = centralOffsets(bytes).first;
        data.setUint32(central + 38, mode << 16, Endian.little);
        expect(() => LegacyBackupArchive.decode(bytes), throwsFormatException);
      }
    },
  );

  test('empty file lists, invalid UTF8, null lists and missing backup content rejected', () {
    for (final bytes in [
      textZip({'bookshelf.json': ''}),
      textZip({'bookshelf.json': '  '}),
      textZip({'bookshelf.json': 'null'}),
      textZip({'bookshelf.json': '[null]'}),
      zip({
        'bookshelf.json': [0xff],
      }),
      textZip({'future.json': '{}'}),
      Uint8List.fromList(ZipEncoder().encode(Archive())),
    ]) {
      expect(() => LegacyBackupArchive.decode(bytes), throwsFormatException);
    }
  });

  test('all known collections validated before staging is returned', () {
    expect(
      () => LegacyBackupArchive.decode(
        textZip({'bookshelf.json': shelf, 'bookSource.json': '[null]'}),
      ),
      throwsFormatException,
    );
  });

  test('explicit empty collections supported without inventing missing collections', () {
    final staged = LegacyBackupArchive.decode(
      textZip({'bookshelf.json': '[]'}),
    );
    expect(staged.document.books, isEmpty);
    expect(staged.document.files.containsKey('bookmark.json'), isFalse);
  });

  test('entry count, entry bytes, total bytes and input bytes limited', () {
    final bytes = textZip({'bookshelf.json': '[]', 'unknown': '12345'});
    for (final limits in [
      const BackupArchiveLimits(maxEntries: 1),
      const BackupArchiveLimits(maxEntryBytes: 4),
      const BackupArchiveLimits(maxTotalBytes: 6),
      const BackupArchiveLimits(maxArchiveBytes: 22),
    ]) {
      expect(
        () => LegacyBackupArchive.decode(bytes, limits: limits),
        throwsFormatException,
      );
    }
  });

  test(
    'lying uncompressed size cannot inflate past the actual output bound',
    () {
      final bytes = textZip({'bookshelf.json': '[]', 'bomb': 'a' * 100000});
      final data = ByteData.sublistView(bytes);
      final central = centralOffsets(bytes).last;
      final local = data.getUint32(central + 42, Endian.little);
      data.setUint32(central + 24, 1, Endian.little);
      data.setUint32(local + 22, 1, Endian.little);
      expect(() => LegacyBackupArchive.decode(bytes), throwsFormatException);
    },
  );

  test(
    'corrupt CRC, local header mismatch, encrypted flags and ZIP64 rejected',
    () {
      for (var variant = 0; variant < 5; variant++) {
        final bytes = textZip({'bookshelf.json': '[]'});
        final data = ByteData.sublistView(bytes);
        final central = centralOffsets(bytes).first;
        switch (variant) {
          case 0:
            data.setUint32(central + 16, 42, Endian.little);
            data.setUint32(14, 42, Endian.little);
          case 1:
            bytes[30] ^= 1;
          case 2:
            data.setUint16(central + 8, 1, Endian.little);
          case 3:
            data.setUint32(central + 24, 0xffffffff, Endian.little);
          case 4:
            bytes[31 + 'bookshelf.json'.length] ^= 0xff;
        }
        expect(
          () => LegacyBackupArchive.decode(bytes),
          throwsFormatException,
          reason: '$variant',
        );
      }
    },
  );

  test('truncated containers and unrelated bytes fail atomically', () {
    final bytes = textZip({'bookshelf.json': shelf, 'bookSource.json': '[]'});
    for (final end in [0, 1, 21, bytes.length ~/ 2, bytes.length - 1]) {
      expect(
        () => LegacyBackupArchive.decode(Uint8List.sublistView(bytes, 0, end)),
        throwsFormatException,
      );
    }
    expect(
      () => LegacyBackupArchive.decode(Uint8List(100)),
      throwsFormatException,
    );
  });

  test('exports enforce the same limits and path checks', () {
    expect(
      () => LegacyBackupArchive.encodeFiles({
        'bookshelf.json': '[]',
        '../evil': 'x',
      }),
      throwsFormatException,
    );
    expect(
      () => LegacyBackupArchive.encodeFiles({
        'bookshelf.json': shelf,
      }, limits: const BackupArchiveLimits(maxEntryBytes: 3)),
      throwsFormatException,
    );
  });
}
