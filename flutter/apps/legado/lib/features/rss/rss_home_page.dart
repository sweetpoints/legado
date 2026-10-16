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
export 'rss_models.dart';

final rssStoreProvider = Provider.family<RssStore, LibraryRepository>(
  (ref, repository) => RssStore(repository),
);
final rssSourcesProvider = FutureProvider.autoDispose
    .family<List<RssSource>, LibraryRepository>((ref, repository) {
      final subscription = repository.changes.listen(
        (_) => ref.invalidateSelf(),
      );
      ref.onDispose(subscription.cancel);
      return ref.watch(rssStoreProvider(repository)).sources();
    });
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
      title: Text(AppLocalizations.of(context)!.rssTitle),
      actions: [
        IconButton(
          tooltip: AppLocalizations.of(context)!.rssAddTooltip,
          onPressed: () => _import(context, ref),
          icon: const Icon(Icons.add),
        ),
      ],
    ),
    body: ref
        .watch(rssSourcesProvider(repository))
        .when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, _) => _Failure(
            error: error,
            retry: () => ref.invalidate(rssSourcesProvider(repository)),
          ),
          data: (sources) => sources.isEmpty
              ? Center(child: Text(AppLocalizations.of(context)!.rssEmpty))
              : ListView.builder(
                  itemCount: sources.length,
                  itemBuilder: (context, index) {
                    final source = sources[index];
                    return ListTile(
                      leading: Icon(
                        source.requiresRuleEngine
                            ? Icons.warning_amber
                            : Icons.rss_feed,
                      ),
                      title: Text(source.name),
                      subtitle: Text(
                        source.requiresRuleEngine
                            ? AppLocalizations.of(context)!.rssLegacySubtitle
                            : source.url,
                      ),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () => context.push('/rss/source', extra: source),
                    );
                  },
                ),
        ),
  );

  Future<void> _import(BuildContext context, WidgetRef ref) async {
    final controller = TextEditingController();
    String? error;
    bool busy = false;
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, update) => AlertDialog(
          title: Text(AppLocalizations.of(context)!.rssAdd),
          content: SizedBox(
            width: 560,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(AppLocalizations.of(context)!.rssImportHelp),
                  const SizedBox(height: 16),
                  TextField(
                    controller: controller,
                    minLines: 3,
                    maxLines: 8,
                    decoration: InputDecoration(
                      labelText: AppLocalizations.of(context)!.rssInput,
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
              child: Text(AppLocalizations.of(context)!.rssCancel),
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
                        ref.invalidate(rssSourcesProvider(repository));
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
                    ? AppLocalizations.of(context)!.rssSaving
                    : AppLocalizations.of(context)!.rssSave,
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
      final articles = await client.fetch(widget.source, cancelToken: token);
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

  @override
  Widget build(BuildContext context) {
    ref.watch(rssClientProvider);
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.source.name),
        actions: [
          IconButton(
            tooltip: _busy
                ? AppLocalizations.of(context)!.rssCancelRefresh
                : AppLocalizations.of(context)!.rssRefresh,
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
          if (widget.source.requiresRuleEngine)
            Padding(
              padding: const EdgeInsets.all(16),
              child: Text(AppLocalizations.of(context)!.rssLegacyHelp),
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
                            label: Text(AppLocalizations.of(context)!.rssFetch),
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
  (LibraryRepository, String) get _key =>
      (widget.repository, widget.location.source.url);
  @override
  void initState() {
    super.initState();
    Future.microtask(() => _mark(read: true));
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
  Widget build(BuildContext context) => ref
      .watch(rssArticlesProvider(_key))
      .when(
        loading: () =>
            const Scaffold(body: Center(child: CircularProgressIndicator())),
        error: (error, _) => Scaffold(
          appBar: AppBar(title: Text(AppLocalizations.of(context)!.rssArticle)),
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
                title: Text(AppLocalizations.of(context)!.rssArticle),
              ),
              body: Center(
                child: Text(AppLocalizations.of(context)!.rssMissingArticle),
              ),
            );
          }
          return Scaffold(
            appBar: AppBar(
              title: Text(article.title),
              actions: [
                IconButton(
                  tooltip: article.starred
                      ? AppLocalizations.of(context)!.rssUnstar
                      : AppLocalizations.of(context)!.rssStar,
                  onPressed: () => _mark(starred: !article.starred),
                  icon: Icon(article.starred ? Icons.star : Icons.star_outline),
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
                  // Content is deliberately text-only: untrusted HTML/scripts never run.
                  Text(
                    _plainText(article.content),
                    style: Theme.of(context).textTheme.bodyLarge,
                  ),
                  if (article.link.isNotEmpty) ...[
                    const SizedBox(height: 24),
                    SelectableText(article.link),
                    Align(
                      alignment: Alignment.centerLeft,
                      child: TextButton.icon(
                        icon: const Icon(Icons.copy),
                        label: Text(AppLocalizations.of(context)!.rssCopyLink),
                        onPressed: () async {
                          await Clipboard.setData(
                            ClipboardData(text: article.link),
                          );
                          if (context.mounted) {
                            ScaffoldMessenger.of(context).showSnackBar(
                              SnackBar(
                                content: Text(
                                  AppLocalizations.of(context)!.rssCopied,
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
            child: Text(AppLocalizations.of(context)!.rssRetry),
          ),
        ],
      ),
    ),
  );
}

String _message(BuildContext context, Object error) {
  final l10n = AppLocalizations.of(context)!;
  if (error is FormatException) {
    return switch (error.message) {
      'rssInvalidUrl' => l10n.rssInvalidUrl,
      'rssCorruptStorage' => l10n.rssCorruptStorage,
      'rssInvalidSource' => l10n.rssInvalidSource,
      'rssLegacyRules' => l10n.rssLegacyHelp,
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
