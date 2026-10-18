// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'code_editor_surface.dart';

// **************************************************************************
// RiverpodGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint, type=warning
/// The native surface is isolated for widget tests; production always builds the
/// registered Android view, with no full-document Dart model or text fallback.

@ProviderFor(codeEditorSurfaceBuilder)
final codeEditorSurfaceBuilderProvider = CodeEditorSurfaceBuilderProvider._();

/// The native surface is isolated for widget tests; production always builds the
/// registered Android view, with no full-document Dart model or text fallback.

final class CodeEditorSurfaceBuilderProvider
    extends
        $FunctionalProvider<
          CodeEditorSurfaceBuilder,
          CodeEditorSurfaceBuilder,
          CodeEditorSurfaceBuilder
        >
    with $Provider<CodeEditorSurfaceBuilder> {
  /// The native surface is isolated for widget tests; production always builds the
  /// registered Android view, with no full-document Dart model or text fallback.
  CodeEditorSurfaceBuilderProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'codeEditorSurfaceBuilderProvider',
        isAutoDispose: true,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$codeEditorSurfaceBuilderHash();

  @$internal
  @override
  $ProviderElement<CodeEditorSurfaceBuilder> $createElement(
    $ProviderPointer pointer,
  ) => $ProviderElement(pointer);

  @override
  CodeEditorSurfaceBuilder create(Ref ref) {
    return codeEditorSurfaceBuilder(ref);
  }

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(CodeEditorSurfaceBuilder value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<CodeEditorSurfaceBuilder>(value),
    );
  }
}

String _$codeEditorSurfaceBuilderHash() =>
    r'e6f8df1d966f94c3e31a7ce67fd26be891648212';
