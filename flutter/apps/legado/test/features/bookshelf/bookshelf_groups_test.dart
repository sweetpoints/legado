import 'package:app_core/app_core.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/app/providers.dart';
import 'package:legado_app/features/bookshelf/bookshelf_page.dart';
import 'package:legado_app/l10n/generated/app_localizations.dart';
import 'package:material_ui/material_ui.dart';

import '../sources/source_test_repository.dart';

class _Groups extends SourceTestRepository
    implements BookshelfLibraryRepository {
  final groups = <int, LegacyBookGroup>{
    1: LegacyBookGroup({'groupId': 1, 'groupName': 'Fiction'}),
  };
  @override
  Future<List<LegacyBookGroup>> bookGroups() async => groups.values.toList();
  @override
  Future<void> setSetting(String key, String? value) async {
    await super.setSetting(key, value);
    controller.add(null);
  }

  @override
  Future<LegacyBookGroup> createBookGroup(String name) async {
    final group = LegacyBookGroup({'groupId': 2, 'groupName': name});
    groups[group.id] = group;
    controller.add(null);
    return group;
  }

  @override
  Future<void> updateBookGroup(LegacyBookGroup group) async {
    groups[group.id] = group;
    controller.add(null);
  }

  @override
  Future<void> removeBookGroup(int id) async {
    groups.remove(id);
    settings['bookshelf.selectedGroup'] = '-1';
    controller.add(null);
  }

  @override
  Future<void> setBookGroups(String url, List<int> ids) async {
    bookRows[url] = LegacyBookRecord(
      bookRows[url]!.toJson()
        ..['group'] = ids.fold<int>(0, (mask, id) => mask | id),
    );
    controller.add(null);
  }

  @override
  Future<void> reorderBooks(List<String> urls) async {
    for (var i = 0; i < urls.length; i++) {
      final url = urls[i];
      bookRows[url] = LegacyBookRecord(bookRows[url]!.toJson()..['order'] = i);
    }
    controller.add(null);
  }
}

Future<ProviderContainer> _mount(
  WidgetTester tester,
  _Groups repository,
  double width,
) async {
  tester.view.physicalSize = Size(width, 800);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  repository.bookRows.addAll({
    'a': LegacyBookRecord({
      'bookUrl': 'a',
      'name': 'Alpha',
      'group': 1,
      'order': 0,
    }),
    'b': LegacyBookRecord({
      'bookUrl': 'b',
      'name': 'Beta',
      'group': 0,
      'order': 1,
    }),
  });
  final container = ProviderContainer(
    overrides: [libraryProvider.overrideWithValue(repository)],
  );
  addTearDown(() async {
    await tester.pumpWidget(const SizedBox.shrink());
    container.dispose();
    await repository.close();
  });
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        theme: ThemeData(useMaterial3: true),
        locale: const Locale('en'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: const BookshelfPage(),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return container;
}

void main() {
  for (final width in [320.0, 360.0]) {
    testWidgets('all shelf entries remain accessible at ${width.toInt()}px', (
      tester,
    ) async {
      await _mount(tester, _Groups(), width);
      expect(find.text('Alpha'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.tap(find.byTooltip('Bookshelf options'));
      await tester.pumpAndSettle();
      expect(find.text('Import EPUB'), findsOneWidget);
      expect(find.text('Manage groups'), findsOneWidget);
      expect(find.text('Search books'), findsOneWidget);
      expect(find.text('Book sources'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.tap(find.text('Manage groups'));
      await tester.pumpAndSettle();
      expect(find.text('Fiction'), findsWidgets);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets(
    'creates renames and deletes custom groups through Material dialogs',
    (tester) async {
      final repository = _Groups();
      await _mount(tester, repository, 360);
      await tester.tap(find.byTooltip('Bookshelf options'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Manage groups'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Create group'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextFormField), 'New group');
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      expect(repository.groups[2]!.name, 'New group');
      await tester.tap(find.byTooltip('Rename group').last);
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextFormField), 'Renamed');
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      expect(repository.groups[2]!.name, 'Renamed');
      await tester.tap(find.byTooltip('Delete group').last);
      await tester.pumpAndSettle();
      expect(find.textContaining('Books will stay'), findsOneWidget);
      await tester.tap(find.text('Delete'));
      await tester.pumpAndSettle();
      expect(repository.groups.containsKey(2), false);
      expect(repository.bookRows.length, 2);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('changes membership filters by group and saves manual order', (
    tester,
  ) async {
    final repository = _Groups();
    await _mount(tester, repository, 360);
    await tester.tap(find.byTooltip('Book actions').first);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Assign groups'));
    await tester.pumpAndSettle();
    await tester.tap(find.byType(CheckboxListTile));
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(repository.bookRows['a']!.toJson()['group'], 0);
    await tester.tap(find.byType(DropdownButtonFormField<int>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Fiction').last);
    await tester.pumpAndSettle();
    expect(find.text('There are no books in this group.'), findsOneWidget);
    await tester.tap(find.byType(DropdownButtonFormField<int>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('All books').last);
    await tester.pumpAndSettle();
    await tester.tap(find.byType(DropdownButtonFormField<BookshelfSort>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Manual order').last);
    await tester.pumpAndSettle();
    expect(repository.settings['bookshelf.sort'], '3');
    await tester.tap(find.byTooltip('Book actions').first);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Move down'));
    await tester.pumpAndSettle();
    expect(repository.bookRows['a']!.toJson()['order'], 1);
    expect(repository.bookRows['b']!.toJson()['order'], 0);
    expect(tester.takeException(), isNull);
  });
}
