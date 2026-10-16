import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:legado_app/features/rss/rss_home_page.dart';
import 'package:legado_app/features/rss/rss_service.dart';
import 'package:legado_app/l10n/generated/app_localizations.dart';

import 'rss_service_test.dart' show MemoryLibrary;

void main() {
  testWidgets(
    'English sources -> articles -> article persists read and bookmark',
    (tester) async {
      final repository = MemoryLibrary();
      final store = RssStore(repository);
      await store.importSources(
        '{"sourceUrl":"https://example.org/rss","sourceName":"Fixture feed"}',
      );
      await store.saveArticles('https://example.org/rss', [
        const RssArticle(
          id: 'entry',
          title: 'Example entry',
          link: 'https://example.org/article',
          content: '<p>Offline text</p><script>bad()</script>',
        ),
      ]);
      final router = GoRouter(
        routes: [
          GoRoute(
            path: '/',
            builder: (_, _) => RssHomePage(repository: repository),
          ),
          GoRoute(
            path: '/rss/source',
            builder: (_, state) => RssSourcePage(
              repository: repository,
              source: state.extra! as RssSource,
            ),
          ),
          GoRoute(
            path: '/rss/article',
            builder: (_, state) => RssArticlePage(
              repository: repository,
              location: state.extra! as RssArticleLocation,
            ),
          ),
        ],
      );
      addTearDown(router.dispose);
      await tester.pumpWidget(
        ProviderScope(
          child: MaterialApp.router(
            locale: const Locale('en'),
            localizationsDelegates: [
              AppLocalizations.delegate,
              ...GlobalMaterialLocalizations.delegates,
            ],
            supportedLocales: AppLocalizations.supportedLocales,
            routerConfig: router,
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('Subscriptions'), findsOneWidget);
      await tester.tap(find.text('Fixture feed'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Example entry'));
      await tester.pumpAndSettle();
      expect(find.text('Offline text'), findsOneWidget);
      expect(find.textContaining('bad()'), findsNothing);
      await tester.tap(find.byTooltip('Bookmark article'));
      await tester.pumpAndSettle();
      final article = (await RssStore(
        repository,
      ).articles('https://example.org/rss')).single;
      expect(article.read, true);
      expect(article.starred, true);
      expect(find.byTooltip('Remove bookmark'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'Chinese legacy HTML rules show actionable compatibility notice',
    (tester) async {
      final repository = MemoryLibrary();
      final source = RssSource.fromJson({
        'sourceUrl': 'https://example.org/html',
        'sourceName': '旧规则',
        'ruleArticles': '.list',
      });
      await tester.pumpWidget(
        ProviderScope(
          child: MaterialApp(
            locale: const Locale('zh'),
            localizationsDelegates: [
              AppLocalizations.delegate,
              ...GlobalMaterialLocalizations.delegates,
            ],
            supportedLocales: AppLocalizations.supportedLocales,
            home: RssSourcePage(repository: repository, source: source),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.textContaining('原规则已保留'), findsOneWidget);
      await tester.tap(find.text('获取订阅文章'));
      await tester.pumpAndSettle();
      expect(find.textContaining('原 Android App'), findsNWidgets(2));
      expect(tester.takeException(), isNull);
    },
  );
}
