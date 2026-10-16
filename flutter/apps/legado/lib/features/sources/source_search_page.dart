import 'package:app_core/app_core.dart';
import 'package:material_ui/material_ui.dart';

import '../../l10n/generated/app_localizations.dart';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

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
  @override
  void dispose() {
    _input.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final sources = ref.watch(sourceListProvider(widget.repository));
    final available =
        sources.asData?.value.where((s) => s['enabled'] != false).toList() ??
        [];
    final id = available.any((s) => BookSourceService.sourceId(s) == _sourceId)
        ? _sourceId!
        : (available.isEmpty
              ? ''
              : BookSourceService.sourceId(available.first));
    final query = (repository: widget.repository, sourceId: id, key: _query);
    final results = ref.watch(sourceSearchProvider(query));
    return Scaffold(
      appBar: AppBar(
        title: Text(AppLocalizations.of(context)!.sourcesSearchTitle),
      ),
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
                      labelText: AppLocalizations.of(context)!
                          .sourcesSourceLabel,
                      border: OutlineInputBorder(),
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
                    }),
                  ),
                const SizedBox(height: 12),
                TextField(
                  controller: _input,
                  textInputAction: TextInputAction.search,
                  onSubmitted: (_) =>
                      setState(() => _query = _input.text.trim()),
                  decoration: InputDecoration(
                    labelText: AppLocalizations.of(context)!.sourcesKeyword,
                    border: const OutlineInputBorder(),
                    suffixIcon: IconButton(
                      tooltip: AppLocalizations.of(context)!.sourcesSearch,
                      icon: const Icon(Icons.search),
                      onPressed: id.isEmpty
                          ? null
                          : () {
                              setState(() => _query = _input.text.trim());
                              ref.invalidate(sourceSearchProvider(query));
                            },
                    ),
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
                ? Center(
                    child: Text(
                      AppLocalizations.of(context)!.sourcesNeedSource,
                    ),
                  )
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
                              _query.isEmpty
                                  ? AppLocalizations.of(context)!
                                        .sourcesSearchPrompt
                                  : AppLocalizations.of(context)!
                                        .sourcesNoResults,
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
        ],
      ),
    );
  }
}
