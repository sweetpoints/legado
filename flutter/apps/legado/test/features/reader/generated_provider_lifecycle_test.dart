import 'dart:async';
import 'dart:typed_data';

import 'package:app_core/app_core.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/app/file_exchange.dart';
import 'package:legado_app/app/providers.dart';
import 'package:legado_app/features/backup/backup_page.dart';
import 'package:legado_app/features/bookshelf/bookshelf_page.dart';
import 'package:legado_app/features/bookshelf/bookshelf_state.dart';
import 'package:legado_app/features/pdf/pdf_reader_page.dart';
import 'package:legado_app/features/reader/reader_bookmarks.dart';
import 'package:legado_app/features/reader/reader_state.dart';

import 'reader_test.dart' show MemoryLibrary;

final class _PendingFiles implements FileExchange {
  final pending = Completer<ImportedTextFile?>();
  @override
  Future<ImportedTextFile?> pickText() => pending.future;
  @override
  Future<ImportedEpubFile?> pickEpub() async => null;
  @override
  Future<Uint8List?> pickBackup() async => null;
  @override
  Future<bool> saveBackup(Uint8List bytes, String name) async => false;
}

final class _CountingLibrary extends MemoryLibrary {
  int bookReads = 0;
  @override
  Future<LegacyBookRecord?> book(String url) async {
    bookReads++;
    return super.book(url);
  }
}

void main() {
  test('reader generated family isolates preview identity and auto-disposes route state', () async {
    final library = MemoryLibrary();
    final identity = ReaderIdentity(
      repository: library,
      bookUrl: 'book',
      initialChapterIndex: 1,
    );
    final otherIdentity = identity.copyWith(initialChapterIndex: 2);
    final container = ProviderContainer();
    addTearDown(container.dispose);
    final first = container.listen(readerStateProvider(identity), (_, _) {});
    final other = container.listen(
      readerStateProvider(otherIdentity),
      (_, _) {},
    );
    container
        .read(readerStateProvider(identity).notifier)
        .replace(
          const ReaderViewState(content: 'first route', characterOffset: 7),
        );
    expect(container.read(readerStateProvider(otherIdentity)).content, isNull);
    first.close();
    await container.pump();
    final reopened = container.listen(readerStateProvider(identity), (_, _) {});
    expect(reopened.read(), const ReaderViewState());
    reopened.close();
    other.close();
  });

  test('generated bookmark family retains per-instance override API', () async {
    final library = MemoryLibrary();
    final book = LegacyBookRecord({'bookUrl': 'book', 'name': 'Book'});
    final query = (repository: library as LibraryRepository, book: book);
    final container = ProviderContainer(
      overrides: [
        readerBookmarksProvider(query)
            .overrideWith((ref) async => <ReaderBookmark>[]),
      ],
    );
    addTearDown(container.dispose);
    expect(
      await container.read(readerBookmarksProvider(query).future),
      isEmpty,
    );
    expect(await library.setting(ReaderBookmarks.settingKey), isNull);
  });

  test('generated backup operation remains alive without listeners and serializes actions', () async {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    final controller = container.read(backupOperationProvider.notifier);
    final pending = Completer<void>();
    final running = controller.run(() => pending.future);
    await container.pump();
    expect(container.read(backupOperationProvider), true);
    var duplicateRan = false;
    await container.read(backupOperationProvider.notifier).run(() async {
      duplicateRan = true;
    });
    expect(duplicateRan, false);
    expect(
      identical(container.read(backupOperationProvider.notifier), controller),
      true,
    );
    pending.complete();
    await running;
    expect(container.read(backupOperationProvider), false);
  });

  test('generated importing controller keeps pending picker alive and ignores post-disposal state writes', () async {
    final files = _PendingFiles();
    final container = ProviderContainer(
      overrides: [fileExchangeProvider.overrideWithValue(files)],
    );
    final controller = container.read(importingTextProvider.notifier);
    final importing = controller.importText();
    await container.pump();
    expect(container.read(importingTextProvider), true);
    expect(
      identical(container.read(importingTextProvider.notifier), controller),
      true,
    );
    container.dispose();
    files.pending.complete(null);
    await importing;
  });

  test(
    'generated bookshelf controller preserves non-auto-dispose lifetime',
    () async {
      final library = MemoryLibrary();
      final container = ProviderContainer(
        overrides: [libraryProvider.overrideWithValue(library)],
      );
      addTearDown(container.dispose);
      final subscription = container.listen(bookshelfStateProvider, (_, _) {});
      await container.read(bookshelfStateProvider.future);
      final controller = container.read(bookshelfStateProvider.notifier);
      subscription.close();
      await container.pump();
      expect(
        identical(container.read(bookshelfStateProvider.notifier), controller),
        true,
      );
      expect(container.read(bookshelfStateProvider).requireValue.groupId, -1);
    },
  );

  test('generated PDF session preserves explicit no-retry and auto-disposes failed routes', () async {
    final library = _CountingLibrary();
    final identity = (
      repository: library as LibraryRepository,
      bookUrl: 'missing-pdf',
    );
    final container = ProviderContainer();
    addTearDown(container.dispose);
    final subscription = container.listen(
      pdfReadingSessionProvider(identity),
      (_, _) {},
    );
    await expectLater(
      container.read(pdfReadingSessionProvider(identity).future),
      throwsA(isA<Exception>()),
    );
    await Future<void>.delayed(const Duration(milliseconds: 350));
    expect(library.bookReads, 1);
    subscription.close();
    await container.pump();
    await expectLater(
      container.read(pdfReadingSessionProvider(identity).future),
      throwsA(isA<Exception>()),
    );
    expect(library.bookReads, 2);
  });
}
