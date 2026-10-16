import 'package:app_core/app_core.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import '../../l10n/generated/app_localizations.dart';
import 'bookshelf_state.dart';

Future<void> shelfAction(
  BuildContext context,
  Future<void> Function() action,
) async {
  final failure = AppLocalizations.of(context).operationFailed;
  try {
    await action();
  } catch (_) {
    if (context.mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(failure)));
    }
  }
}

String shelfGroupLabel(
  AppLocalizations strings,
  int id,
  List<LegacyBookGroup> groups,
) {
  final name = groups.where((g) => g.id == id).firstOrNull?.name ?? '';
  if (name.isNotEmpty) return name;
  return switch (id) {
    -1 => strings.shelfAll,
    0 => strings.shelfUngrouped,
    -2 => strings.shelfLocal,
    -3 => strings.shelfAudio,
    -6 => strings.shelfVideo,
    -4 || -100 => strings.shelfNetworkUngrouped,
    -5 => strings.shelfLocalUngrouped,
    -11 => strings.shelfUpdateFailed,
    _ => '$id',
  };
}

String shelfSortLabel(AppLocalizations strings, BookshelfSort sort) =>
    switch (sort) {
      BookshelfSort.recentlyRead => strings.shelfSortRead,
      BookshelfSort.updated => strings.shelfSortUpdated,
      BookshelfSort.title => strings.shelfSortTitle,
      BookshelfSort.manual => strings.shelfSortManual,
      BookshelfSort.activity => strings.shelfSortActivity,
      BookshelfSort.author => strings.shelfSortAuthor,
    };

