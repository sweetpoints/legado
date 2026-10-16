import 'dart:async';
import 'dart:typed_data';

import 'package:app_core/app_core.dart';
import 'package:crypto/crypto.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';
import 'package:material_ui/material_ui.dart';
import 'package:pdfrx/pdfrx.dart';

import '../../l10n/generated/app_localizations.dart';
import 'pdf_importer.dart';

part 'pdf_reader_page.g.dart';

typedef PdfReaderIdentity = ({LibraryRepository repository, String bookUrl});

Duration? _noPdfSessionRetry(int retryCount, Object error) => null;

@Riverpod(name: 'pdfReadingSessionProvider', retry: _noPdfSessionRetry)
Future<PdfReadingSession> pdfReadingSession(
  Ref ref,
  PdfReaderIdentity identity,
) async {
  var disposed = false;
  ref.onDispose(() => disposed = true);
  final session = await PdfReadingSession.open(identity);
  if (disposed) {
    await session.close();
    throw StateError('PDF reader was closed');
  }
  ref.onDispose(() => unawaited(session.close()));
  return session;
}

/// Real PDFium document with serialized, zero-based page persistence.
class PdfReadingSession {
  PdfReadingSession._(
    this.identity,
    this.book,
    this.document,
    this.initialPage,
  );
  final PdfReaderIdentity identity;
  final LegacyBookRecord book;
  final PdfDocument document;
  final int initialPage;
  Future<void> _saving = Future.value();
  bool _closed = false;

  static Future<PdfReadingSession> open(PdfReaderIdentity identity) async {
    final repository = identity.repository;
    final book = await repository.book(identity.bookUrl);
    final metadata = book == null ? null : PdfBookDocument.fromBook(book);
    if (repository is! LocalBookRepository ||
        metadata == null ||
        book == null) {
      throw const PdfImportException(PdfFailure.missing);
    }
    final bytes = await (repository as LocalBookRepository).localAsset(
      metadata.assetKey,
    );
    if (bytes == null ||
        bytes.isEmpty ||
        bytes.length > PdfImporter.maximumBytes) {
      throw const PdfImportException(PdfFailure.missing);
    }
    final digest = sha256.convert(bytes).toString();
    if (metadata.assetKey != 'flutter/local-books/$digest/document.pdf' ||
        book.url != 'local:pdf:$digest') {
      throw const PdfImportException(PdfFailure.invalid);
    }
    await pdfrxFlutterInitialize();
    final document = await PdfDocument.openData(
      Uint8List.fromList(bytes),
      sourceName: book.url,
      useProgressiveLoading: true,
      passwordProvider: () async => null,
    );
    if (document.isEncrypted || document.pages.length != metadata.pageCount) {
      await document.dispose();
      throw const PdfImportException(PdfFailure.invalid);
    }
    return PdfReadingSession._(
      identity,
      book,
      document,
      book.chapterIndex.clamp(0, document.pages.length - 1) + 1,
    );
  }

  Future<void> savePage(int page) {
    if (_closed || page < 1 || page > document.pages.length) {
      return Future.value();
    }
    final saving = _saving.then(
      (_) => identity.repository.updateProgress(book.url, page - 1, 0),
    );
    // A failed save must not prevent a later page from being persisted.
    _saving = saving.catchError((Object _) {});
    return saving;
  }

  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    await _saving;
    await document.dispose();
  }
}

class PdfReaderPage extends ConsumerStatefulWidget {
  const PdfReaderPage({
    super.key,
    required this.repository,
    required this.bookUrl,
  });
  final LibraryRepository repository;
  final String bookUrl;
  @override
  ConsumerState<PdfReaderPage> createState() => _PdfReaderPageState();
}

class _PdfReaderPageState extends ConsumerState<PdfReaderPage> {
  final _controller = PdfViewerController();
  int? _page;
  bool _saveError = false;

  @override
  void didUpdateWidget(covariant PdfReaderPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.repository != oldWidget.repository ||
        widget.bookUrl != oldWidget.bookUrl) {
      _page = null;
      _saveError = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    final strings = AppLocalizations.of(context);
    final identity = (repository: widget.repository, bookUrl: widget.bookUrl);
    final session = ref.watch(pdfReadingSessionProvider(identity));
    return Scaffold(
      appBar: AppBar(title: Text(session.value?.book.name ?? strings.pdfTitle)),
      body: session.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => Center(child: Text(strings.pdfOpenFailed)),
        data: (value) => Column(
          children: [
            if (_saveError)
              Text(
                strings.pdfSaveFailed,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            Expanded(
              child: PdfViewer(
                PdfDocumentRefDirect(value.document, autoDispose: false),
                controller: _controller,
                initialPageNumber: value.initialPage,
                params: PdfViewerParams(
                  // Embedded URI links never launch another application implicitly.
                  linkHandlerParams: PdfLinkHandlerParams(
                    onLinkTap: (link) {
                      if (link.dest != null) {
                        unawaited(_controller.goToDest(link.dest));
                      }
                    },
                  ),
                  onPageChanged: (page) {
                    if (page == null || !mounted) {
                      return;
                    }
                    setState(() => _page = page);
                    unawaited(
                      value
                          .savePage(page)
                          .then(
                            (_) {
                              if (mounted && _saveError) {
                                setState(() => _saveError = false);
                              }
                            },
                            onError: (Object _) {
                              if (mounted) setState(() => _saveError = true);
                            },
                          ),
                    );
                  },
                  errorBannerBuilder: (_, _, _, _) =>
                      Center(child: Text(strings.pdfOpenFailed)),
                ),
              ),
            ),
            SafeArea(
              top: false,
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  IconButton(
                    tooltip: strings.pdfPreviousPage,
                    icon: const Icon(Icons.chevron_left),
                    onPressed: (_page ?? value.initialPage) <= 1
                        ? null
                        : () => _controller.goToPage(
                            pageNumber: (_page ?? value.initialPage) - 1,
                          ),
                  ),
                  Text(
                    strings.pdfPagePosition(
                      _page ?? value.initialPage,
                      value.document.pages.length,
                    ),
                  ),
                  IconButton(
                    tooltip: strings.pdfNextPage,
                    icon: const Icon(Icons.chevron_right),
                    onPressed:
                        (_page ?? value.initialPage) >=
                            value.document.pages.length
                        ? null
                        : () => _controller.goToPage(
                            pageNumber: (_page ?? value.initialPage) + 1,
                          ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
