import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../../l10n/generated/app_localizations.dart';
import 'diagnostics_bridge.dart';

class AppLogsPage extends StatelessWidget {
  const AppLogsPage({super.key, this.bridge});
  final DiagnosticsBridge? bridge;
  @override
  Widget build(BuildContext context) =>
      DiagnosticsPage(crash: false, bridge: bridge);
}

class DiagnosticsPage extends StatefulWidget {
  const DiagnosticsPage({super.key, required this.crash, this.bridge});
  final bool crash;
  final DiagnosticsBridge? bridge;
  @override
  State<DiagnosticsPage> createState() => _DiagnosticsPageState();
}

class _DiagnosticsPageState extends State<DiagnosticsPage> {
  late final bridge = widget.bridge ?? DiagnosticsBridge(crash: widget.crash);
  final scroll = ScrollController();
  StreamSubscription<DiagnosticsSnapshot>? subscription;
  Timer? save;
  String? lastScrollReceipt;
  DiagnosticsSnapshot? state;
  bool failed = false, pending = false, reflecting = false;
  static const extent = 152.0;
  double? pendingOffset;
  bool offsetScheduled = false;
  String get prefix => widget.crash ? 'crash-logs' : 'app-logs';
  bool get ready =>
      state != null &&
      !state!.flag('busy') &&
      !pending &&
      !failed &&
      !state!.flag('confirmClear');
  @override
  void initState() {
    super.initState();
    scroll.addListener(scrolled);
    subscription = bridge.states.listen(
      accept,
      onError: (Object _) {
        if (mounted) setState(() => failed = true);
      },
    );
    bridge.start().catchError((Object _) {
      if (mounted) setState(() => failed = true);
    });
  }

