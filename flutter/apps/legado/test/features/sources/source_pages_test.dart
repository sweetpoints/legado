import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';
import 'package:go_router/go_router.dart';
import 'package:app_core/app_core.dart';
import 'package:legado_app/services/book_source_service.dart';
import 'package:legado_app/l10n/generated/app_localizations.dart';
import 'package:legado_app/features/sources/source_search_page.dart';
import 'package:legado_app/features/sources/book_detail_page.dart';
import 'package:legado_app/features/sources/source_providers.dart';

import 'source_test_repository.dart';

class _UiService extends BookSourceService {
  _UiService(super.repository);
  final pages = <int>[];
  final preview = BookPreview(
    const NetworkBook(
      sourceId: 'fixture',
      metadata: {
        'bookUrl': 'https://fixture.example/book',
        'name': 'Preview book',
        'author': 'Author',
      },
    ),
    const [
      ReaderChapter(
        url: 'https://fixture.example/chapter',
        title: 'Chapter one',
        index: 0,
      ),
    ],
  );
  @override
  Future<List<NetworkBook>> search(
    String sourceId,
    String key, {
    int page = 1,
  }) async => [preview.book];
  @override
  Future<List<Map<String, Object?>>> exploreMenu(String id) async => [
    {'title': 'Fantasy', 'url': '/category'},
  ];
  @override
  Future<List<NetworkBook>> explore(
    String id,
    String url, {
    int page = 1,
  }) async {
    pages.add(page);
    return [preview.book];
  }

  @override
  Future<BookPreview> loadDetail(NetworkBook selected) async => preview;
  @override
  Future<String> fetchChapter(BookPreview preview, int index) async =>
      'Chapter content';
}

void main() {
  testWidgets(
    'browse pagination and reading preserve preview without adding shelf row',
    (tester) async {
      final repo = SourceTestRepository();
      await repo.putSource({
        'id': 'fixture',
        'name': 'Fixture',
        'baseUrl': 'https://fixture.example',
        'stages': {},
      });
      final service = _UiService(repo);
      BookPreview? opened;
      final router = GoRouter(
        routes: [
          GoRoute(
            path: '/',
            builder: (_, _) => SourceSearchPage(repository: repo),
          ),
          GoRoute(
            path: '/book',
            builder: (_, state) => BookDetailPage(
              repository: repo,
              book: state.extra! as NetworkBook,
            ),
          ),
          GoRoute(
            path: '/reader',
            builder: (_, state) {
              opened = state.extra! as BookPreview;
              return const Scaffold(body: Text('Reader opened'));
            },
          ),
        ],
      );
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            bookSourceServiceProvider(repo).overrideWithValue(service),
          ],
          child: MaterialApp.router(
            routerConfig: router,
            locale: const Locale('en'),
            localizationsDelegates: [
              AppLocalizations.delegate,
              ...GlobalMaterialLocalizations.delegates,
            ],
            supportedLocales: AppLocalizations.supportedLocales,
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Browse categories'));
      await tester.pumpAndSettle();
      expect(find.text('Preview book'), findsOneWidget);
      expect(service.pages, [1]);
      await tester.tap(find.byTooltip('Next page'));
      await tester.pumpAndSettle();
      expect(service.pages, [1, 2]);
      expect(find.text('Page 2'), findsOneWidget);
      await tester.tap(find.text('Preview book'));
      await tester.pumpAndSettle();
      expect(await repo.books(), isEmpty);
      await tester.tap(find.text('Chapter one'));
      await tester.pumpAndSettle();
      expect(find.text('Reader opened'), findsOneWidget);
      expect(opened?.chapters.single.title, 'Chapter one');
      expect(opened?.toLegacyBookRecord().toJson()['origin'], 'fixture');
      expect(await repo.books(), isEmpty);
      await tester.pumpWidget(const SizedBox.shrink());
      router.dispose();
      await service.close();
      await repo.close();
    },
  );
}
