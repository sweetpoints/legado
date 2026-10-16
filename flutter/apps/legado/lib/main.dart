import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import 'app/providers.dart';
import 'app/router.dart';
import 'app/theme.dart';
import 'l10n/generated/app_localizations.dart';

export 'app/theme.dart';

void main() => runApp(const ProviderScope(child: LegadoApp()));

class LegadoApp extends ConsumerWidget {
  const LegadoApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final library = ref.watch(persistentLibraryProvider);
    return library.when(
      data: (_) => const _ReadyApplication(),
      loading: () => _BootstrapApp(ref: ref),
      error: (error, stack) => _BootstrapApp(ref: ref, failed: true),
    );
  }
}

class _ReadyApplication extends ConsumerWidget {
  const _ReadyApplication();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final preferences = ref.watch(preferencesProvider).asData?.value;
    final tag = preferences?.localeTag ?? '';
    return MaterialApp.router(
      debugShowCheckedModeBanner: false,
      onGenerateTitle: (context) => AppLocalizations.of(context).appTitle,
      routerConfig: ref.watch(appRouterProvider),
      theme: readerTheme(Brightness.light),
      darkTheme: readerTheme(Brightness.dark),
      themeMode: switch (preferences?.themeTag) {
        'light' => ThemeMode.light,
        'dark' => ThemeMode.dark,
        _ => ThemeMode.system,
      },
      locale: tag.isEmpty ? null : Locale(tag),
      supportedLocales: AppLocalizations.supportedLocales,
      localizationsDelegates: [
        AppLocalizations.delegate,
        ...GlobalMaterialLocalizations.delegates,
      ],
      // go_router still creates legacy Material pages; bridge their theme/localizations.
      // ignore: deprecated_member_use
      builder: (context, child) => MaterialUiCompatibilityBridge(child: child!),
    );
  }
}

class _BootstrapApp extends StatelessWidget {
  const _BootstrapApp({required this.ref, this.failed = false});
  final WidgetRef ref;
  final bool failed;
  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    theme: readerTheme(Brightness.light),
    darkTheme: readerTheme(Brightness.dark),
    supportedLocales: AppLocalizations.supportedLocales,
    localizationsDelegates: [
      AppLocalizations.delegate,
      ...GlobalMaterialLocalizations.delegates,
    ],
    home: Builder(
      builder: (context) {
        final strings = AppLocalizations.of(context);
        return Scaffold(
          body: Center(
            child: failed
                ? Padding(
                    padding: const EdgeInsets.all(24),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(strings.startupFailed),
                        const SizedBox(height: 16),
                        FilledButton(
                          onPressed: () =>
                              ref.invalidate(persistentLibraryProvider),
                          child: Text(strings.retry),
                        ),
                      ],
                    ),
                  )
                : CircularProgressIndicator(semanticsLabel: strings.loading),
          ),
        );
      },
    ),
  );
}
