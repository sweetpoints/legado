// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'rss_home_page.dart';

// **************************************************************************
// RiverpodGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint, type=warning

@ProviderFor(rssStore)
final rssStoreProvider = RssStoreFamily._();

final class RssStoreProvider
    extends $FunctionalProvider<RssStore, RssStore, RssStore>
    with $Provider<RssStore> {
  RssStoreProvider._({
    required RssStoreFamily super.from,
    required LibraryRepository super.argument,
  }) : super(
         retry: null,
         name: r'rssStoreProvider',
         isAutoDispose: false,
         dependencies: null,
         $allTransitiveDependencies: null,
       );

  @override
  String debugGetCreateSourceHash() => _$rssStoreHash();

  @override
  String toString() {
    return r'rssStoreProvider'
        ''
        '($argument)';
  }

  @$internal
  @override
  $ProviderElement<RssStore> $createElement($ProviderPointer pointer) =>
      $ProviderElement(pointer);

  @override
  RssStore create(Ref ref) {
    final argument = this.argument as LibraryRepository;
    return rssStore(ref, argument);
  }

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(RssStore value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<RssStore>(value),
    );
  }

  @override
  bool operator ==(Object other) {
    return other is RssStoreProvider && other.argument == argument;
  }

  @override
  int get hashCode {
    return argument.hashCode;
  }
}

String _$rssStoreHash() => r'37a927217db1a2ef53b0366391a37e7d2a470b53';

final class RssStoreFamily extends $Family
    with $FunctionalFamilyOverride<RssStore, LibraryRepository> {
  RssStoreFamily._()
    : super(
        retry: null,
        name: r'rssStoreProvider',
        dependencies: null,
        $allTransitiveDependencies: null,
        isAutoDispose: false,
      );

  RssStoreProvider call(LibraryRepository repository) =>
      RssStoreProvider._(argument: repository, from: this);

  @override
  String toString() => r'rssStoreProvider';
}

@ProviderFor(rssSourceView)
final rssSourceViewProvider = RssSourceViewFamily._();

final class RssSourceViewProvider
    extends
        $FunctionalProvider<
          AsyncValue<RssSourceView>,
          RssSourceView,
          FutureOr<RssSourceView>
        >
    with $FutureModifier<RssSourceView>, $FutureProvider<RssSourceView> {
  RssSourceViewProvider._({
    required RssSourceViewFamily super.from,
    required LibraryRepository super.argument,
  }) : super(
         retry: null,
         name: r'rssSourceViewProvider',
         isAutoDispose: true,
         dependencies: null,
         $allTransitiveDependencies: null,
       );

  @override
  String debugGetCreateSourceHash() => _$rssSourceViewHash();

  @override
  String toString() {
    return r'rssSourceViewProvider'
        ''
        '($argument)';
  }

  @$internal
  @override
  $FutureProviderElement<RssSourceView> $createElement(
    $ProviderPointer pointer,
  ) => $FutureProviderElement(pointer);

  @override
  FutureOr<RssSourceView> create(Ref ref) {
    final argument = this.argument as LibraryRepository;
    return rssSourceView(ref, argument);
  }

  @override
  bool operator ==(Object other) {
    return other is RssSourceViewProvider && other.argument == argument;
  }

  @override
  int get hashCode {
    return argument.hashCode;
  }
}

String _$rssSourceViewHash() => r'a6d6194a31f5434562e408b437b4ccc4534baf1b';

final class RssSourceViewFamily extends $Family
    with $FunctionalFamilyOverride<FutureOr<RssSourceView>, LibraryRepository> {
  RssSourceViewFamily._()
    : super(
        retry: null,
        name: r'rssSourceViewProvider',
        dependencies: null,
        $allTransitiveDependencies: null,
        isAutoDispose: true,
      );

  RssSourceViewProvider call(LibraryRepository repository) =>
      RssSourceViewProvider._(argument: repository, from: this);

  @override
  String toString() => r'rssSourceViewProvider';
}

@ProviderFor(rssSources)
final rssSourcesProvider = RssSourcesFamily._();

