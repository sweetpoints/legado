import 'package:flutter_test/flutter_test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:source_v8/source_v8.dart';

class OrgJsoupHost implements ScriptHost {
  final calls = <(String, List<Object?>)>[];
  static const uri = 'https://fixture.invalid/base/';
  static const output = {
    'pretty': true,
    'outline': false,
    'indent': 1,
    'syntax': 'html',
    'charset': 'UTF-8',
    'escapeMode': 'base',
  };
  static const rows = <Object?>[
    {
      'kind': 'document',
      'baseUri': uri,
      'output': output,
      'attributes': {},
      'children': [1],
    },
    {
      'kind': 'element',
      'tag': 'html',
      'namespace': 'http://www.w3.org/1999/xhtml',
      'selfClosing': false,
      'baseUri': uri,
      'attributes': {},
      'children': [2, 3],
    },
    {
      'kind': 'element',
      'tag': 'head',
      'namespace': 'http://www.w3.org/1999/xhtml',
      'selfClosing': false,
      'baseUri': uri,
      'attributes': {},
      'children': [],
    },
    {
      'kind': 'element',
      'tag': 'body',
      'namespace': 'http://www.w3.org/1999/xhtml',
      'selfClosing': false,
      'baseUri': uri,
      'attributes': {},
      'children': [4],
    },
    {
      'kind': 'element',
      'tag': 'a',
      'namespace': 'http://www.w3.org/1999/xhtml',
      'selfClosing': false,
      'baseUri': uri,
      'attributes': {'href': '/book'},
      'children': [5],
    },
    {'kind': 'text', 'baseUri': uri, 'value': 'Book', 'children': []},
  ];
  static Map<String, Object?> node(int index) => {
    '__legacyDom': {'schemaVersion': 1, 'nodes': rows, 'index': index},
  };
  @override
  Future<Object?> call(String method, List<Object?> args) async {
    calls.add((method, args));
    switch (method) {
      case 'orgJsoup.parse':
      case 'orgJsoup.parseBodyFragment':
        expect(args.first, '<a href="/book">Book</a>');
        return node(0);
      case 'orgJsoup.newDocument':
        expect(args, args.length == 2 ? [uri, 'shell'] : [uri]);
        return node(0);
      case 'orgJsoup.newElement':
        expect(args, ['a', uri]);
        return node(4);
      case 'javaHost.domCall':
        final operation = args[1];
        if (operation == 'select') {
          return {
            '__legacyDomList': {
              'schemaVersion': 1,
              'nodes': rows,
              'indexes': [4],
            },
          };
        }
        if (operation == 'text') return 'Book';
        if (operation == 'attr') return 'https://fixture.invalid/book';
        if (operation == 'tagName') return 'body';
        if (operation == 'parent') return node(3);
        throw StateError('Unsupported fixture DOM operation');
      default:
        throw StateError('Unsupported fixture org API');
    }
  }
}

class OrgConnectionHost extends OrgJsoupHost {
  var sequence = 0;
  final configurations = <(String, List<Object?>)>[];
  final bodies = <String, String>{};
  final released = <String>[];
  final cacheValues = <String, Object?>{};
  @override
  Future<Object?> call(String method, List<Object?> args) async {
    if (method == 'cacheHost.put') {
      cacheValues[args[0] as String] = args[1];
      return null;
    }
    if (method == 'cacheHost.getByteArray') return cacheValues[args[0]];
    if (method == 'orgJsoup.connect') {
      expect(args, ['https://fixture.invalid/request']);
      return {'__legacyOrgConnection': 'connection-fixture'};
    }
    if (method == 'orgJsoup.release') {
      released.add(args.single as String);
      return null;
    }
    if (method == 'orgJsoup.connectionCall') {
      expect(args[0], 'connection-fixture');
      final operation = args[1] as String;
      final values = (args[2] as List).cast<Object?>();
      if (operation == 'execute') {
        final token = 'response-${++sequence}';
        bodies[token] = 'body-$sequence';
        return {'__legacyOrgResponse': token, '__legacyResponseKind': 'jsoup'};
      }
      if (operation == 'get') return OrgJsoupHost.node(0);
      if (operation == 'response') {
        return {
          '__legacyOrgResponse': 'response-$sequence',
          '__legacyResponseKind': 'jsoup',
        };
      }
      configurations.add((operation, values));
      return {'__legacyOrgConnection': 'connection-fixture'};
    }
    if (method == 'orgJsoup.responseCall') {
      final token = args[0] as String;
      final operation = args[1];
      if (operation == 'body') return bodies[token];
      if (operation == 'headers') return {'X-Fixture': 'value'};
      if (operation == 'bodyAsBytes') return [65, -1];
      if (operation == 'parse') return OrgJsoupHost.node(0);
      if (operation == 'statusCode') return 200;
      if (operation == 'charset') return {'__legacyOrgResponse': token};
    }
    return super.call(method, args);
  }
}

