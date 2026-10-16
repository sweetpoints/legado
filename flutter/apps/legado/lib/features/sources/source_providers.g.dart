// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'source_providers.dart';

// **************************************************************************
// RiverpodGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint, type=warning

@ProviderFor(bookSourceService)
final bookSourceServiceProvider = BookSourceServiceFamily._();

final class BookSourceServiceProvider
    extends
        $FunctionalProvider<
          BookSourceService,
          BookSourceService,
          BookSourceService
        >
    with $Provider<BookSourceService> {
  BookSourceServiceProvider._({
    required BookSourceServiceFamily super.from,
    required LibraryRepository super.argument,
  }) : super(
         retry: null,
         name: r'bookSourceServiceProvider',
         isAutoDispose: false,
         dependencies: null,
         $allTransitiveDependencies: null,
       );

  @override
  String debugGetCreateSourceHash() => _$bookSourceServiceHash();

  @override
  String toString() {
    return r'bookSourceServiceProvider'
        ''
        '($argument)';
  }

  @$internal
  @override
  $ProviderElement<BookSourceService> $createElement(
    $ProviderPointer pointer,
  ) => $ProviderElement(pointer);

  @override
  BookSourceService create(Ref ref) {
    final argument = this.argument as LibraryRepository;
    return bookSourceService(ref, argument);
  }

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(BookSourceService value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<BookSourceService>(value),
    );
  }

  @override
  bool operator ==(Object other) {
    return other is BookSourceServiceProvider && other.argument == argument;
  }

  @override
  int get hashCode {
    return argument.hashCode;
  }
}

String _$bookSourceServiceHash() => r'7899614c9fa35dc2d708b189d8dfb25cdafe3118';

final class BookSourceServiceFamily extends $Family
    with $FunctionalFamilyOverride<BookSourceService, LibraryRepository> {
  BookSourceServiceFamily._()
    : super(
        retry: null,
        name: r'bookSourceServiceProvider',
        dependencies: null,
        $allTransitiveDependencies: null,
        isAutoDispose: false,
      );

  BookSourceServiceProvider call(LibraryRepository repository) =>
      BookSourceServiceProvider._(argument: repository, from: this);

  @override
  String toString() => r'bookSourceServiceProvider';
}

@ProviderFor(sourceList)
final sourceListProvider = SourceListFamily._();

final class SourceListProvider
    extends
        $FunctionalProvider<
          AsyncValue<List<Map<String, Object?>>>,
          List<Map<String, Object?>>,
          Stream<List<Map<String, Object?>>>
        >
    with
        $FutureModifier<List<Map<String, Object?>>>,
        $StreamProvider<List<Map<String, Object?>>> {
  SourceListProvider._({
    required SourceListFamily super.from,
    required LibraryRepository super.argument,
  }) : super(
         retry: null,
         name: r'sourceListProvider',
         isAutoDispose: false,
         dependencies: null,
         $allTransitiveDependencies: null,
       );

  @override
  String debugGetCreateSourceHash() => _$sourceListHash();

  @override
  String toString() {
    return r'sourceListProvider'
        ''
        '($argument)';
  }

  @$internal
  @override
  $StreamProviderElement<List<Map<String, Object?>>> $createElement(
    $ProviderPointer pointer,
  ) => $StreamProviderElement(pointer);

  @override
  Stream<List<Map<String, Object?>>> create(Ref ref) {
    final argument = this.argument as LibraryRepository;
    return sourceList(ref, argument);
  }

  @override
  bool operator ==(Object other) {
    return other is SourceListProvider && other.argument == argument;
  }

  @override
  int get hashCode {
    return argument.hashCode;
  }
}

String _$sourceListHash() => r'e7ee2c1cc34a9a7a935108722aecdbd03d0a2ee1';

final class SourceListFamily extends $Family
    with
        $FunctionalFamilyOverride<
          Stream<List<Map<String, Object?>>>,
          LibraryRepository
        > {
  SourceListFamily._()
    : super(
        retry: null,
        name: r'sourceListProvider',
        dependencies: null,
        $allTransitiveDependencies: null,
        isAutoDispose: false,
      );

  SourceListProvider call(LibraryRepository repository) =>
      SourceListProvider._(argument: repository, from: this);

  @override
  String toString() => r'sourceListProvider';
}

