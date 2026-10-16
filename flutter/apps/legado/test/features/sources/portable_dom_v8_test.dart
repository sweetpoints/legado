import 'package:flutter_test/flutter_test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:source_v8/source_v8.dart';

void main() {
  test('production V8 org.jsoup parse forests preserve aliases, order and read-only text/html/attr', () async {
    final runtime = V8Runtime(prelude: legacyScriptPrelude, persistent: true);
    try {
      final value = await runtime.evaluateAuxiliary(r'''
    var doc = org.jsoup.Jsoup.parse('<div class="book_name"><a href="book-1.html">Book one</a><a href="book-2.html">Book two</a></div><p class="author">A <b>B</b></p>');
    var links=doc.select('.book_name a');
    ({same:links.first() === doc.selectFirst('a'),order:links.toArray().map(x=>x.attr('href')),text:doc.select('.author').text(),html:doc.select('.author').html(),serialized:String(links),kind:doc instanceof org.jsoup.nodes.Document});
   ''', ScriptContext(host: const PortableLegacyDomHost(_NoHost())));
      expect(value, {
        'same': true,
        'order': ['book-1.html', 'book-2.html'],
        'text': 'A B',
        'html': 'A <b>B</b>',
        'serialized': '<a href="book-1.html">Book one</a>\n<a href="book-2.html">Book two</a>',
        'kind': true,
      });
    } finally {
      await runtime.close();
    }
  });
}

class _NoHost implements ScriptHost {
  const _NoHost();
  @override
  Future<Object?> call(String method, List<Object?> args) async =>
      throw StateError(method);
}
