import 'dart:convert';
import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

class FileExchangeException implements Exception {
  const FileExchangeException(this.code);
  final String code;
}

typedef ImportedTextFile = ({String name, String text});

abstract interface class FileExchange {
  Future<ImportedTextFile?> pickText();
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
    final bytes = await _readBounded(file, 20 * 1024 * 1024, 'textTooLarge');
    try {
      final text = utf8.decode(bytes);
      return (
        name: file.name.replaceFirst(
          RegExp(r'\.txt$', caseSensitive: false),
          '',
        ),
        text: text.startsWith('\uFEFF') ? text.substring(1) : text,
      );
    } on FormatException {
      throw const FileExchangeException('unsupportedText');
    }
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
