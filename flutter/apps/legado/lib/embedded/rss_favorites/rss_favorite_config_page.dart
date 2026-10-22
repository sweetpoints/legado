import 'dart:async';

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../../l10n/generated/app_localizations.dart';
import 'rss_favorite_config_bridge.dart';

class RssFavoriteConfigPage extends StatefulWidget {
  const RssFavoriteConfigPage({super.key, this.bridge});
  final RssFavoriteConfigBridge? bridge;
  @override
  State<RssFavoriteConfigPage> createState() => _ConfigPageState();
}

class _ConfigPageState extends State<RssFavoriteConfigPage> {
  late final bridge = widget.bridge ?? RssFavoriteConfigBridge();
  final title = TextEditingController();
  final group = TextEditingController();
  final titleFocus = FocusNode();
  final groupFocus = FocusNode();
  StreamSubscription<FavoriteConfigSnapshot>? subscription;
  FavoriteConfigSnapshot? state;
  final pending = <String, TextEditingValue>{};
  bool reflecting = false, failed = false, focused = false;
  @override
  void initState() {
    super.initState();
    title.addListener(() => changed('title', title));
    group.addListener(() => changed('group', group));
    titleFocus.addListener(() => focus('title', titleFocus));
    groupFocus.addListener(() => focus('group', groupFocus));
    subscription = bridge.snapshots.stream.listen(
      accept,
      onError: (Object _) {
        if (mounted) setState(() => failed = true);
      },
    );
    unawaited(start());
  }

  Future<void> start() async {
    try {
      await bridge.start();
    } catch (_) {
      if (mounted) setState(() => failed = true);
    }
  }

