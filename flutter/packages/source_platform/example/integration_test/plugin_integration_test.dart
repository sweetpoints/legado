import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:source_platform/source_platform.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets('storage remains isolated between sources', (tester) async {
    const plugin = SourcePlatform(sourceId: 'https://a.example');
    await plugin.write('https://a.example', 'integration', 'value');
    expect(await plugin.read('https://a.example', 'integration'), 'value');
    expect(await plugin.read('https://b.example', 'integration'), isNull);
    await plugin.write('https://a.example', 'integration', null);
  });
}
