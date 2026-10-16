import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/features/sources/source_providers.dart';

import 'source_test_repository.dart';

void main() {
  test('generated service stays alive between listeners then closes when its container is disposed', () async {
    final repository = SourceTestRepository();
    final container = ProviderContainer();
    final subscription = container.listen(
      bookSourceServiceProvider(repository),
      (_, _) {},
    );
    final service = container.read(bookSourceServiceProvider(repository));
    subscription.close();
    await container.pump();
    expect(
      identical(container.read(bookSourceServiceProvider(repository)), service),
      true,
    );
    await service.deleteSource('not-stored');
    container.dispose();
    await expectLater(service.deleteSource('not-stored'), throwsStateError);
    await repository.close();
  });

  test('generated source list keeps listening between screens and releases the repository stream at shutdown', () async {
    final repository = SourceTestRepository();
    final container = ProviderContainer();
    final provider = sourceListProvider(repository);
    final subscription = container.listen(provider, (_, _) {});
    expect(await container.read(provider.future), isEmpty);
    await container.pump();
    expect(repository.controller.hasListener, true);
    subscription.close();
    await container.pump();
    expect(container.exists(provider), true);
    expect(repository.controller.hasListener, true);
    final received = Completer<void>();
    final observer = container.listen(provider, (_, next) {
      if (next.value?.any((e) => e['bookSourceName'] == 'Changed') ?? false) {
        if (!received.isCompleted) received.complete();
      }
    });
    await repository.putSource({
      'bookSourceUrl': 'opaque',
      'bookSourceName': 'Changed',
    });
    await received.future;
    observer.close();
    expect(
      container.read(provider).requireValue.single['bookSourceName'],
      'Changed',
    );
    container.dispose();
    await Future<void>.delayed(Duration.zero);
    expect(repository.controller.hasListener, false);
    await repository.close();
  });

  test('generated record families keep equality and auto-dispose independent empty queries', () async {
    final repository = SourceTestRepository();
    final container = ProviderContainer();
    try {
      final query = (repository: repository, sourceId: '', key: '', page: 1);
      final provider = sourceSearchProvider(query);
      expect(
        provider,
        sourceSearchProvider((
          repository: repository,
          sourceId: '',
          key: '',
          page: 1,
        )),
      );
      expect(
        provider,
        isNot(
          sourceSearchProvider((
            repository: repository,
            sourceId: '',
            key: '',
            page: 2,
          )),
        ),
      );
      final subscription = container.listen(provider, (_, _) {});
      expect(await container.read(provider.future), isEmpty);
      subscription.close();
      await container.pump();
      expect(container.exists(provider), false);
      final explore = sourceExploreProvider((
        repository: repository,
        sourceId: '',
        url: '',
        page: 1,
      ));
      expect(await container.read(explore.future), isEmpty);
      final menu = sourceExploreMenuProvider((
        repository: repository,
        sourceId: '',
      ));
      expect(await container.read(menu.future), isEmpty);
      await container.pump();
      expect(container.exists(explore), false);
      expect(container.exists(menu), false);
    } finally {
      container.dispose();
      await repository.close();
    }
  });
}
