// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'source_management_backend.dart';

// **************************************************************************
// RiverpodGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint, type=warning

@ProviderFor(sourceManagementBackend)
final sourceManagementBackendProvider = SourceManagementBackendFamily._();

final class SourceManagementBackendProvider
    extends
        $FunctionalProvider<
          SourceManagementBackend,
          SourceManagementBackend,
          SourceManagementBackend
        >
    with $Provider<SourceManagementBackend> {
  SourceManagementBackendProvider._({
    required SourceManagementBackendFamily super.from,
    required SourceManagementBinding super.argument,
  }) : super(
         retry: null,
         name: r'sourceManagementBackendProvider',
         isAutoDispose: true,
         dependencies: null,
         $allTransitiveDependencies: null,
       );

  @override
  String debugGetCreateSourceHash() => _$sourceManagementBackendHash();

  @override
  String toString() {
    return r'sourceManagementBackendProvider'
        ''
        '($argument)';
  }

  @$internal
  @override
  $ProviderElement<SourceManagementBackend> $createElement(
    $ProviderPointer pointer,
  ) => $ProviderElement(pointer);

  @override
  SourceManagementBackend create(Ref ref) {
    final argument = this.argument as SourceManagementBinding;
    return sourceManagementBackend(ref, argument);
  }

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(SourceManagementBackend value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<SourceManagementBackend>(value),
    );
  }

  @override
  bool operator ==(Object other) {
    return other is SourceManagementBackendProvider &&
        other.argument == argument;
  }

  @override
  int get hashCode {
    return argument.hashCode;
  }
}

String _$sourceManagementBackendHash() =>
    r'554d8fb9a6111575b0eff38d250350b7256794a5';

final class SourceManagementBackendFamily extends $Family
    with
        $FunctionalFamilyOverride<
          SourceManagementBackend,
          SourceManagementBinding
        > {
  SourceManagementBackendFamily._()
    : super(
        retry: null,
        name: r'sourceManagementBackendProvider',
        dependencies: null,
        $allTransitiveDependencies: null,
        isAutoDispose: true,
      );

  SourceManagementBackendProvider call(SourceManagementBinding binding) =>
      SourceManagementBackendProvider._(argument: binding, from: this);

  @override
  String toString() => r'sourceManagementBackendProvider';
}

@ProviderFor(sourceManagementList)
final sourceManagementListProvider = SourceManagementListFamily._();

final class SourceManagementListProvider
    extends
        $FunctionalProvider<
          AsyncValue<List<Map<String, Object?>>>,
          List<Map<String, Object?>>,
          Stream<List<Map<String, Object?>>>
        >
    with
        $FutureModifier<List<Map<String, Object?>>>,
        $StreamProvider<List<Map<String, Object?>>> {
  SourceManagementListProvider._({
    required SourceManagementListFamily super.from,
    required SourceManagementBinding super.argument,
  }) : super(
         retry: null,
         name: r'sourceManagementListProvider',
         isAutoDispose: true,
         dependencies: null,
         $allTransitiveDependencies: null,
       );

  @override
  String debugGetCreateSourceHash() => _$sourceManagementListHash();

  @override
  String toString() {
    return r'sourceManagementListProvider'
        ''
        '($argument)';
  }

  @$internal
  @override
  $StreamProviderElement<List<Map<String, Object?>>> $createElement(
    $ProviderPointer pointer,
  ) => $StreamProviderElement(pointer);

  @override
  Stream<List<Map<String, Object?>>> create(Ref ref) {
    final argument = this.argument as SourceManagementBinding;
    return sourceManagementList(ref, argument);
  }

  @override
  bool operator ==(Object other) {
    return other is SourceManagementListProvider && other.argument == argument;
  }

  @override
  int get hashCode {
    return argument.hashCode;
  }
}

String _$sourceManagementListHash() =>
    r'87030b9b0f22206b42fb754437200b895d6548e4';

final class SourceManagementListFamily extends $Family
    with
        $FunctionalFamilyOverride<
          Stream<List<Map<String, Object?>>>,
          SourceManagementBinding
        > {
  SourceManagementListFamily._()
    : super(
        retry: null,
        name: r'sourceManagementListProvider',
        dependencies: null,
        $allTransitiveDependencies: null,
        isAutoDispose: true,
      );

  SourceManagementListProvider call(SourceManagementBinding binding) =>
      SourceManagementListProvider._(argument: binding, from: this);

  @override
  String toString() => r'sourceManagementListProvider';
}
