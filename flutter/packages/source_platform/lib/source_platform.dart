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
      case 'browser.show':
      case 'browser.start':
      case 'browser.openUrl':
      case 'browser.video':
      case 'javaHost.cryptoCreate':
      case 'javaHost.cryptoCall':
      case 'javaHost.aesBase64DecodeToString':
      case 'javaHost.desEncodeToBase64String':
      case 'javaHost.getWebViewUA':
      case 'javaHost.HMacHex':
      case 'javaHost.HMacBase64':
      case 'javaHost.androidId':
      case 'javaHost.randomUUID':
      case 'javaHost.toNumChapter':
      case 'javaHost.log':
      case 'javaHost.logType':
      case 'javaHost.toast':
      case 'javaHost.longToast':
      case 'javaHost.timeFormat':
      case 'javaHost.timeFormatUTC':
      case 'javaHost.t2s':
      case 'javaHost.s2t':
      case 'javaHost.getCookie':
      case 'replacement.log':
      case 'replacement.logType':
      case 'replacement.t2s':
      case 'replacement.s2t':
      case 'replacement.get':
      case 'replacement.put':
      case 'localBook.putVolume':
      case 'analyze.get':
      case 'analyze.put':
      case 'analyze.getString':
      case 'analyze.getStringList':
      case 'analyze.getElements':
      case 'analyze.getElement':
      case 'crypto.randomInt32':
      case 'ui.get':
      case 'ui.put':
      case 'ui.searchBook':
      case 'ui.addBook':
      case 'ui.showPhoto':
      case 'ui.open':
      case 'ui.getString':
      case 'ui.getStringList':
      case 'ui.setContent':
      case 'ui.setBaseUrl':
      case 'ui.setRedirectUrl':
      case 'ui.copyText':
      case 'ui.upLoginData':
      case 'ui.reLoginView':
      case 'ui.refreshExplore':
      case 'ui.clearTtsCache':
      case 'sourceState.getLoginInfo':
      case 'sourceState.putLoginInfo':
      case 'sourceState.getLoginHeader':
      case 'sourceState.putLoginHeader':
      case 'sourceState.getVariable':
      case 'sourceState.putVariable':
      case 'sourceState.removeLoginInfo':
      case 'legacyRule.evaluate':
      case 'batch.cacheContent':
        if (arguments.isEmpty ||
            arguments.last is! Map ||
            (arguments.last as Map)['__sourceTaskId'] is! String) {
          throw const EngineException(
            'invalid_request',
            'Host call requires a task',
          );
        }
        final taskId = (arguments.last as Map)['__sourceTaskId'] as String;
        final result = await const MethodChannel('legado/source_host_platform')
            .invokeMethod<Object?>('call', {
              'sourceId': sourceId,
              'taskId': taskId,
              'method': method,
              'arguments': arguments.sublist(0, arguments.length - 1),
            });
        if (method == 'batch.cacheContent' && result is! bool) {
          throw const EngineException(
            'invalid_host_response',
            'cacheContent must return a boolean',
          );
        }
        return result;
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
