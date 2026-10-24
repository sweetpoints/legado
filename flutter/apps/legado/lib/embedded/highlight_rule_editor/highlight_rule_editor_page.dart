import 'dart:async';

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../../l10n/generated/app_localizations.dart';
import 'highlight_rule_editor_bridge.dart';

class HighlightRuleEditorPage extends StatefulWidget {
  const HighlightRuleEditorPage({super.key, this.bridge});
  final HighlightRuleEditorBridge? bridge;
  @override
  State<HighlightRuleEditorPage> createState() =>
      _HighlightRuleEditorPageState();
}

class _HighlightRuleEditorPageState extends State<HighlightRuleEditorPage>
    with WidgetsBindingObserver {
  late final bridge = widget.bridge ?? HighlightRuleEditorBridge();
  final controllers = {
    for (final key in highlightFields) key: TextEditingController(),
  };
  final nodes = {for (final key in highlightFields) key: FocusNode()};
  final pending = <String, TextEditingValue>{};
  final scroll = ScrollController();
  StreamSubscription<HighlightEditorSnapshot>? subscription;
  HighlightEditorSnapshot? state;
  bool reflecting = false,
      failed = false,
      restored = false,
      restoring = false,
      scheduled = false,
      focusRequested = false;
  double? restoreTarget;
  String? restoreFocus;
  (double, double, double)? geometry;
  int stableFrames = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    for (final key in highlightFields) {
      controllers[key]!.addListener(() => changed(key));
      nodes[key]!.addListener(() {
        if (nodes[key]!.hasFocus && state?.flag('editable') == true) {
          unawaited(command('focus', {'key': key}));
        }
      });
    }
    scroll.addListener(() {
      if (reflecting || state == null) return;
      if (restoring) {
        scheduleRestore();
      } else if (restored) {
        restoreTarget = null;
        unawaited(command('scroll', {'offset': scroll.offset.round()}));
      }
    });
    subscription = bridge.snapshots.stream.listen(
      accept,
      onError: (Object _) {
        if (mounted) setState(() => failed = true);
      },
    );
    unawaited(() async {
      try {
        await bridge.start();
      } catch (_) {
        if (mounted) setState(() => failed = true);
      }
    }());
  }

  void accept(HighlightEditorSnapshot next) {
    if (!mounted) return;
    reflecting = true;
    for (final key in highlightFields) {
      if (pending.containsKey(key)) continue;
      final controller = controllers[key]!;
      final text = next.text(key),
          selection = TextSelection(
            baseOffset: next.start(key),
            extentOffset: next.end(key),
          );
      if (controller.text != text || controller.selection != selection) {
        controller.value = TextEditingValue(
          text: text,
          selection: selection,
          composing: controller.text == text
              ? controller.value.composing
              : TextRange.empty,
        );
      }
    }
    reflecting = false;
    setState(() {
      state = next;
      failed = bridge.hasRejectedField;
    });
    if (!next.flag('loading') && !restored && restoreTarget == null) {
      restoreTarget = (next.raw['scroll'] as int).toDouble();
      restoreFocus = next.raw['focus'] as String?;
      scheduleRestore();
    }
  }

  @override
  void didChangeMetrics() {
    if (restoreTarget != null) {
      geometry = null;
      stableFrames = 0;
      scheduleRestore();
    }
  }

  void scheduleRestore() {
    if (!mounted || restoreTarget == null || scheduled) return;
    if (!restoring) setState(() => restoring = true);
    scheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      scheduled = false;
      if (!mounted || restoreTarget == null) return;
      if (!scroll.hasClients || !scroll.position.hasContentDimensions) {
        scheduleRestore();
        return;
      }
      if (!focusRequested) {
        focusRequested = true;
        if (restoreFocus != null) nodes[restoreFocus]?.requestFocus();
      }
      final position = scroll.position;
      final current = (
        position.viewportDimension,
        position.maxScrollExtent,
        View.of(context).viewInsets.bottom / View.of(context).devicePixelRatio,
      );
      final target = restoreTarget!.clamp(
        position.minScrollExtent,
        position.maxScrollExtent,
      );
      reflecting = true;
      scroll.jumpTo(target);
      reflecting = false;
      final focused = restoreFocus == null || nodes[restoreFocus]!.hasFocus;
      stableFrames =
          current == geometry && focused && (scroll.offset - target).abs() < .5
          ? stableFrames + 1
          : 0;
      geometry = current;
      if (stableFrames < 2) {
        scheduleRestore();
        return;
      }
      restored = true;
      setState(() => restoring = false);
      if (state?.raw['scroll'] != scroll.offset.round()) {
        unawaited(command('scroll', {'offset': scroll.offset.round()}));
      }
    });
    WidgetsBinding.instance.scheduleFrame();
  }

  void releaseRestore() {
    restoreTarget = null;
    restored = true;
    if (restoring) setState(() => restoring = false);
  }

  void changed(String key) {
    final value = controllers[key]!.value;
    if (reflecting ||
        state?.flag('editable') != true ||
        !value.selection.isValid) {
      return;
    }
    releaseRestore();
    pending[key] = value;
    unawaited(() async {
      try {
        await bridge.command('field', {
          'key': key,
          'text': value.text,
          'start': value.selection.baseOffset,
          'end': value.selection.extentOffset,
        });
        if (mounted && pending[key] == value) {
          pending.remove(key);
          accept(bridge.latest!);
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

  bool confirmingCancel = false;
  Future<void> requestCancel() async {
    if (confirmingCancel ||
        state?.flag('saving') == true ||
        state?.flag('finished') == true) {
      return;
    }
    if (pending.isNotEmpty || bridge.hasRejectedField) {
      confirmingCancel = true;
      final l10n = AppLocalizations.of(context);
      final discard = await showDialog<bool>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          content: Text(l10n.highlightEditorPendingExit),
          actions: [
            tag(
              'exit-keep',
              TextButton(
                onPressed: () => Navigator.of(dialogContext).pop(false),
                child: Text(l10n.highlightEditorKeep),
              ),
            ),
            tag(
              'exit-discard',
              TextButton(
                onPressed: () => Navigator.of(dialogContext).pop(true),
                child: Text(l10n.highlightEditorDiscard),
              ),
            ),
          ],
        ),
      );
      confirmingCancel = false;
      if (!mounted || discard != true) return;
    }
    await command('cancel');
  }

  Widget tag(String id, Widget child) =>
      Semantics(identifier: 'highlight-rule-$id', child: child);
  @override
  Widget build(BuildContext context) {
    final s = state, enabled = s?.flag('editable') == true;
    final actions =
        enabled && pending.isEmpty && !bridge.hasRejectedField && !failed;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop) unawaited(requestCancel());
      },
      child: Scaffold(
        backgroundColor: Colors.transparent,
        body: SafeArea(
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: () => requestCancel(),
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: GestureDetector(
                onTap: () {},
                child: Card(
                  child: Column(
                    children: [
                      Padding(
                        padding: const EdgeInsets.all(16),
                        child: Text(
                          s?.label('heading') ?? '',
                          style: Theme.of(context).textTheme.titleLarge,
                        ),
                      ),
                      if (s == null || s.flag('loading') || s.flag('saving'))
                        const LinearProgressIndicator(),
                      Expanded(
                        child: Listener(
                          onPointerDown: (_) => releaseRestore(),
                          onPointerSignal: (_) => releaseRestore(),
                          child: ListView(
                            key: const ValueKey('highlight-rule-fields'),
                            controller: scroll,
                            physics: restoring ? const _RestorePhysics() : null,
                            padding: const EdgeInsets.all(16),
                            children: [
                              if (failed)
                                Text(
                                  AppLocalizations.of(context)
                                      .highlightEditorBridgeError,
                                  style: TextStyle(
                                    color: Theme.of(context).colorScheme.error,
                                  ),
                                ),
                              if (s?.raw['error'] != null) ...[
                                Text(s!.raw['error'] as String),
                                tag(
                                  'retry',
                                  TextButton(
                                    onPressed:
                                        s.flag('loading') || s.flag('saving')
                                        ? null
                                        : () => command('retry'),
                                    child: Text(s.label('retry')),
                                  ),
                                ),
                              ],
                              for (final key in highlightFields) ...[
                                tag(
                                  key,
                                  TextField(
                                    key: ValueKey('highlight-rule-$key'),
                                    controller: controllers[key],
                                    focusNode: nodes[key],
                                    enabled: enabled,
                                    minLines: 1,
                                    maxLines: key == 'pattern' ? 8 : 3,
                                    decoration: InputDecoration(
                                      labelText: s?.label(key) ?? '',
                                    ),
                                  ),
                                ),
                                const SizedBox(height: 8),
                              ],
                              for (final key in ['regex', 'body', 'title'])
                                tag(
                                  key,
                                  CheckboxListTile(
                                    key: ValueKey('highlight-rule-$key'),
                                    value: s?.flag(key) == true,
                                    onChanged: !actions
                                        ? null
                                        : (value) => command('flags', {
                                            for (final field in [
                                              'regex',
                                              'body',
                                              'title',
                                            ])
                                              field: field == key
                                                  ? value!
                                                  : s!.flag(field),
                                          }),
                                    title: Text(s?.label(key) ?? ''),
                                  ),
                                ),
                              Row(
                                children: [
                                  tag(
                                    'style',
                                    TextButton(
                                      onPressed: actions
                                          ? () => command('style')
                                          : null,
                                      child: Text(s?.label('style') ?? ''),
                                    ),
                                  ),
                                  if (s != null) _preview(s),
                                ],
                              ),
                            ],
                          ),
                        ),
                      ),
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 12),
                        child: Row(
                          mainAxisAlignment: MainAxisAlignment.end,
                          children: [
                            tag(
                              'cancel',
                              TextButton(
                                onPressed:
                                    s?.flag('saving') == true ||
                                        s?.flag('finished') == true
                                    ? null
                                    : () => requestCancel(),
                                child: Text(s?.label('cancel') ?? ''),
                              ),
                            ),
                            tag(
                              'save',
                              TextButton(
                                onPressed: actions
                                    ? () => command('save')
                                    : null,
                                child: Text(s?.label('save') ?? ''),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _preview(HighlightEditorSnapshot state) {
    final preview = state.raw['preview'] as Map;
    final color = preview['textColor'] as int;
    return Flexible(
      child: tag(
        'preview',
        SizedBox(
          width: (preview['width'] as num).toDouble(),
          height: (preview['height'] as num).toDouble(),
          child: Stack(
            alignment: Alignment.center,
            children: [
              Positioned.fill(
                child: Image.memory(
                  preview['png'] as Uint8List,
                  fit: BoxFit.fill,
                  gaplessPlayback: true,
                ),
              ),
              Text(
                state.label('style'),
                style: TextStyle(
                  fontSize: 16,
                  color: color == 0
                      ? Theme.of(context).colorScheme.onSurface
                      : Color(color & 0xffffffff),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    unawaited(subscription?.cancel());
    unawaited(bridge.close());
    for (final c in controllers.values) {
      c.dispose();
    }
    for (final n in nodes.values) {
      n.dispose();
    }
    scroll.dispose();
    super.dispose();
  }
}

class _RestorePhysics extends ClampingScrollPhysics {
  const _RestorePhysics({super.parent});
  @override
  bool get allowImplicitScrolling => false;
  @override
  _RestorePhysics applyTo(ScrollPhysics? ancestor) =>
      _RestorePhysics(parent: buildParent(ancestor));
}
