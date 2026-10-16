import 'dart:async';

import 'package:app_core/app_core.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../services/book_source_service.dart';

final bookSourceServiceProvider =
    Provider.family<BookSourceService, LibraryRepository>((ref, repository) {
      final service = BookSourceService(repository);
      ref.onDispose(() => unawaited(service.close()));
      return service;
    });

final sourceListProvider =
    StreamProvider.family<List<Map<String, Object?>>, LibraryRepository>((
      ref,
      repository,
    ) async* {
      yield await repository.sources();
      await for (final _ in repository.changes) {
        yield await repository.sources();
      }
    });

typedef SourceSearchQuery = ({
  LibraryRepository repository,
  String sourceId,
  String key,
  int page,
});
final sourceSearchProvider = FutureProvider.autoDispose
    .family<List<NetworkBook>, SourceSearchQuery>((ref, query) {
      if (query.key.isEmpty || query.sourceId.isEmpty) return Future.value([]);
      return ref
          .watch(bookSourceServiceProvider(query.repository))
          .search(query.sourceId, query.key, page: query.page);
    });

typedef BookDetailQuery = ({LibraryRepository repository, NetworkBook book});
final bookDetailProvider = FutureProvider.autoDispose
    .family<BookPreview, BookDetailQuery>(
      (ref, query) => ref
          .watch(bookSourceServiceProvider(query.repository))
          .loadDetail(query.book),
    );

final sourceExploreMenuProvider = FutureProvider.autoDispose
    .family<
      List<Map<String, Object?>>,
      ({LibraryRepository repository, String sourceId})
    >((ref, query) {
      if (query.sourceId.isEmpty) return Future.value([]);
      return ref
          .watch(bookSourceServiceProvider(query.repository))
          .exploreMenu(query.sourceId);
    });
typedef SourceExploreQuery = ({
  LibraryRepository repository,
  String sourceId,
  String url,
  int page,
});
final sourceExploreProvider = FutureProvider.autoDispose
    .family<List<NetworkBook>, SourceExploreQuery>((ref, query) {
      if (query.sourceId.isEmpty || query.url.isEmpty) return Future.value([]);
      return ref
          .watch(bookSourceServiceProvider(query.repository))
          .explore(query.sourceId, query.url, page: query.page);
    });
