// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'source_manager_bridge.dart';

// **************************************************************************
// RiverpodGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint, type=warning

@ProviderFor(sourceManagerBridge)
final sourceManagerBridgeProvider = SourceManagerBridgeProvider._();

final class SourceManagerBridgeProvider
    extends
        $FunctionalProvider<
          SourceManagerBridge,
          SourceManagerBridge,
          SourceManagerBridge
        >
    with $Provider<SourceManagerBridge> {
  SourceManagerBridgeProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'sourceManagerBridgeProvider',
        isAutoDispose: true,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$sourceManagerBridgeHash();

  @$internal
  @override
  $ProviderElement<SourceManagerBridge> $createElement(
    $ProviderPointer pointer,
  ) => $ProviderElement(pointer);

  @override
  SourceManagerBridge create(Ref ref) {
    return sourceManagerBridge(ref);
  }

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(SourceManagerBridge value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<SourceManagerBridge>(value),
    );
  }
}

String _$sourceManagerBridgeHash() =>
    r'ef70e441eaf59c8ecc1a00dae3352d410da178ab';

@ProviderFor(sourceManagerState)
final sourceManagerStateProvider = SourceManagerStateProvider._();

final class SourceManagerStateProvider
    extends
        $FunctionalProvider<
          AsyncValue<SourceManagerSnapshot>,
          SourceManagerSnapshot,
          Stream<SourceManagerSnapshot>
        >
    with
        $FutureModifier<SourceManagerSnapshot>,
        $StreamProvider<SourceManagerSnapshot> {
  SourceManagerStateProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'sourceManagerStateProvider',
        isAutoDispose: true,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$sourceManagerStateHash();

  @$internal
  @override
  $StreamProviderElement<SourceManagerSnapshot> $createElement(
    $ProviderPointer pointer,
  ) => $StreamProviderElement(pointer);

  @override
  Stream<SourceManagerSnapshot> create(Ref ref) {
    return sourceManagerState(ref);
  }
}

String _$sourceManagerStateHash() =>
    r'4fcc5b1134e202559ae0f407fc17ffaa077c187d';
