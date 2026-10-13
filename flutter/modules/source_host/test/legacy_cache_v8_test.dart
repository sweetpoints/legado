import 'package:flutter_test/flutter_test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_legacy/src/legacy_cache.dart';
import 'package:source_host/main.dart' show createSourceEngine;
import 'package:source_v8/source_v8.dart';

class CacheHost implements ScriptHost {
  final values = <String, Object?>{};
  final calls = <(String, List<Object?>)>[];
  @override
  Future<Object?> call(String method, List<Object?> args) async {
    if (args.isNotEmpty &&
        args.last is Map &&
        (args.last as Map).containsKey('__sourceTaskId')) {
      expect((args.last as Map)['__sourceTaskId'], 'cache-production-task');
      args = args.sublist(0, args.length - 1);
    }
    calls.add((method, args));
    final key = args.first as String;
    switch (method) {
      case 'cacheHost.put':
      case 'cacheHost.putMemory':
        values[key] = args[1];
        return null;
      case 'cacheHost.getFromMemory':
      case 'cacheHost.getByteArray':
        return values[key];
      case 'cacheHost.get':
        return (values[key] as Map?)?['value'];
      case 'cacheHost.delete':
        values.remove(key);
        return null;
      default:
        throw StateError('Unknown fixture cache method');
    }
  }
}

void main() {
  test('actual V8 cache methods preserve typed bytes and memory numbers', () async {
    final host = CacheHost();
    final runtime = V8Runtime(prelude: legacyCachePrelude);
    try {
      expect(
        await runtime.evaluateAuxiliary(
          'cache.put("text",[1,2],60);cache.putMemory("number",7);'
          'cache.put("bytes",__legacyCacheMarkBytes([-1,0,127]));'
          'const bytes=cache.getByteArray("bytes");cache.put("copied",bytes);'
          'cache.delete("text");({type:typeof cache,get:typeof cache.get,number:cache.getFromMemory("number"),'
          'bytes,deleted:cache.get("text"),reflection:typeof cache.getClass})',
          ScriptContext(host: host),
        ),
        {
          'type': 'object',
          'get': 'function',
          'number': 7,
          'bytes': [-1, 0, 127],
          'deleted': null,
          'reflection': 'undefined',
        },
      );
      expect(host.calls.first.$2, [
        'text',
        {'kind': 'string', 'value': '1,2'},
        60,
      ]);
      expect(host.values['number'], {'kind': 'json', 'value': 7});
      expect(host.values['copied'], {
        'kind': 'bytes',
        'value': [-1, 0, 127],
      });
    } finally {
      await runtime.close();
    }
  });
  test(
    'production legacy runtime tags actual java bytes for real cache API',
    () async {
      final source = SourceDefinition(
        id: 'cache-production-fixture',
        name: 'Cache fixture',
        baseUrl: Uri.parse('https://fixture.invalid'),
        metadata: {'legacy': true},
        script:
            'async function search(input){cache.put("bytes",java.strToBytes("byte-fixture"));'
            'cache.put("plain",[65,66]);cache.put("copy",cache.getByteArray("bytes"));'
            'return [{name:java.bytesToStr(cache.getByteArray("bytes"))}];}',
      );
      final host = CacheHost();
      final engine = createSourceEngine(source, platform: host);
      try {
        expect(
          await engine.execute(
            source,
            'search',
            input: {'taskId': 'cache-production-task'},
          ),
          [
            {'name': 'byte-fixture'},
          ],
        );
        expect((host.values['bytes'] as Map)['kind'], 'bytes');
        expect(host.values['plain'], {'kind': 'string', 'value': '65,66'});
        expect(host.values['copy'], host.values['bytes']);
      } finally {
        await engine.close();
      }
    },
  );
  test('production modern runtime has no legacy cache namespace', () async {
    final source = SourceDefinition(
      id: 'modern-cache-fixture',
      name: 'Modern fixture',
      baseUrl: Uri.parse('https://fixture.invalid'),
      script: 'async function search(input){return [{name:typeof cache}];}',
    );
    final engine = createSourceEngine(source, platform: CacheHost());
    try {
      expect(
        await engine.execute(
          source,
          'search',
          input: {'taskId': 'cache-production-task'},
        ),
        [
          {'name': 'undefined'},
        ],
      );
    } finally {
      await engine.close();
    }
  });
}
