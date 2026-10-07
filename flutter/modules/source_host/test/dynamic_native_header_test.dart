import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_host/main.dart' show createSourceEngine;

class _HeaderHost implements ScriptHost {
  final headers = <Map>[];
  final sourceVariables = <String, String>{'saved': 'source-value'};
  final entityVariables = <String, String>{};
  @override
  Future<Object?> call(String method, List<Object?> arguments) async {
    final args = arguments.sublist(0, arguments.length - 1);
    expect(arguments.last, {
      '__sourceTaskId': 'header-task',
      '__sourceHostCallback': true,
    });
    switch (method) {
      case 'analyze.get':
        return entityVariables[args.first] ?? '';
      case 'analyze.put':
        entityVariables[args.first as String] = args[1] as String;
        return args[1];
      case 'javaHttp.prepareHeader':
        expect(args.first, 'ajax');
        return {
          'count': 1,
          'script':
              "globalThis.headerRuns=(globalThis.headerRuns||0)+1; "
              "({'X-Dynamic':libraryHeader(),saved:capturedRead('saved'),"
              "put:java.put('header-write','source-written')})",
        };
      case 'javaHttp.headerGet':
        return sourceVariables[args.first] ?? '';
      case 'javaHttp.headerPut':
        sourceVariables[args.first as String] = args[1] as String;
        return args[1];
      case 'javaHttp.ajaxResolved':
        expect(args.first, ['https://fixture.invalid/header', 8000]);
        final evaluations = args[1] as List;
        expect(evaluations, hasLength(1));
        expect((evaluations.single as Map)['failed'], false);
        final header = (evaluations.single as Map)['value'] as Map;
        headers.add(header);
        return header['X-Dynamic'];
      default:
        throw StateError('Unexpected header RPC $method');
    }
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('actual persistent V8 evaluates each header before HTTP with source variables and restores normal scope', () async {
    final source = SourceDefinition(
      id: 'header-source',
      name: 'Header source',
      baseUrl: Uri.parse('https://fixture.invalid/'),
      metadata: {'legacy': true},
    );
    final host = _HeaderHost();
    final engine = createSourceEngine(
      source,
      platform: host,
      useNativeLegacyHttp: true,
    );
    const library =
        "globalThis.libraryLoads=(globalThis.libraryLoads||0)+1; "
        "var capturedRead=java.get; "
        "function libraryHeader(){return libraryLoads+':'+headerRuns;}";
    final kotlin = File(
      '../../../app/src/main/java/io/legado/app/model/sourceEngine/LegacySourceScriptRunner.kt',
    ).readAsStringSync();
    final ownerPrelude = RegExp(
      r'internal fun prelude\(library: String\): String =\s*"""([\s\S]*?)"""',
    ).firstMatch(kotlin)!.group(1)!.replaceAll(r'$library', library);
    try {
      for (final index in [1, 2]) {
        final value = await engine.evaluateAuxiliary(
          source,
          "java.put('saved','entity-value'); "
          "({body:java.ajax('https://fixture.invalid/header',8000),"
          "normal:java.get('saved'),flag:globalThis.__legacyHeaderEvaluation===true})",
          bindings: {
            'taskId': 'header-task',
            'sourceData': <String, Object?>{},
            '__legacySourceTag': 'Header source',
            '__legacySourceKey': 'header-source',
          },
          prelude: ownerPrelude,
        ) as Map;
        expect(value, {
          'body': '1:$index',
          'normal': 'entity-value',
          'flag': false,
        });
      }
      expect(host.headers.map((header) => header['X-Dynamic']), ['1:1', '1:2']);
      expect(
        host.headers.every((header) => header['saved'] == 'source-value'),
        true,
      );
      expect(
        host.headers.every((header) => header['put'] == 'source-written'),
        true,
      );
      expect(host.sourceVariables['header-write'], 'source-written');
      expect(host.sourceVariables['saved'], 'source-value');
    } finally {
      await engine.close();
    }
  });
}
