import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import '../../l10n/generated/app_localizations.dart';
import 'source_editor_bridge.dart';
import 'source_editor_state.dart';

typedef EditorCommand = Future<void> Function(String, [Map<String, Object?>]);

Widget editorTag(String id, Widget child) => Semantics(
  identifier: id,
  child: KeyedSubtree(key: ValueKey(id), child: child),
);

/// Android's persisted draft remains authoritative throughout add-to-app.
/// Editing commands and navigation share one ordered bridge queue.
class SourceEditorPage extends ConsumerStatefulWidget {
  const SourceEditorPage({super.key});
  @override
  ConsumerState<SourceEditorPage> createState() => _SourceEditorPageState();
}

class _SourceEditorPageState extends ConsumerState<SourceEditorPage> {
  final _fieldKeys = <String, GlobalKey>{};
  bool _help = false;
  bool _rejectedInput = false;
  Future<void> _command(String op, [Map<String, Object?> args = const {}]) =>
      _send(op, args);
  Future<void> _fieldCommand(
    String op, [
    Map<String, Object?> args = const {},
  ]) => _send(op, args, propagate: op == 'field');
  Future<void> _send(
    String op,
    Map<String, Object?> args, {
    bool propagate = false,
  }) async {
    final bridge = ref.read(sourceEditorBridgeProvider);
    try {
      await bridge.command(op, args);
      if (mounted && _rejectedInput != bridge.hasRejectedFields) {
        setState(() => _rejectedInput = bridge.hasRejectedFields);
      }
    } catch (error) {
      if (mounted) {
        setState(() => _rejectedInput = bridge.hasRejectedFields);
        final message = op == 'field' || error is SourceEditorWriteBlocked
            ? AppLocalizations.of(context).sourceEditorEditRejected
            : error.toString();
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(message)));
      }
      if (propagate) rethrow;
    }
  }

  void _native(String action, [String? text]) =>
      unawaited(_command('native', {'action': action, 'text': ?text}));
  void _save(String action) => unawaited(_command('save', {'action': action}));
  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final async = ref.watch(sourceEditorStateProvider);
    final state = async.asData?.value;
    final doc = state?.document;
    final enabled = doc != null && !doc.finished && !(state?.busy ?? true);
    final tab = doc?.selectedTab ?? 0;
    final fields = doc?.tabs[tab] ?? const <SourceEditorField>[];
    final tabs = [
      l.sourceEditorBase,
      l.sourceEditorSearch,
      l.sourceEditorExplore,
      l.sourceEditorInfo,
      l.sourceEditorToc,
      l.sourceEditorContent,
      l.sourceEditorReview,
    ];
    final failure =
        state?.error ??
        (_rejectedInput
            ? l.sourceEditorEditRejected
            : (async.hasError ? l.sourceEditorFailed : null));
    final menu = <String, String>{
      if (doc?.hasLogin ?? false) 'LOGIN': l.sourceEditorLogin,
      'DEBUG': l.sourceEditorDebug,
      'SEARCH': l.sourceEditorSearch,
      'COOKIE': l.sourceEditorClearCookie,
      'AUTO_COMPLETE': l.sourceEditorAutoComplete,
      'COPY': l.sourceEditorCopy,
      'PASTE': l.sourceEditorPaste,
      'QR': l.sourceEditorQr,
      'SHARE': l.sourceEditorShare,
      'QR_SHARE': l.sourceEditorQrShare,
      'VARIABLE': l.sourceEditorVariable,
      'LOG': l.sourceEditorLog,
      'HELP': l.sourceEditorHelp,
    };
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (didPop) return;
        if (_help) {
          setState(() => _help = false);
        } else if (state != null &&
            (state.migrationRunning ||
                state.migrationError != null ||
                state.migrationReport != null)) {
          unawaited(_command('dismissMigration'));
        } else if (state?.variable != null) {
          unawaited(_command('dismissVariable'));
        } else if (state?.groups != null) {
          unawaited(_command('dismissGroups'));
        } else if (state?.confirmDiscard ?? false) {
          unawaited(_command('keepEditing'));
        } else {
          unawaited(_command('cancel'));
        }
      },
      child: Scaffold(
        appBar: AppBar(
          leading: editorTag(
            'source-close',
            IconButton(
              tooltip: l.sourceEditorClose,
              onPressed: state?.busy == true ? null : () => _command('cancel'),
              icon: const Icon(Icons.close),
            ),
          ),
          title: Text(l.sourceEditorTitle),
          actions: [
            editorTag(
              'source-fullscreen',
              IconButton(
                tooltip: l.sourceEditorFullScreen,
                onPressed: enabled ? () => _native('EDITOR') : null,
                icon: const Icon(Icons.fullscreen),
              ),
            ),
            editorTag(
              'source-save',
              IconButton(
                tooltip: l.sourceEditorSave,
                onPressed: enabled ? () => _save('FINISH') : null,
                icon: const Icon(Icons.save_outlined),
              ),
            ),
            editorTag(
              'source-menu',
              PopupMenuButton<String>(
                tooltip: l.sourceEditorMenu,
                enabled: enabled,
                onSelected: (action) {
                  switch (action) {
                    case 'DEBUG':
                    case 'SEARCH':
                    case 'LOGIN':
                    case 'VARIABLE':
                      _save(action);
                    case 'COOKIE':
                      unawaited(_command('clearCookie'));
                    case 'AUTO_COMPLETE':
                      unawaited(_command('autoComplete'));
                    case 'PASTE':
                      unawaited(_command('paste'));
                    default:
                      _native(action, action == 'HELP' ? 'ruleHelp' : null);
                  }
                },
                itemBuilder: (_) => menu.entries
                    .map(
                      (e) => PopupMenuItem(
                        value: e.key,
                        child: editorTag(
                          'source-menu-${e.key}',
                          Row(
                            children: [
                              Expanded(child: Text(e.value)),
                              if (e.key == 'AUTO_COMPLETE' &&
                                  (doc?.autoComplete ?? false))
                                const Icon(Icons.check),
                            ],
                          ),
                        ),
                      ),
                    )
                    .toList(),
              ),
            ),
          ],
        ),
        body: SafeArea(
          top: false,
          child: Stack(
            children: [
              Column(
                children: [
                  if (async.isLoading ||
                      state?.busy == true ||
                      (doc == null && failure == null))
                    editorTag(
                      'source-progress',
                      const LinearProgressIndicator(),
                    ),
                  if (failure != null)
                    Padding(
                      padding: const EdgeInsets.all(12),
                      child: Row(
                        children: [
                          Expanded(
                            child: Text(
                              failure,
                              style: TextStyle(
                                color: Theme.of(context).colorScheme.error,
                              ),
                            ),
                          ),
                          editorTag(
                            'source-retry',
                            TextButton(
                              onPressed: state?.busy == true
                                  ? null
                                  : () {
                                      if (async.hasError) {
                                        ref.invalidate(
                                          sourceEditorStateProvider,
                                        );
                                      } else {
                                        unawaited(_command('retry'));
                                      }
                                    },
                              child: Text(l.sourceEditorRetry),
                            ),
                          ),
                        ],
                      ),
                    ),
                  if (doc != null && !doc.finished) ...[
                    _EditorOptions(
                      document: doc,
                      enabled: enabled,
                      command: _command,
                    ),
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 12),
                      child: Row(
                        children: [
                          Expanded(
                            child: Text(
                              l.sourceEditorEngine,
                              style: Theme.of(context).textTheme.labelMedium,
                            ),
                          ),
                          editorTag(
                            'sourceMigrationPreview',
                            TextButton(
                              onPressed:
                                  enabled &&
                                      state!.migrationAvailable &&
                                      !state.migrationRunning
                                  ? () => _command('migration')
                                  : null,
                              child: Text(l.sourceEditorMigration),
                            ),
                          ),
                        ],
                      ),
                    ),
                    editorTag(
                      'source-tabs',
                      SingleChildScrollView(
                        scrollDirection: Axis.horizontal,
                        child: Row(
                          children: List.generate(
                            tabs.length,
                            (i) => editorTag(
                              'source-tab-$i',
                              Padding(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 2,
                                ),
                                child: ChoiceChip(
                                  label: Text(tabs[i]),
                                  selected: tab == i,
                                  onSelected: enabled
                                      ? (_) => _command('tab', {'index': i})
                                      : null,
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                    SingleChildScrollView(
                      scrollDirection: Axis.horizontal,
                      child: Row(
                        children: fields
                            .map(
                              (field) => editorTag(
                                'source-nav-$tab-${field.key}',
                                TextButton(
                                  onPressed: enabled
                                      ? () {
                                          final target =
                                              _fieldKeys['$tab:${field.key}']
                                                  ?.currentContext;
                                          if (target != null) {
                                            unawaited(
                                              Scrollable.ensureVisible(
                                                target,
                                                duration: const Duration(
                                                  milliseconds: 180,
                                                ),
                                                alignment: .1,
                                              ),
                                            );
                                          }
                                          unawaited(
                                            _command('focus', {
                                              'tab': tab,
                                              'key': field.key,
                                            }),
                                          );
                                        }
                                      : null,
                                  child: Text(field.label),
                                ),
                              ),
                            )
                            .toList(),
                      ),
                    ),
                    Expanded(
                      child: editorTag(
                        'source-fields-$tab',
                        SingleChildScrollView(
                          key: ValueKey('editor-fields-$tab'),
                          padding: const EdgeInsets.all(12),
                          child: Column(
                            children: fields.map((field) {
                              final key = _fieldKeys.putIfAbsent(
                                '$tab:${field.key}',
                                () => GlobalKey(),
                              );
                              return Padding(
                                key: key,
                                padding: const EdgeInsets.only(bottom: 12),
                                child: SourceEditorTextField(
                                  key: ValueKey('$tab:${field.key}'),
                                  field: field,
                                  tab: tab,
                                  focused: doc.focusedKey == field.key,
                                  enabled: enabled,
                                  maxLines: state!.maxLines,
                                  command: _fieldCommand,
                                  onEditor: () => _native('EDITOR'),
                                ),
                              );
                            }).toList(),
                          ),
                        ),
                      ),
                    ),
                    if (MediaQuery.viewInsetsOf(context).bottom > 0)
                      _keyboard(state!, enabled, l),
                  ] else
                    const Expanded(child: SizedBox()),
                ],
              ),
              if (state?.confirmDiscard ?? false)
                _modal(
                  AlertDialog(
                    title: Text(l.sourceEditorDiscardTitle),
                    content: Text(l.sourceEditorDiscardBody),
                    actions: [
                      editorTag(
                        'source-discard-cancel',
                        TextButton(
                          onPressed: () => _command('keepEditing'),
                          child: Text(l.sourceEditorKeepEditing),
                        ),
                      ),
                      editorTag(
                        'source-discard-confirm',
                        TextButton(
                          onPressed: () => _command('discard'),
                          child: Text(l.sourceEditorDiscard),
                        ),
                      ),
                    ],
                  ),
                  () => _command('keepEditing'),
                ),
              if (state?.groups case final groups?)
                _modal(
                  AlertDialog(
                    title: Text(l.sourceEditorGroups),
                    content: SizedBox(
                      width: 360,
                      child: SingleChildScrollView(
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: groups
                              .map(
                                (group) => ListTile(
                                  title: Text(group),
                                  onTap: () async {
                                    await _command('insert', {'text': group});
                                    await _command('dismissGroups');
                                  },
                                ),
                              )
                              .toList(),
                        ),
                      ),
                    ),
                    actions: [
                      TextButton(
                        onPressed: () => _command('dismissGroups'),
                        child: Text(l.sourceEditorClose),
                      ),
                    ],
                  ),
                  () => _command('dismissGroups'),
                ),
              if (state?.variable case final value?)
                _modal(
                  _VariableDialog(
                    value: value,
                    comment: state?.variableComment ?? '',
                    busy: state!.busy,
                    command: _command,
                  ),
                  () => _command('dismissVariable'),
                ),
              if (_help)
                _modal(
                  AlertDialog(
                    title: Text(l.sourceEditorKeyboardHelp),
                    content: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        for (final entry in {
                          'KEYBOARD_CONFIG': l.sourceEditorKeyboardConfig,
                          'URL_OPTIONS': l.sourceEditorUrlOptions,
                          'ruleHelp': l.sourceEditorHelp,
                          'jsHelp': l.sourceEditorJsHelp,
                          'regexHelp': l.sourceEditorRegexHelp,
                          if (doc?.focusedKey == 'bookSourceGroup')
                            'GROUP': l.sourceEditorInsertGroup
                          else
                            'FILE': l.sourceEditorChooseFile,
                        }.entries)
                          TextButton(
                            onPressed: () {
                              setState(() => _help = false);
                              if (entry.key == 'GROUP') {
                                unawaited(_command('groups'));
                              } else if (entry.key.endsWith('Help')) {
                                _native('HELP', entry.key);
                              } else {
                                _native(entry.key);
                              }
                            },
                            child: Text(entry.value),
                          ),
                      ],
                    ),
                    actions: [
                      TextButton(
                        onPressed: () => setState(() => _help = false),
                        child: Text(l.sourceEditorClose),
                      ),
                    ],
                  ),
                  () => setState(() => _help = false),
                ),
              if (state != null &&
                  (state.migrationRunning ||
                      state.migrationError != null ||
                      state.migrationReport != null))
                _modal(
                  _migration(state, l),
                  () => _command('dismissMigration'),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _modal(Widget child, VoidCallback close) => Positioned.fill(
    child: Stack(
      children: [
        ModalBarrier(
          dismissible: true,
          onDismiss: close,
          color: Colors.black54,
        ),
        Center(child: child),
      ],
    ),
  );
  Widget _keyboard(
    SourceEditorSnapshot state,
    bool enabled,
    AppLocalizations l,
  ) => SizedBox(
    height: state.keyboardRows.clamp(1, 5) * 40,
    child: editorTag(
      'source-keyboard',
      GridView.count(
        scrollDirection: Axis.horizontal,
        crossAxisCount: state.keyboardRows.clamp(1, 5),
        childAspectRatio: .65,
        children: [
          IconButton(
            tooltip: l.sourceEditorKeyboardHelp,
            onPressed: enabled ? () => setState(() => _help = true) : null,
            icon: const Icon(Icons.help_outline),
          ),
          editorTag(
            'source-undo',
            IconButton(
              tooltip: l.sourceEditorUndo,
              onPressed: enabled ? () => _command('undo') : null,
              icon: const Icon(Icons.undo),
            ),
          ),
          editorTag(
            'source-redo',
            IconButton(
              tooltip: l.sourceEditorRedo,
              onPressed: enabled ? () => _command('redo') : null,
              icon: const Icon(Icons.redo),
            ),
          ),
          ...state.assists.map(
            (a) => TextButton(
              onPressed: enabled
                  ? () => _command('insert', {'text': a['value']})
                  : null,
              child: Text(a['key']!),
            ),
          ),
        ],
      ),
    ),
  );
  Widget _migration(
    SourceEditorSnapshot state,
    AppLocalizations l,
  ) => AlertDialog(
    title: Text(l.sourceEditorMigrationTitle),
    content: SizedBox(
      width: 440,
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (state.migrationRunning) ...[
              const LinearProgressIndicator(),
              Text(l.sourceEditorMigrationRunning),
            ],
            if (state.migrationError case final error?)
              Text(
                error,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            if (state.migrationReport case final report?) ...[
              Text(report.status),
              if (report.requiresManualWork)
                Text(l.sourceEditorMigrationManual),
              if (report.issues.isEmpty) Text(l.sourceEditorMigrationNone),
              for (final issue in report.issues)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 6),
                  child: Text(
                    '${issue['path']}\n${issue['code']}: ${issue['message']}',
                  ),
                ),
            ],
          ],
        ),
      ),
    ),
    actions: [
      TextButton(
        onPressed: state.migrationRunning
            ? null
            : () => _command('dismissMigration'),
        child: Text(l.sourceEditorClose),
      ),
      editorTag(
        'source-migration-apply',
        FilledButton(
          onPressed:
              state.migrationReport?.canApply == true && !state.migrationRunning
              ? () => _command('applyMigration')
              : null,
          child: Text(l.sourceEditorMigrationApply),
        ),
      ),
    ],
  );
}

class _EditorOptions extends StatefulWidget {
  const _EditorOptions({
    required this.document,
    required this.enabled,
    required this.command,
  });
  final SourceEditorDocument document;
  final bool enabled;
  final EditorCommand command;
  @override
  State<_EditorOptions> createState() => _EditorOptionsState();
}

class _EditorOptionsState extends State<_EditorOptions> {
  late SourceEditorOptions _options = widget.document.options;
  final _pending = <SourceEditorOptions>[];
  @override
  void didUpdateWidget(covariant _EditorOptions old) {
    super.didUpdateWidget(old);
    final incoming = widget.document.options;
    final at = _pending.lastIndexOf(incoming);
    if (at >= 0) _pending.removeRange(0, at + 1);
    if (_pending.isEmpty) _options = incoming;
  }

  void _update(SourceEditorOptions value) {
    setState(() {
      _options = value;
      _pending.add(value);
    });
    unawaited(widget.command('options', {'value': value.toMap()}));
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final names = [
      l.sourceEditorText,
      l.sourceEditorAudio,
      l.sourceEditorImage,
      l.sourceEditorFile,
      l.sourceEditorVideo,
    ];
    final labels = {
      'enabled': l.sourceEditorEnabled,
      'enabledExplore': l.sourceEditorExploreEnabled,
      'enabledCookieJar': l.sourceEditorCookie,
      'enabledReview': l.sourceEditorReviewEnabled,
      'eventListener': l.sourceEditorListener,
      'customButton': l.sourceEditorCustomButton,
    };
    final values = _options.toMap();
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        editorTag(
          'source-options-toggle',
          TextButton(
            onPressed: widget.enabled
                ? () => widget.command('expanded', {
                    'value': !widget.document.optionsExpanded,
                  })
                : null,
            child: Text(
              '${l.sourceEditorOptions}: ${names[_options.type]}${widget.document.optionsExpanded ? ' ▴' : ' ▾'}',
            ),
          ),
        ),
        if (widget.document.optionsExpanded)
          editorTag(
            'source-options',
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: [
                  for (final entry in labels.entries)
                    editorTag(
                      'source-option-${entry.key}',
                      FilterChip(
                        label: Text(entry.value),
                        selected: values[entry.key] == true,
                        onSelected: widget.enabled
                            ? (value) {
                                final updated = _options.toMap();
                                updated[entry.key] =
                                    !(updated[entry.key] as bool);
                                _update(SourceEditorOptions.fromMap(updated));
                              }
                            : null,
                      ),
                    ),
                  editorTag(
                    'source-type',
                    PopupMenuButton<int>(
                      tooltip: l.sourceEditorType,
                      enabled: widget.enabled,
                      child: Padding(
                        padding: const EdgeInsets.all(12),
                        child: Text(names[_options.type]),
                      ),
                      onSelected: (value) =>
                          _update(_options.copyWith(type: value)),
                      itemBuilder: (_) => List.generate(
                        names.length,
                        (i) => PopupMenuItem(
                          value: i,
                          child: editorTag('source-type-$i', Text(names[i])),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
      ],
    );
  }
}

/// Keeps unacknowledged edits local while native events echo earlier keystrokes.
/// Acknowledgements drain in order; native undo/paste/recreation then replaces the
/// controller, including selection, without feeding it back as another edit.
class SourceEditorTextField extends StatefulWidget {
  const SourceEditorTextField({
    super.key,
    required this.field,
    required this.tab,
    required this.focused,
    required this.enabled,
    required this.command,
    required this.onEditor,
    this.maxLines = 6,
  });
  final int maxLines;
  final SourceEditorField field;
  final int tab;
  final bool focused;
  final bool enabled;
  final EditorCommand command;
  final VoidCallback onEditor;
  @override
  State<SourceEditorTextField> createState() => _SourceEditorTextFieldState();
}

class _SourceEditorTextFieldState extends State<SourceEditorTextField> {
  late final TextEditingController _controller =
      TextEditingController.fromValue(_value(widget.field));
  final _focus = FocusNode();
  final _pending = <TextEditingValue>[];
  bool _reflecting = false;
  static TextEditingValue _value(SourceEditorField field) => TextEditingValue(
    text: field.value,
    selection: TextSelection(
      baseOffset: field.selectionStart,
      extentOffset: field.selectionEnd,
    ),
  );
  @override
  void initState() {
    super.initState();
    _controller.addListener(_changed);
    _focus.addListener(_focused);
    _requestFocus();
  }

  void _requestFocus() {
    if (widget.focused && widget.enabled && !widget.field.external) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted &&
            widget.focused &&
            widget.enabled &&
            !widget.field.external) {
          _focus.requestFocus();
        }
      });
    }
  }

  void _focused() {
    if (_focus.hasFocus) {
      unawaited(
        widget.command('focus', {'tab': widget.tab, 'key': widget.field.key}),
      );
    }
  }

  void _changed() {
    if (_reflecting || !widget.enabled || widget.field.external) return;
    final value = _controller.value;
    // IMEs temporarily publish an invalid selection before attaching input.
    if (!value.selection.isValid) return;
    _pending.add(value);
    unawaited(
      widget
          .command('field', {
            'tab': widget.tab,
            'key': widget.field.key,
            'value': value.text,
            'start': value.selection.baseOffset,
            'end': value.selection.extentOffset,
          })
          .catchError((Object error, StackTrace stack) {
            if (!mounted) return;
            // Failed edits were never accepted by the VM. Drop their optimistic
            // echoes and restore its latest authoritative value and cursor.
            _pending.clear();
            _reflecting = true;
            _controller.value = _value(widget.field);
            _reflecting = false;
          }),
    );
  }

  @override
  void didUpdateWidget(covariant SourceEditorTextField old) {
    super.didUpdateWidget(old);
    if (widget.field.external) _pending.clear();
    final next = _value(widget.field);
    final at = _pending.lastIndexWhere(
      (v) => v.text == next.text && v.selection == next.selection,
    );
    if (at >= 0) _pending.removeRange(0, at + 1);
    if (_pending.isEmpty &&
        (_controller.text != next.text ||
            _controller.selection != next.selection)) {
      _reflecting = true;
      _controller.value = next;
      _reflecting = false;
    }
    if (!old.focused && widget.focused) _requestFocus();
  }

  @override
  void dispose() {
    _controller.dispose();
    _focus.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // Avoid platform text-layout stalls on untrusted, very long rule fields.
    final unsafe =
        widget.field.external || isSourceEditorInlineUnsafe(widget.field.value);
    if (unsafe) {
      return editorTag(
        'source-field-${widget.tab}-${widget.field.key}',
        ListTile(
          title: Text(widget.field.label),
          subtitle: Text(AppLocalizations.of(context).sourceEditorLargeField),
          trailing: const Icon(Icons.open_in_full),
          onTap: widget.enabled
              ? () async {
                  await widget.command('focus', {
                    'tab': widget.tab,
                    'key': widget.field.key,
                  });
                  widget.onEditor();
                }
              : null,
        ),
      );
    }
    return editorTag(
      'source-field-${widget.tab}-${widget.field.key}',
      TextField(
        controller: _controller,
        focusNode: _focus,
        enabled: widget.enabled,
        minLines: 1,
        maxLines: widget.maxLines.clamp(1, 50),
        keyboardType: TextInputType.multiline,
        style: const TextStyle(fontFamily: 'monospace'),
        decoration: InputDecoration(
          labelText: widget.field.label,
          border: const OutlineInputBorder(),
        ),
      ),
    );
  }
}

class _VariableDialog extends StatefulWidget {
  const _VariableDialog({
    required this.value,
    required this.comment,
    required this.busy,
    required this.command,
  });
  final String value, comment;
  final bool busy;
  final EditorCommand command;
  @override
  State<_VariableDialog> createState() => _VariableDialogState();
}

class _VariableDialogState extends State<_VariableDialog> {
  late final _controller = TextEditingController(text: widget.value);
  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return AlertDialog(
      title: Text(l.sourceEditorVariable),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(widget.comment),
            editorTag(
              'source-variable',
              TextField(
                controller: _controller,
                maxLines: 6,
                minLines: 1,
                enabled: !widget.busy,
                onChanged: (value) =>
                    widget.command('variableEdit', {'value': value}),
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: widget.busy
              ? null
              : () => widget.command('dismissVariable'),
          child: Text(l.sourceEditorCancel),
        ),
        TextButton(
          onPressed: widget.busy
              ? null
              : () =>
                    widget.command('variableSave', {'value': _controller.text}),
          child: Text(l.sourceEditorSave),
        ),
      ],
    );
  }
}

/// Mirrors the existing native editor's layout guard, including all Unicode
/// combining-mark categories while permitting emoji variation selectors.
bool isSourceEditorInlineUnsafe(String text) {
  if (text.length > 12000) return true;
  final marks = RegExp(r'\p{Mark}', unicode: true);
  final window = <bool>[];
  var run = 0;
  var count = 0;
  for (final point in text.runes) {
    final variation =
        (point >= 0xfe00 && point <= 0xfe0f) ||
        (point >= 0xe0100 && point <= 0xe01ef);
    final mark = !variation && marks.hasMatch(String.fromCharCode(point));
    run = mark ? run + 1 : 0;
    if (run >= 8) return true;
    if (window.length == 64) {
      if (window.removeAt(0)) count--;
    }
    window.add(mark);
    if (mark) count++;
    if (count >= 16 && count * 10 >= window.length * 3) return true;
  }
  return false;
}
