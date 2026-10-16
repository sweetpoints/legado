import 'package:app_core/app_core.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';
import 'package:freezed_annotation/freezed_annotation.dart';

import '../../app/providers.dart';

part 'bookshelf_state.freezed.dart';
part 'bookshelf_state.g.dart';

@freezed
abstract class BookshelfViewState with _$BookshelfViewState {
  const factory BookshelfViewState({
    @Default(<LegacyBookGroup>[]) List<LegacyBookGroup> groups,
    @Default(-1) int groupId,
    @Default(BookshelfSort.recentlyRead) BookshelfSort sort,
  }) = _BookshelfViewState;
}

@Riverpod(name: 'bookshelfStateProvider', keepAlive: true)
class BookshelfController extends _$BookshelfController {
  LibraryRepository get _library => ref.read(libraryProvider);
  BookshelfLibraryRepository get _groups =>
      _library as BookshelfLibraryRepository;

  @override
  Future<BookshelfViewState> build() async {
    ref.watch(libraryChangesProvider);
    final library = ref.watch(libraryProvider);
    final groups = library is BookshelfLibraryRepository
        ? await (library as BookshelfLibraryRepository).bookGroups()
        : <LegacyBookGroup>[];
    var selected =
        int.tryParse(await library.setting('bookshelf.selectedGroup') ?? '') ??
        -1;
    if (!{-1, 0, -2}.contains(selected) &&
        !groups.any((g) => g.id == selected && g.show)) {
      selected = -1;
    }
    final globalSort =
        int.tryParse(await library.setting('bookshelf.sort') ?? '') ?? 0;
    final groupSort =
        groups.where((g) => g.id == selected).firstOrNull?.sort ?? -1;
    final sort = groupSort >= 0 && groupSort < BookshelfSort.values.length
        ? groupSort
        : globalSort;
    return BookshelfViewState(
      groups: groups,
      groupId: selected,
      sort: BookshelfSort
          .values[sort >= 0 && sort < BookshelfSort.values.length ? sort : 0],
    );
  }

  Future<void> selectGroup(int id) =>
      _library.setSetting('bookshelf.selectedGroup', '$id');

  Future<void> selectSort(BookshelfSort sort) async {
    final current = await future;
    final group = current.groups
        .where((g) => g.id == current.groupId)
        .firstOrNull;
    if (group == null) {
      await _library.setSetting('bookshelf.sort', '${sort.index}');
    } else {
      await _groups.updateBookGroup(
        LegacyBookGroup(group.toJson()..['bookSort'] = sort.index),
      );
    }
  }

  Future<void> createGroup(String name) async {
    final group = await _groups.createBookGroup(name);
    await selectGroup(group.id);
  }

  Future<void> renameGroup(LegacyBookGroup group, String name) =>
      _groups.updateBookGroup(
        LegacyBookGroup(group.toJson()..['groupName'] = name.trim()),
      );

  Future<void> deleteGroup(LegacyBookGroup group) =>
      _groups.removeBookGroup(group.id);
  Future<void> assign(String url, List<int> ids) =>
      _groups.setBookGroups(url, ids);
  Future<void> reorder(List<String> urls) => _groups.reorderBooks(urls);
}
