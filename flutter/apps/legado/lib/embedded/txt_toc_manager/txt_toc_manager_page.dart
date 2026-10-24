import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../../l10n/generated/app_localizations.dart';
import 'txt_toc_manager_bridge.dart';

class TxtTocManagerPage extends StatefulWidget {
  const TxtTocManagerPage({super.key, this.bridge, this.picker = false});
  final bool picker;
  final TxtTocManagerBridge? bridge;
  @override
  State<TxtTocManagerPage> createState() => _TxtTocManagerPageState();
}

class _TxtTocManagerPageState extends State<TxtTocManagerPage>
    with WidgetsBindingObserver {
  late final bridge = widget.bridge ?? TxtTocManagerBridge();
  final query = TextEditingController(), draft = TextEditingController();
  final scroll = ScrollController();
  final viewport = GlobalKey();
  StreamSubscription<TxtTocManagerSnapshot>? subscription;
  TxtTocManagerSnapshot? state;
  final pending = <String, int>{};
  bool reflecting = false, failed = false, restored = false;
  static const rowExtent = 96.0;
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
    draft.addListener(() => changed('onlineInput', draft));
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

  void accept(TxtTocManagerSnapshot next) {
    if (!mounted) return;
    reflecting = true;
    for (final entry in {'query': query, 'onlineInput': draft}.entries) {
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
      await (op == 'navigateBack'
          ? bridge.navigateBack()
          : bridge.command(op, values));
      if (mounted) setState(() => failed = false);
    } catch (_) {
      if (mounted) setState(() => failed = true);
    }
  }

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
    final starting = bridge.command(selection ? 'beginSlide' : 'beginDrag', {
      'id': id,
    });
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
          await bridge.command('slideTo', {'id': target});
        } else if (target != anchor) {
          await bridge.command('move', {'from': anchor, 'to': target});
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
      await command(selecting ? 'finishSlide' : 'finishDrag');
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
        child: tag('txt-toc-action:$op', Text(label)),
      );
  void mainAction(String op) {
    if (op == 'online') {
      unawaited(command('online', {'value': true}));
    } else {
      unawaited(command(op));
    }
  }

  void selectionAction(String op) {
    if (op == 'enableSelection' || op == 'disableSelection') {
      unawaited(command('enableSelection', {'value': op == 'enableSelection'}));
    } else {
      unawaited(command(op));
    }
  }

  Widget body(TxtTocManagerSnapshot s) {
    final rows = s.rows;
    return LayoutBuilder(
      builder: (context, constraints) => SizedBox(
        key: viewport,
        child: tag(
          'txt-toc-list',
          ListView.builder(
            controller: scroll,
            itemExtent: rowExtent,
            itemCount: rows.length,
            findChildIndexCallback: (key) {
              if (key is! ValueKey<int>) return null;
              final index = rows.indexWhere((row) => row.id == key.value);
              return index < 0 ? null : index;
            },
            itemBuilder: (context, index) {
              final row = rows[index];
              return GestureDetector(
                key: ValueKey(row.id),
                behavior: HitTestBehavior.opaque,
                onLongPressStart:
                    actionsEnabled && s.text('query').trim().isEmpty
                    ? (e) => unawaited(
                        startGesture(row.id, e.globalPosition, false),
                      )
                    : null,
                onLongPressMoveUpdate: (e) => drag(e.globalPosition),
                onLongPressEnd: (_) => unawaited(finishGesture(false)),
                onLongPressCancel: () => unawaited(finishGesture(true)),
                child: tag(
                  'txt-toc-row-${row.id}',
                  Column(
                    children: [
                      Expanded(
                        child: Row(
                          children: [
                            GestureDetector(
                              onHorizontalDragStart:
                                  actionsEnabled && !widget.picker
                                  ? (e) => unawaited(
                                      startGesture(
                                        row.id,
                                        e.globalPosition,
                                        true,
                                      ),
                                    )
                                  : null,
                              onHorizontalDragUpdate: (e) =>
                                  drag(e.globalPosition),
                              onHorizontalDragEnd: (_) =>
                                  unawaited(finishGesture(false)),
                              onHorizontalDragCancel: () =>
                                  unawaited(finishGesture(true)),
                              child: tag(
                                'txt-toc-select-${row.id}',
                                widget.picker
                                    ? Radio<int>(
                                        value: row.id,
                                        enabled: actionsEnabled,
                                      )
                                    : Checkbox(
                                        value: s.selected.contains(row.id),
                                        onChanged: actionsEnabled
                                            ? (_) => command('toggle', {
                                                'id': row.id,
                                              })
                                            : null,
                                      ),
                              ),
                            ),
                            Expanded(
                              child: InkWell(
                                onTap: actionsEnabled
                                    ? () => command(
                                        widget.picker ? 'choose' : 'toggle',
                                        {'id': row.id},
                                      )
                                    : null,
                                child: Column(
                                  mainAxisAlignment: MainAxisAlignment.center,
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    tag(
                                      'txt-toc-name-${row.id}',
                                      Text(
                                        row.name,
                                        maxLines: 1,
                                        overflow: TextOverflow.ellipsis,
                                      ),
                                    ),
                                    if (row.example?.isNotEmpty == true)
                                      tag(
                                        'txt-toc-example-${row.id}',
                                        Text(
                                          row.example!,
                                          maxLines: 1,
                                          overflow: TextOverflow.ellipsis,
                                          style: Theme.of(context)
                                              .textTheme
                                              .bodySmall,
                                        ),
                                      ),
                                  ],
                                ),
                              ),
                            ),
                            tag(
                              'txt-toc-enabled-${row.id}',
                              Switch(
                                value: row.enabled,
                                onChanged: actionsEnabled
                                    ? (v) => command('enabled', {
                                        'id': row.id,
                                        'value': v,
                                      })
                                    : null,
                              ),
                            ),
                            tag(
                              'txt-toc-edit-${row.id}',
                              IconButton(
                                onPressed: actionsEnabled
                                    ? () => command('edit', {'id': row.id})
                                    : null,
                                tooltip: s.label('edit'),
                                icon: const Icon(Icons.edit),
                              ),
                            ),
                            tag(
                              'txt-toc-row-menu-${row.id}',
                              PopupMenuButton<String>(
                                enabled: actionsEnabled,
                                onSelected: (op) async {
                                  if (op == 'up' || op == 'down') {
                                    await command('move', {
                                      'from': row.id,
                                      'to': rows[index + (op == 'up' ? -1 : 1)]
                                          .id,
                                    });
                                    await command('finishDrag');
                                  } else if (op == 'delete') {
                                    if (!widget.picker &&
                                        s.selected.contains(row.id)) {
                                      await command('toggle', {'id': row.id});
                                    }
                                    await command('delete', {'id': row.id});
                                  } else {
                                    if (s.selected.contains(row.id)) {
                                      await command('toggle', {'id': row.id});
                                    }
                                    await command('edge', {
                                      'id': row.id,
                                      'value': op == 'top',
                                    });
                                  }
                                },
                                itemBuilder: (_) => [
                                  if (s.text('query').trim().isEmpty &&
                                      index > 0)
                                    item('up', s.label('up')),
                                  if (s.text('query').trim().isEmpty &&
                                      index < rows.length - 1)
                                    item('down', s.label('down')),
                                  item('top', s.label('top')),
                                  item('bottom', s.label('bottom')),
                                  item('delete', s.label('delete')),
                                ],
                              ),
                            ),
                          ],
                        ),
                      ),
                      const Divider(height: 1),
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

  Widget modal(TxtTocManagerSnapshot s) {
    final kind = s.dialog!;
    final title = s.label(
      kind == 'delete'
          ? 'deleteTitle'
          : kind == 'online'
          ? 'online'
          : kind == 'export'
          ? 'exportSuccess'
          : 'passphrase',
    );
    return Positioned.fill(
      child: Stack(
        children: [
          ModalBarrier(
            dismissible: editable,
            onDismiss: () => command('cancelModal'),
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
                        if (kind == 'delete')
                          Text(
                            '${s.label('sureDelete')}\n${(s.raw['deleteNames']! as List).join('\n')}',
                          )
                        else if (kind == 'online') ...[
                          tag(
                            'txt-toc-online-input',
                            TextField(
                              controller: draft,
                              enabled: editable,
                              decoration: InputDecoration(
                                labelText: s.label('sourceUrl'),
                              ),
                            ),
                          ),
                          SizedBox(
                            height: 150,
                            child: ListView(
                              children: [
                                for (final url in s.history.where(
                                  (url) =>
                                      draft.text.isEmpty ||
                                      url.toLowerCase().contains(
                                        draft.text.toLowerCase(),
                                      ),
                                ))
                                  Row(
                                    children: [
                                      Expanded(
                                        child: TextButton(
                                          onPressed: editable
                                              ? () {
                                                  draft
                                                      .value = TextEditingValue(
                                                    text: url,
                                                    selection:
                                                        TextSelection.collapsed(
                                                          offset: url.length,
                                                        ),
                                                  );
                                                }
                                              : null,
                                          child: Text(url, maxLines: 1),
                                        ),
                                      ),
                                      tag(
                                        'txt-toc-history-delete-$url',
                                        IconButton(
                                          onPressed: actionsEnabled
                                              ? () => command('deleteHistory', {
                                                  'text': url,
                                                })
                                              : null,
                                          tooltip: s.label('delete'),
                                          icon: const Icon(
                                            Icons.delete_outline,
                                          ),
                                        ),
                                      ),
                                    ],
                                  ),
                              ],
                            ),
                          ),
                        ] else ...[
                          if (kind == 'export' &&
                              s.text('exportSummary').isNotEmpty)
                            Text(s.text('exportSummary')),
                          SelectableText(
                            s.text(
                              kind == 'export' ? 'exportUrl' : 'passphrase',
                            ),
                          ),
                          if (kind == 'export' && s.flag('canEncode'))
                            tag(
                              'txt-toc-passphrase',
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
                      kind == 'delete'
                          ? 'txt-toc-delete-cancel'
                          : 'txt-toc-dialog-cancel',
                      TextButton(
                        onPressed: editable
                            ? () => command('cancelModal')
                            : null,
                        child: Text(s.label('cancel')),
                      ),
                    ),
                    tag(
                      kind == 'delete'
                          ? 'txt-toc-delete-confirm'
                          : kind == 'online'
                          ? 'txt-toc-online-confirm'
                          : 'txt-toc-dialog-confirm',
                      TextButton(
                        onPressed: actionsEnabled
                            ? () => command(
                                kind == 'delete'
                                    ? 'confirmDelete'
                                    : kind == 'online'
                                    ? 'confirmOnline'
                                    : kind == 'export'
                                    ? 'copyExport'
                                    : 'copyPassphrase',
                              )
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

  @override
  Widget build(BuildContext context) {
    final s = state;
    final l = AppLocalizations.of(context);
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
    final page = PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop) unawaited(command('navigateBack'));
      },
      child: Stack(
        children: [
          ExcludeSemantics(
            excluding: s.dialog != null,
            child: Scaffold(
              appBar: AppBar(
                leading: tag(
                  'txt-toc-back',
                  IconButton(
                    onPressed: () => command('navigateBack'),
                    tooltip: s.label('back'),
                    icon: const Icon(Icons.arrow_back),
                  ),
                ),
                title: Text(l.txtTocManagerTitle),
                actions: [
                  tag(
                    'txt-toc-add',
                    IconButton(
                      onPressed: actionsEnabled ? () => command('add') : null,
                      tooltip: s.label('add'),
                      icon: const Icon(Icons.add),
                    ),
                  ),
                  tag(
                    'txt-toc-menu',
                    PopupMenuButton<String>(
                      enabled: actionsEnabled,
                      onSelected: mainAction,
                      itemBuilder: (_) => [
                        for (final op in [
                          'importFile',
                          'online',
                          'importQr',
                          'default',
                          'help',
                        ])
                          item(op, s.label(op)),
                      ],
                    ),
                  ),
                ],
              ),
              body: Column(
                children: [
                  tag(
                    'txt-toc-search',
                    TextField(
                      controller: query,
                      enabled: editable,
                      decoration: InputDecoration(labelText: s.label('search')),
                      textInputAction: TextInputAction.search,
                      onSubmitted: (_) => FocusScope.of(context).unfocus(),
                    ),
                  ),
                  if (!s.flag('loaded') || s.flag('busy'))
                    const LinearProgressIndicator(),
                  if (failed || s.flag('error'))
                    Row(
                      children: [
                        Expanded(child: Text(l.txtTocManagerBridgeError)),
                        TextButton(
                          onPressed: () => command('retry'),
                          child: Text(s.label('retry')),
                        ),
                      ],
                    ),
                  Expanded(
                    child: Scrollbar(
                      controller: scroll,
                      interactive: true,
                      child: body(s),
                    ),
                  ),
                ],
              ),
              bottomNavigationBar: SafeArea(
                top: false,
                child: widget.picker
                    ? Row(
                        mainAxisAlignment: MainAxisAlignment.end,
                        children: [
                          tag(
                            'txt-toc-cancel',
                            TextButton(
                              onPressed: editable
                                  ? () => command('navigateBack')
                                  : null,
                              child: Text(s.label('cancel')),
                            ),
                          ),
                          tag(
                            'txt-toc-confirm',
                            TextButton(
                              onPressed:
                                  actionsEnabled && s.raw['selectedId'] != null
                                  ? () => command('confirmChoice')
                                  : null,
                              child: Text(s.label('confirm')),
                            ),
                          ),
                        ],
                      )
                    : Row(
                        children: [
                          tag(
                            'txt-toc-all',
                            Checkbox(
                              value:
                                  s.rows.isNotEmpty &&
                                  s.visibleSelection.length == s.rows.length,
                              onChanged: actionsEnabled
                                  ? (_) => command('all')
                                  : null,
                            ),
                          ),
                          Text('${s.visibleSelection.length}/${s.rows.length}'),
                          TextButton(
                            onPressed: actionsEnabled
                                ? () => command('invert')
                                : null,
                            child: Text(s.label('invert')),
                          ),
                          const Spacer(),
                          tag(
                            'txt-toc-delete-selection',
                            IconButton(
                              onPressed:
                                  actionsEnabled &&
                                      s.visibleSelection.isNotEmpty
                                  ? () => command('deleteSelection')
                                  : null,
                              tooltip: s.label('delete'),
                              icon: const Icon(Icons.delete_outline),
                            ),
                          ),
                          tag(
                            'txt-toc-selection-menu',
                            PopupMenuButton<String>(
                              enabled:
                                  actionsEnabled &&
                                  s.visibleSelection.isNotEmpty,
                              onSelected: selectionAction,
                              itemBuilder: (_) => [
                                for (final op in [
                                  'enableSelection',
                                  'disableSelection',
                                  'export',
                                  'share',
                                ])
                                  PopupMenuItem(
                                    value: op,
                                    child: tag(
                                      'txt-toc-$op',
                                      Text(s.label(op)),
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
          if (s.dialog != null) modal(s),
        ],
      ),
    );
    if (!widget.picker) return page;
    final picker = RadioGroup<int>(
      groupValue: s.raw['selectedId'] as int?,
      onChanged: (id) {
        if (actionsEnabled && id != null) {
          unawaited(command('choose', {'id': id}));
        }
      },
      child: page,
    );
    return Material(
      color: Colors.transparent,
      child: LayoutBuilder(
        builder: (context, constraints) => Stack(
          children: [
            tag(
              'txt-toc-picker-backdrop',
              ModalBarrier(
                dismissible: editable,
                onDismiss: () => command('navigateBack'),
                color: Colors.black54,
              ),
            ),
            Center(
              child: SizedBox(
                width: constraints.maxWidth * .9,
                height: constraints.maxHeight * .8,
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(20),
                  child: picker,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    timer?.cancel();
    unawaited(finishGesture(true));
    subscription?.cancel();
    query.dispose();
    draft.dispose();
    scroll.dispose();
    unawaited(bridge.close());
    super.dispose();
  }
}

class TxtTocPickerPage extends StatelessWidget {
  const TxtTocPickerPage({super.key, this.bridge});
  final TxtTocManagerBridge? bridge;
  @override
  Widget build(BuildContext context) =>
      TxtTocManagerPage(picker: true, bridge: bridge);
}