@ProviderFor(sourceSearch)
final sourceSearchProvider = SourceSearchFamily._();

final class SourceSearchProvider
    extends
        $FunctionalProvider<
          AsyncValue<List<NetworkBook>>,
          List<NetworkBook>,
          FutureOr<List<NetworkBook>>
        >
    with
        $FutureModifier<List<NetworkBook>>,
        $FutureProvider<List<NetworkBook>> {
  SourceSearchProvider._({
    required SourceSearchFamily super.from,
    required SourceSearchQuery super.argument,
  }) : super(
         retry: null,
         name: r'sourceSearchProvider',
         isAutoDispose: true,
         dependencies: null,
         $allTransitiveDependencies: null,
       );

  @override
  String debugGetCreateSourceHash() => _$sourceSearchHash();

  @override
  String toString() {
    return r'sourceSearchProvider'
        ''
        '($argument)';
  }

  @$internal
  @override
  $FutureProviderElement<List<NetworkBook>> $createElement(
    $ProviderPointer pointer,
  ) => $FutureProviderElement(pointer);

  @override
  FutureOr<List<NetworkBook>> create(Ref ref) {
    final argument = this.argument as SourceSearchQuery;
    return sourceSearch(ref, argument);
  }

  @override
  bool operator ==(Object other) {
    return other is SourceSearchProvider && other.argument == argument;
  }

  @override
  int get hashCode {
    return argument.hashCode;
  }
}

String _$sourceSearchHash() => r'c6ee353455e3e16b9f14912e7804d836697e3a9a';

final class SourceSearchFamily extends $Family
    with
        $FunctionalFamilyOverride<
          FutureOr<List<NetworkBook>>,
          SourceSearchQuery
        > {
  SourceSearchFamily._()
    : super(
        retry: null,
        name: r'sourceSearchProvider',
        dependencies: null,
        $allTransitiveDependencies: null,
        isAutoDispose: true,
      );

  SourceSearchProvider call(SourceSearchQuery query) =>
      SourceSearchProvider._(argument: query, from: this);

  @override
  String toString() => r'sourceSearchProvider';
}

@ProviderFor(bookDetail)
final bookDetailProvider = BookDetailFamily._();

final class BookDetailProvider
    extends
        $FunctionalProvider<
          AsyncValue<BookPreview>,
          BookPreview,
          FutureOr<BookPreview>
        >
    with $FutureModifier<BookPreview>, $FutureProvider<BookPreview> {
  BookDetailProvider._({
    required BookDetailFamily super.from,
    required BookDetailQuery super.argument,
  }) : super(
         retry: null,
         name: r'bookDetailProvider',
         isAutoDispose: true,
         dependencies: null,
         $allTransitiveDependencies: null,
       );

  @override
  String debugGetCreateSourceHash() => _$bookDetailHash();

  @override
  String toString() {
    return r'bookDetailProvider'
        ''
        '($argument)';
  }

  @$internal
  @override
  $FutureProviderElement<BookPreview> $createElement(
    $ProviderPointer pointer,
  ) => $FutureProviderElement(pointer);

  @override
  FutureOr<BookPreview> create(Ref ref) {
    final argument = this.argument as BookDetailQuery;
    return bookDetail(ref, argument);
  }

  @override
  bool operator ==(Object other) {
    return other is BookDetailProvider && other.argument == argument;
  }

  @override
  int get hashCode {
    return argument.hashCode;
  }
}

String _$bookDetailHash() => r'c6f43a195994c35c02bb2c6f028686f87964015e';

final class BookDetailFamily extends $Family
    with $FunctionalFamilyOverride<FutureOr<BookPreview>, BookDetailQuery> {
  BookDetailFamily._()
    : super(
        retry: null,
        name: r'bookDetailProvider',
        dependencies: null,
        $allTransitiveDependencies: null,
        isAutoDispose: true,
      );

  BookDetailProvider call(BookDetailQuery query) =>
      BookDetailProvider._(argument: query, from: this);

  @override
  String toString() => r'bookDetailProvider';
}

@ProviderFor(sourceExploreMenu)
final sourceExploreMenuProvider = SourceExploreMenuFamily._();

