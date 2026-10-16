import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/features/rss/rss_home_page.dart';

import '../sources/source_test_repository.dart';

void main() {
  test('generated RSS store family stays alive while temporary source views cancel subscriptions', () async {
    final repository = SourceTestRepository();
    final container = ProviderContainer();
    try {
      final store = container.read(rssStoreProvider(repository));
      final subscription = container.listen(
        rssSourceViewProvider(repository),
        (_, _) {},
      );
      expect(
        (await container.read(rssSourceViewProvider(repository).future))
            .sources,
        isEmpty,
      );
      expect(repository.controller.hasListener, true);
      subscription.close();
      await container.pump();
      expect(repository.controller.hasListener, false);
      expect(container.exists(rssSourceViewProvider(repository)), false);
      expect(
        identical(container.read(rssStoreProvider(repository)), store),
        true,
      );
      final articles = rssArticlesProvider((repository, 'source'));
      final articleSubscription = container.listen(articles, (_, _) {});
      expect(await container.read(articles.future), isEmpty);
      expect(repository.controller.hasListener, true);
      articleSubscription.close();
      await container.pump();
      expect(repository.controller.hasListener, false);
      expect(container.exists(articles), false);
    } finally {
      container.dispose();
      await repository.close();
    }
  });

  test(
    'generated auto-disposed RSS client closes Dio and recreates its session',
    () async {
      final container = ProviderContainer();
      try {
        final subscription = container.listen(rssClientProvider, (_, _) {});
        final client = container.read(rssClientProvider);
        subscription.close();
        await container.pump();
        expect(container.exists(rssClientProvider), false);
        await expectLater(
          client.dio.get<void>('https://example.invalid/closed'),
          throwsA(
            isA<DioException>().having(
              (e) => e.message,
              'message',
              contains('closed'),
            ),
          ),
        );
        final replacement = container.read(rssClientProvider);
        expect(identical(client, replacement), false);
      } finally {
        container.dispose();
      }
    },
  );
}
