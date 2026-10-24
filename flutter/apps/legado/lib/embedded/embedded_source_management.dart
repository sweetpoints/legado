import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../app/theme.dart';
import '../features/sources/source_management_page.dart';
import '../l10n/generated/app_localizations.dart';
import 'replace_editor/replace_editor_page.dart';
import 'replace_manager/replace_manager_page.dart';
import 'highlight_rule_editor/highlight_rule_editor_page.dart';
import 'highlight_rule_editor/highlight_color_picker_page.dart';
import 'txt_toc_rule_editor/txt_toc_rule_editor_page.dart';
import 'source_management_backend.dart';
import 'source_manager/source_manager_page.dart';
import 'source_editor/source_editor_page.dart';
import 'code_editor/code_editor_page.dart';
import 'source_debugger/source_debugger_page.dart';
import 'rss_source_editor/rss_source_editor_page.dart';
import 'rule_subscriptions/rule_subscription_page.dart';
import 'rss_manager/rss_manager_page.dart';
import 'dictionary_manager/dictionary_manager_page.dart';
import 'rss_favorites/rss_favorites_page.dart';
import 'rss_favorites/rss_favorite_config_page.dart';
import 'dictionary_rule_editor/dictionary_rule_editor_page.dart';
import 'highlight_manager/highlight_manager_page.dart';
import 'highlight_manager/highlight_groups_page.dart';
import 'txt_toc_manager/txt_toc_manager_page.dart';
import 'named_groups/named_groups_page.dart';

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
        '/highlight-manager',
        '/highlight-groups',
        '/txt-toc-manager',
        '/txt-toc-picker',
        '/named-groups',
        '/replace-manager',
        '/highlight-rule-editor',
        '/highlight-rule-color-picker',
        '/txt-toc-rule-editor',
        '/replace-editor',
        '/sources',
        '/source-manager',
        '/source-editor',
        '/code-editor',
        '/source-debugger',
        '/rss-source-editor',
        '/rule-subscriptions',
        '/rss-manager',
        '/dictionary-manager',
        '/rss-favorites',
        '/rss-favorite-config',
        '/dictionary-rule-editor',
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
        path: '/highlight-manager',
        builder: (_, _) => const HighlightManagerPage(),
      ),
      GoRoute(
        path: '/highlight-groups',
        builder: (_, _) => const HighlightGroupsPage(),
      ),
      GoRoute(
        path: '/txt-toc-manager',
        builder: (_, _) => const TxtTocManagerPage(),
      ),
      GoRoute(
        path: '/txt-toc-picker',
        builder: (_, _) => const TxtTocPickerPage(),
      ),
      GoRoute(
        path: '/named-groups',
        builder: (_, _) => const NamedGroupsPage(),
      ),
      GoRoute(
        path: '/replace-manager',
        builder: (_, _) => const ReplaceManagerPage(),
      ),
      GoRoute(
        path: '/highlight-rule-editor',
        builder: (_, _) => const HighlightRuleEditorPage(),
      ),
      GoRoute(
        path: '/highlight-rule-color-picker',
        builder: (_, _) => const HighlightColorPickerPage(),
      ),
      GoRoute(
        path: '/txt-toc-rule-editor',
        builder: (_, _) => const TxtTocRuleEditorPage(),
      ),
      GoRoute(
        path: '/dictionary-rule-editor',
        builder: (_, _) => const DictionaryRuleEditorPage(),
      ),
      GoRoute(
        path: '/dictionary-manager',
        builder: (_, _) => const DictionaryManagerPage(),
      ),
      GoRoute(
        path: '/replace-editor',
        builder: (_, _) => const ReplaceEditorPage(),
      ),
      GoRoute(path: '/rss-manager', builder: (_, _) => const RssManagerPage()),
      GoRoute(
        path: '/rss-favorite-config',
        builder: (_, _) => const RssFavoriteConfigPage(),
      ),
      GoRoute(
        path: '/rss-favorites',
        builder: (_, _) => const RssFavoritesPage(),
      ),
      GoRoute(
        path: '/rule-subscriptions',
        builder: (_, _) => const RuleSubscriptionPage(),
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
