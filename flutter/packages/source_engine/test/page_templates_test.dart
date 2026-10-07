import 'package:source_engine/source_engine.dart';
import 'package:test/test.dart';

import 'engine_test.dart' show NoScripts;
import 'legacy_base_guard_test.dart' show RecordingNetwork;

void main() {
  final migrationError = throwsA(
    isA<EngineException>().having(
      (e) => e.code,
      'code',
      'legacy_page_requires_migration',
    ),
  );
  test('legacy page selection and arithmetic old truth values', () {
    const template =
        '/<\u0000 first \t, second ,last>/?p={{page + 1}}&prev={{page-2}}&q={{key}}';
    expect(
      expandLegacyPageTemplate(template, {'page': 1}, urlChoices: true),
      '/first/?p=2&prev=-1&q={{key}}',
    );
    expect(
      expandLegacyPageTemplate(template, {'page': 2}, urlChoices: true),
      '/second/?p=3&prev=0&q={{key}}',
    );
    expect(
      expandLegacyPageTemplate(template, {'page': 3}, urlChoices: true),
      '/last/?p=4&prev=1&q={{key}}',
    );
    expect(
      expandLegacyPageTemplate(template, {'page': 9}, urlChoices: true),
      '/last/?p=10&prev=7&q={{key}}',
    );
    expect(
      expandLegacyPageTemplate('<\u00a0a\u00a0,b>', {
        'page': 1,
      }, urlChoices: true),
      '\u00a0a\u00a0',
    );
    expect(expandLegacyPageTemplate('<a,b>{{page+0}}', {'page': 1}), '<a,b>1');
  });
  test('unsafe page values and expressions rejected explicitly', () {
    for (final value in [null, 0, -1, '2', 2.0, 9007199254740992]) {
      expect(
        () => expandLegacyPageTemplate('{{page+1}}', {'page': value}),
        migrationError,
      );
      expect(
        () => expandLegacyPageTemplate('<a,b>', {
          'page': value,
        }, urlChoices: true),
        migrationError,
      );
    }
    for (final expression in [
      'page+01',
      'page*2',
      'page + -1',
      'page+9007199254740992',
      'java.ajax("x")',
      'page+1',
      '{{page',
    ]) {
      final template = expression == '{{page' ? expression : '{{$expression}}';
      final page = expression == 'page+1' ? 9007199254740991 : 1;
      expect(
        () => expandLegacyPageTemplate(template, {'page': page}),
        migrationError,
      );
    }
    expect(
      expandLegacyPageTemplate('{{page-9007199254740991}}', {'page': 1}),
      '-9007199254740990',
    );
  });
  SourceDefinition source(SourceStage stage) => SourceDefinition(
    id: 'pages',
    name: 'Pages',
    baseUrl: Uri.parse('https://example.org'),
    stages: {'search': stage},
  );
  test('flag JSON roundtrip strict boolean and adapter input validation', () {
    final config = source(
      const SourceStage(
        url: '/',
        legacyPageTemplates: true,
        legacyRequestInput: 'exploreUrl',
      ),
    );
    expect(
      SourceDefinition.fromJson(config.toJson()).toJson(),
      config.toJson(),
    );
    expect(SourceStage.fromJson({'url': '/'}).legacyPageTemplates, false);
    for (final value in [null, 'true', 1]) {
      expect(
        () => SourceStage.fromJson({'url': '/', 'legacyPageTemplates': value}),
        throwsA(
          isA<EngineException>().having(
            (e) => e.code,
            'code',
            'invalid_source',
          ),
        ),
      );
    }
    expect(
      () => source(const SourceStage(url: '/', legacyRequestInput: 'key')),
      throwsA(
        isA<EngineException>().having((e) => e.code, 'code', 'invalid_source'),
      ),
    );
  });
  test(
    'engine resolves legacy page templates before network; modern stays raw',
    () async {
      final network = RecordingNetwork();
      final engine = SourceEngine(runtime: NoScripts(), network: network);
      try {
        final config = source(
          const SourceStage(
            url: '/<first,other>?p={{page+1}}&q={{key}}',
            legacyPageTemplates: true,
          ),
        );
        await engine.execute(
          config,
          'search',
          input: {'page': 2, 'key': 'a b'},
        );
        expect(
          network.calls.single.toString(),
          'https://example.org/other?p=3&q=a%20b',
        );
        await expectLater(
          engine.execute(config, 'search', input: {'page': 0, 'key': 'a'}),
          migrationError,
        );
        await expectLater(
          engine.execute(
            source(
              const SourceStage(url: '/{{page*2}}', legacyPageTemplates: true),
            ),
            'search',
            input: {'page': 2},
          ),
          migrationError,
        );
        expect(network.calls, hasLength(1));
        await engine.execute(
          source(const SourceStage(url: '/{{page+1}}')),
          'search',
          input: {'page': 2},
        );
        expect(network.calls.last.path, '/%7B%7Bpage+1%7D%7D');
      } finally {
        await engine.close();
      }
    },
  );
  test(
    'body page expansion preserves legacy JSON guard and encoding',
    () async {
      final network = RecordingNetwork();
      final engine = SourceEngine(runtime: NoScripts(), network: network);
      try {
        final config = source(
          const SourceStage(
            url: '/post',
            method: 'POST',
            body: 'q={{key}}&page={{page+1}}',
            legacyPageTemplates: true,
            bodyEncoding: 'legacyFormUtf8',
            bodyTemplateMode: 'legacyJsonString',
          ),
        );
        await expectLater(
          engine.execute(config, 'search', input: {'page': 2, 'key': 'a"b'}),
          throwsA(
            isA<EngineException>().having(
              (e) => e.code,
              'code',
              'legacy_body_template_requires_migration',
            ),
          ),
        );
        await expectLater(
          engine.execute(config, 'search', input: {'page': 2.0, 'key': 'safe'}),
          migrationError,
        );
        expect(network.calls, isEmpty);
        expect(
          encodeLegacyFormUtf8Body(
            expandLegacyPageTemplate('page={{page+1}}&choice=<a,b>', {
              'page': 2,
            }),
          ),
          'page=3&choice=%3Ca%2Cb%3E',
        );
        await engine.execute(
          config,
          'search',
          input: {'page': 2, 'key': 'safe'},
        );
        expect(network.calls, hasLength(1));
      } finally {
        await engine.close();
      }
    },
  );
  test('request adapter required and resolves before page expansion', () async {
    final network = RecordingNetwork();
    final config = source(
      const SourceStage(
        url: '{{exploreUrl}}',
        legacyRequestInput: 'exploreUrl',
      ),
    );
    final missing = SourceEngine(runtime: NoScripts(), network: network);
    await expectLater(
      missing.execute(config, 'search'),
      throwsA(
        isA<EngineException>().having(
          (e) => e.code,
          'code',
          'legacy_request_adapter_required',
        ),
      ),
    );
    await missing.close();
    final engine = SourceEngine(
      runtime: NoScripts(),
      network: network,
      requestAdapter: (source, stage, input) => SourceStage(
        url: input['exploreUrl'] as String,
        legacyPageTemplates: true,
        fields: stage.fields,
      ),
    );
    try {
      await engine.execute(
        config,
        'search',
        input: {'exploreUrl': '/<one,two>?p={{page-1}}', 'page': 2},
      );
      expect(network.calls.single.toString(), 'https://example.org/two?p=1');
    } finally {
      await engine.close();
    }
  });
  test(
    'choice parsing rejects interpolation, nesting and incomplete brackets',
    () {
      for (final template in [
        '/<{{key}},fallback>',
        '/<{{page+1}},fallback>',
        '/<a,<b,c>>',
        '/<a,b',
        '/<single>',
        '/a>',
      ]) {
        expect(
          () => expandLegacyPageTemplate(template, {
            'page': 2,
            'key': 'a,b',
          }, urlChoices: true),
          migrationError,
        );
      }
    },
  );
  test(
    'dynamic angles cannot create old page choices in URL or body',
    () async {
      final network = RecordingNetwork();
      final engine = SourceEngine(runtime: NoScripts(), network: network);
      try {
        for (final placeholder in ['key', 'exploreUrl']) {
          await expectLater(
            engine.execute(
              source(
                SourceStage(
                  url: '/{{$placeholder}}',
                  legacyPageTemplates: true,
                ),
              ),
              'search',
              input: {placeholder: '<a,b>', 'page': 2},
            ),
            migrationError,
          );
        }
        for (final mode in ['legacyJsonString', 'legacyFormUtf8']) {
          final config = source(
            SourceStage(
              url: '/post',
              body: 'q={{key}}',
              bodyTemplateMode: mode == 'legacyJsonString' ? mode : 'raw',
              bodyEncoding: mode == 'legacyFormUtf8' ? mode : 'raw',
            ),
          );
          await expectLater(
            engine.execute(
              config,
              'search',
              input: {'key': '<a,b>', 'page': 2},
            ),
            throwsA(
              isA<EngineException>().having(
                (e) => e.code,
                'code',
                'legacy_body_template_requires_migration',
              ),
            ),
          );
        }
        expect(network.calls, isEmpty);
        await engine.execute(
          source(const SourceStage(url: '/{{key}}', legacyPageTemplates: true)),
          'search',
          input: {'key': 'a,b'},
        );
        expect(network.calls.single.toString(), 'https://example.org/a%2Cb');
      } finally {
        await engine.close();
      }
    },
  );
}
