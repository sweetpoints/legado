import 'dart:typed_data';

import 'package:app_backup/app_backup.dart';
import 'package:app_storage/app_storage.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/app/file_exchange.dart';
import 'package:legado_app/app/providers.dart';
import 'package:legado_app/app/router.dart';
import 'package:legado_app/main.dart';
import 'package:material_ui/material_ui.dart';

class _Files implements FileExchange {
  Uint8List? backup;
  Uint8List? saved;
  @override
  Future<ImportedTextFile?> pickText() async =>
      (name: '测试书', text: '第一章 起点\n这里是第一段正文。\n第二章 继续\n这里是第二段正文。');
  @override
  Future<Uint8List?> pickBackup() async => backup;
  @override
  Future<bool> saveBackup(Uint8List bytes, String name) async {
    saved = bytes;
    return true;
  }
}

// Drift workers and ZIP isolates need real event-loop progress in widget tests.
Future<void> waitFor(WidgetTester tester, bool Function() ready) async {
  for (var attempt = 0; attempt < 200; attempt++) {
    await tester.pump(const Duration(milliseconds: 16));
    if (ready()) {
      await tester.pump(const Duration(milliseconds: 400));
      if (ready()) return;
    }
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 10)),
    );
  }
  expect(
    ready(),
    isTrue,
    reason: 'Application did not reach the expected state',
  );
}

Future<void> cleanup(
  WidgetTester tester,
  ProviderContainer container,
  SqliteLibraryRepository repository,
) async {
  await tester.pumpWidget(const SizedBox.shrink());
  container.dispose();
  var closed = false;
  final closing = repository.close().then((_) {
    closed = true;
  });
  await waitFor(tester, () => closed);
  await closing;
}

Future<T> databaseCall<T>(
  WidgetTester tester,
  Future<T> Function() action,
) async {
  var completed = false;
  T? result;
  Object? failure;
  action().then(
    (value) {
      result = value;
      completed = true;
    },
    onError: (Object error, StackTrace stack) {
      failure = error;
      completed = true;
    },
  );
  await waitFor(tester, () => completed);
  if (failure != null) throw failure!;
  return result as T;
}

void main() {
  testWidgets(
    'imports TXT into Drift, reads chapters and changes locale without losing navigation',
    (tester) async {
      final repository = (await tester.runAsync(
        SqliteLibraryRepository.inMemory,
      ))!;
      await databaseCall(
        tester,
        () => repository.setSetting('app.locale', 'zh'),
      );
      final files = _Files();
      final container = ProviderContainer(
        overrides: [
          persistentLibraryProvider.overrideWith((ref) async => repository),
          fileExchangeProvider.overrideWithValue(files),
        ],
      );
      addTearDown(() => cleanup(tester, container, repository));
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: const LegadoApp(),
        ),
      );
      await waitFor(tester, () => find.text('书架还没有图书').evaluate().isNotEmpty);
      await tester.tap(find.text('导入 TXT 图书'));
      await waitFor(tester, () => find.text('测试书').evaluate().isNotEmpty);
      expect((await databaseCall(tester, repository.books)).single.name, '测试书');
      await tester.tap(find.text('测试书'));
      await waitFor(
        tester,
        () => find
            .byWidgetPredicate(
              (widget) =>
                  widget is SelectableText &&
                  (widget.data?.contains('这里是第一段正文。') ?? false),
            )
            .evaluate()
            .isNotEmpty,
      );
      container.read(appRouterProvider).go('/settings');
      await waitFor(tester, () => find.text('语言').evaluate().isNotEmpty);
      await databaseCall(
        tester,
        () => container.read(preferencesProvider.notifier).setLocale('en'),
      );
      await waitFor(tester, () => find.text('Language').evaluate().isNotEmpty);
      expect(
        await databaseCall(tester, () => repository.setting('app.locale')),
        'en',
      );
      container.read(appRouterProvider).go('/bookshelf');
      await waitFor(tester, () => find.text('测试书').evaluate().isNotEmpty);
      expect(find.text('Bookshelf'), findsWidgets);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'restores archive media and exports the consistent Drift snapshot',
    (tester) async {
      final repository = (await tester.runAsync(
        SqliteLibraryRepository.inMemory,
      ))!;
      await databaseCall(
        tester,
        () => repository.setSetting('app.locale', 'en'),
      );
      final files = _Files()
        ..backup = LegacyBackupArchive.encodeFiles(
          {
            'bookshelf.json': '[{"bookUrl":"opaque-book","name":"Restored book","durChapterIndex":2,"durChapterPos":11}]',
          },
          retainedEntries: {
            'covers/book.bin': Uint8List.fromList([0, 255, 1]),
          },
        );
      final container = ProviderContainer(
        overrides: [
          persistentLibraryProvider.overrideWith((ref) async => repository),
          fileExchangeProvider.overrideWithValue(files),
          initialLocationProvider.overrideWithValue('/backup'),
        ],
      );
      addTearDown(() => cleanup(tester, container, repository));
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: const LegadoApp(),
        ),
      );
      await waitFor(
        tester,
        () => find.text('Restore ZIP backup').evaluate().isNotEmpty,
      );
      await tester.tap(find.text('Restore ZIP backup'));
      await waitFor(
        tester,
        () => find.text('Restore this backup?').evaluate().isNotEmpty,
      );
      await tester.tap(find.text('Confirm'));
      await waitFor(
        tester,
        () => find.text('Backup restored').evaluate().isNotEmpty,
      );
      expect(
        (await databaseCall(tester, repository.books)).single.characterOffset,
        11,
      );
      expect(
        (await databaseCall(
          tester,
          repository.backupSnapshot,
        )).entries['covers/book.bin'],
        [0, 255, 1],
      );
      await tester.tap(find.text('Export ZIP backup'));
      await waitFor(tester, () => files.saved != null);
      final decoded = LegacyBackupArchive.decode(files.saved!);
      expect(decoded.document.books.single.name, 'Restored book');
      expect(decoded.retainedEntries['covers/book.bin'], [0, 255, 1]);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets('missing reader identity shows localized navigation recovery', (
    tester,
  ) async {
    final repository = (await tester.runAsync(
      SqliteLibraryRepository.inMemory,
    ))!;
    await databaseCall(tester, () => repository.setSetting('app.locale', 'en'));
    final container = ProviderContainer(
      overrides: [
        persistentLibraryProvider.overrideWith((ref) async => repository),
        initialLocationProvider.overrideWithValue('/reader'),
      ],
    );
    addTearDown(() => cleanup(tester, container, repository));
    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const LegadoApp()),
    );
    await waitFor(
      tester,
      () => find.text('This link cannot be opened.').evaluate().isNotEmpty,
    );
    await tester.tap(find.text('Back to bookshelf'));
    await waitFor(
      tester,
      () => find.text('Your bookshelf is empty').evaluate().isNotEmpty,
    );
    expect(
      container.read(appRouterProvider).routeInformationProvider.value.uri.path,
      '/bookshelf',
    );
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
