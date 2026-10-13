import 'package:source_engine/source_engine.dart';
import 'package:test/test.dart';

import 'engine_test.dart' show NoScripts;

class _Pages implements LegacyPageFetcher {
  _Pages(this.pages);
  final Map<String, String> pages;
  final fetched = <String>[];

  @override
  Future<NetworkResponse> fetch(
    SourceDefinition source,
    String operation,
    Map<String, Object?> input,
    ScriptContext context, {
    String? nextUrl,
    CancellationToken? cancellation,
  }) async {
    expect(operation, 'toc');
    final url = nextUrl ?? input['tocUrl'] as String;
    fetched.add(url);
    final html = pages[url];
    if (html == null) throw StateError('Unexpected directory page');
    return NetworkResponse(Uri.parse(url), 200, const {}, html);
  }
}

SourceDefinition _source() => SourceDefinition(
  id: 'offline-toc',
  name: 'Offline TOC',
  baseUrl: Uri.parse('https://fixture.invalid/'),
  metadata: {'legacyOriginal': <String, Object?>{}},
  stages: {
    'toc': SourceStage(
      url: '{{tocUrl}}',
      list: '@css:div.chapter',
      fields: {'title': '@css:a@text', 'chapterUrl': '@css:a@href'},
      nextPage: '@css:a.page@href',
      maxPages: 5,
    ),
  },
);

String _page(String title, List<String> next) =>
    '<div class="chapter"><a href="/$title">$title</a></div>'
    '${next.map((url) => '<a class="page" href="$url">Page</a>').join()}';

void main() {
  const first = 'https://fixture.invalid/toc';
  const second = 'https://fixture.invalid/toc/2';
  const third = 'https://fixture.invalid/toc/3';

  test('current directory link does not hide the valid next page', () async {
    final pages = _Pages({
      first: _page('One', [first, second]),
      second: _page('Two', [second, first]),
    });
    final engine = SourceEngine(runtime: NoScripts(), legacyPageFetcher: pages);
    try {
      final rows = await engine.execute(
        _source(),
        'toc',
        input: {'tocUrl': first},
      );
      expect(rows.map((row) => row['title']), ['One', 'Two']);
      expect(rows.map((row) => row['chapterUrl']), [
        'https://fixture.invalid/One',
        'https://fixture.invalid/Two',
      ]);
      expect(pages.fetched, [first, second]);
    } finally {
      await engine.close();
    }
  });

  test(
    'initial directory page list fetches every branch exactly once',
    () async {
      final pages = _Pages({
        first: _page('One', [second, third]),
        second: _page('Two', ['https://fixture.invalid/not-a-branch']),
        third: _page('Three', [second]),
      });
      final engine = SourceEngine(
        runtime: NoScripts(),
        legacyPageFetcher: pages,
      );
      try {
        final rows = await engine.execute(
          _source(),
          'toc',
          input: {'tocUrl': first},
        );
        expect(rows.map((row) => row['title']), ['One', 'Two', 'Three']);
        expect(pages.fetched, [first, second, third]);
      } finally {
        await engine.close();
      }
    },
  );
}
