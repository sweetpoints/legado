import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart' show listEquals;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import '../../l10n/generated/app_localizations.dart';
import 'source_manager_bridge.dart';
import 'source_manager_labels.dart';
import 'source_manager_state.dart';

/// A projection of ManagerViewModel; no selection, sorting or mutation is stored
/// in Dart. Controllers and drag coordinates are only transient input state.
class SourceManagerPage extends ConsumerStatefulWidget {
  const SourceManagerPage({super.key});
  @override
  ConsumerState<SourceManagerPage> createState() => _SourceManagerPageState();
}

class _SourceManagerPageState extends ConsumerState<SourceManagerPage> {
  final _scroll = ScrollController();
  final _viewport = GlobalKey();
  final _rowBoxes = <String, GlobalKey>{};
  SourceManagerSnapshot? _latest;
  SourceManagerBridge? _bridge;
  BuildContext? _dialogContext;
  int _reorderOrigin = 0;
  SliverReorderableListState? _activeReorderList;
  List<String> _reorderUrls = const [];
  bool _dialogOpen = false, _dialogClosing = false, _reordering = false;
  Timer? _slideTimer;
  Offset? _pointer;
  List<String>? _slideRows;
  Set<String> _slideStart = {};
  int _slideAnchor = 0;
  String? _lastSlide;

