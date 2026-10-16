import 'package:app_core/app_core.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import '../../app/file_exchange.dart';
import '../../app/providers.dart';
import '../../l10n/generated/app_localizations.dart';
import '../reader/local_text_importer.dart';
import '../reader/epub_importer.dart';
import 'bookshelf_state.dart';
import 'bookshelf_groups.dart';

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
    final supportsGroups =
        ref.watch(libraryProvider) is BookshelfLibraryRepository;
    final shelf =
        ref.watch(bookshelfStateProvider).value ?? const BookshelfViewState();
    return Scaffold(
      appBar: AppBar(
        title: Text(strings.bookshelfTitle),
        actions: [
          PopupMenuButton<String>(
            tooltip: strings.shelfOptions,
            onSelected: (action) async {
              if (action == 'groups') {
                await showModalBottomSheet<void>(
                  context: context,
                  isScrollControlled: true,
                  builder: (_) => const BookshelfGroupManager(),
                );
              } else if (action == 'search') {
                context.go('/search');
              } else if (action == 'sources') {
                context.push('/sources');
              } else if (action == 'epub') {
                try {
                  await ref.read(importingTextProvider.notifier).importEpub();
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
                          EpubImportFailure.empty => strings.readerEpubEmpty,
                        }
                      : error is FileExchangeException &&
                            error.code == 'epubTooLarge'
                      ? strings.epubTooLarge
                      : strings.operationFailed;
                  ScaffoldMessenger.of(context)
                      .showSnackBar(SnackBar(content: Text(message)));
                }
              }
            },
            itemBuilder: (_) => [
              if (supportsGroups)
                PopupMenuItem(
                  value: 'groups',
                  child: Text(strings.shelfManageGroups),
                ),
              PopupMenuItem(
                value: 'epub',
                enabled: !busy,
                child: Text(strings.readerEpubImport),
              ),
              PopupMenuItem(value: 'search', child: Text(strings.searchTitle)),
              PopupMenuItem(
                value: 'sources',
                child: Text(strings.sourcesTitle),
              ),
            ],
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
      body: Column(
        children: [
          if (supportsGroups) const BookshelfFilters(),
          Expanded(
            child: books.when(
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (_, _) => Center(
                child: FilledButton(
                  onPressed: () => ref.invalidate(bookshelfProvider),
                  child: Text(strings.retry),
                ),
              ),
              data: (allItems) {
                final items = selectBookshelfBooks(
                  allItems,
                  shelf.groups,
                  groupId: shelf.groupId,
                  sort: shelf.sort,
                );
                return items.isEmpty
                    ? Center(
                        child: Padding(
                          padding: const EdgeInsets.all(24),
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Text(
                                allItems.isEmpty
                                    ? strings.emptyShelf
                                    : strings.shelfEmptyGroup,
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
                              title: Text(
                                book.name,
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                              ),
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
                              trailing: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  if (supportsGroups)
                                    PopupMenuButton<String>(
                                      tooltip: strings.shelfBookActions,
                                      onSelected: (action) async {
                                        if (action == 'groups') {
                                          await assignBookGroups(
                                            context,
                                            ref,
                                            book,
                                          );
                                          return;
                                        }
                                        final urls = items
                                            .map((book) => book.url)
                                            .toList();
                                        final other = action == 'up'
                                            ? index - 1
                                            : index + 1;
                                        final moving = urls.removeAt(index);
                                        urls.insert(other, moving);
                                        if (context.mounted) {
                                          await shelfAction(
                                            context,
                                            () => ref
                                                .read(
                                                  bookshelfStateProvider
                                                      .notifier,
                                                )
                                                .reorder(urls),
                                          );
                                        }
                                      },
                                      itemBuilder: (_) => [
                                        PopupMenuItem(
                                          value: 'groups',
                                          child: Text(
                                            strings.shelfAssignGroups,
                                          ),
                                        ),
                                        if (shelf.sort ==
                                            BookshelfSort.manual) ...[
                                          PopupMenuItem(
                                            value: 'up',
                                            enabled: index > 0,
                                            child: Text(strings.shelfMoveUp),
                                          ),
                                          PopupMenuItem(
                                            value: 'down',
                                            enabled: index + 1 < items.length,
                                            child: Text(strings.shelfMoveDown),
                                          ),
                                        ],
                                      ],
                                    ),
                                  IconButton(
                                    tooltip: strings.delete,
                                    icon: const Icon(Icons.delete_outline),
                                    onPressed: () async {
                                      final confirmed = await showDialog<bool>(
                                        context: context,
                                        builder: (dialogContext) => AlertDialog(
                                          title: Text(strings.deleteBookTitle),
                                          content: Text(
                                            strings.deleteBookMessage,
                                          ),
                                          actions: [
                                            TextButton(
                                              onPressed: () => Navigator.pop(
                                                dialogContext,
                                                false,
                                              ),
                                              child: Text(strings.cancel),
                                            ),
                                            FilledButton(
                                              onPressed: () => Navigator.pop(
                                                dialogContext,
                                                true,
                                              ),
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
                                          ScaffoldMessenger.of(context)
                                              .showSnackBar(
                                                SnackBar(
                                                  content: Text(
                                                    strings.operationFailed,
                                                  ),
                                                ),
                                              );
                                        }
                                      }
                                    },
                                  ),
                                ],
                              ),
                            );
                          },
                        ),
                      );
              },
            ),
          ),
        ],
      ),
    );
  }
}