class BookshelfFilters extends ConsumerWidget {
  const BookshelfFilters({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = AppLocalizations.of(context);
    final state = ref.watch(bookshelfStateProvider);
    return state.when(
      loading: () => const LinearProgressIndicator(),
      error: (_, _) => TextButton(
        onPressed: () => ref.invalidate(bookshelfStateProvider),
        child: Text(strings.retry),
      ),
      data: (data) {
        final ids = <int>{
          -1,
          0,
          -2,
          for (final group in data.groups)
            if (group.show && group.id != 0) group.id,
        };
        final controller = ref.read(bookshelfStateProvider.notifier);
        return Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          child: Row(
            children: [
              Expanded(
                child: DropdownButtonFormField<int>(
                  key: ValueKey('shelf-group-${data.groupId}'),
                  initialValue: data.groupId,
                  isExpanded: true,
                  decoration: InputDecoration(labelText: strings.shelfGroup),
                  items: [
                    for (final id in ids)
                      DropdownMenuItem(
                        value: id,
                        child: Text(
                          shelfGroupLabel(strings, id, data.groups),
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                  ],
                  onChanged: (value) {
                    if (value != null) {
                      shelfAction(context, () => controller.selectGroup(value));
                    }
                  },
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: DropdownButtonFormField<BookshelfSort>(
                  key: ValueKey('shelf-sort-${data.sort.index}'),
                  initialValue: data.sort,
                  isExpanded: true,
                  decoration: InputDecoration(labelText: strings.shelfSort),
                  items: [
                    for (final sort in BookshelfSort.values)
                      DropdownMenuItem(
                        value: sort,
                        child: Text(
                          shelfSortLabel(strings, sort),
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                  ],
                  onChanged: (value) {
                    if (value != null) {
                      shelfAction(context, () => controller.selectSort(value));
                    }
                  },
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}

Future<String?> _groupName(BuildContext context, {String initial = ''}) async {
  final strings = AppLocalizations.of(context);
  final text = TextEditingController(text: initial);
  final form = GlobalKey<FormState>();
  final result = await showDialog<String>(
    context: context,
    builder: (context) => AlertDialog(
      title: Text(
        initial.isEmpty ? strings.shelfCreateGroup : strings.shelfRenameGroup,
      ),
      content: Form(
        key: form,
        child: TextFormField(
          controller: text,
          autofocus: true,
          decoration: InputDecoration(labelText: strings.shelfGroupName),
          validator: (value) => value == null || value.trim().isEmpty
              ? strings.shelfGroupNameRequired
              : null,
          onFieldSubmitted: (_) {
            if (form.currentState!.validate()) {
              Navigator.pop(context, text.text.trim());
            }
          },
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: Text(strings.cancel),
        ),
        FilledButton(
          onPressed: () {
            if (form.currentState!.validate()) {
              Navigator.pop(context, text.text.trim());
            }
          },
          child: Text(strings.save),
        ),
      ],
    ),
  );
  // Route transition can still reference the controller until its overlay exits.
  WidgetsBinding.instance.addPostFrameCallback((_) => text.dispose());
  return result;
}

class BookshelfGroupManager extends ConsumerWidget {
  const BookshelfGroupManager({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = AppLocalizations.of(context);
    final groups = ref.watch(bookshelfStateProvider);
    final controller = ref.read(bookshelfStateProvider.notifier);
    return SafeArea(
      child: SizedBox(
        height: MediaQuery.sizeOf(context).height * .75,
        child: Column(
          children: [
            ListTile(
              title: Text(
                strings.shelfManageGroups,
                style: Theme.of(context).textTheme.titleLarge,
              ),
              trailing: IconButton(
                tooltip: strings.close,
                onPressed: () => Navigator.pop(context),
                icon: const Icon(Icons.close),
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(16),
              child: FilledButton.icon(
                onPressed: () async {
                  final name = await _groupName(context);
                  if (name != null && context.mounted) {
                    await shelfAction(
                      context,
                      () => controller.createGroup(name),
                    );
                  }
                },
                icon: const Icon(Icons.create_new_folder),
                label: Text(strings.shelfCreateGroup),
              ),
            ),
            Expanded(
              child: groups.when(
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (_, _) => Center(child: Text(strings.operationFailed)),
                data: (data) {
                  final custom = data.groups
                      .where((g) => g.isAssignable)
                      .toList();
                  return custom.isEmpty
                      ? Center(child: Text(strings.shelfNoGroups))
                      : ListView(
                          children: [
                            for (final group in custom)
                              ListTile(
                                title: Text(group.name),
                                trailing: Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    IconButton(
                                      tooltip: strings.shelfRenameGroup,
                                      icon: const Icon(Icons.edit),
                                      onPressed: () async {
                                        final name = await _groupName(
                                          context,
                                          initial: group.name,
                                        );
                                        if (name != null && context.mounted) {
                                          await shelfAction(
                                            context,
                                            () => controller.renameGroup(
                                              group,
                                              name,
                                            ),
                                          );
                                        }
                                      },
                                    ),
                                    IconButton(
                                      tooltip: strings.shelfDeleteGroup,
                                      icon: const Icon(
                                        Icons.folder_delete_outlined,
                                      ),
                                      onPressed: () async {
                                        final yes = await showDialog<bool>(
                                          context: context,
                                          builder: (dialog) => AlertDialog(
                                            title: Text(
                                              strings.shelfDeleteGroup,
                                            ),
                                            content: Text(
                                              strings.shelfDeleteGroupMessage,
                                            ),
                                            actions: [
                                              TextButton(
                                                onPressed: () => Navigator.pop(
                                                  dialog,
                                                  false,
                                                ),
                                                child: Text(strings.cancel),
                                              ),
                                              FilledButton(
                                                onPressed: () =>
                                                    Navigator.pop(dialog, true),
                                                child: Text(strings.delete),
                                              ),
                                            ],
                                          ),
                                        );
                                        if (yes == true && context.mounted) {
                                          await shelfAction(
                                            context,
                                            () => controller.deleteGroup(group),
                                          );
                                        }
                                      },
                                    ),
                                  ],
                                ),
                              ),
                          ],
                        );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

Future<void> assignBookGroups(
  BuildContext context,
  WidgetRef ref,
  LegacyBookRecord book,
) async {
  final strings = AppLocalizations.of(context);
  final state = await ref.read(bookshelfStateProvider.future);
  if (!context.mounted) return;
  final groups = state.groups.where((g) => g.isAssignable).toList();
  final chosen = groups
      .where((g) => (bookInteger(book, 'group') & g.id) != 0)
      .map((g) => g.id)
      .toSet();
  final yes = await showDialog<bool>(
    context: context,
    builder: (dialog) => StatefulBuilder(
      builder: (dialog, update) => AlertDialog(
        title: Text(strings.shelfAssignGroups),
        content: SizedBox(
          width: 400,
          child: groups.isEmpty
              ? Text(strings.shelfNoGroups)
              : SingleChildScrollView(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      for (final group in groups)
                        CheckboxListTile(
                          title: Text(group.name),
                          value: chosen.contains(group.id),
                          onChanged: (value) => update(() {
                            if (value == true) {
                              chosen.add(group.id);
                            } else {
                              chosen.remove(group.id);
                            }
                          }),
                        ),
                    ],
                  ),
                ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialog, false),
            child: Text(strings.cancel),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialog, true),
            child: Text(strings.save),
          ),
        ],
      ),
    ),
  );
  if (yes == true && context.mounted) {
    await shelfAction(
      context,
      () => ref
          .read(bookshelfStateProvider.notifier)
          .assign(book.url, chosen.toList()),
    );
  }
}
