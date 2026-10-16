import 'dart:typed_data';

import 'package:app_core/app_core.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/app/file_exchange.dart';
import 'package:legado_app/app/providers.dart';
import 'package:legado_app/app/router.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../features/sources/source_test_repository.dart';

class _TrackedAdapter implements HttpClientAdapter {
  var closeCount = 0;
  bool? forced;
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) => throw StateError('This lifecycle test must not make network requests');
  @override
  void close({bool force = false}) {
    closeCount++;
    forced = force;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('generated foundation names and keepAlive match their former public contract', () {
    final providers = <String, ProviderOrFamily>{
      'persistentLibraryProvider': persistentLibraryProvider,
      'libraryProvider': libraryProvider,
      'libraryChangesProvider': libraryChangesProvider,
      'bookshelfProvider': bookshelfProvider,
      'dioProvider': dioProvider,
      'preferencesProvider': preferencesProvider,
      'fileExchangeProvider': fileExchangeProvider,
      'initialLocationProvider': initialLocationProvider,
      'appRouterProvider': appRouterProvider,
    };
    for (final entry in providers.entries) {
      expect(entry.value.name, entry.key);
      expect(
        entry.value.isAutoDispose,
        false,
        reason: '${entry.key} previously stayed alive',
      );
    }
  });

  test('Dio survives the last listener and closes its adapter on container disposal', () async {
    final container = ProviderContainer();
    final adapter = _TrackedAdapter();
    try {
      final subscription = container.listen(dioProvider, (_, _) {});
      final client = container.read(dioProvider)..httpClientAdapter = adapter;
      subscription.close();
      await container.pump();
      expect(identical(container.read(dioProvider), client), true);
      expect(adapter.closeCount, 0);
      expect(client.options.connectTimeout, const Duration(seconds: 15));
      expect(client.options.receiveTimeout, const Duration(seconds: 30));
    } finally {
      container.dispose();
    }
    expect(adapter.closeCount, 1);
    expect(adapter.forced, true);
  });

  test('library stream and cached bookshelf stay alive and update only after changes', () async {
    final repository = SourceTestRepository();
    final container = ProviderContainer(
      overrides: [
        persistentLibraryProvider.overrideWith((ref) async => repository),
      ],
    );
    try {
      await container.read(persistentLibraryProvider.future);
      expect(container.read(libraryProvider), same(repository));
      final subscription = container.listen(libraryChangesProvider, (_, _) {});
      await container.read(libraryChangesProvider.future);
      await container.pump();
      final booksSubscription = container.listen(bookshelfProvider, (_, _) {});
      await container.read(bookshelfProvider.future);
      await container.pump();
      subscription.close();
      await container.pump();
      expect(repository.controller.hasListener, true);
      repository.bookRows['a'] = LegacyBookRecord({
        'bookUrl': 'a',
        'name': 'One',
      });
      expect(await container.read(bookshelfProvider.future), isEmpty);
      repository.controller.add(null);
      await Future<void>.delayed(Duration.zero);
      await container.pump();
      expect((await container.read(bookshelfProvider.future)).single.url, 'a');
      booksSubscription.close();
      await container.pump();
      expect(repository.controller.hasListener, true);
    } finally {
      container.dispose();
      await Future<void>.delayed(Duration.zero);
      await repository.close();
    }
    expect(repository.controller.hasListener, false);
  });

  test('generated router and file exchange retain value override APIs and router state', () async {
    final repository = SourceTestRepository();
    const files = NativeFileExchange();
    final container = ProviderContainer(
      overrides: [
        libraryProvider.overrideWithValue(repository),
        initialLocationProvider.overrideWithValue('/settings'),
        fileExchangeProvider.overrideWithValue(files),
      ],
    );
    try {
      final subscription = container.listen(appRouterProvider, (_, _) {});
      final router = container.read(appRouterProvider);
      subscription.close();
      await container.pump();
      expect(container.read(appRouterProvider), same(router));
      expect(router.routeInformationProvider.value.uri.path, '/settings');
      expect(container.read(fileExchangeProvider), same(files));
    } finally {
      container.dispose();
      await repository.close();
    }
  });
}