  void accept(DiagnosticsSnapshot next) {
    if (!mounted) return;
    final old = state;
    final initial =
        old == null ||
        old.binding != next.binding ||
        old.number('page') != next.number('page');
    double? target;
    if (initial) {
      target = next.number('scrollIndex') * extent + next.offset;
    } else if (scroll.hasClients && scroll.offset > 0 && old.rows.isNotEmpty) {
      final currentOffset = pendingOffset ?? scroll.offset;
      final index = (currentOffset / extent).floor().clamp(
        0,
        old.rows.length - 1,
      );
      final anchor = old.rows[index]['id'];
      final updated = next.rows.indexWhere((row) => row['id'] == anchor);
      if (updated >= 0) target = updated * extent + currentOffset % extent;
    }
    setState(() {
      state = next;
      failed = false;
    });
    if (target != null) pendingOffset = target;
    if (pendingOffset != null && !offsetScheduled && !next.flag('busy')) {
      offsetScheduled = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        offsetScheduled = false;
        final latestOffset = pendingOffset;
        pendingOffset = null;
        if (!mounted || !scroll.hasClients || latestOffset == null) return;
        reflecting = true;
        scroll.jumpTo(latestOffset.clamp(0, scroll.position.maxScrollExtent));
        reflecting = false;
        scrolled();
      });
    }
  }

  void scrolled() {
    if (reflecting ||
        state == null ||
        state!.flag('busy') ||
        !scroll.hasClients) {
      return;
    }
    save?.cancel();
    save = Timer(const Duration(milliseconds: 120), () {
      if (!mounted || !scroll.hasClients) return;
      final receipt =
          '${(scroll.offset / extent).floor()}:${scroll.offset % extent}';
      if (receipt == lastScrollReceipt) return;
      lastScrollReceipt = receipt;
      unawaited(
        bridge
            .command('scroll', {
              'index': (scroll.offset / extent).floor(),
              'offset': scroll.offset % extent,
            })
            .catchError((Object _) {
              lastScrollReceipt = null;
              if (mounted) setState(() => failed = true);
            }),
      );
    });
  }

  Future<void> command(
    String op, [
    Map<String, Object?> args = const {},
  ]) async {
    setState(() => pending = true);
    try {
      await bridge.command(op, args);
    } catch (_) {
      if (mounted) setState(() => failed = true);
    } finally {
      if (mounted) setState(() => pending = false);
    }
  }

  Widget tag(String id, Widget child) =>
      Semantics(identifier: id, child: child);
  Widget button(String id, String label, String op, {bool? enabled}) => tag(
    id,
    TextButton(
      onPressed: (enabled ?? ready) ? () => command(op) : null,
      child: Text(label),
    ),
  );
  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final s = state;
    final rows = s?.rows ?? const <Map<String, Object?>>[];
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop) unawaited(command('back'));
      },
      child: Material(
        child: SafeArea(
          child: Column(
            children: [
              Row(
                children: [
                  button(
                    '$prefix-close',
                    l.diagnosticsClose,
                    'back',
                    enabled: s != null && !pending,
                  ),
                  Expanded(
                    child: Text(
                      widget.crash ? l.crashLogsTitle : l.appLogsTitle,
                      style: Theme.of(context).textTheme.titleLarge,
                    ),
                  ),
                  button('$prefix-clear', l.diagnosticsClear, 'clear'),
                  if (!widget.crash)
                    tag(
                      '$prefix-menu',
                      PopupMenuButton<String>(
                        enabled: ready,
                        onSelected: (op) => command(op),
                        itemBuilder: (_) => [
                          PopupMenuItem(
                            value: 'export',
                            child: tag('$prefix-export', Text(l.appLogsExport)),
                          ),
                        ],
                      ),
                    ),
                ],
              ),
              if (s == null || s.flag('busy') || pending)
                tag('$prefix-progress', const LinearProgressIndicator()),
              if (failed || s?.flag('error') == true)
                Row(
                  children: [
                    Expanded(
                      child: tag('$prefix-error', Text(l.diagnosticsError)),
                    ),
                    tag(
                      '$prefix-retry',
                      TextButton(
                        onPressed: pending
                            ? null
                            : () async {
                                try {
                                  await bridge.refresh();
                                  await command('retry');
                                } catch (_) {
                                  if (mounted) setState(() => failed = true);
                                }
                              },
                        child: Text(l.diagnosticsRetry),
                      ),
                    ),
                  ],
                ),
              if (s?.raw['notice'] != null)
                tag(
                  '$prefix-notice',
                  Text(
                    s!.raw['notice'] == 'NoLogs'
                        ? l.appLogsEmpty
                        : l.appLogsShareFailed,
                  ),
                ),
              if (s != null && rows.isEmpty && !s.flag('busy'))
                tag(
                  '$prefix-empty',
                  Text(widget.crash ? l.crashLogsEmpty : l.appLogsEmpty),
                ),
              Expanded(
                child: tag(
                  '$prefix-list',
                  Scrollbar(
                    controller: scroll,
                    interactive: true,
                    child: ListView.builder(
                      controller: scroll,
                      itemExtent: extent,
                      itemCount: rows.length,
                      findChildIndexCallback: (key) => key is ValueKey<String>
                          ? rows
                                .indexWhere((r) => r['id'] == key.value)
                                .takeUnlessNegative()
                          : null,
                      itemBuilder: (context, index) {
                        final row = rows[index];
                        final id = row['id']! as String;
                        return SizedBox(
                          key: ValueKey(id),
                          height: extent,
                          child: tag(
                            '$prefix-row-$id',
                            Column(
                              crossAxisAlignment: CrossAxisAlignment.stretch,
                              children: [
                                if (!widget.crash)
                                  Text(
                                    row['time']! as String,
                                    style: Theme.of(context)
                                        .textTheme
                                        .labelMedium,
                                  ),
                                Expanded(
                                  child: Padding(
                                    padding: const EdgeInsets.symmetric(
                                      horizontal: 12,
                                    ),
                                    child: SelectableText(
                                      row['text']! as String,
                                      maxLines: 4,
                                    ),
                                  ),
                                ),
                                if (row['details'] == true ||
                                    row['truncated'] == true)
                                  tag(
                                    '$prefix-open-$id',
                                    TextButton(
                                      onPressed: ready
                                          ? () => command('open', {'id': id})
                                          : null,
                                      child: Text(l.diagnosticsDetails),
                                    ),
                                  ),
                                if ((row['links'] as List? ?? const [])
                                    .isNotEmpty)
                                  SizedBox(
                                    height: 28,
                                    child: ListView(
                                      scrollDirection: Axis.horizontal,
                                      children: [
                                        for (
                                          var linkIndex = 0;
                                          linkIndex <
                                              (row['links'] as List).length;
                                          linkIndex++
                                        )
                                          TextButton(
                                            onPressed: ready
                                                ? () => command('link', {
                                                    'id': id,
                                                    'index': linkIndex,
                                                  })
                                                : null,
                                            child: Text(
                                              (row['links'] as List)[linkIndex]
                                                  as String,
                                            ),
                                          ),
                                      ],
                                    ),
                                  ),
                                const Divider(height: 1),
                              ],
                            ),
                          ),
                        );
                      },
                    ),
                  ),
                ),
              ),
              if ((s?.number('pages') ?? 1) > 1)
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    button(
                      '$prefix-previous',
                      l.diagnosticsPrevious,
                      'previous',
                      enabled: ready && s!.number('page') > 0,
                    ),
                    Text('${s!.number('page') + 1}/${s.number('pages')}'),
                    button(
                      '$prefix-next',
                      l.diagnosticsNext,
                      'next',
                      enabled:
                          ready && s.number('page') + 1 < s.number('pages'),
                    ),
                  ],
                ),
              if (s?.flag('confirmClear') == true)
                tag(
                  '$prefix-confirmation',
                  Column(
                    children: [
                      Text(l.appLogsClearConfirm),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.end,
                        children: [
                          button(
                            '$prefix-cancel-clear',
                            l.diagnosticsCancel,
                            'cancelClear',
                            enabled: !pending,
                          ),
                          button(
                            '$prefix-confirm-clear',
                            l.diagnosticsClear,
                            'confirmClear',
                            enabled: !pending && s?.flag('busy') == false,
                          ),
                        ],
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

  @override
  void dispose() {
    save?.cancel();
    subscription?.cancel();
    scroll.dispose();
    unawaited(bridge.close());
    super.dispose();
  }
}

extension on int {
  int? takeUnlessNegative() => this < 0 ? null : this;
}
