// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'file_exchange.dart';

// **************************************************************************
// RiverpodGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint, type=warning

@ProviderFor(fileExchange)
final fileExchangeProvider = FileExchangeProvider._();

final class FileExchangeProvider
    extends $FunctionalProvider<FileExchange, FileExchange, FileExchange>
    with $Provider<FileExchange> {
  FileExchangeProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'fileExchangeProvider',
        isAutoDispose: false,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$fileExchangeHash();

  @$internal
  @override
  $ProviderElement<FileExchange> $createElement($ProviderPointer pointer) =>
      $ProviderElement(pointer);

  @override
  FileExchange create(Ref ref) {
    return fileExchange(ref);
  }

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(FileExchange value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<FileExchange>(value),
    );
  }
}

String _$fileExchangeHash() => r'8c3b2c10ec13adf7b4fa19a28186515da17a452c';