final class RssSourcesProvider
    extends
        $FunctionalProvider<
          AsyncValue<List<RssSource>>,
          List<RssSource>,
          FutureOr<List<RssSource>>
        >
    with $FutureModifier<List<RssSource>>, $FutureProvider<List<RssSource>> {
  RssSourcesProvider._({
    required RssSourcesFamily super.from,
    required LibraryRepository super.argument,
  }) : super(
         retry: null,
         name: r'rssSourcesProvider',
         isAutoDispose: true,
         dependencies: null,
         $allTransitiveDependencies: null,
       );

  @override
  String debugGetCreateSourceHash() => _$rssSourcesHash();

  @override
  String toString() {
    return r'rssSourcesProvider'
        ''
        '($argument)';
  }

  @$internal
  @override
  $FutureProviderElement<List<RssSource>> $createElement(
    $ProviderPointer pointer,
  ) => $FutureProviderElement(pointer);

  @override
  FutureOr<List<RssSource>> create(Ref ref) {
    final argument = this.argument as LibraryRepository;
    return rssSources(ref, argument);
  }

  @override
  bool operator ==(Object other) {
    return other is RssSourcesProvider && other.argument == argument;
  }

  @override
  int get hashCode {
    return argument.hashCode;
  }
}

String _$rssSourcesHash() => r'0e1cc65e610436249d977a3f0e1cdffc82108928';

final class RssSourcesFamily extends $Family
    with
        $FunctionalFamilyOverride<
          FutureOr<List<RssSource>>,
          LibraryRepository
        > {
  RssSourcesFamily._()
    : super(
        retry: null,
        name: r'rssSourcesProvider',
        dependencies: null,
        $allTransitiveDependencies: null,
        isAutoDispose: true,
      );

  RssSourcesProvider call(LibraryRepository repository) =>
      RssSourcesProvider._(argument: repository, from: this);

  @override
  String toString() => r'rssSourcesProvider';
}

@ProviderFor(rssArticles)
final rssArticlesProvider = RssArticlesFamily._();

final class RssArticlesProvider
    extends
        $FunctionalProvider<
          AsyncValue<List<RssArticle>>,
          List<RssArticle>,
          FutureOr<List<RssArticle>>
        >
    with $FutureModifier<List<RssArticle>>, $FutureProvider<List<RssArticle>> {
  RssArticlesProvider._({
    required RssArticlesFamily super.from,
    required (LibraryRepository, String) super.argument,
  }) : super(
         retry: null,
         name: r'rssArticlesProvider',
         isAutoDispose: true,
         dependencies: null,
         $allTransitiveDependencies: null,
       );

  @override
  String debugGetCreateSourceHash() => _$rssArticlesHash();

  @override
  String toString() {
    return r'rssArticlesProvider'
        ''
        '($argument)';
  }

  @$internal
  @override
  $FutureProviderElement<List<RssArticle>> $createElement(
    $ProviderPointer pointer,
  ) => $FutureProviderElement(pointer);

  @override
  FutureOr<List<RssArticle>> create(Ref ref) {
    final argument = this.argument as (LibraryRepository, String);
    return rssArticles(ref, argument);
  }

  @override
  bool operator ==(Object other) {
    return other is RssArticlesProvider && other.argument == argument;
  }

  @override
  int get hashCode {
    return argument.hashCode;
  }
}

String _$rssArticlesHash() => r'cebe03ba71f103f45bc0a52d8b0c71e39b198627';

final class RssArticlesFamily extends $Family
    with
        $FunctionalFamilyOverride<
          FutureOr<List<RssArticle>>,
          (LibraryRepository, String)
        > {
  RssArticlesFamily._()
    : super(
        retry: null,
        name: r'rssArticlesProvider',
        dependencies: null,
        $allTransitiveDependencies: null,
        isAutoDispose: true,
      );

  RssArticlesProvider call((LibraryRepository, String) args) =>
      RssArticlesProvider._(argument: args, from: this);

  @override
  String toString() => r'rssArticlesProvider';
}

@ProviderFor(rssClient)
final rssClientProvider = RssClientProvider._();

final class RssClientProvider
    extends $FunctionalProvider<RssFeedClient, RssFeedClient, RssFeedClient>
    with $Provider<RssFeedClient> {
  RssClientProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'rssClientProvider',
        isAutoDispose: true,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$rssClientHash();

  @$internal
  @override
  $ProviderElement<RssFeedClient> $createElement($ProviderPointer pointer) =>
      $ProviderElement(pointer);

  @override
  RssFeedClient create(Ref ref) {
    return rssClient(ref);
  }

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(RssFeedClient value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<RssFeedClient>(value),
    );
  }
}

String _$rssClientHash() => r'0631e68603ee3495b2d19741efe2ddaba6a56e61';
