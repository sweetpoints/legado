// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'providers.dart';

// **************************************************************************
// RiverpodGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint, type=warning

@ProviderFor(persistentLibrary)
final persistentLibraryProvider = PersistentLibraryProvider._();

final class PersistentLibraryProvider
    extends
        $FunctionalProvider<
          AsyncValue<LibraryRepository>,
          LibraryRepository,
          FutureOr<LibraryRepository>
        >
    with
        $FutureModifier<LibraryRepository>,
        $FutureProvider<LibraryRepository> {
  PersistentLibraryProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'persistentLibraryProvider',
        isAutoDispose: false,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$persistentLibraryHash();

  @$internal
  @override
  $FutureProviderElement<LibraryRepository> $createElement(
    $ProviderPointer pointer,
  ) => $FutureProviderElement(pointer);

  @override
  FutureOr<LibraryRepository> create(Ref ref) {
    return persistentLibrary(ref);
  }
}

String _$persistentLibraryHash() => r'cd77aeb7149a7b471bc7c0bbe55fb98cc2b88c1e';

/// Overridden only after opening the durable library; never a memory fallback.

@ProviderFor(library)
final libraryProvider = LibraryProvider._();

/// Overridden only after opening the durable library; never a memory fallback.

final class LibraryProvider
    extends
        $FunctionalProvider<
          LibraryRepository,
          LibraryRepository,
          LibraryRepository
        >
    with $Provider<LibraryRepository> {
  /// Overridden only after opening the durable library; never a memory fallback.
  LibraryProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'libraryProvider',
        isAutoDispose: false,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$libraryHash();

  @$internal
  @override
  $ProviderElement<LibraryRepository> $createElement(
    $ProviderPointer pointer,
  ) => $ProviderElement(pointer);

  @override
  LibraryRepository create(Ref ref) {
    return library(ref);
  }

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(LibraryRepository value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<LibraryRepository>(value),
    );
  }
}

String _$libraryHash() => r'eb44a7ccf78ef9689b2eff8bc8cbf95b409ff9e9';

@ProviderFor(libraryChanges)
final libraryChangesProvider = LibraryChangesProvider._();

final class LibraryChangesProvider
    extends $FunctionalProvider<AsyncValue<int>, int, Stream<int>>
    with $FutureModifier<int>, $StreamProvider<int> {
  LibraryChangesProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'libraryChangesProvider',
        isAutoDispose: false,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$libraryChangesHash();

  @$internal
  @override
  $StreamProviderElement<int> $createElement($ProviderPointer pointer) =>
      $StreamProviderElement(pointer);

  @override
  Stream<int> create(Ref ref) {
    return libraryChanges(ref);
  }
}

String _$libraryChangesHash() => r'1f788b52cc1c9e2f401b3f41ea7afd131aeb39c2';

@ProviderFor(bookshelf)
final bookshelfProvider = BookshelfProvider._();

final class BookshelfProvider
    extends
        $FunctionalProvider<
          AsyncValue<List<LegacyBookRecord>>,
          List<LegacyBookRecord>,
          FutureOr<List<LegacyBookRecord>>
        >
    with
        $FutureModifier<List<LegacyBookRecord>>,
        $FutureProvider<List<LegacyBookRecord>> {
  BookshelfProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'bookshelfProvider',
        isAutoDispose: false,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$bookshelfHash();

  @$internal
  @override
  $FutureProviderElement<List<LegacyBookRecord>> $createElement(
    $ProviderPointer pointer,
  ) => $FutureProviderElement(pointer);

  @override
  FutureOr<List<LegacyBookRecord>> create(Ref ref) {
    return bookshelf(ref);
  }
}

String _$bookshelfHash() => r'46b81446f302164fb5e8edef2745e4cbd60d9b47';

@ProviderFor(dio)
final dioProvider = DioProvider._();

final class DioProvider extends $FunctionalProvider<Dio, Dio, Dio>
    with $Provider<Dio> {
  DioProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'dioProvider',
        isAutoDispose: false,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$dioHash();

  @$internal
  @override
  $ProviderElement<Dio> $createElement($ProviderPointer pointer) =>
      $ProviderElement(pointer);

  @override
  Dio create(Ref ref) {
    return dio(ref);
  }

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(Dio value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<Dio>(value),
    );
  }
}

String _$dioHash() => r'8bab33b70bc935628132d6cbf9ffaee00e39cb6b';

@ProviderFor(PreferencesController)
final preferencesProvider = PreferencesControllerProvider._();

final class PreferencesControllerProvider
    extends $AsyncNotifierProvider<PreferencesController, AppPreferences> {
  PreferencesControllerProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'preferencesProvider',
        isAutoDispose: false,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$preferencesControllerHash();

  @$internal
  @override
  PreferencesController create() => PreferencesController();
}

String _$preferencesControllerHash() =>
    r'521459a0c757a9b657e49717b006ecc2706581e1';

abstract class _$PreferencesController extends $AsyncNotifier<AppPreferences> {
  FutureOr<AppPreferences> build();
  @$mustCallSuper
  @override
  WhenComplete runBuild() {
    final ref = this.ref as $Ref<AsyncValue<AppPreferences>, AppPreferences>;
    final element =
        ref.element
            as $ClassProviderElement<
              AnyNotifier<AsyncValue<AppPreferences>, AppPreferences>,
              AsyncValue<AppPreferences>,
              Object?,
              Object?
            >;
    return element.handleCreate(ref, build);
  }
}
