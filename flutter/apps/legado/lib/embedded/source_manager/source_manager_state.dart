import 'package:freezed_annotation/freezed_annotation.dart';

part 'source_manager_state.freezed.dart';

@Freezed(fromJson: false, toJson: false)
abstract class SourceManagerRow with _$SourceManagerRow {
  const SourceManagerRow._();
  const factory SourceManagerRow({
    required String url,
    required String name,
    String? group,
    @Default(0) int order,
    @Default(false) bool enabled,
    @Default(false) bool exploreEnabled,
    @Default(false) bool hasExplore,
    @Default(false) bool hasLogin,
    @Default(false) bool hasJs,
    @Default(0) int updated,
    @Default(0) int response,
    @Default(0) int weight,
    @Default('NEEDS_CHECK') String checkStatus,
    @Default('') String checkDetail,
    @Default('#') String host,
  }) = _SourceManagerRow;
  String get displayName =>
      group == null || group!.trim().isEmpty ? name : '$name ($group)';
  factory SourceManagerRow.fromJson(Map<String, Object?> json) =>
      SourceManagerRow(
        url: json['url'] as String,
        name: json['name'] as String,
        group: json['group'] as String?,
        order: json['order'] as int,
        enabled: json['enabled'] as bool,
        exploreEnabled: json['exploreEnabled'] as bool,
        hasExplore: json['hasExplore'] as bool,
        hasLogin: json['hasLogin'] as bool,
        hasJs: json['hasJs'] as bool,
        updated: json['updated'] as int,
        response: json['response'] as int,
        weight: json['weight'] as int,
        checkStatus: json['checkStatus'] as String,
        checkDetail: json['checkDetail'] as String,
        host: json['host'] as String,
      );
}

@Freezed(fromJson: false, toJson: false)
abstract class SourceManagerFeedback with _$SourceManagerFeedback {
  const SourceManagerFeedback._();
  const factory SourceManagerFeedback({
    required String url,
    required String summary,
    required bool canSharePassphrase,
    String? passphrase,
  }) = _SourceManagerFeedback;
  factory SourceManagerFeedback.fromJson(Map<String, Object?> json) =>
      SourceManagerFeedback(
        url: json['url'] as String,
        summary: json['summary'] as String,
        canSharePassphrase: json['canSharePassphrase'] as bool,
        passphrase: json['passphrase'] as String?,
      );
}

@Freezed(fromJson: false, toJson: false)
abstract class SourceManagerSnapshot with _$SourceManagerSnapshot {
  const SourceManagerSnapshot._();
  const factory SourceManagerSnapshot({
    @Default('') String query,
    @Default([]) List<SourceManagerRow> rows,
    @Default([]) List<String> groups,
    @Default({}) Set<String> selected,
    @Default({}) Map<String, int> counts,
    @Default('Default') String sort,
    @Default(true) bool ascending,
    @Default(false) bool byDomain,
    @Default(false) bool showStatus,
    @Default('') String status,
    @Default(false) bool busy,
    @Default(true) bool loading,
    String? error,
    String? checkMessage,
    @Default({}) Map<String, String> debugMessages,
    String? dialog,
    @Default('') String draft,
    @Default(false) bool blockNavigation,
    String? draggingKey,
    @Default([]) List<String> importHistory,
    SourceManagerFeedback? feedback,
    @Default(false) bool canMove,
  }) = _SourceManagerSnapshot;
  int get selectedCount =>
      rows.where((row) => selected.contains(row.url)).length;
  static const sorts = [
    'Default',
    'Weight',
    'Name',
    'Url',
    'Update',
    'Respond',
    'Enable',
  ];
  static const dialogs = [
    'DELETE',
    'ADD_GROUP',
    'REMOVE_GROUP',
    'IMPORT',
    'CHECK',
    'EXPORT_SUCCESS',
    'PASSPHRASE',
  ];
  factory SourceManagerSnapshot.fromJson(Map<String, Object?> json) {
    if (json['schemaVersion'] != 1 ||
        !sorts.contains(json['sort']) ||
        (json['dialog'] != null && !dialogs.contains(json['dialog']))) {
      throw const FormatException('unsupported_source_manager_snapshot');
    }
    final rows = (json['rows'] as List)
        .map(
          (e) => SourceManagerRow.fromJson(Map<String, Object?>.from(e as Map)),
        )
        .toList();
    if (rows.map((e) => e.url).toSet().length != rows.length ||
        rows.any((e) => e.url.isEmpty)) {
      throw const FormatException('invalid_source_manager_rows');
    }
    return SourceManagerSnapshot(
      query: json['query'] as String,
      rows: rows,
      groups: List<String>.from(json['groups'] as List),
      selected: Set<String>.from(json['selected'] as List),
      counts: Map<String, int>.from(json['counts'] as Map),
      sort: json['sort'] as String,
      ascending: json['ascending'] as bool,
      byDomain: json['byDomain'] as bool,
      showStatus: json['showStatus'] as bool,
      status: json['status'] as String,
      busy: json['busy'] as bool,
      loading: json['loading'] as bool,
      error: json['error'] as String?,
      checkMessage: json['checkMessage'] as String?,
      debugMessages: Map<String, String>.from(json['debugMessages'] as Map),
      dialog: json['dialog'] as String?,
      draft: json['draft'] as String,
      blockNavigation: json['blockNavigation'] as bool,
      draggingKey: json['draggingKey'] as String?,
      importHistory: List<String>.from(json['importHistory'] as List),
      feedback: json['feedback'] == null
          ? null
          : SourceManagerFeedback.fromJson(
              Map<String, Object?>.from(json['feedback'] as Map),
            ),
      canMove: json['canMove'] as bool,
    );
  }
}
