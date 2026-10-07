import 'package:flutter_test/flutter_test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_host/main.dart';

class _UtilityProbe implements ScriptRuntime {
  @override
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  }) async {
    final md5 = await context.host.call('crypto.md5', ['abc']);
    final hex = await context.host.call('encoding.hexEncode', ['hi']);
    await expectLater(
      context.host.call('java.md5Encode', ['abc']),
      throwsA(
        isA<EngineException>().having(
          (error) => error.code,
          'code',
          'unsupported_host_api',
        ),
      ),
    );
    return [
      {'name': md5, 'hex': hex},
    ];
  }

  @override
  Future<void> close() async {}
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('module composition dispatches utilities and rejects legacy namespace in modern mode', () async {
    final definition = SourceDefinition.fromJson({
      'schemaVersion': 1,
      'id': 'utility',
      'name': 'Utility',
      'baseUrl': 'https://example.org/',
      'script': 'async function search(){}',
    });
    final engine = createSourceEngine(definition, runtime: _UtilityProbe());
    final result = await engine.execute(definition, 'search');
    expect(result.single['name'], '900150983cd24fb0d6963f7d28e17f72');
    expect(result.single['hex'], '6869');
    await engine.close();
  });
}
