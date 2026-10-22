import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter/semantics.dart';
import 'package:material_ui/material_ui.dart';

import '../../l10n/generated/app_localizations.dart';
import 'rss_favorites_bridge.dart';

Widget _tag(String id, Widget child) => Semantics(
  identifier: id,
  child: KeyedSubtree(key: ValueKey(id), child: child),
);

class RssFavoritesPage extends StatefulWidget {
  const RssFavoritesPage({super.key, this.bridge});
  final RssFavoritesBridge? bridge;
  @override
  State<RssFavoritesPage> createState() => _FavoritesPageState();
}

class _FavoritesPageState extends State<RssFavoritesPage> {
  late final bridge = widget.bridge ?? RssFavoritesBridge();
  StreamSubscription<FavoritesSnapshot>? subscription;
  FavoritesSnapshot? snapshot;
  bool failed = false;
  @override
  void initState() {
    super.initState();
    subscription = bridge.snapshots.stream.listen(
      (value) {
        if (mounted) {
          setState(() {
            snapshot = value;
            failed = false;
          });
        }
      },
      onError: (Object _, StackTrace _) {
        if (mounted) setState(() => failed = true);
      },
    );
    unawaited(
      bridge.start().catchError((Object _) {
        if (mounted) setState(() => failed = true);
      }),
    );
  }

  void send(String op, [Map<String, Object?> args = const {}]) {
    unawaited(
      bridge.command(op, args).catchError((Object _) {
        if (mounted) setState(() => failed = true);
      }),
    );
  }

