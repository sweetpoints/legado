import 'dart:async';

import 'package:flutter/semantics.dart';
import 'package:material_ui/material_ui.dart';

import 'rss_manager_bridge.dart';

/// Presentation only. Native Room and the disk-backed manager session own every mutation.
class RssManagerPage extends StatefulWidget {
  const RssManagerPage({super.key, this.bridge});
  final RssManagerBridge? bridge;
  @override
  State<RssManagerPage> createState() => _RssManagerPageState();
}

class _RssManagerPageState extends State<RssManagerPage> {
  late final bridge = widget.bridge ?? RssManagerBridge();
  late Stream<RssManagerSnapshot> stream = bridge.snapshots();
  bool streamFailed = false;
  bool syncing = false;
  @override
  void initState() {
    super.initState();
    query.addListener(() {
      if (!syncing) field('query', query);
    });
    draft.addListener(() {
      if (!syncing) field('draft', draft);
    });
  }

  final query = TextEditingController();
  final draft = TextEditingController();
  final scroll = ScrollController();
  final viewport = GlobalKey();
  Timer? slideTimer;
  Offset? slidePointer;
  String? slideAnchor;
  int? slidePointerId;
  String? slidePointerSource;
  final ignoredSlidePointers = <int, String>{};
  final ignoredTapSources = <String>{};
  int? reorderOrigin;
  int? reorderPointer;
  String? reorderSource;

  void clearReorder() {
    reorderOrigin = null;
    reorderPointer = null;
    reorderSource = null;
  }

  RssManagerSnapshot? latest;
  void slide(Offset pointer) {
    slidePointer = pointer;
    final s = latest;
    final anchor = slideAnchor;
    final box = viewport.currentContext?.findRenderObject() as RenderBox?;
    if (s == null ||
        anchor == null ||
        box == null ||
        !scroll.hasClients ||
        s.rows.isEmpty) {
      return;
    }
    final y = box.globalToLocal(pointer).dy;
    if (y < 32 || y > box.size.height - 32) {
      scroll.jumpTo(
        (scroll.offset + (y < 32 ? -16 : 16)).clamp(
          0,
          scroll.position.maxScrollExtent,
        ),
      );
    }
    final index = ((scroll.offset + y) / 72).floor().clamp(
      0,
      s.rows.length - 1,
    );
    command('selectionRange', {
      'ids': [anchor, s.rows[index]['id']],
    });
  }

  void startSlide(String id, Offset pointer) {
    if (slidePointerId == null ||
        slidePointerSource != id ||
        slideAnchor != null) {
      return;
    }
    slideAnchor = id;
    command('beginSelection');
    slide(pointer);
    slideTimer = Timer.periodic(const Duration(milliseconds: 80), (_) {
      if (slidePointer != null) slide(slidePointer!);
    });
  }

  void endSlide(bool cancelled) {
    if (slideAnchor == null) return;
    slideTimer?.cancel();
    slideTimer = null;
    slidePointer = null;
    slideAnchor = null;
    slidePointerId = null;
    slidePointerSource = null;
    command(cancelled ? 'cancelGesture' : 'finishSelection');
  }

  bool restoring = false;
  bool restored = false;
  String? failure;
  String tr(String en, String zh) =>
      Localizations.localeOf(context).languageCode == 'zh' ? zh : en;
  Future<void> command(
    String op, [
    Map<String, Object?> args = const {},
  ]) async {
    try {
      if (op == 'retry') {
        await bridge.retryField();
        if (latest == null || streamFailed) {
          setState(() {
            failure = null;
            stream = bridge.snapshots();
          });
          return;
        }
      }
      await bridge.command(op, args);
      if ((op == 'query' || op == 'draft') && mounted && failure != null) {
        setState(() => failure = null);
      }
    } catch (_) {
      if (mounted) {
        setState(() => failure = tr('Operation failed. Retry.', '操作失败，请重试。'));
      }
    }
  }

  final pendingFields = <String, int>{};
  void field(String op, TextEditingController controller) {
    pendingFields[op] = (pendingFields[op] ?? 0) + 1;
    unawaited(
      command(op, {
        'value': controller.text,
        'start': controller.selection.start.clamp(0, controller.text.length),
        'end': controller.selection.end.clamp(0, controller.text.length),
      }).whenComplete(() => pendingFields[op] = (pendingFields[op] ?? 1) - 1),
    );
  }