  Future<void> _send(String op, [Map<String, Object?> args = const {}]) async {
    try {
      await _bridge!.command(op, args);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(e.toString())));
      }
    }
  }

  Future<void> _action(String action, [String key = '']) =>
      _send('action', {'action': action, 'key': key});
  Future<void> _back() async {
    final state = _latest;
    if (state == null) {
      await _action('back');
      return;
    }
    if (state.busy) return;
    if (state.dialog != null) {
      await _send('dismiss');
    } else if (state.selected.isNotEmpty) {
      await _send('slide', {'keys': <String>[], 'selectedAtStart': <String>[]});
    } else {
      await _action('back');
    }
  }

  void _syncDialog(SourceManagerSnapshot state) {
    if (state.dialog != null && !_dialogOpen) {
      _dialogOpen = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        unawaited(
          showDialog<void>(
            context: context,
            barrierDismissible: true,
            builder: (dialogContext) {
              _dialogContext = dialogContext;
              return _ManagerDialog(send: _send);
            },
          ).whenComplete(() {
            _dialogOpen = false;
            _dialogClosing = false;
            _dialogContext = null;
            if (mounted &&
                _latest?.dialog != null &&
                !(_latest?.busy ?? true)) {
              unawaited(_send('dismiss'));
            }
          }),
        );
      });
    } else if (state.dialog == null && _dialogOpen && !_dialogClosing) {
      _dialogClosing = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        final dialog = _dialogContext;
        if (dialog != null &&
            dialog.mounted &&
            (ModalRoute.of(dialog)?.isCurrent ?? false)) {
          Navigator.of(dialog).pop();
        }
      });
    }
  }

  @override
  void dispose() {
    _slideTimer?.cancel();
    if (_reordering) {
      unawaited(
        _bridge
                ?.command('finishDrag', {'cancelled': true})
                .catchError((Object _) {}) ??
            Future.value(),
      );
    }
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    _bridge = ref.watch(sourceManagerBridgeProvider);
    ref.listen(sourceManagerStateProvider, (_, next) {
      final state = next.asData?.value;
      if (state != null) {
        if (_reordering &&
            !listEquals(
              _reorderUrls,
              state.rows.map((row) => row.url).toList(),
            )) {
          // Cancel before layout replaces rows: the framework's item-count
          // cancellation happens during layout, too late to reparent row keys safely.
          _reordering = false;
          _activeReorderList?.cancelReorder();
          _activeReorderList = null;
          _reorderUrls = const [];
          unawaited(_send('finishDrag', {'cancelled': true}));
        }
        _latest = state;
        _syncDialog(state);
      }
    });
    final strings = AppLocalizations.of(context);
    return BackButtonListener(
      onBackButtonPressed: () async {
        if (_latest?.dialog == null &&
            !(ModalRoute.of(context)?.isCurrent ?? true)) {
          return false; // Let Navigator close its popup route before native navigation.
        }
        await _back();
        return true;
      },
      child: PopScope(
        canPop: false,
        onPopInvokedWithResult: (didPop, _) {
          if (!didPop) unawaited(_back());
        },
        child: ref
            .watch(sourceManagerStateProvider)
            .when(
              loading: () => Scaffold(
                appBar: AppBar(
                  title: Text(strings.sourceManagerTitle),
                  leading: BackButton(onPressed: _back),
                ),
                body: const Center(child: CircularProgressIndicator()),
              ),
              error: (error, _) => Scaffold(
                appBar: AppBar(
                  title: Text(strings.sourceManagerTitle),
                  leading: BackButton(onPressed: _back),
                ),
                body: Center(
                  child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(strings.sourceManagerLoadFailed),
                        const SizedBox(height: 12),
                        SelectableText(error.toString()),
                        FilledButton(
                          onPressed: () =>
                              ref.invalidate(sourceManagerStateProvider),
                          child: Text(strings.sourceManagerRetry),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
              data: (state) {
                _latest = state;
                _syncDialog(state);
                return Scaffold(
                  appBar: AppBar(
                    title: Text(strings.sourceManagerTitle),
                    leading: BackButton(onPressed: _back),
                    actions: [_mainMenu(state)],
                  ),
                  body: SafeArea(
                    child: Column(
                      children: [
                        Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 8),
                          child: _NativeValueField(
                            identifier: 'source-manager-query',
                            value: state.query,
                            label: strings.sourceManagerQuery,
                            enabled: !state.busy,
                            onChanged: (value) =>
                                _send('query', {'value': value}),
                          ),
                        ),
                        if (state.showStatus)
                          Wrap(
                            alignment: WrapAlignment.spaceEvenly,
                            children: [
                              for (final status in [
                                '',
                                'NEEDS_CHECK',
                                'PASSED',
                                'FAILED',
                              ])
                                ChoiceChip(
                                  key: ValueKey(
                                    'source-manager-status:$status',
                                  ),
                                  label: Text(_status(strings, status)),
                                  selected: state.status == status,
                                  onSelected: state.busy
                                      ? null
                                      : (_) =>
                                            _send('status', {'value': status}),
                                ),
                            ],
                          ),
                        if (state.loading || state.busy)
                          const LinearProgressIndicator(),
                        if (state.error != null)
                          Padding(
                            padding: const EdgeInsets.all(8),
                            child: Row(
                              children: [
                                Expanded(
                                  child: Text(
                                    state.error!,
                                    style: TextStyle(
                                      color: Theme.of(context)
                                          .colorScheme
                                          .error,
                                    ),
                                  ),
                                ),
                                TextButton(
                                  onPressed: state.busy
                                      ? null
                                      : () => _action('retry'),
                                  child: Text(strings.sourceManagerRetry),
                                ),
                              ],
                            ),
                          ),
                        if (state.checkMessage != null)
                          Semantics(
                            identifier: 'source-manager-progress',
                            child: Row(
                              children: [
                                Expanded(child: Text(state.checkMessage!)),
                                TextButton(
                                  onPressed: () => _action('cancel-check'),
                                  child: Text(strings.sourceManagerCancel),
                                ),
                              ],
                            ),
                          ),
                        Expanded(
                          child: SizedBox(
                            key: _viewport,
                            child: state.rows.isEmpty
                                ? Center(
                                    child: Text(strings.sourceManagerEmpty),
                                  )
                                : ReorderableListView.builder(
                                    key: const ValueKey('source-manager-list'),
                                    scrollController: _scroll,
                                    buildDefaultDragHandles: false,
                                    autoScrollerVelocityScalar: 60,
                                    itemCount: state.rows.length,
                                    onReorderStart: (index) {
                                      _reordering = true;
                                      _reorderOrigin = index;
                                      _activeReorderList =
                                          _rowBoxes[state.rows[index].url]
                                              ?.currentContext
                                              ?.findAncestorStateOfType<
                                                SliverReorderableListState
                                              >();
                                      _reorderUrls = state.rows
                                          .map((row) => row.url)
                                          .toList();
                                      unawaited(
                                        _send('beginDrag', {
                                          'key': state.rows[index].url,
                                        }),
                                      );
                                    },
                                    // Commit only when Flutter's drop animation has finished.
                                    onReorderItem: (_, target) {
                                      if (!_reordering) return;
                                      _reordering = false;
                                      _activeReorderList = null;
                                      _reorderUrls = const [];
                                      unawaited(() async {
                                        await _send('previewDrag', {
                                          'index': target,
                                        });
                                        await _send('finishDrag', {
                                          'cancelled': false,
                                        });
                                      }());
                                    },
                                    onReorderEnd: (insertIndex) {
                                      final target =
                                          insertIndex > _reorderOrigin
                                          ? insertIndex - 1
                                          : insertIndex;
                                      if (_reordering &&
                                          target == _reorderOrigin) {
                                        _reordering = false;
                                        _activeReorderList = null;
                                        _reorderUrls = const [];
                                        unawaited(
                                          _send('finishDrag', {
                                            'cancelled': true,
                                          }),
                                        );
                                      }
                                    },
                                    itemBuilder: (context, index) =>
                                        _liveRow(state.rows[index].url),
                                  ),
                          ),
                        ),
                        Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 8),
                          child: Wrap(
                            alignment: WrapAlignment.spaceBetween,
                            crossAxisAlignment: WrapCrossAlignment.center,
                            spacing: 4,
                            children: [
                              TextButton(
                                key: const ValueKey(
                                  'source-manager-select-all',
                                ),
                                onPressed: state.busy
                                    ? null
                                    : () => _send('selectAll'),
                                child: Text(strings.sourceManagerSelectAll),
                              ),
                              TextButton(
                                key: const ValueKey('source-manager-invert'),
                                onPressed: state.busy
                                    ? null
                                    : () => _send('invert'),
                                child: Text(strings.sourceManagerInvert),
                              ),
                              Semantics(
                                identifier: 'source-manager-count',
                                child: Text(
                                  strings.sourceManagerCount(
                                    state.selectedCount,
                                    state.rows.length,
                                  ),
                                ),
                              ),
                              TextButton(
                                key: const ValueKey(
                                  'source-manager-delete-selected',
                                ),
                                onPressed:
                                    state.selectedCount == 0 || state.busy
                                    ? null
                                    : () => _action('delete'),
                                child: Text(strings.sourceManagerDelete),
                              ),
                              _selectionMenu(state),
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
    );
  }

  Widget _mainMenu(SourceManagerSnapshot state) {
    final s = AppLocalizations.of(context);
    return Semantics(
      identifier: 'source-manager-menu',
      child: PopupMenuButton<String>(
        key: const ValueKey('source-manager-menu'),
        tooltip: s.sourceManagerMenu,
        onSelected: (value) {
          FocusScope.of(context).unfocus();
          if (value.startsWith('sort:')) {
            unawaited(_send('sort', {'value': value.substring(5)}));
          } else if (value.startsWith('group:')) {
            unawaited(_send('query', {'value': value}));
          } else {
            unawaited(_action(value));
          }
        },
        itemBuilder: (_) => [
          for (final action in sourceManagerMainActions)
            PopupMenuItem(
              key: ValueKey('source-manager-action:$action'),
              value: action,
              enabled: !state.busy,
              child: Semantics(
                identifier: 'source-manager-action:$action',
                child: Row(
                  children: [
                    if (action == 'domain')
                      const Padding(
                        padding: EdgeInsets.only(right: 8),
                        child: Icon(Icons.public),
                      ),
                    if ((action == 'descending' && !state.ascending) ||
                        (action == 'domain' && state.byDomain) ||
                        (action == 'show-status' && state.showStatus) ||
                        (action == 'block-navigation' && state.blockNavigation))
                      const Padding(
                        padding: EdgeInsets.only(right: 8),
                        child: Icon(Icons.check),
                      ),
                    Flexible(child: Text(managerActionLabel(s, action))),
                  ],
                ),
              ),
            ),
          const PopupMenuDivider(),
          for (final sort in SourceManagerSnapshot.sorts)
            PopupMenuItem(
              key: ValueKey('source-manager-sort:$sort'),
              value: 'sort:$sort',
              enabled: !state.busy,
              child: Semantics(
                identifier: 'source-manager-sort:$sort',
                child: Text(
                  '${state.sort == sort ? '✓ ' : ''}${managerSortLabel(s, sort)}',
                ),
              ),
            ),
          const PopupMenuDivider(),
          for (final group in state.groups)
            PopupMenuItem(
              key: ValueKey('source-manager-group:$group'),
              value: 'group:$group',
              enabled: !state.busy,
              child: Text(group),
            ),
        ],
      ),
    );
  }

  Widget _selectionMenu(SourceManagerSnapshot state) {
    final s = AppLocalizations.of(context);
    return Semantics(
      identifier: 'source-manager-selection-menu',
      child: PopupMenuButton<String>(
        key: const ValueKey('source-manager-selection-menu'),
        tooltip: s.sourceManagerSelectionMenu,
        enabled: state.selectedCount > 0 && !state.busy,
        onSelected: (value) {
          if (value.startsWith('mutation:')) {
            unawaited(_send('mutation', {'mutation': value.substring(9)}));
          } else {
            unawaited(_action(value));
          }
        },
        itemBuilder: (_) => [
          for (final mutation in sourceManagerBulkMutations)
            PopupMenuItem(
              key: ValueKey('source-manager-mutation:$mutation'),
              value: 'mutation:$mutation',
              child: Text(managerMutationLabel(s, mutation)),
            ),
          for (final action in sourceManagerBulkActions)
            PopupMenuItem(
              key: ValueKey('source-manager-action:$action'),
              value: action,
              child: Text(managerActionLabel(s, action)),
            ),
        ],
      ),
    );
  }

  Widget _liveRow(String url) => Consumer(
    key: ValueKey(url),
    builder: (context, ref, child) {
      // Flutter's drag overlay retains the item widget captured at drag start.
      // Subscribe inside that retained widget instead of capturing its snapshot.
      final state = ref.watch(sourceManagerStateProvider).asData?.value;
      if (state == null) return const SizedBox.shrink();
      final index = state.rows.indexWhere((row) => row.url == url);
      if (index < 0) return const SizedBox.shrink();
      return _row(state, index);
    },
  );

  Widget _row(SourceManagerSnapshot state, int index) {
    final row = state.rows[index];
    final s = AppLocalizations.of(context);
    final debug = state.debugMessages[row.url] ?? '';
    final options = [
      if (state.canMove) ...['top', 'bottom', 'up', 'down'],
      if (row.hasLogin) 'login',
      'search',
      'debug',
      'delete',
      if (row.hasExplore) 'explore',
    ];
    return Column(
      children: [
        if (state.byDomain &&
            (index == 0 || state.rows[index - 1].host != row.host))
          Align(
            alignment: Alignment.centerLeft,
            child: Padding(
              padding: const EdgeInsets.all(8),
              child: Text(
                row.host,
                style: Theme.of(context).textTheme.titleSmall,
              ),
            ),
          ),
        Semantics(
          identifier: 'source-manager-row:${row.url}',
          child: SizedBox(
            key: _rowBoxes.putIfAbsent(row.url, GlobalKey.new),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 4),
              child: Row(
                children: [
                  GestureDetector(
                    onLongPressStart: state.busy
                        ? null
                        : (details) =>
                              _startSlide(state, index, details.globalPosition),
                    onLongPressMoveUpdate: (details) {
                      _pointer = details.globalPosition;
                      _previewSlide();
                    },
                    onLongPressEnd: (_) => _stopSlide(),
                    onLongPressCancel: _stopSlide,
                    child: Semantics(
                      identifier: 'select:${row.url}',
                      label: '${s.sourceManagerSelect} ${row.displayName}',
                      child: Checkbox(
                        key: ValueKey('select:${row.url}'),
                        value: state.selected.contains(row.url),
                        onChanged: state.busy
                            ? null
                            : (_) => _send('toggle', {'key': row.url}),
                      ),
                    ),
                  ),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          row.displayName,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                        Text(
                          s.sourceManagerBookCount(state.counts[row.url] ?? 0),
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                        Wrap(
                          spacing: 8,
                          children: [
                            if (row.hasJs)
                              Text(
                                s.sourceManagerJsBadge,
                                style: Theme.of(context).textTheme.labelSmall,
                              ),
                            if (row.hasExplore)
                              Text(
                                row.exploreEnabled
                                    ? s.sourceManagerExploreOnBadge
                                    : s.sourceManagerExploreOffBadge,
                                style: Theme.of(context).textTheme.labelSmall,
                              ),
                          ],
                        ),
                        if (state.showStatus || debug.isNotEmpty)
                          Text(
                            debug.isEmpty
                                ? '${_status(s, row.checkStatus)}${row.checkDetail.isEmpty ? '' : ': ${row.checkDetail}'}'
                                : debug,
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                        if (debug.isNotEmpty &&
                            !RegExp(
                              '成功|失败|success|fail',
                              caseSensitive: false,
                            ).hasMatch(debug))
                          const LinearProgressIndicator(),
                      ],
                    ),
                  ),
                  Semantics(
                    identifier: 'enabled:${row.url}',
                    label: '${s.sourceManagerEnabled} ${row.displayName}',
                    child: Switch(
                      key: ValueKey('enabled:${row.url}'),
                      value: row.enabled,
                      onChanged: state.busy
                          ? null
                          : (value) => _send('mutation', {
                              'mutation': value ? 'ENABLE' : 'DISABLE',
                              'key': row.url,
                            }),
                    ),
                  ),
                  Semantics(
                    identifier: 'source-manager-action:edit:${row.url}',
                    child: TextButton(
                      onPressed: state.busy
                          ? null
                          : () => _action('edit', row.url),
                      child: Text(s.sourceManagerEdit),
                    ),
                  ),
                  ReorderableDelayedDragStartListener(
                    index: index,
                    enabled: state.canMove,
                    child: Listener(
                      onPointerCancel: (_) {
                        if (_reordering) {
                          _reordering = false;
                          _activeReorderList = null;
                          _reorderUrls = const [];
                          unawaited(_send('finishDrag', {'cancelled': true}));
                        }
                      },
                      child: Semantics(
                        identifier: 'source-manager-row-menu:${row.url}',
                        label: '${s.sourceManagerDrag} ${row.displayName}',
                        child: PopupMenuButton<String>(
                          key: ValueKey('source-manager-row-menu:${row.url}'),
                          tooltip: '',
                          enabled: !state.busy,
                          onSelected: (action) => _action(action, row.url),
                          itemBuilder: (_) => [
                            for (final action in options)
                              PopupMenuItem(
                                key: ValueKey('source-manager-action:$action'),
                                value: action,
                                child: Text(
                                  action == 'explore'
                                      ? (row.exploreEnabled
                                            ? s.sourceManagerDisableExplore
                                            : s.sourceManagerEnableExplore)
                                      : managerActionLabel(s, action),
                                ),
                              ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
        const Divider(height: 1),
      ],
    );
  }

  void _startSlide(SourceManagerSnapshot state, int index, Offset pointer) {
    _slideRows = state.rows.map((e) => e.url).toList();
    _slideAnchor = index;
    _slideStart = Set.of(state.selected);
    _pointer = pointer;
    _lastSlide = null;
    _previewSlide();
    _slideTimer?.cancel();
    _slideTimer = Timer.periodic(const Duration(milliseconds: 32), (_) {
      if (!mounted || !_scroll.hasClients || _pointer == null) return;
      final box = _viewport.currentContext?.findRenderObject() as RenderBox?;
      if (box == null || !box.hasSize) return;
      final top = box.localToGlobal(Offset.zero).dy,
          bottom = box.localToGlobal(Offset(0, box.size.height)).dy;
      final delta = _pointer!.dy < top + 48
          ? -24.0
          : _pointer!.dy > bottom - 48
          ? 24.0
          : 0.0;
      if (delta != 0) {
        _scroll.jumpTo(
          (_scroll.offset + delta).clamp(0, _scroll.position.maxScrollExtent),
        );
        WidgetsBinding.instance.addPostFrameCallback((_) => _previewSlide());
      }
    });
  }

  void _previewSlide() {
    final rows = _slideRows, pointer = _pointer;
    if (rows == null || pointer == null || !mounted) return;
    var target = _slideAnchor;
    for (var i = 0; i < rows.length; i++) {
      final box =
          _rowBoxes[rows[i]]?.currentContext?.findRenderObject() as RenderBox?;
      if (box != null &&
          box.attached &&
          box.hasSize &&
          pointer.dy >= box.localToGlobal(Offset.zero).dy) {
        target = i;
      }
    }
    final keys = rows.sublist(
      math.min(_slideAnchor, target),
      math.max(_slideAnchor, target) + 1,
    );
    final signature = keys.join('\u0000');
    if (_lastSlide == signature) return;
    _lastSlide = signature;
    unawaited(
      _send('slide', {'keys': keys, 'selectedAtStart': _slideStart.toList()}),
    );
  }

  void _stopSlide() {
    _slideTimer?.cancel();
    _slideTimer = null;
    _slideRows = null;
    _pointer = null;
  }
}

String _status(AppLocalizations s, String value) => switch (value) {
  'PASSED' => s.sourceManagerStatusPassed,
  'FAILED' => s.sourceManagerStatusFailed,
  'NEEDS_CHECK' => s.sourceManagerStatusNeeded,
  _ => s.sourceManagerStatusAll,
};

class _NativeValueField extends StatefulWidget {
  const _NativeValueField({
    super.key,
    required this.identifier,
    required this.value,
    required this.label,
    required this.enabled,
    required this.onChanged,
  });
  final String identifier, value, label;
  final bool enabled;
  final Future<void> Function(String) onChanged;
  @override
  State<_NativeValueField> createState() => _NativeValueFieldState();
}

class _NativeValueFieldState extends State<_NativeValueField> {
  late final _controller = TextEditingController(text: widget.value);
  final _focus = FocusNode();
  final _pending = <int>{};
  int _inputSequence = 0;
  bool _deferredNativeUpdate = false;

  void _applyNative() {
    if (widget.value != _controller.text) {
      _controller.value = TextEditingValue(
        text: widget.value,
        selection: TextSelection.collapsed(offset: widget.value.length),
      );
    }
  }

  @override
  void didUpdateWidget(covariant _NativeValueField oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.identifier != widget.identifier) {
      _pending.clear();
      _deferredNativeUpdate = false;
      _applyNative();
    } else if (oldWidget.value != widget.value) {
      if (_pending.isNotEmpty) {
        _deferredNativeUpdate = true;
      } else {
        _applyNative();
      }
    }
  }

  Future<void> _changed(String value) async {
    final sequence = ++_inputSequence;
    _pending.add(sequence);
    try {
      await widget.onChanged(value);
    } finally {
      if (mounted) {
        _pending.remove(sequence);
        if (_pending.isEmpty && _deferredNativeUpdate) {
          // Native commands and events originate on the same platform thread.
          // Wait for this frame to consume their latest state, then reconcile.
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (mounted && _pending.isEmpty && _deferredNativeUpdate) {
              _deferredNativeUpdate = false;
              _applyNative();
            }
          });
          setState(() {});
        }
      }
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    _focus.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Semantics(
    identifier: widget.identifier,
    child: TextField(
      key: ValueKey(widget.identifier),
      controller: _controller,
      focusNode: _focus,
      enabled: widget.enabled,
      decoration: InputDecoration(labelText: widget.label),
      onChanged: (value) => unawaited(_changed(value)),
    ),
  );
}

class _ManagerDialog extends ConsumerWidget {
  const _ManagerDialog({required this.send});
  final Future<void> Function(String, [Map<String, Object?>]) send;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(sourceManagerStateProvider).asData?.value;
    if (state == null || state.dialog == null) return const SizedBox.shrink();
    final s = AppLocalizations.of(context);
    final title = switch (state.dialog) {
      'DELETE' => s.sourceManagerDelete,
      'ADD_GROUP' => s.sourceManagerAddGroup,
      'REMOVE_GROUP' => s.sourceManagerRemoveGroup,
      'IMPORT' => s.sourceManagerOnline,
      'CHECK' => s.sourceManagerCheckKeyword,
      'EXPORT_SUCCESS' => s.sourceManagerExportSuccess,
      _ => s.sourceManagerPassphrase,
    };
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop && !state.busy) unawaited(send('dismiss'));
      },
      child: Semantics(
        identifier: 'source-manager-dialog',
        child: AlertDialog(
          key: const ValueKey('source-manager-dialog'),
          title: Text(title),
          content: SizedBox(
            width: 520,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (state.dialog == 'DELETE')
                    Text(s.sourceManagerDeletePrompt)
                  else ...[
                    if (state.dialog == 'EXPORT_SUCCESS') ...[
                      if (state.feedback?.summary.isNotEmpty ?? false)
                        Text(state.feedback!.summary),
                      if (state.feedback?.canSharePassphrase ?? false)
                        TextButton(
                          onPressed: state.busy
                              ? null
                              : () => send('passphrase'),
                          child: Text(s.sourceManagerPassphrase),
                        ),
                    ],
                    _NativeValueField(
                      key: ValueKey(state.dialog),
                      identifier: 'source-manager-draft',
                      value: state.draft,
                      label: s.sourceManagerDraft,
                      enabled: !state.busy,
                      onChanged: (value) => send('draft', {'value': value}),
                    ),
                    if (state.dialog == 'IMPORT')
                      for (final entry in state.importHistory)
                        Row(
                          children: [
                            Expanded(
                              child: TextButton(
                                onPressed: state.busy
                                    ? null
                                    : () {
                                        FocusScope.of(context).unfocus();
                                        unawaited(
                                          send('draft', {'value': entry}),
                                        );
                                      },
                                child: Text(entry),
                              ),
                            ),
                            IconButton(
                              tooltip: s.sourceManagerForgetImport,
                              onPressed: state.busy
                                  ? null
                                  : () =>
                                        send('forgetImport', {'value': entry}),
                              icon: const Icon(Icons.delete_outline),
                            ),
                          ],
                        ),
                    if (state.dialog == 'CHECK')
                      TextButton(
                        onPressed: () => send('action', {
                          'action': 'check-config',
                          'key': '',
                        }),
                        child: Text(s.sourceManagerCheckConfig),
                      ),
                    if (state.dialog == 'ADD_GROUP' ||
                        state.dialog == 'REMOVE_GROUP')
                      for (final group in state.groups)
                        TextButton(
                          onPressed: state.busy
                              ? null
                              : () {
                                  FocusScope.of(context).unfocus();
                                  unawaited(send('draft', {'value': group}));
                                },
                          child: Text(group),
                        ),
                  ],
                ],
              ),
            ),
          ),
          actions: [
            TextButton(
              onPressed: state.busy ? null : () => send('dismiss'),
              child: Text(s.sourceManagerCancel),
            ),
            Semantics(
              identifier: 'source-manager-confirm',
              child: FilledButton(
                key: const ValueKey('source-manager-confirm'),
                onPressed: state.busy ? null : () => send('confirm'),
                child: Text(s.sourceManagerConfirm),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
