import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/foundation.dart' show compute;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'local_text_decoder.dart';

class FileExchangeException implements Exception {
  const FileExchangeException(this.code);
  final String code;
}

typedef ImportedTextFile = ({String name, String text});
typedef ImportedEpubFile = ({String name, Uint8List bytes});

abstract interface class FileExchange {
  Future<ImportedTextFile?> pickText();
  Future<ImportedEpubFile?> pickEpub();
  Future<Uint8List?> pickBackup();
  Future<bool> saveBackup(Uint8List bytes, String name);
}

final fileExchangeProvider = Provider<FileExchange>(
  (ref) => const NativeFileExchange(),
);

class NativeFileExchange implements FileExchange {
  const NativeFileExchange();

  Future<Uint8List> _readBounded(
    PlatformFile file,
    int maximum,
    String code,
  ) async {
    final length = file.lengthSync() ?? await file.length();
    if (length != null && length > maximum) throw FileExchangeException(code);
    final output = BytesBuilder(copy: false);
    await for (final chunk in file.readAsByteStream()) {
      if (output.length + chunk.length > maximum) {
        throw FileExchangeException(code);
      }
      output.add(chunk);
    }
    return output.takeBytes();
  }

  @override
  Future<ImportedTextFile?> pickText() async {
    final file = await FilePicker.pickFile(
      type: FileType.custom,
      allowedExtensions: ['txt'],
    );
    if (file == null) return null;
    try {
      final length = file.lengthSync() ?? await file.length();
      final bytes = await LocalTextDecoder.readBounded(
        file.readAsByteStream(),
        declaredLength: length,
      );
      final decoded = await compute(LocalTextDecoder.decode, bytes);
      return (
        name: file.name.replaceFirst(
          RegExp(r'\.txt$', caseSensitive: false),
          '',
        ),
        text: decoded.text,
      );
    } on LocalTextDecodeException catch (error) {
      throw FileExchangeException(error.code);
    }
  }

  @override
  Future<ImportedEpubFile?> pickEpub() async {
    final file = await FilePicker.pickFile(
      type: FileType.custom,
      allowedExtensions: ['epub'],
    );
    if (file == null) return null;
    return (
      name: file.name,
      bytes: await _readBounded(file, 128 * 1024 * 1024, 'epubTooLarge'),
    );
  }

  @override
  Future<Uint8List?> pickBackup() async {
    final file = await FilePicker.pickFile(
      type: FileType.custom,
      allowedExtensions: ['zip'],
    );
    if (file == null) return null;
    return _readBounded(file, 128 * 1024 * 1024, 'backupTooLarge');
  }

  @override
  Future<bool> saveBackup(Uint8List bytes, String name) async =>
      await FilePicker.saveFile(
        fileName: name,
        bytes: bytes,
        type: FileType.custom,
        allowedExtensions: ['zip'],
      ) !=
      null;
}
