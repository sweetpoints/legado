import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:source_host/source_host.dart';
import 'package:source_host/session_store.dart';

class _ForbiddenStore implements SourceSessionStore {
  @override
  Future<Map<String, Object?>?> read(String sourceId, bool legacy) async =>
      throw StateError('migration must not read sessions');
  @override
  Future<void> write(
    String sourceId,
    bool legacy,
    Map<String, Object?> session,
  ) async => throw StateError('migration must not write sessions');
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  Map<String, Object?> fixture() => {
    'bookSourceUrl': 'https://fixture.example',
    'bookSourceName': 'Migration fixture',
    'searchUrl': '/search?q={{key}}',
    'ruleSearch': {
      'bookList': 'tag.article',
      'name': 'tag.h2@text',
      'bookUrl': 'tag.a@href',
    },
    'customOriginal': {
      'preserve': ['exact', 2, false],
    },
  };
  SourceHost host() => SourceHost(
    (_) => throw StateError('migration must not create an engine'),
    sessionStore: _ForbiddenStore(),
  );
  MethodCall migrate(Object? json, {Object? version = 1}) =>
      MethodCall('migrate', {'protocolVersion': version, 'sourceJson': json});
  test('offline migrate returns preserved original and unverified candidate without runtime/session work', () async {
    final instance = host();
    try {
      final input = fixture();
      final before = jsonEncode(input);
      final report = await instance.handle(migrate(before)) as Map;
      expect(report['protocolVersion'], 1);
      expect(report['reportVersion'], 1);
      expect(report['status'], 'unverified');
      expect(report['verified'], false);
      expect(report['executed'], false);
      expect(report['requiresManualWork'], false);
      expect(report['issues'], isEmpty);
      expect(report['original'], input);
      expect(jsonEncode(input), before);
      final candidate = report['candidate'] as Map;
      expect(candidate['schemaVersion'], 1);
      expect((candidate['metadata'] as Map)['compatibility'], 'unverified');
      expect((candidate['metadata'] as Map)['legacy'], false);
      expect((candidate['stages'] as Map)['search'], isA<Map>());
      // Return data uses only the channel codec's supported JSON value types.
      const StandardMethodCodec().encodeSuccessEnvelope(report);
    } finally {
      await instance.close();
    }
  });
  test('unsupported legacy capabilities are a manual review report, not applied or thrown', () async {
    final instance = host();
    try {
      final raw = fixture()..['loginUrl'] = 'https://fixture.example/login';
      final report = await instance.handle(migrate(jsonEncode(raw))) as Map;
      expect(report['status'], 'manualRequired');
      expect(report['requiresManualWork'], true);
      expect(report['verified'], false);
      expect(report['executed'], false);
      expect(report['original'], raw);
      expect(report['candidate'], isA<Map>());
      final issues = report['issues'] as List;
      expect(issues, isNotEmpty);
      expect(
        issues.every(
          (e) =>
              e is Map &&
              e['path'] is String &&
              e['code'] is String &&
              e['message'] is String,
        ),
        true,
      );
      expect(
        ((report['candidate'] as Map)['metadata'] as Map)['compatibility'],
        'manualRequired',
      );
    } finally {
      await instance.close();
    }
  });
  test('invalid protocol or source is rejected without runtime and closed host stays closed', () async {
    final instance = host();
    for (final request in [
      migrate(jsonEncode(fixture()), version: 2),
      migrate(null),
    ]) {
      await expectLater(
        instance.handle(request),
        throwsA(
          isA<PlatformException>().having(
            (e) => e.code,
            'code',
            'invalid_request',
          ),
        ),
      );
    }
    for (final input in [
      '{broken',
      '[]',
      '{}',
      jsonEncode({'schemaVersion': 1}),
      jsonEncode(fixture()..['bookSourceUrl'] = 42),
    ]) {
      await expectLater(
        instance.handle(migrate(input)),
        throwsA(
          isA<PlatformException>().having(
            (e) => e.code,
            'code',
            'invalid_source',
          ),
        ),
      );
    }
    await instance.close();
    await expectLater(
      instance.handle(migrate(jsonEncode(fixture()))),
      throwsA(
        isA<PlatformException>().having((e) => e.code, 'code', 'host_closed'),
      ),
    );
  });
}
