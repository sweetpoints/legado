import 'dart:async';
import 'dart:math' as math;

import 'package:app_core/app_core.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'reader_state.dart';
import '../../l10n/generated/app_localizations.dart';

/// Continuous text reader. Positions are UTF-16 text offsets, never percentages
/// or viewport pixels, so a saved position survives font and window changes.
class ReaderPage extends ConsumerStatefulWidget {
  const ReaderPage({
    super.key,
    required this.repository,
    required this.bookUrl,
    this.loadChapter,
    this.previewBook,
    this.previewChapters,
    this.initialChapterIndex,
  });
  final LibraryRepository repository;
  final String bookUrl;
  final Future<void> Function(ReaderChapter chapter)? loadChapter;
  final LegacyBookRecord? previewBook;
  final List<ReaderChapter>? previewChapters;
  final int? initialChapterIndex;

  @override
  ConsumerState<ReaderPage> createState() => _ReaderPageState();
}

class _ReaderPageState extends ConsumerState<ReaderPage>
    with WidgetsBindingObserver {
  final _scroll = ScrollController();
  late ReaderIdentity _identity;
  ReaderIdentity _widgetIdentity() => ReaderIdentity(
    repository: widget.repository,
    bookUrl: widget.bookUrl,
    previewBook: widget.previewBook,
    previewChapters: widget.previewChapters == null
        ? null
        : List.unmodifiable(widget.previewChapters!),
    initialChapterIndex: widget.initialChapterIndex,
  );
  ReaderStateController get _controller =>
      ref.read(readerStateProvider(_identity).notifier);
  ReaderViewState get _state => ref.read(readerStateProvider(_identity));
  LegacyBookRecord? get _book => _state.book;
  set _book(LegacyBookRecord? value) =>
      _controller.replace(_state.copyWith(book: value));
  List<ReaderChapter> get _chapters => _state.chapters;
  set _chapters(List<ReaderChapter> value) =>
      _controller.replace(_state.copyWith(chapters: value));
  int get _selected => _state.selected;
  set _selected(int value) =>
      _controller.replace(_state.copyWith(selected: value));
  int get _offset => _state.characterOffset;
  set _offset(int value) =>
      _controller.replace(_state.copyWith(characterOffset: value));
  String? get _text => _state.content;
  set _text(String? value) =>
      _controller.replace(_state.copyWith(content: value));
  String? get _error => _state.error;
  set _error(String? value) =>
      _controller.replace(_state.copyWith(error: value));
  String? get _saveError => _state.saveError;
  set _saveError(String? value) =>
      _controller.replace(_state.copyWith(saveError: value));
  bool get _loading => _state.loading;
  set _loading(bool value) =>
      _controller.replace(_state.copyWith(loading: value));
  double get _fontSize => _state.fontSize;
  set _fontSize(double value) =>
      _controller.replace(_state.copyWith(fontSize: value));
  String get _theme => _state.theme;
  set _theme(String value) =>
      _controller.replace(_state.copyWith(theme: value));
  AppLocalizations get _l10n => AppLocalizations.of(context);
  bool _settingsSaveFailed = false;
  bool _persistToLibrary = false;
  int _request = 0;
  bool _restoring = false;
  Timer? _saveTimer;
  TextPainter? _painter;
  double? _layoutWidth;
  Future<void> _saveQueue = Future.value();

  void _change(VoidCallback action) => action();

  @override
  void initState() {
    super.initState();
    _identity = _widgetIdentity();
    WidgetsBinding.instance.addObserver(this);
    _scroll.addListener(_onScroll);
    unawaited(Future.microtask(_initialize));
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) unawaited(_persist());
  }

  @override
  void didUpdateWidget(ReaderPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    final identity = _widgetIdentity();
    if (identity != _identity) {
      _identity = identity;
      _saveTimer?.cancel();
      _request++;
      unawaited(Future.microtask(_initialize));
    }
  }

  Future<void> _initialize() async {
    if (!mounted) return;
    _book = null;
    _chapters = [];
    _text = null;
    final request = ++_request;
    _change(() {
      _loading = true;
      _error = null;
    });
    try {
      final storedBook = await widget.repository.book(widget.bookUrl);
      final book = storedBook ?? _identity.previewBook;
      if (!mounted || request != _request) return;
      _persistToLibrary = storedBook != null;
      if (book == null) throw StateError(_l10n.readerBookMissing);
      final storedChapters = await widget.repository.chapters(widget.bookUrl);
      final chapters = storedChapters.isNotEmpty
          ? storedChapters
          : (_identity.previewChapters ?? <ReaderChapter>[]);
      if (!mounted || request != _request) return;
      if (chapters.isEmpty) throw StateError(_l10n.readerTocMissing);
      final size = double.tryParse(
        await widget.repository.setting('reader.fontSize') ?? '',
      );
      final theme = await widget.repository.setting('reader.theme');
      if (!mounted || request != _request) return;
      _book = book;
      _chapters = chapters.where((chapter) => !chapter.isVolume).toList();
      if (_chapters.isEmpty) throw StateError(_l10n.readerTocEmpty);
      final saved = _chapters.indexWhere(
        (c) => c.index == (_identity.initialChapterIndex ?? book.chapterIndex),
      );
      _fontSize = (size ?? 20).clamp(14, 36);
      _theme = ['light', 'dark'].contains(theme) ? theme! : 'system';
      await _openChapter(
        saved < 0 ? 0 : saved,
        offset:
            saved < 0 ||
                (_identity.initialChapterIndex != null &&
                    _identity.initialChapterIndex != book.chapterIndex)
            ? 0
            : book.characterOffset,
      );
    } catch (error) {
      if (!mounted || request != _request) return;
      _change(() {
        _loading = false;
        _error = '$error';
      });
    }
  }

  Future<void> _openChapter(int index, {int offset = 0}) async {
    final request = ++_request;
    _saveTimer?.cancel();
    _change(() {
      _selected = index;
      _offset = offset;
      _text = null;
      _error = null;
      _loading = true;
      _layoutWidth = null;
    });
    try {
      final chapter = _chapters[index];
      var content = await widget.repository.content(
        widget.bookUrl,
        chapter.url,
      );
      if (content == null && widget.loadChapter != null) {
        await widget.loadChapter!(chapter);
        content = await widget.repository.content(widget.bookUrl, chapter.url);
      }
      if (!mounted || request != _request) return;
      if (content == null || content.isEmpty) {
        throw StateError(_l10n.readerContentMissing);
      }
      if (!mounted || request != _request) return;
      _change(() {
        _text = content;
        _offset = offset.clamp(0, content!.length);
        _loading = false;
      });
    } catch (error) {
      if (!mounted || request != _request) return;
      _change(() {
        _loading = false;
        _error = '$error';
      });
    }
  }

  Future<void> _navigate(int index) async {
    if (_loading || index < 0 || index >= _chapters.length) return;
    await _persist();
    if (!mounted || (_saveError != null && !_settingsSaveFailed)) return;
    await _openChapter(index);
    if (mounted && _error == null) await _persist();
  }

  void _onScroll() {
    if (_loading || _restoring || _painter == null || !_scroll.hasClients) {
      return;
    }
    final position = _painter!.getPositionForOffset(
      Offset(0, math.max(0, _scroll.offset - 24)),
    );
    _offset = position.offset;
    _saveTimer?.cancel();
    _saveTimer = Timer(
      const Duration(milliseconds: 350),
      () => unawaited(_persist()),
    );
  }

  Future<void> _persist() {
    if (!mounted ||
        !_persistToLibrary ||
        _loading ||
        _error != null ||
        _text == null) {
      return Future.value();
    }
    _saveTimer?.cancel();
    final repository = widget.repository;
    final url = widget.bookUrl;
    final chapter = _chapters[_selected].index;
    final offset = _offset;
    final request = _request;
    _saveQueue = _saveQueue.then((_) async {
      if (!mounted || request != _request) return;
      try {
        await repository.updateProgress(url, chapter, offset);
        if (mounted &&
            request == _request &&
            _saveError != null &&
            !_settingsSaveFailed) {
          _change(() => _saveError = null);
        }
      } catch (error) {
        if (mounted && request == _request) {
          _change(
            () => _saveError = _l10n.readerProgressSaveFailed(error.toString()),
          );
        }
      }
    });
    return _saveQueue;
  }

  void _restorePosition() {
    final request = _request;
    _restoring = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted ||
          request != _request ||
          !_scroll.hasClients ||
          _painter == null) {
        return;
      }
      final y = _painter!
          .getOffsetForCaret(TextPosition(offset: _offset), Rect.zero)
          .dy;
      _scroll.jumpTo(
        (_offset == 0 ? 0.0 : y + 24).clamp(
          0,
          _scroll.position.maxScrollExtent,
        ),
      );
      _restoring = false;
    });
  }

  Future<void> _settings() async {
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (sheetContext) => StatefulBuilder(
        builder: (context, updateSheet) => SafeArea(
          child: SingleChildScrollView(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    _l10n.readerSettings,
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  Row(
                    children: [
                      Text(_l10n.readerFontSize),
                      Expanded(
                        child: Slider(
                          value: _fontSize,
                          min: 14,
                          max: 36,
                          divisions: 22,
                          label: _fontSize.round().toString(),
                          onChanged: (value) {
                            _change(() {
                              _fontSize = value;
                              _layoutWidth = null;
                            });
                            updateSheet(() {});
                          },
                        ),
                      ),
                      Text('${_fontSize.round()}'),
                    ],
                  ),
                  DropdownButton<String>(
                    value: _theme,
                    isExpanded: true,
                    items: [
                      DropdownMenuItem(
                        value: 'system',
                        child: Text(_l10n.readerSystemTheme),
                      ),
                      DropdownMenuItem(
                        value: 'light',
                        child: Text(_l10n.readerLightTheme),
                      ),
                      DropdownMenuItem(
                        value: 'dark',
                        child: Text(_l10n.readerDarkTheme),
                      ),
                    ],
                    onChanged: (value) {
                      if (value == null) return;
                      _theme = value;
                      updateSheet(() {});
                    },
                  ),
                  const SizedBox(height: 16),
                  FilledButton(
                    onPressed: () => Navigator.pop(sheetContext),
                    child: Text(_l10n.readerDone),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
    if (!mounted) return;
    await _persistSettings();
  }

  Future<void> _persistSettings() async {
    try {
      await widget.repository.setSetting('reader.fontSize', '$_fontSize');
      await widget.repository.setSetting('reader.theme', _theme);
      if (mounted) {
        _settingsSaveFailed = false;
        _saveError = null;
      }
    } catch (error) {
      if (mounted) {
        _settingsSaveFailed = true;
        _saveError = _l10n.readerSettingsSaveFailed(error.toString());
      }
    }
  }

  Future<void> _catalogue() async {
    final controller = ScrollController(
      initialScrollOffset: math.max(0, _selected * 56.0 - 112),
    );
    final index = await showModalBottomSheet<int>(
      context: context,
      isScrollControlled: true,
      builder: (context) => SafeArea(
        child: SizedBox(
          height: MediaQuery.sizeOf(context).height * .75,
          child: Column(
            children: [
              Padding(
                padding: const EdgeInsets.all(16),
                child: Text(
                  _l10n.readerCatalogueCount(_chapters.length),
                  style: Theme.of(context).textTheme.titleLarge,
                ),
              ),
              Expanded(
                child: ListView.builder(
                  itemCount: _chapters.length,
                  controller: controller,
                  itemExtent: 56,
                  itemBuilder: (context, index) => ListTile(
                    selected: index == _selected,
                    title: Text(
                      _chapters[index].title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    trailing: index == _selected
                        ? const Icon(Icons.bookmark)
                        : null,
                    onTap: () => Navigator.pop(context, index),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
    controller.dispose();
    if (mounted && index != null) await _navigate(index);
  }

  @override
  void dispose() {
    _saveTimer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    _request++;
    _scroll.dispose();
    _painter?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // Scrolling updates the UTF-16 position without relaying out the whole
    // chapter on each pointer frame. Visual state changes still rebuild here.
    ref.watch(
      readerStateProvider(_identity)
          .select((state) => state.copyWith(characterOffset: 0)),
    );
    final inherited = Theme.of(context);
    final brightness = switch (_theme) {
      'light' => Brightness.light,
      'dark' => Brightness.dark,
      _ => inherited.brightness,
    };
    final theme = ThemeData(
      useMaterial3: true,
      colorScheme: ColorScheme.fromSeed(
        seedColor: inherited.colorScheme.primary,
        brightness: brightness,
      ),
    );
    return Theme(
      data: theme,
      child: Builder(
        builder: (context) => PopScope(
          canPop: false,
          onPopInvokedWithResult: (didPop, result) async {
            if (didPop) return;
            await _persist();
            if (context.mounted) Navigator.of(context).pop(result);
          },
          child: CallbackShortcuts(
            bindings: {
              const SingleActivator(LogicalKeyboardKey.arrowLeft): () =>
                  unawaited(_navigate(_selected - 1)),
              const SingleActivator(LogicalKeyboardKey.arrowRight): () =>
                  unawaited(_navigate(_selected + 1)),
            },
            child: Focus(
              autofocus: true,
              child: Scaffold(
                appBar: AppBar(
                  title: Text(_book?.name ?? _l10n.readerTitle),
                  actions: [
                    IconButton(
                      onPressed: _settings,
                      tooltip: _l10n.readerSettings,
                      icon: const Icon(Icons.text_fields),
                    ),
                  ],
                ),
                body: SafeArea(
                  child: Column(
                    children: [
                      if (_saveError != null)
                        MaterialBanner(
                          content: Text(_saveError!),
                          actions: [
                            TextButton(
                              onPressed: () => unawaited(
                                _settingsSaveFailed
                                    ? _persistSettings()
                                    : _persist(),
                              ),
                              child: Text(_l10n.readerRetry),
                            ),
                          ],
                        ),
                      Expanded(
                        child: _loading
                            ? const Center(child: CircularProgressIndicator())
                            : _error != null
                            ? Center(
                                child: Padding(
                                  padding: const EdgeInsets.all(24),
                                  child: Column(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      Text(
                                        _error!,
                                        style: TextStyle(
                                          color: theme.colorScheme.error,
                                        ),
                                      ),
                                      const SizedBox(height: 16),
                                      FilledButton(
                                        onPressed: () =>
                                            _book == null || _chapters.isEmpty
                                            ? _initialize()
                                            : _openChapter(
                                                _selected,
                                                offset: _offset,
                                              ),
                                        child: Text(_l10n.readerRetry),
                                      ),
                                    ],
                                  ),
                                ),
                              )
                            : LayoutBuilder(
                                builder: (context, constraints) {
                                  final width =
                                      math.min(800.0, constraints.maxWidth) -
                                      48;
                                  final style = DefaultTextStyle.of(context)
                                      .style
                                      .merge(
                                        TextStyle(
                                          fontSize: _fontSize,
                                          height: 1.8,
                                          color: theme.colorScheme.onSurface,
                                        ),
                                      );
                                  _painter?.dispose();
                                  _painter = TextPainter(
                                    text: TextSpan(text: _text!, style: style),
                                    textDirection: Directionality.of(context),
                                    textScaler: MediaQuery.textScalerOf(
                                      context,
                                    ),
                                  )..layout(maxWidth: width);
                                  if (_layoutWidth != width) {
                                    _layoutWidth = width;
                                    _restorePosition();
                                  }
                                  return Align(
                                    alignment: Alignment.topCenter,
                                    child: SizedBox(
                                      width: math.min(
                                        800.0,
                                        constraints.maxWidth,
                                      ),
                                      child: Scrollbar(
                                        controller: _scroll,
                                        child: SingleChildScrollView(
                                          key: const Key('reader-scroll'),
                                          controller: _scroll,
                                          padding: const EdgeInsets.all(24),
                                          child: SelectableText(
                                            _text!,
                                            style: style,
                                          ),
                                        ),
                                      ),
                                    ),
                                  );
                                },
                              ),
                      ),
                    ],
                  ),
                ),
                bottomNavigationBar: SafeArea(
                  child: Row(
                    children: [
                      IconButton(
                        onPressed: !_loading && _selected > 0
                            ? () => _navigate(_selected - 1)
                            : null,
                        tooltip: _l10n.readerPreviousChapter,
                        icon: const Icon(Icons.chevron_left),
                      ),
                      Expanded(
                        child: TextButton(
                          onPressed: _chapters.isEmpty ? null : _catalogue,
                          child: Text(
                            _chapters.isEmpty
                                ? _l10n.readerCatalogue
                                : _l10n.readerChapterPosition(
                                    _selected + 1,
                                    _chapters.length,
                                    _chapters[_selected].title,
                                  ),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      ),
                      IconButton(
                        onPressed: !_loading && _selected + 1 < _chapters.length
                            ? () => _navigate(_selected + 1)
                            : null,
                        tooltip: _l10n.readerNextChapter,
                        icon: const Icon(Icons.chevron_right),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
