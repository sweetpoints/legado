import 'dart:convert';
import 'dart:io' as io show ZLibDecoder;
import 'dart:typed_data';

import 'package:app_core/app_core.dart';
import 'package:archive/archive.dart';
import 'package:unorm_dart/unorm_dart.dart' as unorm;

/// Resource limits checked before decompression and again on actual output.
final class BackupArchiveLimits {
  const BackupArchiveLimits({
    this.maxEntries = 10000,
    this.maxEntryBytes = 64 * 1024 * 1024,
    this.maxTotalBytes = 256 * 1024 * 1024,
    this.maxArchiveBytes = 128 * 1024 * 1024,
  });

  final int maxEntries;
  final int maxEntryBytes;
  final int maxTotalBytes;
  final int maxArchiveBytes;

  void _validate() {
    if (maxEntries < 1 ||
        maxEntryBytes < 1 ||
        maxTotalBytes < 1 ||
        maxArchiveBytes < 22) {
      throw ArgumentError('Backup archive limits must be positive');
    }
  }
}

/// A validated, detached staging result. It is not a database restore.
final class LegacyBackupStaging {
  LegacyBackupStaging._(this.document, Map<String, Uint8List> entries)
    : entries = Map.unmodifiable({
        for (final item in entries.entries)
          item.key: Uint8List.fromList(item.value).asUnmodifiableView(),
      });

  final LegacyBackupDocument document;

  /// All file bytes, including original BOMs and unknown/encrypted payloads.
  /// The map and its values are immutable. Directory records are not retained.
  final Map<String, Uint8List> entries;

  /// Files not interpreted as known legacy text, retained byte-for-byte.
  Map<String, Uint8List> get retainedEntries => Map.unmodifiable({
    for (final entry in entries.entries)
      if (!LegacyBackupArchive.textFileNames.contains(entry.key))
        entry.key: entry.value,
  });
}

/// ZIP codec for Android, iOS, macOS, Windows and Linux.
///
/// Validates the entire container before creating a staging result, without
/// filesystem access. Supports single-disk ZIP32, stored/deflated entries and
/// UTF-8 names (ASCII names without the UTF-8 flag are also supported).
/// Unsupported ZIP64, encrypted ZIPs and special filesystem entries fail closed.
abstract final class LegacyBackupArchive {
  static const textFileNames = {
    ...LegacyBackupDocument.listFiles,
    'config.xml',
    'videoConfig.xml',
    'cookies.json',
    'runtimeSourceCache.json',
    'legado-library.json',
  };

  static LegacyBackupStaging decode(
    Uint8List bytes, {
    BackupArchiveLimits limits = const BackupArchiveLimits(),
  }) {
    final files = decodeEntries(bytes, limits: limits);
    final text = <String, String>{};
    for (final name in textFileNames) {
      final value = files[name];
      if (value == null) continue;
      var decoded = utf8.decode(value, allowMalformed: false);
      if (decoded.startsWith('\uFEFF')) decoded = decoded.substring(1);
      // A present blank list is rejected instead of silently erasing data.
      if (LegacyBackupDocument.listFiles.contains(name) &&
          decoded.trim().isEmpty) {
        throw const FormatException('Known backup list is empty');
      }
      if (name == 'legado-library.json') _validateLibraryExtension(decoded);
      text[name] = decoded;
    }
    if (text.isEmpty) {
      throw const FormatException(
        'ZIP contains no recognized legacy backup files',
      );
    }
    return LegacyBackupStaging._(LegacyBackupDocument.parse(text), files);
  }