  @override
  void dispose() {
    unawaited(subscription?.cancel());
    if (widget.bridge == null) unawaited(bridge.close());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final s = snapshot;
    if (s == null) {
      return Scaffold(
        body: Center(
          child: failed
              ? TextButton(
                  onPressed: () => unawaited(bridge.start()),
                  child: Text(l.retry),
                )
              : const CircularProgressIndicator(),
        ),
      );
    }
    final confirmation = s.confirmation;
    return PopScope(
      canPop: confirmation == null,
      onPopInvokedWithResult: (popped, _) {
        if (!popped && confirmation != null) send('cancel');
      },
      child: Stack(
        children: [
          Scaffold(
            appBar: s.toolbar
                ? AppBar(
                    title: Text(l.rssFavoritesTitle),
                    leading: BackButton(onPressed: () => send('back')),
                    actions: [
                      _tag(
                        'rss-favorites-groups',
                        PopupMenuButton<String>(
                          tooltip: l.rssFavoritesGroups,
                          enabled: s.loaded,
                          onSelected: (group) =>
                              send('group', {'group': group}),
                          itemBuilder: (_) => s.groups
                              .map(
                                (g) => PopupMenuItem(
                                  value: g,
                                  child: _tag(
                                    'rss-favorites-group-menu-$g',
                                    Text(g),
                                  ),
                                ),
                              )
                              .toList(),
                          icon: const Icon(Icons.group_outlined),
                        ),
                      ),
                      _tag(
                        'rss-favorites-menu',
                        PopupMenuButton<String>(
                          tooltip: l.rssFavoritesMenu,
                          onSelected: send,
                          itemBuilder: (_) => [
                            PopupMenuItem(
                              value: 'deleteGroup',
                              enabled:
                                  s.loaded && !s.busy && s.groups.isNotEmpty,
                              child: _tag(
                                'rss-favorites-delete-group',
                                Text(l.rssFavoritesDeleteGroup),
                              ),
                            ),
                            PopupMenuItem(
                              value: 'deleteAll',
                              enabled: s.loaded && !s.busy,
                              child: _tag(
                                'rss-favorites-delete-all',
                                Text(l.rssFavoritesDeleteAll),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  )
                : null,
            body: Column(
              children: [
                if (!s.loaded || s.busy) const LinearProgressIndicator(),
                if (failed || s.error)
                  Row(
                    children: [
                      Expanded(child: Text(l.rssFavoritesError)),
                      TextButton(
                        onPressed: () => send('retry'),
                        child: Text(l.retry),
                      ),
                    ],
                  ),
                if (s.groups.length > 1)
                  SingleChildScrollView(
                    scrollDirection: Axis.horizontal,
                    child: Row(
                      children: s.groups
                          .map(
                            (g) => Padding(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 4,
                              ),
                              child: ChoiceChip(
                                label: Text(g),
                                selected: s.group == g,
                                onSelected: (_) => send('group', {'group': g}),
                              ),
                            ),
                          )
                          .toList(),
                    ),
                  ),
                Expanded(
                  child: s.loaded && s.group != null
                      ? _GroupList(
                          key: ValueKey(
                            '${s.generation}:${s.group}:${MediaQuery.textScalerOf(context).scale(100)}',
                          ),
                          extent: MediaQuery.textScalerOf(context)
                              .scale(100)
                              .clamp(100, double.infinity),
                          snapshot: s,
                          send: send,
                        )
                      : const SizedBox.shrink(),
                ),
              ],
            ),
          ),
          if (confirmation != null)
            Positioned.fill(
              child: BlockSemantics(
                child: Stack(
                  children: [
                    ModalBarrier(
                      color: Theme.of(context).colorScheme.scrim
                          .withValues(alpha: .4),
                      dismissible: !s.busy,
                      onDismiss: () => send('cancel'),
                      semanticsLabel: l.cancel,
                    ),
                    Center(
                      child: AlertDialog(
                        title: Text(l.rssFavoritesConfirmDelete),
                        content: _tag(
                          'rss-favorites-confirm-message',
                          Text(switch (confirmation['kind']) {
                            'Row' =>
                              s.rows
                                  .where((r) => r.id == confirmation['target'])
                                  .map((r) => r.title)
                                  .join(),
                            'Group' => confirmation['target'] as String,
                            _ => l.rssFavoritesDeleteAll,
                          }),
                        ),
                        actions: [
                          TextButton(
                            onPressed: s.busy ? null : () => send('cancel'),
                            child: _tag(
                              'rss-favorites-cancel-delete',
                              Text(l.cancel),
                            ),
                          ),
                          TextButton(
                            onPressed: s.busy ? null : () => send('confirm'),
                            child: _tag(
                              'rss-favorites-confirm-delete',
                              Text(l.delete),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _GroupList extends StatefulWidget {
  const _GroupList({
    super.key,
    required this.snapshot,
    required this.send,
    required this.extent,
  });
  final double extent;
  final FavoritesSnapshot snapshot;
  final void Function(String, [Map<String, Object?>]) send;
  @override
  State<_GroupList> createState() => _GroupListState();
}

class _GroupListState extends State<_GroupList> {
  late final controller = ScrollController(
    initialScrollOffset: widget.snapshot.position(
      widget.snapshot.group!,
      extent: widget.extent,
    ),
  );
  @override
  void initState() {
    super.initState();
    controller.addListener(save);
  }

  void save() => flush();

  void flush() {
    if (controller.hasClients) {
      final offset = controller.offset.clamp(0, double.infinity).round();
      widget.send('scroll', {
        'group': widget.snapshot.group!,
        'index': offset ~/ widget.extent,
        'offset': (offset % widget.extent).round(),
      });
    }
  }

  @override
  void dispose() {
    flush();
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final s = widget.snapshot;
    final rows = s.rows.where((r) => r.group == s.group).toList();
    return GestureDetector(
      onHorizontalDragEnd: (details) {
        final velocity = details.primaryVelocity ?? 0;
        final index = s.groups.indexOf(s.group!);
        final target =
            index +
            (velocity < -100
                ? 1
                : velocity > 100
                ? -1
                : 0);
        if (target >= 0 && target < s.groups.length && target != index) {
          widget.send('group', {'group': s.groups[target]});
        }
      },
      child: _tag(
        'rss-favorites-list-${s.group}',
        ListView.builder(
          controller: controller,
          itemExtent: widget.extent,
          itemCount: rows.length,
          itemBuilder: (_, index) {
            final row = rows[index];
            return Semantics(
              identifier: 'rss-favorite-row-${row.id}',
              customSemanticsActions: {
                CustomSemanticsAction(
                  label: AppLocalizations.of(context).delete,
                ): () {
                  if (!s.busy) widget.send('delete', {'id': row.id});
                },
              },
              child: ListTile(
                key: ValueKey(row.id),
                enabled: !s.busy,
                title: Text(
                  row.title,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
                subtitle: row.date == null
                    ? null
                    : Text(row.date!, maxLines: 1),
                onTap: () => widget.send('read', {'id': row.id}),
                onLongPress: () => widget.send('delete', {'id': row.id}),
                trailing: row.image
                    ? SizedBox(
                        width: 94,
                        height: 68,
                        child: AndroidView(
                          key: ValueKey('image-${row.id}-${row.imageKey}'),
                          viewType: 'legado/rss-favorite-image',
                          creationParams: {'id': row.id},
                          creationParamsCodec: const StandardMessageCodec(),
                        ),
                      )
                    : null,
              ),
            );
          },
        ),
      ),
    );
  }
}
