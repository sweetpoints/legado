import 'package:source_engine/source_engine.dart';
import 'package:source_migration/source_migration.dart';
import 'package:source_tools/source_tools.dart';
import 'package:test/test.dart';

/// Injected runtime checks assembly/host behavior, not V8 execution.
class UtilityProbe implements ScriptRuntime {
  UtilityProbe(this.method, this.arguments, {required this.expectedCode});
  final String method;
  final List<Object?> arguments;
  final String expectedCode;
  bool closed = false;
  @override
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  }) async {
    expect(code, contains(expectedCode));
    return [
      {'value': await context.host.call(method, arguments)},
    ];
  }

  @override
  Future<void> close() async {
    closed = true;
  }
}

void main() {
  test('migrated modern utility candidate resolves crypto API through CLI platform', () async {
    final migration = SourceMigrator().migrateScript('java.md5Encode("book")');
    expect(migration.requiresManualWork, false);
    expect(migration.candidate, contains('source.crypto.md5'));
    final source = SourceDefinition(
      id: 'candidate',
      name: 'Candidate',
      baseUrl: Uri.parse('https://example.org'),
      script:
          'async function getContent(){return [{value: ${migration.candidate}}];}',
    );
    final runtime = UtilityProbe('crypto.md5', [
      'book',
    ], expectedCode: 'source.crypto.md5');
    final engine = createCliEngine(source, runtime);
    try {
      final result = await engine.execute(source, 'content');
      expect(result.single['value'], '821f03288846297c2cf43c34766a38f7');
    } finally {
      await engine.close();
    }
    expect(runtime.closed, true);
  });
  test('legacy mode installs java compatibility host while modern refuses java API', () async {
    for (final legacy in [true, false]) {
      final source = SourceDefinition(
        id: 'probe',
        name: 'Probe',
        baseUrl: Uri.parse('https://example.org'),
        metadata: {'legacy': legacy},
        script: 'function getContent(){return java.md5Encode("book");}',
      );
      final runtime = UtilityProbe('java.md5Encode', [
        'book',
      ], expectedCode: 'java.md5Encode');
      final engine = createCliEngine(source, runtime);
      try {
        if (legacy) {
          expect(
            (await engine.execute(source, 'content')).single['value'],
            '821f03288846297c2cf43c34766a38f7',
          );
        } else {
          await expectLater(
            engine.execute(source, 'content'),
            throwsA(isA<EngineException>()),
          );
        }
      } finally {
        await engine.close();
      }
    }
  });
}
