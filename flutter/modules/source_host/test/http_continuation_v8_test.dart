import 'dart:async';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_host/main.dart' show createSourceEngine;

class _Steps implements ScriptHost {
  final aborted = <String>[];
  final outcomes = <Map>[];
  final callbacks = <List<Object?>>[];
  final bool failScript;
  final bool busyScript;
  final started = Completer<void>();
  _Steps({this.failScript = false, this.busyScript = false});

  Map<String, Object?> script(int sequence) => {
    'status': 'script',
    'token': 'step-token',
    'sequence': sequence,
    'script': busyScript
        ? "globalThis.phaseRuns=(globalThis.phaseRuns||0)+1;java.get('started');while(true){}"
        : failScript
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
        if (busyScript && !started.isCompleted) started.complete();
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

class _NestedHeaders implements ScriptHost {
  final headers = <Map>[];
  final aborted = <String>[];
  Map? outerResult;
  @override
  Future<Object?> call(String method, List<Object?> arguments) async {
    expect(arguments.last, {
      '__sourceTaskId': 'step-task',
      '__sourceHostCallback': true,
    });
    final args = arguments.sublist(0, arguments.length - 1);
    switch (method) {
      case 'analyze.get':
        return 'outer-book';
      case 'javaHttp.headerGet':
        return 'source-token';
      case 'javaHttp.prepareHeader':
        return {
          'script': "({'X-Token':capturedGet('token')+'/'+capturedSourceGet('token')})",
          'count': 1,
        };
      case 'javaHttp.begin':
        final evaluations = args[2] as List;
        expect((evaluations.single as Map)['failed'], false);
        headers.add((evaluations.single as Map)['value'] as Map);
        if (((args[1] as List).first as String).endsWith('/inner')) {
          return {
            'status': 'done',
            'token': 'inner-token',
            'value': 'inner-body',
          };
        }
        return {
          'status': 'script',
          'token': 'outer-token',
          'sequence': 1,
          'script': "var before=java.get('token');var inner=java.ajax('https://fixture.invalid/inner');({before,inner,after:java.get('token'),alias:capturedGet('token')})",
          'bindings': {'result': 'outer-step'},
        };
      case 'javaHttp.stepCall':
        expect(args.take(3).toList(), ['outer-token', 1, 'get']);
        return 'url-scope';
      case 'javaHttp.continue':
        expect(args.take(2).toList(), ['outer-token', 1]);
        expect((args[2] as Map)['ok'], true);
        outerResult = (args[2] as Map)['value'] as Map;
        return {
          'status': 'done',
          'token': 'outer-token',
          'value': 'outer-body',
        };
      case 'javaHttp.abort':
        aborted.add(args.single as String);
        return null;
      default:
        throw StateError('Unexpected nested HTTP callback $method');
    }
  }
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
  test('hard cancellation restores host frames before the next caller bindings without resetting globals', () async {
    final host = _Steps(busyScript: true);
    final engine = createSourceEngine(
      source,
      platform: host,
      useNativeLegacyHttp: true,
    );
    final token = CancellationToken();
    try {
      final pending = engine.evaluateAuxiliary(
        source,
        "globalThis.result='outer-result';java.ajax('https://fixture.invalid/raw',8000)",
        bindings: bindings,
        prelude: _ownerPrelude(),
        cancellation: token,
      );
      await host.started.future.timeout(const Duration(seconds: 5));
      final timer = Timer(const Duration(milliseconds: 30), token.cancel);
      try {
        await expectLater(
          pending,
          throwsA(
            isA<EngineException>().having(
              (error) => error.code,
              'code',
              'cancelled',
            ),
          ),
        );
      } finally {
        timer.cancel();
      }
      expect(
        await engine.evaluateAuxiliary(
          source,
          "({result,book:book.name,baseUrl,phaseRuns,phaseLoads,scope:typeof __legacyHttpStep,header:globalThis.__legacyHeaderEvaluation===true,token:java.get('token')})",
          bindings: {
            ...bindings,
            'book': {'name': 'fresh-book'},
            'result': 'fresh-result',
            'baseUrl': 'https://fresh.invalid/',
          },
          prelude: _ownerPrelude(),
        ),
        {
          'result': 'fresh-result',
          'book': 'fresh-book',
          'baseUrl': 'https://fresh.invalid/',
          'phaseRuns': 1,
          'phaseLoads': 1,
          'scope': 'undefined',
          'header': false,
          'token': 'outer-book',
        },
      );
    } finally {
      await engine.close();
    }
  });
  test('nested dynamic header overrides URL-step scope only until its own request completes', () async {
    final host = _NestedHeaders();
    final engine = createSourceEngine(
      source,
      platform: host,
      useNativeLegacyHttp: true,
    );
    try {
      expect(
        await engine.evaluateAuxiliary(
          source,
          "({before:java.get('token'),body:java.ajax('https://fixture.invalid/outer'),after:java.get('token'),scope:typeof __legacyHttpStep})",
          bindings: bindings,
          prelude: _ownerPrelude(),
        ),
        {
          'before': 'outer-book',
          'body': 'outer-body',
          'after': 'outer-book',
          'scope': 'undefined',
        },
      );
      expect(host.headers, [
        {'X-Token': 'source-token/source-token'},
        {'X-Token': 'source-token/source-token'},
      ]);
      expect(host.outerResult, {
        'before': 'url-scope',
        'inner': 'inner-body',
        'after': 'url-scope',
        'alias': 'url-scope',
      });
      expect(host.aborted, ['inner-token', 'outer-token']);
    } finally {
      await engine.close();
    }
  });
}
