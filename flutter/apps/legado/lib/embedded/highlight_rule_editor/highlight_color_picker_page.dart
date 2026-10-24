import 'dart:async';

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../../l10n/generated/app_localizations.dart';
import 'highlight_color_picker_bridge.dart';

class HighlightColorPickerPage extends StatefulWidget {
  const HighlightColorPickerPage({super.key, this.bridge});
  final HighlightColorPickerBridge? bridge;
  @override
  State<HighlightColorPickerPage> createState() =>
      _HighlightColorPickerPageState();
}

class _HighlightColorPickerPageState extends State<HighlightColorPickerPage> {
  late final bridge = widget.bridge ?? HighlightColorPickerBridge();
  final hex = TextEditingController();
  StreamSubscription<HighlightColorSnapshot>? subscription;
  HighlightColorSnapshot? state;
  int color = 0, pending = 0;
  bool failed = false;
  @override
  void initState() {
    super.initState();
    subscription = bridge.snapshots.stream.listen(
      accept,
      onError: (Object _) {
        if (mounted) setState(() => failed = true);
      },
    );
    unawaited(() async {
      try {
        await bridge.start();
      } catch (_) {
        if (mounted) setState(() => failed = true);
      }
    }());
  }

  void reflect(HighlightColorSnapshot next) {
    color = next.color;
    if (hex.text != next.hex) {
      hex.value = TextEditingValue(
        text: next.hex,
        selection: TextSelection.collapsed(offset: next.hex.length),
      );
    }
  }

  void accept(HighlightColorSnapshot next) {
    if (!mounted) return;
    setState(() {
      state = next;
      if (pending == 0 && !bridge.hasRejectedInput) reflect(next);
    });
  }

  String format(int value) =>
      '#${(value & (state?.withAlpha == true ? 0xffffffff : 0xffffff)).toRadixString(16).padLeft(state?.withAlpha == true ? 8 : 6, '0').toUpperCase()}';
  void edit(String op, Map<String, Object?> args, void Function() update) {
    setState(() {
      pending++;
      update();
    });
    unawaited(() async {
      try {
        await bridge.command(op, args);
        if (mounted) setState(() => failed = bridge.hasRejectedInput);
      } catch (_) {
        if (mounted) setState(() => failed = true);
      } finally {
        if (mounted) {
          setState(() {
            pending--;
            if (pending == 0 &&
                !failed &&
                !bridge.hasRejectedInput &&
                bridge.latest != null) {
              reflect(bridge.latest!);
            }
          });
        }
      }
    }());
  }

  Future<void> action(String op) async {
    try {
      if (op == 'cancel' && bridge.latest == null) {
        await const MethodChannel('legado/embedded_ui')
            .invokeMethod<void>('navigation.close');
      } else {
        await bridge.command(op);
      }
    } catch (_) {
      if (mounted) setState(() => failed = true);
    }
  }

