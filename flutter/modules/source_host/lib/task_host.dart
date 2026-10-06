import 'package:source_engine/source_engine.dart';

/// Task identity is owned by the caller, never by source script arguments.
class TaskScriptHost implements ScriptHost {
  TaskScriptHost(this.delegate, this.taskId);
  final ScriptHost delegate;
  final Object? taskId;
  static const taskMethods = {
    'batch.cacheContent',
    'browser.open',
    'browser.show',
    'browser.start',
    'browser.openUrl',
    'browser.video',
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
