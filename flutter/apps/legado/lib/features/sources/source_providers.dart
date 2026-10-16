import 'dart:async';

import 'package:app_core/app_core.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../services/book_source_service.dart';

part 'source_providers.g.dart';

@Riverpod(keepAlive: true)
BookSourceService bookSourceService(Ref ref, LibraryRepository repository) {
  final service = BookSourceService(repository);
  ref.onDispose(() => unawaited(service.close()));
  return service;
}

@Riverpod(keepAlive: true)
Stream<List<Map<String, Object?>>> sourceList(
  Ref ref,
  LibraryRepository repository,
) async* {
  yield await repository.sources();
  await for (final _ in repository.changes) {
    yield await repository.sources();
  }
}

typedef SourceSearchQuery = ({
  LibraryRepository repository,
  String sourceId,
  String key,
  int page,
});

@riverpod
Future<List<NetworkBook>> sourceSearch(Ref ref, SourceSearchQuery query) {
  if (query.key.isEmpty || query.sourceId.isEmpty) return Future.value([]);
  return ref
      .watch(bookSourceServiceProvider(query.repository))
      .search(query.sourceId, query.key, page: query.page);
}

typedef BookDetailQuery = ({LibraryRepository repository, NetworkBook book});

@riverpod
Future<BookPreview> bookDetail(Ref ref, BookDetailQuery query) => ref
    .watch(bookSourceServiceProvider(query.repository))
    .loadDetail(query.book);

@riverpod
Future<List<Map<String, Object?>>> sourceExploreMenu(
  Ref ref,
  ({LibraryRepository repository, String sourceId}) query,
) {
  if (query.sourceId.isEmpty) return Future.value([]);
  return ref
      .watch(bookSourceServiceProvider(query.repository))
      .exploreMenu(query.sourceId);
}

typedef SourceExploreQuery = ({
  LibraryRepository repository,
  String sourceId,
  String url,
  int page,
});

@riverpod
Future<List<NetworkBook>> sourceExplore(Ref ref, SourceExploreQuery query) {
  if (query.sourceId.isEmpty || query.url.isEmpty) return Future.value([]);
  return ref
      .watch(bookSourceServiceProvider(query.repository))
      .explore(query.sourceId, query.url, page: query.page);
}
