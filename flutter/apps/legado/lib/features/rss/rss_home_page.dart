import 'dart:convert';

import 'package:app_core/app_core.dart';
import 'package:dio/dio.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:html/parser.dart' as html;
import 'package:go_router/go_router.dart';

import '../../l10n/generated/app_localizations.dart';
import 'rss_models.dart';
import 'rss_service.dart';
import 'rss_html_rules.dart';
export 'rss_models.dart';

final rssStoreProvider = Provider.family<RssStore, LibraryRepository>(
  (ref, repository) => RssStore(repository),
);
typedef RssSourceView = ({
  List<RssSource> sources,
  int restoredCount,
  int conflictCount,
});
final rssSourceViewProvider = FutureProvider.autoDispose
    .family<RssSourceView, LibraryRepository>((ref, repository) {
      final subscription = repository.changes.listen(
        (_) => ref.invalidateSelf(),
      );
      ref.onDispose(subscription.cancel);
      return ref.watch(rssStoreProvider(repository)).sourceView();
    });
final rssSourcesProvider = FutureProvider.autoDispose
    .family<List<RssSource>, LibraryRepository>(
      (ref, repository) async =>
          (await ref.watch(rssSourceViewProvider(repository).future)).sources,
    );
final rssArticlesProvider = FutureProvider.autoDispose
    .family<List<RssArticle>, (LibraryRepository, String)>((ref, args) {
      final subscription = args.$1.changes.listen((_) => ref.invalidateSelf());
      ref.onDispose(subscription.cancel);
      return ref.watch(rssStoreProvider(args.$1)).articles(args.$2);
    });
final rssClientProvider = Provider.autoDispose<RssFeedClient>((ref) {
  final dio = Dio(BaseOptions(connectTimeout: const Duration(seconds: 15)));
  ref.onDispose(() => dio.close(force: true));
  return RssFeedClient(dio);
});

class RssHomePage extends ConsumerWidget {
  const RssHomePage({super.key, required this.repository});
  final LibraryRepository repository;
  @override
  Widget build(BuildContext context, WidgetRef ref) => Scaffold(
    appBar: AppBar(
      title: Text(AppLocalizations.of(context).rssTitle),
      actions: [
        IconButton(
          tooltip: AppLocalizations.of(context).rssAddTooltip,
          onPressed: () => _import(context, ref),
          icon: const Icon(Icons.add),
        ),
      ],
    ),
    body: ref
        .watch(rssSourceViewProvider(repository))
        .when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, _) => _Failure(
            error: error,
            retry: () => ref.invalidate(rssSourceViewProvider(repository)),
          ),
          data: (view) => Column(
            children: [
              if (view.restoredCount > 0 || view.conflictCount > 0)
                Padding(
                  padding: const EdgeInsets.all(16),
                  child: Text(
                    AppLocalizations.of(
                      context,
                    ).rssRestoreSummary(view.restoredCount, view.conflictCount),
                  ),
                ),
              Expanded(
                child: view.sources.isEmpty
                    ? Center(child: Text(AppLocalizations.of(context).rssEmpty))
                    : ListView.builder(
                        itemCount: view.sources.length,
                        itemBuilder: (context, index) {
                          final source = view.sources[index];
                          return ListTile(
                            leading: Icon(
                              source.requiresRuleEngine
                                  ? Icons.warning_amber
                                  : Icons.rss_feed,
                            ),
                            title: Text(source.name),
                            subtitle: Text(
                              source.requiresRuleEngine
                                  ? AppLocalizations.of(context)
                                        .rssLegacySubtitle
                                  : source.url,
                            ),
                            trailing: IconButton(
                              tooltip: AppLocalizations.of(context).rssDelete,
                              icon: const Icon(Icons.delete_outline),
                              onPressed: () => _delete(context, ref, source),
                            ),
                            onTap: () =>
                                context.push('/rss/source', extra: source),
                          );
                        },
                      ),
              ),
            ],
          ),
        ),
  );

  Future<void> _delete(
    BuildContext context,
    WidgetRef ref,
    RssSource source,
  ) async {
    final accepted = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(AppLocalizations.of(context).rssDelete),
        content: Text(AppLocalizations.of(context).rssDeleteHelp),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(AppLocalizations.of(context).rssCancel),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(AppLocalizations.of(context).rssDelete),
          ),
        ],
      ),
    );
    if (accepted != true || !context.mounted) return;
    try {
      await ref.read(rssStoreProvider(repository)).removeSource(source.url);
      ref.invalidate(rssSourceViewProvider(repository));
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(_message(context, e))));
      }
    }
  }

  Future<void> _import(BuildContext context, WidgetRef ref) async {
    final controller = TextEditingController();
    String? error;
    bool busy = false;
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, update) => AlertDialog(
          title: Text(AppLocalizations.of(context).rssAdd),
          content: SizedBox(
            width: 560,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(AppLocalizations.of(context).rssImportHelp),
                  const SizedBox(height: 16),
                  TextField(
                    controller: controller,
                    minLines: 3,
                    maxLines: 8,
                    decoration: InputDecoration(
                      labelText: AppLocalizations.of(context).rssInput,
                      errorText: error,
                      errorMaxLines: 4,
                      border: const OutlineInputBorder(),
                    ),
                  ),
                ],
              ),
            ),
          ),
          actions: [
            TextButton(
              onPressed: busy ? null : () => Navigator.pop(context),
              child: Text(AppLocalizations.of(context).rssCancel),
            ),
            FilledButton(
              onPressed: busy
                  ? null
                  : () async {
                      update(() {
                        busy = true;
                        error = null;
                      });
                      try {
                        final text = controller.text.trim();
                        final input = text.startsWith('http')
                            ? '{"sourceUrl": ${jsonEncode(text)}}'
                            : text;
                        await ref
                            .read(rssStoreProvider(repository))
                            .importSources(input);
                        ref.invalidate(rssSourceViewProvider(repository));
                        if (context.mounted) Navigator.pop(context);
                      } catch (e) {
                        if (context.mounted) {
                          update(() {
                            busy = false;
                            error = _message(context, e);
                          });
                        }
                      }
                    },
              child: Text(
                busy
                    ? AppLocalizations.of(context).rssSaving
                    : AppLocalizations.of(context).rssSave,
              ),
            ),
          ],
        ),
      ),
    );
    controller.dispose();
  }
}