  void sync(TextEditingController c, RssManagerSnapshot s, String key) {
    if ((pendingFields[key] ?? 0) > 0 || failure != null) return;
    final text = s.data[key] as String;
    final start = (s.data['${key}Start'] as int).clamp(0, text.length);
    final end = (s.data['${key}End'] as int).clamp(0, text.length);
    if (c.text != text ||
        c.selection != TextSelection(baseOffset: start, extentOffset: end)) {
      syncing = true;
      c.value = TextEditingValue(
        text: text,
        selection: TextSelection(baseOffset: start, extentOffset: end),
      );
      syncing = false;
    }
  }

  @override
  void dispose() {
    slideTimer?.cancel();
    unawaited(bridge.close());
    query.dispose();
    draft.dispose();
    scroll.dispose();
    super.dispose();
  }

  void effect(String action, [String? id]) => unawaited(
    command('effect', {
      'value': action,
      'ids': id == null ? <String>[] : [id],
    }),
  );
  void openDialog(String value, List<String> ids) =>
      unawaited(command('dialog', {'value': value, 'ids': ids}));
  Widget menu(
    String label,
    List<PopupMenuEntry<String>> entries,
    void Function(String) action, {
    bool enabled = true,
    Key? key,
  }) => Semantics(
    identifier: key is ValueKey<String> ? key.value : null,
    child: PopupMenuButton<String>(
      key: key,
      tooltip: label,
      enabled: enabled,
      itemBuilder: (_) => entries,
      onSelected: action,
    ),
  );
  PopupMenuItem<String> item(String value, String en, String zh) =>
      PopupMenuItem(
        value: value,
        child: Semantics(
          identifier: 'rss-manager-action:$value',
          child: Text(tr(en, zh)),
        ),
      );

