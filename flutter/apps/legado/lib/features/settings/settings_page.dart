import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import '../../app/providers.dart';
import '../../l10n/generated/app_localizations.dart';

class SettingsPage extends ConsumerWidget {
  const SettingsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = AppLocalizations.of(context);
    final preferences = ref.watch(preferencesProvider);
    return Scaffold(
      appBar: AppBar(title: Text(strings.settingsTitle)),
      body: preferences.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => Center(
          child: FilledButton(
            onPressed: () => ref.invalidate(preferencesProvider),
            child: Text(strings.retry),
          ),
        ),
        data: (value) => ListView(
          children: [
            ListTile(
              title: Text(strings.languageTitle),
              trailing: DropdownButton<String>(
                value: {'', 'en', 'zh'}.contains(value.localeTag)
                    ? value.localeTag
                    : '',
                items: [
                  DropdownMenuItem(
                    value: '',
                    child: Text(strings.systemLanguage),
                  ),
                  DropdownMenuItem(
                    value: 'zh',
                    child: Text(strings.chineseLanguage),
                  ),
                  DropdownMenuItem(
                    value: 'en',
                    child: Text(strings.englishLanguage),
                  ),
                ],
                onChanged: (tag) => _save(
                  context,
                  () => ref.read(preferencesProvider.notifier).setLocale(tag!),
                ),
              ),
            ),
            ListTile(
              title: Text(strings.appearanceTitle),
              trailing: DropdownButton<String>(
                value: {'system', 'light', 'dark'}.contains(value.themeTag)
                    ? value.themeTag
                    : 'system',
                items: [
                  DropdownMenuItem(
                    value: 'system',
                    child: Text(strings.systemTheme),
                  ),
                  DropdownMenuItem(
                    value: 'light',
                    child: Text(strings.lightTheme),
                  ),
                  DropdownMenuItem(
                    value: 'dark',
                    child: Text(strings.darkTheme),
                  ),
                ],
                onChanged: (tag) => _save(
                  context,
                  () => ref.read(preferencesProvider.notifier).setTheme(tag!),
                ),
              ),
            ),
            ListTile(
              leading: const Icon(Icons.settings_backup_restore),
              title: Text(strings.backupTitle),
              onTap: () => context.push('/backup'),
            ),
            ListTile(
              leading: const Icon(Icons.source),
              title: Text(strings.sourcesTitle),
              onTap: () => context.push('/sources'),
            ),
            ListTile(
              leading: const Icon(Icons.policy_outlined),
              title: Text(strings.licensesTitle),
              onTap: () => showLicensePage(
                context: context,
                applicationName: strings.appTitle,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _save(
    BuildContext context,
    Future<void> Function() action,
  ) async {
    try {
      await action();
    } catch (_) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(AppLocalizations.of(context).operationFailed)),
        );
      }
    }
  }
}
