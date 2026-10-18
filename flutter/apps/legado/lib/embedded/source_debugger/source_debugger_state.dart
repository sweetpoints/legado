import 'package:freezed_annotation/freezed_annotation.dart';

part 'source_debugger_state.freezed.dart';

@Freezed(fromJson: false, toJson: false)
abstract class SourceDebuggerSort with _$SourceDebuggerSort {
  const factory SourceDebuggerSort({
    required String name,
    required String query,
    required bool enabled,
  }) = _SourceDebuggerSort;
}

@Freezed(fromJson: false, toJson: false)
abstract class SourceDebuggerSnapshot with _$SourceDebuggerSnapshot {
  const SourceDebuggerSnapshot._();
  const factory SourceDebuggerSnapshot({
    required int generation,
    required int revision,
    required String kind,
    @Default('') String name,
    @Default('') String keyword,
    @Default('') String query,
    @Default(0) int queryStart,
    @Default(0) int queryEnd,
    @Default('') String output,
    @Default(true) bool help,
    @Default(true) bool loading,
    @Default(false) bool loaded,
    @Default(false) bool running,
    @Default(false) bool missing,
    @Default(false) bool closed,
    @Default([]) List<SourceDebuggerSort> sorts,
    @Default(0) int selectedSort,
    @Default([]) List<String> htmlStages,
    String? error,
    String? issue,
    @Default(false) bool canRetry,
  }) = _SourceDebuggerSnapshot;

  bool get isBook => kind == 'book';
  bool get enabled => loaded && !closed && !missing;
  List<String> get stages => isBook
      ? const ['search', 'book', 'toc', 'content']
      : const ['list', 'content'];

  factory SourceDebuggerSnapshot.fromMap(Object? value) {
    try {
      final m = Map<String, Object?>.from(value as Map);
      if (m['schemaVersion'] != 1) throw const FormatException();
      final state = SourceDebuggerSnapshot(
        generation: m['generation'] as int,
        revision: m['revision'] as int,
        kind: m['kind'] as String,
        name: m['name'] as String,
        keyword: m['keyword'] as String,
        query: m['query'] as String,
        queryStart: m['queryStart'] as int,
        queryEnd: m['queryEnd'] as int,
        output: m['output'] as String,
        help: m['help'] as bool,
        loading: m['loading'] as bool,
        loaded: m['loaded'] as bool,
        running: m['running'] as bool,
        missing: m['missing'] as bool,
        closed: m['closed'] as bool,
        sorts: (m['sorts'] as List).map((v) {
          final sort = Map<String, Object?>.from(v as Map);
          return SourceDebuggerSort(
            name: sort['name'] as String,
            query: sort['query'] as String,
            enabled: sort['enabled'] as bool,
          );
        }).toList(),
        selectedSort: m['selectedSort'] as int,
        htmlStages: List<String>.from(m['htmlStages'] as List),
        error: m['error'] as String?,
        issue: m['issue'] as String?,
        canRetry: m['canRetry'] as bool,
      );
      if (state.generation <= 0 ||
          state.revision <= 0 ||
          !const ['book', 'rss'].contains(state.kind) ||
          state.output.length > 20000 ||
          state.queryStart < 0 ||
          state.queryStart > state.query.length ||
          state.queryEnd < 0 ||
          state.queryEnd > state.query.length ||
          state.selectedSort < 0 ||
          (state.sorts.isNotEmpty &&
              state.selectedSort >= state.sorts.length) ||
          (state.issue != null &&
              !const ['busy', 'interrupted'].contains(state.issue)) ||
          state.htmlStages.any((s) => !state.stages.contains(s))) {
        throw const FormatException();
      }
      return state;
    } catch (_) {
      throw const FormatException('invalid_source_debugger_snapshot');
    }
  }
}