void main() {
  test(
    'actual V8 org parse yields typed selected nodes and book DTOs',
    () async {
      final host = OrgJsoupHost();
      final engine = SourceEngine(
        runtime: V8Runtime(prelude: legacyScriptPrelude),
        platform: host,
      );
      try {
        final source = SourceDefinition(
          id: 'org-parse-fixture',
          name: 'Org parse fixture',
          baseUrl: Uri.parse(OrgJsoupHost.uri),
          script:
              'async function search(input){return org.jsoup.Jsoup.parse(input.html,input.base).select("a").toArray()'
              '.map(node=>({name:node.text(),bookUrl:node.attr("abs:href"),parent:node.parent().tagName()}));}',
        );
        expect(
          await engine.execute(
            source,
            'search',
            input: {
              'html': '<a href="/book">Book</a>',
              'base': OrgJsoupHost.uri,
            },
          ),
          [
            {
              'name': 'Book',
              'bookUrl': 'https://fixture.invalid/book',
              'parent': 'body',
            },
          ],
        );
        expect(host.calls.first.$1, 'orgJsoup.parse');
        expect(host.calls.first.$2, [
          '<a href="/book">Book</a>',
          OrgJsoupHost.uri,
        ]);
      } finally {
        await engine.close();
      }
    },
  );
  test(
    'bounded Packages aliases and constructors delegate real typed callbacks',
    () async {
      final host = OrgJsoupHost();
      final runtime = V8Runtime(prelude: legacyScriptPrelude);
      try {
        expect(
          await runtime.evaluateAuxiliary(
            'const Doc=Packages.org.jsoup.nodes.Document,El=Packages.org.jsoup.nodes.Element;'
            'const document=new Doc(base),element=new El("a",base);'
            '({same:Packages.org===org,elementText:element.text(),foreign:typeof Packages.java,'
            'method:org.jsoup.Connection.Method.PUT.name()})',
            ScriptContext(host: host, variables: {'base': OrgJsoupHost.uri}),
          ),
          {
            'same': true,
            'elementText': 'Book',
            'foreign': 'undefined',
            'method': 'PUT',
          },
        );
        expect(host.calls.take(2).map((c) => c.$1), [
          'orgJsoup.newDocument',
          'orgJsoup.newElement',
        ]);
      } finally {
        await runtime.close();
      }
    },
  );
  test('XML parser markers and Document shell use exact native overloads', () async {
    final host = OrgJsoupHost();
    final runtime = V8Runtime(prelude: legacyScriptPrelude);
    try {
      await runtime.evaluateAuxiliary(
        'org.jsoup.Jsoup.parse(html,org.jsoup.parser.Parser.xmlParser());'
        'org.jsoup.Jsoup.parse(html,base,org.jsoup.parser.Parser.xmlParser());'
        'org.jsoup.nodes.Document.createShell(base);',
        ScriptContext(
          host: host,
          variables: {
            'html': '<a href="/book">Book</a>',
            'base': OrgJsoupHost.uri,
          },
        ),
      );
      expect(host.calls[0].$2, [
        '<a href="/book">Book</a>',
        {'__legacyOrgParser': 'xml'},
      ]);
      expect(host.calls[1].$2, [
        '<a href="/book">Book</a>',
        OrgJsoupHost.uri,
        {'__legacyOrgParser': 'xml'},
      ]);
      expect(host.calls[2].$2, [OrgJsoupHost.uri, 'shell']);
    } finally {
      await runtime.close();
    }
  });
  test('existing bounded Packages members are preserved', () async {
    final runtime = V8Runtime(
      prelude:
          'globalThis.Packages={preserved:42,org:{otherPackage:"existing"}};$legacyScriptPrelude',
    );
    try {
      expect(
        await runtime.evaluate(
          '({preserved:Packages.preserved,other:Packages.org.otherPackage,same:Packages.org===org})',
          ScriptContext(host: OrgJsoupHost()),
        ),
        {'preserved': 42, 'other': 'existing', 'same': true},
      );
    } finally {
      await runtime.close();
    }
  });

  test('modern V8 does not acquire org or Packages namespaces', () async {
    final runtime = V8Runtime();
    try {
      expect(
        await runtime.evaluate(
          '({org:typeof org,packages:typeof Packages})',
          ScriptContext(host: OrgJsoupHost()),
        ),
        {'org': 'undefined', 'packages': 'undefined'},
      );
    } finally {
      await runtime.close();
    }
  });
  test('actual V8 Jsoup fluent calls preserve connection and independent responses', () async {
    final host = OrgConnectionHost();
    final runtime = V8Runtime(prelude: legacyScriptPrelude, persistent: true);
    try {
      expect(
        await runtime.evaluateAuxiliary(
          'var retainedConnection=org.jsoup.Jsoup.connect("https://fixture.invalid/request");'
          'const same=retainedConnection.method(org.jsoup.Connection.Method.PUT).header("X-Fixture","value")'
          '.requestBody("payload").ignoreContentType(true).ignoreHttpErrors(true)===retainedConnection;'
          'var retainedResponse=retainedConnection.execute();const later=retainedConnection.execute();'
          'cache.put("org-bytes-fixture",retainedResponse.bodyAsBytes());'
          'cache.put("org-bytes-copy",cache.getByteArray("org-bytes-fixture"));'
          '({same,old:retainedResponse.body(),later:later.body(),header:retainedResponse.headers().get("X-Fixture"),'
          'bytes:retainedResponse.bodyAsBytes(),parsed:retainedResponse.parse().select("a").get(0).text(),'
          'charsetSame:retainedResponse.charset("utf-8")===retainedResponse,responseSame:retainedConnection.response()===later})',
          ScriptContext(host: host),
        ),
        {
          'same': true,
          'old': 'body-1',
          'later': 'body-2',
          'header': 'value',
          'bytes': [65, -1],
          'parsed': 'Book',
          'charsetSame': true,
          'responseSame': true,
        },
      );
      expect(host.configurations.map((c) => c.$1), [
        'method',
        'header',
        'requestBody',
        'ignoreContentType',
        'ignoreHttpErrors',
      ]);
      expect(host.configurations.first.$2, ['PUT']);
      expect(host.cacheValues['org-bytes-fixture'], {
        'kind': 'bytes',
        'value': [65, -1],
      });
      expect(
        host.cacheValues['org-bytes-copy'],
        host.cacheValues['org-bytes-fixture'],
      );
      expect(
        await runtime.evaluateAuxiliary(
          'retainedResponse.body()',
          ScriptContext(host: host),
        ),
        'body-1',
      );
      expect(
        await runtime.evaluateAuxiliary(
          'retainedConnection.get().select("a").get(0).text()',
          ScriptContext(host: host),
        ),
        'Book',
      );
      await runtime.evaluateAuxiliary(
        'retainedConnection.dispose();retainedResponse.dispose();',
        ScriptContext(host: host),
      );
      expect(host.released, ['connection-fixture', 'response-1']);
      await expectLater(
        runtime.evaluateAuxiliary(
          'retainedConnection.get()',
          ScriptContext(host: host),
        ),
        throwsA(isA<EngineException>()),
      );
    } finally {
      await runtime.close();
    }
  });
}
