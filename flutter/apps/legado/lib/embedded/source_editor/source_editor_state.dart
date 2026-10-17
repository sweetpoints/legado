import 'package:freezed_annotation/freezed_annotation.dart';
part 'source_editor_state.freezed.dart';

Map<String, Object?> editorMap(Object? value) =>
    Map<String, Object?>.from(value as Map);

@Freezed(fromJson: false, toJson: false)
abstract class SourceEditorField with _$SourceEditorField {
  const SourceEditorField._();
  const factory SourceEditorField({
    required String key,
    required String value,
    required String label,
    @Default(0) int selectionStart,
    @Default(0) int selectionEnd,
  }) = _SourceEditorField;
  factory SourceEditorField.fromMap(Map<String, Object?> m) {
    final field = SourceEditorField(
      key: m['key'] as String,
      value: m['value'] as String,
      label: m['label'] as String,
      selectionStart: m['selectionStart'] as int,
      selectionEnd: m['selectionEnd'] as int,
    );
    if (field.key.isEmpty ||
        field.selectionStart < 0 ||
        field.selectionEnd < 0 ||
        field.selectionStart > field.value.length ||
        field.selectionEnd > field.value.length) {
      throw const FormatException('invalid_editor_field');
    }
    return field;
  }
}

@Freezed(fromJson: false, toJson: false)
abstract class SourceEditorOptions with _$SourceEditorOptions {
  const SourceEditorOptions._();
  const factory SourceEditorOptions({
    required bool enabled,
    required bool enabledExplore,
    required bool enabledCookieJar,
    required bool enabledReview,
    required int type,
    required bool eventListener,
    required bool customButton,
  }) = _SourceEditorOptions;
  factory SourceEditorOptions.fromMap(Map<String, Object?> m) {
    final result = SourceEditorOptions(
      enabled: m['enabled'] as bool,
      enabledExplore: m['enabledExplore'] as bool,
      enabledCookieJar: m['enabledCookieJar'] as bool,
      enabledReview: m['enabledReview'] as bool,
      type: m['type'] as int,
      eventListener: m['eventListener'] as bool,
      customButton: m['customButton'] as bool,
    );
    if (result.type < 0 || result.type > 4) {
      throw const FormatException('invalid_editor_type');
    }
    return result;
  }
  Map<String, Object?> toMap() => {
    'enabled': enabled,
    'enabledExplore': enabledExplore,
    'enabledCookieJar': enabledCookieJar,
    'enabledReview': enabledReview,
    'type': type,
    'eventListener': eventListener,
    'customButton': customButton,
  };
}

@Freezed(fromJson: false, toJson: false)
abstract class SourceEditorDocument with _$SourceEditorDocument {
  const SourceEditorDocument._();
  const factory SourceEditorDocument({
    required int revision,
    required int selectedTab,
    String? focusedKey,
    required bool optionsExpanded,
    required bool autoComplete,
    required bool finished,
    required bool dirty,
    required bool hasLogin,
    required SourceEditorOptions options,
    required List<List<SourceEditorField>> tabs,
  }) = _SourceEditorDocument;
  factory SourceEditorDocument.fromMap(Map<String, Object?> m) {
    final form = editorMap(m['form']);
    final tabs = (form['tabs'] as List)
        .map(
          (e) => (e as List)
              .map((f) => SourceEditorField.fromMap(editorMap(f)))
              .toList(),
        )
        .toList();
    final selected = m['selectedTab'] as int;
    if (tabs.length != 7 ||
        selected < 0 ||
        selected >= tabs.length ||
        tabs.any((tab) => tab.map((f) => f.key).toSet().length != tab.length)) {
      throw const FormatException('invalid_editor_tabs');
    }
    return SourceEditorDocument(
      revision: m['revision'] as int,
      selectedTab: selected,
      focusedKey: m['focusedKey'] as String?,
      optionsExpanded: m['optionsExpanded'] as bool,
      autoComplete: m['autoComplete'] as bool,
      finished: m['finished'] as bool,
      dirty: m['dirty'] as bool,
      hasLogin: m['hasLogin'] as bool,
      options: SourceEditorOptions.fromMap(editorMap(form['options'])),
      tabs: tabs,
    );
  }
}

@Freezed(fromJson: false, toJson: false)
abstract class SourceEditorMigration with _$SourceEditorMigration {
  const SourceEditorMigration._();
  const factory SourceEditorMigration({
    required int revision,
    required String status,
    required bool requiresManualWork,
    required bool canApply,
    required List<Map<String, Object?>> issues,
  }) = _SourceEditorMigration;
  factory SourceEditorMigration.fromMap(Map<String, Object?> m) =>
      SourceEditorMigration(
        revision: m['revision'] as int,
        status: m['status'] as String,
        requiresManualWork: m['requiresManualWork'] as bool,
        canApply: m['canApply'] as bool,
        issues: (m['issues'] as List).map(editorMap).toList(),
      );
}

@Freezed(fromJson: false, toJson: false)
abstract class SourceEditorSnapshot with _$SourceEditorSnapshot {
  const SourceEditorSnapshot._();
  const factory SourceEditorSnapshot({
    SourceEditorDocument? document,
    @Default(false) bool busy,
    String? error,
    @Default(false) bool confirmDiscard,
    List<String>? groups,
    String? variable,
    String? variableComment,
    @Default(6) int maxLines,
    @Default(1) int keyboardRows,
    @Default([]) List<Map<String, String>> assists,
    @Default(false) bool migrationAvailable,
    @Default(false) bool migrationRunning,
    String? migrationError,
    SourceEditorMigration? migrationReport,
  }) = _SourceEditorSnapshot;
  factory SourceEditorSnapshot.fromMap(Map<String, Object?> m) {
    if (m['schemaVersion'] != 1) {
      throw const FormatException('unsupported_editor_snapshot');
    }
    return SourceEditorSnapshot(
      document: m['document'] == null
          ? null
          : SourceEditorDocument.fromMap(editorMap(m['document'])),
      busy: m['busy'] as bool,
      error: m['error'] as String?,
      confirmDiscard: m['confirmDiscard'] as bool,
      groups: m['groups'] == null
          ? null
          : List<String>.from(m['groups'] as List),
      variable: m['variable'] as String?,
      variableComment: m['variableComment'] as String?,
      maxLines: (m['maxLines'] as int?) ?? 6,
      keyboardRows: m['keyboardRows'] as int,
      assists: (m['assists'] as List)
          .map((e) => Map<String, String>.from(e as Map))
          .toList(),
      migrationAvailable: m['migrationAvailable'] as bool,
      migrationRunning: m['migrationRunning'] as bool,
      migrationError: m['migrationError'] as String?,
      migrationReport: m['migrationReport'] == null
          ? null
          : SourceEditorMigration.fromMap(editorMap(m['migrationReport'])),
    );
  }
}
