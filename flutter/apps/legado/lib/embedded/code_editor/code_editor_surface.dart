import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter/rendering.dart' show PlatformViewHitTestBehavior;
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../l10n/generated/app_localizations.dart';
part 'code_editor_surface.g.dart';

typedef CodeEditorSurfaceBuilder = Widget Function(
  BuildContext context,
  int generation,
);

/// The native surface is isolated for widget tests; production always builds the
/// registered Android view, with no full-document Dart model or text fallback.
@riverpod
CodeEditorSurfaceBuilder codeEditorSurfaceBuilder(Ref ref) =>
    (context, generation) => CodeEditorSurface(generation: generation);

class CodeEditorSurface extends StatelessWidget {
  const CodeEditorSurface({super.key, required this.generation});
  final int generation;
  @override
  Widget build(BuildContext context) {
    if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) {
      return Center(
        child: Text(AppLocalizations.of(context).codeEditorPlatformUnavailable),
      );
    }
    return PlatformViewLink(
      key: ValueKey(generation),
      viewType: 'legado/code_editor_surface',
      surfaceFactory: (context, controller) => AndroidViewSurface(
        controller: controller as AndroidViewController,
        hitTestBehavior: PlatformViewHitTestBehavior.opaque,
        gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{},
      ),
      onCreatePlatformView: (params) {
        // Traditional Hybrid Composition preserves native editor accessibility,
        // text selection handles and IME on the complete supported Android range.
        final controller = PlatformViewsService.initExpensiveAndroidView(
          id: params.id,
          viewType: 'legado/code_editor_surface',
          layoutDirection: Directionality.of(context),
          creationParams: {'generation': generation},
          creationParamsCodec: const StandardMessageCodec(),
          onFocus: () => params.onFocusChanged(true),
        );
        controller.addOnPlatformViewCreatedListener(
          params.onPlatformViewCreated,
        );
        unawaited(controller.create());
        return controller;
      },
    );
  }
}