  void accept(FavoriteConfigSnapshot next) {
    if (!mounted) return;
    reflecting = true;
    void reflect(
      String key,
      TextEditingController controller,
      String text,
      int start,
      int end,
    ) {
      if (!pending.containsKey(key)) {
        controller.value = TextEditingValue(
          text: text,
          selection: TextSelection(
            baseOffset: start.clamp(0, text.length),
            extentOffset: end.clamp(0, text.length),
          ),
          composing: controller.text == text
              ? controller.value.composing
              : TextRange.empty,
        );
      }
    }

    reflect('title', title, next.title, next.titleStart, next.titleEnd);
    reflect('group', group, next.group, next.groupStart, next.groupEnd);
    reflecting = false;
    setState(() {
      state = next;
      failed = bridge.hasRejectedField;
    });
    if (next.loaded && next.canEdit && !focused) {
      focused = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && state?.canEdit == true) {
          (next.focus == 'group' ? groupFocus : titleFocus).requestFocus();
        }
      });
    }
  }

  void focus(String key, FocusNode node) {
    if (node.hasFocus && state?.canEdit == true) {
      unawaited(command('focus', {'key': key}));
    }
  }

  void changed(String key, TextEditingController controller) {
    if (reflecting || state?.canEdit != true || !controller.selection.isValid) {
      return;
    }
    final value = controller.value;
    pending[key] = value;
    unawaited(() async {
      try {
        await bridge.command('field', {
          'key': key,
          'text': value.text,
          'start': value.selection.start,
          'end': value.selection.end,
        });
        if (mounted && pending[key] == value) {
          pending.remove(key);
          setState(() => failed = bridge.hasRejectedField);
        }
      } catch (_) {
        if (mounted) setState(() => failed = true);
      }
    }());
  }

  Future<void> command(
    String op, [
    Map<String, Object?> args = const {},
  ]) async {
    try {
      if (op == 'cancel' && bridge.latest == null) {
        await const MethodChannel('legado/embedded_ui')
            .invokeMethod<void>('navigation.close');
      } else {
        await bridge.command(op, args);
      }
    } catch (_) {
      if (mounted) setState(() => failed = true);
    }
  }

  @override
  void dispose() {
    unawaited(subscription?.cancel());
    unawaited(bridge.close());
    title.dispose();
    group.dispose();
    titleFocus.dispose();
    groupFocus.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final s = state;
    final editable = s?.canEdit == true;
    final cancelable = s?.busy != true;
    Widget tagged(String id, Widget child) =>
        Semantics(identifier: id, container: true, child: child);
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop && cancelable) unawaited(command('cancel'));
      },
      child: Scaffold(
        backgroundColor: Colors.transparent,
        resizeToAvoidBottomInset: true,
        body: SafeArea(
          child: Stack(
            children: [
              Positioned.fill(
                child: tagged(
                  'favorite-config-outside',
                  GestureDetector(
                    behavior: HitTestBehavior.opaque,
                    onTap: cancelable
                        ? () => unawaited(command('cancel'))
                        : null,
                    child: const ColoredBox(color: Colors.transparent),
                  ),
                ),
              ),
              Center(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: ConstrainedBox(
                    constraints: BoxConstraints(
                      maxHeight: MediaQuery.sizeOf(context).height * .85,
                    ),
                    child: Material(
                      color: Theme.of(context).colorScheme.surface,
                      borderRadius: BorderRadius.circular(16),
                      clipBehavior: Clip.antiAlias,
                      child: SingleChildScrollView(
                        child: Padding(
                          padding: const EdgeInsets.all(16),
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              tagged(
                                'favorite-config-heading',
                                Text(
                                  l.rssFavoriteConfigHeading,
                                  style: Theme.of(context).textTheme.titleLarge,
                                ),
                              ),
                              if (s == null || s.loading || s.busy)
                                const LinearProgressIndicator(),
                              if (failed || s?.error == true)
                                tagged(
                                  'favorite-config-error',
                                  Text(l.rssFavoritesError),
                                ),
                              if (s == null || (!s.loaded && !s.loading))
                                tagged(
                                  'favorite-config-retry',
                                  TextButton(
                                    onPressed: () => unawaited(
                                      s == null ? start() : command('retry'),
                                    ),
                                    child: Text(l.retry),
                                  ),
                                ),
                              const SizedBox(height: 12),
                              tagged(
                                'favorite-config-title',
                                TextField(
                                  controller: title,
                                  focusNode: titleFocus,
                                  enabled: editable,
                                  maxLines: 4,
                                  minLines: 1,
                                  textInputAction: TextInputAction.next,
                                  onEditingComplete: () {},
                                  onSubmitted: (_) => groupFocus.requestFocus(),
                                  decoration: InputDecoration(
                                    labelText: l.rssFavoriteConfigTitle,
                                    border: const OutlineInputBorder(),
                                  ),
                                ),
                              ),
                              const SizedBox(height: 12),
                              tagged(
                                'favorite-config-group',
                                TextField(
                                  controller: group,
                                  focusNode: groupFocus,
                                  enabled: editable,
                                  maxLines: 4,
                                  minLines: 1,
                                  textInputAction: TextInputAction.done,
                                  onEditingComplete: () {},
                                  onSubmitted: (_) => unawaited(
                                    SystemChannels.textInput.invokeMethod<void>(
                                      'TextInput.hide',
                                    ),
                                  ),
                                  decoration: InputDecoration(
                                    labelText: l.rssFavoriteConfigGroup,
                                    border: const OutlineInputBorder(),
                                  ),
                                ),
                              ),
                              Wrap(
                                alignment: WrapAlignment.spaceBetween,
                                children: [
                                  tagged(
                                    'favorite-config-delete',
                                    TextButton(
                                      onPressed: editable
                                          ? () => unawaited(command('delete'))
                                          : null,
                                      child: Text(l.delete),
                                    ),
                                  ),
                                  tagged(
                                    'favorite-config-cancel',
                                    TextButton(
                                      onPressed: cancelable
                                          ? () => unawaited(command('cancel'))
                                          : null,
                                      child: Text(l.cancel),
                                    ),
                                  ),
                                  tagged(
                                    'favorite-config-confirm',
                                    TextButton(
                                      onPressed: editable
                                          ? () => unawaited(command('save'))
                                          : null,
                                      child: Text(l.rssFavoriteConfigConfirm),
                                    ),
                                  ),
                                ],
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
