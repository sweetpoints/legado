import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_host/session_store.dart';
import 'package:source_host/source_host.dart';

class _MemoryStore implements SourceSessionStore {
  final values = <String, Map<String, Object?>>{};
  String key(String id, bool legacy) => '$id:$legacy';
  @override
  Future<Map<String, Object?>?> read(String sourceId, bool legacy) async =>
      values[key(sourceId, legacy)];
  @override
  Future<void> write(
    String sourceId,
    bool legacy,
    Map<String, Object?> value,
  ) async {
    values[key(sourceId, legacy)] = Map<String, Object?>.from(
      jsonDecode(jsonEncode(value)) as Map,
    );
  }
}

class _StateRuntime implements ScriptRuntime, SourceRuntimeState {
  _StateRuntime(this.tag);
  final String tag;
  final state = <String, Object?>{};
  bool closed = false;
  @override
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  }) async {
    final input = context.variables;
    if (input['value'] != null) {
      await context.host.call('variables.put', ['saved', input['value']]);
      state['runtimeSaved'] = input['value'];
    }
    final value = input['cookieUrl'] != null
        ? await context.host.call('cookies.get', [input['cookieUrl']])
        : await context.host.call('variables.get', ['saved']);
    return [
      {'name': value ?? '', 'tag': tag, 'runtimeSaved': state['runtimeSaved']},
    ];
  }

  @override
  Map<String, Object?> exportRuntimeState() => Map.of(state);
  @override
  void importRuntimeState(Map<String, Object?> value) => state.addAll(value);
  @override
  Future<void> close() async {
    closed = true;
  }
}

Map<String, Object?> _definition(
  String id, {
  String name = 'Example',
  String baseUrl = 'https://example.org/',
}) => {
  'schemaVersion': 1,
  'id': id,
  'name': name,
  'baseUrl': baseUrl,
  'script': 'async function search(input){}',
};
Future<List<Map>> _execute(
  SourceHost host,
  Map<String, Object?> definition,
  Map<String, Object?> input,
  String task,
) async => (await host.handle(
  MethodCall('execute', {
    'protocolVersion': 1,
    'taskId': task,
    'operation': 'search',
    'sourceJson': jsonEncode(definition),
    'input': input,
  }),
) as List).cast<Map>();

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('host restores variable/runtime state after recreation and invalidates changed config', () async {
    final store = _MemoryStore();
    final runtimes = <_StateRuntime>[];
    SourceEngine factory(SourceDefinition source) {
      final runtime = _StateRuntime(source.name);
      runtimes.add(runtime);
      return SourceEngine(runtime: runtime);
    }

    var host = SourceHost(factory, sessionStore: store);
    final definition = _definition('source-a');
    expect(
      (await _execute(host, definition, {
        'value': 'persisted',
      }, '1')).single['name'],
      'persisted',
    );
    await host.close();
    expect(runtimes.single.closed, true);
    host = SourceHost(factory, sessionStore: store);
    final restored = (await _execute(host, definition, {}, '2')).single;
    expect(restored['name'], 'persisted');
    expect(restored['runtimeSaved'], 'persisted');
    final changed = {...definition, 'name': 'Updated'};
    expect((await _execute(host, changed, {}, '3')).single['tag'], 'Updated');
    expect(runtimes[1].closed, true);
    expect(runtimes.length, 3);
    expect(
      (await _execute(host, _definition('source-b'), {}, '4')).single['name'],
      '',
    );
    expect(
      (await _execute(
        host,
        {...changed, 'baseUrl': 'https://other.example/'},
        {},
        '5',
      )).single['name'],
      '',
    );
    await host.close();
  });
  test(
    'restored cookies retain scope/security/expiry and are source isolated',
    () async {
      final store = _MemoryStore();
      final cookie = <String, Object?>{
        'name': 'session',
        'value': 'example-value',
        'domain': 'example.org',
        'path': '/protected',
        'hostOnly': true,
        'secure': true,
        'httpOnly': true,
        'maxAge': null,
        'expires': '2035-01-01T00:00:00.000Z',
        'created': '2026-10-01T00:00:00.000Z',
      };
      store.values['source-a:false'] = {
        'formatVersion': 1,
        'origin': 'https://example.org',
        'engine': {
          'variables': {},
          'cookies': [cookie],
        },
        'runtime': {},
      };
      final host = SourceHost(
        (source) => SourceEngine(runtime: _StateRuntime(source.name)),
        sessionStore: store,
      );
      final definition = _definition('source-a');
      expect(
        (await _execute(host, definition, {
          'cookieUrl': 'https://example.org/protected/chapter',
        }, '1')).single['name'],
        'session=example-value',
      );
      final saved =
          ((store.values['source-a:false']!['engine'] as Map)['cookies']
                      as List)
                  .single
              as Map;
      for (final key in cookie.keys) {
        expect(saved[key], cookie[key], reason: key);
      }
      for (final url in [
        'http://example.org/protected/chapter',
        'https://sub.example.org/protected/chapter',
        'https://example.org/public',
      ]) {
        expect(
          (await _execute(host, definition, {
            'cookieUrl': url,
          }, url)).single['name'],
          '',
        );
      }
      expect(
        (await _execute(host, _definition('source-b'), {
          'cookieUrl': 'https://example.org/protected/chapter',
        }, 'other')).single['name'],
        '',
      );
      await host.close();
    },
  );
  test('platform session writes use committed channel and distinct execution mode keys', () async {
    const channel = MethodChannel('legado/source_platform');
    final writes = <MethodCall>[];
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(channel, (call) async {
      writes.add(call);
      return null;
    });
    const store = PlatformSessionStore();
    await store.write('source-a', false, {'formatVersion': 1});
    await store.write('source-a', true, {'formatVersion': 1});
    expect(writes.map((call) => call.method), ['writeSession', 'writeSession']);
    expect((writes[0].arguments as Map)['key'], '__engine.session.v1.modern');
    expect((writes[1].arguments as Map)['key'], '__engine.session.v1.legacy');
    messenger.setMockMethodCallHandler(channel, null);
  });
}
