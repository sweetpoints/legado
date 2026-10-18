import 'package:freezed_annotation/freezed_annotation.dart';
part 'code_editor_state.freezed.dart';

Map<String, Object?> codeEditorMap(Object? value) =>
    Map<String, Object?>.from(value as Map);

@Freezed(fromJson: false, toJson: false)
abstract class CodeEditorSearch with _$CodeEditorSearch {
  const CodeEditorSearch._();
  const factory CodeEditorSearch({
    @Default('') String query,
    @Default('') String replacement,
    @Default(false) bool visible,
    @Default(false) bool replaceVisible,
    @Default(true) bool regex,
    @Default(false) bool ignoreCase,
    @Default(0) int queryStart,
    @Default(0) int queryEnd,
    @Default(0) int replacementStart,
    @Default(0) int replacementEnd,
  }) = _CodeEditorSearch;
  factory CodeEditorSearch.fromMap(Map<String, Object?> m) {
    final search = CodeEditorSearch(
      query: m['query'] as String,
      replacement: m['replacement'] as String,
      visible: m['visible'] as bool,
      replaceVisible: m['replaceVisible'] as bool,
      regex: m['regex'] as bool,
      ignoreCase: m['ignoreCase'] as bool,
      queryStart: m['queryStart'] as int,
      queryEnd: m['queryEnd'] as int,
      replacementStart: m['replacementStart'] as int,
      replacementEnd: m['replacementEnd'] as int,
    );
    if (search.query.length > 65536 ||
        search.replacement.length > 65536 ||
        search.queryStart < 0 ||
        search.queryEnd < 0 ||
        search.queryStart > search.query.length ||
        search.queryEnd > search.query.length ||
        search.replacementStart < 0 ||
        search.replacementEnd < 0 ||
        search.replacementStart > search.replacement.length ||
        search.replacementEnd > search.replacement.length) {
      throw const FormatException('invalid_code_editor_search');
    }
    return search;
  }
  Map<String, Object?> toMap() => {
    'query': query,
    'replacement': replacement,
    'visible': visible,
    'replaceVisible': replaceVisible,
    'regex': regex,
    'ignoreCase': ignoreCase,
    'queryStart': queryStart,
    'queryEnd': queryEnd,
    'replacementStart': replacementStart,
    'replacementEnd': replacementEnd,
  };
}

@Freezed(fromJson: false, toJson: false)
abstract class CodeEditorEngine with _$CodeEditorEngine {
  const CodeEditorEngine._();
  const factory CodeEditorEngine({
    @Default(false) bool ready,
    @Default(false) bool reading,
    @Default(false) bool failed,
    @Default(false) bool replacing,
    String? replacementError,
    @Default('0') String searchResult,
  }) = _CodeEditorEngine;
  factory CodeEditorEngine.fromMap(Map<String, Object?> m) => CodeEditorEngine(
    ready: m['ready'] as bool,
    reading: m['reading'] as bool,
    failed: m['failed'] as bool,
    replacing: m['replacing'] as bool,
    replacementError: m['replacementError'] as String?,
    searchResult: m['searchResult'] as String,
  );
}

@Freezed(fromJson: false, toJson: false)
abstract class CodeEditorSnapshot with _$CodeEditorSnapshot {
  const CodeEditorSnapshot._();
  const factory CodeEditorSnapshot({
    @Default(false) bool busy,
    String? error,
    @Default(false) bool confirmDiscard,
    @Default(false) bool hasSession,
    String? title,
    @Default(false) bool writable,
    @Default(false) bool safe,
    @Default(false) bool finished,
    @Default(false) bool showDebugSource,
    @Default(false) bool showLoginSource,
    @Default(false) bool checkJavaScriptSyntax,
    @Default(0) int generation,
    @Default(1) int keyboardRows,
    @Default(false) bool autoWrap,
    @Default([]) List<Map<String, String>> assists,
    @Default(CodeEditorSearch()) CodeEditorSearch search,
    @Default(CodeEditorEngine()) CodeEditorEngine engine,
  }) = _CodeEditorSnapshot;
  bool get enabled =>
      hasSession &&
      !finished &&
      !busy &&
      engine.ready &&
      !engine.reading &&
      !engine.replacing;
  factory CodeEditorSnapshot.fromMap(Map<String, Object?> m) {
    if (m['schemaVersion'] != 1) {
      throw const FormatException('unsupported_code_editor_snapshot');
    }
    final state = CodeEditorSnapshot(
      busy: m['busy'] as bool,
      error: m['error'] as String?,
      confirmDiscard: m['confirmDiscard'] as bool,
      hasSession: m['hasSession'] as bool,
      title: m['title'] as String?,
      writable: m['writable'] as bool,
      safe: m['safe'] as bool,
      finished: m['finished'] as bool,
      showDebugSource: m['showDebugSource'] as bool,
      showLoginSource: m['showLoginSource'] as bool,
      checkJavaScriptSyntax: (m['checkJavaScriptSyntax'] as bool?) ?? false,
      generation: m['generation'] as int,
      keyboardRows: m['keyboardRows'] as int,
      autoWrap: m['autoWrap'] as bool,
      assists: (m['assists'] as List)
          .map((e) => Map<String, String>.from(e as Map))
          .toList(),
      search: CodeEditorSearch.fromMap(codeEditorMap(m['search'])),
      engine: CodeEditorEngine.fromMap(codeEditorMap(m['engine'])),
    );
    if (state.generation < 0 ||
        (state.hasSession && state.generation == 0) ||
        state.keyboardRows < 1 ||
        state.keyboardRows > 5) {
      throw const FormatException('invalid_code_editor_generation');
    }
    return state;
  }
}
