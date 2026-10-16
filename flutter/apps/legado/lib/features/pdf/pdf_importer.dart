import 'dart:typed_data';

import 'package:app_core/app_core.dart';
import 'package:crypto/crypto.dart';
import 'package:pdfrx/pdfrx.dart';

enum PdfFailure { tooLarge, invalid, encrypted, missing, unsupportedStorage }

class PdfImportException implements Exception {
  const PdfImportException(this.failure);
  final PdfFailure failure;
}

/// PDF metadata contains an archive key, never a device-specific filesystem path.
class PdfBookDocument {
  const PdfBookDocument(this.assetKey, this.pageCount);
  final String assetKey;
  final int pageCount;

  static PdfBookDocument? fromBook(LegacyBookRecord book) {
    final metadata = book.toJson()['flutterDocument'];
    if (metadata is! Map || metadata['format'] != 'pdf') return null;
    final key = metadata['assetKey'];
    final pages = metadata['pageCount'];
    if (key is! String ||
        !RegExp(r'^flutter/local-books/[a-f0-9]{64}/document\.pdf$')
            .hasMatch(key) ||
        pages is! int ||
        pages < 1 ||
        pages > PdfImporter.maximumPages) {
      return null;
    }
    return PdfBookDocument(key, pages);
  }
}

class PdfImporter {
  static const maximumBytes = 32 * 1024 * 1024;
  static const maximumPages = 20000;

  static Future<LegacyBookRecord> importBytes(
    LibraryRepository repository, {
    required String name,
    required Uint8List bytes,
  }) async {
    if (repository is! LocalBookRepository) {
      throw const PdfImportException(PdfFailure.unsupportedStorage);
    }
    if (bytes.length > maximumBytes) {
      throw const PdfImportException(PdfFailure.tooLarge);
    }
    if (bytes.length < 8 ||
        !String.fromCharCodes(bytes.take(8)).startsWith('%PDF-')) {
      throw const PdfImportException(PdfFailure.invalid);
    }
    // Take ownership before asynchronous native parsing; callers may reuse buffers.
    final data = Uint8List.fromList(bytes);
    final digest = sha256.convert(data).toString();
    final url = 'local:pdf:$digest';
    final existing = await repository.book(url);
    if (existing != null) return existing;
    await pdfrxFlutterInitialize();
    PdfDocument? document;
    try {
      document = await PdfDocument.openData(
        data,
        sourceName: url,
        passwordProvider: () async => null,
      );
      // Passwords are intentionally never retained in backups or preferences.
      if (document.isEncrypted) {
        throw const PdfImportException(PdfFailure.encrypted);
      }
      final count = document.pages.length;
      if (count < 1 || count > maximumPages) {
        throw const PdfImportException(PdfFailure.invalid);
      }
      final key = 'flutter/local-books/$digest/document.pdf';
      final title = name
          .replaceAll('\\', '/')
          .split('/')
          .last
          .replaceFirst(RegExp(r'\.pdf$', caseSensitive: false), '')
          .trim();
      final book = LegacyBookRecord({
        'bookUrl': url,
        'name': title.isEmpty ? 'PDF' : title,
        'author': '',
        'origin': 'loc_book',
        'originName': 'PDF',
        'type': 256,
        'durChapterIndex': 0,
        'durChapterPos': 0,
        'totalChapterNum': count,
        'flutterDocument': {
          'format': 'pdf',
          'assetKey': key,
          'pageCount': count,
        },
      });
      await (repository as LocalBookRepository).importLocalBook(
        book,
        const [],
        const {},
        assetsByKey: {key: data},
      );
      return book;
    } on PdfPasswordException {
      throw const PdfImportException(PdfFailure.encrypted);
    } on PdfException {
      throw const PdfImportException(PdfFailure.invalid);
    } finally {
      await document?.dispose();
    }
  }
}