  /// Application-managed v1 extension exported by the library repository.
  /// Validate known fields without re-encoding or discarding future fields.
  static void _validateLibraryExtension(String text) {
    final value = jsonDecode(text);
    if (value is! Map<String, dynamic> ||
        value['version'] is! int ||
        value['version'] != 1) {
      throw const FormatException(
        'Invalid or unsupported library backup extension',
      );
    }
    bool nonEmptyString(Object? value) => value is String && value.isNotEmpty;
    if (value.containsKey('chapters')) {
      final chapters = value['chapters'];
      if (chapters is! List) {
        throw const FormatException('Invalid library backup chapters');
      }
      for (final item in chapters) {
        if (item is! Map<String, dynamic> || !nonEmptyString(item['bookUrl'])) {
          throw const FormatException(
            'Invalid library backup chapter identity',
          );
        }
        final chapter = item['chapter'];
        if (chapter is! Map<String, dynamic> ||
            !nonEmptyString(chapter['url']) ||
            chapter['title'] is! String ||
            chapter['index'] is! int ||
            (chapter['index'] as int) < 0 ||
            (chapter.containsKey('isVolume') && chapter['isVolume'] is! bool)) {
          throw const FormatException('Invalid library backup chapter');
        }
      }
    }
    if (value.containsKey('content')) {
      final content = value['content'];
      if (content is! List) {
        throw const FormatException('Invalid library backup content');
      }
      for (final item in content) {
        if (item is! Map<String, dynamic> ||
            !nonEmptyString(item['bookUrl']) ||
            !nonEmptyString(item['chapterUrl']) ||
            item['content'] is! String) {
          throw const FormatException(
            'Invalid library backup content identity',
          );
        }
      }
    }
    if (value.containsKey('settings')) {
      final settings = value['settings'];
      if (settings is! Map<String, dynamic> ||
          settings.values.any((value) => value is! String)) {
        throw const FormatException('Invalid library backup settings');
      }
    }
  }

  /// Decodes a generic ZIP into detached immutable regular-file payloads.
  ///
  /// Applies all structural, path, CRC and resource checks without interpreting
  /// legacy JSON or requiring a recognized backup file. Useful for EPUB staging;
  /// callers must separately validate container-specific formats and contents.
  static Map<String, Uint8List> decodeEntries(
    Uint8List bytes, {
    BackupArchiveLimits limits = const BackupArchiveLimits(),
  }) {
    limits._validate();
    if (bytes.length > limits.maxArchiveBytes) {
      throw const FormatException('Backup ZIP exceeds archive byte limit');
    }
    try {
      final records = _Directory(bytes, limits).read();
      final files = <String, Uint8List>{};
      for (final record in records) {
        final compressed = Uint8List.sublistView(
          bytes,
          record.dataStart,
          record.dataStart + record.compressedSize,
        );
        final sink = _BoundedSink(record.size);
        if (record.method == 0) {
          sink.add(compressed);
          sink.close();
        } else {
          // Native zlib streams output immediately into a bounded sink. Do not
          // use ZipDecoder.readBytes or a collecting conversion sink here.
          final decoder = io.ZLibDecoder(raw: true)
              .startChunkedConversion(sink);
          for (var offset = 0; offset < compressed.length; offset += 1024) {
            final end = offset + 1024 < compressed.length
                ? offset + 1024
                : compressed.length;
            decoder.add(Uint8List.sublistView(compressed, offset, end));
          }
          decoder.close();
        }
        final result = sink.bytes;
        if (result.length != record.size || getCrc32(result) != record.crc) {
          throw const FormatException('Backup entry size or CRC mismatch');
        }
        if (!record.directory) files[record.name] = result;
      }
      return Map.unmodifiable({
        for (final entry in files.entries)
          entry.key: Uint8List.fromList(entry.value).asUnmodifiableView(),
      });
    } on FormatException {
      rethrow;
    } on Exception {
      throw const FormatException('Corrupt or unsupported backup ZIP');
    } on RangeError {
      throw const FormatException('Truncated backup ZIP');
    }
  }

  /// Re-encodes all staged file payloads without changing their contents.
  /// ZIP timestamps/compression metadata and empty directory records may change.
  static Uint8List encode(
    LegacyBackupStaging staging, {
    BackupArchiveLimits limits = const BackupArchiveLimits(),
  }) => _encodeEntries(staging.entries, limits);

  /// Exports repository text files plus opaque retained entries from an import.
  /// Callers may use `staging.retainedEntries` to preserve media and future files.
  /// Conflicting names fail; stale text cannot silently replace new text.
  static Uint8List encodeFiles(
    Map<String, String> files, {
    Map<String, Uint8List> retainedEntries = const {},
    BackupArchiveLimits limits = const BackupArchiveLimits(),
  }) {
    LegacyBackupDocument.parse(files);
    final entries = <String, Uint8List>{...retainedEntries};
    for (final entry in files.entries) {
      if (entries.containsKey(entry.key)) {
        throw const FormatException('Conflicting backup export entries');
      }
      entries[entry.key] = Uint8List.fromList(utf8.encode(entry.value));
    }
    return _encodeEntries(entries, limits);
  }

