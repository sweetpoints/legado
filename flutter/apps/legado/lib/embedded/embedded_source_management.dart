import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../app/theme.dart';
import '../features/sources/source_management_page.dart';
import '../l10n/generated/app_localizations.dart';
import 'source_management_backend.dart';
import 'source_manager/source_manager_page.dart';
import 'source_editor/source_editor_page.dart';
import 'code_editor/code_editor_page.dart';
import 'source_debugger/source_debugger_page.dart';
import 'rss_source_editor/rss_source_editor_page.dart';
import 'rss_favorites/rss_favorites_page.dart';

part 'embedded_source_management.g.dart';

/// Called once by the add-to-app entrypoint. This is deliberately independent
/// of main.dart: no Drift bootstrap, PDF license loading or V8 initialization.
void runEmbeddedSourceManagement() =>
    runApp(const ProviderScope(child: EmbeddedSourceManagementApp()));

typedef EmbeddedSourceConfiguration = ({
  String route,
  String locale,
  String theme,
});

@riverpod
Future<EmbeddedSourceConfiguration> embeddedSourceConfiguration(Ref ref) async {
  final result = await const MethodChannel('legado/embedded_ui')
      .invokeMapMethod<String, Object?>('bootstrap');
  if (result == null ||
      ![
        '/sources',
        '/source-manager',
        '/source-editor',
        '/code-editor',
        '/source-debugger',
        '/rss-source-editor',
        '/rss-favorites',
      ].contains(result['route']) ||
      !['zh', 'en'].contains(result['locale']) ||
      !['system', 'light', 'dark'].contains(result['theme'])) {
    throw const FormatException('invalid_embedded_bootstrap');
  }
  return (
    route: result['route']! as String,
    locale: result['locale']! as String,
    theme: result['theme']! as String,
  );
}

@riverpod
MethodChannelSourceManagementBackend embeddedSourceBackend(Ref ref) {
  final backend = MethodChannelSourceManagementBackend();
  ref.onDispose(() => unawaited(backend.close()));
  return backend;
}

@riverpod
GoRouter embeddedSourceRouter(Ref ref) {
  final backend = ref.watch(embeddedSourceBackendProvider);
  final configuration = ref
      .watch(embeddedSourceConfigurationProvider)
      .requireValue;
  final router = GoRouter(
    restorationScopeId: 'embedded-router',
    initialLocation: configuration.route,
    routes: [
      GoRoute(
        path: '/rss-favorites',
        builder: (_, _) => const RssFavoritesPage(),
      ),
      GoRoute(
        path: '/rss-source-editor',
        builder: (_, _) => const RssSourceEditorPage(),
      ),
      GoRoute(
        path: '/source-debugger',
        builder: (_, _) => const SourceDebuggerPage(),
      ),
      GoRoute(path: '/code-editor', builder: (_, _) => const CodeEditorPage()),
      GoRoute(
        path: '/source-editor',
        builder: (_, _) => const SourceEditorPage(),
      ),
      GoRoute(
        path: '/source-manager',
        builder: (_, _) => const SourceManagerPage(),
      ),
      GoRoute(
        path: '/sources',
        builder: (_, _) => SourceManagementPage.withBackend(
          backend: backend,
          onClose: backend.closeNavigation,
        ),
      ),
    ],
  );
  ref.onDispose(router.dispose);
  return router;
}

class EmbeddedSourceManagementApp extends ConsumerWidget {
  const EmbeddedSourceManagementApp({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) => ref
      .watch(embeddedSourceConfigurationProvider)
      .when(
        data: (config) => MaterialApp.router(
          restorationScopeId: 'embedded-ui',
          debugShowCheckedModeBanner: false,
          theme: readerTheme(Brightness.light),
          darkTheme: readerTheme(Brightness.dark),
          themeMode: switch (config.theme) {
            'light' => ThemeMode.light,
            'dark' => ThemeMode.dark,
            _ => ThemeMode.system,
          },
          locale: Locale(config.locale),
          localizationsDelegates: [
            AppLocalizations.delegate,
            ...GlobalMaterialLocalizations.delegates,
          ],
          supportedLocales: AppLocalizations.supportedLocales,
          routerConfig: ref.watch(embeddedSourceRouterProvider),
          // go_router uses Flutter Material routes; bridge to material_ui widgets.
          // ignore: deprecated_member_use
          builder: (_, child) => MaterialUiCompatibilityBridge(child: child!),
        ),
        loading: () => _bootstrapShell(context, ref, false),
        error: (_, _) => _bootstrapShell(context, ref, true),
      );

  Widget _bootstrapShell(BuildContext context, WidgetRef ref, bool failed) =>
      MaterialApp(
        restorationScopeId: 'embedded-ui',
        debugShowCheckedModeBanner: false,
        theme: readerTheme(Brightness.light),
        darkTheme: readerTheme(Brightness.dark),
        localizationsDelegates: [
          AppLocalizations.delegate,
          ...GlobalMaterialLocalizations.delegates,
        ],
        supportedLocales: AppLocalizations.supportedLocales,
        home: Builder(
          builder: (context) => Scaffold(
            appBar: AppBar(
              title: Text(AppLocalizations.of(context).sourcesManage),
              leading: BackButton(
                onPressed: () async {
                  try {
                    await const MethodChannel('legado/embedded_ui')
                        .invokeMethod<void>('navigation.close');
                  } catch (e) {
                    if (context.mounted) {
                      ScaffoldMessenger.of(context)
                          .showSnackBar(SnackBar(content: Text(e.toString())));
                    }
                  }
                },
              ),
            ),
            body: Center(
              child: failed
                  ? Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(AppLocalizations.of(context).startupFailed),
                        const SizedBox(height: 16),
                        FilledButton(
                          onPressed: () => ref.invalidate(
                            embeddedSourceConfigurationProvider,
                          ),
                          child: Text(AppLocalizations.of(context).retry),
                        ),
                      ],
                    )
                  : const CircularProgressIndicator(),
            ),
          ),
        ),
      );
}
