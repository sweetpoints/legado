import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../../l10n/generated/app_localizations.dart';
import 'highlight_manager_bridge.dart';

class HighlightGroupsPage extends StatefulWidget {
  const HighlightGroupsPage({super.key, this.bridge});
  final HighlightBridge? bridge;
  @override
  State<HighlightGroupsPage> createState() => _HighlightGroupsPageState();
}

class _HighlightGroupsPageState extends State<HighlightGroupsPage> {
  late final bridge = widget.bridge ?? HighlightBridge(groups: true);
  final name = TextEditingController();
  final focus = FocusNode();
  StreamSubscription<HighlightSnapshot>? subscription;
  HighlightSnapshot? state;
  int pending = 0;
  bool reflecting = false, failed = false;
  bool get ready =>
      state?.flag('loaded') == true &&
      state?.flag('busy') == false &&
      pending == 0 &&
      !failed;
  @override
  void initState() {
    super.initState();
    name.addListener(changed);
    subscription = bridge.states.listen(
      accept,
      onError: (Object _) {
        if (mounted) setState(() => failed = true);
      },
    );
    bridge.start().catchError((Object _) {
      if (mounted) setState(() => failed = true);
    });
  }

  void accept(HighlightSnapshot s) {
    if (!mounted) return;
    if (pending == 0 && !bridge.rejectedName) {
      final v = TextEditingValue(
        text: s.text('name'),
        selection: TextSelection(
          baseOffset: s.number('nameStart'),
          extentOffset: s.number('nameEnd'),
        ),
      );
      reflecting = true;
      if (name.value != v) name.value = v;
      reflecting = false;
    }
    final focusNow =
        state?.text('stage') != 'Rename' && s.text('stage') == 'Rename';
    setState(() => state = s);
    if (focusNow) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && state?.text('stage') == 'Rename') focus.requestFocus();
      });
    }
  }

  void changed() {
    if (reflecting || !name.selection.isValid) return;
    final value = name.value;
    pending++;
    setState(() {});
    unawaited(() async {
      try {
        await bridge.command('name', {
          'text': value.text,
          'start': value.selection.baseOffset,
          'end': value.selection.extentOffset,
        });
        if (mounted) setState(() => failed = false);
      } catch (_) {
        if (mounted) setState(() => failed = true);
      } finally {
        pending--;
        if (mounted) {
          setState(() {});
          if (pending == 0 && !bridge.rejectedName && bridge.latest != null) {
            accept(bridge.latest!);
          }
        }
      }
    }());
  }

  Future<void> command(
    String op, [
    Map<String, Object?> args = const {},
  ]) async {
    try {
      await bridge.command(op, args);
      if (mounted) setState(() => failed = false);
    } catch (_) {
      if (mounted) setState(() => failed = true);
    }
  }

  Widget tag(String id, Widget child) =>
      Semantics(identifier: id, child: child);
  Widget button(String id, String text, VoidCallback action, {bool? enabled}) =>
      tag(
        id,
        TextButton(
          onPressed: (enabled ?? ready) ? action : null,
          child: Text(text),
        ),
      );
  @override
  Widget build(BuildContext context) {
    final s = state;
    final l = AppLocalizations.of(context);
    if (s == null) {
      return Scaffold(
        body: Center(
          child: failed
              ? Text(l.highlightManagerUnavailable)
              : const CircularProgressIndicator(),
        ),
      );
    }
    String label(String key) => s.label(key);
    final busy = s.flag('busy');
    final stage = s.text('stage');
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (popped, _) {
        if (!popped) unawaited(command('back'));
      },
      child: Scaffold(
        appBar: AppBar(
          title: Text(label('title')),
          automaticallyImplyLeading: false,
          actions: [
            tag(
              'highlight-group-close',
              IconButton(
                onPressed: busy ? null : () => command('back'),
                icon: const Icon(Icons.close),
                tooltip: label('close'),
              ),
            ),
          ],
        ),
        body: Stack(
          children: [
            Column(
              children: [
                if (!s.flag('loaded') || busy)
                  tag(
                    'highlight-group-progress',
                    const LinearProgressIndicator(),
                  ),
                if (failed || s.raw['error'] != null)
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          failed
                              ? l.highlightManagerUnavailable
                              : s.text('error'),
                        ),
                      ),
                      if (stage == 'None')
                        button(
                          'highlight-group-retry',
                          label('retry'),
                          () async {
                            await command('retry');
                            await bridge.refresh();
                          },
                          enabled: !busy,
                        ),
                    ],
                  ),
                Expanded(
                  child: tag(
                    'highlight-group-list',
                    ListView.builder(
                      itemCount: s.strings('groups').length,
                      itemBuilder: (_, i) {
                        final group = s.strings('groups')[i];
                        return Row(
                          key: ValueKey(group),
                          children: [
                            Expanded(
                              child: tag(
                                'highlight-group-label-$group',
                                Text(group),
                              ),
                            ),
                            button(
                              'highlight-group-edit-$group',
                              label('edit'),
                              () => command('rename', {'value': group}),
                            ),
                            button(
                              'highlight-group-delete-$group',
                              label('delete'),
                              () => command('delete', {'value': group}),
                            ),
                          ],
                        );
                      },
                    ),
                  ),
                ),
              ],
            ),
            if (stage != 'None')
              Positioned.fill(
                child: Stack(
                  children: [
                    ModalBarrier(
                      dismissible: !busy,
                      onDismiss: () => command('cancel'),
                      color: Colors.black54,
                    ),
                    Center(
                      child: ConstrainedBox(
                        constraints: const BoxConstraints(
                          maxWidth: 520,
                          maxHeight: 560,
                        ),
                        child: AlertDialog(
                          title: Text(
                            label(
                              stage == 'Rename'
                                  ? 'groupEdit'
                                  : stage == 'Delete'
                                  ? 'groupDelete'
                                  : 'moveGroup',
                            ),
                          ),
                          content: SingleChildScrollView(
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                if (stage == 'Rename')
                                  tag(
                                    'highlight-group-name',
                                    TextField(
                                      controller: name,
                                      minLines: 1,
                                      maxLines: 3,
                                      focusNode: focus,
                                      enabled: !busy,
                                      decoration: InputDecoration(
                                        labelText: label('groupName'),
                                      ),
                                      onSubmitted: (_) {
                                        if (ready) {
                                          unawaited(command('confirmRename'));
                                        }
                                      },
                                    ),
                                  ),
                                if (stage == 'Delete')
                                  Text(label('groupDeleteMessage')),
                                if (stage == 'Move') ...[
                                  for (final group
                                      in s
                                          .strings('groups')
                                          .where((g) => g != s.raw['source']))
                                    button(
                                      'highlight-group-move-$group',
                                      '[$group]',
                                      () => command('move', {'value': group}),
                                    ),
                                  button(
                                    'highlight-group-move-none',
                                    label('noGroup'),
                                    () => command('move', {'value': null}),
                                  ),
                                ],
                                if (s.raw['error'] != null)
                                  Text(s.text('error')),
                              ],
                            ),
                          ),
                          actions: [
                            button(
                              'highlight-group-cancel',
                              label('cancel'),
                              () => command('cancel'),
                              enabled: !busy,
                            ),
                            if (stage == 'Rename')
                              button(
                                'highlight-group-rename-confirm',
                                label('confirm'),
                                () => command('confirmRename'),
                              ),
                            if (stage == 'Delete') ...[
                              button(
                                'highlight-group-choose-move',
                                label('moveGroup'),
                                () => command('chooseMove'),
                              ),
                              button(
                                'highlight-group-delete-confirm',
                                label('delete'),
                                () => command('confirmDelete'),
                              ),
                            ],
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }

  @override
  void dispose() {
    subscription?.cancel();
    unawaited(bridge.close());
    name.dispose();
    focus.dispose();
    super.dispose();
  }
}
