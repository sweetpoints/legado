import 'package:source_engine/source_engine.dart';
import 'package:test/test.dart';

import 'engine_test.dart' show NoScripts;

class _NativeHost implements ScriptHost {
  _NativeHost(this.tocUrl);
  final String tocUrl;
  final requestedUrls = <String>[];

  @override
  Future<Object?> call(String method, List<Object?> arguments) async {
    final request = arguments.first as Map;
    Object? value;
    if (method == 'legacyRequest.fetch') {
      final raw = request['urlRule'] as String;
      requestedUrls.add(raw);
      if (request['operation'] == 'toc') expect(raw, tocUrl);
      value = {
        'url': 'https://fixture.invalid/n/book/xiaoshuo.html',
        'status': 200,
        'headers': <String, List<String>>{},
        'body': '<div class="list"><li><a href="1.html"><span>Chapter 1</span></a></li></div>',
      };
    } else {
      expect(method, 'legacyRule.evaluate');
      if (request['operation'] == 'info') {
        expect(request['isUrl'], true);
        expect(request['rule'], "text.开始阅读@href##\$##,{'webView': true}");
        value = tocUrl;
      } else {
        value = switch (request['mode']) {
          'elements' => ['chapter-row'],
          _ => request['rule'] == 'a span@text' ? 'Chapter 1' : '1.html',
        };
      }
    }
    return {
      'value': value,
      'variables': <String, String>{},
      if (request['variableScope'] != null)
        'variableScope': request['variableScope'],
    };
  }
}

void main() {
  for (final suffix in [
    ",{'webView': true}",
    ', {"webView":true,"headers":{"Referer":"https://fixture.invalid/a,b"}}',
    ',{"method":"POST","body":"key=value&other=a,b"}',
    '',
  ]) {
    test(
      'info to native TOC preserves request options verbatim: $suffix',
      () async {
        final tocUrl = 'https://fixture.invalid/n/book/xiaoshuo.html$suffix';
        final host = _NativeHost(tocUrl);
        final engine = SourceEngine(
          runtime: NoScripts(),
          platform: host,
          legacyRuleEvaluator: const HostLegacyRuleEvaluator(),
          legacyPageFetcher: const HostLegacyPageFetcher(),
        );
        final source = SourceDefinition(
          id: 'quanben-fixture',
          name: '全本小说',
          baseUrl: Uri.parse('https://fixture.invalid/'),
          metadata: {
            'legacyOriginal': {'bookSourceUrl': 'https://fixture.invalid/'},
          },
          stages: {
            'info': SourceStage(
              url: '{{bookUrl}}',
              fields: {
                'tocUrl': "@legacy:text.开始阅读@href##\$##,{'webView': true}",
              },
            ),
            'toc': const SourceStage(
              url: '{{tocUrl}}',
              list: '@legacy:.list li',
              fields: {'title': '@legacy:a span@text', 'url': '@legacy:a@href'},
            ),
          },
        );
        try {
          final info = await engine.execute(
            source,
            'info',
            input: {
              'taskId': 'info-task',
              'bookUrl': 'https://fixture.invalid/n/book/',
            },
          );
          expect(info.single['tocUrl'], tocUrl);
          final chapters = await engine.execute(
            source,
            'toc',
            input: {'taskId': 'toc-task', 'tocUrl': info.single['tocUrl']},
          );
          expect(chapters.single['title'], 'Chapter 1');
          expect(
            chapters.single['url'],
            'https://fixture.invalid/n/book/1.html',
          );
          expect(host.requestedUrls, [
            'https://fixture.invalid/n/book/',
            tocUrl,
          ]);
        } finally {
          await engine.close();
        }
      },
    );
  }
}
