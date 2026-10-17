// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'source_editor_bridge.dart';

// **************************************************************************
// RiverpodGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint, type=warning

@ProviderFor(sourceEditorBridge)
final sourceEditorBridgeProvider = SourceEditorBridgeProvider._();

final class SourceEditorBridgeProvider
    extends
        $FunctionalProvider<
          SourceEditorBridge,
          SourceEditorBridge,
          SourceEditorBridge
        >
    with $Provider<SourceEditorBridge> {
  SourceEditorBridgeProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'sourceEditorBridgeProvider',
        isAutoDispose: true,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$sourceEditorBridgeHash();

  @$internal
  @override
  $ProviderElement<SourceEditorBridge> $createElement(
    $ProviderPointer pointer,
  ) => $ProviderElement(pointer);

  @override
  SourceEditorBridge create(Ref ref) {
    return sourceEditorBridge(ref);
  }

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(SourceEditorBridge value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<SourceEditorBridge>(value),
    );
  }
}

String _$sourceEditorBridgeHash() =>
    r'842079887beec078a8544de981a980cca61d1b19';

@ProviderFor(sourceEditorState)
final sourceEditorStateProvider = SourceEditorStateProvider._();

final class SourceEditorStateProvider
    extends
        $FunctionalProvider<
          AsyncValue<SourceEditorSnapshot>,
          SourceEditorSnapshot,
          Stream<SourceEditorSnapshot>
        >
    with
        $FutureModifier<SourceEditorSnapshot>,
        $StreamProvider<SourceEditorSnapshot> {
  SourceEditorStateProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'sourceEditorStateProvider',
        isAutoDispose: true,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$sourceEditorStateHash();

  @$internal
  @override
  $StreamProviderElement<SourceEditorSnapshot> $createElement(
    $ProviderPointer pointer,
  ) => $StreamProviderElement(pointer);

  @override
  Stream<SourceEditorSnapshot> create(Ref ref) {
    return sourceEditorState(ref);
  }
}

String _$sourceEditorStateHash() => r'748f9386b3d64b4f54c5d34cace09586b724f37a';