  bool confirmingCancel = false;
  Future<void> requestCancel() async {
    if (confirmingCancel || state?.finished == true) return;
    if (pending > 0 || bridge.hasRejectedInput) {
      confirmingCancel = true;
      final l10n = AppLocalizations.of(context);
      final discard = await showDialog<bool>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          content: Text(l10n.highlightEditorPendingExit),
          actions: [
            tag(
              'exit-keep',
              TextButton(
                onPressed: () => Navigator.of(dialogContext).pop(false),
                child: Text(l10n.highlightEditorKeep),
              ),
            ),
            tag(
              'exit-discard',
              TextButton(
                onPressed: () => Navigator.of(dialogContext).pop(true),
                child: Text(l10n.highlightEditorDiscard),
              ),
            ),
          ],
        ),
      );
      confirmingCancel = false;
      if (!mounted || discard != true) return;
    }
    await action('cancel');
  }

  Widget tag(String id, Widget child) =>
      Semantics(identifier: 'highlight-rule-color-$id', child: child);
  @override
  Widget build(BuildContext context) {
    final s = state, enabled = s != null && !s.finished;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop) unawaited(requestCancel());
      },
      child: Scaffold(
        backgroundColor: Colors.transparent,
        body: SafeArea(
          child: Center(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Card(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Padding(
                      padding: const EdgeInsets.all(16),
                      child: Text(
                        s?.label('heading') ?? '',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                    ),
                    if (s == null) const LinearProgressIndicator(),
                    Flexible(
                      child: ListView(
                        shrinkWrap: true,
                        padding: const EdgeInsets.all(16),
                        children: [
                          if (failed)
                            Text(
                              AppLocalizations.of(context)
                                  .highlightColorBridgeError,
                              style: TextStyle(
                                color: Theme.of(context).colorScheme.error,
                              ),
                            ),
                          tag(
                            'preview',
                            SizedBox(
                              height: 48,
                              child: ColoredBox(
                                color: Color(color & 0xffffffff),
                              ),
                            ),
                          ),
                          const SizedBox(height: 12),
                          if (s != null)
                            Wrap(
                              children: [
                                for (var i = 0; i < s.presets.length; i++)
                                  Semantics(
                                    identifier:
                                        'highlight-rule-color-preset-$i',
                                    button: true,
                                    enabled: enabled,
                                    label: format(s.presets[i]),
                                    child: SizedBox(
                                      width: 64,
                                      height: 48,
                                      child: InkWell(
                                        onTap: !enabled
                                            ? null
                                            : () => edit(
                                                'preset',
                                                {'index': i},
                                                () {
                                                  color = s.presets[i];
                                                  hex.text = format(color);
                                                },
                                              ),
                                        child: Padding(
                                          padding: const EdgeInsets.all(4),
                                          child: ColoredBox(
                                            color: Color(
                                              s.presets[i] & 0xffffffff,
                                            ),
                                          ),
                                        ),
                                      ),
                                    ),
                                  ),
                              ],
                            ),
                          const SizedBox(height: 12),
                          tag(
                            'hex',
                            TextField(
                              key: const ValueKey('highlight-rule-color-hex'),
                              controller: hex,
                              enabled: enabled,
                              maxLines: 1,
                              decoration: InputDecoration(
                                labelText: s?.withAlpha == true
                                    ? '#AARRGGBB'
                                    : '#RRGGBB',
                                errorText: s?.invalid == true
                                    ? AppLocalizations.of(context)
                                          .highlightColorInvalidHex
                                    : null,
                              ),
                              onChanged: (text) => edit(
                                'hex',
                                {'text': text},
                                () {
                                  final clean = text.startsWith('#')
                                      ? text.substring(1)
                                      : text;
                                  if (clean.length ==
                                          (s?.withAlpha == true ? 8 : 6) &&
                                      RegExp(r'^[0-9a-fA-F]+$')
                                          .hasMatch(clean)) {
                                    final parsed = int.parse(clean, radix: 16);
                                    color = s?.withAlpha == true
                                        ? parsed.toSigned(32)
                                        : (parsed | 0xff000000).toSigned(32);
                                  }
                                },
                              ),
                            ),
                          ),
                          if (s != null)
                            for (final channel in [
                              if (s.withAlpha) ('A', 24),
                              ('R', 16),
                              ('G', 8),
                              ('B', 0),
                            ]) ...[
                              Text(
                                '${channel.$1}: ${(color >> channel.$2) & 255}',
                              ),
                              tag(
                                channel.$1,
                                Slider(
                                  key: ValueKey(
                                    'highlight-rule-color-${channel.$1}',
                                  ),
                                  value: ((color >> channel.$2) & 255)
                                      .toDouble(),
                                  min: 0,
                                  max: 255,
                                  onChanged: !enabled
                                      ? null
                                      : (value) => edit(
                                          'channel',
                                          {
                                            'channel': channel.$1,
                                            'value': value.toInt(),
                                          },
                                          () {
                                            color =
                                                ((color &
                                                            ~(255 <<
                                                                channel.$2)) |
                                                        (value.toInt() <<
                                                            channel.$2))
                                                    .toSigned(32);
                                            hex.text = format(color);
                                          },
                                        ),
                                ),
                              ),
                            ],
                        ],
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 12),
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.end,
                        children: [
                          tag(
                            'cancel',
                            TextButton(
                              onPressed: s?.finished == true
                                  ? null
                                  : () => requestCancel(),
                              child: Text(s?.label('cancel') ?? ''),
                            ),
                          ),
                          tag(
                            'save',
                            TextButton(
                              onPressed:
                                  enabled &&
                                      !s.invalid &&
                                      pending == 0 &&
                                      !failed &&
                                      !bridge.hasRejectedInput
                                  ? () => action('save')
                                  : null,
                              child: Text(s?.label('save') ?? ''),
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
    );
  }

  @override
  void dispose() {
    unawaited(subscription?.cancel());
    unawaited(bridge.close());
    hex.dispose();
    super.dispose();
  }
}
