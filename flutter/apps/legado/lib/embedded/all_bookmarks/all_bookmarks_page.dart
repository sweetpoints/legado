import 'dart:async';

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../../l10n/generated/app_localizations.dart';
import 'all_bookmarks_bridge.dart';

class AllBookmarksPage extends StatefulWidget {
  const AllBookmarksPage({super.key, this.bridge});
  final AllBookmarksBridge? bridge;
  @override
  State<AllBookmarksPage> createState() => _AllBookmarksState();
}

class _Entry {
  _Entry(this.row, this.header, this.extent);
  final AllBookmarksRow row;
  final bool header;
  final double extent;
}

class _BookmarksHeader extends SliverPersistentHeaderDelegate {
  _BookmarksHeader(this.height, this.child);
  final double height;
  final Widget child;
  @override
  double get minExtent => height;
  @override
  double get maxExtent => height;
  @override
  Widget build(
    BuildContext context,
    double shrinkOffset,
    bool overlapsContent,
  ) => child;
  @override
  bool shouldRebuild(covariant _BookmarksHeader oldDelegate) => true;
}

class _AllBookmarksState extends State<AllBookmarksPage> {
  late final bridge = widget.bridge ?? AllBookmarksBridge();
  final scroll = ScrollController();
  StreamSubscription<AllBookmarksSnapshot>? subscription;
  AllBookmarksSnapshot? state;
  bool restored = false, failed = false;
  (int, int)? pendingScroll;
  Future<void>? scrollFlight;
  List<_Entry> entries = [];
  @override
  void initState() {
    super.initState();
    scroll.addListener(recordScroll);
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

  void accept(AllBookmarksSnapshot next) {
    if (!mounted) return;
    final nextEntries = <_Entry>[];
    AllBookmarksRow? previous;
    for (final row in next.rows) {
      if (previous == null ||
          previous.bookName != row.bookName ||
          previous.bookAuthor != row.bookAuthor) {
        nextEntries.add(
          _Entry(
            row,
            true,
            (MediaQuery.textScalerOf(context).scale(16) * 1.5 + 12).clamp(
              36,
              double.infinity,
            ),
          ),
        );
      }
      nextEntries.add(
        _Entry(
          row,
          false,
          (MediaQuery.textScalerOf(context).scale(14) * 1.5 +
                  MediaQuery.textScalerOf(context).scale(12) *
                      1.5 *
                      ((row.original.isEmpty ? 0 : 1) +
                          (row.content.isEmpty ? 0 : 1)) +
                  40)
              .clamp(104, double.infinity),
        ),
      );
      previous = row;
    }
    setState(() {
      state = next;
      entries = nextEntries;
    });
    if (next.loaded && !restored) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted || restored || !scroll.hasClients) return;
        final index = next.index.clamp(
          0,
          (entries.length - 1).clamp(0, 0x7fffffff),
        );
        final prefix = entries
            .take(index)
            .fold(0.0, (total, e) => total + e.extent);
        scroll.jumpTo(
          (prefix + next.offset / View.of(context).devicePixelRatio).clamp(
            0,
            scroll.position.maxScrollExtent,
          ),
        );
        restored = true;
      });
    }
  }

  Future<void> command(
    String op, [
    Map<String, Object?> args = const {},
  ]) async {
    try {
      if (op == 'back' && bridge.latest == null) {
        await const MethodChannel('legado/embedded_ui')
            .invokeMethod<void>('navigation.close');
      } else {
        await bridge.command(op, args);
      }
    } catch (_) {
      if (mounted) setState(() => failed = true);
    }
  }

  void recordScroll() {
    if (!mounted || !restored || !scroll.hasClients || entries.isEmpty) return;
    var remaining = scroll.offset;
    var index = 0;
    while (index < entries.length - 1 && remaining >= entries[index].extent) {
      remaining -= entries[index++].extent;
    }
    pendingScroll = (
      index,
      (remaining * View.of(context).devicePixelRatio).round().clamp(
        0,
        0x7fffffff,
      ),
    );
    if (scrollFlight != null) return;
    final flight = () async {
      while (mounted && pendingScroll != null) {
        final next = pendingScroll!;
        pendingScroll = null;
        await command('scroll', {'index': next.$1, 'offset': next.$2});
      }
    }();
    scrollFlight = flight;
    unawaited(flight.whenComplete(() => scrollFlight = null));
  }

  Widget tag(String id, Widget child) =>
      Semantics(identifier: 'all-bookmarks-$id', child: child);
  Widget rowWidget(_Entry entry, bool enabled) {
    final row = entry.row;
    return SizedBox(
      key: ValueKey(row.id),
      height: entry.extent,
      child: tag(
        'row-${row.id}',
        Card(
          child: InkWell(
            onTap: enabled
                ? () =>
                      unawaited(command('open', {'id': row.id, 'edit': false}))
                : null,
            onLongPress: enabled
                ? () => unawaited(command('open', {'id': row.id, 'edit': true}))
                : null,
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  tag(
                    'chapter-${row.id}',
                    Text(
                      row.chapter,
                      style: const TextStyle(fontSize: 14),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  if (row.original.isNotEmpty)
                    tag(
                      'original-${row.id}',
                      Text(
                        row.original,
                        style: const TextStyle(fontSize: 12),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  if (row.content.isNotEmpty)
                    tag(
                      'content-${row.id}',
                      Text(
                        row.content,
                        style: const TextStyle(fontSize: 12),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  List<Widget> groupedSlivers(bool enabled) {
    final slivers = <Widget>[];
    var index = 0;
    while (index < entries.length) {
      final header = entries[index++];
      final rows = <_Entry>[];
      while (index < entries.length && !entries[index].header) {
        rows.add(entries[index++]);
      }
      final headerWidget = tag(
        'header-${header.row.id}',
        ColoredBox(
          color: Theme.of(context).colorScheme.surface,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text(
                '${header.row.bookName}(${header.row.bookAuthor})',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 16,
                  color: Theme.of(context).colorScheme.primary,
                ),
              ),
            ),
          ),
        ),
      );
      slivers.add(
        SliverMainAxisGroup(
          slivers: [
            SliverPersistentHeader(
              pinned: true,
              delegate: _BookmarksHeader(header.extent, headerWidget),
            ),
            SliverList(
              delegate: SliverChildBuilderDelegate(
                (context, rowIndex) => rowWidget(rows[rowIndex], enabled),
                childCount: rows.length,
                findChildIndexCallback: (key) {
                  if (key is! ValueKey<String>) return null;
                  final result = rows.indexWhere((e) => e.row.id == key.value);
                  return result < 0 ? null : result;
                },
              ),
            ),
          ],
        ),
      );
    }
    return slivers;
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final s = state;
    final enabled = s?.loaded == true && !s!.exporting && !s.pending;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) unawaited(command('back'));
      },
      child: Scaffold(
        appBar: AppBar(
          title: Text(l.allBookmarksTitle),
          leading: tag(
            'back',
            IconButton(
              icon: const Icon(Icons.arrow_back),
              onPressed: () => unawaited(command('back')),
            ),
          ),
          actions: [
            tag(
              'menu',
              PopupMenuButton<bool>(
                enabled: enabled,
                icon: const Icon(Icons.more_vert),
                onSelected: (markdown) =>
                    unawaited(command('export', {'markdown': markdown})),
                itemBuilder: (_) => [
                  PopupMenuItem(
                    value: false,
                    child: tag('export-json', Text(l.allBookmarksExportJson)),
                  ),
                  PopupMenuItem(
                    value: true,
                    child: tag('export-md', Text(l.allBookmarksExportMarkdown)),
                  ),
                ],
              ),
            ),
          ],
        ),
        body: SafeArea(
          child: Column(
            children: [
              if (s == null || !s.loaded || s.exporting)
                const LinearProgressIndicator(),
              if (failed || s?.error != null) Text(l.allBookmarksError),
              if (s?.loaded != true)
                tag(
                  'retry',
                  TextButton(
                    onPressed: () => unawaited(command('retry')),
                    child: Text(l.allBookmarksRetry),
                  ),
                ),
              Expanded(
                child: tag(
                  'list',
                  CustomScrollView(
                    controller: scroll,
                    slivers: groupedSlivers(enabled),
                  ),
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
    scroll.removeListener(recordScroll);
    scroll.dispose();
    unawaited(subscription?.cancel());
    unawaited(bridge.close());
    super.dispose();
  }
}
