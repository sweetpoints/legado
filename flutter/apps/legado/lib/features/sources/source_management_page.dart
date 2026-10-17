import 'dart:convert';

import 'package:app_core/app_core.dart';
import 'package:material_ui/material_ui.dart';

import '../../l10n/generated/app_localizations.dart';

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../services/book_source_service.dart';
import '../../embedded/source_management_backend.dart';

class SourceManagementPage extends ConsumerWidget {
  const SourceManagementPage({
    super.key,
    required LibraryRepository this.repository,
  }) : backend = null,
       onClose = null;
  const SourceManagementPage.withBackend({
    super.key,
    required SourceManagementBackend this.backend,
    this.onClose,
  }) : repository = null;
  final LibraryRepository? repository;
  final SourceManagementBackend? backend;
  final Future<void> Function()? onClose;
  SourceManagementBinding get _binding =>
      (repository: repository, backend: backend);

  Future<void> _edit(
    BuildContext context,
    WidgetRef ref, [
    Map<String, Object?>? source,
  ]) async {
    final service = ref.read(sourceManagementBackendProvider(_binding));
    final input = TextEditingController(
      text: source == null ? '' : await _encode(source),
    );
    if (!context.mounted) {
      input.dispose();
      return;
    }
    String? error;
    bool saving = false;
    bool dialogOpen = true;
    await showDialog<void>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setState) => AlertDialog(
          title: Text(
            source == null
                ? AppLocalizations.of(context).sourcesImportJson
                : AppLocalizations.of(context).sourcesEdit,
          ),
          content: SizedBox(
            width: 720,
            child: TextField(
              controller: input,
              minLines: 8,
              maxLines: 20,
              decoration: InputDecoration(
                hintText: AppLocalizations.of(context).sourcesJsonHint,
                errorText: error,
                errorMaxLines: 4,
                border: const OutlineInputBorder(),
              ),
            ),
          ),
          actions: [
            TextButton(
              onPressed: saving ? null : () => context.pop(),
              child: Text(AppLocalizations.of(context).sourcesCancel),
            ),
            FilledButton(
              onPressed: saving
                  ? null
                  : () async {
                      setState(() {
                        saving = true;
                        error = null;
                      });
                      try {
                        final parsed = BookSourceService.parseImport(
                          input.text,
                        );
                        if (source != null &&
                            (parsed.length != 1 ||
                                BookSourceService.sourceId(parsed.single) !=
                                    BookSourceService.sourceId(source))) {
                          throw FormatException(
                            AppLocalizations.of(context)
                                .sourcesIdentityImmutable,
                          );
                        }
                        if (source == null) {
                          await service.importJson(input.text);
                        } else {
                          await service.update(
                            input.text,
                            expectedId: BookSourceService.sourceId(source),
                          );
                        }
                        if (dialogOpen &&
                            context.mounted &&
                            (ModalRoute.of(context)?.isCurrent ?? false)) {
                          context.pop();
                        }
                      } catch (e) {
                        if (dialogOpen &&
                            context.mounted &&
                            (ModalRoute.of(context)?.isCurrent ?? false)) {
                          setState(() {
                            saving = false;
                            error = e.toString();
                          });
                        }
                      }
                    },
              child: Text(AppLocalizations.of(context).sourcesSave),
            ),
          ],
        ),
      ),
    ).whenComplete(() => dialogOpen = false);
    // Dialog route may animate after pop; dispose on a later frame.
    WidgetsBinding.instance.addPostFrameCallback((_) => input.dispose());
  }

  Future<String> _encode(Map<String, Object?> source) async {
    // Encoding is lossless for unknown fields and opaque source IDs.
    return sourceJson(source);
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final sources = ref.watch(sourceManagementListProvider(_binding));
    final service = ref.watch(sourceManagementBackendProvider(_binding));
    return Scaffold(
      appBar: AppBar(
        leading: onClose == null
            ? null
            : BackButton(
                onPressed: () async {
                  try {
                    await onClose!();
                  } catch (e) {
                    if (context.mounted) _error(context, e);
                  }
                },
              ),
        title: Text(AppLocalizations.of(context).sourcesManage),
        actions: [
          IconButton(
            tooltip: AppLocalizations.of(context).sourcesCopy,
            icon: const Icon(Icons.copy_all),
            onPressed: () async {
              try {
                await Clipboard.setData(
                  ClipboardData(text: await service.exportJson()),
                );
                if (context.mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    SnackBar(
                      content: Text(AppLocalizations.of(context).sourcesCopied),
                    ),
                  );
                }
              } catch (e) {
                if (context.mounted) _error(context, e);
              }
            },
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _edit(context, ref),
        icon: const Icon(Icons.add),
        label: Text(AppLocalizations.of(context).sourcesImport),
      ),
      body: sources.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(AppLocalizations.of(context).startupFailed),
                const SizedBox(height: 12),
                SelectableText(e.toString()),
                const SizedBox(height: 12),
                FilledButton(
                  onPressed: () =>
                      ref.invalidate(sourceManagementListProvider(_binding)),
                  child: Text(AppLocalizations.of(context).retry),
                ),
              ],
            ),
          ),
        ),
        data: (items) => items.isEmpty
            ? Center(child: Text(AppLocalizations.of(context).sourcesEmpty))
            : ListView.builder(
                padding: const EdgeInsets.only(bottom: 96),
                itemCount: items.length,
                itemBuilder: (context, index) {
                  final source = items[index];
                  final id = BookSourceService.sourceId(source);
                  return ListTile(
                    title: Text(
                      source['bookSourceName']?.toString() ??
                          source['name']?.toString() ??
                          id,
                    ),
                    subtitle: Text(
                      id,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                    onTap: () => _edit(context, ref, source),
                    trailing: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Switch(
                          value: source['enabled'] != false,
                          onChanged: (value) async {
                            try {
                              await service.setEnabled(source, value);
                            } catch (e) {
                              if (context.mounted) _error(context, e);
                            }
                          },
                        ),
                        IconButton(
                          tooltip: AppLocalizations.of(context).sourcesDelete,
                          icon: const Icon(Icons.delete_outline),
                          onPressed: () async {
                            final confirmed = await showDialog<bool>(
                              context: context,
                              builder: (context) => AlertDialog(
                                title: Text(
                                  AppLocalizations.of(context)
                                      .sourcesDeleteConfirm,
                                ),
                                content: Text(
                                  source['bookSourceName']?.toString() ?? id,
                                ),
                                actions: [
                                  TextButton(
                                    onPressed: () => context.pop(false),
                                    child: Text(
                                      AppLocalizations.of(context)
                                          .sourcesCancel,
                                    ),
                                  ),
                                  FilledButton(
                                    onPressed: () => context.pop(true),
                                    child: Text(
                                      AppLocalizations.of(context)
                                          .sourcesDeleteAction,
                                    ),
                                  ),
                                ],
                              ),
                            );
                            if (confirmed == true) {
                              try {
                                await service.deleteSource(id);
                              } catch (e) {
                                if (context.mounted) _error(context, e);
                              }
                            }
                          },
                        ),
                      ],
                    ),
                  );
                },
              ),
      ),
    );
  }

  void _error(BuildContext context, Object error) =>
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(error.toString())));
}

String sourceJson(Map<String, Object?> source) =>
    const JsonEncoder.withIndent('  ').convert(source);
