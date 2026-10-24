import 'dart:async';

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../../l10n/generated/app_localizations.dart';
import 'txt_toc_rule_editor_bridge.dart';

/// Same token families and precedence as native RuleSyntaxPatterns; offsets remain UTF-16.
class TxtTocRuleTextController extends TextEditingController {
  static final patterns = [
    RegExp(r'"[A-Za-z0-9]*?"\:|"|\{|\}|\[|\]'),
    RegExp(r'\\n'),
    RegExp(r':|==|>|<|!=|>=|<=|->|=|%|-|-=|%=|\+|\-|\-=|\+=|\^|\&|\|::|\?|\*'),
    RegExp(
      r'\b(?:var|let|const|function|return|if|else|for|while|do|break|continue|switch|case|default|try|catch|finally|throw|new|delete|typeof|instanceof|in|of|void|this|true|false|null|undefined)\b',
    ),
  ];
  @override
  TextSpan buildTextSpan({
    required BuildContext context,
    TextStyle? style,
    required bool withComposing,
  }) {
    if (text.length > 4096) {
      return super.buildTextSpan(
        context: context,
        style: style,
        withComposing: withComposing,
      );
    }
    final colors = Theme.of(context).colorScheme;
    final palette = [
      colors.primary,
      colors.onSurfaceVariant,
      colors.tertiary,
      colors.secondary,
    ];
    final tokens = List<int>.filled(text.length, -1);
    for (var i = 0; i < patterns.length; i++) {
      for (final match in patterns[i].allMatches(text)) {
        for (var j = match.start; j < match.end; j++) {
          tokens[j] = i;
        }
      }
    }
    final spans = <TextSpan>[];
    var start = 0;
    while (start < text.length) {
      final token = tokens[start];
      final composing =
          withComposing &&
          value.composing.isValid &&
          value.composing.start <= start &&
          start < value.composing.end;
      var end = start + 1;
      while (end < text.length &&
          tokens[end] == token &&
          composing ==
              (withComposing &&
                  value.composing.isValid &&
                  value.composing.start <= end &&
                  end < value.composing.end)) {
        end++;
      }
      spans.add(
        TextSpan(
          text: text.substring(start, end),
          style: TextStyle(
            color: token < 0 ? null : palette[token],
            decoration: composing ? TextDecoration.underline : null,
          ),
        ),
      );
      start = end;
    }
    return TextSpan(style: style, children: spans);
  }
}

class TxtTocRuleEditorPage extends StatefulWidget {
  const TxtTocRuleEditorPage({super.key, this.bridge});
  final TxtTocRuleEditorBridge? bridge;
  @override
  State<TxtTocRuleEditorPage> createState() => _EditorState();
}

class _EditorState extends State<TxtTocRuleEditorPage> {
  late final bridge = widget.bridge ?? TxtTocRuleEditorBridge();
  final controllers = <String, TextEditingController>{
    for (final key in txtTocFields)
      key: key == 'Replacement'
          ? TxtTocRuleTextController()
          : TextEditingController(),
  };
  final focuses = {for (final key in txtTocFields) key: FocusNode()};
  final pending = <String, TextEditingValue>{};
  StreamSubscription<TxtTocEditorSnapshot>? subscription;
  TxtTocEditorSnapshot? state;
  bool reflecting = false, failed = false;
  int? generation;
  bool focusRestored = false;
  @override
  void initState() {
    super.initState();
    for (final key in txtTocFields) {
      controllers[key]!.addListener(() => changed(key));
      focuses[key]!.addListener(() {
        if (focuses[key]!.hasFocus && state?.canEdit == true) {
          focusRestored = true;
          unawaited(command('focus', {'key': key}));
        }
      });
    }
    subscription = bridge.snapshots.stream.listen(
      accept,
      onError: (Object _) {
        if (mounted) setState(() => failed = true);
      },
    );
    unawaited(start());
  }