final class SourceExploreMenuProvider
    extends
        $FunctionalProvider<
          AsyncValue<List<Map<String, Object?>>>,
          List<Map<String, Object?>>,
          FutureOr<List<Map<String, Object?>>>
        >
    with
        $FutureModifier<List<Map<String, Object?>>>,
        $FutureProvider<List<Map<String, Object?>>> {
  SourceExploreMenuProvider._({
    required SourceExploreMenuFamily super.from,
    required ({LibraryRepository repository, String sourceId}) super.argument,
  }) : super(
         retry: null,
         name: r'sourceExploreMenuProvider',
         isAutoDispose: true,
         dependencies: null,
         $allTransitiveDependencies: null,
       );

  @override
  String debugGetCreateSourceHash() => _$sourceExploreMenuHash();

  @override
  String toString() {
    return r'sourceExploreMenuProvider'
        ''
        '($argument)';
  }

  @$internal
  @override
  $FutureProviderElement<List<Map<String, Object?>>> $createElement(
    $ProviderPointer pointer,
  ) => $FutureProviderElement(pointer);

  @override
  FutureOr<List<Map<String, Object?>>> create(Ref ref) {
    final argument =
        this.argument as ({LibraryRepository repository, String sourceId});
    return sourceExploreMenu(ref, argument);
  }

  @override
  bool operator ==(Object other) {
    return other is SourceExploreMenuProvider && other.argument == argument;
  }

  @override
  int get hashCode {
    return argument.hashCode;
  }
}

String _$sourceExploreMenuHash() => r'a5c3ae91f3c22cc189e5d29a9b8c8e144161fc4b';

final class SourceExploreMenuFamily extends $Family
    with
        $FunctionalFamilyOverride<
          FutureOr<List<Map<String, Object?>>>,
          ({LibraryRepository repository, String sourceId})
        > {
  SourceExploreMenuFamily._()
    : super(
        retry: null,
        name: r'sourceExploreMenuProvider',
        dependencies: null,
        $allTransitiveDependencies: null,
        isAutoDispose: true,
      );

  SourceExploreMenuProvider call(
    ({LibraryRepository repository, String sourceId}) query,
  ) => SourceExploreMenuProvider._(argument: query, from: this);

  @override
  String toString() => r'sourceExploreMenuProvider';
}

@ProviderFor(sourceExplore)
final sourceExploreProvider = SourceExploreFamily._();

final class SourceExploreProvider
    extends
        $FunctionalProvider<
          AsyncValue<List<NetworkBook>>,
          List<NetworkBook>,
          FutureOr<List<NetworkBook>>
        >
    with
        $FutureModifier<List<NetworkBook>>,
        $FutureProvider<List<NetworkBook>> {
  SourceExploreProvider._({
    required SourceExploreFamily super.from,
    required SourceExploreQuery super.argument,
  }) : super(
         retry: null,
         name: r'sourceExploreProvider',
         isAutoDispose: true,
         dependencies: null,
         $allTransitiveDependencies: null,
       );

  @override
  String debugGetCreateSourceHash() => _$sourceExploreHash();

  @override
  String toString() {
    return r'sourceExploreProvider'
        ''
        '($argument)';
  }

  @$internal
  @override
  $FutureProviderElement<List<NetworkBook>> $createElement(
    $ProviderPointer pointer,
  ) => $FutureProviderElement(pointer);

  @override
  FutureOr<List<NetworkBook>> create(Ref ref) {
    final argument = this.argument as SourceExploreQuery;
    return sourceExplore(ref, argument);
  }

  @override
  bool operator ==(Object other) {
    return other is SourceExploreProvider && other.argument == argument;
  }

  @override
  int get hashCode {
    return argument.hashCode;
  }
}

String _$sourceExploreHash() => r'8c8c4fec3a2262f44a1e516c6382d229e8931804';

final class SourceExploreFamily extends $Family
    with
        $FunctionalFamilyOverride<
          FutureOr<List<NetworkBook>>,
          SourceExploreQuery
        > {
  SourceExploreFamily._()
    : super(
        retry: null,
        name: r'sourceExploreProvider',
        dependencies: null,
        $allTransitiveDependencies: null,
        isAutoDispose: true,
      );

  SourceExploreProvider call(SourceExploreQuery query) =>
      SourceExploreProvider._(argument: query, from: this);

  @override
  String toString() => r'sourceExploreProvider';
}