class RssSourcePage extends ConsumerStatefulWidget {
  const RssSourcePage({
    super.key,
    required this.repository,
    required this.source,
  });
  final LibraryRepository repository;
  final RssSource source;
  @override
  ConsumerState<RssSourcePage> createState() => _RssSourcePageState();
}

class _RssSourcePageState extends ConsumerState<RssSourcePage> {
  CancelToken? _request;
  String? _error;
  bool _busy = false;
  String? _category;
  (LibraryRepository, String) get _key =>
      (widget.repository, widget.source.url);
  @override
  void dispose() {
    _request?.cancel('离开订阅页面');
    super.dispose();
  }

  Future<void> _refresh() async {
    _request?.cancel('替换刷新请求');
    final token = CancelToken();
    _request = token;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final client = ref.read(rssClientProvider);
      final articles = await client.fetch(
        widget.source,
        cancelToken: token,
        categoryUrl: _category,
      );
      if (!mounted || token.isCancelled) return;
      await ref
          .read(rssStoreProvider(widget.repository))
          .saveArticles(widget.source.url, articles);
      if (mounted) ref.invalidate(rssArticlesProvider(_key));
    } catch (error) {
      if (mounted && !token.isCancelled) {
        setState(() => _error = _message(context, error));
      }
    } finally {
      if (mounted && identical(_request, token)) {
        setState(() {
          _busy = false;
          _request = null;
        });
      }
    }
  }

  Widget _categoryPicker() {
    try {
      final categories = RssHtmlRules().categories(widget.source);
      return Padding(
        padding: const EdgeInsets.all(16),
        child: DropdownButtonFormField<String>(
          initialValue: _category ?? categories.first.url,
          isExpanded: true,
          items: [
            for (final category in categories)
              DropdownMenuItem(
                value: category.url,
                child: Text(category.title),
              ),
          ],
          onChanged: _busy
              ? null
              : (value) {
                  setState(() => _category = value);
                  _refresh();
                },
        ),
      );
    } catch (e) {
      return Padding(
        padding: const EdgeInsets.all(16),
        child: Text(_message(context, e)),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    ref.watch(rssClientProvider);
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.source.name),
        actions: [
          IconButton(
            tooltip: _busy
                ? AppLocalizations.of(context).rssCancelRefresh
                : AppLocalizations.of(context).rssRefresh,
            onPressed: _busy
                ? () {
                    _request?.cancel('用户取消');
                    setState(() {
                      _busy = false;
                      _request = null;
                    });
                  }
                : _refresh,
            icon: Icon(_busy ? Icons.close : Icons.refresh),
          ),
        ],
      ),
      body: Column(
        children: [
          if (_busy) const LinearProgressIndicator(),
          if (widget.source.requiresRuleEngine) _categoryPicker(),
          if (widget.source.requiresRuleEngine)
            Padding(
              padding: const EdgeInsets.all(16),
              child: Text(AppLocalizations.of(context).rssLegacyHelp),
            ),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.all(16),
              child: SelectableText(
                _error!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
          Expanded(
            child: ref
                .watch(rssArticlesProvider(_key))
                .when(
                  loading: () =>
                      const Center(child: CircularProgressIndicator()),
                  error: (error, _) => _Failure(
                    error: error,
                    retry: () => ref.invalidate(rssArticlesProvider(_key)),
                  ),
                  data: (articles) => articles.isEmpty
                      ? Center(
                          child: FilledButton.icon(
                            onPressed: _busy ? null : _refresh,
                            icon: const Icon(Icons.refresh),
                            label: Text(AppLocalizations.of(context).rssFetch),
                          ),
                        )
                      : RefreshIndicator(
                          onRefresh: _refresh,
                          child: ListView.builder(
                            itemCount: articles.length,
                            itemBuilder: (context, index) {
                              final article = articles[index];
                              return ListTile(
                                leading: Icon(
                                  article.starred
                                      ? Icons.star
                                      : article.read
                                      ? Icons.drafts_outlined
                                      : Icons.markunread_outlined,
                                ),
                                title: Text(article.title),
                                subtitle: Text(article.published),
                                onTap: () => context.push(
                                  '/rss/article',
                                  extra: RssArticleLocation(
                                    source: widget.source,
                                    articleId: article.id,
                                  ),
                                ),
                              );
                            },
                          ),
                        ),
                ),
          ),
        ],
      ),
    );
  }
}

