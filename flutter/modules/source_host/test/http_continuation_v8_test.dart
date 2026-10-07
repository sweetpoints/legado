import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_host/main.dart' show createSourceEngine;

class _Steps implements ScriptHost {
  final aborted = <String>[];
  final outcomes = <Map>[];
  final callbacks = <List<Object?>>[];
  final bool failScript;
  _Steps({this.failScript = false});

  Map<String, Object?> script(int sequence) => {
    'status': 'script',
    'token': 'step-token',
    'sequence': sequence,
    'script': failScript
        ? "throw new Error('controlled-step-failure')"
        : sequence == 1
        ? "globalThis.phaseRuns=(globalThis.phaseRuns||0)+1; "
              "libraryPhase()+':'+java.put('ignored','value')+':'+source.getVariable()"
        : "source.fixture.echo('echo').then(v=>{globalThis.phaseRuns++;return result+':'+libraryPhase()+':'+v;})",
    'bindings': {
      'baseUrl': 'https://step.invalid/$sequence',
      'result': sequence == 1 ? 'url-input' : 'body-input',
      'book': null,
      'chapter': null,
      'page': null,
      'key': null,
      'infoMap': {'token': 'native-info'},
      '__analyzeScript': 'step-code',
      'taskId': 'must-not-replace-task',
    },
  };

  @override
  Future<Object?> call(String method, List<Object?> arguments) async {
    if (method == 'fixture.echo') {
      await Future<void>.delayed(const Duration(milliseconds: 1));
      return arguments.single;
    }
    final args = arguments.sublist(0, arguments.length - 1);
    expect(arguments.last, {
      '__sourceTaskId': 'step-task',
      '__sourceHostCallback': true,
    });
    switch (method) {
      case 'analyze.get':
        return 'outer-book';
      case 'sourceState.getVariable':
        return 'source-value';
      case 'javaHttp.prepareHeader':
        return null;
      case 'javaHttp.begin':
        expect(args, [
          'ajax',
          ['https://fixture.invalid/raw', 8000],
          null,
        ]);
        return script(1);
      case 'javaHttp.stepCall':
        expect(args[0], 'step-token');
        expect(args[1], anyOf(1, 2));
        callbacks.add(args);
        return args[2] == 'get' ? '' : (args[3] as List)[1];
      case 'javaHttp.continue':
        expect(args[0], 'step-token');
        final result = args[2] as Map;
        outcomes.add(result);
        if (result['ok'] == false) {
          throw EngineException('script_error', result['message'] as String);
        }
        return args[1] == 1
            ? script(2)
            : {'status': 'done', 'token': 'step-token', 'value': 'native-body'};
      case 'javaHttp.abort':
        aborted.add(args.single as String);
        return null;
      default:
        throw StateError('Unexpected continuation method $method');
    }
  }
}

String _ownerPrelude() {
  final kotlin = File(
    '../../../app/src/main/java/io/legado/app/model/sourceEngine/LegacySourceScriptRunner.kt',
  ).readAsStringSync();
  return RegExp(
        r'internal fun prelude\(library: String\): String =\s*"""([\s\S]*?)"""',
      )
      .firstMatch(kotlin)!
      .group(1)!
      .replaceAll(
        r'$library',
        "globalThis.phaseLoads=(globalThis.phaseLoads||0)+1; "
            "var capturedGet=java.get; var capturedSourceGet=source.get; "
            "function libraryPhase(){return phaseLoads+':'+capturedGet('token')+':'+capturedSourceGet('token');}",
      );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final source = SourceDefinition(
    id: 'continuation-owner',
    name: 'Continuation',
    baseUrl: Uri.parse('https://fixture.invalid/'),
    metadata: {'legacy': true},
  );
  final bindings = {
    'taskId': 'step-task',
    'sourceData': <String, Object?>{},
    '__legacySourceTag': 'Continuation',
    '__legacySourceKey': 'continuation-owner',
    '__legacyExtractionPrefix': 'analyze',
    'book': {'name': 'outer-book'},
  };
  test(
    'actual V8 resumes raw native steps in one VM and restores scoped bindings',
    () async {
      final host = _Steps();
      final engine = createSourceEngine(
        source,
        platform: host,
        useNativeLegacyHttp: true,
      );
      try {
        final value = await engine.evaluateAuxiliary(
          source,
          "Object.defineProperty(globalThis,'result',{value:'outer-result',writable:true,enumerable:false,configurable:true});"
          "globalThis.baseUrl='https://outer.invalid/';"
          "({before:java.get('token'),body:'prefix-'+java.ajax('https://fixture.invalid/raw',8000),"
          "after:java.get('token'),result,baseUrl,book:book.name,runs:phaseRuns,loads:phaseLoads,"
          "enumerable:Object.getOwnPropertyDescriptor(globalThis,'result').enumerable,"
          "scope:typeof globalThis.__legacyHttpStep,header:globalThis.__legacyHeaderEvaluation===true})",
          bindings: bindings,
          prelude: _ownerPrelude(),
        );
        expect(value, {
          'before': 'outer-book',
          'body': 'prefix-native-body',
          'after': 'outer-book',
          'result': 'outer-result',
          'baseUrl': 'https://outer.invalid/',
          'book': 'outer-book',
          'runs': 2,
          'loads': 1,
          'enumerable': false,
          'scope': 'undefined',
          'header': false,
        });
        expect(host.outcomes, [
          {'ok': true, 'value': '1:::value:source-value'},
          {'ok': true, 'value': 'body-input:1:::echo'},
        ]);
        expect(host.callbacks, hasLength(5));
        expect(host.aborted, ['step-token']);
      } finally {
        await engine.close();
      }
    },
  );
  test(
    'script failure restores bindings and aborts the original native session',
    () async {
      final host = _Steps(failScript: true);
      final engine = createSourceEngine(
        source,
        platform: host,
        useNativeLegacyHttp: true,
      );
      try {
        await expectLater(
          engine.evaluateAuxiliary(
            source,
            "globalThis.result='outer-result';java.ajax('https://fixture.invalid/raw',8000)",
            bindings: bindings,
            prelude: _ownerPrelude(),
          ),
          throwsA(
            isA<EngineException>().having(
              (error) => error.code,
              'code',
              'script_error',
            ),
          ),
        );
        expect(host.aborted, ['step-token']);
        expect(host.outcomes.single['ok'], false);
        expect(
          await engine.evaluateAuxiliary(
            source,
            "({result,book:book.name,scope:typeof globalThis.__legacyHttpStep,token:java.get('token')})",
            bindings: bindings,
            prelude: _ownerPrelude(),
          ),
          {
            'result': 'outer-result',
            'book': 'outer-book',
            'scope': 'undefined',
            'token': 'outer-book',
          },
        );
      } finally {
        await engine.close();
      }
    },
  );
}
