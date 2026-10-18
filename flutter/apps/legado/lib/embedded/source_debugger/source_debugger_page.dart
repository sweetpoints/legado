import 'dart:async';

import 'package:flutter/gestures.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import '../../l10n/generated/app_localizations.dart';
import 'source_debugger_bridge.dart';
import 'source_debugger_state.dart';

Widget _tag(String id, Widget child) => Semantics(
  identifier: id,
  child: KeyedSubtree(key: ValueKey(id), child: child),
);

class SourceDebuggerPage extends ConsumerStatefulWidget {
  const SourceDebuggerPage({super.key});
  @override
  ConsumerState<SourceDebuggerPage> createState() => _SourceDebuggerPageState();
}

class _SourceDebuggerPageState extends ConsumerState<SourceDebuggerPage>
    with RestorationMixin {
  final _query = TextEditingController();
  final _focus = FocusNode();
  final _categories = RestorableBool(false);
  int? _generation;
  TextEditingValue? _lastSubmitted;
  bool _suppressBlur = false;
  bool _applying = false;
  bool _closeSent = false;
  bool _initialFocusApplied = false;
  @override
  String get restorationId => 'source_debugger';
  @override
  void restoreState(RestorationBucket? oldBucket, bool initialRestore) {
    registerForRestoration(_categories, 'categories');
  }

  @override
  void initState() {
    super.initState();
    _query.addListener(_queryChanged);
    _focus.addListener(_focusChanged);
  }

  SourceDebuggerSnapshot? get _state =>
      ref.read(sourceDebuggerStateProvider).asData?.value;
  void _queryChanged() {
    final state = _state;
    if (_applying ||
        state == null ||
        !state.enabled ||
        !_query.selection.isValid) {
      return;
    }
    final pending = _lastSubmitted;
    // Composing range is Flutter/IME state, not a native query edit. Creating
    // pending work for an unchanged wire value cannot receive a StateFlow echo.
    if (_query.text == (pending?.text ?? state.query) &&
        _query.selection.baseOffset ==
            (pending?.selection.baseOffset ?? state.queryStart) &&
        _query.selection.extentOffset ==
            (pending?.selection.extentOffset ?? state.queryEnd)) {
      return;
    }
    _lastSubmitted = _query.value;
    unawaited(
      _send('query', {
        'text': _query.text,
        'start': _query.selection.baseOffset,
        'end': _query.selection.extentOffset,
      }),
    );
  }

  void _focusChanged() {
    if (_focus.hasFocus) {
      _suppressBlur = false;
    } else if (_suppressBlur) {
      _suppressBlur = false;
      return;
    }
    if (!_applying && _state?.enabled == true) {
      unawaited(_send('help', {'value': _focus.hasFocus}));
    }
  }

  Future<void> _send(String op, [Map<String, Object?> args = const {}]) async {
    final state = _state;
    if (state == null || state.closed) return;
    try {
      await ref
          .read(sourceDebuggerBridgeProvider)
          .command(op, state.generation, args);
    } catch (_) {
      if (mounted) {
        final authority = _state;
        if (op == 'query' &&
            authority?.generation == state.generation &&
            _lastSubmitted?.text == args['text'] &&
            _lastSubmitted?.selection.baseOffset == args['start'] &&
            _lastSubmitted?.selection.extentOffset == args['end']) {
          // A rejected latest edit is visibly rolled back. The bridge still
          // blocks a queued run until a new query command succeeds.
          _lastSubmitted = null;
          _sync(authority!);
        }
        if (op == 'close') _closeSent = false;
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(AppLocalizations.of(context).debuggerCommandError),
          ),
        );
      }
    }
  }

  void _sync(SourceDebuggerSnapshot state) {
    if (_generation != state.generation) {
      _generation = state.generation;
      _lastSubmitted = null;
      _closeSent = false;
      _initialFocusApplied = false;
    }
    if (!_initialFocusApplied && state.loaded && !state.closed) {
      _initialFocusApplied = true;
      if (state.help) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted &&
              _generation == state.generation &&
              _state?.help == true) {
            _focus.requestFocus();
          }
        });
      }
    }
    // Old native echoes must not replace text still being typed through the queue.
    if (_lastSubmitted == null ||
        (state.query == _lastSubmitted!.text &&
            state.queryStart == _lastSubmitted!.selection.baseOffset &&
            state.queryEnd == _lastSubmitted!.selection.extentOffset)) {
      // The matching native echo acknowledges the newest queued edit. Native
      // sort/prefix operations can then replace query and selection normally.
      _lastSubmitted = null;
      final next = TextEditingValue(
        text: state.query,
        selection: TextSelection(
          baseOffset: state.queryStart,
          extentOffset: state.queryEnd,
        ),
      );
      if (_query.text != next.text || _query.selection != next.selection) {
        _applying = true;
        _query.value = next;
        _applying = false;
      }
    }
  }

  void _dismissKeyboard() {
    // A native run sets help=false itself; avoid queuing a focus-help command
    // after run and accidentally showing the help panel over fresh output.
    _suppressBlur = true;
    _applying = true;
    _focus.unfocus();
    _applying = false;
  }

  void _run([String? example]) {
    if (example != null) {
      _query.value = TextEditingValue(
        text: example,
        selection: TextSelection.collapsed(offset: example.length),
      );
    }
    _dismissKeyboard();
    unawaited(_send('run'));
  }

  void _prefix(String value) {
    if (_query.text.trim().isEmpty || _query.text.length <= 2) {
      // Match the native helper: insert a short prefix without starting a run
      // or dismissing the query keyboard. Its authoritative echo updates text.
      _lastSubmitted = null;
      unawaited(_send('prefix', {'value': value}));
      _focus.requestFocus();
    } else {
      _action('prefix', {'value': value});
    }
  }

  void _action(String op, [Map<String, Object?> args = const {}]) {
    _dismissKeyboard();
    unawaited(_send(op, args));
  }

  void _close() {
    if (_closeSent) return;
    _closeSent = true;
    _action('close');
  }

  @override
  void dispose() {
    _query.dispose();
    _focus.dispose();
    _categories.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    ref.listen(sourceDebuggerStateProvider, (_, next) {
      next.whenData(_sync);
    });
    final async = ref.watch(sourceDebuggerStateProvider);
    final state = async.asData?.value;
    if (state != null && _generation != state.generation) _sync(state);
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) {
          if (_categories.value) {
            setState(() => _categories.value = false);
          } else {
            _close();
          }
        }
      },
      child: Scaffold(
        appBar: AppBar(
          leading: _tag(
            'source-debug-close',
            IconButton(
              tooltip: l.debuggerClose,
              onPressed: _close,
              icon: const Icon(Icons.arrow_back),
            ),
          ),
          title: Text(l.debuggerTitle),
          actions: [
            if (state?.isBook == true)
              _tag(
                'source-debug-scan',
                IconButton(
                  tooltip: l.debuggerScan,
                  onPressed: state!.enabled ? () => _action('scan') : null,
                  icon: const Icon(Icons.qr_code_scanner),
                ),
              ),
            if (state != null) _menu(state, l),
          ],
        ),
        body: SafeArea(
          top: false,
          child: state == null
              ? Center(
                  child: async.hasError
                      ? Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text(l.debuggerConnectionError),
                            TextButton(
                              onPressed: () =>
                                  ref.invalidate(sourceDebuggerStateProvider),
                              child: Text(l.debuggerRetry),
                            ),
                          ],
                        )
                      : Semantics(
                          label: l.debuggerLoading,
                          child: const CircularProgressIndicator(),
                        ),
                )
              : Column(
                  children: [
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 12),
                      child: Row(
                        children: [
                          Expanded(
                            child: _tag(
                              'source-debug-query',
                              TextField(
                                controller: _query,
                                focusNode: _focus,
                                enabled: state.enabled,
                                maxLines: 1,
                                textInputAction: TextInputAction.search,
                                decoration: InputDecoration(
                                  labelText: l.debuggerSearch,
                                  counterText: '',
                                ),
                                onSubmitted: (_) => _run(),
                              ),
                            ),
                          ),
                          _tag(
                            'source-debug-run',
                            IconButton(
                              tooltip: l.debuggerSearch,
                              onPressed: state.enabled ? () => _run() : null,
                              icon: const Icon(Icons.search),
                            ),
                          ),
                        ],
                      ),
                    ),
                    if (state.loading || state.running && !state.help)
                      const LinearProgressIndicator(),
                    if (state.error != null || state.issue != null)
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 12),
                        child: Row(
                          children: [
                            Expanded(
                              child: Text(
                                state.error ??
                                    (state.issue == 'busy'
                                        ? l.debuggerBusy
                                        : l.debuggerInterrupted),
                                style: TextStyle(
                                  color: Theme.of(context).colorScheme.error,
                                ),
                              ),
                            ),
                            if (state.canRetry)
                              _tag(
                                'source-debug-retry',
                                TextButton(
                                  onPressed: state.loading
                                      ? null
                                      : () => _action('retry'),
                                  child: Text(l.debuggerRetry),
                                ),
                              ),
                          ],
                        ),
                      ),
                    Expanded(
                      child: state.help
                          ? _help(state, l)
                          : _tag(
                              'source-debug-output',
                              _DebuggerOutput(
                                text: state.output,
                                onUrl: (url) => _send('openUrl', {'url': url}),
                              ),
                            ),
                    ),
                  ],
                ),
        ),
        bottomSheet: state != null && _categories.value
            ? _sorts(state, l)
            : null,
      ),
    );
  }

  Widget _menu(SourceDebuggerSnapshot state, AppLocalizations l) {
    final labels = {
      'search': l.debuggerSearchHtml,
      'book': l.debuggerBookHtml,
      'toc': l.debuggerTocHtml,
      'content': l.debuggerContentHtml,
      'list': l.debuggerListHtml,
    };
    return _tag(
      'source-debug-menu',
      PopupMenuButton<String>(
        tooltip: l.debuggerMenu,
        enabled: state.enabled,
        onSelected: (value) {
          if (state.stages.contains(value)) {
            _action('html', {'stage': value});
          } else {
            _action(value);
          }
        },
        itemBuilder: (_) => [
          for (final stage in state.stages)
            PopupMenuItem(
              value: stage,
              child: _tag('source-debug-html-$stage', Text(labels[stage]!)),
            ),
          if (state.isBook) ...[
            PopupMenuItem(
              value: 'refreshExplore',
              child: _tag('source-debug-refresh', Text(l.debuggerRefresh)),
            ),
            PopupMenuItem(
              value: 'instructions',
              child: _tag(
                'source-debug-instructions',
                Text(l.debuggerInstructions),
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _help(SourceDebuggerSnapshot state, AppLocalizations l) {
    final sort = state.sorts.isEmpty ? null : state.sorts[state.selectedSort];
    Widget example(String id, String text, VoidCallback action) => _tag(
      id,
      TextButton(onPressed: state.enabled ? action : null, child: Text(text)),
    );
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text(l.debuggerSearchHelp),
        Wrap(
          children: [
            example(
              'source-debug-example-my',
              state.isBook ? state.keyword : '我的',
              () => _run(state.isBook ? state.keyword : '我的'),
            ),
            example('source-debug-example-system', '系统', () => _run('系统')),
          ],
        ),
        Text(
          state.isBook ? l.debuggerBookCategoryHelp : l.debuggerRssCategoryHelp,
        ),
        Row(
          children: [
            Expanded(
              child: _tag(
                'source-debug-category',
                InkWell(
                  onTap: !state.enabled || sort?.enabled == false
                      ? null
                      : () {
                          if (sort == null) {
                            _run('系统::http://xxx');
                          } else {
                            _action('sort', {'index': state.selectedSort});
                          }
                        },
                  onLongPress: state.enabled && state.sorts.isNotEmpty
                      ? () => setState(() => _categories.value = true)
                      : null,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 16),
                    child: Text(sort?.query ?? '系统::http://xxx'),
                  ),
                ),
              ),
            ),
            _tag(
              'source-debug-sorts',
              IconButton(
                tooltip: l.debuggerCategories,
                onPressed: state.enabled
                    ? () => setState(() => _categories.value = true)
                    : null,
                icon: const Icon(Icons.arrow_drop_down),
              ),
            ),
          ],
        ),
        if (state.isBook) ...[
          Text(l.debuggerDetailsHelp),
          example(
            'source-debug-detail',
            'https://m.qidian.com/book/1015609210',
            () => _action('detail'),
          ),
          Text(l.debuggerTocHelp),
          example(
            'source-debug-prefix-plus',
            '++https://www.zhaishuyuan.com/read/30394',
            () => _prefix('++'),
          ),
          Text(l.debuggerContentHelp),
          example(
            'source-debug-prefix-minus',
            '--https://www.zhaishuyuan.com/chapter/30394/20940996',
            () => _prefix('--'),
          ),
        ] else ...[
          Text(l.debuggerRssContentHelp),
          example(
            'source-debug-detail',
            'https://m.qidian.com/book/1015609210',
            () {
              if (_query.text.trim().isNotEmpty) _run();
            },
          ),
        ],
      ],
    );
  }

  Widget _sorts(SourceDebuggerSnapshot state, AppLocalizations l) => Material(
    elevation: 8,
    child: SafeArea(
      top: false,
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxHeight: MediaQuery.sizeOf(context).height * .5,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              title: Text(l.debuggerCategories),
              trailing: IconButton(
                tooltip: l.debuggerClose,
                icon: const Icon(Icons.close),
                onPressed: () => setState(() => _categories.value = false),
              ),
            ),
            if (state.sorts.isEmpty)
              Padding(
                padding: const EdgeInsets.all(16),
                child: Text(l.debuggerNoCategories),
              ),
            Flexible(
              child: ListView(
                shrinkWrap: true,
                children: [
                  for (var i = 0; i < state.sorts.length; i++)
                    _tag(
                      'source-debug-sort-$i',
                      ListTile(
                        title: Text(state.sorts[i].name),
                        selected: i == state.selectedSort,
                        enabled: state.enabled && state.sorts[i].enabled,
                        onTap: () {
                          setState(() => _categories.value = false);
                          _action('sort', {'index': i});
                        },
                      ),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    ),
  );
}

/// URL recognizers are disposed on every log update. Selection remains native
/// Flutter text selection, including copy, while normal taps open web links.
class _DebuggerOutput extends StatefulWidget {
  const _DebuggerOutput({required this.text, required this.onUrl});
  final Future<void> Function(String) onUrl;
  final String text;
  @override
  State<_DebuggerOutput> createState() => _DebuggerOutputState();
}

class _DebuggerOutputState extends State<_DebuggerOutput> {
  final _recognizers = <TapGestureRecognizer>[];
  @override
  void dispose() {
    for (final recognizer in _recognizers) {
      recognizer.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    for (final recognizer in _recognizers) {
      recognizer.dispose();
    }
    _recognizers.clear();
    final spans = <InlineSpan>[];
    final pattern = RegExp(
      r'(?:(?:https?://|www\.)[^\s<>]+|[a-zA-Z0-9](?:[a-zA-Z0-9.-]*\.)[a-zA-Z]{2,}(?:/[^\s<>]*)?)',
    );
    var cursor = 0;
    for (final match in pattern.allMatches(widget.text)) {
      if (match.start > 0 && widget.text[match.start - 1] == '@') continue;
      spans.add(TextSpan(text: widget.text.substring(cursor, match.start)));
      final raw = match.group(0)!;
      final url = raw.startsWith('http://') || raw.startsWith('https://')
          ? raw
          : 'http://$raw';
      final recognizer = TapGestureRecognizer()
        ..onTap = () async {
          await widget.onUrl(url);
        };
      _recognizers.add(recognizer);
      spans.add(
        TextSpan(
          text: raw,
          recognizer: recognizer,
          style: TextStyle(
            color: Theme.of(context).colorScheme.primary,
            decoration: TextDecoration.underline,
          ),
        ),
      );
      cursor = match.end;
    }
    spans.add(TextSpan(text: widget.text.substring(cursor)));
    return SingleChildScrollView(
      padding: const EdgeInsets.all(12),
      child: SelectableText.rich(
        TextSpan(children: spans),
        style: const TextStyle(fontFamily: 'monospace'),
      ),
    );
  }
}
