// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'backup_page.dart';

// **************************************************************************
// RiverpodGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint, type=warning

@ProviderFor(BackupOperationController)
final backupOperationProvider = BackupOperationControllerProvider._();

final class BackupOperationControllerProvider
    extends $NotifierProvider<BackupOperationController, bool> {
  BackupOperationControllerProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'backupOperationProvider',
        isAutoDispose: false,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$backupOperationControllerHash();

  @$internal
  @override
  BackupOperationController create() => BackupOperationController();

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(bool value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<bool>(value),
    );
  }
}

String _$backupOperationControllerHash() =>
    r'd58a7195033f9ac1f98ec547361a23dc442c0ba6';

abstract class _$BackupOperationController extends $Notifier<bool> {
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
