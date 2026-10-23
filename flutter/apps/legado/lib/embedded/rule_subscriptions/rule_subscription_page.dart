import 'dart:async';

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../../l10n/generated/app_localizations.dart';
import 'rule_subscription_bridge.dart';

class RuleSubscriptionPage extends StatefulWidget {
  const RuleSubscriptionPage({super.key, this.bridge});
  final RuleSubscriptionBridge? bridge;
  @override
  State<RuleSubscriptionPage> createState() => _RuleSubscriptionPageState();
}

class _RuleSubscriptionPageState extends State<RuleSubscriptionPage> {
  late final RuleSubscriptionBridge _bridge;
  StreamSubscription<Map<String, dynamic>>? _subscription;
  Map<String, dynamic>? _state;
  bool _failed = false;
  @override
  void initState() {
    super.initState();
    _bridge = widget.bridge ?? RuleSubscriptionBridge();
    _state = _bridge.latest;
    _subscription = _bridge.states.listen(
      (state) {
        if (mounted) {
          setState(() {
            _state = state;
          });
        }
      },
      onError: (_) {
        if (mounted) setState(() => _failed = true);
      },
    );
    unawaited(
      _bridge.load().catchError((Object _) {
        if (mounted) setState(() => _failed = true);
      }),
    );
  }

  @override
  void dispose() {
    unawaited(_subscription?.cancel());
    if (widget.bridge == null) unawaited(_bridge.close());
    super.dispose();
  }

  Future<void> _send(String op, [Map<String, Object?> args = const {}]) async {
    try {
      await _bridge.command(op, args);
      if (mounted) setState(() => _failed = false);
    } catch (_) {
      if (mounted) setState(() => _failed = true);
    }
  }

