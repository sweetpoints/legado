import 'package:source_engine/source_engine.dart';
import 'package:test/test.dart';

import 'engine_test.dart' show NoScripts;

class RecordingNetwork extends NetworkClient {
  final calls = <Uri>[];
  @override
  Future<NetworkResponse> request(
    Uri uri, {
    String method = 'GET',
    Map<String, String> headers = const {},
    String? body,
    String? charset,
    Duration timeout = const Duration(seconds: 30),
    CancellationToken? cancellation,
    int maxRedirects = 5,
    bool followRedirects = true,
  }) async {
    calls.add(uri);
    return NetworkResponse(uri, 200, {}, '<p>content</p>');
  }
}

void main() {
  SourceDefinition source(String url, {bool marked = true}) => SourceDefinition(
    id: 'legacy-key',
    name: 'Legacy',
    baseUrl: Uri.parse('https://inferred.example/search'),
    metadata: {if (marked) 'legacyBaseUrlUnavailable': true},
    stages: {
      'content': SourceStage(url: url, fields: {'content': '@css:p@text'}),
    },
  );
  test(
    'legacy nonHTTP ID cannot infer host for relative stage requests',
    () async {
      final network = RecordingNetwork();
      final engine = SourceEngine(runtime: NoScripts(), network: network);
      try {
        for (final url in [
          '/chapter',
          'chapter',
          '//other.example/chapter',
          '',
          'file:///chapter',
        ]) {
          await expectLater(
            engine.execute(source(url), 'content'),
            throwsA(
              isA<EngineException>().having(
                (e) => e.code,
                'code',
                'legacy_base_url_required',
              ),
            ),
          );
        }
        await expectLater(
          engine.execute(
            source('{{chapterUrl}}'),
            'content',
            input: {'chapterUrl': '/chapter'},
          ),
          throwsA(
            isA<EngineException>().having(
              (e) => e.code,
              'code',
              'legacy_base_url_required',
            ),
          ),
        );
        expect(network.calls, isEmpty);
      } finally {
        await engine.close();
      }
    },
  );
  test(
    'explicit HTTP targets remain executable after template substitution',
    () async {
      final network = RecordingNetwork();
      final engine = SourceEngine(runtime: NoScripts(), network: network);
      try {
        expect(
          await engine.execute(
            source('{{chapterUrl}}'),
            'content',
            input: {'chapterUrl': 'https://actual.example/chapter#part'},
          ),
          [
            {'content': 'content'},
          ],
        );
        expect(
          network.calls.single.toString(),
          'https://actual.example/chapter#part',
        );
      } finally {
        await engine.close();
      }
    },
  );
  test(
    'modern and normal HTTP legacy sources keep relative resolution',
    () async {
      final network = RecordingNetwork();
      final engine = SourceEngine(runtime: NoScripts(), network: network);
      try {
        expect(
          await engine.execute(source('/chapter', marked: false), 'content'),
          [
            {'content': 'content'},
          ],
        );
        expect(
          network.calls.single.toString(),
          'https://inferred.example/chapter',
        );
      } finally {
        await engine.close();
      }
    },
  );
}
