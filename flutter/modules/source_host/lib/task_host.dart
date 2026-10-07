import 'package:source_engine/source_engine.dart';

/// Task identity is owned by the caller, never by source script arguments.
class TaskScriptHost implements ScriptHost {
  TaskScriptHost(this.delegate, this.taskId);
  final ScriptHost delegate;
  final Object? taskId;
  static const taskMethods = {
    'legacyRule.evaluate',
    'batch.cacheContent',
    'browser.open',
    'browser.show',
    'browser.start',
    'browser.openUrl',
    'browser.video',
    'javaHost.cryptoCreate',
    'javaHost.cryptoCall',
    'javaHost.aesBase64DecodeToString',
    'javaHost.desEncodeToBase64String',
    'javaHost.getWebViewUA',
    'javaHost.HMacHex',
    'javaHost.HMacBase64',
    'javaHost.androidId',
    'javaHost.randomUUID',
    'javaHost.toNumChapter',
    'javaHost.log',
    'javaHost.logType',
    'javaHost.toast',
    'javaHost.longToast',
    'javaHost.timeFormat',
    'javaHost.timeFormatUTC',
    'javaHost.t2s',
    'javaHost.s2t',
    'javaHost.getCookie',
    'replacement.log',
    'replacement.logType',
    'replacement.t2s',
    'replacement.s2t',
    'replacement.get',
    'replacement.put',
    'localBook.putVolume',
    'analyze.get',
    'analyze.put',
    'analyze.getString',
    'analyze.getStringList',
    'analyze.getElements',
    'analyze.getElement',
    'crypto.randomInt32',
    'ui.get',
    'ui.put',
    'ui.searchBook',
    'ui.addBook',
    'ui.showPhoto',
    'ui.open',
    'ui.getString',
    'ui.getStringList',
    'ui.setContent',
    'ui.setBaseUrl',
    'ui.setRedirectUrl',
    'ui.copyText',
    'ui.upLoginData',
    'ui.reLoginView',
    'ui.refreshExplore',
    'ui.clearTtsCache',
    'sourceState.getLoginInfo',
    'sourceState.putLoginInfo',
    'sourceState.getLoginHeader',
    'sourceState.putLoginHeader',
    'sourceState.getVariable',
    'sourceState.putVariable',
    'sourceState.removeLoginInfo',
  };
  @override
  Future<Object?> call(String method, List<Object?> arguments) async {
    if (taskMethods.contains(method)) {
      if (taskId is! String || (taskId as String).isEmpty) {
        throw const EngineException(
          'invalid_request',
          'Host call requires a task',
        );
      }
      return delegate.call(method, [
        ...arguments,
        {'__sourceTaskId': taskId},
      ]);
    }
    return delegate.call(method, arguments);
  }
}
