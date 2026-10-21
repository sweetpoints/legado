import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';
import 'package:legado_app/embedded/embedded_source_management.dart';

import 'rss_editor_fixture.dart';

void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  const bootstrap = MethodChannel('legado/embedded_ui');
  const editor = MethodChannel('legado/rss_source_editor');
  const events = MethodChannel('legado/rss_source_editor/state');

  testWidgets(
    'real embedded router restores RSS viewport for a new native binding',
    (tester) async {
      tester.view.physicalSize = const Size(1100, 450);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      var generation = 1;
      final bootstrapCalls = <String>[];
      binding.defaultBinaryMessenger.setMockMethodCallHandler(bootstrap, (
        call,
      ) async {
        bootstrapCalls.add(call.method);
        if (call.method != 'bootstrap') {
          throw StateError('Unexpected repository access');
        }
        return {
          'route': '/rss-source-editor',
          'locale': 'en',
          'theme': 'light',
        };
      });
      binding.defaultBinaryMessenger.setMockMethodCallHandler(editor, (
        call,
      ) async {
        if (call.method != 'snapshot') {
          throw StateError('Unexpected draft mutation');
        }
        return rssEditorFixture(generation: generation);
      });
      binding.defaultBinaryMessenger.setMockMethodCallHandler(
        events,
        (_) async => null,
      );
      addTearDown(() {
        for (final channel in [bootstrap, editor, events]) {
          binding.defaultBinaryMessenger.setMockMethodCallHandler(
            channel,
            null,
          );
        }
      });
      await tester.pumpWidget(
        const ProviderScope(child: EmbeddedSourceManagementApp()),
      );
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.byKey(const ValueKey('rss-field-jsLib')));
      await tester.pumpAndSettle();
      double offset() => tester
          .widget<SingleChildScrollView>(
            find
                .descendant(
                  of: find.byKey(const ValueKey('rss-fields-0')),
                  matching: find.byType(SingleChildScrollView),
                )
                .first,
          )
          .controller!
          .offset;
      final before = offset();
      expect(before, greaterThan(0));
      generation = 2;
      await tester.restartAndRestore();
      await tester.pumpAndSettle();
      expect(offset(), before);
      expect(bootstrapCalls, everyElement('bootstrap'));
      expect(tester.takeException(), isNull);
    },
  );
}