  Widget _tag(String id, Widget child) => Semantics(
    key: ValueKey(id),
    identifier: id,
    child: KeyedSubtree(key: ValueKey(id), child: child),
  );
  String _issue(AppLocalizations l) => switch (_state?['issue']) {
    'EmptyUrl' => l.ruleSubscriptionsEmptyUrl,
    'DuplicateUrl' => l.ruleSubscriptionsDuplicate,
    'Missing' => l.ruleSubscriptionsMissing,
    'Conflict' => l.ruleSubscriptionsConflict,
    _ =>
      _bridge.hasRejectedFields
          ? l.ruleSubscriptionsRejected
          : l.ruleSubscriptionsFailure,
  };
  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final state = _state;
    final editor = state?['editor'] as Map?;
    final enabled =
        state?['loaded'] == true &&
        state?['busy'] != true &&
        state?['loading'] != true &&
        state?['closed'] != true;
    final rows = (state?['rows'] as List?) ?? const [];
    final types = [
      l.ruleSubscriptionsBook,
      l.ruleSubscriptionsRss,
      l.ruleSubscriptionsReplace,
    ];
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop && state?['busy'] != true) unawaited(_send('back'));
      },
      child: Scaffold(
        appBar: AppBar(
          title: Text(l.ruleSubscriptionsTitle),
          leading: IconButton(
            tooltip: MaterialLocalizations.of(context).backButtonTooltip,
            onPressed: state?['busy'] == true ? null : () => _send('back'),
            icon: const Icon(Icons.arrow_back),
          ),
          actions: [
            _tag(
              'subscription-add',
              IconButton(
                tooltip: l.ruleSubscriptionsAdd,
                onPressed: enabled && editor == null
                    ? () => _send('create')
                    : null,
                icon: const Icon(Icons.add),
              ),
            ),
          ],
        ),
        body: Stack(
          children: [
            Column(
              children: [
                if (state == null ||
                    state['loading'] == true ||
                    state['busy'] == true)
                  const LinearProgressIndicator(),
                if (editor == null && (_failed || state?['issue'] != null))
                  _error(l),
                Expanded(
                  child: rows.isEmpty
                      ? Center(child: Text(l.ruleSubscriptionsEmpty))
                      : _tag(
                          'subscription-list',
                          ReorderableListView.builder(
                            restorationId: 'subscription-list',
                            itemCount: rows.length,
                            onReorderItem: (oldIndex, newIndex) {
                              if (!enabled || editor != null) return;
                              final target = newIndex;
                              if (oldIndex != target) {
                                unawaited(
                                  _send('move', {
                                    'id': (rows[oldIndex] as Map)['id'],
                                    'target': (rows[target] as Map)['id'],
                                  }),
                                );
                              }
                            },
                            itemBuilder: (context, index) {
                              final row = rows[index] as Map;
                              final id = row['id'] as String;
                              return _tag(
                                'subscription-row-$id',
                                ListTile(
                                  enabled: enabled && editor == null,
                                  title: Text(row['name'] as String),
                                  subtitle: Text(
                                    '${types[(row['type'] as int).clamp(0, 2)]}\n${row['url']}',
                                    maxLines: 2,
                                    overflow: TextOverflow.ellipsis,
                                  ),
                                  onTap: () => _send('open', {'id': id}),
                                  trailing: Row(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      _tag(
                                        'subscription-edit-$id',
                                        IconButton(
                                          tooltip: l.ruleSubscriptionsEdit,
                                          onPressed: enabled && editor == null
                                              ? () => _send('edit', {'id': id})
                                              : null,
                                          icon: const Icon(Icons.edit),
                                        ),
                                      ),
                                      PopupMenuButton<String>(
                                        enabled: enabled && editor == null,
                                        tooltip: l.ruleSubscriptionsActions,
                                        onSelected: (action) {
                                          if (action == 'delete') {
                                            unawaited(
                                              _send('delete', {'id': id}),
                                            );
                                          } else {
                                            final target = action == 'up'
                                                ? index - 1
                                                : index + 1;
                                            unawaited(
                                              _send('move', {
                                                'id': id,
                                                'target':
                                                    (rows[target] as Map)['id'],
                                              }),
                                            );
                                          }
                                        },
                                        itemBuilder: (_) => [
                                          if (index > 0)
                                            PopupMenuItem(
                                              value: 'up',
                                              child: Text(
                                                l.ruleSubscriptionsUp,
                                              ),
                                            ),
                                          if (index + 1 < rows.length)
                                            PopupMenuItem(
                                              value: 'down',
                                              child: Text(
                                                l.ruleSubscriptionsDown,
                                              ),
                                            ),
                                          PopupMenuItem(
                                            value: 'delete',
                                            child: Text(l.delete),
                                          ),
                                        ],
                                      ),
                                    ],
                                  ),
                                ),
                              );
                            },
                          ),
                        ),
                ),
              ],
            ),
            if (editor != null)
              Positioned.fill(
                child: GestureDetector(
                  onTap: enabled ? () => _send('cancel') : null,
                  child: ColoredBox(
                    color: Theme.of(context).colorScheme.scrim
                        .withValues(alpha: .32),
                    child: SafeArea(
                      child: Center(
                        child: GestureDetector(
                          onTap: () {},
                          child: Dialog(
                            child: Padding(
                              padding: const EdgeInsets.all(20),
                              child: ConstrainedBox(
                                constraints: const BoxConstraints(
                                  maxWidth: 560,
                                ),
                                child: Column(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Text(
                                      l.ruleSubscriptionsTitle,
                                      style: Theme.of(context)
                                          .textTheme
                                          .titleLarge,
                                    ),
                                    Flexible(
                                      child: SingleChildScrollView(
                                        restorationId:
                                            'subscription-editor-scroll',
                                        child: _form(
                                          l,
                                          editor,
                                          enabled &&
                                              state?['pendingSave'] != true,
                                          types,
                                        ),
                                      ),
                                    ),
                                    Row(
                                      mainAxisAlignment: MainAxisAlignment.end,
                                      children: [
                                        _tag(
                                          'subscription-cancel',
                                          TextButton(
                                            onPressed: enabled
                                                ? () => _send('cancel')
                                                : null,
                                            child: Text(l.cancel),
                                          ),
                                        ),
                                        _tag(
                                          'subscription-save',
                                          TextButton(
                                            onPressed:
                                                enabled &&
                                                    !_bridge.hasRejectedFields
                                                ? () => _send('save')
                                                : null,
                                            child: Text(l.save),
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
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _error(AppLocalizations l) => Column(
    children: [
      Text(
        _issue(l),
        style: TextStyle(color: Theme.of(context).colorScheme.error),
      ),
      TextButton(
        onPressed: () async {
          if (_state == null) {
            await _bridge.load();
          } else {
            await _send('retry');
          }
        },
        child: Text(l.retry),
      ),
    ],
  );
  Widget _form(
    AppLocalizations l,
    Map editor,
    bool enabled,
    List<String> types,
  ) => _tag(
    'subscription-editor',
    Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        _tag(
          'subscription-type',
          DropdownButtonFormField<int>(
            initialValue: editor['type'] as int,
            key: ValueKey('type-${editor['id']}-${editor['type']}'),
            decoration: InputDecoration(labelText: l.ruleSubscriptionsType),
            items: [
              for (var i = 0; i < types.length; i++)
                DropdownMenuItem(
                  value: i,
                  child: _tag('subscription-type-$i', Text(types[i])),
                ),
            ],
            onChanged: enabled
                ? (value) => _send('type', {'value': value})
                : null,
          ),
        ),
        _input(editor, 'name', l.ruleSubscriptionsName, enabled),
        _input(editor, 'url', 'URL', enabled),
        _tag(
          'subscription-auto',
          SwitchListTile(
            title: Text(l.ruleSubscriptionsAutomatic),
            value: editor['automatic'] == true,
            onChanged: enabled
                ? (value) => _send('automatic', {'value': value})
                : null,
          ),
        ),
        _tag(
          'subscription-silent',
          SwitchListTile(
            title: Text(l.ruleSubscriptionsSilent),
            value: editor['silent'] == true,
            onChanged: enabled && editor['silentEnabled'] == true
                ? (value) => _send('silent', {'value': value})
                : null,
          ),
        ),
        _input(
          editor,
          'interval',
          l.ruleSubscriptionsInterval,
          enabled && editor['automatic'] == true,
        ),
        if (_state?['pendingSave'] == true) Text(l.ruleSubscriptionsPending),
        if (_failed || _state?['issue'] != null) _error(l),
      ],
    ),
  );
  Widget _input(Map editor, String field, String label, bool enabled) => _tag(
    'subscription-$field',
    _SubscriptionInput(
      key: ValueKey('${editor['id']}-$field'),
      value: editor[field] as String,
      label: label,
      enabled: enabled,
      number: field == 'interval',
      changed: (value) => _send(field, {'value': value}),
    ),
  );
}

/// Keep the native acknowledged value separate from an in-flight text edit.
/// Only an external change replaces the controller, preserving selection/IME.
class _SubscriptionInput extends StatefulWidget {
  const _SubscriptionInput({
    super.key,
    required this.value,
    required this.label,
    required this.enabled,
    required this.number,
    required this.changed,
  });
  final String value, label;
  final bool enabled, number;
  final Future<void> Function(String) changed;
  @override
  State<_SubscriptionInput> createState() => _SubscriptionInputState();
}

class _SubscriptionInputState extends State<_SubscriptionInput>
    with RestorationMixin {
  final _controller = RestorableTextEditingController();
  String? _sent;
  int _pending = 0;
  @override
  String get restorationId => widget.key.toString();
  @override
  void restoreState(RestorationBucket? oldBucket, bool initialRestore) {
    registerForRestoration(_controller, 'text');
    if (_controller.value.text != widget.value) {
      _controller.value.text = widget.value;
    }
  }

  @override
  void didUpdateWidget(covariant _SubscriptionInput oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (_pending == 0 &&
        widget.value != _sent &&
        _controller.value.text != widget.value) {
      _controller.value.value = TextEditingValue(
        text: widget.value,
        selection: TextSelection.collapsed(offset: widget.value.length),
      );
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => TextField(
    controller: _controller.value,
    enabled: widget.enabled,
    decoration: InputDecoration(labelText: widget.label),
    keyboardType: widget.number
        ? TextInputType.number
        : TextInputType.multiline,
    inputFormatters: widget.number
        ? [FilteringTextInputFormatter.digitsOnly]
        : null,
    maxLines: widget.number ? 1 : 5,
    minLines: 1,
    onChanged: (value) async {
      _sent = value;
      _pending++;
      try {
        await widget.changed(value);
      } finally {
        _pending--;
      }
    },
  );
}
