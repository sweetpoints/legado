// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'reader_state.dart';

// **************************************************************************
// RiverpodGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint, type=warning
/// Each reader route owns an immutable session. Layout objects stay in the UI.

@ProviderFor(ReaderStateController)
final readerStateProvider = ReaderStateControllerFamily._();

/// Each reader route owns an immutable session. Layout objects stay in the UI.
final class ReaderStateControllerProvider
    extends $NotifierProvider<ReaderStateController, ReaderViewState> {
  /// Each reader route owns an immutable session. Layout objects stay in the UI.
  ReaderStateControllerProvider._({
    required ReaderStateControllerFamily super.from,
    required ReaderIdentity super.argument,
  }) : super(
         retry: null,
         name: r'readerStateProvider',
         isAutoDispose: true,
         dependencies: null,
         $allTransitiveDependencies: null,
       );

  @override
  String debugGetCreateSourceHash() => _$readerStateControllerHash();

  @override
  String toString() {
    return r'readerStateProvider'
        ''
        '($argument)';
  }

  @$internal
  @override
  ReaderStateController create() => ReaderStateController();

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(ReaderViewState value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<ReaderViewState>(value),
    );
  }

  @override
  bool operator ==(Object other) {
    return other is ReaderStateControllerProvider && other.argument == argument;
  }

  @override
  int get hashCode {
    return argument.hashCode;
  }
}

String _$readerStateControllerHash() =>
    r'2071343f84e8ca0fffcc968b26736f78e03036fe';

/// Each reader route owns an immutable session. Layout objects stay in the UI.

final class ReaderStateControllerFamily extends $Family
    with
        $ClassFamilyOverride<
          ReaderStateController,
          ReaderViewState,
          ReaderViewState,
          ReaderViewState,
          ReaderIdentity
        > {
  ReaderStateControllerFamily._()
    : super(
        retry: null,
        name: r'readerStateProvider',
        dependencies: null,
        $allTransitiveDependencies: null,
        isAutoDispose: true,
      );

  /// Each reader route owns an immutable session. Layout objects stay in the UI.

  ReaderStateControllerProvider call(ReaderIdentity identity) =>
      ReaderStateControllerProvider._(argument: identity, from: this);

  @override
  String toString() => r'readerStateProvider';
}

/// Each reader route owns an immutable session. Layout objects stay in the UI.

abstract class _$ReaderStateController extends $Notifier<ReaderViewState> {
  late final _$args = ref.$arg as ReaderIdentity;
  ReaderIdentity get identity => _$args;

  ReaderViewState build(ReaderIdentity identity);
  @$mustCallSuper
  @override
  WhenComplete runBuild() {
    final ref = this.ref as $Ref<ReaderViewState, ReaderViewState>;
    final element =
        ref.element
            as $ClassProviderElement<
              AnyNotifier<ReaderViewState, ReaderViewState>,
              ReaderViewState,
              Object?,
              Object?
            >;
    return element.handleCreate(ref, () => build(_$args));
  }
}
