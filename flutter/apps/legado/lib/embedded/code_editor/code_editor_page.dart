import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import '../../l10n/generated/app_localizations.dart';
import 'code_editor_bridge.dart';
import 'code_editor_state.dart';
import 'code_editor_surface.dart';
import '../source_editor/source_editor_page.dart' show editorTag;

typedef CodeEditorCommand = Future<void> Function(
  String, [
  Map<String, Object?>,
]);

/// Chrome and search live in Flutter. The document and its selection remain
/// exclusively owned by the Android session and registered editing surface.
class CodeEditorPage extends ConsumerStatefulWidget {
  const CodeEditorPage({super.key});
  @override
  ConsumerState<CodeEditorPage> createState() => _CodeEditorPageState();
}

class _CodeEditorPageState extends ConsumerState<CodeEditorPage> {
  final _searchKey = GlobalKey<CodeEditorSearchPanelState>();
  bool _help = false;
  bool _rejected = false;
  Future<void> _command(String op, [Map<String, Object?> args = const {}]) =>
      _send(op, args);
  Future<void> _searchCommand(
    String op, [
    Map<String, Object?> args = const {},
  ]) => _send(op, args, propagate: op == 'search');
  Future<void> _send(
    String op,
    Map<String, Object?> args, {
    bool propagate = false,
  }) async {
    final bridge = ref.read(codeEditorBridgeProvider);
    try {
      await bridge.command(op, args);
      if (mounted && _rejected != bridge.hasRejectedSearch) {
        setState(() => _rejected = bridge.hasRejectedSearch);
      }
    } catch (error) {
      if (mounted) {
        setState(() => _rejected = bridge.hasRejectedSearch);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              op == 'search' || error is CodeEditorWriteBlocked
                  ? AppLocalizations.of(context).codeEditorSearchRejected
                  : error.toString(),
            ),
          ),
        );
      }
      if (propagate) rethrow;
    }
  }

  void _action(String name) => unawaited(_command('action', {'name': name}));
  void _insert(String text) {
    if (_searchKey.currentState?.insertAssist(text) != true) {
      unawaited(_command('insert', {'text': text}));
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final async = ref.watch(codeEditorStateProvider);
    final state = async.asData?.value;
    final enabled = state?.enabled ?? false;
    final writable = state?.writable ?? false;
    final safe = state?.safe ?? true;
    final error =
        state?.error ??
        (_rejected
            ? l.codeEditorSearchRejected
            : async.hasError
            ? l.codeEditorLoadingFailed
            : null);
    final actions = <String, String>{
      if (writable && (state?.showLoginSource ?? false))
        'LOGIN': l.codeEditorLogin,
      if (!safe) ...{
        'THEME': l.codeEditorTheme,
        'SELECT_ALL': l.codeEditorSelectAll,
        if (writable) 'FORMAT': l.codeEditorFormat,
        if (state?.checkJavaScriptSyntax ?? false) 'SYNTAX': l.codeEditorSyntax,
        'SETTINGS': l.codeEditorSettings,
        'WRAP': l.codeEditorWrap,
      },
      'CURL': l.codeEditorCurl,
      'LOG': l.codeEditorLog,
      'HELP': l.codeEditorHelp,
    };
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (didPop) return;
        if (_help) {
          setState(() => _help = false);
        } else if (state?.confirmDiscard ?? false) {
          unawaited(_command('keepEditing'));
        } else {
          unawaited(_command('exit'));
        }
      },
      child: Scaffold(
        appBar: PreferredSize(
          preferredSize: const Size.fromHeight(56),
          child: editorTag(
            'code-toolbar',
            AppBar(
              title: Text(
                state?.title ?? l.codeEditorTitle,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              leading: editorTag(
                'code-close',
                IconButton(
                  tooltip: l.codeEditorClose,
                  onPressed: state?.busy == true
                      ? null
                      : () => _command('exit'),
                  icon: const Icon(Icons.close),
                ),
              ),
              actions: [
                if (!safe)
                  editorTag(
                    'code-search-toggle',
                    IconButton(
                      tooltip: l.codeEditorSearch,
                      onPressed: enabled ? () => _action('SEARCH') : null,
                      icon: const Icon(Icons.search),
                    ),
                  ),
                if (writable)
                  editorTag(
                    'code-save',
                    IconButton(
                      tooltip: l.codeEditorSave,
                      onPressed: enabled ? () => _action('SAVE') : null,
                      icon: const Icon(Icons.save_outlined),
                    ),
                  ),
                if (writable && (state?.showDebugSource ?? false))
                  editorTag(
                    'code-debug',
                    IconButton(
                      tooltip: l.codeEditorDebug,
                      onPressed: enabled ? () => _action('DEBUG') : null,
                      icon: const Icon(Icons.bug_report_outlined),
                    ),
                  ),
                editorTag(
                  'code-menu',
                  PopupMenuButton<String>(
                    tooltip: l.codeEditorMenu,
                    enabled: state?.busy != true,
                    onSelected: (name) {
                      if (name == 'HELP') {
                        setState(() => _help = true);
                      } else {
                        _action(name);
                      }
                    },
                    itemBuilder: (_) => actions.entries
                        .map(
                          (e) => PopupMenuItem(
                            value: e.key,
                            enabled: e.key == 'HELP' || e.key == 'LOG'
                                ? state?.busy != true
                                : enabled,
                            child: editorTag(
                              e.key == 'LOGIN'
                                  ? 'code-login'
                                  : 'code-menu-${e.key}',
                              Row(
                                children: [
                                  Expanded(child: Text(e.value)),
                                  if (e.key == 'WRAP' &&
                                      (state?.autoWrap ?? false))
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
          ),
        ),
        body: SafeArea(
          top: false,
          child: Stack(
            children: [
              Column(
                children: [
                  if (async.isLoading ||
                      state?.busy == true ||
                      state?.engine.reading == true ||
                      state?.engine.replacing == true ||
                      (state?.hasSession == true &&
                          state?.engine.ready == false &&
                          state?.engine.failed == false))
                    editorTag('code-progress', const LinearProgressIndicator()),
                  if (error != null)
                    Padding(
                      padding: const EdgeInsets.all(8),
                      child: Row(
                        children: [
                          Expanded(
                            child: Text(
                              error,
                              style: TextStyle(
                                color: Theme.of(context).colorScheme.error,
                              ),
                            ),
                          ),
                          editorTag(
                            'code-retry',
                            TextButton(
                              onPressed: state?.busy == true
                                  ? null
                                  : () {
                                      if (async.hasError) {
                                        ref.invalidate(codeEditorStateProvider);
                                      } else {
                                        unawaited(_command('retry'));
                                      }
                                    },
                              child: Text(l.codeEditorRetry),
                            ),
                          ),
                        ],
                      ),
                    ),
                  if (state?.engine.replacementError
                      case final replacementError?)
                    Padding(
                      padding: const EdgeInsets.all(8),
                      child: Text(
                        replacementError,
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.error,
                        ),
                      ),
                    ),
                  if (state?.engine.failed ?? false)
                    Row(
                      children: [
                        Expanded(
                          child: Padding(
                            padding: const EdgeInsets.all(8),
                            child: Text(l.codeEditorLoadFailed),
                          ),
                        ),
                        editorTag(
                          'code-restart',
                          TextButton(
                            onPressed: state?.busy == true
                                ? null
                                : () => _command('restart'),
                            child: Text(l.codeEditorRestart),
                          ),
                        ),
                      ],
                    ),
                  Expanded(
                    child: editorTag(
                      'code-editor-surface',
                      state?.hasSession == true &&
                              !state!.finished &&
                              state.generation > 0
                          ? KeyedSubtree(
                              key: ValueKey(
                                'code-generation-${state.generation}',
                              ),
                              child: ref.watch(
                                codeEditorSurfaceBuilderProvider,
                              )(context, state.generation),
                            )
                          : const SizedBox.expand(),
                    ),
                  ),
                  if (state != null && !safe && state.search.visible)
                    CodeEditorSearchPanel(
                      key: _searchKey,
                      search: state.search,
                      generation: state.generation,
                      enabled: enabled,
                      writable: writable,
                      result: state.engine.searchResult,
                      command: _searchCommand,
                      action: _action,
                    ),
                  if (state != null &&
                      MediaQuery.viewInsetsOf(context).bottom > 0)
                    _keyboard(state, l),
                ],
              ),
              if (state?.confirmDiscard ?? false)
                _modal(
                  AlertDialog(
                    title: Text(l.codeEditorDiscardTitle),
                    content: Text(l.codeEditorDiscardBody),
                    actions: [
                      editorTag(
                        'code-discard-cancel',
                        TextButton(
                          onPressed: () => _command('keepEditing'),
                          child: Text(l.codeEditorKeepEditing),
                        ),
                      ),
                      editorTag(
                        'code-discard-confirm',
                        TextButton(
                          onPressed: () => _command('discard'),
                          child: Text(l.codeEditorDiscard),
                        ),
                      ),
                    ],
                  ),
                  () => _command('keepEditing'),
                ),
              if (_help)
                _modal(
                  AlertDialog(
                    title: Text(l.codeEditorHelp),
                    content: SingleChildScrollView(
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          for (final entry in {
                            'KEYBOARD_CONFIG': l.codeEditorKeyboardConfig,
                            'RULE_HELP': l.codeEditorRuleHelp,
                            'RSS_HELP': l.codeEditorRssHelp,
                            'JS_HELP': l.codeEditorJsHelp,
                            'REGEX_HELP': l.codeEditorRegexHelp,
                          }.entries)
                            editorTag(
                              'code-help-${entry.key}',
                              TextButton(
                                onPressed: () {
                                  setState(() => _help = false);
                                  _action(entry.key);
                                },
                                child: Text(entry.value),
                              ),
                            ),
                        ],
                      ),
                    ),
                    actions: [
                      TextButton(
                        onPressed: () => setState(() => _help = false),
                        child: Text(l.codeEditorClose),
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
  Widget _keyboard(CodeEditorSnapshot state, AppLocalizations l) => editorTag(
    'code-keyboard',
    SizedBox(
      height: state.keyboardRows * 40,
      child: Focus(
        canRequestFocus: false,
        descendantsAreFocusable: false,
        child: GridView.count(
          scrollDirection: Axis.horizontal,
          crossAxisCount: state.keyboardRows,
          childAspectRatio: .65,
          children: [
            editorTag(
              'code-help',
              IconButton(
                tooltip: l.codeEditorHelp,
                onPressed: state.enabled
                    ? () => setState(() => _help = true)
                    : null,
                icon: const Icon(Icons.help_outline),
              ),
            ),
            editorTag(
              'code-undo',
              IconButton(
                tooltip: l.codeEditorUndo,
                onPressed: state.enabled && state.writable
                    ? () => _action('UNDO')
                    : null,
                icon: const Icon(Icons.undo),
              ),
            ),
            editorTag(
              'code-redo',
              IconButton(
                tooltip: l.codeEditorRedo,
                onPressed: state.enabled && state.writable
                    ? () => _action('REDO')
                    : null,
                icon: const Icon(Icons.redo),
              ),
            ),
            for (var i = 0; i < state.assists.length; i++)
              editorTag(
                'code-assist-$i',
                TextButton(
                  onPressed: state.enabled
                      ? () => _insert(state.assists[i]['value']!)
                      : null,
                  child: Text(state.assists[i]['key']!),
                ),
              ),
          ],
        ),
      ),
    ),
  );
}

class CodeEditorSearchPanel extends StatefulWidget {
  const CodeEditorSearchPanel({
    super.key,
    required this.search,
    required this.generation,
    required this.enabled,
    required this.writable,
    required this.result,
    required this.command,
    required this.action,
  });
  final CodeEditorSearch search;
  final int generation;
  final bool enabled, writable;
  final String result;
  final CodeEditorCommand command;
  final ValueChanged<String> action;
  @override
  State<CodeEditorSearchPanel> createState() => CodeEditorSearchPanelState();
}

class CodeEditorSearchPanelState extends State<CodeEditorSearchPanel> {
  late CodeEditorSearch _draft = widget.search;
  final _pending = <CodeEditorSearch>[];
  bool _reflecting = false;
  late final _query = TextEditingController.fromValue(
    _queryValue(widget.search),
  );
  late final _replacement = TextEditingController.fromValue(
    _replacementValue(widget.search),
  );
  final _queryFocus = FocusNode();
  final _replacementFocus = FocusNode();
  static TextEditingValue _queryValue(CodeEditorSearch s) => TextEditingValue(
    text: s.query,
    selection: TextSelection(
      baseOffset: s.queryStart,
      extentOffset: s.queryEnd,
    ),
  );
  static TextEditingValue _replacementValue(CodeEditorSearch s) =>
      TextEditingValue(
        text: s.replacement,
        selection: TextSelection(
          baseOffset: s.replacementStart,
          extentOffset: s.replacementEnd,
        ),
      );
  @override
  void initState() {
    super.initState();
    _query.addListener(_queryChanged);
    _replacement.addListener(_replacementChanged);
    _requestFocus();
  }

  void _requestFocus() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !widget.enabled) return;
      if (_draft.replaceVisible) {
        _replacementFocus.requestFocus();
      } else {
        _queryFocus.requestFocus();
      }
    });
  }

  void _queryChanged() {
    if (_reflecting || !widget.enabled || !_query.selection.isValid) return;
    _change(
      _draft.copyWith(
        query: _query.text,
        queryStart: _query.selection.baseOffset,
        queryEnd: _query.selection.extentOffset,
      ),
    );
  }

  void _replacementChanged() {
    if (_reflecting || !widget.enabled || !_replacement.selection.isValid) {
      return;
    }
    _change(
      _draft.copyWith(
        replacement: _replacement.text,
        replacementStart: _replacement.selection.baseOffset,
        replacementEnd: _replacement.selection.extentOffset,
      ),
    );
  }

  void _change(CodeEditorSearch value) {
    final focusReplacement = !_draft.replaceVisible && value.replaceVisible;
    setState(() => _draft = value);
    _pending.add(value);
    unawaited(
      widget.command('search', {'value': value.toMap()}).catchError((
        Object error,
        StackTrace stack,
      ) {
        if (!mounted) return;
        _pending.clear();
        setState(() => _draft = widget.search);
        _reflect(widget.search);
      }),
    );
    if (focusReplacement) _requestFocus();
  }

  void _reflect(CodeEditorSearch value) {
    _reflecting = true;
    final query = _queryValue(value);
    if (_query.text != query.text || _query.selection != query.selection) {
      _query.value = query;
    }
    final replacement = _replacementValue(value);
    if (_replacement.text != replacement.text ||
        _replacement.selection != replacement.selection) {
      _replacement.value = replacement;
    }
    _reflecting = false;
  }

  @override
  void didUpdateWidget(covariant CodeEditorSearchPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.generation != widget.generation) {
      _pending.clear();
      _draft = widget.search;
      _reflect(_draft);
      _requestFocus();
      return;
    }
    if (!oldWidget.enabled && widget.enabled) {
      _requestFocus();
    }
    final at = _pending.lastIndexOf(widget.search);
    if (at >= 0) _pending.removeRange(0, at + 1);
    if (_pending.isEmpty) {
      final focusReplacement =
          !_draft.replaceVisible && widget.search.replaceVisible;
      _draft = widget.search;
      _reflect(_draft);
      if (focusReplacement) _requestFocus();
    }
  }

  bool insertAssist(String text) {
    final controller = _replacementFocus.hasFocus
        ? _replacement
        : _queryFocus.hasFocus
        ? _query
        : null;
    if (controller == null) return false;
    final selection = controller.selection;
    final start = selection.isValid ? selection.start : controller.text.length;
    final end = selection.isValid ? selection.end : start;
    final value = controller.text.replaceRange(start, end, text);
    if (value.length > 65536) return true;
    controller.value = TextEditingValue(
      text: value,
      selection: TextSelection.collapsed(offset: start + text.length),
    );
    return true;
  }

  @override
  void dispose() {
    _query.dispose();
    _replacement.dispose();
    _queryFocus.dispose();
    _replacementFocus.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final limit = TextInputFormatter.withFunction(
      (oldValue, newValue) =>
          newValue.text.length <= 65536 ? newValue : oldValue,
    );
    return editorTag(
      'code-search',
      Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              Expanded(
                child: editorTag(
                  'code-query',
                  TextField(
                    controller: _query,
                    focusNode: _queryFocus,
                    enabled: widget.enabled,
                    maxLines: 1,
                    inputFormatters: [limit],
                    decoration: InputDecoration(
                      labelText: l.codeEditorQuery,
                      border: const OutlineInputBorder(),
                    ),
                  ),
                ),
              ),
              editorTag(
                'code-search-close',
                IconButton(
                  tooltip: l.codeEditorClose,
                  onPressed: widget.enabled
                      ? () => _change(_draft.copyWith(visible: false))
                      : null,
                  icon: const Icon(Icons.close),
                ),
              ),
            ],
          ),
          Wrap(
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              editorTag(
                'code-regex',
                Checkbox(
                  value: _draft.regex,
                  onChanged: widget.enabled
                      ? (_) => _change(_draft.copyWith(regex: !_draft.regex))
                      : null,
                ),
              ),
              Text(l.codeEditorRegex),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 8),
                child: Text(widget.result),
              ),
              editorTag(
                'code-previous',
                IconButton(
                  tooltip: l.codeEditorPrevious,
                  onPressed: widget.enabled
                      ? () => widget.action('PREVIOUS')
                      : null,
                  icon: const Icon(Icons.arrow_upward),
                ),
              ),
              editorTag(
                'code-next',
                IconButton(
                  tooltip: l.codeEditorNext,
                  onPressed: widget.enabled
                      ? () => widget.action('NEXT')
                      : null,
                  icon: const Icon(Icons.arrow_downward),
                ),
              ),
              editorTag(
                'code-replace',
                TextButton(
                  onPressed: widget.enabled && widget.writable
                      ? () {
                          if (_draft.replaceVisible) {
                            widget.action('REPLACE');
                          } else {
                            _change(_draft.copyWith(replaceVisible: true));
                          }
                        }
                      : null,
                  child: Text(l.codeEditorReplace),
                ),
              ),
            ],
          ),
          if (_draft.replaceVisible)
            Row(
              children: [
                Expanded(
                  child: editorTag(
                    'code-replacement',
                    TextField(
                      controller: _replacement,
                      focusNode: _replacementFocus,
                      enabled: widget.enabled && widget.writable,
                      maxLines: 1,
                      inputFormatters: [limit],
                      decoration: InputDecoration(
                        labelText: l.codeEditorReplace,
                        border: const OutlineInputBorder(),
                      ),
                    ),
                  ),
                ),
                editorTag(
                  'code-replace-all',
                  TextButton(
                    onPressed: widget.enabled && widget.writable
                        ? () => widget.action('REPLACE_ALL')
                        : null,
                    child: Text(l.codeEditorReplaceAll),
                  ),
                ),
                editorTag(
                  'code-replacement-close',
                  IconButton(
                    tooltip: l.codeEditorClose,
                    onPressed: widget.enabled
                        ? () => _change(_draft.copyWith(replaceVisible: false))
                        : null,
                    icon: const Icon(Icons.close),
                  ),
                ),
              ],
            ),
        ],
      ),
    );
  }
}
