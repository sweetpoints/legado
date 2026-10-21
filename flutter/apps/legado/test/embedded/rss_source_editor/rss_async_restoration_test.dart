import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';
import 'package:legado_app/embedded/embedded_source_management.dart';

import 'rss_editor_fixture.dart';

// The SDK TestRestorationManager normally supplies a SynchronousFuture. The
// real Android engine returns root restoration bytes asynchronously, after an
// embedded bootstrap shell may already have rendered without a scope.
class AsyncRestorationBinding extends AutomatedTestWidgetsFlutterBinding {
  @override
  TestRestorationManager createRestorationManager() =>
      AsyncRestorationManager();
}

class AsyncRestorationManager extends TestRestorationManager {
  Completer<void>? gate;
  @override
  Future<RestorationBucket?> get rootBucket {
    final pending = gate;
    if (pending != null) {
      return pending.future.then((_) => super.rootBucket);
    }
    return super.rootBucket;
  }

  void release() {
    final pending = gate;
    gate = null;
    pending?.complete();
  }
}

void main() {
  final binding = AsyncRestorationBinding();
  const bootstrap = MethodChannel('legado/embedded_ui');
  const editor = MethodChannel('legado/rss_source_editor');
  const events = MethodChannel('legado/rss_source_editor/state');
  for (final delayRoot in [false, true]) {
    for (final delayBootstrap in [false, true]) {
      for (final loading in [false, true]) {
        testWidgets(
          'real bootstrap restore root=$delayRoot bootstrap=$delayBootstrap initialLoading=$loading',
          (tester) async {
            tester.view.physicalSize = const Size(320, 640);
            tester.view.devicePixelRatio = 1;
            addTearDown(tester.view.resetPhysicalSize);
            addTearDown(tester.view.resetDevicePixelRatio);
            Map<String, Object?> complete(int generation) {
              final value = rssEditorFixture(generation: generation);
              fieldMap(
                value,
                'sourceName',
              ).addAll({'text': 'RSS fixture', 'length': 11});
              const url =
                  'https://rss-editor-12345678-abcd-abcd-abcd-123456789000.invalid/';
              fieldMap(
                value,
                'sourceUrl',
              ).addAll({'text': url, 'length': url.length});
              return value;
            }

            var native = complete(1);
            Completer<void>? bootstrapGate;
            binding.defaultBinaryMessenger.setMockMethodCallHandler(bootstrap, (
              call,
            ) async {
              await bootstrapGate?.future;
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
                throw StateError('unexpected native mutation');
              }
              return native;
            });
            binding.defaultBinaryMessenger.setMockMethodCallHandler(
              events,
              (_) async => null,
            );
            addTearDown(() {
              for (final c in [bootstrap, editor, events]) {
                binding.defaultBinaryMessenger.setMockMethodCallHandler(
                  c,
                  null,
                );
              }
            });
            await tester.pumpWidget(
              const ProviderScope(child: EmbeddedSourceManagementApp()),
            );
            await tester.pumpAndSettle();
            final target = find.byKey(const ValueKey('rss-field-jsLib'));
            await tester.ensureVisible(target);
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
            final data = await tester.getRestorationData();
            await tester.pumpWidget(const SizedBox());
            await tester.pumpAndSettle();
            final manager =
                binding.restorationManager as AsyncRestorationManager;
            manager.restoreFrom(data);
            if (delayRoot) manager.gate = Completer<void>();
            if (delayBootstrap) bootstrapGate = Completer<void>();
            native = loading
                ? (rssEditorFixture(generation: 2)..addAll({
                    'loading': true,
                    'loaded': false,
                    'canEdit': false,
                  }))
                : complete(2);
            await tester.pumpWidget(
              const ProviderScope(child: EmbeddedSourceManagementApp()),
            );
            await tester.pump();
            await tester.pump(const Duration(milliseconds: 16));
            bootstrapGate?.complete();
            bootstrapGate = null;
            await tester.pump();
            await tester.pump(const Duration(milliseconds: 16));
            if (loading) {
              expect(find.byKey(const ValueKey('rss-fields-0')), findsNothing);
              native = complete(2)..['revision'] = 2;
              await binding.defaultBinaryMessenger.handlePlatformMessage(
                events.name,
                const StandardMethodCodec().encodeSuccessEnvelope(native),
                (_) {},
              );
              await tester.pump();
              await tester.pump(const Duration(milliseconds: 16));
            }
            manager.release();
            await tester.pumpAndSettle();
            expect(offset(), before);
            expect(tester.takeException(), isNull);
          },
        );
      }
    }
  }
}
