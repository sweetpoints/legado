import 'package:app_core/app_core.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../l10n/generated/app_localizations.dart';
import '../../services/book_source_service.dart';
import 'source_providers.dart';

class SourceSearchPage extends ConsumerStatefulWidget {
  const SourceSearchPage({super.key, required this.repository});
  final LibraryRepository repository;
  @override
  ConsumerState<SourceSearchPage> createState() => _SourceSearchPageState();
}

class _SourceSearchPageState extends ConsumerState<SourceSearchPage> {
  final _input = TextEditingController();
  String? _sourceId;
  String _query = '';
  String _exploreUrl = '';
  int _page = 1;
  bool _browse = false;
  @override
  void dispose() {
    _input.dispose();
    super.dispose();
  }

  void _search() => setState(() {
    _query = _input.text.trim();
    _page = 1;
  });

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    final sources = ref.watch(sourceListProvider(widget.repository));
    final available =
        sources.asData?.value.where((s) => s['enabled'] != false).toList() ??
        [];
    final id = available.any((s) => BookSourceService.sourceId(s) == _sourceId)
        ? _sourceId!
        : (available.isEmpty
              ? ''
              : BookSourceService.sourceId(available.first));
    final query = (
      repository: widget.repository,
      sourceId: id,
      key: _query,
      page: _page,
    );
    final menu = _browse
        ? ref.watch(
            sourceExploreMenuProvider((
              repository: widget.repository,
              sourceId: id,
            )),
          )
        : const AsyncData<List<Map<String, Object?>>>([]);
    final categories = menu.asData?.value ?? [];
    final selectedUrl = categories.any((c) => c['url'] == _exploreUrl)
        ? _exploreUrl
        : (categories.firstOrNull?['url']?.toString() ?? '');
    final results = _browse
        ? ref.watch(
            sourceExploreProvider((
              repository: widget.repository,
              sourceId: id,
              url: selectedUrl,
              page: _page,
            )),
          )
        : ref.watch(sourceSearchProvider(query));
    return Scaffold(
      appBar: AppBar(title: Text(l10n.sourcesSearchTitle)),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              children: [
                if (available.isNotEmpty)
                  DropdownButtonFormField<String>(
                    initialValue: id,
                    key: ValueKey(id),
                    isExpanded: true,
                    decoration: InputDecoration(
                      labelText: l10n.sourcesSourceLabel,
                      border: const OutlineInputBorder(),
                    ),
                    items: available
                        .map(
                          (s) => DropdownMenuItem(
                            value: BookSourceService.sourceId(s),
                            child: Text(
                              s['bookSourceName']?.toString() ??
                                  s['name']?.toString() ??
                                  BookSourceService.sourceId(s),
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                        )
                        .toList(),
                    onChanged: (value) => setState(() {
                      _sourceId = value;
                      _query = '';
                      _page = 1;
                      _exploreUrl = '';
                    }),
                  ),
                const SizedBox(height: 12),
                SegmentedButton<bool>(
                  segments: [
                    ButtonSegment(
                      value: false,
                      label: Text(l10n.sourcesSearch),
                      icon: const Icon(Icons.search),
                    ),
                    ButtonSegment(
                      value: true,
                      label: Text(l10n.sourcesBrowse),
                      icon: const Icon(Icons.explore),
                    ),
                  ],
                  selected: {_browse},
                  onSelectionChanged: (selection) => setState(() {
                    _browse = selection.single;
                    _page = 1;
                  }),
                ),
                const SizedBox(height: 12),
                if (!_browse)
                  TextField(
                    controller: _input,
                    textInputAction: TextInputAction.search,
                    onSubmitted: (_) => _search(),
                    decoration: InputDecoration(
                      labelText: l10n.sourcesKeyword,
                      border: const OutlineInputBorder(),
                      suffixIcon: IconButton(
                        tooltip: l10n.sourcesSearch,
                        icon: const Icon(Icons.search),
                        onPressed: id.isEmpty
                            ? null
                            : () {
                                _search();
                                ref.invalidate(
                                  sourceSearchProvider((
                                    repository: widget.repository,
                                    sourceId: id,
                                    key: _input.text.trim(),
                                    page: 1,
                                  )),
                                );
                              },
                      ),
                    ),
                  ),
                if (_browse)
                  menu.when(
                    loading: () => const LinearProgressIndicator(),
                    error: (e, _) => SelectableText(e.toString()),
                    data: (items) => items.isEmpty
                        ? Text(l10n.sourcesNoCategories)
                        : DropdownButtonFormField<String>(
                            key: ValueKey('$id:$selectedUrl'),
                            initialValue: selectedUrl,
                            isExpanded: true,
                            decoration: InputDecoration(
                              labelText: l10n.sourcesCategory,
                              border: const OutlineInputBorder(),
                            ),
                            items: items
                                .map(
                                  (item) => DropdownMenuItem(
                                    value: item['url'] as String,
                                    child: Text(
                                      item['title']?.toString() ?? '',
                                      overflow: TextOverflow.ellipsis,
                                    ),
                                  ),
                                )
                                .toList(),
                            onChanged: (value) => setState(() {
                              _exploreUrl = value ?? '';
                              _page = 1;
                            }),
                          ),
                  ),
              ],
            ),
          ),
          if (sources.hasError)
            Padding(
              padding: const EdgeInsets.all(16),
              child: SelectableText(sources.error.toString()),
            ),
          Expanded(
            child: available.isEmpty
                ? Center(child: Text(l10n.sourcesNeedSource))
                : results.when(
                    loading: () =>
                        const Center(child: CircularProgressIndicator()),
                    error: (e, _) => SingleChildScrollView(
                      padding: const EdgeInsets.all(16),
                      child: SelectableText(e.toString()),
                    ),
                    data: (books) => books.isEmpty
                        ? Center(
                            child: Text(
                              !_browse && _query.isEmpty
                                  ? l10n.sourcesSearchPrompt
                                  : l10n.sourcesNoResults,
                            ),
                          )
                        : ListView.builder(
                            itemCount: books.length,
                            itemBuilder: (context, index) {
                              final book = books[index];
                              return ListTile(
                                title: Text(book.name),
                                subtitle: Text(book.author),
                                trailing: const Icon(Icons.chevron_right),
                                onTap: () => context.push('/book', extra: book),
                              );
                            },
                          ),
                  ),
          ),
          if ((_browse && selectedUrl.isNotEmpty) ||
              (!_browse && _query.isNotEmpty))
            SafeArea(
              top: false,
              child: Padding(
                padding: const EdgeInsets.all(8),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                  children: [
                    IconButton(
                      tooltip: l10n.sourcesPrevious,
                      onPressed: _page > 1 && !results.isLoading
                          ? () => setState(() => _page--)
                          : null,
                      icon: const Icon(Icons.chevron_left),
                    ),
                    Text(l10n.sourcesPage(_page)),
                    IconButton(
                      tooltip: l10n.sourcesNext,
                      onPressed:
                          !results.isLoading &&
                              (results.asData?.value.isNotEmpty ?? false)
                          ? () => setState(() => _page++)
                          : null,
                      icon: const Icon(Icons.chevron_right),
                    ),
                  ],
                ),
              ),
            ),
        ],
      ),
    );
  }
}
