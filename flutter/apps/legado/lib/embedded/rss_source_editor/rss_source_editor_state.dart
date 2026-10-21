import 'package:freezed_annotation/freezed_annotation.dart';
part 'rss_source_editor_state.freezed.dart';

Map<String, Object?> rssEditorMap(Object? value) =>
    Map<String, Object?>.from(value as Map);

@Freezed(fromJson: false, toJson: false)
abstract class RssEditorField with _$RssEditorField {
  const RssEditorField._();
  const factory RssEditorField({
    required String key,
    required String label,
    String? text,
    required int start,
    required int end,
    required bool external,
    required int length,
  }) = _RssEditorField;
  factory RssEditorField.fromMap(Object? value) {
    final m = rssEditorMap(value);
    final f = RssEditorField(
      key: m['key'] as String,
      label: m['label'] as String,
      text: m['text'] as String?,
      start: m['start'] as int,
      end: m['end'] as int,
      external: m['external'] as bool,
      length: m['length'] as int,
    );
    if (f.key.isEmpty ||
        f.length < 0 ||
        f.start < 0 ||
        f.end < 0 ||
        (f.external
            ? f.text != null || f.start != 0 || f.end != 0
            : f.text == null ||
                  f.length != f.text!.length ||
                  f.start > f.length ||
                  f.end > f.length)) {
      throw const FormatException('invalid_rss_editor_field');
    }
    return f;
  }
}

@Freezed(fromJson: false, toJson: false)
abstract class RssEditorTab with _$RssEditorTab {
  const RssEditorTab._();
  const factory RssEditorTab({
    required int index,
    required String label,
    required List<RssEditorField> fields,
  }) = _RssEditorTab;
  factory RssEditorTab.fromMap(Object? value) {
    final m = rssEditorMap(value);
    return RssEditorTab(
      index: m['index'] as int,
      label: m['label'] as String,
      fields: (m['fields'] as List).map(RssEditorField.fromMap).toList(),
    );
  }
}

const rssEditorBooleanOptions = {
  'enabled',
  'singleUrl',
  'cookieJar',
  'preload',
  'enableJs',
  'loadWithBaseUrl',
  'showWebLog',
  'cacheFirst',
};

@Freezed(fromJson: false, toJson: false)
abstract class RssEditorSnapshot with _$RssEditorSnapshot {
  const RssEditorSnapshot._();
  const factory RssEditorSnapshot({
    required int generation,
    required int revision,
    required bool loading,
    required bool loaded,
    required bool busy,
    required bool missing,
    required bool canEdit,
    required bool editorPending,
    required bool finished,
    required bool savedResult,
    required bool exit,
    required bool expanded,
    required bool autoComplete,
    String? error,
    String? issue,
    required int tab,
    String? focus,
    required List<RssEditorTab> tabs,
    required Map<String, Object?> options,
    required List<Map<String, String>> assists,
    required int keyboardRows,
    required int maxLines,
    required List<String> typeLabels,
    required List<String> articleStyleLabels,
  }) = _RssEditorSnapshot;
  factory RssEditorSnapshot.fromMap(Object? value) {
    final m = rssEditorMap(value);
    if (m['schemaVersion'] != 1) {
      throw const FormatException('unsupported_rss_editor_snapshot');
    }
    final result = RssEditorSnapshot(
      generation: m['generation'] as int,
      revision: m['revision'] as int,
      loading: m['loading'] as bool,
      loaded: m['loaded'] as bool,
      busy: m['busy'] as bool,
      missing: m['missing'] as bool,
      canEdit: m['canEdit'] as bool,
      editorPending: m['editorPending'] as bool,
      finished: m['finished'] as bool,
      savedResult: m['savedResult'] as bool,
      exit: m['exit'] as bool,
      expanded: m['expanded'] as bool,
      autoComplete: m['autoComplete'] as bool,
      error: m['error'] as String?,
      issue: m['issue'] as String?,
      tab: m['tab'] as int,
      focus: m['focus'] as String?,
      tabs: (m['tabs'] as List).map(RssEditorTab.fromMap).toList(),
      options: rssEditorMap(m['options']),
      assists: (m['assists'] as List)
          .map((v) => Map<String, String>.from(v as Map))
          .toList(),
      keyboardRows: m['keyboardRows'] as int,
      maxLines: m['maxLines'] as int,
      typeLabels: List<String>.from(m['typeLabels'] as List),
      articleStyleLabels: List<String>.from(m['articleStyleLabels'] as List),
    );
    final keys = result.tabs.expand((t) => t.fields).map((f) => f.key).toList();
    if (result.generation <= 0 ||
        result.revision <= 0 ||
        result.tabs.length != 4 ||
        result.tab < 0 ||
        result.tab > 3 ||
        result.keyboardRows < 1 ||
        result.keyboardRows > 5 ||
        result.maxLines < 1 ||
        result.typeLabels.length != 3 ||
        result.articleStyleLabels.length != 5 ||
        keys.toSet().length != keys.length ||
        List.generate(4, (i) => result.tabs[i].index != i).any((v) => v) ||
        rssEditorBooleanOptions.any((k) => result.options[k] is! bool) ||
        result.options['type'] is! int ||
        result.options['articleStyle'] is! int ||
        !(result.options['type'] as int >= 0 &&
            result.options['type'] as int <= 2) ||
        !(result.options['articleStyle'] as int >= 0 &&
            result.options['articleStyle'] as int <= 4) ||
        (result.focus != null && !keys.contains(result.focus)) ||
        (result.issue != null &&
            !{
              'Required',
              'Format',
              'Focus',
              'NoLogin',
            }.contains(result.issue))) {
      throw const FormatException('invalid_rss_editor_snapshot');
    }
    return result;
  }
  RssEditorField? field(String key) {
    for (final t in tabs) {
      for (final f in t.fields) {
        if (f.key == key) return f;
      }
    }
    return null;
  }
}
