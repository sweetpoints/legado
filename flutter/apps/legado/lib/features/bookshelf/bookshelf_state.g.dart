// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'bookshelf_state.dart';

// **************************************************************************
// RiverpodGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint, type=warning

@ProviderFor(BookshelfController)
final bookshelfStateProvider = BookshelfControllerProvider._();

final class BookshelfControllerProvider
    extends $AsyncNotifierProvider<BookshelfController, BookshelfViewState> {
  BookshelfControllerProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'bookshelfStateProvider',
        isAutoDispose: false,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$bookshelfControllerHash();

  @$internal
  @override
  BookshelfController create() => BookshelfController();
}

String _$bookshelfControllerHash() =>
    r'8a5d32593ee48740806e93bda5db75848339102c';

abstract class _$BookshelfController
    extends $AsyncNotifier<BookshelfViewState> {
  FutureOr<BookshelfViewState> build();
  @$mustCallSuper
  @override
  WhenComplete runBuild() {
    final ref =
        this.ref as $Ref<AsyncValue<BookshelfViewState>, BookshelfViewState>;
    final element =
        ref.element
            as $ClassProviderElement<
              AnyNotifier<AsyncValue<BookshelfViewState>, BookshelfViewState>,
              AsyncValue<BookshelfViewState>,
              Object?,
              Object?
            >;
    return element.handleCreate(ref, build);
  }
}
