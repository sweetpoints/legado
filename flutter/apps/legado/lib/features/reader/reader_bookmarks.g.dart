// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'reader_bookmarks.dart';

// **************************************************************************
// RiverpodGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint, type=warning

@ProviderFor(readerBookmarks)
final readerBookmarksProvider = ReaderBookmarksFamily._();

final class ReaderBookmarksProvider
    extends
        $FunctionalProvider<
          AsyncValue<List<ReaderBookmark>>,
          List<ReaderBookmark>,
          FutureOr<List<ReaderBookmark>>
        >
    with
        $FutureModifier<List<ReaderBookmark>>,
        $FutureProvider<List<ReaderBookmark>> {
  ReaderBookmarksProvider._({
    required ReaderBookmarksFamily super.from,
    required ({LegacyBookRecord book, LibraryRepository repository})
    super.argument,
  }) : super(
         retry: null,
         name: r'readerBookmarksProvider',
         isAutoDispose: true,
         dependencies: null,
         $allTransitiveDependencies: null,
       );

  @override
  String debugGetCreateSourceHash() => _$readerBookmarksHash();

  @override
  String toString() {
    return r'readerBookmarksProvider'
        ''
        '($argument)';
  }

  @$internal
  @override
  $FutureProviderElement<List<ReaderBookmark>> $createElement(
    $ProviderPointer pointer,
  ) => $FutureProviderElement(pointer);

  @override
  FutureOr<List<ReaderBookmark>> create(Ref ref) {
    final argument =
        this.argument
            as ({LegacyBookRecord book, LibraryRepository repository});
    return readerBookmarks(ref, argument);
  }

  @override
  bool operator ==(Object other) {
    return other is ReaderBookmarksProvider && other.argument == argument;
  }

  @override
  int get hashCode {
    return argument.hashCode;
  }
}

String _$readerBookmarksHash() => r'741b847178d4249514e962ff05e07a9af9e340a7';

final class ReaderBookmarksFamily extends $Family
    with
        $FunctionalFamilyOverride<
          FutureOr<List<ReaderBookmark>>,
          ({LegacyBookRecord book, LibraryRepository repository})
        > {
  ReaderBookmarksFamily._()
    : super(
        retry: null,
        name: r'readerBookmarksProvider',
        dependencies: null,
        $allTransitiveDependencies: null,
        isAutoDispose: true,
      );

  ReaderBookmarksProvider call(
    ({LegacyBookRecord book, LibraryRepository repository}) query,
  ) => ReaderBookmarksProvider._(argument: query, from: this);

  @override
  String toString() => r'readerBookmarksProvider';
}