  @override
  Widget build(BuildContext context) => StreamBuilder<RssManagerSnapshot>(
    stream: stream,
    builder: (context, snapshot) {
      streamFailed = snapshot.hasError;
      final s = snapshot.data;
      if (s == null) {
        return Scaffold(
          appBar: AppBar(
            title: Text(tr('RSS sources', '订阅源管理')),
            leading: BackButton(onPressed: () => command('back')),
          ),
          body: Center(
            child: snapshot.hasError
                ? TextButton(
                    onPressed: () => command('retry'),
                    child: Text(tr('Retry', '重试')),
                  )
                : const CircularProgressIndicator(),
          ),
        );
      }
      latest = s;
      sync(query, s, 'query');
      sync(draft, s, 'draft');
      if (!restored && s.data['loaded'] == true && s.rows.isNotEmpty) {
        restored = true;
        restoring = true;
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted && scroll.hasClients) {
            scroll.jumpTo(
              ((s.data['scrollIndex'] as int) * 72.0 +
                      (s.data['scrollOffset'] as int))
                  .clamp(0, scroll.position.maxScrollExtent),
            );
          }
          restoring = false;
        });
      }
      final selected = s.selection;
      final normal = s.editable && s.data['dialog'] == null;
      final output = ['ExportResult', 'Passphrase'].contains(s.data['dialog']);
      return PopScope(
        canPop: false,
        onPopInvokedWithResult: (didPop, _) {
          if (!didPop) {
            if (s.data['dialog'] != null) {
              command('cancel');
            } else {
              command('back');
            }
          }
        },
        child: Stack(
          children: [
            Scaffold(
              appBar: AppBar(
                title: Text(tr('RSS sources', '订阅源管理')),
                leading: BackButton(onPressed: () => command('back')),
                actions: [
                  menu(
                    tr('Filters and groups', '筛选与分组'),
                    [
                      item('', 'All', '全部'),
                      item('enabled', 'Enabled', '已启用'),
                      item('disabled', 'Disabled', '已禁用'),
                      item('login', 'Login required', '需要登录'),
                      item('none', 'No group', '未分组'),
                      ...s
                          .strings('groups')
                          .map(
                            (g) => PopupMenuItem(
                              value: 'group:$g',
                              child: Text(g),
                            ),
                          ),
                      item('@groups', 'Manage groups', '分组管理'),
                    ],
                    (v) {
                      if (v == '@groups') {
                        effect('Groups');
                        return;
                      }
                      if ([
                        '',
                        'enabled',
                        'disabled',
                        'login',
                        'none',
                      ].contains(v)) {
                        command('filter', {'value': v.isEmpty ? 'all' : v});
                      } else {
                        command('query', {
                          'value': v,
                          'start': v.length,
                          'end': v.length,
                        });
                      }
                    },
                    enabled: s.nativeEditable,
                    key: const ValueKey('rss-manager-filters'),
                  ),
                  menu(
                    tr('More', '更多'),
                    [
                      item('Add', 'Add source', '新增订阅源'),
                      item('ImportLocal', 'Import local file', '本地导入'),
                      item('url', 'Import URL', '网络导入'),
                      item('ImportQr', 'Scan QR code', '二维码导入'),
                      item('defaults', 'Import defaults', '导入默认规则'),
                      item('Help', 'Help', '帮助'),
                    ],
                    (v) {
                      if (v == 'url') {
                        openDialog('ImportUrl', []);
                      } else if (v == 'defaults') {
                        command('defaults');
                      } else {
                        effect(v);
                      }
                    },
                    enabled: s.nativeEditable,
                    key: const ValueKey('rss-manager-menu'),
                  ),
                ],
              ),
              body: SafeArea(
                child: Column(
                  children: [
                    Padding(
                      padding: const EdgeInsets.all(12),
                      child: Semantics(
                        identifier: 'rss-manager-query',
                        child: TextField(
                          key: const ValueKey('rss-manager-query'),
                          controller: query,
                          enabled: normal,
                          decoration: InputDecoration(
                            labelText: tr('Search RSS sources', '搜索订阅源'),
                            prefixIcon: const Icon(Icons.search),
                          ),

                          onTapOutside: (_) => FocusScope.of(context).unfocus(),
                          textInputAction: TextInputAction.search,
                          onSubmitted: (_) => FocusScope.of(context).unfocus(),
                        ),
                      ),
                    ),
                    if (s.data['busy'] == true) const LinearProgressIndicator(),
                    if (s.data['error'] == true ||
                        failure != null ||
                        snapshot.hasError)
                      Row(
                        children: [
                          Expanded(
                            child: Text(
                              failure ??
                                  tr('Operation failed. Retry.', '操作失败，请重试。'),
                            ),
                          ),
                          TextButton(
                            onPressed: s.data['busy'] == true
                                ? null
                                : () {
                                    setState(() => failure = null);
                                    command('retry');
                                  },
                            child: Text(tr('Retry', '重试')),
                          ),
                        ],
                      ),
                    Expanded(
                      child: NotificationListener<ScrollEndNotification>(
                        onNotification: (n) {
                          if (restored && !restoring && scroll.hasClients) {
                            final offset = scroll.offset.clamp(
                              0,
                              double.infinity,
                            );
                            command('scroll', {
                              'index': offset ~/ 72,
                              'offset': (offset % 72).round(),
                            });
                          }
                          return false;
                        },
                        child: Scrollbar(
                          controller: scroll,
                          interactive: true,
                          child: ReorderableListView.builder(
                            key: viewport,
                            buildDefaultDragHandles: false,
                            onReorderStart: (index) {
                              reorderOrigin = index;
                              command('beginDrag', {
                                'ids': [s.rows[index]['id']],
                              });
                            },
                            onReorderItem: (oldIndex, newIndex) {
                              if (reorderOrigin != oldIndex ||
                                  oldIndex == newIndex) {
                                return;
                              }
                              clearReorder();
                              final after = newIndex > oldIndex;
                              final target = s.rows[newIndex]['id'];
                              command('dragTo', {
                                'ids': [target],
                                'after': after,
                              });
                              command('finishDrag');
                            },
                            onReorderEnd: (insertIndex) {
                              final origin = reorderOrigin;
                              if (origin == null) return;
                              // onReorderEnd precedes the proxy's drop animation and
                              // uses the insertion index before removing the old row.
                              final target = insertIndex > origin
                                  ? insertIndex - 1
                                  : insertIndex;
                              if (target == origin) {
                                clearReorder();
                                command('cancelGesture');
                              }
                              // A changed drop retains its native session until
                              // onReorderItem runs after the proxy animation.
                            },
                            scrollController: scroll,
                            itemExtent: 72,
                            itemCount: s.rows.length,
                            itemBuilder: (context, index) {
                              final row = s.rows[index];
                              final id = row['id'] as String;
                              final name = row['name'] as String;
                              return Semantics(
                                key: ValueKey('rss-manager-row-$id'),
                                identifier: 'rss-manager-row-$id',
                                label: name,
                                selected: selected.contains(id),
                                customSemanticsActions: {
                                  CustomSemanticsAction(
                                    label: tr('Move up', '上移'),
                                  ): () {
                                    if (index > 0) {
                                      command('move', {
                                        'ids': [id, s.rows[index - 1]['id']],
                                        'after': false,
                                      });
                                    }
                                  },
                                  CustomSemanticsAction(
                                    label: tr('Move down', '下移'),
                                  ): () {
                                    if (index + 1 < s.rows.length) {
                                      command('move', {
                                        'ids': [id, s.rows[index + 1]['id']],
                                        'after': true,
                                      });
                                    }
                                  },
                                },
                                child: Row(
                                  children: [
                                    ReorderableDragStartListener(
                                      index: index,
                                      enabled: normal,
                                      child: Listener(
                                        onPointerDown: (event) {
                                          if (!normal) return;
                                          // The SDK cancels an active visual reorder when
                                          // another enabled handle pointer goes down.
                                          // End that same native session before its successor.
                                          if (reorderOrigin != null &&
                                              reorderPointer != event.pointer) {
                                            clearReorder();
                                            command('cancelGesture');
                                          }
                                          reorderPointer = event.pointer;
                                          reorderSource = id;
                                        },
                                        onPointerCancel: (event) {
                                          // Flutter's drag-cancel path has no onReorderEnd.
                                          // Only the pointer that started this handle's
                                          // active native session may cancel it.
                                          if (reorderPointer == event.pointer &&
                                              reorderSource == id) {
                                            final active =
                                                reorderOrigin != null;
                                            clearReorder();
                                            if (active) {
                                              command('cancelGesture');
                                            }
                                          }
                                        },
                                        onPointerUp: (event) {
                                          if (reorderOrigin == null &&
                                              reorderPointer == event.pointer &&
                                              reorderSource == id) {
                                            clearReorder();
                                          }
                                        },
                                        child: Semantics(
                                          identifier: 'rss-manager-reorder-$id',
                                          label: tr(
                                            'Reorder $name',
                                            '拖动排序 $name',
                                          ),
                                          child: const Padding(
                                            padding: EdgeInsets.all(8),
                                            child: Icon(Icons.drag_handle),
                                          ),
                                        ),
                                      ),
                                    ),
                                    Semantics(
                                      identifier: 'rss-manager-select-$id',
                                      child: Listener(
                                        onPointerDown: (event) {
                                          if (!normal) return;
                                          if (slidePointerId == null) {
                                            slidePointerId = event.pointer;
                                            slidePointerSource = id;
                                          } else if (slidePointerId !=
                                              event.pointer) {
                                            ignoredSlidePointers[event
                                                    .pointer] =
                                                id;
                                          }
                                        },
                                        onPointerCancel: (event) {
                                          ignoredSlidePointers.remove(
                                            event.pointer,
                                          );
                                          if (slidePointerId == event.pointer &&
                                              slidePointerSource == id) {
                                            // Accepted Flutter drags report onPanEnd
                                            // even for raw pointer cancellation.
                                            endSlide(true);
                                            slidePointerId = null;
                                            slidePointerSource = null;
                                          }
                                        },
                                        onPointerUp: (event) {
                                          if (ignoredSlidePointers.remove(
                                                event.pointer,
                                              ) ==
                                              id) {
                                            // The ignored pointer may win its Checkbox's
                                            // tap arena in this same dispatch. Block only
                                            // that tap, then allow later accessible clicks.
                                            ignoredTapSources.add(id);
                                            scheduleMicrotask(
                                              () =>
                                                  ignoredTapSources.remove(id),
                                            );
                                          } else if (slideAnchor == null &&
                                              slidePointerId == event.pointer &&
                                              slidePointerSource == id) {
                                            slidePointerId = null;
                                            slidePointerSource = null;
                                          }
                                        },
                                        child: GestureDetector(
                                          onPanStart: normal
                                              ? (d) => startSlide(
                                                  id,
                                                  d.globalPosition,
                                                )
                                              : null,
                                          onPanUpdate: normal
                                              ? (d) {
                                                  if (slidePointerSource ==
                                                          id &&
                                                      slideAnchor == id) {
                                                    slide(d.globalPosition);
                                                  }
                                                }
                                              : null,
                                          onPanEnd: normal
                                              ? (_) {
                                                  if (slidePointerSource ==
                                                      id) {
                                                    endSlide(false);
                                                  }
                                                }
                                              : null,
                                          onPanCancel: normal
                                              ? () {
                                                  if (slidePointerSource ==
                                                      id) {
                                                    endSlide(true);
                                                  }
                                                }
                                              : null,
                                          child: Checkbox(
                                            semanticLabel: tr(
                                              'Select $name',
                                              '选择 $name',
                                            ),
                                            value: selected.contains(id),
                                            onChanged: normal
                                                ? (v) {
                                                    if (slidePointerId ==
                                                            null &&
                                                        slideAnchor == null &&
                                                        !ignoredTapSources
                                                            .contains(id)) {
                                                      command('selected', {
                                                        'ids': [id],
                                                        'checked': v!,
                                                      });
                                                    }
                                                  }
                                                : null,
                                          ),
                                        ),
                                      ),
                                    ),
                                    Expanded(
                                      child: InkWell(
                                        onTap: normal
                                            ? () => command('selected', {
                                                'ids': [id],
                                                'checked': !selected.contains(
                                                  id,
                                                ),
                                              })
                                            : null,
                                        child: Text(
                                          name,
                                          maxLines: 2,
                                          overflow: TextOverflow.ellipsis,
                                        ),
                                      ),
                                    ),
                                    Semantics(
                                      identifier: 'rss-manager-enabled-$id',
                                      label: tr('Enable $name', '启用 $name'),
                                      child: Switch(
                                        value: row['enabled'] as bool,
                                        onChanged: normal
                                            ? (v) => command('enabled', {
                                                'ids': [id],
                                                'value': v,
                                              })
                                            : null,
                                      ),
                                    ),
                                    IconButton(
                                      tooltip: tr('Edit $name', '编辑 $name'),
                                      onPressed: s.nativeEditable
                                          ? () => effect('Edit', id)
                                          : null,
                                      icon: const Icon(Icons.edit),
                                    ),
                                    menu(
                                      tr('Source actions', '订阅源操作'),
                                      [
                                        item('up', 'Move up', '上移'),
                                        item('down', 'Move down', '下移'),
                                        item('top', 'Move to top', '置顶'),
                                        item('bottom', 'Move to bottom', '置底'),
                                        item('delete', 'Delete', '删除'),
                                      ],
                                      (v) {
                                        if (v == 'delete') {
                                          openDialog('Delete', [id]);
                                        } else if (v == 'top' ||
                                            v == 'bottom') {
                                          command('edge', {
                                            'ids': [id],
                                            'value': v == 'top',
                                          });
                                        } else {
                                          final target = v == 'up'
                                              ? index - 1
                                              : index + 1;
                                          if (target >= 0 &&
                                              target < s.rows.length) {
                                            command('move', {
                                              'ids': [id, s.rows[target]['id']],
                                              'after': v == 'down',
                                            });
                                          }
                                        }
                                      },
                                      enabled: normal,
                                    ),
                                  ],
                                ),
                              );
                            },
                          ),
                        ),
                      ),
                    ),
                    Wrap(
                      crossAxisAlignment: WrapCrossAlignment.center,
                      children: [
                        Text('${selected.length}/${s.rows.length}'),
                        TextButton(
                          onPressed: normal
                              ? () => command(
                                  selected.length == s.rows.length
                                      ? 'invert'
                                      : 'all',
                                )
                              : null,
                          child: Text(tr('Select all', '全选')),
                        ),
                        TextButton(
                          onPressed: normal ? () => command('invert') : null,
                          child: Text(tr('Invert', '反选')),
                        ),
                        menu(
                          tr('Selection actions', '批量操作'),
                          [
                            item('interval', 'Select interval', '选择区间'),
                            item('enable', 'Enable', '启用'),
                            item('disable', 'Disable', '禁用'),
                            item('AddGroup', 'Add group', '添加分组'),
                            item('RemoveGroup', 'Remove group', '移除分组'),
                            item('top', 'Move to top', '置顶'),
                            item('bottom', 'Move to bottom', '置底'),
                            item('Export', 'Export', '导出'),
                            item('Share', 'Share', '分享'),
                            item('Delete', 'Delete', '删除'),
                          ],
                          (v) {
                            switch (v) {
                              case 'interval':
                                command('interval');
                              case 'enable':
                              case 'disable':
                                command('enabled', {
                                  'ids': selected,
                                  'value': v == 'enable',
                                });
                              case 'top':
                              case 'bottom':
                                command('edge', {
                                  'ids': selected,
                                  'value': v == 'top',
                                });
                              case 'Export':
                              case 'Share':
                                if (s.nativeEditable) effect(v);
                              default:
                                openDialog(v, selected);
                            }
                          },
                          enabled: normal && selected.isNotEmpty,
                          key: const ValueKey('rss-manager-bulk'),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
            if (s.data['dialog'] != null)
              Positioned.fill(
                child: Stack(
                  children: [
                    ModalBarrier(
                      key: const ValueKey('rss-manager-modal-barrier'),
                      color: Theme.of(context).colorScheme.scrim
                          .withValues(alpha: 0.54),
                      dismissible: s.editable,
                      semanticsLabel: tr('Dismiss dialog', '关闭对话框'),
                      onDismiss: () => command('cancel'),
                    ),
                    SafeArea(
                      child: Padding(
                        padding: const EdgeInsets.all(24),
                        child: Card(
                          child: Padding(
                            padding: const EdgeInsets.all(16),
                            child: ListView(
                              children: [
                                Text(
                                  switch (s.data['dialog']) {
                                    'Delete' => tr(
                                      'Delete selected sources?',
                                      '删除所选订阅源？',
                                    ),
                                    'AddGroup' => tr('Add group', '添加分组'),
                                    'RemoveGroup' => tr('Remove group', '移除分组'),
                                    'ImportUrl' => tr('Import URL', '网络导入'),
                                    'ExportResult' => tr(
                                      'Export succeeded',
                                      '导出成功',
                                    ),
                                    _ => tr('Passphrase', '口令'),
                                  },
                                  style: Theme.of(context).textTheme.titleLarge,
                                ),
                                if (s.data['dialog'] == 'Delete')
                                  ...s.rows
                                      .where(
                                        (r) => s
                                            .strings('targets')
                                            .contains(r['id']),
                                      )
                                      .map((r) => Text(r['name'] as String))
                                else ...[
                                  if (output && s.data['feedback'] != null)
                                    Text(
                                      (s.data['feedback'] as Map)['summary']
                                          as String,
                                    ),
                                  Semantics(
                                    identifier: 'rss-manager-draft',
                                    child: TextField(
                                      key: const ValueKey('rss-manager-draft'),
                                      controller: draft,
                                      readOnly: output,
                                      enabled: s.editable,

                                      decoration: InputDecoration(
                                        labelText: output
                                            ? tr('Result', '结果')
                                            : s.data['dialog'] == 'ImportUrl'
                                            ? 'URL'
                                            : tr('Group name', '分组名称'),
                                      ),
                                    ),
                                  ),
                                  if (s.data['dialog'] == 'ImportUrl')
                                    ...s
                                        .strings('history')
                                        .map(
                                          (v) => Row(
                                            children: [
                                              Expanded(
                                                child: TextButton(
                                                  onPressed: s.editable
                                                      ? () => command('draft', {
                                                          'value': v,
                                                          'start': v.length,
                                                          'end': v.length,
                                                        })
                                                      : null,
                                                  child: Text(v),
                                                ),
                                              ),
                                              IconButton(
                                                tooltip: tr(
                                                  'Forget URL',
                                                  '删除记录',
                                                ),
                                                onPressed: s.editable
                                                    ? () => command(
                                                        'forgetImport',
                                                        {'value': v},
                                                      )
                                                    : null,
                                                icon: const Icon(Icons.delete),
                                              ),
                                            ],
                                          ),
                                        ),
                                  if ([
                                    'AddGroup',
                                    'RemoveGroup',
                                  ].contains(s.data['dialog']))
                                    ...s
                                        .strings('groups')
                                        .map(
                                          (g) => TextButton(
                                            onPressed: s.editable
                                                ? () => command('draft', {
                                                    'value': g,
                                                    'start': g.length,
                                                    'end': g.length,
                                                  })
                                                : null,
                                            child: Text(g),
                                          ),
                                        ),
                                  if (s.data['dialog'] == 'ExportResult' &&
                                      (s.data['feedback']
                                              as Map?)?['canEncode'] ==
                                          true)
                                    TextButton(
                                      onPressed: s.editable
                                          ? () => command('passphrase')
                                          : null,
                                      child: Text(
                                        tr('Create passphrase', '生成口令'),
                                      ),
                                    ),
                                ],
                                Wrap(
                                  children: [
                                    TextButton(
                                      onPressed: s.editable
                                          ? () => command('cancel')
                                          : null,
                                      child: Text(tr('Cancel', '取消')),
                                    ),
                                    FilledButton(
                                      onPressed: s.editable
                                          ? () => command(
                                              output ? 'copy' : 'confirm',
                                            )
                                          : null,
                                      child: Text(
                                        output
                                            ? tr('Copy', '复制')
                                            : tr('Confirm', '确认'),
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
                  ],
                ),
              ),
          ],
        ),
      );
    },
  );
}
