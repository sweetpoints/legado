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
