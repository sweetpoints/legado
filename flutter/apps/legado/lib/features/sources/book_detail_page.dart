import 'package:app_core/app_core.dart';
import 'package:material_ui/material_ui.dart';

import '../../l10n/generated/app_localizations.dart';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../services/book_source_service.dart';
import 'source_providers.dart';

class BookDetailPage extends ConsumerStatefulWidget {
  const BookDetailPage({
    super.key,
    required this.repository,
    required this.book,
  });
  final LibraryRepository repository;
  final NetworkBook book;
  @override
  ConsumerState<BookDetailPage> createState() => _BookDetailPageState();
}

class _BookDetailPageState extends ConsumerState<BookDetailPage> {
  bool _busy = false;
  Future<void> _act(Future<void> Function() action) async {
    setState(() => _busy = true);
    try {
      await action();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(e.toString())));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final query = (repository: widget.repository, book: widget.book);
    final state = ref.watch(bookDetailProvider(query));
    final service = ref.watch(bookSourceServiceProvider(widget.repository));
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.book.name),
        actions: [
          IconButton(
            tooltip: AppLocalizations.of(context).sourcesRefresh,
            icon: const Icon(Icons.refresh),
            onPressed: _busy
                ? null
                : () => ref.invalidate(bookDetailProvider(query)),
          ),
        ],
      ),
      body: state.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: SelectableText(
            AppLocalizations.of(context).sourcesDetailError(e.toString()),
          ),
        ),
        data: (preview) => ListView(
          children: [
            Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    preview.book.name,
                    style: Theme.of(context).textTheme.headlineSmall,
                  ),
                  Text(preview.book.author),
                  const SizedBox(height: 12),
                  Text(preview.book.metadata['intro']?.toString() ?? ''),
                  const SizedBox(height: 16),
                  FilledButton.icon(
                    onPressed: _busy
                        ? null
                        : () => _act(() async {
                            await service.addToShelf(preview);
                            if (context.mounted) {
                              ScaffoldMessenger.of(context).showSnackBar(
                                SnackBar(
                                  content: Text(
                                    AppLocalizations.of(context).sourcesAdded,
                                  ),
                                ),
                              );
                            }
                          }),
                    icon: const Icon(Icons.library_add),
                    label: Text(AppLocalizations.of(context).sourcesAdd),
                  ),
                  const SizedBox(height: 16),
                  Text(
                    AppLocalizations.of(context)
                        .sourcesChapterCount(preview.chapters.length),
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  if (preview.chapters.isEmpty)
                    Text(AppLocalizations.of(context).sourcesEmptyToc),
                  if (_busy) const LinearProgressIndicator(),
                ],
              ),
            ),
            for (final chapter in preview.chapters)
              ListTile(
                title: Text(chapter.title),
                enabled: !_busy && !chapter.isVolume,
                onTap: () => _act(() async {
                  // Reader consumes cache and persisted progress; cache before navigation.
                  await service.fetchChapter(preview, chapter.index);
                  if (context.mounted) {
                    await context.push(
                      '/reader?bookUrl=${Uri.encodeComponent(preview.book.url)}&chapter=${chapter.index}',
                      extra: preview,
                    );
                  }
                }),
              ),
          ],
        ),
      ),
    );
  }
}
