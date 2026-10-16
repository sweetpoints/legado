import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:app_core/app_core.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/services/book_source_service.dart';
import 'package:source_engine/source_engine.dart';

class _NoScript implements ScriptRuntime {
  @override
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  }) async => throw StateError(
    'This fixture must use actual CSS extraction, not fake JS',
  );
  @override
  Future<void> close() async {}
}

class _Repository implements LibraryRepository {
  final sourceRows = <String, Map<String, Object?>>{};
  final bookRows = <String, LegacyBookRecord>{};
  final chapterRows = <String, List<ReaderChapter>>{};
  final contentRows = <String, String>{};
  final settings = <String, String>{};
  final controller = StreamController<void>.broadcast();
  @override
  Stream<void> get changes => controller.stream;
  @override
  Future<List<LegacyBookRecord>> books() async => bookRows.values.toList();
  @override
  Future<LegacyBookRecord?> book(String url) async => bookRows[url];
  @override
  Future<void> putBook(LegacyBookRecord book) async {
    bookRows[book.url] = book;
    controller.add(null);
  }

  @override
  Future<void> removeBook(String url) async => bookRows.remove(url);
  @override
  Future<void> updateProgress(String url, int index, int offset) async {
    final b = bookRows[url]!;
    bookRows[url] = LegacyBookRecord({
      ...b.toJson(),
      'durChapterIndex': index,
      'durChapterPos': offset,
    });
  }

  @override
  Future<List<ReaderChapter>> chapters(String bookUrl) async =>
      chapterRows[bookUrl] ?? [];
  @override
  Future<void> putChapters(String bookUrl, List<ReaderChapter> chapters) async {
    chapterRows[bookUrl] = chapters;
  }

  @override
  Future<String?> content(String bookUrl, String chapterUrl) async =>
      contentRows['$bookUrl|$chapterUrl'];
  @override
  Future<void> putContent(
    String bookUrl,
    String chapterUrl,
    String content,
  ) async {
    contentRows['$bookUrl|$chapterUrl'] = content;
  }

  @override
  Future<List<Map<String, Object?>>> sources() async =>
      sourceRows.values.toList();
  @override
  Future<void> putSource(Map<String, Object?> source) async {
    sourceRows[BookSourceService.sourceId(source)] = source;
    controller.add(null);
  }

  @override
  Future<void> removeSource(String id) async {
    sourceRows.remove(id);
    controller.add(null);
  }

  @override
  Future<String?> setting(String key) async => settings[key];
  @override
  Future<void> setSetting(String key, String? value) async {
    if (value == null) {
      settings.remove(key);
    } else {
      settings[key] = value;
    }
  }

  @override
  Future<Map<String, String>> backupFiles() async => {};
  @override
  Future<void> restoreBackup(LegacyBackupDocument document) async =>
      throw UnimplementedError();
  @override
  Future<void> close() => controller.close();
}

