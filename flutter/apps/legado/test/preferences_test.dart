import 'package:app_storage/app_storage.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/app/providers.dart';

import 'features/sources/source_test_repository.dart';

class _FailOnceRepository extends SourceTestRepository {
  var failNext = true;

  @override
  Future<void> setSetting(String key, String? value) async {
    if (failNext) {
      failNext = false;
      throw StateError('test_write_failure');
    }
    await super.setSetting(key, value);
  }
}

void main() {
  test(
    'concurrent locale and theme updates preserve both durable values',
    () async {
      final repository = await SqliteLibraryRepository.inMemory();
      final container = ProviderContainer(
        overrides: [
          persistentLibraryProvider.overrideWith((ref) async => repository),
        ],
      );
      try {
        await container.read(preferencesProvider.future);
        final controller = container.read(preferencesProvider.notifier);
        await Future.wait([
          controller.setLocale('en'),
          controller.setTheme('dark'),
          controller.setLocale('zh'),
        ]);
        final preferences = await container.read(preferencesProvider.future);
        expect(preferences.localeTag, 'zh');
        expect(preferences.themeTag, 'dark');
        expect(await repository.setting('app.locale'), 'zh');
        expect(await repository.setting('app.theme'), 'dark');
        container.invalidate(preferencesProvider);
        expect(await container.read(preferencesProvider.future), preferences);
      } finally {
        container.dispose();
        await repository.close();
      }
    },
  );

  test('failed preference write leaves state intact and does not block later updates', () async {
    final repository = _FailOnceRepository();
    final container = ProviderContainer(
      overrides: [
        persistentLibraryProvider.overrideWith((ref) async => repository),
      ],
    );
    try {
      final original = await container.read(preferencesProvider.future);
      final controller = container.read(preferencesProvider.notifier);
      final failed = controller.setLocale('en');
      final failure = expectLater(failed, throwsStateError);
      final succeeding = controller.setTheme('dark');
      await failure;
      await succeeding;
      final preferences = await container.read(preferencesProvider.future);
      expect(preferences.localeTag, original.localeTag);
      expect(preferences.themeTag, 'dark');
      expect(await repository.setting('app.locale'), isNull);
      expect(await repository.setting('app.theme'), 'dark');
    } finally {
      container.dispose();
      await repository.close();
    }
  });
}
