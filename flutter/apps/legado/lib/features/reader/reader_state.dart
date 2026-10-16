import 'package:app_core/app_core.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:freezed_annotation/freezed_annotation.dart';

part 'reader_state.freezed.dart';

@freezed
abstract class ReaderViewState with _$ReaderViewState {
  const factory ReaderViewState({
    LegacyBookRecord? book,
    @Default(<ReaderChapter>[]) List<ReaderChapter> chapters,
    @Default(0) int selected,
    @Default(0) int characterOffset,
    String? content,
    String? error,
    String? saveError,
    @Default(true) bool loading,
    @Default(20) double fontSize,
    @Default('system') String theme,
  }) = _ReaderViewState;
}

/// Immutable provider identity prevents preview content from sharing a session
/// with another book or a different chapter-selection route.
@freezed
abstract class ReaderIdentity with _$ReaderIdentity {
  const factory ReaderIdentity({
    required LibraryRepository repository,
    required String bookUrl,
    LegacyBookRecord? previewBook,
    List<ReaderChapter>? previewChapters,
    int? initialChapterIndex,
  }) = _ReaderIdentity;
}

/// Each reader route owns an immutable session. Layout objects stay in the UI.
final readerStateProvider = NotifierProvider.autoDispose
    .family<ReaderStateController, ReaderViewState, ReaderIdentity>(
      ReaderStateController.new,
    );

class ReaderStateController extends Notifier<ReaderViewState> {
  ReaderStateController(this.identity);
  final ReaderIdentity identity;
  @override
  ReaderViewState build() => const ReaderViewState();
  void replace(ReaderViewState value) => state = value;
}
