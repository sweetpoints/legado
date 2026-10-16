import 'dart:async';
import 'dart:io';

import 'package:app_core/app_core.dart';
import 'package:app_storage/app_storage.dart';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:path_provider/path_provider.dart';

import 'preferences.dart';

final persistentLibraryProvider = FutureProvider<LibraryRepository>((
  ref,
) async {
  final directory = await getApplicationSupportDirectory();
  await directory.create(recursive: true);
  final repository = await SqliteLibraryRepository.open(
    '${directory.path}${Platform.pathSeparator}legado.sqlite',
  );
  if (!ref.mounted) {
    await repository.close();
    throw StateError('library_open_cancelled');
  }
  ref.onDispose(() => unawaited(repository.close()));
  return repository;
});

/// Overridden only after opening the durable library; never a memory fallback.
final libraryProvider = Provider<LibraryRepository>(
  (ref) => ref.watch(persistentLibraryProvider).requireValue,
);

final libraryChangesProvider = StreamProvider<int>((ref) async* {
  var revision = 0;
  yield revision;
  await for (final _ in ref.watch(libraryProvider).changes) {
    yield ++revision;
  }
});

final bookshelfProvider = FutureProvider<List<LegacyBookRecord>>((ref) {
  ref.watch(libraryChangesProvider);
  return ref.watch(libraryProvider).books();
});

final dioProvider = Provider<Dio>((ref) {
  final dio = Dio(
    BaseOptions(
      connectTimeout: const Duration(seconds: 15),
      receiveTimeout: const Duration(seconds: 30),
      sendTimeout: const Duration(seconds: 30),
    ),
  );
  ref.onDispose(() => dio.close(force: true));
  return dio;
});

final preferencesProvider =
    AsyncNotifierProvider<PreferencesController, AppPreferences>(
      PreferencesController.new,
    );

class PreferencesController extends AsyncNotifier<AppPreferences> {
  Future<void> _pending = Future.value();

  @override
  Future<AppPreferences> build() async {
    final repository = await ref.watch(persistentLibraryProvider.future);
    final locale = await repository.setting('app.locale') ?? '';
    final theme = await repository.setting('app.theme') ?? 'system';
    return AppPreferences(localeTag: locale, themeTag: theme);
  }

  Future<void> _update(
    String key,
    String value,
    AppPreferences Function(AppPreferences) change,
  ) {
    final next = _pending.then((_) async {
      final current = await future;
      await ref.read(libraryProvider).setSetting(key, value);
      if (ref.mounted) state = AsyncData(change(current));
    });
    _pending = next.then<void>((_) {}, onError: (Object _, StackTrace _) {});
    return next;
  }

  Future<void> setLocale(String locale) {
    if (!{'', 'zh', 'en'}.contains(locale)) {
      throw ArgumentError.value(locale, 'locale');
    }
    return _update('app.locale', locale, (p) => p.copyWith(localeTag: locale));
  }

  Future<void> setTheme(String theme) {
    if (!{'system', 'light', 'dark'}.contains(theme)) {
      throw ArgumentError.value(theme, 'theme');
    }
    return _update('app.theme', theme, (p) => p.copyWith(themeTag: theme));
  }
}
