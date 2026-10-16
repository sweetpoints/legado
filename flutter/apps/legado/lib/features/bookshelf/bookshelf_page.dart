import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import '../../app/file_exchange.dart';
import '../../app/providers.dart';
import '../../l10n/generated/app_localizations.dart';
import '../reader/local_text_importer.dart';
import '../reader/epub_importer.dart';

final importingTextProvider = NotifierProvider<ImportingTextController, bool>(
  ImportingTextController.new,
);

class ImportingTextController extends Notifier<bool> {
  @override
  bool build() => false;
  Future<void> importEpub() async {
    if (state) return;
    state = true;
    try {
      final picked = await ref.read(fileExchangeProvider).pickEpub();
      if (picked != null) {
        await EpubImporter.importBytes(
          ref.read(libraryProvider),
          name: picked.name,
          bytes: picked.bytes,
        );
        ref.invalidate(bookshelfProvider);
      }
    } finally {
      if (ref.mounted) state = false;
    }
  }

  Future<void> importText() async {
    if (state) return;
    state = true;
    try {
      final picked = await ref.read(fileExchangeProvider).pickText();
      if (picked != null) {
        await LocalTextImporter.importText(
          ref.read(libraryProvider),
          name: picked.name,
          text: picked.text,
        );
        ref.invalidate(bookshelfProvider);
      }
    } finally {
      if (ref.mounted) state = false;
    }
  }
}

class BookshelfPage extends ConsumerWidget {
  const BookshelfPage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = AppLocalizations.of(context);
    final books = ref.watch(bookshelfProvider);
    final busy = ref.watch(importingTextProvider);
    return Scaffold(
      appBar: AppBar(
        title: Text(strings.bookshelfTitle),
        actions: [
          IconButton(
            tooltip: strings.readerEpubImport,
            icon: const Icon(Icons.file_open),
            onPressed: busy
                ? null
                : () async {
                    try {
                      await ref
                          .read(importingTextProvider.notifier)
                          .importEpub();
                    } catch (error) {
                      if (!context.mounted) return;
                      final message = error is EpubImportException
                          ? switch (error.failure) {
                              EpubImportFailure.invalidArchive =>
                                strings.readerEpubInvalidArchive,
                              EpubImportFailure.invalidPackage =>
                                strings.readerEpubInvalidPackage,
                              EpubImportFailure.encrypted =>
                                strings.readerEpubEncrypted,
                              EpubImportFailure.unsupported =>
                                strings.readerEpubUnsupported,
                              EpubImportFailure.empty =>
                                strings.readerEpubEmpty,
                            }
                          : error is FileExchangeException &&
                                error.code == 'epubTooLarge'
                          ? strings.epubTooLarge
                          : strings.operationFailed;
                      ScaffoldMessenger.of(context)
                          .showSnackBar(SnackBar(content: Text(message)));
                    }
                  },
          ),
          IconButton(
            tooltip: strings.searchTitle,
            icon: const Icon(Icons.search),
            onPressed: () => context.go('/search'),
          ),
          IconButton(
            tooltip: strings.sourcesTitle,
            icon: const Icon(Icons.source),
            onPressed: () => context.push('/sources'),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: busy
            ? null
            : () async {
                try {
                  await ref.read(importingTextProvider.notifier).importText();
                } catch (error) {
                  if (!context.mounted) return;
                  final code = error is FileExchangeException ? error.code : '';
                  final message = switch (code) {
                    'unsupportedText' => strings.unsupportedText,
                    'textTooLarge' => strings.textTooLarge,
                    _ => strings.operationFailed,
                  };
                  ScaffoldMessenger.of(context)
                      .showSnackBar(SnackBar(content: Text(message)));
                }
              },
        icon: busy
            ? const SizedBox.square(
                dimension: 20,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : const Icon(Icons.add),
        label: Text(strings.importText),
      ),
      body: books.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => Center(
          child: FilledButton(
            onPressed: () => ref.invalidate(bookshelfProvider),
            child: Text(strings.retry),
          ),
        ),
        data: (items) => items.isEmpty
            ? Center(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        strings.emptyShelf,
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      const SizedBox(height: 8),
                      Text(strings.emptyShelfHint),
                    ],
                  ),
                ),
              )
            : RefreshIndicator(
                onRefresh: () async {
                  ref.invalidate(bookshelfProvider);
                  await ref.read(bookshelfProvider.future);
                },
                child: ListView.builder(
                  padding: const EdgeInsets.only(bottom: 100),
                  itemCount: items.length,
                  itemBuilder: (context, index) {
                    final book = items[index];
                    return ListTile(
                      leading: const Icon(Icons.menu_book),
                      title: Text(book.name),
                      subtitle: Text(
                        '${book.author}\n${strings.chapterProgress(book.chapterIndex + 1)}',
                      ),
                      isThreeLine: true,
                      onTap: () => context.push(
                        Uri(
                          path: '/reader',
                          queryParameters: {'bookUrl': book.url},
                        ).toString(),
                      ),
                      trailing: IconButton(
                        tooltip: strings.delete,
                        icon: const Icon(Icons.delete_outline),
                        onPressed: () async {
                          final confirmed = await showDialog<bool>(
                            context: context,
                            builder: (dialogContext) => AlertDialog(
                              title: Text(strings.deleteBookTitle),
                              content: Text(strings.deleteBookMessage),
                              actions: [
                                TextButton(
                                  onPressed: () =>
                                      Navigator.pop(dialogContext, false),
                                  child: Text(strings.cancel),
                                ),
                                FilledButton(
                                  onPressed: () =>
                                      Navigator.pop(dialogContext, true),
                                  child: Text(strings.delete),
                                ),
                              ],
                            ),
                          );
                          if (confirmed != true) return;
                          try {
                            await ref
                                .read(libraryProvider)
                                .removeBook(book.url);
                            ref.invalidate(bookshelfProvider);
                          } catch (_) {
                            if (context.mounted) {
                              ScaffoldMessenger.of(context).showSnackBar(
                                SnackBar(
                                  content: Text(strings.operationFailed),
                                ),
                              );
                            }
                          }
                        },
                      ),
                    );
                  },
                ),
              ),
      ),
    );
  }
}
