import 'package:flutter/semantics.dart';

import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../../l10n/generated/app_localizations.dart';
import 'highlight_manager_bridge.dart';

class HighlightManagerPage extends StatefulWidget {
  const HighlightManagerPage({super.key, this.bridge});
  final HighlightBridge? bridge;
  @override
  State<HighlightManagerPage> createState() => _HighlightManagerPageState();
}

class _HighlightManagerPageState extends State<HighlightManagerPage>
    with WidgetsBindingObserver {
  late final bridge = widget.bridge ?? HighlightBridge();
  StreamSubscription<HighlightSnapshot>? subscription;
  HighlightSnapshot? state;
  bool failed = false, filter = false, gesture = false, sliding = false;
  bool restoredScroll = false, reflectingScroll = false;
  Future<void>? scrollFlight;
  (int, double)? scrollPending;
  String? dragId;
  Offset? pointer;
  final viewport = GlobalKey();
  final scroll = ScrollController();
  final rowKeys = <String, GlobalKey>{};
  Timer? timer;
  Future<void> gestureQueue = Future.value();
  (String, String)? lastMove;
  bool get enabled =>
      state?.flag('loaded') == true && state?.flag('busy') == false && !failed;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    scroll.addListener(persistScroll);
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
    if (state?.binding != null && state!.binding != s.binding) {
      restoredScroll = false;
    }
    setState(() => state = s);
    if (!restoredScroll && s.flag('loaded') && s.rows.isNotEmpty) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted ||
            !scroll.hasClients ||
            restoredScroll ||
            state?.binding != s.binding) {
          return;
        }
        final index = (s.raw['scrollIndex']! as int).clamp(
          0,
          s.rows.length - 1,
        );
        final offset = (s.raw['scrollOffset']! as num).toDouble();
        reflectingScroll = true;
        scroll.jumpTo(
          (index * 80.0 + offset).clamp(0, scroll.position.maxScrollExtent),
        );
        reflectingScroll = false;
        restoredScroll = true;
      });
    }
  }

  void persistScroll() {
    if (!restoredScroll ||
        reflectingScroll ||
        state == null ||
        !scroll.hasClients) {
      return;
    }
    scrollPending = ((scroll.offset / 80.0).floor(), scroll.offset % 80.0);
    if (scrollFlight != null) return;
    scrollFlight = () async {
      try {
        while (mounted && scrollPending != null) {
          final position = scrollPending!;
          scrollPending = null;
          await bridge.command('scroll', {
            'index': position.$1,
            'offset': position.$2,
          });
        }
      } catch (_) {
        if (mounted) setState(() => failed = true);
      } finally {
        scrollFlight = null;
      }
    }();
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

  void begin(String id, Offset global, bool selection) {
    if (!enabled || gesture) return;
    gesture = true;
    sliding = selection;
    dragId = id;
    pointer = global;
    lastMove = null;
    gestureQueue = bridge.command(selection ? 'slideStart' : 'dragStart', {
      'uuid': id,
    });
    timer = Timer.periodic(
      const Duration(milliseconds: 50),
      (_) => move(pointer),
    );
    setState(() {});
  }

  void move(Offset? global) {
    if (!gesture || global == null || !mounted) return;
    pointer = global;
    final box = viewport.currentContext?.findRenderObject() as RenderBox?;
    if (box != null && scroll.hasClients) {
      final y = box.globalToLocal(global).dy;
      final delta = y < 44
          ? -18.0
          : y > box.size.height - 44
          ? 18.0
          : 0.0;
      if (delta != 0) {
        scroll.jumpTo(
          (scroll.offset + delta).clamp(0, scroll.position.maxScrollExtent),
        );
      }
    }
    final rows = state?.rows ?? const [];
    for (final row in rows) {
      final id = row['uuid']! as String;
      final rowBox =
          rowKeys[id]?.currentContext?.findRenderObject() as RenderBox?;
      if (rowBox == null || !rowBox.attached || !rowBox.hasSize) continue;
      final local = rowBox.globalToLocal(global);
      if (local.dy < 0 || local.dy >= rowBox.size.height) continue;
      if (lastMove == (dragId!, id)) return;
      lastMove = (dragId!, id);
      gestureQueue = gestureQueue.then(
        (_) => bridge.command(sliding ? 'slideMove' : 'dragMove', {
          'uuid': sliding ? id : dragId,
          'to': id,
        }),
      );
      return;
    }
  }

  Future<void> finish({bool cancel = false}) async {
    if (!gesture) return;
    timer?.cancel();
    timer = null;
    pointer = null;
    try {
      await gestureQueue;
      await bridge.command(
        cancel
            ? 'cancelGesture'
            : sliding
            ? 'slideEnd'
            : 'dragEnd',
      );
    } catch (_) {
      await command('cancelGesture');
      if (mounted) setState(() => failed = true);
    } finally {
      gesture = false;
      dragId = null;
      if (mounted) setState(() {});
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState value) {
    if (value != AppLifecycleState.resumed) unawaited(finish(cancel: true));
  }

  Widget tag(String id, Widget child) =>
      Semantics(identifier: id, child: child);
  Widget button(
    String id,
    String label,
    VoidCallback action, {
    bool active = true,
  }) => tag(
    id,
    TextButton(onPressed: active ? action : null, child: Text(label)),
  );
  Future<void> back() async {
    if (filter) {
      setState(() => filter = false);
      return;
    }
    await finish(cancel: true);
    await command('back');
  }

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
    final rows = s.rows;
    final selected = s.strings('selected').toSet();
    final count = rows.where((r) => selected.contains(r['uuid'])).length;
    final ready = enabled && !gesture;
    void action(String name, [int? id]) =>
        unawaited(command('action', {'action': name, 'id': id}));
    Widget item(
      String id,
      String text,
      VoidCallback onTap, {
      bool active = true,
    }) => button(id, text, onTap, active: ready && active);
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (popped, _) {
        if (!popped) unawaited(back());
      },
      child: Scaffold(
        appBar: AppBar(
          title: Text(label('title')),
          leading: IconButton(
            onPressed: back,
            icon: const Icon(Icons.arrow_back),
            tooltip: label('back'),
          ),
          actions: [
            tag(
              'highlight-management-add',
              IconButton(
                onPressed: ready ? () => action('Add') : null,
                icon: const Icon(Icons.add),
                tooltip: label('add'),
              ),
            ),
            tag(
              'highlight-management-menu',
              PopupMenuButton<String>(
                enabled: ready,
                tooltip: label('more'),
                onSelected: (v) {
                  if (v == 'filter') {
                    setState(() => filter = true);
                  } else if (v == 'exportAll') {
                    unawaited(command('export', {'value': true}));
                  } else {
                    action(v);
                  }
                },
                itemBuilder: (_) => [
                  PopupMenuItem(
                    value: 'Import',
                    child: tag(
                      'highlight-management-import',
                      Text(label('import')),
                    ),
                  ),
                  PopupMenuItem(
                    value: 'exportAll',
                    enabled: !s.flag('exporting'),
                    child: tag(
                      'highlight-management-export-all',
                      Text(label('exportAll')),
                    ),
                  ),
                  PopupMenuItem(
                    value: 'Groups',
                    child: tag(
                      'highlight-management-groups',
                      Text(label('groups')),
                    ),
                  ),
                  PopupMenuItem(
                    value: 'filter',
                    child: tag(
                      'highlight-management-filter',
                      Text(label('filter')),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
        body: Stack(
          children: [
            Column(
              children: [
                if (!s.flag('loaded') || s.flag('busy'))
                  const LinearProgressIndicator(),
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
                      button(
                        'highlight-management-retry',
                        label('retry'),
                        () async {
                          await command('retry');
                          await bridge.refresh();
                        },
                      ),
                    ],
                  ),
                if (s.flag('loaded') && rows.isEmpty)
                  tag('highlight-management-empty', Text(label('empty'))),
                Expanded(
                  child: tag(
                    'highlight-management-list',
                    Scrollbar(
                      controller: scroll,
                      interactive: true,
                      child: ListView.builder(
                        key: viewport,
                        controller: scroll,
                        itemCount: rows.length,
                        findChildIndexCallback: (key) {
                          if (key is! ValueKey<String>) return null;
                          final i = rows.indexWhere(
                            (r) => r['uuid'] == key.value,
                          );
                          return i < 0 ? null : i;
                        },
                        itemBuilder: (_, index) {
                          final row = rows[index];
                          final id = row['uuid']! as String;
                          final key = rowKeys.putIfAbsent(id, GlobalKey.new);
                          final name = row['name']! as String;
                          return SizedBox(
                            key: ValueKey(id),
                            height: 80,
                            child: tag(
                              'highlight-management-row-$id',
                              Row(
                                key: key,
                                children: [
                                  GestureDetector(
                                    onLongPressStart: ready
                                        ? (d) =>
                                              begin(id, d.globalPosition, true)
                                        : null,
                                    onLongPressMoveUpdate: (d) =>
                                        move(d.globalPosition),
                                    onLongPressEnd: (_) => unawaited(finish()),
                                    onLongPressCancel: () =>
                                        unawaited(finish(cancel: true)),
                                    child: tag(
                                      'highlight-management-select-$id',
                                      Checkbox(
                                        value: selected.contains(id),
                                        onChanged: ready
                                            ? (_) => unawaited(
                                                command('select', {'uuid': id}),
                                              )
                                            : null,
                                      ),
                                    ),
                                  ),
                                  Expanded(
                                    child: GestureDetector(
                                      onLongPressStart: ready
                                          ? (d) => begin(
                                              id,
                                              d.globalPosition,
                                              false,
                                            )
                                          : null,
                                      onLongPressMoveUpdate: (d) =>
                                          move(d.globalPosition),
                                      onLongPressEnd: (_) =>
                                          unawaited(finish()),
                                      onLongPressCancel: () =>
                                          unawaited(finish(cancel: true)),
                                      child: tag(
                                        'highlight-management-name-$id',
                                        Semantics(
                                          customSemanticsActions: {
                                            if (index > 0)
                                              CustomSemanticsAction(
                                                label: label('up'),
                                              ): () async {
                                                await command('dragStart');
                                                await command('dragMove', {
                                                  'uuid': id,
                                                  'to': rows[index - 1]['uuid'],
                                                });
                                                await command('dragEnd');
                                              },
                                            if (index + 1 < rows.length)
                                              CustomSemanticsAction(
                                                label: label('down'),
                                              ): () async {
                                                await command('dragStart');
                                                await command('dragMove', {
                                                  'uuid': id,
                                                  'to': rows[index + 1]['uuid'],
                                                });
                                                await command('dragEnd');
                                              },
                                          },
                                          child: InkWell(
                                            onTap: ready
                                                ? () => unawaited(
                                                    command('select', {
                                                      'uuid': id,
                                                    }),
                                                  )
                                                : null,
                                            child: Padding(
                                              padding:
                                                  const EdgeInsets.symmetric(
                                                    vertical: 16,
                                                  ),
                                              child: Text(
                                                row['label']! as String,
                                                maxLines: 2,
                                                overflow: TextOverflow.ellipsis,
                                              ),
                                            ),
                                          ),
                                        ),
                                      ),
                                    ),
                                  ),
                                  tag(
                                    'highlight-management-enabled-$id',
                                    Switch(
                                      value: row['enabled'] == true,
                                      onChanged: ready
                                          ? (v) => unawaited(
                                              command('enable', {
                                                'uuid': id,
                                                'value': v,
                                              }),
                                            )
                                          : null,
                                    ),
                                  ),
                                  tag(
                                    'highlight-management-edit-$id',
                                    IconButton(
                                      onPressed: ready
                                          ? () => action(
                                              'Edit',
                                              row['id']! as int,
                                            )
                                          : null,
                                      icon: const Icon(Icons.edit),
                                      tooltip: '${label('edit')} $name',
                                    ),
                                  ),
                                  tag(
                                    'highlight-management-more-$id',
                                    PopupMenuButton<String>(
                                      enabled: ready,
                                      tooltip: label('more'),
                                      onSelected: (v) => unawaited(
                                        command(
                                          v == 'delete' ? 'delete' : 'moveEdge',
                                          {'uuid': id, 'value': v == 'top'},
                                        ),
                                      ),
                                      itemBuilder: (_) => [
                                        for (final v in [
                                          'top',
                                          'bottom',
                                          'delete',
                                        ])
                                          PopupMenuItem(
                                            value: v,
                                            child: tag(
                                              'highlight-management-$v-$id',
                                              Text(label(v)),
                                            ),
                                          ),
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
                ),
                SafeArea(
                  top: false,
                  child: Column(
                    children: [
                      Row(
                        children: [
                          tag(
                            'highlight-management-all',
                            Checkbox(
                              value: rows.isNotEmpty && count == rows.length,
                              onChanged: ready
                                  ? (v) => unawaited(
                                      command('all', {'value': v ?? false}),
                                    )
                                  : null,
                            ),
                          ),
                          Expanded(
                            child: Text(
                              '${label('all')} ($count/${rows.length})',
                            ),
                          ),
                          item(
                            'highlight-management-invert',
                            label('invert'),
                            () => unawaited(command('all', {'value': false})),
                          ),
                        ],
                      ),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.end,
                        children: [
                          item(
                            'highlight-management-delete-selection',
                            label('delete'),
                            () => unawaited(command('delete')),
                            active: count > 0,
                          ),
                          tag(
                            'highlight-management-selection-menu',
                            PopupMenuButton<String>(
                              enabled: ready && count > 0,
                              onSelected: (v) {
                                switch (v) {
                                  case 'enableSelection':
                                  case 'disableSelection':
                                    unawaited(
                                      command('enableSelection', {
                                        'value': v == 'enableSelection',
                                      }),
                                    );
                                  case 'topSelection':
                                  case 'bottomSelection':
                                    unawaited(
                                      command('moveSelection', {
                                        'value': v == 'topSelection',
                                      }),
                                    );
                                  case 'export':
                                    unawaited(
                                      command('export', {'value': false}),
                                    );
                                  case 'share':
                                    unawaited(command('share'));
                                }
                              },
                              itemBuilder: (_) => [
                                for (final v in [
                                  'enableSelection',
                                  'disableSelection',
                                  'topSelection',
                                  'bottomSelection',
                                  'export',
                                  'share',
                                ])
                                  PopupMenuItem(
                                    value: v,
                                    enabled:
                                        v != 'export' || !s.flag('exporting'),
                                    child: tag(
                                      'highlight-management-${v == 'enableSelection'
                                          ? 'enable-selection'
                                          : v == 'disableSelection'
                                          ? 'disable-selection'
                                          : v == 'topSelection'
                                          ? 'top-selection'
                                          : v == 'bottomSelection'
                                          ? 'bottom-selection'
                                          : v}',
                                      Text(label(v)),
                                    ),
                                  ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ],
            ),
            if (filter)
              _modal(
                label('filter'),
                Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    for (final group in <String?>[
                      null,
                      '\u0000',
                      ...s.strings('groups'),
                    ])
                      item(
                        group == null
                            ? 'highlight-filter-all'
                            : group == '\u0000'
                            ? 'highlight-filter-ungrouped'
                            : 'highlight-filter-$group',
                        group == null
                            ? label('allGroups')
                            : group == '\u0000'
                            ? label('noGroup')
                            : '[$group]',
                        () {
                          setState(() => filter = false);
                          unawaited(command('filter', {'value': group}));
                        },
                      ),
                  ],
                ),
                [
                  button(
                    'highlight-management-filter-cancel',
                    label('cancel'),
                    () => setState(() => filter = false),
                  ),
                ],
              ),
            if (s.strings('deletion').isNotEmpty)
              _modal(
                label('title'),
                Text('${label('sureDelete')}\n${s.text('deletionName')}'),
                [
                  item(
                    'highlight-management-delete-cancel',
                    label('cancel'),
                    () => unawaited(command('deleteCancel')),
                  ),
                  item(
                    'highlight-management-delete-confirm',
                    label('confirm'),
                    () => unawaited(command('deleteConfirm')),
                  ),
                ],
              ),
            if (s.raw['exportResult'] != null)
              _modal(
                label('exportSuccess'),
                Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (s.raw['exportSummary'] != null)
                      tag(
                        'highlight-management-export-summary',
                        Text(s.text('exportSummary')),
                      ),
                    tag(
                      'highlight-management-export-result',
                      SelectableText(s.text('exportResult')),
                    ),
                  ],
                ),
                [
                  item(
                    'highlight-management-export-dismiss',
                    label('cancel'),
                    () => unawaited(command('exportDismiss')),
                  ),
                  item(
                    'highlight-management-export-copy',
                    label('confirm'),
                    () => unawaited(command('exportCopy')),
                  ),
                ],
              ),
          ],
        ),
      ),
    );
  }

  Widget _modal(String title, Widget content, List<Widget> actions) =>
      Positioned.fill(
        child: Stack(
          children: [
            const ModalBarrier(dismissible: false, color: Colors.black54),
            Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(
                  maxWidth: 520,
                  maxHeight: 560,
                ),
                child: AlertDialog(
                  title: Text(title),
                  content: SingleChildScrollView(child: content),
                  actions: actions,
                ),
              ),
            ),
          ],
        ),
      );
  @override
  void dispose() {
    timer?.cancel();
    if (gesture) {
      unawaited(bridge.command('cancelGesture').catchError((Object _) {}));
    }
    WidgetsBinding.instance.removeObserver(this);
    subscription?.cancel();
    unawaited(bridge.close());
    scroll.dispose();
    super.dispose();
  }
}
