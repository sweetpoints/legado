import 'package:source_engine/source_engine.dart';
import 'package:test/test.dart';

import 'engine_test.dart' show NoScripts;

class _Host implements ScriptHost {
  final calls = <Map>[];
  @override
  Future<Object?> call(String method, List<Object?> args) async {
    final r = args.first as Map;
    calls.add(r);
    Object? value;
    if (method == 'legacyRequest.fetch') {
      value = {
        'url': r['urlRule'],
        'body': r['urlRule'] == 'https://fixture.invalid/sub'
            ? 'Lyrics'
            : 'first-body',
        'status': 200,
        'headers': <String, List<String>>{},
      };
    } else if (r['mode'] == 'content') {
      value = ' First ';
    } else if (r['rule'] == 'sub') {
      value = '  SUB &  ';
    } else if (r['rule'] == 'urlSub') {
      value = ' https://fixture.invalid/sub ';
    } else {
      expect(r['input'], 'First\nSUB &');
      value = 'After\nSUB &';
    }
    return {
      'value': value,
      'variables': <String, String>{},
      'variableScope': r['variableScope'],
    };
  }
}

SourceDefinition _source(String sub, {bool replace = false}) =>
    SourceDefinition(
      id: 'source',
      name: 'Fixture',
      baseUrl: Uri.parse('https://fixture.invalid/'),
      metadata: {
        'legacyOriginal': {
          'ruleContent': {
            'subContent': sub,
            if (replace) 'replaceRegex': 'replace',
          },
        },
      },
      stages: {
        'content': SourceStage(
          url: '{{chapterUrl}}',
          fields: {
            'content': 'body',
            'subContent': sub,
            if (replace) 'replaceRegex': 'replace',
          },
        ),
      },
    );
void main() {
  test(
    'online TXT appends raw subcontent before whole replacement exactly once',
    () async {
      final host = _Host();
      final engine = SourceEngine(
        runtime: NoScripts(),
        platform: host,
        legacyPageFetcher: const HostLegacyPageFetcher(),
        legacyRuleEvaluator: const HostLegacyRuleEvaluator(),
      );
      try {
        expect(
          await engine.execute(
            _source('sub', replace: true),
            'content',
            input: {
              'taskId': 'task',
              'chapterUrl': 'https://fixture.invalid/one',
              '__legacyOnLineTxt': true,
            },
          ),
          [
            {'content': '　　After\n　　SUB &'},
          ],
        );
        expect(host.calls.where((r) => r['rule'] == 'sub'), hasLength(1));
      } finally {
        await engine.close();
      }
    },
  );
  test('ordinary text keeps subcontent out of the main body', () async {
    final host = _Host();
    final engine = SourceEngine(
      runtime: NoScripts(),
      platform: host,
      legacyPageFetcher: const HostLegacyPageFetcher(),
      legacyRuleEvaluator: const HostLegacyRuleEvaluator(),
    );
    try {
      expect(
        await engine.execute(
          _source('sub'),
          'content',
          input: {
            'taskId': 'task',
            'chapterUrl': 'https://fixture.invalid/one',
          },
        ),
        [
          {'content': ' First '},
        ],
      );
    } finally {
      await engine.close();
    }
  });
  for (final media in ['audio', 'video']) {
    test(
      '$media URL subcontent fetches through original networking into its chapter variable',
      () async {
        final host = _Host();
        final engine = SourceEngine(
          runtime: NoScripts(),
          platform: host,
          legacyPageFetcher: const HostLegacyPageFetcher(),
          legacyRuleEvaluator: const HostLegacyRuleEvaluator(),
        );
        try {
          expect(
            await engine.execute(
              _source('urlSub'),
              'content',
              input: {
                'taskId': 'task',
                'chapterUrl': 'https://fixture.invalid/one',
                media == 'audio' ? '__legacyIsAudio' : '__legacyIsVideo': true,
              },
            ),
            [
              {
                'content': ' First ',
                'variable': {media == 'audio' ? 'lyric' : 'danmaku': 'Lyrics'},
              },
            ],
          );
          expect(
            host.calls
                .where((r) => r.containsKey('urlRule'))
                .map((r) => r['urlRule']),
            ['https://fixture.invalid/one', 'https://fixture.invalid/sub'],
          );
        } finally {
          await engine.close();
        }
      },
    );
  }
}