  static Uint8List _encodeEntries(
    Map<String, Uint8List> files,
    BackupArchiveLimits limits,
  ) {
    limits._validate();
    if (files.length > limits.maxEntries) {
      throw const FormatException('Backup entry count exceeds limit');
    }
    final archive = Archive();
    var size = 0;
    final paths = _Paths();
    for (final item in files.entries) {
      paths.add(item.key, false);
      size += item.value.length;
      if (item.value.length > limits.maxEntryBytes ||
          size > limits.maxTotalBytes) {
        throw const FormatException('Backup bytes exceed export limit');
      }
      archive.add(ArchiveFile.bytes(item.key, item.value));
    }
    final bytes = Uint8List.fromList(ZipEncoder().encode(archive));
    // Use exactly the same validation for exports and imports.
    decode(bytes, limits: limits);
    return bytes;
  }
}

final class _BoundedSink implements Sink<List<int>> {
  _BoundedSink(this.limit);
  final int limit;
  final BytesBuilder _output = BytesBuilder(copy: true);
  bool _closed = false;
  @override
  void add(List<int> data) {
    if (_closed || _output.length + data.length > limit) {
      throw const FormatException(
        'Decompressed backup entry exceeds declared size',
      );
    }
    _output.add(data);
  }

  @override
  void close() => _closed = true;
  Uint8List get bytes => _output.takeBytes();
}

final class _Record {
  const _Record(
    this.name,
    this.directory,
    this.method,
    this.size,
    this.compressedSize,
    this.crc,
    this.dataStart,
    this.localStart,
    this.localEnd,
  );
  final String name;
  final bool directory;
  final int method, size, compressedSize, crc, dataStart, localStart, localEnd;
}

final class _Directory {
  _Directory(this.bytes, this.limits) : data = ByteData.sublistView(bytes);
  final Uint8List bytes;
  final ByteData data;
  final BackupArchiveLimits limits;
  int u16(int offset) => data.getUint16(offset, Endian.little);
  int u32(int offset) => data.getUint32(offset, Endian.little);
  Never fail() => throw const FormatException(
    'Invalid or unsupported backup ZIP structure',
  );

