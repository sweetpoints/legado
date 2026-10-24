import 'dart:async';

import 'package:material_ui/material_ui.dart';

import 'replace_manager_bridge.dart';

class ReplaceManagerPage extends StatefulWidget {
  const ReplaceManagerPage({super.key, this.bridge});
  final ReplaceManagerBridge? bridge;
  @override
  State<ReplaceManagerPage> createState() => _ReplaceManagerPageState();
}

class _ReplaceManagerPageState extends State<ReplaceManagerPage>
    with WidgetsBindingObserver {
  late final bridge = widget.bridge ?? ReplaceManagerBridge();
  final query = TextEditingController(), draft = TextEditingController();
  final scroll = ScrollController();
  final viewport = GlobalKey();
  StreamSubscription<ReplaceManagerSnapshot>? subscription;
  ReplaceManagerSnapshot? state;
  final pending = <String, int>{};
  bool reflecting = false, failed = false, restored = false;
  static const rowExtent = 80.0;
  int? anchor;
  bool selecting = false, finishing = false, gestureFailed = false;
  int gestureToken = 0;
  Offset? pointer;
  Timer? timer;
  Future<void>? gestureFlight;
  Future<void>? gestureStart;
  (int, bool)? lastTarget;
  Future<void>? scrollFlight;
  (int, int)? scrollPending;
  bool get editable =>
      state?.flag('loaded') == true &&
      state?.flag('busy') == false &&
      state?.flag('pending') == false;
  bool get actionsEnabled =>
      editable && pending.values.every((n) => n == 0) && !failed;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    query.addListener(() => changed('query', query));
    draft.addListener(() => changed('draft', draft));
    subscription = bridge.states.listen(
      accept,
      onError: (Object _) {
        if (mounted) setState(() => failed = true);
      },
    );
    bridge.start().catchError((Object _) {
      if (mounted) setState(() => failed = true);
    });
    scroll.addListener(persistScroll);
  }

  void accept(ReplaceManagerSnapshot next) {
    if (!mounted) return;
    reflecting = true;
    for (final entry in {'query': query, 'draft': draft}.entries) {
      if ((pending[entry.key] ?? 0) != 0 || bridge.rejectedField == entry.key) {
        continue;
      }
      final value = TextEditingValue(
        text: next.text(entry.key),
        selection: TextSelection(
          baseOffset: next.number('${entry.key}Start'),
          extentOffset: next.number('${entry.key}End'),
        ),
      );
      if (entry.value.value != value) entry.value.value = value;
    }
    reflecting = false;
    setState(() => state = next);
    if (!restored && next.flag('loaded') && next.rows.isNotEmpty) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted || !scroll.hasClients || restored) return;
        final index = next.number('scrollIndex').clamp(0, next.rows.length - 1);
        final target =
            index * rowExtent +
            next.number('scrollOffset') / View.of(context).devicePixelRatio;
        scroll.jumpTo(target.clamp(0, scroll.position.maxScrollExtent));
        restored = true;
      });
    }
  }

  void changed(String key, TextEditingController controller) {
    if (reflecting || !controller.selection.isValid) return;
    final value = controller.value;
    pending[key] = (pending[key] ?? 0) + 1;
    setState(() {});
    unawaited(() async {
      try {
        await bridge.command(key, {
          'text': value.text,
          'start': value.selection.baseOffset,
          'end': value.selection.extentOffset,
        });
        if (mounted) setState(() => failed = false);
      } catch (_) {
        if (mounted) setState(() => failed = true);
      } finally {
        pending[key] = pending[key]! - 1;
        if (mounted && pending[key] == 0 && !failed && bridge.latest != null) {
          accept(bridge.latest!);
        }
      }
    }());
  }

  Future<void> command(
    String op, [
    Map<String, Object?> values = const {},
  ]) async {
    try {
      // A modal cancel is also an explicit discard of a rejected background filter.
      // Its own native cancel ACK must succeed before cancelling the modal.
      if (op == 'cancelDialog' && bridge.rejectedField == 'query') {
        await bridge.command('cancelQuery');
      }
      await bridge.command(op, values);
      if (mounted) setState(() => failed = false);
    } catch (_) {
      if (mounted) setState(() => failed = true);
    }
  }

  Future<void> effect(String action, [int? id]) =>
      command('effect', {'action': action, 'id': id});
  Future<void> dialog(String kind, List<int> ids) =>
      command('dialog', {'kind': kind, 'ids': ids});
  void persistScroll() {
    if (!mounted ||
        !restored ||
        !scroll.hasClients ||
        state?.flag('loaded') != true) {
      return;
    }
    final index = (scroll.offset / rowExtent).floor().clamp(
      0,
      (state!.rows.length - 1).clamp(0, 0x7fffffff),
    );
    scrollPending = (
      index,
      ((scroll.offset - index * rowExtent) * View.of(context).devicePixelRatio)
          .round()
          .clamp(0, 0x7fffffff),
    );
    if (scrollFlight != null) return;
    final flight = () async {
      while (mounted && scrollPending != null) {
        final value = scrollPending!;
        scrollPending = null;
        await command('scroll', {'index': value.$1, 'offset': value.$2});
      }
    }();
    scrollFlight = flight;
    unawaited(flight.whenComplete(() => scrollFlight = null));
  }

  Future<void> startGesture(int id, Offset position, bool selection) async {
    if (!actionsEnabled || anchor != null) return;
    anchor = id;
    selecting = selection;
    finishing = false;
    gestureFailed = false;
    lastTarget = null;
    final token = ++gestureToken;
    final starting = bridge.command(
      selection ? 'beginSelection' : 'beginDrag',
      selection ? const {} : {'id': id},
    );
    gestureStart = starting;
    try {
      await starting;
    } catch (_) {
      gestureFailed = true;
    } finally {
      if (gestureStart == starting) gestureStart = null;
    }
    if (!mounted || anchor == null || token != gestureToken || finishing) {
      return;
    }
    if (gestureFailed) {
      await finishGesture(true);
      return;
    }
    pointer = position;
    timer = Timer.periodic(const Duration(milliseconds: 40), (_) {
      if (pointer != null) pump();
    });
    pump();
  }

  void drag(Offset position) {
    pointer = position;
    pump();
  }

  void pump() {
    if (anchor == null ||
        finishing ||
        gestureFlight != null ||
        pointer == null) {
      return;
    }
    final token = gestureToken;
    final flight = () async {
      final box = viewport.currentContext?.findRenderObject() as RenderBox?;
      final s = bridge.latest;
      if (box == null || s == null || s.rows.isEmpty || !scroll.hasClients) {
        return;
      }
      final y = box.globalToLocal(pointer!).dy;
      if (y < 32 || y > box.size.height - 32) {
        scroll.jumpTo(
          (scroll.offset + (y < 32 ? -16 : 16)).clamp(
            0,
            scroll.position.maxScrollExtent,
          ),
        );
      }
      final index = ((scroll.offset + y) / rowExtent).floor().clamp(
        0,
        s.rows.length - 1,
      );
      final target = s.rows[index].id;
      final after = (scroll.offset + y - index * rowExtent) > rowExtent / 2;
      if (lastTarget == (target, after)) return;
      try {
        if (selecting) {
          await bridge.command('selectionRange', {
            'first': anchor,
            'last': target,
          });
        } else if (target != anchor) {
          await bridge.command('dragTo', {'id': target, 'after': after});
        }
        if (token == gestureToken) lastTarget = (target, after);
      } catch (_) {
        gestureFailed = true;
        if (mounted) setState(() => failed = true);
        scheduleMicrotask(() => finishGesture(true));
      }
    }();
    gestureFlight = flight;
    unawaited(
      flight.whenComplete(() {
        if (gestureFlight == flight) gestureFlight = null;
      }),
    );
  }

  Future<void> finishGesture(bool cancelled) async {
    if (anchor == null || finishing) return;
    finishing = true;
    timer?.cancel();
    pointer = null;
    await gestureStart?.catchError((Object _) {});
    await gestureFlight;
    if (cancelled || gestureFailed) {
      await command('cancelGesture');
    } else {
      await command(selecting ? 'finishSelection' : 'finishDrag');
    }
    anchor = null;
    finishing = false;
    lastTarget = null;
    gestureToken++;
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState value) {
    if (value != AppLifecycleState.resumed) unawaited(finishGesture(true));
  }

  Widget tag(String id, Widget child) =>
      Semantics(identifier: id, child: child);
  PopupMenuItem<String> item(String op, String label, {bool enabled = true}) =>
      PopupMenuItem(
        value: op,
        enabled: enabled,
        child: tag('replace-rule-action:$op', Text(label)),
      );
  void mainAction(String op) {
    switch (op) {
      case 'add':
        unawaited(effect('Add'));
      case 'importLocal':
        unawaited(effect('ImportLocal'));
      case 'importQr':
        unawaited(effect('ImportQr'));
      case 'importUrl':
        unawaited(dialog('ImportUrl', const []));
      case 'manual':
        unawaited(command('manual'));
      case 'help':
        unawaited(effect('Help'));
    }
  }

  void selectionAction(String op) {
    final ids = state!.visibleSelection;
    switch (op) {
      case 'enable':
        unawaited(command('enabled', {'ids': ids, 'value': true}));
      case 'disable':
        unawaited(command('enabled', {'ids': ids, 'value': false}));
      case 'addGroup':
        unawaited(dialog('AddGroup', ids));
      case 'removeGroup':
        unawaited(dialog('RemoveGroup', ids));
      case 'top':
        unawaited(command('edge', {'ids': ids, 'value': true}));
      case 'bottom':
        unawaited(command('edge', {'ids': ids, 'value': false}));
      case 'export':
        unawaited(effect('Export'));
      case 'share':
        unawaited(effect('Share'));
    }
  }

  Widget body(ReplaceManagerSnapshot s) {
    final rows = s.rows;
    return LayoutBuilder(
      builder: (context, constraints) => SizedBox(
        key: viewport,
        child: tag(
          'replace-rule-list',
          ListView.builder(
            controller: scroll,
            itemExtent: rowExtent,
            itemCount: rows.length,
            itemBuilder: (context, index) {
              final row = rows[index];
              return GestureDetector(
                behavior: HitTestBehavior.opaque,
                onLongPressStart: actionsEnabled
                    ? (e) => unawaited(
                        startGesture(row.id, e.globalPosition, false),
                      )
                    : null,
                onLongPressMoveUpdate: (e) => drag(e.globalPosition),
                onLongPressEnd: (_) => unawaited(finishGesture(false)),
                onLongPressCancel: () => unawaited(finishGesture(true)),
                child: tag(
                  'replace-rule-row-${row.id}',
                  Row(
                    children: [
                      GestureDetector(
                        onHorizontalDragStart: actionsEnabled
                            ? (e) => unawaited(
                                startGesture(row.id, e.globalPosition, true),
                              )
                            : null,
                        onHorizontalDragUpdate: (e) => drag(e.globalPosition),
                        onHorizontalDragEnd: (_) =>
                            unawaited(finishGesture(false)),
                        onHorizontalDragCancel: () =>
                            unawaited(finishGesture(true)),
                        child: tag(
                          'replace-rule-select-${row.id}',
                          Checkbox(
                            value: s.selected.contains(row.id),
                            onChanged: actionsEnabled
                                ? (v) => command('selected', {
                                    'id': row.id,
                                    'value': v!,
                                  })
                                : null,
                          ),
                        ),
                      ),
                      Expanded(
                        child: InkWell(
                          onTap: actionsEnabled
                              ? () => command('selected', {
                                  'id': row.id,
                                  'value': !s.selected.contains(row.id),
                                })
                              : null,
                          child: Text(
                            row.displayName,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      ),
                      tag(
                        'replace-rule-enabled-${row.id}',
                        Switch(
                          value: row.enabled,
                          onChanged: actionsEnabled
                              ? (v) => command('enabled', {
                                  'ids': [row.id],
                                  'value': v,
                                })
                              : null,
                        ),
                      ),
                      tag(
                        'replace-rule-edit-${row.id}',
                        IconButton(
                          onPressed: actionsEnabled && !s.flag('waitingNative')
                              ? () => effect('Edit', row.id)
                              : null,
                          tooltip: s.label('edit'),
                          icon: const Icon(Icons.edit),
                        ),
                      ),
                      tag(
                        'replace-rule-menu-${row.id}',
                        PopupMenuButton<String>(
                          enabled: actionsEnabled,
                          onSelected: (op) async {
                            if (op == 'up' || op == 'down') {
                              final target =
                                  rows[index + (op == 'up' ? -1 : 1)].id;
                              await command('beginDrag', {'id': row.id});
                              await command('dragTo', {
                                'id': target,
                                'after': op == 'down',
                              });
                              await command('finishDrag');
                            } else if (op == 'delete') {
                              await dialog('Delete', [row.id]);
                            } else {
                              await command('edge', {
                                'ids': [row.id],
                                'value': op == 'top',
                              });
                            }
                          },
                          itemBuilder: (_) => [
                            if (index > 0) item('up', s.label('top')),
                            if (index < rows.length - 1)
                              item('down', s.label('bottom')),
                            item('top', s.label('top')),
                            item('bottom', s.label('bottom')),
                            item('delete', s.label('delete')),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              );
            },
          ),
        ),
      ),
    );
  }

  Widget modal(ReplaceManagerSnapshot s) {
    final output = ['ExportResult', 'Passphrase'].contains(s.dialog);
    final title = s.label(
      s.dialog == 'Delete'
          ? 'deleteTitle'
          : s.dialog == 'AddGroup'
          ? 'addGroup'
          : s.dialog == 'RemoveGroup'
          ? 'removeGroup'
          : s.dialog == 'ImportUrl'
          ? 'importUrl'
          : s.dialog == 'ExportResult'
          ? 'exportSuccess'
          : 'passphrase',
    );
    return Positioned.fill(
      child: Stack(
        children: [
          ModalBarrier(
            dismissible: editable,
            onDismiss: () => command('cancelDialog'),
            color: Colors.black54,
          ),
          SafeArea(
            child: Center(
              child: SingleChildScrollView(
                child: AlertDialog(
                  title: Text(title),
                  content: SizedBox(
                    width: 420,
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        if (s.dialog == 'Delete')
                          Text(
                            '${s.label('sureDelete')}\n${s.rows.where((r) => (s.raw['targets']! as List).contains(r.id)).map((r) => r.name).join('\n')}',
                          )
                        else ...[
                          if (s.dialog == 'ExportResult' &&
                              s.text('summary').isNotEmpty)
                            Text(s.text('summary')),
                          tag(
                            'replace-rule-dialog-field',
                            TextField(
                              controller: draft,
                              enabled: editable,
                              decoration: InputDecoration(
                                labelText: s.dialog == 'ImportUrl'
                                    ? 'url'
                                    : s.label(output ? 'path' : 'groupName'),
                              ),
                            ),
                          ),
                          if (s.dialog == 'ImportUrl')
                            SizedBox(
                              height: 150,
                              child: ListView(
                                children: [
                                  for (final value in s.history)
                                    Row(
                                      children: [
                                        Expanded(
                                          child: TextButton(
                                            onPressed: editable
                                                ? () => setDraft(value)
                                                : null,
                                            child: Text(value, maxLines: 1),
                                          ),
                                        ),
                                        IconButton(
                                          onPressed: actionsEnabled
                                              ? () => command('forgetImport', {
                                                  'text': value,
                                                })
                                              : null,
                                          tooltip: s.label('delete'),
                                          icon: const Icon(
                                            Icons.delete_outline,
                                          ),
                                        ),
                                      ],
                                    ),
                                ],
                              ),
                            ),
                          if (['AddGroup', 'RemoveGroup'].contains(s.dialog))
                            SizedBox(
                              height: 120,
                              child: ListView(
                                children: [
                                  for (final value in s.groups)
                                    TextButton(
                                      onPressed: editable
                                          ? () => setDraft(value)
                                          : null,
                                      child: Text(value),
                                    ),
                                ],
                              ),
                            ),
                          if (s.dialog == 'ExportResult' && s.flag('canEncode'))
                            tag(
                              'replace-rule-passphrase',
                              TextButton(
                                onPressed: actionsEnabled
                                    ? () => command('passphrase')
                                    : null,
                                child: Text(s.label('passphrase')),
                              ),
                            ),
                        ],
                      ],
                    ),
                  ),
                  actions: [
                    tag(
                      'replace-rule-dialog-cancel',
                      TextButton(
                        onPressed: editable
                            ? () => command('cancelDialog')
                            : null,
                        child: Text(s.label('cancel')),
                      ),
                    ),
                    tag(
                      'replace-rule-dialog-confirm',
                      TextButton(
                        onPressed: actionsEnabled
                            ? () => command(output ? 'copy' : 'confirmDialog')
                            : null,
                        child: Text(s.label('confirm')),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  void setDraft(String value) {
    draft.value = TextEditingValue(
      text: value,
      selection: TextSelection.collapsed(offset: value.length),
    );
  }

  @override
  Widget build(BuildContext context) {
    final s = state;
    if (s == null) {
      return Scaffold(
        body: Center(
          child: failed
              ? TextButton(
                  onPressed: () => bridge.refresh(),
                  child: const Icon(Icons.refresh),
                )
              : const CircularProgressIndicator(),
        ),
      );
    }
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop) {
          unawaited(
            s.dialog != null ? command('cancelDialog') : command('back'),
          );
        }
      },
      child: Stack(
        children: [
          ExcludeSemantics(
            excluding: s.dialog != null,
            child: Scaffold(
              appBar: AppBar(
                leading: tag(
                  'replace-rule-back',
                  IconButton(
                    onPressed: () => command('back'),
                    tooltip: s.label('back'),
                    icon: const Icon(Icons.arrow_back),
                  ),
                ),
                title: tag(
                  'replace-rule-search',
                  TextField(
                    controller: query,
                    enabled: editable,
                    textInputAction: TextInputAction.search,
                    onSubmitted: (_) => FocusScope.of(context).unfocus(),
                    decoration: InputDecoration(hintText: s.label('search')),
                  ),
                ),
                actions: [
                  tag(
                    'replace-rule-groups',
                    PopupMenuButton<String>(
                      enabled: actionsEnabled,
                      onSelected: (value) {
                        if (value == 'manage') {
                          unawaited(effect('Groups'));
                          return;
                        }
                        final text = value.startsWith('group:')
                            ? value
                            : s.label(value);
                        query.value = TextEditingValue(
                          text: text,
                          selection: TextSelection.collapsed(
                            offset: text.length,
                          ),
                        );
                      },
                      itemBuilder: (_) => [
                        item('manage', s.label('manageGroups')),
                        for (final value in ['enabled', 'disabled', 'noGroup'])
                          item(value, s.label(value)),
                        for (final group in s.groups)
                          item('group:$group', group),
                      ],
                    ),
                  ),
                  tag(
                    'replace-rule-more',
                    PopupMenuButton<String>(
                      enabled: actionsEnabled && !s.flag('waitingNative'),
                      onSelected: mainAction,
                      itemBuilder: (_) => [
                        for (final value in [
                          'add',
                          'importLocal',
                          'importUrl',
                          'importQr',
                        ])
                          item(value, s.label(value)),
                        CheckedPopupMenuItem(
                          value: 'manual',
                          checked: s.flag('manual'),
                          child: tag(
                            'replace-rule-manual',
                            Text(s.label('manual')),
                          ),
                        ),
                        item('help', s.label('help')),
                      ],
                    ),
                  ),
                ],
              ),
              body: Column(
                children: [
                  if (failed || s.flag('error'))
                    Row(
                      children: [
                        const Icon(Icons.error_outline),
                        TextButton(
                          onPressed: () {
                            final key = bridge.rejectedField;
                            if (key == null) {
                              unawaited(command('retry'));
                            } else {
                              changed(key, key == 'query' ? query : draft);
                            }
                          },
                          child: Text(s.label('retry')),
                        ),
                        TextButton(
                          onPressed: () => command(
                            s.dialog == null ? 'cancelQuery' : 'cancelDialog',
                          ),
                          child: Text(s.label('cancel')),
                        ),
                      ],
                    ),
                  Expanded(child: body(s)),
                ],
              ),
              bottomNavigationBar: SafeArea(
                child: SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  child: Row(
                    children: [
                      tag(
                        'replace-rule-count',
                        Text('${s.visibleSelection.length}/${s.rows.length}'),
                      ),
                      tag(
                        'replace-rule-all',
                        TextButton(
                          onPressed: actionsEnabled
                              ? () => command(
                                  s.visibleSelection.length == s.rows.length
                                      ? 'invert'
                                      : 'all',
                                )
                              : null,
                          child: Text(s.label('all')),
                        ),
                      ),
                      tag(
                        'replace-rule-invert',
                        TextButton(
                          onPressed: actionsEnabled
                              ? () => command('invert')
                              : null,
                          child: Text(s.label('invert')),
                        ),
                      ),
                      tag(
                        'replace-rule-interval',
                        TextButton(
                          onPressed: actionsEnabled
                              ? () => command('interval')
                              : null,
                          child: const Icon(Icons.select_all),
                        ),
                      ),
                      tag(
                        'replace-rule-delete-selection',
                        TextButton(
                          onPressed:
                              actionsEnabled && s.visibleSelection.isNotEmpty
                              ? () => dialog('Delete', s.visibleSelection)
                              : null,
                          child: Text(s.label('delete')),
                        ),
                      ),
                      tag(
                        'replace-rule-selection-menu',
                        PopupMenuButton<String>(
                          enabled: actionsEnabled,
                          onSelected: selectionAction,
                          itemBuilder: (_) => [
                            for (final value in [
                              'enable',
                              'disable',
                              'addGroup',
                              'removeGroup',
                              'top',
                              'bottom',
                              'export',
                              'share',
                            ])
                              item(
                                value,
                                s.label(
                                  value == 'enable'
                                      ? 'enableSelection'
                                      : value == 'disable'
                                      ? 'disableSelection'
                                      : value,
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
          if (s.dialog != null) modal(s),
        ],
      ),
    );
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    timer?.cancel();
    unawaited(bridge.command('cancelGesture').catchError((Object _) {}));
    unawaited(subscription?.cancel());
    unawaited(bridge.close());
    query.dispose();
    draft.dispose();
    scroll.dispose();
    super.dispose();
  }
}
