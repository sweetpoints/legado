import 'package:flutter/foundation.dart';

import 'package:app_backup/app_backup.dart';
import 'package:app_core/app_core.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';
import 'package:material_ui/material_ui.dart';

import '../../app/file_exchange.dart';
import '../../app/providers.dart';
import '../../l10n/generated/app_localizations.dart';

part 'backup_page.g.dart';

@Riverpod(name: 'backupOperationProvider', keepAlive: true)
class BackupOperationController extends _$BackupOperationController {
  @override
  bool build() => false;
  Future<void> run(Future<void> Function() action) async {
    if (state) return;
    state = true;
    try {
      await action();
    } finally {
      if (ref.mounted) state = false;
    }
  }
}

class BackupPage extends ConsumerWidget {
  const BackupPage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = AppLocalizations.of(context);
    final busy = ref.watch(backupOperationProvider);
    Future<void> guarded(Future<void> Function() action) async {
      try {
        await ref.read(backupOperationProvider.notifier).run(action);
      } catch (error) {
        if (!context.mounted) return;
        final message =
            error is FileExchangeException && error.code == 'backupTooLarge'
            ? strings.backupTooLarge
            : strings.operationFailed;
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(message)));
      }
    }

    return Scaffold(
      appBar: AppBar(title: Text(strings.backupTitle)),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text(strings.backupExplanation),
          const SizedBox(height: 16),
          FilledButton.icon(
            icon: const Icon(Icons.restore),
            label: Text(strings.restoreZip),
            onPressed: busy
                ? null
                : () => guarded(() async {
                    final repository = ref.read(libraryProvider);
                    if (repository is! ArchiveLibraryRepository) {
                      throw StateError('archive_restore_unsupported');
                    }
                    final bytes = await ref
                        .read(fileExchangeProvider)
                        .pickBackup();
                    if (bytes == null) return;
                    final staging = await compute(
                      LegacyBackupArchive.decode,
                      bytes,
                    );
                    if (!context.mounted) return;
                    final confirmed = await showDialog<bool>(
                      context: context,
                      builder: (dialogContext) => AlertDialog(
                        title: Text(strings.restoreTitle),
                        content: Text(
                          '${strings.restoreMessage}\n${strings.bookCount(staging.document.books.length)}',
                        ),
                        actions: [
                          TextButton(
                            onPressed: () =>
                                Navigator.pop(dialogContext, false),
                            child: Text(strings.cancel),
                          ),
                          FilledButton(
                            onPressed: () => Navigator.pop(dialogContext, true),
                            child: Text(strings.confirm),
                          ),
                        ],
                      ),
                    );
                    if (confirmed != true) return;
                    await (repository as ArchiveLibraryRepository)
                        .restoreArchive(
                          staging.document,
                          staging.retainedEntries,
                        );
                    ref.invalidate(bookshelfProvider);
                    ref.invalidate(preferencesProvider);
                    if (context.mounted) {
                      ScaffoldMessenger.of(context).showSnackBar(
                        SnackBar(content: Text(strings.restoreSucceeded)),
                      );
                    }
                  }),
          ),
          const SizedBox(height: 12),
          OutlinedButton.icon(
            icon: const Icon(Icons.archive_outlined),
            label: Text(strings.exportZip),
            onPressed: busy
                ? null
                : () => guarded(() async {
                    final repository = ref.read(libraryProvider);
                    if (repository is! ArchiveLibraryRepository) {
                      throw StateError('archive_export_unsupported');
                    }
                    final snapshot =
                        await (repository as ArchiveLibraryRepository)
                            .backupSnapshot();
                    final bytes = await compute(_encodeSnapshot, snapshot);
                    final saved = await ref
                        .read(fileExchangeProvider)
                        .saveBackup(bytes, 'legado-backup.zip');
                    if (saved && context.mounted) {
                      ScaffoldMessenger.of(context).showSnackBar(
                        SnackBar(content: Text(strings.exportSucceeded)),
                      );
                    }
                  }),
          ),
          if (busy)
            const Padding(
              padding: EdgeInsets.all(24),
              child: Center(child: CircularProgressIndicator()),
            ),
        ],
      ),
    );
  }
}

Uint8List _encodeSnapshot(LibraryBackupSnapshot snapshot) =>
    LegacyBackupArchive.encodeFiles(
      snapshot.files,
      retainedEntries: snapshot.entries.map(
        (key, value) => MapEntry(key, Uint8List.fromList(value)),
      ),
    );
