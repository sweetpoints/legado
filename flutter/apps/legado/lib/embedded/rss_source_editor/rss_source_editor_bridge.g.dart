// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'rss_source_editor_bridge.dart';

// **************************************************************************
// RiverpodGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint, type=warning

@ProviderFor(rssEditorBridge)
final rssEditorBridgeProvider = RssEditorBridgeProvider._();

final class RssEditorBridgeProvider
    extends
        $FunctionalProvider<RssEditorBridge, RssEditorBridge, RssEditorBridge>
    with $Provider<RssEditorBridge> {
  RssEditorBridgeProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'rssEditorBridgeProvider',
        isAutoDispose: true,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$rssEditorBridgeHash();

  @$internal
  @override
  $ProviderElement<RssEditorBridge> $createElement($ProviderPointer pointer) =>
      $ProviderElement(pointer);

  @override
  RssEditorBridge create(Ref ref) {
    return rssEditorBridge(ref);
  }

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(RssEditorBridge value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<RssEditorBridge>(value),
    );
  }
}

String _$rssEditorBridgeHash() => r'f3a46ed8cf0bf95376af7e07f08d474bfc07331c';

@ProviderFor(rssEditorState)
final rssEditorStateProvider = RssEditorStateProvider._();

final class RssEditorStateProvider
    extends
        $FunctionalProvider<
          AsyncValue<RssEditorSnapshot>,
          RssEditorSnapshot,
          Stream<RssEditorSnapshot>
        >
    with
        $FutureModifier<RssEditorSnapshot>,
        $StreamProvider<RssEditorSnapshot> {
  RssEditorStateProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'rssEditorStateProvider',
        isAutoDispose: true,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$rssEditorStateHash();

  @$internal
  @override
  $StreamProviderElement<RssEditorSnapshot> $createElement(
    $ProviderPointer pointer,
  ) => $StreamProviderElement(pointer);

  @override
  Stream<RssEditorSnapshot> create(Ref ref) {
    return rssEditorState(ref);
  }
}

String _$rssEditorStateHash() => r'8f5225c8b4099deb88a72c2ab1632074fd10d708';
