import 'dart:typed_data';

import 'package:app_core/app_core.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import '../../l10n/generated/app_localizations.dart';
import 'pdf_importer.dart';

/// Dedicated picker reads a bounded stream; absolute device paths are discarded.
Future<({String name, Uint8List bytes})?> pickLocalPdf() async {
  final file = await FilePicker.pickFile(
    type: FileType.custom,
    allowedExtensions: ['pdf'],
  );
  if (file == null) return null;
  final size = file.lengthSync() ?? await file.length();
  if (size != null && size > PdfImporter.maximumBytes) {
    throw const PdfImportException(PdfFailure.tooLarge);
  }
  final output = BytesBuilder(copy: false);
  await for (final chunk in file.readAsByteStream()) {
    if (output.length + chunk.length > PdfImporter.maximumBytes) {
      throw const PdfImportException(PdfFailure.tooLarge);
    }
    output.add(chunk);
  }
  return (name: file.name, bytes: output.takeBytes());
}

class PdfImportPage extends ConsumerStatefulWidget {
  const PdfImportPage({
    super.key,
    required this.repository,
    required this.onImported,
  });
  final LibraryRepository repository;
  final void Function(LegacyBookRecord book) onImported;
  @override
  ConsumerState<PdfImportPage> createState() => _PdfImportPageState();
}

class _PdfImportPageState extends ConsumerState<PdfImportPage> {
  bool _busy = false;
  PdfFailure? _error;
  Future<void> _import() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final picked = await pickLocalPdf();
      if (picked == null || !mounted) return;
      final book = await PdfImporter.importBytes(
        widget.repository,
        name: picked.name,
        bytes: picked.bytes,
      );
      if (mounted) widget.onImported(book);
    } on PdfImportException catch (error) {
      if (mounted) setState(() => _error = error.failure);
    } catch (_) {
      if (mounted) setState(() => _error = PdfFailure.invalid);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final strings = AppLocalizations.of(context);
    final message = switch (_error) {
      PdfFailure.tooLarge => strings.pdfTooLarge,
      PdfFailure.encrypted => strings.pdfEncrypted,
      PdfFailure.unsupportedStorage => strings.pdfUnsupportedStorage,
      PdfFailure.invalid || PdfFailure.missing => strings.pdfOpenFailed,
      null => null,
    };
    return Scaffold(
      appBar: AppBar(title: Text(strings.pdfImportTitle)),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(strings.pdfImportDescription),
              const SizedBox(height: 16),
              if (message != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: 16),
                  child: Text(
                    message,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
                ),
              if (_busy)
                const CircularProgressIndicator()
              else
                FilledButton.icon(
                  onPressed: _import,
                  icon: const Icon(Icons.picture_as_pdf),
                  label: Text(strings.pdfChooseFile),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