class RssArticleLocation {
  const RssArticleLocation({required this.source, required this.articleId});
  final RssSource source;
  final String articleId;
}

class RssArticlePage extends ConsumerStatefulWidget {
  const RssArticlePage({
    super.key,
    required this.repository,
    required this.location,
  });
  final LibraryRepository repository;
  final RssArticleLocation location;
  @override
  ConsumerState<RssArticlePage> createState() => _RssArticlePageState();
}

class _RssArticlePageState extends ConsumerState<RssArticlePage> {
  String? _error;
  CancelToken? _request;
  bool _loading = false;
  (LibraryRepository, String) get _key =>
      (widget.repository, widget.location.source.url);
  @override
  void initState() {
    super.initState();
    Future.microtask(() async {
      await _mark(read: true);
      if (mounted) await _loadArticle();
    });
  }

  @override
  void dispose() {
    _request?.cancel('article_disposed');
    super.dispose();
  }

  Future<void> _loadArticle() async {
    if (!mounted) return;
    final token = CancelToken();
    _request?.cancel('article_replaced');
    _request = token;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final store = ref.read(rssStoreProvider(widget.repository));
      final current = (await store.articles(widget.location.source.url))
          .where((e) => e.id == widget.location.articleId)
          .firstOrNull;
      if (current == null || !mounted || token.isCancelled) return;
      final loaded = await ref
          .read(rssClientProvider)
          .fetchArticle(widget.location.source, current, cancelToken: token);
      if (!mounted || token.isCancelled) return;
      await store.saveArticleContent(widget.location.source.url, loaded);
      if (mounted) ref.invalidate(rssArticlesProvider(_key));
    } catch (e) {
      if (mounted && !token.isCancelled) {
        setState(() => _error = _message(context, e));
      }
    } finally {
      if (mounted && identical(token, _request)) {
        setState(() {
          _loading = false;
          _request = null;
        });
      }
    }
  }

  Future<void> _mark({bool? read, bool? starred}) async {
    if (!mounted) return;
    try {
      await ref
          .read(rssStoreProvider(widget.repository))
          .mark(
            widget.location.source.url,
            widget.location.articleId,
            read: read,
            starred: starred,
          );
      if (mounted) ref.invalidate(rssArticlesProvider(_key));
    } catch (e) {
      if (mounted) setState(() => _error = _message(context, e));
    }
  }

  @override
  Widget build(BuildContext context) {
    ref.watch(rssClientProvider);
    return ref
        .watch(rssArticlesProvider(_key))
        .when(
          loading: () =>
              const Scaffold(body: Center(child: CircularProgressIndicator())),
          error: (error, _) => Scaffold(
            appBar: AppBar(
              title: Text(AppLocalizations.of(context).rssArticle),
            ),
            body: _Failure(
              error: error,
              retry: () => ref.invalidate(rssArticlesProvider(_key)),
            ),
          ),
          data: (articles) {
            final article = articles
                .where((e) => e.id == widget.location.articleId)
                .firstOrNull;
            if (article == null) {
              return Scaffold(
                appBar: AppBar(
                  title: Text(AppLocalizations.of(context).rssArticle),
                ),
                body: Center(
                  child: Text(AppLocalizations.of(context).rssMissingArticle),
                ),
              );
            }
            return Scaffold(
              appBar: AppBar(
                title: Text(article.title),
                actions: [
                  IconButton(
                    tooltip: article.starred
                        ? AppLocalizations.of(context).rssUnstar
                        : AppLocalizations.of(context).rssStar,
                    onPressed: () => _mark(starred: !article.starred),
                    icon: Icon(
                      article.starred ? Icons.star : Icons.star_outline,
                    ),
                  ),
                ],
              ),
              body: SelectionArea(
                child: ListView(
                  padding: const EdgeInsets.all(24),
                  children: [
                    Text(
                      article.title,
                      style: Theme.of(context).textTheme.headlineSmall,
                    ),
                    const SizedBox(height: 8), Text(article.published),
                    if (_error != null)
                      Text(
                        _error!,
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.error,
                        ),
                      ),
                    const SizedBox(height: 24),
                    if (_loading) const LinearProgressIndicator(),
                    if (_error != null)
                      TextButton(
                        onPressed: _loadArticle,
                        child: Text(AppLocalizations.of(context).rssRetry),
                      ),
                    // Scripts never run; extracted HTTP images render natively.
                    Text(
                      _plainText(article.content),
                      style: Theme.of(context).textTheme.bodyLarge,
                    ),
                    for (final image
                        in ((article.original['rssImages'] as List?) ?? [])
                            .whereType<String>())
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 8),
                        child: Image.network(
                          image,
                          fit: BoxFit.contain,
                          headers: _imageHeaders(
                            widget.location.source,
                            article.link,
                          ),
                          semanticLabel: article.title,
                          errorBuilder: (context, error, stack) => Padding(
                            padding: const EdgeInsets.all(16),
                            child: Text(
                              AppLocalizations.of(context).rssImageError,
                            ),
                          ),
                        ),
                      ),
                    if (article.link.isNotEmpty) ...[
                      const SizedBox(height: 24),
                      SelectableText(article.link),
                      Align(
                        alignment: Alignment.centerLeft,
                        child: TextButton.icon(
                          icon: const Icon(Icons.copy),
                          label: Text(AppLocalizations.of(context).rssCopyLink),
                          onPressed: () async {
                            await Clipboard.setData(
                              ClipboardData(text: article.link),
                            );
                            if (context.mounted) {
                              ScaffoldMessenger.of(context).showSnackBar(
                                SnackBar(
                                  content: Text(
                                    AppLocalizations.of(context).rssCopied,
                                  ),
                                ),
                              );
                            }
                          },
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            );
          },
        );
  }
}

class _Failure extends StatelessWidget {
  const _Failure({required this.error, required this.retry});
  final Object error;
  final VoidCallback retry;
  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          SelectableText(_message(context, error)),
          const SizedBox(height: 12),
          FilledButton(
            onPressed: retry,
            child: Text(AppLocalizations.of(context).rssRetry),
          ),
        ],
      ),
    ),
  );
}

