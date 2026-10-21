import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import '../../l10n/generated/app_localizations.dart';
import '../source_editor/source_editor_page.dart'
    show isSourceEditorInlineUnsafe;
import 'rss_source_editor_bridge.dart';
import 'rss_source_editor_state.dart';

typedef RssEditorCommand = Future<RssEditorSnapshot?> Function(
  String, [
  Map<String, Object?>,
]);
Widget rssEditorTag(String id, Widget child) => Semantics(
  identifier: id,
  child: KeyedSubtree(key: ValueKey(id), child: child),
);

/// The form projects the native private draft; only acknowledged edits may save.
class RssSourceEditorPage extends ConsumerStatefulWidget {
  const RssSourceEditorPage({super.key});
  @override
  ConsumerState<RssSourceEditorPage> createState() =>
      _RssSourceEditorPageState();
}

class _RssSourceEditorPageState extends ConsumerState<RssSourceEditorPage>
    with RestorationMixin {
  final _scrollControllers = List.generate(
    4,
    (_) => _RestorableEditorScrollController(),
  );
  @override
  String? get restorationId => 'rss-source-editor';
  @override
  void restoreState(RestorationBucket? oldBucket, bool initialRestore) {
    for (var i = 0; i < _scrollControllers.length; i++) {
      registerForRestoration(_scrollControllers[i], 'rss-tab-scroll-$i');
    }
  }

  @override
  void dispose() {
    for (final controller in _scrollControllers) {
      controller.dispose();
    }
    super.dispose();
  }

  final _fieldKeys = <String, GlobalKey>{};
  bool _help = false;
  bool _rejected = false;
  Future<RssEditorSnapshot?> _send(
    String op, [
    Map<String, Object?> args = const {},
  ]) async {
    final bridge = ref.read(rssEditorBridgeProvider);
    final generation = bridge.latestSnapshot?.generation;
    if (generation == null) return null;
    try {
      final ack = await bridge.command(op, generation, args);
      if (mounted) setState(() => _rejected = bridge.hasRejectedFields);
      return bridge.latestSnapshot ?? ack;
    } catch (error) {
      if (mounted) {
        setState(() => _rejected = bridge.hasRejectedFields);
        final l = AppLocalizations.of(context);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              op == 'field' || error is RssEditorWriteBlocked
                  ? l.rssEditorEditRejected
                  : l.rssEditorFailed,
            ),
          ),
        );
      }
      if (op == 'field' || op == 'option') rethrow;
      return null;
    }
  }

  void _native(String action) => unawaited(_send('native', {'action': action}));
  void _save(String action) => unawaited(_send('save', {'action': action}));
  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final async = ref.watch(rssEditorStateProvider);
    final state = async.asData?.value;
    final editable = state?.canEdit == true;
    final ime = MediaQuery.viewInsetsOf(context).bottom > 0;
    final failure =
        state?.error ??
        (_rejected
            ? l.rssEditorEditRejected
            : async.hasError
            ? l.rssEditorFailed
            : state?.missing == true
            ? l.rssEditorMissing
            : null);
    final issue = switch (state?.issue) {
      'Required' => l.rssEditorRequired,
      'Format' => l.rssEditorFormat,
      'Focus' => l.rssEditorFocus,
      'NoLogin' => l.rssEditorNoLogin,
      _ => null,
    };
    final fields = state?.tabs[state.tab].fields ?? const <RssEditorField>[];
    final menus = <String, String>{
      'Debug': l.sourceEditorDebug,
      'Login': l.sourceEditorLogin,
      'Variable': l.sourceEditorVariable,
      'Cookie': l.sourceEditorClearCookie,
      'AutoComplete': l.sourceEditorAutoComplete,
      'Clipboard': l.sourceEditorCopy,
      'ScanQr': l.sourceEditorQr,
      'ShareText': l.sourceEditorShare,
      'ShareQr': l.sourceEditorQrShare,
      'Log': l.sourceEditorLog,
      'Help': l.sourceEditorHelp,
      'Paste': l.sourceEditorPaste,
    };
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (didPop) return;
        if (_help) {
          setState(() => _help = false);
        } else {
          unawaited(_send(state?.exit == true ? 'keepEditing' : 'cancel'));
        }
      },
      child: Scaffold(
        appBar: AppBar(
          title: Text(l.rssEditorTitle),
          leading: rssEditorTag(
            'rss-close',
            IconButton(
              tooltip: l.sourceEditorClose,
              onPressed: state == null || state.busy || state.editorPending
                  ? null
                  : () => _send('cancel'),
              icon: const Icon(Icons.arrow_back),
            ),
          ),
          actions: [
            rssEditorTag(
              'rss-fullscreen',
              IconButton(
                tooltip: l.sourceEditorFullScreen,
                onPressed: editable ? () => _send('editor') : null,
                icon: const Icon(Icons.fullscreen),
              ),
            ),
            rssEditorTag(
              'rss-save',
              IconButton(
                tooltip: l.sourceEditorSave,
                onPressed: editable ? () => _save('Close') : null,
                icon: const Icon(Icons.save_outlined),
              ),
            ),
            rssEditorTag(
              'rss-menu',
              PopupMenuButton<String>(
                tooltip: l.sourceEditorMenu,
                enabled: editable,
                onSelected: (value) {
                  switch (value) {
                    case 'Debug':
                    case 'Login':
                    case 'Variable':
                      _save(value);
                    case 'Cookie':
                      unawaited(_send('clearCookie'));
                    case 'AutoComplete':
                      unawaited(
                        _send('autoComplete', {
                          'value':
                              !(ref
                                      .read(rssEditorBridgeProvider)
                                      .latestSnapshot
                                      ?.autoComplete ??
                                  false),
                        }),
                      );
                    case 'Paste':
                      unawaited(_send('paste'));
                    default:
                      _native(value);
                  }
                },
                itemBuilder: (_) => menus.entries
                    .map(
                      (e) => PopupMenuItem(
                        value: e.key,
                        child: rssEditorTag(
                          'rss-menu-${e.key}',
                          Row(
                            children: [
                              Expanded(child: Text(e.value)),
                              if (e.key == 'AutoComplete' &&
                                  state?.autoComplete == true)
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
                      state?.loading == true ||
                      state?.busy == true)
                    const LinearProgressIndicator(),
                  if (failure != null || issue != null)
                    Padding(
                      padding: const EdgeInsets.all(12),
                      child: Row(
                        children: [
                          Expanded(
                            child: Text(
                              failure ?? issue!,
                              style: TextStyle(
                                color: Theme.of(context).colorScheme.error,
                              ),
                            ),
                          ),
                          if (failure != null &&
                              (async.hasError ||
                                  state?.loaded != true ||
                                  state?.editorPending == true))
                            rssEditorTag(
                              'rss-retry',
                              TextButton(
                                onPressed: state?.busy == true
                                    ? null
                                    : () {
                                        if (async.hasError) {
                                          ref.invalidate(
                                            rssEditorStateProvider,
                                          );
                                        } else {
                                          unawaited(
                                            _send(
                                              state?.editorPending == true
                                                  ? 'retryEditor'
                                                  : 'retry',
                                            ),
                                          );
                                        }
                                      },
                                child: Text(l.sourceEditorRetry),
                              ),
                            ),
                          if (state?.editorPending == true)
                            TextButton(
                              onPressed: () => _send('discardEditor'),
                              child: Text(l.sourceEditorCancel),
                            ),
                        ],
                      ),
                    ),
                  if (state != null && !state.finished) ...[
                    if (!ime) ...[
                      _RssOptions(
                        key: ValueKey('options-${state.generation}'),
                        state: state,
                        command: _send,
                      ),
                      rssEditorTag(
                        'rss-tabs',
                        SingleChildScrollView(
                          scrollDirection: Axis.horizontal,
                          child: Row(
                            children: state.tabs
                                .map(
                                  (t) => rssEditorTag(
                                    'rss-tab-${t.index}',
                                    Padding(
                                      padding: const EdgeInsets.symmetric(
                                        horizontal: 2,
                                      ),
                                      child: ChoiceChip(
                                        label: Text(t.label),
                                        selected: t.index == state.tab,
                                        onSelected: editable
                                            ? (_) {
                                                FocusManager
                                                    .instance
                                                    .primaryFocus
                                                    ?.unfocus();
                                                unawaited(
                                                  _send('tab', {
                                                    'index': t.index,
                                                  }),
                                                );
                                              }
                                            : null,
                                      ),
                                    ),
                                  ),
                                )
                                .toList(),
                          ),
                        ),
                      ),
                    ],
                    rssEditorTag(
                      'rss-navigation',
                      SingleChildScrollView(
                        scrollDirection: Axis.horizontal,
                        child: Row(
                          children: fields
                              .map(
                                (f) => rssEditorTag(
                                  'rss-nav-${f.key}',
                                  TextButton(
                                    onPressed: editable
                                        ? () {
                                            final target = _fieldKeys[f.key]
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
                                              _send('focus', {'key': f.key}),
                                            );
                                          }
                                        : null,
                                    child: Text(
                                      f.key == 'shouldOverrideUrlLoading'
                                          ? l.rssEditorUrlOverride
                                          : f.label,
                                    ),
                                  ),
                                ),
                              )
                              .toList(),
                        ),
                      ),
                    ),
                    Expanded(
                      child: rssEditorTag(
                        'rss-fields-${state.tab}',
                        SingleChildScrollView(
                          key: PageStorageKey('rss-fields-${state.tab}'),
                          controller: _scrollControllers[state.tab].value,
                          padding: const EdgeInsets.all(12),
                          child: Column(
                            children: [
                              if (state.tab == 3)
                                _RssWebOptions(
                                  key: ValueKey('web-${state.generation}'),
                                  state: state,
                                  command: _send,
                                ),
                              ...fields.map(
                                (f) => Padding(
                                  key: _fieldKeys.putIfAbsent(
                                    f.key,
                                    () => GlobalKey(),
                                  ),
                                  padding: const EdgeInsets.only(bottom: 12),
                                  child: RssEditorTextField(
                                    key: ValueKey(
                                      '${state.generation}:${f.key}',
                                    ),
                                    field: f,
                                    focused: state.focus == f.key,
                                    enabled: editable,
                                    maxLines: state.maxLines,
                                    command: _send,
                                    latestField: () => ref
                                        .read(rssEditorBridgeProvider)
                                        .latestSnapshot
                                        ?.field(f.key),
                                    onEditor: () => unawaited(_send('editor')),
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                    if (ime)
                      SizedBox(
                        height: state.keyboardRows * 40,
                        child: rssEditorTag(
                          'rss-keyboard',
                          GridView.count(
                            scrollDirection: Axis.horizontal,
                            crossAxisCount: state.keyboardRows,
                            childAspectRatio: .65,
                            children: [
                              IconButton(
                                tooltip: l.sourceEditorKeyboardHelp,
                                onPressed: editable
                                    ? () => setState(() => _help = true)
                                    : null,
                                icon: const Icon(Icons.help_outline),
                              ),
                              rssEditorTag(
                                'rss-undo',
                                IconButton(
                                  tooltip: l.sourceEditorUndo,
                                  onPressed: editable
                                      ? () => _send('undo')
                                      : null,
                                  icon: const Icon(Icons.undo),
                                ),
                              ),
                              rssEditorTag(
                                'rss-redo',
                                IconButton(
                                  tooltip: l.sourceEditorRedo,
                                  onPressed: editable
                                      ? () => _send('redo')
                                      : null,
                                  icon: const Icon(Icons.redo),
                                ),
                              ),
                              ...state.assists.map(
                                (a) => TextButton(
                                  onPressed: editable
                                      ? () => _send('insert', {
                                          'text': a['value'],
                                        })
                                      : null,
                                  child: Text(a['key']!),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                  ] else
                    const Expanded(child: SizedBox()),
                ],
              ),
              if (state?.exit == true)
                _modal(
                  AlertDialog(
                    title: Text(l.sourceEditorDiscardTitle),
                    content: Text(l.sourceEditorDiscardBody),
                    actions: [
                      rssEditorTag(
                        'rss-discard-cancel',
                        TextButton(
                          onPressed: () => _send('keepEditing'),
                          child: Text(l.sourceEditorKeepEditing),
                        ),
                      ),
                      rssEditorTag(
                        'rss-discard-confirm',
                        TextButton(
                          onPressed: () => _send('discard'),
                          child: Text(l.sourceEditorDiscard),
                        ),
                      ),
                    ],
                  ),
                  () => _send('keepEditing'),
                ),
              if (_help)
                _modal(
                  AlertDialog(
                    title: Text(l.sourceEditorKeyboardHelp),
                    content: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        for (final entry in <String, String>{
                          'keyboardConfig': l.sourceEditorKeyboardConfig,
                          'UrlOptions': l.sourceEditorUrlOptions,
                          'Help': l.sourceEditorHelp,
                          'JsHelp': l.sourceEditorJsHelp,
                          'RegexHelp': l.sourceEditorRegexHelp,
                          'File': l.sourceEditorChooseFile,
                        }.entries)
                          TextButton(
                            onPressed: () {
                              setState(() => _help = false);
                              if (entry.key == 'keyboardConfig') {
                                unawaited(_send('keyboardConfig'));
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
}

class _RssOptions extends StatelessWidget {
  const _RssOptions({super.key, required this.state, required this.command});
  final RssEditorSnapshot state;
  final RssEditorCommand command;
  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        rssEditorTag(
          'rss-options-toggle',
          TextButton(
            onPressed: state.canEdit
                ? () => command('expanded', {'value': !state.expanded})
                : null,
            child: Text(
              '${l.sourceEditorOptions} ${state.expanded ? '▴' : '▾'}',
            ),
          ),
        ),
        if (state.expanded)
          _RssOptionChips(state: state, command: command, web: false),
        _RssOptionChips(state: state, command: command, web: null),
      ],
    );
  }
}

class _RssWebOptions extends StatelessWidget {
  const _RssWebOptions({super.key, required this.state, required this.command});
  final RssEditorSnapshot state;
  final RssEditorCommand command;
  @override
  Widget build(BuildContext context) =>
      _RssOptionChips(state: state, command: command, web: true);
}

class _RssOptionChips extends StatefulWidget {
  const _RssOptionChips({
    required this.state,
    required this.command,
    required this.web,
  });
  final RssEditorSnapshot state;
  final RssEditorCommand command;
  final bool? web;
  @override
  State<_RssOptionChips> createState() => _RssOptionChipsState();
}

class _RssOptionChipsState extends State<_RssOptionChips> {
  late final Map<String, Object?> _values = Map.of(widget.state.options);
  final _pending = <String, int>{};
  int _serial = 0;
  @override
  void didUpdateWidget(covariant _RssOptionChips old) {
    super.didUpdateWidget(old);
    for (final e in widget.state.options.entries) {
      if (!_pending.containsKey(e.key)) _values[e.key] = e.value;
    }
  }

  void _update(String key, Object value) {
    final serial = ++_serial;
    setState(() {
      _values[key] = value;
      _pending[key] = serial;
    });
    unawaited(
      widget
          .command('option', {'key': key, 'value': value})
          .then(
            (ack) {
              if (!mounted || _pending[key] != serial) return;
              setState(() {
                _pending.remove(key);
                _values[key] = ack?.options[key] ?? widget.state.options[key];
              });
            },
            onError: (Object error, StackTrace stack) {
              if (!mounted || _pending[key] != serial) return;
              setState(() {
                _pending.remove(key);
                _values[key] = widget.state.options[key];
              });
            },
          ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final labels = widget.web == true
        ? {
            'enableJs': l.rssEditorEnableJs,
            'loadWithBaseUrl': l.rssEditorBaseUrl,
            'showWebLog': l.rssEditorWebLog,
            'cacheFirst': l.rssEditorCacheFirst,
          }
        : {
            'enabled': l.sourceEditorEnabled,
            'singleUrl': l.rssEditorSingleUrl,
            'cookieJar': l.sourceEditorCookie,
            'preload': l.rssEditorPreload,
          };
    return rssEditorTag(
      widget.web == true
          ? 'rss-web-options'
          : widget.web == false
          ? 'rss-options'
          : 'rss-option-enums',
      SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: Row(
          children: [
            if (widget.web != null)
              for (final e in labels.entries)
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 2),
                  child: rssEditorTag(
                    'rss-option-${e.key}',
                    FilterChip(
                      label: Text(e.value),
                      selected: _values[e.key] == true,
                      onSelected: widget.state.canEdit
                          ? (_) => _update(e.key, !(_values[e.key] as bool))
                          : null,
                    ),
                  ),
                ),
            if (widget.web == null)
              for (final entry in {
                'type': widget.state.typeLabels,
                'articleStyle': widget.state.articleStyleLabels,
              }.entries)
                rssEditorTag(
                  entry.key == 'type' ? 'rss-type' : 'rss-article-style',
                  PopupMenuButton<int>(
                    enabled: widget.state.canEdit,
                    tooltip: entry.key == 'type'
                        ? l.sourceEditorType
                        : l.rssEditorArticleStyle,
                    onSelected: (v) => _update(entry.key, v),
                    itemBuilder: (_) => List.generate(
                      entry.value.length,
                      (i) => PopupMenuItem(
                        value: i,
                        child: rssEditorTag(
                          '${entry.key == 'type' ? 'rss-type' : 'rss-article-style'}-$i',
                          Text(entry.value[i]),
                        ),
                      ),
                    ),
                    child: Padding(
                      padding: const EdgeInsets.all(12),
                      child: Text(
                        '${entry.key == 'type' ? l.sourceEditorType : l.rssEditorArticleStyle}: ${entry.value[_values[entry.key] as int]}',
                      ),
                    ),
                  ),
                ),
          ],
        ),
      ),
    );
  }
}

/// Local edits survive older event echoes until their own native reply arrives.
class RssEditorTextField extends StatefulWidget {
  const RssEditorTextField({
    super.key,
    required this.field,
    required this.focused,
    required this.enabled,
    required this.maxLines,
    required this.command,
    required this.onEditor,
    required this.latestField,
  });
  final RssEditorField field;
  final bool focused, enabled;
  final int maxLines;
  final RssEditorCommand command;
  final VoidCallback onEditor;
  final RssEditorField? Function() latestField;
  @override
  State<RssEditorTextField> createState() => _RssEditorTextFieldState();
}

class _RssEditorTextFieldState extends State<RssEditorTextField> {
  late final _controller = TextEditingController.fromValue(
    _value(widget.field),
  );
  final _focus = FocusNode();
  final _pending = <int>{};
  int _serial = 0;
  bool _reflecting = false;
  late TextEditingValue _submitted;
  static TextEditingValue _value(RssEditorField f) => TextEditingValue(
    text: f.text ?? '',
    selection: TextSelection(baseOffset: f.start, extentOffset: f.end),
  );
  @override
  void initState() {
    super.initState();
    _submitted = _controller.value;
    _controller.addListener(_changed);
    _focus.addListener(_focused);
    _requestFocus();
  }

  void _focused() {
    if (_focus.hasFocus) {
      unawaited(widget.command('focus', {'key': widget.field.key}));
    }
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

  void _reflect(RssEditorField f) {
    final value = _value(f);
    _submitted = value;
    if (_controller.text == value.text &&
        _controller.selection == value.selection) {
      return;
    }
    _reflecting = true;
    _controller.value = value;
    _reflecting = false;
  }

  void _changed() {
    final value = _controller.value;
    if (_reflecting ||
        !widget.enabled ||
        widget.field.external ||
        !value.selection.isValid ||
        (_submitted.text == value.text &&
            _submitted.selection == value.selection)) {
      return;
    }
    _submitted = value;
    final serial = ++_serial;
    _pending.add(serial);
    unawaited(
      widget
          .command('field', {
            'key': widget.field.key,
            'value': value.text,
            'start': value.selection.baseOffset,
            'end': value.selection.extentOffset,
          })
          .then(
            (ack) {
              if (!mounted) return;
              _pending.remove(serial);
              if (_pending.isEmpty) {
                _reflect(
                  widget.latestField() ??
                      ack?.field(widget.field.key) ??
                      widget.field,
                );
              }
            },
            onError: (Object error, StackTrace stack) {
              if (!mounted) return;
              _pending.remove(serial);
              if (_pending.isEmpty) {
                _reflect(widget.latestField() ?? widget.field);
              }
            },
          ),
    );
  }

  @override
  void didUpdateWidget(covariant RssEditorTextField old) {
    super.didUpdateWidget(old);
    if (widget.field.external) _pending.clear();
    if (_pending.isEmpty) _reflect(widget.field);
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
    final unsafe =
        widget.field.external ||
        isSourceEditorInlineUnsafe(widget.field.text ?? '');
    return rssEditorTag(
      'rss-field-${widget.field.key}',
      unsafe
          ? ListTile(
              title: Text(
                widget.field.key == 'shouldOverrideUrlLoading'
                    ? AppLocalizations.of(context).rssEditorUrlOverride
                    : widget.field.label,
              ),
              subtitle: Text(AppLocalizations.of(context).rssEditorExternal),
              trailing: const Icon(Icons.open_in_full),
              onTap: widget.enabled
                  ? () async {
                      await widget.command('focus', {'key': widget.field.key});
                      widget.onEditor();
                    }
                  : null,
            )
          : TextField(
              controller: _controller,
              focusNode: _focus,
              enabled: widget.enabled,
              minLines: 1,
              maxLines: widget.maxLines.clamp(1, 50),
              keyboardType: TextInputType.multiline,
              style: const TextStyle(fontFamily: 'monospace'),
              decoration: InputDecoration(
                labelText: widget.field.key == 'shouldOverrideUrlLoading'
                    ? AppLocalizations.of(context).rssEditorUrlOverride
                    : widget.field.label,
                border: const OutlineInputBorder(),
              ),
            ),
    );
  }
}

/// ScrollController has no framework restorable wrapper. Serialize its actual
/// attached offset and restore an owned controller for each independent tab.
class _RestorableEditorScrollController
    extends RestorableChangeNotifier<ScrollController> {
  @override
  ScrollController createDefaultValue() => ScrollController();
  @override
  ScrollController fromPrimitives(Object? data) =>
      ScrollController(initialScrollOffset: (data! as num).toDouble());
  @override
  Object toPrimitives() =>
      value.hasClients ? value.offset : value.initialScrollOffset;
}
