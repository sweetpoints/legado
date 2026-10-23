import 'dart:async';

import 'package:material_ui/material_ui.dart';

import 'dictionary_manager_bridge.dart';

/// Flutter presentation; Room, gesture rollback, pending effects and Activity results stay native.
class DictionaryManagerPage extends StatefulWidget {
  const DictionaryManagerPage({super.key, this.bridge});
  final DictionaryManagerBridge? bridge;
  @override
  State<DictionaryManagerPage> createState() => _DictionaryManagerPageState();
}

class _DictionaryManagerPageState extends State<DictionaryManagerPage>
    with WidgetsBindingObserver {
  late final bridge = widget.bridge ?? DictionaryManagerBridge();
  final input = TextEditingController();
  final scroll = ScrollController();
  final viewport = GlobalKey();
  StreamSubscription<DictionaryManagerSnapshot>? subscription;
  DictionaryManagerSnapshot? state;
  bool failed = false;
  int pendingInputs = 0;
  String? anchor;
  bool selecting = false;
  Offset? pointer;
  Timer? timer;
  bool get zh => Localizations.localeOf(context).languageCode == 'zh';
  String tr(String cn, String en) => zh ? cn : en;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    subscription = bridge.states.listen(
      (value) {
        if (!mounted) return;
        if (pendingInputs == 0 &&
            state?.input != value.input &&
            input.text != value.input) {
          input.text = value.input;
        }
        setState(() {
          state = value;
          failed = false;
        });
      },
      onError: (Object _) {
        if (mounted) setState(() => failed = true);
      },
    );
    bridge.start().catchError((Object _) {
      if (mounted) setState(() => failed = true);
    });
  }

  Future<void> command(
    String op, [
    Map<String, Object?> args = const {},
  ]) async {
    if (op == 'input') pendingInputs++;
    try {
      await bridge.command(op, args);
    } catch (_) {
      if (mounted) setState(() => failed = true);
    } finally {
      if (op == 'input') pendingInputs--;
    }
  }

  int gestureToken = 0;
  bool finishingGesture = false;
  bool gestureFailed = false;
  Future<void>? gestureFlight;

  Future<void> finishGesture(bool cancelled) async {
    if (anchor == null || finishingGesture) return;
    finishingGesture = true;
    timer?.cancel();
    pointer = null;
    final token = gestureToken;
    await gestureFlight;
    if (token != gestureToken) return;
    cancelled = cancelled || gestureFailed;
    await command(
      selecting
          ? (cancelled ? 'cancelSlide' : 'endSlide')
          : (cancelled ? 'cancelReorder' : 'finishReorder'),
    );
    if (token == gestureToken) {
      anchor = null;
      finishingGesture = false;
      gestureToken++;
    }
  }

  void cancelGesture() => unawaited(finishGesture(true));
  @override
  void didChangeAppLifecycleState(AppLifecycleState value) {
    if (value != AppLifecycleState.resumed) cancelGesture();
  }

  void drag(Offset global) {
    if (anchor == null || finishingGesture) return;
    pointer = global;
    if (gestureFlight != null) return;
    final token = gestureToken;
    final flight = pumpGesture(token);
    gestureFlight = flight;
    unawaited(
      flight.whenComplete(() {
        if (gestureFlight == flight) gestureFlight = null;
      }),
    );
  }

  Future<void> pumpGesture(int token) async {
    final box = viewport.currentContext?.findRenderObject() as RenderBox?;
    final point = pointer;
    final s = bridge.latest;
    if (box == null ||
        point == null ||
        s == null ||
        s.rules.isEmpty ||
        !scroll.hasClients ||
        anchor == null ||
        finishingGesture ||
        token != gestureToken) {
      return;
    }
    final y = box.globalToLocal(point).dy;
    if (y < 32 || y > box.size.height - 32) {
      scroll.jumpTo(
        (scroll.offset + (y < 32 ? -16 : 16)).clamp(
          0,
          scroll.position.maxScrollExtent,
        ),
      );
    }
    final index = ((scroll.offset + y) / 80).floor().clamp(
      0,
      s.rules.length - 1,
    );
    final target = s.rules[index].name;
    try {
      if (selecting) {
        await bridge.command('slideTo', {'name': target});
      } else if (anchor != target) {
        await bridge.command('move', {'from': anchor, 'to': target});
      }
    } catch (_) {
      gestureFailed = true;
      if (mounted) setState(() => failed = true);
      // Failed transport must roll back the native preview rather than commit a partial drag.
      if (token == gestureToken && !finishingGesture) {
        scheduleMicrotask(cancelGesture);
      }
    }
  }

  void start(String name, Offset global, bool selection) {
    if (state?.busy != false || anchor != null) return;
    anchor = name;
    selecting = selection;
    finishingGesture = false;
    gestureFailed = false;
    gestureToken++;
    if (selection) unawaited(command('beginSlide', {'name': name}));
    drag(global);
    timer = Timer.periodic(const Duration(milliseconds: 80), (_) {
      if (pointer != null) drag(pointer!);
    });
  }

  void dragFor(String name, Offset point) {
    if (anchor == name) drag(point);
  }

  void endFor(String name, bool cancelled) {
    if (anchor == name) unawaited(finishGesture(cancelled));
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    cancelGesture();
    subscription?.cancel();
    unawaited(bridge.close());
    input.dispose();
    scroll.dispose();
    super.dispose();
  }

  PopupMenuItem<String> item(String op, String cn, String en) => PopupMenuItem(
    value: op,
    child: Semantics(
      identifier: 'dictionary-action:$op',
      child: Text(tr(cn, en)),
    ),
  );
  @override
  Widget build(BuildContext context) {
    final s = state;
    final enabled = s != null && !s.busy && !s.loading;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) unawaited(command('back'));
      },
      child: Stack(
        children: [
          Scaffold(
            appBar: AppBar(
              title: Text(tr('字典规则', 'Dictionary rules')),
              leading: IconButton(
                tooltip: tr('返回', 'Back'),
                icon: const Icon(Icons.arrow_back),
                onPressed: () => command('back'),
              ),
              actions: [
                Semantics(
                  identifier: 'dictionary-add',
                  child: IconButton(
                    key: const ValueKey('dictionary-add'),
                    tooltip: tr('新建', 'Create'),
                    icon: const Icon(Icons.add),
                    onPressed: enabled ? () => command('add') : null,
                  ),
                ),
                Semantics(
                  identifier: 'dictionary-menu',
                  child: PopupMenuButton<String>(
                    key: const ValueKey('dictionary-menu'),
                    enabled: enabled,
                    onSelected: (op) => op == 'online'
                        ? command(op, {'value': true})
                        : command(op),
                    itemBuilder: (_) => [
                      item('importFile', '本地导入', 'Import file'),
                      item('online', '在线导入', 'Import online'),
                      item('importQr', '二维码导入', 'Import QR code'),
                      item('importDefault', '导入默认规则', 'Import defaults'),
                      item('help', '帮助', 'Help'),
                    ],
                  ),
                ),
              ],
            ),
            body: Stack(
              children: [
                Column(
                  children: [
                    if (s == null || s.loading || s.busy)
                      const LinearProgressIndicator(),
                    if (failed || s?.error == true)
                      MaterialBanner(
                        content: Text(
                          tr('操作失败，请重试', 'Operation failed. Try again.'),
                        ),
                        actions: [
                          TextButton(
                            onPressed: () async {
                              if (bridge.latest == null) {
                                await bridge.refresh();
                              } else {
                                await command('retry');
                              }
                            },
                            child: Text(tr('重试', 'Retry')),
                          ),
                        ],
                      ),
                    Expanded(
                      child: Listener(
                        onPointerCancel: (_) => cancelGesture(),
                        child: SizedBox(
                          key: viewport,
                          child: Scrollbar(
                            controller: scroll,
                            thumbVisibility: true,
                            interactive: true,
                            child: ListView.builder(
                              key: const ValueKey('dictionary-list'),
                              controller: scroll,
                              itemExtent: 80,
                              itemCount: s?.rules.length ?? 0,
                              itemBuilder: (_, index) {
                                final row = s!.rules[index];
                                return Row(
                                  key: ValueKey('dictionary-row-${row.name}'),
                                  children: [
                                    GestureDetector(
                                      onVerticalDragStart: (d) => start(
                                        row.name,
                                        d.globalPosition,
                                        true,
                                      ),
                                      onVerticalDragUpdate: (d) =>
                                          dragFor(row.name, d.globalPosition),
                                      onVerticalDragEnd: (_) =>
                                          endFor(row.name, false),
                                      onVerticalDragCancel: () =>
                                          endFor(row.name, true),
                                      child: SizedBox(
                                        width: 50,
                                        child: Semantics(
                                          identifier:
                                              'dictionary-select-${row.name}',
                                          child: Checkbox(
                                            key: ValueKey(
                                              'dictionary-select-${row.name}',
                                            ),
                                            semanticLabel: tr(
                                              '选择 ${row.name}',
                                              'Select ${row.name}',
                                            ),
                                            value: s.selected.contains(
                                              row.name,
                                            ),
                                            onChanged: enabled
                                                ? (_) => command('toggle', {
                                                    'name': row.name,
                                                  })
                                                : null,
                                          ),
                                        ),
                                      ),
                                    ),
                                    Expanded(
                                      child: GestureDetector(
                                        onLongPressStart: (d) => start(
                                          row.name,
                                          d.globalPosition,
                                          false,
                                        ),
                                        onLongPressMoveUpdate: (d) =>
                                            dragFor(row.name, d.globalPosition),
                                        onLongPressEnd: (_) =>
                                            endFor(row.name, false),
                                        onLongPressCancel: () =>
                                            endFor(row.name, true),
                                        child: InkWell(
                                          onTap: enabled
                                              ? () => command('toggle', {
                                                  'name': row.name,
                                                })
                                              : null,
                                          child: SizedBox(
                                            height: 80,
                                            child: Align(
                                              alignment: Alignment.centerLeft,
                                              child: Text(
                                                row.name,
                                                maxLines: 2,
                                                overflow: TextOverflow.ellipsis,
                                              ),
                                            ),
                                          ),
                                        ),
                                      ),
                                    ),
                                    Semantics(
                                      identifier:
                                          'dictionary-enabled-${row.name}',
                                      child: Switch(
                                        key: ValueKey(
                                          'dictionary-enabled-${row.name}',
                                        ),
                                        value: row.enabled,
                                        onChanged: enabled
                                            ? (v) => command('enabled', {
                                                'name': row.name,
                                                'value': v,
                                              })
                                            : null,
                                      ),
                                    ),
                                    Semantics(
                                      identifier: 'dictionary-edit-${row.name}',
                                      child: IconButton(
                                        key: ValueKey(
                                          'dictionary-edit-${row.name}',
                                        ),
                                        tooltip: tr(
                                          '编辑 ${row.name}',
                                          'Edit ${row.name}',
                                        ),
                                        icon: const Icon(Icons.edit),
                                        onPressed: enabled
                                            ? () => command('edit', {
                                                'name': row.name,
                                              })
                                            : null,
                                      ),
                                    ),
                                    Semantics(
                                      identifier: 'dictionary-more-${row.name}',
                                      child: PopupMenuButton<String>(
                                        key: ValueKey(
                                          'dictionary-more-${row.name}',
                                        ),
                                        tooltip: tr(
                                          '更多 ${row.name}',
                                          'More ${row.name}',
                                        ),
                                        enabled: enabled,
                                        onSelected: (op) async {
                                          if (op == 'delete') {
                                            await command(op, {
                                              'name': row.name,
                                            });
                                          } else {
                                            final target =
                                                index + (op == 'up' ? -1 : 1);
                                            await command('move', {
                                              'from': row.name,
                                              'to': s.rules[target].name,
                                            });
                                            await command('finishReorder');
                                          }
                                        },
                                        itemBuilder: (_) => [
                                          if (index > 0)
                                            item('up', '上移', 'Move up'),
                                          if (index < s.rules.length - 1)
                                            item('down', '下移', 'Move down'),
                                          item('delete', '删除', 'Delete'),
                                        ],
                                      ),
                                    ),
                                  ],
                                );
                              },
                            ),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
            bottomNavigationBar: s == null
                ? null
                : SafeArea(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Row(
                          children: [
                            Semantics(
                              identifier: 'dictionary-all',
                              child: Checkbox(
                                key: const ValueKey('dictionary-all'),
                                value:
                                    s.rules.isNotEmpty &&
                                    s.selected.length == s.rules.length,
                                onChanged: enabled
                                    ? (_) => command('selectAll')
                                    : null,
                              ),
                            ),
                            Expanded(
                              child: Text(
                                '${tr('已选', 'Selected')} ${s.selected.length}/${s.rules.length}',
                              ),
                            ),
                            TextButton(
                              onPressed: enabled
                                  ? () => command('invert')
                                  : null,
                              child: Text(tr('反选', 'Invert')),
                            ),
                          ],
                        ),
                        Row(
                          mainAxisAlignment: MainAxisAlignment.end,
                          children: [
                            TextButton(
                              key: const ValueKey(
                                'dictionary-delete-selection',
                              ),
                              onPressed: enabled && s.selected.isNotEmpty
                                  ? () => command('deleteSelection')
                                  : null,
                              child: Text(tr('删除', 'Delete')),
                            ),
                            Semantics(
                              identifier: 'dictionary-selection-menu',
                              child: PopupMenuButton<String>(
                                key: const ValueKey(
                                  'dictionary-selection-menu',
                                ),
                                enabled: enabled && s.selected.isNotEmpty,
                                onSelected: (op) =>
                                    op == 'enable' || op == 'disable'
                                    ? command('enableSelection', {
                                        'value': op == 'enable',
                                      })
                                    : command(op),
                                itemBuilder: (_) => [
                                  item('enable', '启用所选', 'Enable selected'),
                                  item('disable', '禁用所选', 'Disable selected'),
                                  item('export', '导出所选', 'Export selected'),
                                  item('share', '分享所选', 'Share selected'),
                                ],
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
          ),
          if (s != null) ...dialogs(s),
        ],
      ),
    );
  }

  List<Widget> dialogs(DictionaryManagerSnapshot s) {
    final enabled = !s.busy;
    Widget action(
      String op,
      String cn,
      String en, [
      Map<String, Object?> args = const {},
    ]) => TextButton(
      onPressed:
          enabled ||
              [
                'online',
                'cancelDelete',
                'closeExport',
                'closePassphrase',
              ].contains(op)
          ? () => command(op, args)
          : null,
      child: Semantics(
        identifier: 'dictionary-action:$op',
        child: Text(tr(cn, en)),
      ),
    );
    Widget dialog(
      Widget content,
      List<Widget> actions,
      String title,
      String cancel,
    ) => Positioned.fill(
      child: BlockSemantics(
        child: Stack(
          children: [
            ModalBarrier(
              color: Colors.black54,
              dismissible: true,
              semanticsLabel: tr('关闭对话框', 'Close dialog'),
              onDismiss: () => command(
                cancel,
                cancel == 'online' ? {'value': false} : const {},
              ),
            ),
            Center(
              child: SingleChildScrollView(
                child: AlertDialog(
                  title: Text(title),
                  content: content,
                  actions: [
                    ...actions,
                    action(
                      cancel,
                      '取消',
                      'Cancel',
                      cancel == 'online' ? {'value': false} : const {},
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
    if (s.deleteName != null) {
      return [
        dialog(
          Text('${tr('确认删除', 'Delete rule')} ${s.deleteName}？'),
          [action('confirmDelete', '确定', 'Delete')],
          tr('提示', 'Confirm'),
          'cancelDelete',
        ),
      ];
    }
    if (s.online) {
      return [
        dialog(
          SizedBox(
            width: 360,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Semantics(
                  identifier: 'dictionary-online-input',
                  child: TextField(
                    key: const ValueKey('dictionary-online-input'),
                    controller: input,
                    enabled: enabled,
                    onChanged: (value) => command('input', {'value': value}),
                    decoration: InputDecoration(
                      labelText: tr('规则地址', 'Rule URL'),
                    ),
                  ),
                ),
                SizedBox(
                  height: 180,
                  child: ListView(
                    children: s.history
                        .where(
                          (url) => url.toLowerCase().contains(
                            input.text.toLowerCase(),
                          ),
                        )
                        .map(
                          (url) => Row(
                            children: [
                              Expanded(
                                child: TextButton(
                                  onPressed: enabled
                                      ? () => command('input', {'value': url})
                                      : null,
                                  child: Text(url),
                                ),
                              ),
                              IconButton(
                                tooltip: tr('删除记录', 'Delete history'),
                                onPressed: enabled
                                    ? () => command('deleteHistory', {
                                        'value': url,
                                      })
                                    : null,
                                icon: const Icon(Icons.delete_outline),
                              ),
                            ],
                          ),
                        )
                        .toList(),
                  ),
                ),
              ],
            ),
          ),
          [action('confirmOnline', '导入', 'Import')],
          tr('在线导入', 'Import online'),
          'online',
        ),
      ];
    }
    if (s.exportUrl != null) {
      return [
        dialog(
          Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (s.summary.isNotEmpty) Text(s.summary),
              SelectableText(s.exportUrl!),
            ],
          ),
          [
            action('copyExport', '复制地址', 'Copy path'),
            if (s.canEncode)
              action('createPassphrase', '生成口令', 'Create passphrase'),
          ],
          tr('导出成功', 'Exported'),
          'closeExport',
        ),
      ];
    }
    if (s.passphrase != null) {
      return [
        dialog(
          SelectableText(s.passphrase!),
          [action('copyPassphrase', '复制', 'Copy')],
          tr('分享口令', 'Share passphrase'),
          'closePassphrase',
        ),
      ];
    }
    return [];
  }
}