String _message(BuildContext context, Object error) {
  final l10n = AppLocalizations.of(context);
  if (error is FormatException) {
    return switch (error.message) {
      'rssInvalidUrl' => l10n.rssInvalidUrl,
      'rssCorruptStorage' => l10n.rssCorruptStorage,
      'rssInvalidRestore' => l10n.rssInvalidRestore,
      'rssDuplicateRestoreSource' => l10n.rssDuplicateRestoreSource,
      'rssInvalidSource' => l10n.rssInvalidSource,
      'rssLegacyRules' || 'rssScriptCapability' => l10n.rssScriptCapability,
      'rssUnsupportedRule' => l10n.rssUnsupportedRule,
      'rssPaginationCycle' => l10n.rssPaginationCycle,
      'rssPageLimit' => l10n.rssPageLimit,
      'rssInvalidArticleLink' => l10n.rssInvalidArticleLink,
      'rssEmptyResponse' => l10n.rssEmptyResponse,
      'rssTooLarge' => l10n.rssTooLarge,
      _ => l10n.rssInvalidFeed,
    };
  }
  return error is DioException ? l10n.rssNetworkError : l10n.rssOperationError;
}

String _plainText(String content) {
  final fragment = html.parseFragment(content);
  for (final node in fragment.querySelectorAll(
    'script, style, iframe, object',
  )) {
    node.remove();
  }
  for (final br in fragment.querySelectorAll('br')) {
    br.replaceWith(html.parseFragment('\n'));
  }
  return fragment.text ?? '';
}

Map<String, String> _imageHeaders(RssSource source, String baseUrl) {
  final raw = source.original['header'];
  if (raw is! String || raw.isEmpty) return {};
  try {
    final json = jsonDecode(raw.replaceAll('{{baseUrl}}', baseUrl));
    if (json is! Map) return {};
    return {
      for (final entry in json.entries)
        if (entry.value is String) entry.key.toString(): entry.value as String,
    };
  } catch (_) {
    return {};
  }
}
