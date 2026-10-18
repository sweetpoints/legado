// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'code_editor_bridge.dart';

// **************************************************************************
// RiverpodGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint, type=warning

@ProviderFor(codeEditorBridge)
final codeEditorBridgeProvider = CodeEditorBridgeProvider._();

final class CodeEditorBridgeProvider
    extends
        $FunctionalProvider<
          CodeEditorBridge,
          CodeEditorBridge,
          CodeEditorBridge
        >
    with $Provider<CodeEditorBridge> {
  CodeEditorBridgeProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'codeEditorBridgeProvider',
        isAutoDispose: true,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$codeEditorBridgeHash();

  @$internal
  @override
  $ProviderElement<CodeEditorBridge> $createElement($ProviderPointer pointer) =>
      $ProviderElement(pointer);

  @override
  CodeEditorBridge create(Ref ref) {
    return codeEditorBridge(ref);
  }

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(CodeEditorBridge value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<CodeEditorBridge>(value),
    );
  }
}

String _$codeEditorBridgeHash() => r'f6aefaffedda1397be2d4af3994c7bdf5ef49cc5';

@ProviderFor(codeEditorState)
final codeEditorStateProvider = CodeEditorStateProvider._();

final class CodeEditorStateProvider
    extends
        $FunctionalProvider<
          AsyncValue<CodeEditorSnapshot>,
          CodeEditorSnapshot,
          Stream<CodeEditorSnapshot>
        >
    with
        $FutureModifier<CodeEditorSnapshot>,
        $StreamProvider<CodeEditorSnapshot> {
  CodeEditorStateProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'codeEditorStateProvider',
        isAutoDispose: true,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$codeEditorStateHash();

  @$internal
  @override
  $StreamProviderElement<CodeEditorSnapshot> $createElement(
    $ProviderPointer pointer,
  ) => $StreamProviderElement(pointer);

  @override
  Stream<CodeEditorSnapshot> create(Ref ref) {
    return codeEditorState(ref);
  }
}

String _$codeEditorStateHash() => r'957a42f4f0c61cef096f38d9164e430a0574c8cb';
