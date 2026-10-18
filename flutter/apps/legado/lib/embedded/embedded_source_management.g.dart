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
    r'f0333c1096e0b74938f8525a607c926009d0e175';

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
    r'4aa5109c6623f5216b9133ba60759e79214c9520';
