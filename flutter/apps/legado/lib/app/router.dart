import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import '../features/backup/backup_page.dart';
import '../features/bookshelf/bookshelf_page.dart';
import '../features/pdf/pdf_import_page.dart';
import '../features/pdf/pdf_reader_page.dart';
import '../features/reader/reader_page.dart';
import '../features/rss/rss_home_page.dart';
import '../features/settings/settings_page.dart';
import '../features/sources/book_detail_page.dart';
import '../features/sources/source_management_page.dart';
import '../features/sources/source_search_page.dart';
import '../features/sources/source_providers.dart';
import '../l10n/generated/app_localizations.dart';
import '../services/book_source_service.dart';
import 'providers.dart';

part 'router.g.dart';

@Riverpod(keepAlive: true)
String initialLocation(Ref ref) => '/bookshelf';
@Riverpod(keepAlive: true)
GoRouter appRouter(Ref ref) {
  final repository = ref.watch(libraryProvider);
  final router = GoRouter(
    initialLocation: ref.read(initialLocationProvider),
    errorBuilder: (context, state) => const InvalidLinkPage(),
    routes: [
      StatefulShellRoute.indexedStack(
        builder: (context, state, shell) => _NavigationShell(shell: shell),
        branches: [
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: '/bookshelf',
                builder: (_, _) => const BookshelfPage(),
              ),
            ],
          ),
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: '/search',
                builder: (_, _) => SourceSearchPage(repository: repository),
              ),
            ],
          ),
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: '/rss',
                builder: (_, _) => RssHomePage(repository: repository),
              ),
            ],
          ),
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: '/settings',
                builder: (_, _) => const SettingsPage(),
              ),
            ],
          ),
        ],
      ),
      GoRoute(
        path: '/sources',
        builder: (_, _) => SourceManagementPage(repository: repository),
      ),
      GoRoute(path: '/backup', builder: (_, _) => const BackupPage()),
      GoRoute(
        path: '/pdf/import',
        builder: (context, state) => PdfImportPage(
          repository: repository,
          onImported: (book) {
            ref.invalidate(bookshelfProvider);
            context.replace(
              Uri(
                path: '/pdf',
                queryParameters: {'bookUrl': book.url},
              ).toString(),
            );
          },
        ),
      ),
      GoRoute(
        path: '/pdf',
        builder: (context, state) {
          final url = state.uri.queryParameters['bookUrl'];
          if (url == null || url.isEmpty) return const InvalidLinkPage();
          return PdfReaderPage(repository: repository, bookUrl: url);
        },
      ),
      GoRoute(
        path: '/book',
        builder: (context, state) {
          final extra = state.extra;
          if (extra is NetworkBook) {
            return BookDetailPage(repository: repository, book: extra);
          }
          final source = state.uri.queryParameters['sourceId'];
          final url = state.uri.queryParameters['bookUrl'];
          if (source == null || url == null || source.isEmpty || url.isEmpty) {
            return const InvalidLinkPage();
          }
          return BookDetailPage(
            repository: repository,
            book: NetworkBook(
              sourceId: source,
              metadata: {'bookUrl': url, 'name': url},
            ),
          );
        },
      ),
      GoRoute(
        path: '/reader',
        builder: (context, state) {
          final url = state.uri.queryParameters['bookUrl'];
          if (url == null || url.isEmpty) return const InvalidLinkPage();
          final preview = state.extra is BookPreview
              ? state.extra as BookPreview
              : null;
          return _ReaderRoute(
            bookUrl: url,
            initialChapter: int.tryParse(
              state.uri.queryParameters['chapter'] ?? '',
            ),
            preview: preview,
          );
        },
      ),
      GoRoute(
        path: '/rss/source',
        builder: (context, state) => state.extra is RssSource
            ? RssSourcePage(
                repository: repository,
                source: state.extra! as RssSource,
              )
            : const InvalidLinkPage(),
      ),
      GoRoute(
        path: '/rss/article',
        builder: (context, state) => state.extra is RssArticleLocation
            ? RssArticlePage(
                repository: repository,
                location: state.extra! as RssArticleLocation,
              )
            : const InvalidLinkPage(),
      ),
    ],
  );
  ref.onDispose(router.dispose);
  return router;
}

class _ReaderRoute extends ConsumerWidget {
  const _ReaderRoute({
    required this.bookUrl,
    this.initialChapter,
    this.preview,
  });
  final String bookUrl;
  final int? initialChapter;
  final BookPreview? preview;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final repository = ref.watch(libraryProvider);
    return ReaderPage(
      repository: repository,
      bookUrl: bookUrl,
      initialChapterIndex: initialChapter,
      previewBook: preview?.toLegacyBookRecord(),
      previewChapters: preview?.chapters,
      loadChapter: (chapter) async {
        final service = ref.read(bookSourceServiceProvider(repository));
        var currentPreview = preview;
        if (currentPreview == null) {
          final stored = await repository.book(bookUrl);
          if (stored == null) throw StateError('book_missing');
          final metadata = stored.toJson();
          final sourceId = metadata['origin'];
          if (sourceId is! String || sourceId == 'local') {
            throw StateError('cached_content_missing');
          }
          currentPreview = BookPreview(
            NetworkBook(sourceId: sourceId, metadata: metadata),
            await repository.chapters(bookUrl),
          );
        }
        final index = currentPreview.chapters.indexWhere(
          (value) => value.url == chapter.url,
        );
        if (index < 0) throw StateError('chapter_missing');
        await service.fetchChapter(currentPreview, index);
      },
    );
  }
}

class _NavigationShell extends StatelessWidget {
  const _NavigationShell({required this.shell});
  final StatefulNavigationShell shell;
  @override
  Widget build(BuildContext context) {
    final strings = AppLocalizations.of(context);
    final labels = [
      strings.bookshelfTitle,
      strings.exploreTitle,
      strings.navigationRssTitle,
      strings.settingsTitle,
    ];
    const icons = [
      Icons.menu_book,
      Icons.explore,
      Icons.rss_feed,
      Icons.settings,
    ];
    return LayoutBuilder(
      builder: (context, constraints) {
        final wide = constraints.maxWidth >= 840;
        void select(int index) =>
            shell.goBranch(index, initialLocation: index == shell.currentIndex);
        return Scaffold(
          body: Row(
            children: [
              if (wide)
                NavigationRail(
                  selectedIndex: shell.currentIndex,
                  onDestinationSelected: select,
                  labelType: NavigationRailLabelType.all,
                  destinations: [
                    for (var index = 0; index < labels.length; index++)
                      NavigationRailDestination(
                        icon: Icon(icons[index]),
                        label: Text(labels[index]),
                      ),
                  ],
                ),
              Expanded(child: shell),
            ],
          ),
          bottomNavigationBar: wide
              ? null
              : NavigationBar(
                  selectedIndex: shell.currentIndex,
                  onDestinationSelected: select,
                  destinations: [
                    for (var index = 0; index < labels.length; index++)
                      NavigationDestination(
                        icon: Icon(icons[index]),
                        label: labels[index],
                      ),
                  ],
                ),
        );
      },
    );
  }
}

class InvalidLinkPage extends StatelessWidget {
  const InvalidLinkPage({super.key});
  @override
  Widget build(BuildContext context) {
    final strings = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(strings.appTitle)),
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(strings.invalidRoute),
            const SizedBox(height: 12),
            FilledButton(
              onPressed: () => context.go('/bookshelf'),
              child: Text(strings.backToShelf),
            ),
          ],
        ),
      ),
    );
  }
}
