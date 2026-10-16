// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'bookshelf_page.dart';

// **************************************************************************
// RiverpodGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint, type=warning

@ProviderFor(ImportingTextController)
final importingTextProvider = ImportingTextControllerProvider._();

final class ImportingTextControllerProvider
    extends $NotifierProvider<ImportingTextController, bool> {
  ImportingTextControllerProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'importingTextProvider',
        isAutoDispose: false,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$importingTextControllerHash();

  @$internal
  @override
  ImportingTextController create() => ImportingTextController();

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(bool value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<bool>(value),
    );
  }
}

String _$importingTextControllerHash() =>
    r'39725513b182c7e2162596785f3dc522962191c8';

abstract class _$ImportingTextController extends $Notifier<bool> {
  bool build();
  @$mustCallSuper
  @override
  WhenComplete runBuild() {
    final ref = this.ref as $Ref<bool, bool>;
    final element =
        ref.element
            as $ClassProviderElement<
              AnyNotifier<bool, bool>,
              bool,
              Object?,
              Object?
            >;
    return element.handleCreate(ref, build);
  }
}
