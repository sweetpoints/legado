import 'dart:async';

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import 'replace_editor_bridge.dart';

class ReplaceEditorPage extends StatefulWidget {
  const ReplaceEditorPage({super.key, this.bridge});
  final ReplaceEditorBridge? bridge;
  @override
  State<ReplaceEditorPage> createState() => _ReplaceEditorPageState();
}

class _ReplaceEditorPageState extends State<ReplaceEditorPage> {
  late final bridge = widget.bridge ?? ReplaceEditorBridge();
  final controllers = {
    for (final key in replaceFields) key: TextEditingController(),
  };
  final focusNodes = {for (final key in replaceFields) key: FocusNode()};
  final pending = <String, TextEditingValue>{};
  final scroll = ScrollController();
  StreamSubscription<ReplaceSnapshot>? subscription;
  ReplaceSnapshot? state;
  bool reflecting = false, failed = false, restoredScroll = false;
  String? focused;
  bool returning = false, exitDialogShowing = false;
  @override
  void initState() {
    super.initState();
    for (final key in replaceFields) {
      controllers[key]!.addListener(() => changed(key));
      focusNodes[key]!.addListener(() {
        if (focusNodes[key]!.hasFocus && state?.flag('editable') == true) {
          setState(() => focused = key);
          unawaited(command('focus', {'key': key}));
        }
      });
    }
    scroll.addListener(() {
      if (restoredScroll && !reflecting && state?.flag('loaded') == true) {
        unawaited(command('scroll', {'offset': scroll.offset.round()}));
      }
    });
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

  void accept(ReplaceSnapshot next) {
    if (!mounted) return;
    if (next.flag('exit') && !exitDialogShowing) {
      exitDialogShowing = true;
      WidgetsBinding.instance.addPostFrameCallback((_) async {
        if (!mounted) return;
        final labels = next.raw['labels'] as Map;
        await showDialog<void>(
          context: context,
          builder: (dialogContext) => AlertDialog(
            title: Text(labels['exit'] as String),
            content: Text(labels['exitMessage'] as String),
            actions: [
              for (final op in ['keep', 'discard'])
                tagged(
                  op,
                  TextButton(
                    onPressed: () async {
                      await command(op);
                      if (dialogContext.mounted) {
                        Navigator.of(dialogContext).pop();
                      }
                    },
                    child: Text(labels[op] as String),
                  ),
                ),
            ],
          ),
        );
        if (mounted && state?.flag('exit') == true) await command('keep');
        exitDialogShowing = false;
      });
    }
    reflecting = true;
    for (final key in replaceFields) {
      if (pending.containsKey(key)) continue;
      final actual = next.fields[key]!;
      final controller = controllers[key]!;
      final selection = TextSelection(
        baseOffset: actual.start,
        extentOffset: actual.end,
      );
      if (controller.text != actual.text || controller.selection != selection) {
        controller.value = TextEditingValue(
          text: actual.text,
          selection: selection,
          composing: controller.text == actual.text
              ? controller.value.composing
              : TextRange.empty,
        );
      }
    }
    reflecting = false;
    setState(() {
      state = next;
      focused = next.focus;
      failed = bridge.hasRejectedField;
    });
    if (next.flag('loaded') && !restoredScroll) {
      restoredScroll = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && scroll.hasClients) {
          reflecting = true;
          scroll.jumpTo(
            (next.raw['scroll'] as int).toDouble().clamp(
              0,
              scroll.position.maxScrollExtent,
            ),
          );
          reflecting = false;
        }
        final key = next.focus;
        if (mounted &&
            next.flag('editable') &&
            key != null &&
            !next.fields[key]!.external) {
          focusNodes[key]!.requestFocus();
        }
      });
    }
    final nowReturning = next.flag('editorReturning') || !next.flag('editable');
    if (returning && !nowReturning && next.focus != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && state?.flag('editable') == true) {
          focusNodes[next.focus]?.requestFocus();
        }
      });
    }
    returning = nowReturning;
  }

  void changed(String key) {
    final value = controllers[key]!.value;
    if (reflecting ||
        state?.flag('editable') != true ||
        !value.selection.isValid) {
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
          accept(bridge.latest!);
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
      if (op == 'close' && bridge.latest == null) {
        await const MethodChannel('legado/embedded_ui')
            .invokeMethod<void>('navigation.close');
      } else {
        await bridge.command(op, {
          ...args,
          if (op == 'close')
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
    for (final c in controllers.values) {
      c.dispose();
    }
    for (final n in focusNodes.values) {
      n.dispose();
    }
    scroll.dispose();
    super.dispose();
  }

  Widget tagged(String key, Widget child) => Semantics(
    identifier: 'replace-editor-$key',
    container: true,
    child: child,
  );
  @override
  Widget build(BuildContext context) {
    final s = state;
    final labels = (s?.raw['labels'] as Map?) ?? const {};
    String label(String key) => labels[key] as String? ?? '';
    final editable = s?.flag('editable') == true;
    final cancelable = s?.flag('busy') != true && s?.flag('editorOpen') != true;
    Widget action(String key, IconData icon, String op) => tagged(
      key,
      IconButton(
        tooltip: label(op),
        onPressed: editable
            ? () => unawaited(
                command(op, op == 'editor' ? {'key': focused} : const {}),
              )
            : null,
        icon: Icon(icon),
      ),
    );
    Widget field(String key) {
      final text = s?.fields[key];
      if (text?.external == true) {
        return tagged(
          key,
          OutlinedButton(
            onPressed: editable
                ? () => unawaited(command('editor', {'key': key}))
                : null,
            child: Text('${text!.label} — ${label('editor')}'),
          ),
        );
      }
      return tagged(
        key,
        TextField(
          controller: controllers[key],
          focusNode: focusNodes[key],
          enabled: editable,
          minLines: key == 'Sample' ? 4 : 1,
          maxLines: key == 'Timeout'
              ? 1
              : key == 'Sample'
              ? 8
              : null,
          keyboardType: key == 'Timeout'
              ? TextInputType.number
              : TextInputType.multiline,
          decoration: InputDecoration(
            labelText: text?.label ?? '',
            border: const OutlineInputBorder(),
          ),
          onEditingComplete: () {},
        ),
      );
    }

    Widget flag(String key) => tagged(
      key,
      CheckboxListTile(
        title: Text(label(key)),
        value: s?.flag(key) ?? false,
        dense: true,
        onChanged: editable
            ? (value) => unawaited(
                command('flags', {
                  for (final k in ['regex', 'title', 'source', 'content'])
                    k: k == key ? value : s!.flag(k),
                }),
              )
            : null,
      ),
    );
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop && cancelable) unawaited(command('close'));
      },
      child: Scaffold(
        resizeToAvoidBottomInset: true,
        appBar: AppBar(
          title: Text(label('heading')),
          leading: tagged(
            'back',
            IconButton(
              tooltip: label('back'),
              onPressed: cancelable ? () => unawaited(command('close')) : null,
              icon: const Icon(Icons.arrow_back),
            ),
          ),
          actions: [
            action('fullscreen', Icons.code, 'editor'),
            action('save', Icons.save, 'save'),
            tagged(
              'menu',
              PopupMenuButton<String>(
                tooltip: label('more'),
                enabled: editable,
                onSelected: (op) => unawaited(command(op)),
                itemBuilder: (_) => [
                  for (final op in ['copy', 'paste', 'help', 'config'])
                    PopupMenuItem(
                      value: op,
                      child: tagged(op, Text(label(op))),
                    ),
                ],
              ),
            ),
          ],
        ),
        body: SafeArea(
          child: Column(
            children: [
              if (s == null || s.flag('busy')) const LinearProgressIndicator(),
              if (failed)
                tagged(
                  'bridge-error',
                  MaterialBanner(
                    content: Text(label('retry')),
                    actions: [
                      TextButton(
                        onPressed: () {
                          for (final key in pending.keys.toList()) {
                            changed(key);
                          }
                          if (pending.isEmpty) unawaited(start());
                        },
                        child: Text(label('retry')),
                      ),
                    ],
                  ),
                ),
              if (s != null && s.flag('editorReturning') && !s.flag('busy'))
                Row(
                  children: [
                    for (final op in ['retryEditor', 'discardEditor'])
                      tagged(
                        op == 'retryEditor' ? 'return-retry' : 'return-discard',
                        TextButton(
                          onPressed: () => unawaited(command(op)),
                          child: Text(
                            label(op == 'retryEditor' ? 'retry' : 'cancel'),
                          ),
                        ),
                      ),
                  ],
                ),
              if (s != null && !s.flag('loaded') && !s.flag('busy'))
                TextButton(
                  onPressed: () => unawaited(command('retry')),
                  child: Text(label('retry')),
                ),
              Expanded(
                child: tagged(
                  'fields',
                  SingleChildScrollView(
                    controller: scroll,
                    padding: const EdgeInsets.all(12),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        for (final key in replaceFields) ...[
                          field(key),
                          const SizedBox(height: 12),
                          if (key == 'Pattern')
                            Row(
                              children: [
                                Expanded(child: flag('regex')),
                                tagged(
                                  'help',
                                  IconButton(
                                    tooltip: label('help'),
                                    onPressed: editable
                                        ? () => unawaited(command('help'))
                                        : null,
                                    icon: const Icon(Icons.help_outline),
                                  ),
                                ),
                              ],
                            ),
                          if (key == 'Replacement') ...[
                            flag('title'),
                            flag('source'),
                            flag('content'),
                          ],
                        ],
                        Text(
                          label('output'),
                          style: Theme.of(context).textTheme.labelLarge,
                        ),
                        tagged(
                          'output',
                          Listener(
                            onPointerDown: (_) {
                              setState(() => focused = null);
                              FocusScope.of(context).unfocus();
                              unawaited(command('clearFocus'));
                            },
                            child: tagged(
                              'output-text',
                              SelectableText(
                                s?.raw['preview'] as String? ?? '',
                              ),
                            ),
                          ),
                        ),
                        if (s?.flag('previewFailed') == true)
                          tagged(
                            'preview-error',
                            Text(
                              label(
                                s?.raw['previewError'] as String? ??
                                    'previewError',
                              ),
                              style: TextStyle(
                                color: Theme.of(context).colorScheme.error,
                              ),
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
              ),
              if (MediaQuery.viewInsetsOf(context).bottom > 0 && s != null)
                tagged(
                  'keyboard',
                  SizedBox(
                    height: 48.0 * (s.raw['rows'] as int).clamp(1, 5),
                    child: GridView.count(
                      scrollDirection: Axis.horizontal,
                      crossAxisCount: (s.raw['rows'] as int).clamp(1, 5),
                      childAspectRatio: .8,
                      children: [
                        tagged(
                          'key-config',
                          IconButton(
                            tooltip: label('config'),
                            onPressed: () => unawaited(command('config')),
                            icon: const Icon(Icons.settings),
                          ),
                        ),
                        for (final op in ['undo', 'redo'])
                          tagged(
                            op,
                            IconButton(
                              tooltip: label(op),
                              onPressed: editable && focused != null
                                  ? () => unawaited(command(op))
                                  : null,
                              icon: Icon(
                                op == 'undo' ? Icons.undo : Icons.redo,
                              ),
                            ),
                          ),
                        for (final key in s.keys)
                          tagged(
                            'key-${key.key}',
                            TextButton(
                              onPressed: editable && focused != null
                                  ? () => unawaited(
                                      command('insert', {'text': key.value}),
                                    )
                                  : null,
                              child: Text(key.key),
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
    );
  }
}
