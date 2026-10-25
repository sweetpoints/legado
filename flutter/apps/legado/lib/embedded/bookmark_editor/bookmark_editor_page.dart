import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../l10n/generated/app_localizations.dart';
import 'bookmark_editor_bridge.dart';

class BookmarkEditorPage extends StatefulWidget {
  const BookmarkEditorPage({super.key, this.bridge});
  final BookmarkEditorBridge? bridge;
  @override
  State<BookmarkEditorPage> createState() => _EditorState();
}

class _EditorState extends State<BookmarkEditorPage> {
  late final bridge = widget.bridge ?? BookmarkEditorBridge();
  final controllers = <String, TextEditingController>{
    for (final key in bookmarkFields) key: TextEditingController(),
  };
  final focuses = {for (final key in bookmarkFields) key: FocusNode()};
  final pending = <String, TextEditingValue>{};
  StreamSubscription<BookmarkEditorSnapshot>? subscription;
  BookmarkEditorSnapshot? state;
  bool reflecting = false, failed = false;
  int? generation;
  bool focusRestored = false;
  @override
  void initState() {
    super.initState();
    for (final key in bookmarkFields) {
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

  void accept(BookmarkEditorSnapshot next) {
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
    for (final key in bookmarkFields) {
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
        await bridge.command(op, args);
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
    identifier: 'bookmark-editor-$id',
    container: true,
    child: child,
  );
  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context), s = state;
    final enabled = s?.canEdit == true;
    final mutation =
        enabled && pending.isEmpty && !bridge.hasRejectedField && !failed;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) unawaited(command('cancel'));
      },
      child: Scaffold(
        backgroundColor: Colors.transparent,
        body: SafeArea(
          child: Stack(
            children: [
              Positioned.fill(
                child: tagged(
                  'outside',
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
                  child: GestureDetector(
                    onTap: () {},
                    child: Card(
                      child: ConstrainedBox(
                        constraints: BoxConstraints(
                          maxHeight: MediaQuery.sizeOf(context).height * .85,
                        ),
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Padding(
                              padding: const EdgeInsets.all(16),
                              child: tagged(
                                'heading',
                                Text(
                                  l.bookmarkEditorTitle,
                                  style: Theme.of(context).textTheme.titleLarge,
                                ),
                              ),
                            ),
                            Padding(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 16,
                              ),
                              child: tagged(
                                'chapter',
                                Text(s?.raw['chapter'] as String? ?? ''),
                              ),
                            ),
                            if (s == null || s.loading || s.saving)
                              tagged(
                                'working',
                                const LinearProgressIndicator(),
                              ),
                            Flexible(
                              child: SingleChildScrollView(
                                padding: const EdgeInsets.all(16),
                                child: Column(
                                  children: [
                                    if (failed || s?.error != null)
                                      tagged(
                                        'error',
                                        Text(
                                          s?.error ??
                                              l.bookmarkEditorBridgeError,
                                          style: TextStyle(
                                            color: Theme.of(context)
                                                .colorScheme
                                                .error,
                                          ),
                                        ),
                                      ),
                                    if ((s == null && failed) ||
                                        s?.loadFailed == true)
                                      tagged(
                                        s == null ? 'bridge-retry' : 'retry',
                                        TextButton(
                                          onPressed: () => unawaited(
                                            s == null
                                                ? start()
                                                : command('retry'),
                                          ),
                                          child: Text(l.retry),
                                        ),
                                      ),
                                    for (final key in bookmarkFields)
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
                                            maxLines: 4,
                                            textInputAction: key == 'text'
                                                ? TextInputAction.next
                                                : TextInputAction.done,
                                            onSubmitted: (_) {
                                              if (key == 'text') {
                                                focuses['content']!
                                                    .requestFocus();
                                              } else {
                                                focuses[key]!.unfocus();
                                              }
                                            },
                                            decoration: InputDecoration(
                                              labelText: key == 'text'
                                                  ? l.bookmarkEditorText
                                                  : l.bookmarkEditorContent,
                                              border:
                                                  const OutlineInputBorder(),
                                            ),
                                          ),
                                        ),
                                      ),
                                  ],
                                ),
                              ),
                            ),
                            Padding(
                              padding: const EdgeInsets.all(8),
                              child: Row(
                                children: [
                                  if (s?.raw['canDelete'] == true)
                                    tagged(
                                      'delete',
                                      TextButton(
                                        onPressed: mutation
                                            ? () => unawaited(command('delete'))
                                            : null,
                                        child: Text(l.bookmarkEditorDelete),
                                      ),
                                    ),
                                  const Spacer(),
                                  tagged(
                                    'cancel',
                                    TextButton(
                                      onPressed: s?.saving == true
                                          ? null
                                          : () => unawaited(command('cancel')),
                                      child: Text(l.bookmarkEditorCancel),
                                    ),
                                  ),
                                  tagged(
                                    'confirm',
                                    TextButton(
                                      onPressed: mutation
                                          ? () => unawaited(command('confirm'))
                                          : null,
                                      child: Text(l.bookmarkEditorConfirm),
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ],
                        ),
                      ),
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