  Future<void> start() async {
    try {
      await bridge.start();
    } catch (_) {
      if (mounted) setState(() => failed = true);
    }
  }

  void accept(TxtTocEditorSnapshot next) {
    if (!mounted) return;
    if (generation != null && generation != next.generation) {
      pending.clear();
      focusRestored = false;
    }
    generation = next.generation;
    if (state?.canEdit == false && next.canEdit && next.focus != null) {
      focusRestored = false;
    }
    reflecting = true;
    for (final key in txtTocFields) {
      if (pending.containsKey(key)) continue;
      final controller = controllers[key]!;
      final selection = TextSelection(
        baseOffset: next.start(key),
        extentOffset: next.end(key),
      );
      if (controller.text != next.text(key) ||
          controller.selection != selection) {
        controller.value = TextEditingValue(
          text: next.text(key),
          selection: selection,
          composing: controller.text == next.text(key)
              ? controller.value.composing
              : TextRange.empty,
        );
      }
    }
    reflecting = false;
    setState(() {
      state = next;
      failed = bridge.hasRejectedField;
    });
    if (next.canEdit && next.focus != null && !focusRestored) {
      focusRestored = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && state?.canEdit == true) {
          focuses[next.focus]!.requestFocus();
        }
      });
    }
  }

  void changed(String key) {
    final value = controllers[key]!.value;
    if (reflecting || state?.canEdit != true || !value.selection.isValid) {
      return;
    }
    pending[key] = value;
    unawaited(() async {
      try {
        await bridge.command('field', {
          'key': key,
          'text': value.text,
          'start': value.selection.baseOffset,
          'end': value.selection.extentOffset,
        });
        if (mounted && pending[key] == value) {
          pending.remove(key);
          setState(() => failed = bridge.hasRejectedField);
        }
      } catch (_) {
        if (mounted) setState(() => failed = true);
      }
    }());
  }

  Future<void> command(
    String op, [
    Map<String, Object?> args = const {},
  ]) async {
    try {
      if (op == 'cancel' && bridge.latest == null) {
        await const MethodChannel('legado/embedded_ui')
            .invokeMethod<void>('navigation.close');
      } else {
        await bridge.command(op, {
          ...args,
          if (op == 'cancel')
            'localDirty': pending.isNotEmpty || bridge.hasRejectedField,
        });
      }
    } catch (_) {
      if (mounted) setState(() => failed = true);
    }
  }

  @override
  void dispose() {
    unawaited(subscription?.cancel());
    unawaited(bridge.close());
    for (final controller in controllers.values) {
      controller.dispose();
    }
    for (final focus in focuses.values) {
      focus.dispose();
    }
    super.dispose();
  }

  Widget tagged(String id, Widget child) => Semantics(
    identifier: 'txt-toc-editor-$id',
    container: true,
    child: child,
  );
  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final s = state;
    final enabled = s?.canEdit == true;
    final labels = {
      'Name': l.txtTocEditorName,
      'Regex': l.txtTocEditorRegex,
      'Replacement': l.txtTocEditorReplacement,
      'Example': l.txtTocEditorExample,
    };
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop && s?.saving != true) {
          unawaited(command(s?.confirmExit == true ? 'keep' : 'cancel'));
        }
      },
      child: Scaffold(
        backgroundColor: Colors.transparent,
        body: SafeArea(
          child: Stack(
            children: [
              Positioned.fill(
                child: tagged(
                  'backdrop',
                  GestureDetector(
                    behavior: HitTestBehavior.opaque,
                    onTap: s?.saving == true
                        ? null
                        : () => unawaited(command('cancel')),
                    child: const ColoredBox(color: Colors.transparent),
                  ),
                ),
              ),
              Center(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: ConstrainedBox(
                    constraints: BoxConstraints(
                      maxWidth: 720,
                      maxHeight: MediaQuery.sizeOf(context).height * .9,
                    ),
                    child: Material(
                      color: Theme.of(context).colorScheme.surface,
                      borderRadius: BorderRadius.circular(16),
                      clipBehavior: Clip.antiAlias,
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Row(
                            children: [
                              tagged(
                                'close',
                                IconButton(
                                  tooltip: l.close,
                                  onPressed: s?.saving == true
                                      ? null
                                      : () => unawaited(command('cancel')),
                                  icon: const Icon(Icons.close),
                                ),
                              ),
                              Expanded(
                                child: Text(
                                  l.txtTocEditorTitle,
                                  style: Theme.of(context).textTheme.titleLarge,
                                ),
                              ),
                              tagged(
                                'fullscreen',
                                IconButton(
                                  tooltip: l.txtTocEditorFullscreen,
                                  onPressed: enabled
                                      ? () => unawaited(command('fullscreen'))
                                      : null,
                                  icon: const Icon(Icons.code),
                                ),
                              ),
                              tagged(
                                'save',
                                IconButton(
                                  tooltip: l.txtTocEditorSave,
                                  onPressed: enabled
                                      ? () => unawaited(command('save'))
                                      : null,
                                  icon: const Icon(Icons.save),
                                ),
                              ),
                              tagged(
                                'menu',
                                PopupMenuButton<String>(
                                  enabled: enabled,
                                  tooltip: l.txtTocEditorMenu,
                                  onSelected: (op) => unawaited(command(op)),
                                  itemBuilder: (_) => [
                                    PopupMenuItem(
                                      value: 'copy',
                                      child: tagged(
                                        'copy',
                                        Text(l.txtTocEditorCopy),
                                      ),
                                    ),
                                    PopupMenuItem(
                                      value: 'paste',
                                      child: tagged(
                                        'paste',
                                        Text(l.txtTocEditorPaste),
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ),
                          if (s == null || s.loading || s.saving)
                            const LinearProgressIndicator(),
                          Flexible(
                            child: SingleChildScrollView(
                              padding: const EdgeInsets.all(16),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.stretch,
                                children: [
                                  if (failed || s?.error != null)
                                    tagged(
                                      'error',
                                      Text(
                                        s?.error ?? l.txtTocEditorBridgeError,
                                        style: TextStyle(
                                          color: Theme.of(context)
                                              .colorScheme
                                              .error,
                                        ),
                                      ),
                                    ),
                                  if (s == null || s.loadFailed)
                                    tagged(
                                      'retry',
                                      TextButton(
                                        onPressed: () => unawaited(
                                          s == null
                                              ? start()
                                              : command('retry'),
                                        ),
                                        child: Text(l.retry),
                                      ),
                                    ),
                                  for (final key in txtTocFields)
                                    Padding(
                                      padding: const EdgeInsets.only(
                                        bottom: 12,
                                      ),
                                      child: tagged(
                                        key,
                                        TextField(
                                          controller: controllers[key],
                                          focusNode: focuses[key],
                                          enabled: enabled,
                                          minLines: key == 'Replacement'
                                              ? 4
                                              : 1,
                                          maxLines: 8,
                                          decoration: InputDecoration(
                                            labelText: labels[key],
                                            border: const OutlineInputBorder(),
                                          ),
                                        ),
                                      ),
                                    ),
                                ],
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
              if (s?.confirmExit == true)
                Positioned.fill(
                  child: ColoredBox(
                    color: Colors.black54,
                    child: Center(
                      child: AlertDialog(
                        title: Text(l.txtTocEditorExit),
                        content: Text(l.txtTocEditorExitMessage),
                        actions: [
                          tagged(
                            'discard',
                            TextButton(
                              onPressed: () => unawaited(command('discard')),
                              child: Text(l.txtTocEditorDiscard),
                            ),
                          ),
                          tagged(
                            'keep',
                            TextButton(
                              onPressed: () => unawaited(command('keep')),
                              child: Text(l.txtTocEditorKeep),
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
    );
  }
}
