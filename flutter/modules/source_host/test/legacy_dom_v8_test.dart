import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/src/legacy_dom.dart';
import 'package:source_v8/source_v8.dart';

class DomHost implements ScriptHost {
  final calls = <String>[];
  static const table = <Object?>[
    {'kind': 'document'},
    {'kind': 'element'},
  ];
  static const node = {
    '__legacyDom': {'schemaVersion': 1, 'nodes': table, 'index': 1},
  };
  static const list = {
    '__legacyDomList': {
      'schemaVersion': 1,
      'nodes': table,
      'indexes': [1],
    },
  };
  @override
  Future<Object?> call(String method, List<Object?> args) async {
    expect(method, 'javaHost.domCall');
    expect(args[0], isA<Map>());
    final operation = args[1] as String;
    calls.add(operation);
    switch (operation) {
      case 'attr':
        return '/fixture';
      case 'toString':
        return '<a>First</a>\n<a>Second</a>';
      case 'text':
        return 'Text';
      case 'ownText':
        return 'Own';
      case 'html':
        return '<b>Text</b>';
      case 'outerHtml':
        return '<a>Text</a>';
      case 'select':
        return list;
      case 'parent':
        return node;
      default:
        throw StateError('Unsupported fixture DOM operation');
    }
  }
}

void main() {
  test('actual V8 typed DOM and shared ES array list preserve old read API', () async {
    final host = DomHost();
    final runtime = V8Runtime(prelude: legacyDomPrelude, persistent: true);
    try {
      expect(
        await runtime.evaluateAuxiliary(
          'var retainedDom=__legacyDomMaterialize(input);'
          'var selected=retainedDom.select("a");'
          '({href:retainedDom.attr("href"), own:retainedDom.ownText(),'
          'array:Array.isArray(selected),size:selected.size(),same:selected.get(0)===selected[0],'
          'text:selected.text(), html:selected.html(), parent:retainedDom.parent().text(),'
          'listString:String(selected),plainString:String([1,2]),'
          'sharedJson:JSON.stringify(selected),plainJson:JSON.stringify([1,2]),'
          'callbackText:__sourceHostSync("javaHost.domCall",[selected,"text",[]]),'
          'reflection:typeof retainedDom.getClass, marker:retainedDom.toJSON().__legacyDom.index})',
          ScriptContext(host: host, variables: {'input': DomHost.node}),
        ),
        {
          'href': '/fixture',
          'own': 'Own',
          'array': true,
          'size': 1,
          'same': true,
          'text': 'Text',
          'html': '<b>Text</b>',
          'listString': '<a>First</a>\n<a>Second</a>',
          'plainString': '1,2',
          'sharedJson': jsonEncode(DomHost.list),
          'plainJson': '[1,2]',
          'callbackText': 'Text',
          'parent': 'Text',
          'reflection': 'undefined',
          'marker': 1,
        },
      );
      expect(
        await runtime.evaluateAuxiliary(
          'retainedDom.outerHtml()',
          ScriptContext(host: host),
        ),
        '<a>Text</a>',
      );
    } finally {
      await runtime.close();
    }
  });
  test(
    'legacy DOM toArray supports book search DTOs as a shallow node copy',
    () async {
      final host = DomHost();
      final runtime = V8Runtime(prelude: legacyDomPrelude);
      try {
        expect(
          await runtime.evaluateAuxiliary(
            'const elements=__legacyDomMaterialize(input);const array=elements.toArray();'
            'const same=array[0]===elements.get(0);'
            'const books=array.map(node=>({name:node.text(),bookUrl:node.attr("href"),'
            'parentName:node.parent().text()}));'
            'array[0]=null;let invalidOverload=false;try{elements.toArray([])}catch(e){invalidOverload=true;}'
            '({books,same,array:Array.isArray(array),size:elements.size(),stillNode:elements.get(0)!==null,'
            'ordinary:typeof [].toArray,empty:__legacyDomMaterialize([]).toArray().length,invalidOverload})',
            ScriptContext(host: host, variables: {'input': DomHost.list}),
          ),
          {
            'books': [
              {'name': 'Text', 'bookUrl': '/fixture', 'parentName': 'Text'},
            ],
            'same': true,
            'array': true,
            'size': 1,
            'stillNode': true,
            'ordinary': 'undefined',
            'empty': 0,
            'invalidOverload': true,
          },
        );
      } finally {
        await runtime.close();
      }
    },
  );
  test('actual V8 source search toArray returns book DTO records', () async {
    final engine = SourceEngine(
      runtime: V8Runtime(prelude: legacyDomPrelude),
      platform: DomHost(),
    );
    try {
      final records = await engine.execute(
        SourceDefinition(
          id: 'dom-search-fixture',
          name: 'DOM search fixture',
          baseUrl: Uri.parse('https://fixture.invalid/'),
          script:
              'async function search(input){return __legacyDomMaterialize(input.nodes)'
              '.toArray().map(node=>({name:node.text(),bookUrl:node.attr("href")}));}',
        ),
        'search',
        input: {'nodes': DomHost.list},
      );
      expect(records, [
        {'name': 'Text', 'bookUrl': '/fixture'},
      ]);
    } finally {
      await engine.close();
    }
  });
}
