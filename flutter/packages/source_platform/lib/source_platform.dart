import 'package:flutter/services.dart';
import 'package:source_engine/source_engine.dart';

/// Android session and storage adapter. No rule semantics live here.
class SourcePlatform implements ScriptHost {
  const SourcePlatform({required this.sourceId});
  final String sourceId;
  static const _channel = MethodChannel('legado/source_platform');
  Future<String?> read(String sourceId, String key) =>
      _channel.invokeMethod<String>('read', {'sourceId': sourceId, 'key': key});
  Future<void> write(String sourceId, String key, String? value) =>
      _channel.invokeMethod<void>('write', {
        'sourceId': sourceId,
        'key': key,
        'value': value,
      });

  /// Committed on the native storage worker before success is returned.
  Future<void> writeSession(String sourceId, String key, String value) =>
      _channel.invokeMethod<void>('writeSession', {
        'sourceId': sourceId,
        'key': key,
        'value': value,
      });

  Future<String?> cookies(String url) =>
      _channel.invokeMethod<String>('cookies', {'url': url});
  Future<void> setCookie(String url, String value) =>
      _channel.invokeMethod<void>('setCookie', {'url': url, 'value': value});

  /// Requires a host-provided UI adapter; absence is an explicit capability error.
  Future<Map<String, Object?>?> browser(Map<String, Object?> request) =>
      const MethodChannel('legado/source_host_platform')
          .invokeMapMethod<String, Object?>('browser', request);
  void _checkPublicKey(String key) {
    if (key.startsWith('__engine.session.')) {
      throw const EngineException(
        'reserved_storage_key',
        'Engine session keys are reserved',
      );
    }
  }

  @override
  Future<Object?> call(String method, List<Object?> arguments) async {
    switch (method) {
      case 'browser.open':
        return browser({
          'sourceId': sourceId,
          'url': arguments.first as String,
          'title': arguments.length > 1 ? arguments[1] as String : '',
          'taskId': arguments.length > 2 ? arguments[2] as String : null,
        });
      case 'storage.read':
        _checkPublicKey(arguments.first as String);
        return read(sourceId, arguments.first as String);
      case 'storage.write':
        _checkPublicKey(arguments.first as String);
        await write(sourceId, arguments[0] as String, arguments[1] as String?);
        return null;
      default:
        throw EngineException(
          'unsupported_host_api',
          'Unsupported platform API $method',
        );
    }
  }
}