void main() {
  test('import preserves opaque ID, nested unknown fields and rejects malformed batch before storage', () async {
    final repo = _Repository();
    final service = BookSourceService(repo);
    const legacy = {
      'bookSourceUrl': '歪歪斜斜的字眼',
      'bookSourceName': '自定义',
      'enabled': true,
      'unknown': {
        'future': [1, 'two'],
      },
    };
    await service.importJson(jsonEncode(legacy));
    expect(jsonDecode(await service.exportJson()), [legacy]);
    await service.setEnabled(legacy, false);
    expect(repo.sourceRows.values.single['unknown'], legacy['unknown']);
    await expectLater(
      service.importJson(jsonEncode([legacy, null])),
      throwsFormatException,
    );
    expect(repo.sourceRows.length, 1);
    await service.close();
    await repo.close();
  });

  test('actual HTTP search/detail/TOC/content keeps previews off shelf and restores cookies', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final base = 'http://${server.address.address}:${server.port}';
    final received = <String>[];
    var contents = 0;
    server.listen((request) async {
      received.add(request.uri.path);
      expect(request.headers.value('X-Source'), 'header-value');
      request.response.headers.contentType = ContentType.html;
      switch (request.uri.path) {
        case '/search':
          request.response.cookies.add(Cookie('session', 'kept'));
          request.response.write(
            '<article><a href="/book">Book</a><em>Author</em></article>',
          );
        case '/book':
          expect(
            request.cookies.any(
              (c) => c.name == 'session' && c.value == 'kept',
            ),
            true,
          );
          request.response.write(
            '<h1>Detail book</h1><a class="toc" href="/toc">TOC</a>',
          );
        case '/toc':
          request.response.write(
            '<li><a href="/chapter">First chapter</a></li>',
          );
        case '/chapter':
          contents++;
          request.response.write('<main>Actual HTTP chapter</main>');
      }
      await request.response.close();
    });
    final repo = _Repository();
    final source = {
      'id': 'opaque-source',
      'name': 'Fixture',
      'baseUrl': base,
      'headers': {'X-Source': 'header-value'},
      'stages': {
        'search': {
          'url': '/search?q={{key}}',
          'list': '@css:article',
          'fields': {
            'name': '@css:a@text',
            'author': '@css:em@text',
            'bookUrl': '@css:a@href',
          },
        },
        'info': {
          'url': '{{bookUrl}}',
          'fields': {'name': '@css:h1@text', 'tocUrl': '@css:.toc@href'},
        },
        'toc': {
          'url': '{{tocUrl}}',
          'list': '@css:li',
          'fields': {'title': '@css:a@text', 'url': '@css:a@href'},
        },
        'content': {
          'url': '{{chapterUrl}}',
          'fields': {'content': '@css:main@text'},
        },
      },
    };
    SourceEngine factory(SourceDefinition _) =>
        SourceEngine(runtime: _NoScript());
    var service = BookSourceService(repo, engineFactory: factory);
    try {
      await service.importJson(jsonEncode(source));
      final books = await service.search('opaque-source', '中文 空格');
      expect(books.single.url, '$base/book');
      await service.close();
      service = BookSourceService(repo, engineFactory: factory);
      final preview = await service.loadDetail(books.single);
      expect(preview.book.name, 'Detail book');
      expect(preview.chapters.single.url, '$base/chapter');
      expect(await repo.books(), isEmpty);
      expect(await repo.chapters(books.single.url), isEmpty);
      expect(await service.fetchChapter(preview, 0), 'Actual HTTP chapter');
      expect(await service.fetchChapter(preview, 0), 'Actual HTTP chapter');
      expect(contents, 1);
      final added = await service.addToShelf(preview);
      expect(added.toJson()['origin'], 'opaque-source');
      expect((await repo.chapters(added.url)).length, 1);
      await repo.updateProgress(added.url, 0, 4);
      await service.addToShelf(preview);
      expect((await repo.book(added.url))!.characterOffset, 4);
      expect(received, ['/search', '/book', '/toc', '/chapter']);
      await service.setEnabled(source, false);
      await expectLater(
        service.search('opaque-source', 'book'),
        throwsA(
          isA<EngineException>().having(
            (e) => e.code,
            'code',
            'source_disabled',
          ),
        ),
      );
    } finally {
      await service.close();
      await repo.close();
      await server.close(force: true);
    }
  });
  test(
    'production V8 legacy mainJs executes HTTP and restores Java variables',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      final base = 'http://${server.address.address}:${server.port}';
      server.listen((request) async {
        request.response.headers.contentType = ContentType.json;
        request.response.write('{"value":"from HTTP"}');
        await request.response.close();
      });
      final repo = _Repository();
      var service = BookSourceService(repo);
      try {
        await service.importJson(
          jsonEncode({
            'bookSourceUrl': base,
            'bookSourceName': 'Native V8 fixture',
            'enabledCookieJar': true,
            'mainJs': r"""
          function search(key, page) {
            const counter = String(Number(java.get('counter') || '0') + 1);
            java.put('counter', counter);
            const response = JSON.parse(java.ajax(baseUrl + '/value'));
            return [{name: key + ':' + response.value, author: counter, bookUrl: baseUrl + '/book'}];
          }
        """,
          }),
        );
        final first = await service.search(base, 'Keyword');
        expect(first.single.name, 'Keyword:from HTTP');
        expect(first.single.author, '1');
        await service.close();
        service = BookSourceService(repo);
        final restored = await service.search(base, 'Again');
        expect(restored.single.name, 'Again:from HTTP');
        expect(restored.single.author, '2');
      } finally {
        await service.close();
        await repo.close();
        await server.close(force: true);
      }
    },
  );
  test(
    'unsupported legacy pipelines are explicit and source is still exportable',
    () async {
      final repo = _Repository();
      final service = BookSourceService(
        repo,
        engineFactory: (_) => throw StateError('must not create engine'),
      );
      await service.importJson(
        jsonEncode({
          'bookSourceUrl': 'https://example.org',
          'bookSourceName': 'Unsupported',
          'searchUrl': '/q',
          'ruleSearch': {'bookList': '@js:java.unsupported()'},
        }),
      );
      await expectLater(
        service.search('https://example.org', 'a'),
        throwsA(
          isA<EngineException>().having(
            (e) => e.code,
            'code',
            'legacy_requires_migration',
          ),
        ),
      );
      expect(jsonDecode(await service.exportJson()), hasLength(1));
      await service.close();
      await repo.close();
    },
  );
}
