import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:legado_app/embedded/rule_subscriptions/rule_subscription_bridge.dart';
import 'package:legado_app/embedded/rule_subscriptions/rule_subscription_page.dart';
import 'package:legado_app/l10n/generated/app_localizations.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('test/subscription-ui');
  late StreamController<Object?> events;
  late RuleSubscriptionBridge bridge;
  late Map<String, Object?> native;
  late List<String> operations;
  var sequence = 0;
  setUp(() async {
    events = StreamController<Object?>();
    sequence = 0;
    operations = [];
    native = {
      'generation': 'owner',
      'revision': 0,
      'sequence': ++sequence,
      'loaded': true,
      'loading': false,
      'busy': false,
      'closed': false,
      'pendingSave': false,
      'issue': null,
      'rows': [
        for (var i = 0; i < 3; i++)
          {
            'id': '$i',
            'name': 'Source $i',
            'url': 'https://example.test/$i',
            'type': i,
          },
      ],
      'editor': null,
    };
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'command') {
            operations.add((call.arguments as Map)['op'] as String);
            native['revision'] = (native['revision'] as int) + 1;
          }
          native['sequence'] = ++sequence;
          return Map<String, Object?>.from(native);
        });
    bridge = RuleSubscriptionBridge(methods: channel, events: events.stream);
    await bridge.load();
  });
  tearDown(() async {
    await bridge.close();
    await events.close();
  });
  Future<void> pump(WidgetTester tester, String locale) async {
    await tester.pumpWidget(
      MaterialApp(
        restorationScopeId: 'subscriptions-test',
        locale: Locale(locale),
        supportedLocales: AppLocalizations.supportedLocales,
        localizationsDelegates: [
          AppLocalizations.delegate,
          ...GlobalMaterialLocalizations.delegates,
        ],
        home: RuleSubscriptionPage(bridge: bridge),
      ),
    );
    await tester.pumpAndSettle();
  }

  for (final locale in ['en', 'zh']) {
    testWidgets('all three types and native import handoff in $locale', (
      tester,
    ) async {
      await pump(tester, locale);
      expect(find.text('Source 0'), findsOneWidget);
      expect(
        find.textContaining(locale == 'en' ? 'Book sources' : '书源'),
        findsOneWidget,
      );
      expect(
        find.textContaining(locale == 'en' ? 'RSS sources' : '订阅源'),
        findsOneWidget,
      );
      expect(
        find.textContaining(locale == 'en' ? 'Replacement rules' : '替换规则'),
        findsOneWidget,
      );
      expect(
        tester
            .widget<ListTile>(
              find.ancestor(
                of: find.text('Source 1'),
                matching: find.byType(ListTile),
              ),
            )
            .enabled,
        true,
      );
      await tester.tap(find.text('Source 1'));
      await tester.pumpAndSettle();
      await tester.runAsync(() async {
        await Future<void>.delayed(Duration.zero);
      });
      expect(operations, ['open']);
    });
  }
  testWidgets(
    'pending native receipt locks fields but permits save retry and cancel',
    (tester) async {
      native['editor'] = {
        'id': 'draft',
        'name': 'Pending',
        'url': 'https://example.test',
        'type': 2,
        'automatic': false,
        'interval': '0',
        'silent': false,
        'silentEnabled': false,
      };
      native['pendingSave'] = true;
      await bridge.load();
      await pump(tester, 'en');
      final fields = tester
          .widgetList<TextField>(find.byType(TextField))
          .toList();
      expect(fields.length, 3);
      expect(fields.every((field) => field.enabled == false), true);
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      await tester.runAsync(() async {
        await Future<void>.delayed(Duration.zero);
      });
      expect(operations, ['save']);
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      expect(operations, ['save', 'cancel']);
    },
  );
}
