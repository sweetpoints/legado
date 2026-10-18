// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'source_debugger_bridge.dart';

// **************************************************************************
// RiverpodGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint, type=warning

@ProviderFor(sourceDebuggerBridge)
final sourceDebuggerBridgeProvider = SourceDebuggerBridgeProvider._();

final class SourceDebuggerBridgeProvider
    extends
        $FunctionalProvider<
          SourceDebuggerBridge,
          SourceDebuggerBridge,
          SourceDebuggerBridge
        >
    with $Provider<SourceDebuggerBridge> {
  SourceDebuggerBridgeProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'sourceDebuggerBridgeProvider',
        isAutoDispose: true,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$sourceDebuggerBridgeHash();

  @$internal
  @override
  $ProviderElement<SourceDebuggerBridge> $createElement(
    $ProviderPointer pointer,
  ) => $ProviderElement(pointer);

  @override
  SourceDebuggerBridge create(Ref ref) {
    return sourceDebuggerBridge(ref);
  }

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(SourceDebuggerBridge value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<SourceDebuggerBridge>(value),
    );
  }
}

String _$sourceDebuggerBridgeHash() =>
    r'a10d68cf71a7bb8c3a8adcdb9cd644a18c51f0ef';

@ProviderFor(sourceDebuggerState)
final sourceDebuggerStateProvider = SourceDebuggerStateProvider._();

final class SourceDebuggerStateProvider
    extends
        $FunctionalProvider<
          AsyncValue<SourceDebuggerSnapshot>,
          SourceDebuggerSnapshot,
          Stream<SourceDebuggerSnapshot>
        >
    with
        $FutureModifier<SourceDebuggerSnapshot>,
        $StreamProvider<SourceDebuggerSnapshot> {
  SourceDebuggerStateProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'sourceDebuggerStateProvider',
        isAutoDispose: true,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$sourceDebuggerStateHash();

  @$internal
  @override
  $StreamProviderElement<SourceDebuggerSnapshot> $createElement(
    $ProviderPointer pointer,
  ) => $StreamProviderElement(pointer);

  @override
  Stream<SourceDebuggerSnapshot> create(Ref ref) {
    return sourceDebuggerState(ref);
  }
}

String _$sourceDebuggerStateHash() =>
    r'09d0fcfc00d9ddd79c29371a0e77bbcc8c4a524c';
