// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'pdf_reader_page.dart';

// **************************************************************************
// RiverpodGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint, type=warning

@ProviderFor(pdfReadingSession)
final pdfReadingSessionProvider = PdfReadingSessionFamily._();

final class PdfReadingSessionProvider
    extends
        $FunctionalProvider<
          AsyncValue<PdfReadingSession>,
          PdfReadingSession,
          FutureOr<PdfReadingSession>
        >
    with
        $FutureModifier<PdfReadingSession>,
        $FutureProvider<PdfReadingSession> {
  PdfReadingSessionProvider._({
    required PdfReadingSessionFamily super.from,
    required PdfReaderIdentity super.argument,
  }) : super(
         retry: _noPdfSessionRetry,
         name: r'pdfReadingSessionProvider',
         isAutoDispose: true,
         dependencies: null,
         $allTransitiveDependencies: null,
       );

  @override
  String debugGetCreateSourceHash() => _$pdfReadingSessionHash();

  @override
  String toString() {
    return r'pdfReadingSessionProvider'
        ''
        '($argument)';
  }

  @$internal
  @override
  $FutureProviderElement<PdfReadingSession> $createElement(
    $ProviderPointer pointer,
  ) => $FutureProviderElement(pointer);

  @override
  FutureOr<PdfReadingSession> create(Ref ref) {
    final argument = this.argument as PdfReaderIdentity;
    return pdfReadingSession(ref, argument);
  }

  @override
  bool operator ==(Object other) {
    return other is PdfReadingSessionProvider && other.argument == argument;
  }

  @override
  int get hashCode {
    return argument.hashCode;
  }
}

String _$pdfReadingSessionHash() => r'2be51f1d392601100b9ea83e0c2f7fa9f9d00f2e';

final class PdfReadingSessionFamily extends $Family
    with
        $FunctionalFamilyOverride<
          FutureOr<PdfReadingSession>,
          PdfReaderIdentity
        > {
  PdfReadingSessionFamily._()
    : super(
        retry: _noPdfSessionRetry,
        name: r'pdfReadingSessionProvider',
        dependencies: null,
        $allTransitiveDependencies: null,
        isAutoDispose: true,
      );

  PdfReadingSessionProvider call(PdfReaderIdentity identity) =>
      PdfReadingSessionProvider._(argument: identity, from: this);

  @override
  String toString() => r'pdfReadingSessionProvider';
}