  List<_Record> read() {
    if (bytes.length < 22) fail();
    var end = -1;
    final min = bytes.length > 65557 ? bytes.length - 65557 : 0;
    for (var i = bytes.length - 22; i >= min; i--) {
      if (u32(i) == 0x06054b50 && i + 22 + u16(i + 20) == bytes.length) {
        end = i;
        break;
      }
    }
    if (end < 0 || u16(end + 4) != 0 || u16(end + 6) != 0) fail();
    final count = u16(end + 10);
    final directorySize = u32(end + 12);
    final directoryStart = u32(end + 16);
    if (count == 0xffff ||
        count != u16(end + 8) ||
        count > limits.maxEntries ||
        directoryStart + directorySize != end) {
      fail();
    }
    var offset = directoryStart;
    var total = 0;
    final paths = _Paths();
    final records = <_Record>[];
    for (var index = 0; index < count; index++) {
      if (offset + 46 > end || u32(offset) != 0x02014b50) fail();
      final flags = u16(offset + 8);
      final method = u16(offset + 10);
      final crc = u32(offset + 16);
      final compressedSize = u32(offset + 20);
      final size = u32(offset + 24);
      final nameSize = u16(offset + 28);
      final extraSize = u16(offset + 30);
      final commentSize = u16(offset + 32);
      final mode = u32(offset + 38) >> 16;
      final local = u32(offset + 42);
      final next = offset + 46 + nameSize + extraSize + commentSize;
      if (next > end ||
          nameSize == 0 ||
          u16(offset + 34) != 0 ||
          (flags & ~0x080e) != 0 ||
          (method != 0 && method != 8) ||
          compressedSize == 0xffffffff ||
          size == 0xffffffff ||
          size > limits.maxEntryBytes ||
          (mode & 0xf000 != 0 &&
              mode & 0xf000 != 0x8000 &&
              mode & 0xf000 != 0x4000)) {
        fail();
      }
      final rawName = Uint8List.sublistView(
        bytes,
        offset + 46,
        offset + 46 + nameSize,
      );
      if (flags & 0x0800 == 0 && rawName.any((b) => b > 127)) fail();
      final name = utf8.decode(rawName, allowMalformed: false);
      final directory = name.endsWith('/');
      if ((directory && size != 0) ||
          (mode & 0xf000 == 0x4000 && !directory) ||
          (mode & 0xf000 == 0x8000 && directory)) {
        fail();
      }
      paths.add(name, directory);
      total += size;
      if (total > limits.maxTotalBytes) fail();
      if (local + 30 > directoryStart ||
          u32(local) != 0x04034b50 ||
          u16(local + 6) != flags ||
          u16(local + 8) != method ||
          u16(local + 26) != nameSize) {
        fail();
      }
      final start = local + 30 + nameSize + u16(local + 28);
      if (start + compressedSize > directoryStart) fail();
      for (var i = 0; i < nameSize; i++) {
        if (bytes[local + 30 + i] != rawName[i]) fail();
      }
      if (flags & 8 == 0 &&
          (u32(local + 14) != crc ||
              u32(local + 18) != compressedSize ||
              u32(local + 22) != size)) {
        fail();
      }
      if (method == 0 && size != compressedSize) fail();
      var localEnd = start + compressedSize;
      if (flags & 8 != 0) {
        if (localEnd + 12 > directoryStart) fail();
        if (u32(localEnd) == 0x08074b50) localEnd += 4;
        if (localEnd + 12 > directoryStart ||
            u32(localEnd) != crc ||
            u32(localEnd + 4) != compressedSize ||
            u32(localEnd + 8) != size) {
          fail();
        }
        localEnd += 12;
      }
      records.add(
        _Record(
          name,
          directory,
          method,
          size,
          compressedSize,
          crc,
          start,
          local,
          localEnd,
        ),
      );
      offset = next;
    }
    if (offset != end) fail();
    // Reject aliased or overlapping local headers/payloads.
    final sorted = [...records]
      ..sort((a, b) => a.localStart.compareTo(b.localStart));
    var expectedStart = 0;
    for (final record in sorted) {
      if (record.localStart != expectedStart) fail();
      expectedStart = record.localEnd;
    }
    if (expectedStart != directoryStart) fail();
    return records;
  }
}

final class _Paths {
  final Map<String, bool> _names = {};
  final Map<String, String> _spelling = {};
  final Set<String> _parents = {};
  void add(String name, bool directory) {
    final trimmed = directory ? name.substring(0, name.length - 1) : name;
    final parts = trimmed.split('/');
    if (trimmed.isEmpty ||
        utf8.encode(trimmed).length > 4096 ||
        parts.length > 64 ||
        trimmed.startsWith('/') ||
        trimmed.contains('\\') ||
        trimmed.contains(':') ||
        RegExp(r'[\x00-\x1f\x7f<>"|?*]').hasMatch(trimmed) ||
        parts.any(
          (part) =>
              part.isEmpty ||
              utf8.encode(part).length > 255 ||
              part == '.' ||
              part == '..' ||
              part.endsWith('.') ||
              part.endsWith(' ') ||
              RegExp(
                r'^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\.|$)',
                caseSensitive: false,
              ).hasMatch(part),
        )) {
      throw const FormatException('Unsafe backup entry path');
    }
    final key = unorm.nfc(trimmed).toLowerCase();
    if (_names.containsKey(key)) {
      throw const FormatException('Duplicate or case-colliding backup path');
    }
    for (var i = 1; i <= parts.length; i++) {
      final prefix = parts.take(i).join('/');
      final lower = unorm.nfc(prefix).toLowerCase();
      if (_spelling[lower] != null && _spelling[lower] != prefix) {
        throw const FormatException('Case-colliding backup directory');
      }
      _spelling[lower] = prefix;
      if (i < parts.length) _parents.add(lower);
      if (i < parts.length && _names[lower] == false) {
        throw const FormatException('File used as backup directory');
      }
    }
    if (!directory && _parents.contains(key)) {
      throw const FormatException('Backup file replaces directory');
    }
    _names[key] = directory;
  }
}
