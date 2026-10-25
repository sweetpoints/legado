// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'embedded_source_management.dart';

// **************************************************************************
// RiverpodGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint, type=warning

@ProviderFor(embeddedSourceConfiguration)
final embeddedSourceConfigurationProvider =
    EmbeddedSourceConfigurationProvider._();

final class EmbeddedSourceConfigurationProvider
    extends
        $FunctionalProvider<
          AsyncValue<EmbeddedSourceConfiguration>,
          EmbeddedSourceConfiguration,
          FutureOr<EmbeddedSourceConfiguration>
        >
    with
        $FutureModifier<EmbeddedSourceConfiguration>,
        $FutureProvider<EmbeddedSourceConfiguration> {
  EmbeddedSourceConfigurationProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'embeddedSourceConfigurationProvider',
        isAutoDispose: true,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$embeddedSourceConfigurationHash();

  @$internal
  @override
  $FutureProviderElement<EmbeddedSourceConfiguration> $createElement(
    $ProviderPointer pointer,
  ) => $FutureProviderElement(pointer);

  @override
  FutureOr<EmbeddedSourceConfiguration> create(Ref ref) {
    return embeddedSourceConfiguration(ref);
  }
}

String _$embeddedSourceConfigurationHash() =>
    r'8418f15ed79268064d7782e0c800e707b9785edc';

@ProviderFor(embeddedSourceBackend)
final embeddedSourceBackendProvider = EmbeddedSourceBackendProvider._();

final class EmbeddedSourceBackendProvider
    extends
        $FunctionalProvider<
          MethodChannelSourceManagementBackend,
          MethodChannelSourceManagementBackend,
          MethodChannelSourceManagementBackend
        >
    with $Provider<MethodChannelSourceManagementBackend> {
  EmbeddedSourceBackendProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'embeddedSourceBackendProvider',
        isAutoDispose: true,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$embeddedSourceBackendHash();

  @$internal
  @override
  $ProviderElement<MethodChannelSourceManagementBackend> $createElement(
    $ProviderPointer pointer,
  ) => $ProviderElement(pointer);

  @override
  MethodChannelSourceManagementBackend create(Ref ref) {
    return embeddedSourceBackend(ref);
  }

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(MethodChannelSourceManagementBackend value) {
    return $ProviderOverride(
      origin: this,
      providerOverride:
          $SyncValueProvider<MethodChannelSourceManagementBackend>(value),
    );
  }
}

String _$embeddedSourceBackendHash() =>
    r'6342dc58638fae1351fd6d2ca17cfe784bc3c622';

@ProviderFor(embeddedSourceRouter)
final embeddedSourceRouterProvider = EmbeddedSourceRouterProvider._();

final class EmbeddedSourceRouterProvider
    extends $FunctionalProvider<GoRouter, GoRouter, GoRouter>
    with $Provider<GoRouter> {
  EmbeddedSourceRouterProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'embeddedSourceRouterProvider',
        isAutoDispose: true,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$embeddedSourceRouterHash();

  @$internal
  @override
  $ProviderElement<GoRouter> $createElement($ProviderPointer pointer) =>
      $ProviderElement(pointer);

  @override
  GoRouter create(Ref ref) {
    return embeddedSourceRouter(ref);
  }

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(GoRouter value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<GoRouter>(value),
    );
  }
}

String _$embeddedSourceRouterHash() =>
    r'037753bc0e107e7721beeb799f751a568fbd58b2';
