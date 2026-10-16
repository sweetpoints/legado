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

  test('portable evaluator opts into stable fanout order and removes cycles/duplicates', () async {
    final pages = _NetworkPages({
      first: _page('One', [first, second, second, third]),
      second: _page('Two', [first, 'https://fixture.invalid/unwanted-child']),
      third: _page('Three', [second]),
    });
    final engine = SourceEngine(
      runtime: NoScripts(),
      network: pages,
      legacyRuleEvaluator: _PortableEvaluator(),
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
  });
  test('portable fanout retains page budget and cancellation', () async {
    for (final cancelled in [false, true]) {
      final pages = _NetworkPages({
        first: _page('One', [second, third]),
        second: _page('Two', []),
        third: _page('Three', []),
      }, cancelOn: cancelled ? 2 : null);
      final engine = SourceEngine(
        runtime: NoScripts(),
        network: pages,
        legacyRuleEvaluator: _PortableEvaluator(),
      );
      final source = SourceDefinition.fromJson({
        ..._source().toJson(),
        'stages': {
          'toc': {..._source().stages['toc']!.toJson(), 'maxPages': 2},
        },
      });
      try {
        await expectLater(
          engine.execute(
            source,
            'toc',
            input: {'tocUrl': first},
            cancellation: CancellationToken(),
          ),
          throwsA(
            isA<EngineException>().having(
              (error) => error.code,
              'code',
              cancelled ? 'cancelled' : 'pagination_limit',
            ),
          ),
        );
        expect(pages.fetched, [first, second]);
      } finally {
        await engine.close();
      }
    }
  });
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

class _PortableEvaluator implements LegacyTocFanOutEvaluator {
  @override
  bool supportsRule(String rule) => true;
  @override
  Future<List<Object?>> evaluate(
    String rule,
    Object? input,
    ScriptContext context, {
    required SourceDefinition source,
    required String operation,
    bool elements = false,
    bool element = false,
    bool formatContent = false,
    bool resolveCover = false,
    bool scalar = false,
    bool isUrl = false,
    bool unescape = true,
    CancellationToken? cancellation,
  }) async {
    final values = await RuleEvaluator(NoScripts()).evaluate(
      rule,
      input,
      context,
      elements: elements,
      cancellation: cancellation,
    );
    return isUrl
        ? values
              .map(
                (value) =>
                    Uri.parse(context.variables['baseUrl'] as String)
                        .resolve(value.toString())
                        .toString(),
              )
              .toList()
        : values;
  }
}

class _NetworkPages extends NetworkClient {
  _NetworkPages(this.pages, {this.cancelOn});
  final Map<String, String> pages;
  final int? cancelOn;
  final fetched = <String>[];
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
    fetched.add(uri.toString());
    if (fetched.length == cancelOn) cancellation?.cancel();
    cancellation?.throwIfCancelled();
    return NetworkResponse(uri, 200, const {}, pages[uri.toString()]!);
  }
}
