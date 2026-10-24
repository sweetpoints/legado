import 'dart:async';

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../../l10n/generated/app_localizations.dart';
import 'named_groups_bridge.dart';

class NamedGroupsPage extends StatefulWidget {
  const NamedGroupsPage({super.key, this.bridge});
  final NamedGroupsBridge? bridge;
  @override
  State<NamedGroupsPage> createState() => _NamedGroupsState();
}

class _NamedGroupsState extends State<NamedGroupsPage> {
  late final bridge = widget.bridge ?? NamedGroupsBridge();
  final controller = TextEditingController();
  final focus = FocusNode();
  NamedGroupsSnapshot? state;
  StreamSubscription<NamedGroupsSnapshot>? subscription;
  TextEditingValue? pending;
  bool reflecting = false, failed = false, endingEdit = false;
  @override
  void initState() {
    super.initState();
    controller.addListener(changed);
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

  void accept(NamedGroupsSnapshot next) {
    if (!mounted) return;
    final newlyEditing =
        next.editing &&
        (state?.editing != true || state?.generation != next.generation);
    if (state?.generation != next.generation || !next.editing) pending = null;
    if (pending == null) {
      reflecting = true;
      final value = TextEditingValue(
        text: next.name,
        selection: TextSelection(
          baseOffset: next.start,
          extentOffset: next.end,
        ),
      );
      if (controller.value != value) controller.value = value;
      reflecting = false;
    }
    setState(() {
      state = next;
      failed = bridge.hasRejectedField;
    });
    if (newlyEditing) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && state?.editing == true && state?.busy == false) {
          focus.requestFocus();
        }
      });
    }
  }

  void changed() {
    final value = controller.value;
    if (reflecting ||
        endingEdit ||
        state?.editing != true ||
        state?.busy == true ||
        !value.selection.isValid) {
      return;
    }
    pending = value;
    unawaited(() async {
      try {
        await bridge.command('field', {
          'text': value.text,
          'start': value.selection.baseOffset,
          'end': value.selection.extentOffset,
        });
        if (mounted && pending == value) {
          pending = null;
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
    final endsEdit = ['cancel', 'back', 'confirm'].contains(op);
    if (endsEdit) {
      endingEdit = true;
    }
    try {
      if (op == 'back' && bridge.latest == null) {
        await const MethodChannel('legado/embedded_ui')
            .invokeMethod<void>('navigation.close');
      } else {
        await bridge.command(op, args);
      }
    } catch (_) {
      if (mounted) setState(() => failed = true);
    } finally {
      if (endsEdit) {
        endingEdit = false;
      }
    }
  }

  Widget tag(String id, Widget child) =>
      Semantics(identifier: 'named-group-$id', child: child);
  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final s = state;
    final available =
        s != null &&
        !s.loading &&
        !s.busy &&
        !s.editing &&
        !s.recoveryFailed &&
        !s.mutationUncertain;
    // This page belongs only to NamedGroupDialog's floating, non-fullscreen
    // Android window. That host keeps adjustResize and already fits its frame
    // above the IME. Consume its bottom inset here once; a retained embedding
    // inset must not shrink that already-local viewport again (including Dialog).
    // This is not a global keyboard policy or a standalone/fullscreen host policy.
    return MediaQuery.removeViewInsets(
      context: context,
      removeBottom: true,
      child: PopScope(
        canPop: false,
        onPopInvokedWithResult: (didPop, _) {
          if (!didPop) unawaited(command('back'));
        },
        child: Scaffold(
          resizeToAvoidBottomInset: false,
          backgroundColor: Colors.transparent,
          body: SafeArea(
            child: Stack(
              children: [
                Positioned.fill(
                  child: tag(
                    'backdrop',
                    GestureDetector(
                      behavior: HitTestBehavior.opaque,
                      onTap: s?.busy == true
                          ? null
                          : () => unawaited(command('back')),
                      child: const ColoredBox(color: Colors.transparent),
                    ),
                  ),
                ),
                Center(
                  child: LayoutBuilder(
                    builder: (context, constraints) => SizedBox(
                      width: constraints.maxWidth * .9,
                      height: constraints.maxHeight * .9,
                      child: Material(
                        color: Theme.of(context).colorScheme.surface,
                        borderRadius: BorderRadius.circular(16),
                        clipBehavior: Clip.antiAlias,
                        child: Column(
                          children: [
                            Row(
                              children: [
                                tag(
                                  'close',
                                  IconButton(
                                    onPressed: s?.busy == true
                                        ? null
                                        : () => unawaited(command('back')),
                                    tooltip: l.close,
                                    icon: const Icon(Icons.close),
                                  ),
                                ),
                                Expanded(
                                  child: Text(
                                    l.namedGroupsTitle,
                                    style: Theme.of(context)
                                        .textTheme
                                        .titleLarge,
                                  ),
                                ),
                                tag(
                                  'add',
                                  TextButton(
                                    onPressed: available
                                        ? () => unawaited(command('add'))
                                        : null,
                                    child: Text(l.namedGroupsAdd),
                                  ),
                                ),
                              ],
                            ),
                            if (s == null || s.loading || s.busy)
                              const LinearProgressIndicator(),
                            if (failed || s?.error != null)
                              tag(
                                'error',
                                Padding(
                                  padding: const EdgeInsets.all(12),
                                  child: Text(s?.error ?? l.namedGroupsError),
                                ),
                              ),
                            if ((failed || s?.error != null) &&
                                s?.editing != true)
                              tag(
                                'retry',
                                TextButton(
                                  onPressed: s?.busy == true
                                      ? null
                                      : () => unawaited(command('retry')),
                                  child: Text(l.namedGroupsRetry),
                                ),
                              ),
                            Expanded(
                              child: tag(
                                'list',
                                ListView.builder(
                                  itemCount: s?.groups.length ?? 0,
                                  findChildIndexCallback: (key) {
                                    if (key is! ValueKey<String> || s == null) {
                                      return null;
                                    }
                                    final i = s.groups.indexWhere(
                                      (g) => g.id == key.value,
                                    );
                                    return i < 0 ? null : i;
                                  },
                                  itemBuilder: (context, i) {
                                    final row = s!.groups[i];
                                    return ListTile(
                                      key: ValueKey(row.id),
                                      title: Text(row.name),
                                      trailing: Row(
                                        mainAxisSize: MainAxisSize.min,
                                        children: [
                                          tag(
                                            'edit-${row.id}',
                                            TextButton(
                                              onPressed: available
                                                  ? () => unawaited(
                                                      command('edit', {
                                                        'id': row.id,
                                                      }),
                                                    )
                                                  : null,
                                              child: Text(l.namedGroupsEdit),
                                            ),
                                          ),
                                          tag(
                                            'delete-${row.id}',
                                            TextButton(
                                              onPressed: available
                                                  ? () => unawaited(
                                                      command('delete', {
                                                        'id': row.id,
                                                      }),
                                                    )
                                                  : null,
                                              child: Text(l.namedGroupsDelete),
                                            ),
                                          ),
                                        ],
                                      ),
                                    );
                                  },
                                ),
                              ),
                            ),
                            if (s?.showDone == true)
                              Align(
                                alignment: Alignment.centerRight,
                                child: tag(
                                  'done',
                                  TextButton(
                                    onPressed: s?.busy == true
                                        ? null
                                        : () => unawaited(command('back')),
                                    child: Text(l.namedGroupsConfirm),
                                  ),
                                ),
                              ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
                if (s?.editing == true) ...[
                  Positioned.fill(
                    child: ModalBarrier(
                      dismissible: !s!.busy,
                      onDismiss: () => unawaited(command('cancel')),
                      color: Colors.black26,
                    ),
                  ),
                  Center(
                    child: AlertDialog(
                      scrollable: true,
                      title: Text(
                        s.original == null
                            ? l.namedGroupsAdd
                            : l.namedGroupsEdit,
                      ),
                      content: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          tag(
                            'name',
                            TextField(
                              controller: controller,
                              focusNode: focus,
                              enabled:
                                  !s.busy &&
                                  !s.recoveryFailed &&
                                  !s.mutationUncertain,
                              decoration: InputDecoration(
                                labelText: l.namedGroupsName,
                              ),
                              textInputAction: TextInputAction.done,
                              onSubmitted: (_) => unawaited(command('confirm')),
                            ),
                          ),
                          if (failed || s.error != null)
                            Text(s.error ?? l.namedGroupsError),
                        ],
                      ),
                      actions: [
                        tag(
                          'cancel',
                          TextButton(
                            onPressed: s.busy
                                ? null
                                : () => unawaited(command('cancel')),
                            child: Text(l.namedGroupsCancel),
                          ),
                        ),
                        tag(
                          'confirm',
                          TextButton(
                            onPressed:
                                s.busy ||
                                    s.recoveryFailed ||
                                    s.mutationUncertain
                                ? null
                                : () => unawaited(command('confirm')),
                            child: Text(l.namedGroupsConfirm),
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }

  @override
  void dispose() {
    unawaited(subscription?.cancel());
    unawaited(bridge.close());
    controller.dispose();
    focus.dispose();
    super.dispose();
  }
}
