// GENERATED CODE - DO NOT MODIFY BY HAND
// coverage:ignore-file
// ignore_for_file: type=lint, type=warning, deprecated_member_use, deprecated_member_use_from_same_package
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'rss_source_editor_state.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// dart format off
T _$identity<T>(T value) => value;
/// @nodoc
mixin _$RssEditorField {

 String get key; String get label; String? get text; int get start; int get end; bool get external; int get length;
/// Create a copy of RssEditorField
/// with the given fields replaced by the non-null parameter values.
@JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
$RssEditorFieldCopyWith<RssEditorField> get copyWith => _$RssEditorFieldCopyWithImpl<RssEditorField>(this as RssEditorField, _$identity);



@override
bool operator ==(Object other) {
  final _this = this as RssEditorField;
  return identical(this, other) || (other.runtimeType == runtimeType&&other is RssEditorField&&(identical(other.key, _this.key) || other.key == _this.key)&&(identical(other.label, _this.label) || other.label == _this.label)&&(identical(other.text, _this.text) || other.text == _this.text)&&(identical(other.start, _this.start) || other.start == _this.start)&&(identical(other.end, _this.end) || other.end == _this.end)&&(identical(other.external, _this.external) || other.external == _this.external)&&(identical(other.length, _this.length) || other.length == _this.length));
}


@override
int get hashCode {
  final _this = this as RssEditorField;
  return Object.hash(runtimeType,_this.key,_this.label,_this.text,_this.start,_this.end,_this.external,_this.length);
}

@override
String toString() {
  final _this = this as RssEditorField;
  return 'RssEditorField(key: ${_this.key}, label: ${_this.label}, text: ${_this.text}, start: ${_this.start}, end: ${_this.end}, external: ${_this.external}, length: ${_this.length})';
}


}

/// @nodoc
abstract mixin class $RssEditorFieldCopyWith<$Res>  {
  factory $RssEditorFieldCopyWith(RssEditorField value, $Res Function(RssEditorField) _then) = _$RssEditorFieldCopyWithImpl;
@useResult
$Res call({
 String key, String label, String? text, int start, int end, bool external, int length
});




}
/// @nodoc
class _$RssEditorFieldCopyWithImpl<$Res>
    implements $RssEditorFieldCopyWith<$Res> {
  _$RssEditorFieldCopyWithImpl(this._self, this._then);

  final RssEditorField _self;
  final $Res Function(RssEditorField) _then;

/// Create a copy of RssEditorField
/// with the given fields replaced by the non-null parameter values.
@pragma('vm:prefer-inline') @override $Res call({Object? key = null,Object? label = null,Object? text = freezed,Object? start = null,Object? end = null,Object? external = null,Object? length = null,}) {
  return _then(RssEditorField(
key: null == key ? _self.key : key // ignore: cast_nullable_to_non_nullable
as String,label: null == label ? _self.label : label // ignore: cast_nullable_to_non_nullable
as String,text: freezed == text ? _self.text : text // ignore: cast_nullable_to_non_nullable
as String?,start: null == start ? _self.start : start // ignore: cast_nullable_to_non_nullable
as int,end: null == end ? _self.end : end // ignore: cast_nullable_to_non_nullable
as int,external: null == external ? _self.external : external // ignore: cast_nullable_to_non_nullable
as bool,length: null == length ? _self.length : length // ignore: cast_nullable_to_non_nullable
as int,
  ));
}

}


/// Adds pattern-matching-related methods to [RssEditorField].
extension RssEditorFieldPatterns on RssEditorField {
/// A variant of `map` that fallback to returning `orElse`.
///
/// It is equivalent to doing:
/// ```dart
/// switch (sealedClass) {
///   case final Subclass value:
///     return ...;
///   case _:
///     return orElse();
/// }
/// ```

@optionalTypeArgs TResult maybeMap<TResult extends Object?>(TResult Function( _RssEditorField value)?  $default,{required TResult orElse(),}){
final _that = this;
switch (_that) {
case _RssEditorField() when $default != null:
return $default(_that);case _:
  return orElse();

}
}
/// A `switch`-like method, using callbacks.
///
/// Callbacks receives the raw object, upcasted.
/// It is equivalent to doing:
/// ```dart
/// switch (sealedClass) {
///   case final Subclass value:
///     return ...;
///   case final Subclass2 value:
///     return ...;
/// }
/// ```

@optionalTypeArgs TResult map<TResult extends Object?>(TResult Function( _RssEditorField value)  $default,){
final _that = this;
switch (_that) {
case _RssEditorField():
return $default(_that);case _:
  throw StateError('Unexpected subclass');

}
}
/// A variant of `map` that fallback to returning `null`.
///
/// It is equivalent to doing:
/// ```dart
/// switch (sealedClass) {
///   case final Subclass value:
///     return ...;
///   case _:
///     return null;
/// }
/// ```

@optionalTypeArgs TResult? mapOrNull<TResult extends Object?>(TResult? Function( _RssEditorField value)?  $default,){
final _that = this;
switch (_that) {
case _RssEditorField() when $default != null:
return $default(_that);case _:
  return null;

}
}
/// A variant of `when` that fallback to an `orElse` callback.
///
/// It is equivalent to doing:
/// ```dart
/// switch (sealedClass) {
///   case Subclass(:final field):
///     return ...;
///   case _:
///     return orElse();
/// }
/// ```

@optionalTypeArgs TResult maybeWhen<TResult extends Object?>(TResult Function( String key,  String label,  String? text,  int start,  int end,  bool external,  int length)?  $default,{required TResult orElse(),}) {final _that = this;
switch (_that) {
case _RssEditorField() when $default != null:
return $default(_that.key,_that.label,_that.text,_that.start,_that.end,_that.external,_that.length);case _:
  return orElse();

}
}
/// A `switch`-like method, using callbacks.
///
/// As opposed to `map`, this offers destructuring.
/// It is equivalent to doing:
/// ```dart
/// switch (sealedClass) {
///   case Subclass(:final field):
///     return ...;
///   case Subclass2(:final field2):
///     return ...;
/// }
/// ```

@optionalTypeArgs TResult when<TResult extends Object?>(TResult Function( String key,  String label,  String? text,  int start,  int end,  bool external,  int length)  $default,) {final _that = this;
switch (_that) {
case _RssEditorField():
return $default(_that.key,_that.label,_that.text,_that.start,_that.end,_that.external,_that.length);case _:
  throw StateError('Unexpected subclass');

}
}
/// A variant of `when` that fallback to returning `null`
///
/// It is equivalent to doing:
/// ```dart
/// switch (sealedClass) {
///   case Subclass(:final field):
///     return ...;
///   case _:
///     return null;
/// }
/// ```

@optionalTypeArgs TResult? whenOrNull<TResult extends Object?>(TResult? Function( String key,  String label,  String? text,  int start,  int end,  bool external,  int length)?  $default,) {final _that = this;
switch (_that) {
case _RssEditorField() when $default != null:
return $default(_that.key,_that.label,_that.text,_that.start,_that.end,_that.external,_that.length);case _:
  return null;

}
}

}

/// @nodoc


class _RssEditorField extends RssEditorField {
  const _RssEditorField({required this.key, required this.label, this.text, required this.start, required this.end, required this.external, required this.length}): super._();


@override final  String key;
@override final  String label;
@override final  String? text;
@override final  int start;
@override final  int end;
@override final  bool external;
@override final  int length;

/// Create a copy of RssEditorField
/// with the given fields replaced by the non-null parameter values.
@override @JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
_$RssEditorFieldCopyWith<_RssEditorField> get copyWith => __$RssEditorFieldCopyWithImpl<_RssEditorField>(this, _$identity);



@override
bool operator ==(Object other) {
    return identical(this, other) || (other.runtimeType == runtimeType&&other is _RssEditorField&&(identical(other.key, key) || other.key == key)&&(identical(other.label, label) || other.label == label)&&(identical(other.text, text) || other.text == text)&&(identical(other.start, start) || other.start == start)&&(identical(other.end, end) || other.end == end)&&(identical(other.external, external) || other.external == external)&&(identical(other.length, length) || other.length == length));
}


@override
int get hashCode {
    return Object.hash(runtimeType,key,label,text,start,end,external,length);
}

@override
String toString() {
    return 'RssEditorField(key: $key, label: $label, text: $text, start: $start, end: $end, external: $external, length: $length)';
}


}

/// @nodoc
abstract mixin class _$RssEditorFieldCopyWith<$Res> implements $RssEditorFieldCopyWith<$Res> {
  factory _$RssEditorFieldCopyWith(_RssEditorField value, $Res Function(_RssEditorField) _then) = __$RssEditorFieldCopyWithImpl;
@override @useResult
$Res call({
 String key, String label, String? text, int start, int end, bool external, int length
});




}
/// @nodoc
class __$RssEditorFieldCopyWithImpl<$Res>
    implements _$RssEditorFieldCopyWith<$Res> {
  __$RssEditorFieldCopyWithImpl(this._self, this._then);

  final _RssEditorField _self;
  final $Res Function(_RssEditorField) _then;

/// Create a copy of RssEditorField
/// with the given fields replaced by the non-null parameter values.
@override @pragma('vm:prefer-inline') $Res call({Object? key = null,Object? label = null,Object? text = freezed,Object? start = null,Object? end = null,Object? external = null,Object? length = null,}) {
  return _then(_RssEditorField(
key: null == key ? _self.key : key // ignore: cast_nullable_to_non_nullable
as String,label: null == label ? _self.label : label // ignore: cast_nullable_to_non_nullable
as String,text: freezed == text ? _self.text : text // ignore: cast_nullable_to_non_nullable
as String?,start: null == start ? _self.start : start // ignore: cast_nullable_to_non_nullable
as int,end: null == end ? _self.end : end // ignore: cast_nullable_to_non_nullable
as int,external: null == external ? _self.external : external // ignore: cast_nullable_to_non_nullable
as bool,length: null == length ? _self.length : length // ignore: cast_nullable_to_non_nullable
as int,
  ));
}


}

/// @nodoc
mixin _$RssEditorTab {

 int get index; String get label; List<RssEditorField> get fields;
/// Create a copy of RssEditorTab
/// with the given fields replaced by the non-null parameter values.
@JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
$RssEditorTabCopyWith<RssEditorTab> get copyWith => _$RssEditorTabCopyWithImpl<RssEditorTab>(this as RssEditorTab, _$identity);



@override
bool operator ==(Object other) {
  final _this = this as RssEditorTab;
  return identical(this, other) || (other.runtimeType == runtimeType&&other is RssEditorTab&&(identical(other.index, _this.index) || other.index == _this.index)&&(identical(other.label, _this.label) || other.label == _this.label)&&const DeepCollectionEquality().equals(other.fields, _this.fields));
}


@override
int get hashCode {
  final _this = this as RssEditorTab;
  return Object.hash(runtimeType,_this.index,_this.label,const DeepCollectionEquality().hash(_this.fields));
}

@override
String toString() {
  final _this = this as RssEditorTab;
  return 'RssEditorTab(index: ${_this.index}, label: ${_this.label}, fields: ${_this.fields})';
}


}

/// @nodoc
abstract mixin class $RssEditorTabCopyWith<$Res>  {
  factory $RssEditorTabCopyWith(RssEditorTab value, $Res Function(RssEditorTab) _then) = _$RssEditorTabCopyWithImpl;
@useResult
$Res call({
 int index, String label, List<RssEditorField> fields
});




}
/// @nodoc
class _$RssEditorTabCopyWithImpl<$Res>
    implements $RssEditorTabCopyWith<$Res> {
  _$RssEditorTabCopyWithImpl(this._self, this._then);

  final RssEditorTab _self;
  final $Res Function(RssEditorTab) _then;

/// Create a copy of RssEditorTab
/// with the given fields replaced by the non-null parameter values.
@pragma('vm:prefer-inline') @override $Res call({Object? index = null,Object? label = null,Object? fields = null,}) {
  return _then(RssEditorTab(
index: null == index ? _self.index : index // ignore: cast_nullable_to_non_nullable
as int,label: null == label ? _self.label : label // ignore: cast_nullable_to_non_nullable
as String,fields: null == fields ? _self.fields : fields // ignore: cast_nullable_to_non_nullable
as List<RssEditorField>,
  ));
}

}


/// Adds pattern-matching-related methods to [RssEditorTab].
extension RssEditorTabPatterns on RssEditorTab {
/// A variant of `map` that fallback to returning `orElse`.
///
/// It is equivalent to doing:
/// ```dart
/// switch (sealedClass) {
///   case final Subclass value:
///     return ...;
///   case _:
///     return orElse();
/// }
/// ```

@optionalTypeArgs TResult maybeMap<TResult extends Object?>(TResult Function( _RssEditorTab value)?  $default,{required TResult orElse(),}){
final _that = this;
switch (_that) {
case _RssEditorTab() when $default != null:
return $default(_that);case _:
  return orElse();

}
}
/// A `switch`-like method, using callbacks.
///
/// Callbacks receives the raw object, upcasted.
/// It is equivalent to doing:
/// ```dart
/// switch (sealedClass) {
///   case final Subclass value:
///     return ...;
///   case final Subclass2 value:
///     return ...;
/// }
/// ```

@optionalTypeArgs TResult map<TResult extends Object?>(TResult Function( _RssEditorTab value)  $default,){
final _that = this;
switch (_that) {
case _RssEditorTab():
return $default(_that);case _:
  throw StateError('Unexpected subclass');

}
}
/// A variant of `map` that fallback to returning `null`.
///
/// It is equivalent to doing:
/// ```dart
/// switch (sealedClass) {
///   case final Subclass value:
///     return ...;
///   case _:
///     return null;
/// }
/// ```

@optionalTypeArgs TResult? mapOrNull<TResult extends Object?>(TResult? Function( _RssEditorTab value)?  $default,){
final _that = this;
switch (_that) {
case _RssEditorTab() when $default != null:
return $default(_that);case _:
  return null;

}
}
/// A variant of `when` that fallback to an `orElse` callback.
///
/// It is equivalent to doing:
/// ```dart
/// switch (sealedClass) {
///   case Subclass(:final field):
///     return ...;
///   case _:
///     return orElse();
/// }
/// ```

@optionalTypeArgs TResult maybeWhen<TResult extends Object?>(TResult Function( int index,  String label,  List<RssEditorField> fields)?  $default,{required TResult orElse(),}) {final _that = this;
switch (_that) {
case _RssEditorTab() when $default != null:
return $default(_that.index,_that.label,_that.fields);case _:
  return orElse();

}
}
/// A `switch`-like method, using callbacks.
///
/// As opposed to `map`, this offers destructuring.
/// It is equivalent to doing:
/// ```dart
/// switch (sealedClass) {
///   case Subclass(:final field):
///     return ...;
///   case Subclass2(:final field2):
///     return ...;
/// }
/// ```

@optionalTypeArgs TResult when<TResult extends Object?>(TResult Function( int index,  String label,  List<RssEditorField> fields)  $default,) {final _that = this;
switch (_that) {
case _RssEditorTab():
return $default(_that.index,_that.label,_that.fields);case _:
  throw StateError('Unexpected subclass');

}
}
/// A variant of `when` that fallback to returning `null`
///
/// It is equivalent to doing:
/// ```dart
/// switch (sealedClass) {
///   case Subclass(:final field):
///     return ...;
///   case _:
///     return null;
/// }
/// ```

@optionalTypeArgs TResult? whenOrNull<TResult extends Object?>(TResult? Function( int index,  String label,  List<RssEditorField> fields)?  $default,) {final _that = this;
switch (_that) {
case _RssEditorTab() when $default != null:
return $default(_that.index,_that.label,_that.fields);case _:
  return null;

}
}

}

/// @nodoc


class _RssEditorTab extends RssEditorTab {
  const _RssEditorTab({required this.index, required this.label, required  List<RssEditorField> fields}): _fields = fields,super._();


@override final  int index;
@override final  String label;
 final  List<RssEditorField> _fields;
@override List<RssEditorField> get fields {
  if (_fields is EqualUnmodifiableListView) return _fields;
  // ignore: implicit_dynamic_type
  return EqualUnmodifiableListView(_fields);
}


/// Create a copy of RssEditorTab
/// with the given fields replaced by the non-null parameter values.
@override @JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
_$RssEditorTabCopyWith<_RssEditorTab> get copyWith => __$RssEditorTabCopyWithImpl<_RssEditorTab>(this, _$identity);



@override
bool operator ==(Object other) {
    return identical(this, other) || (other.runtimeType == runtimeType&&other is _RssEditorTab&&(identical(other.index, index) || other.index == index)&&(identical(other.label, label) || other.label == label)&&const DeepCollectionEquality().equals(other.fields, _fields));
}


@override
int get hashCode {
    return Object.hash(runtimeType,index,label,const DeepCollectionEquality().hash(_fields));
}

@override
String toString() {
    return 'RssEditorTab(index: $index, label: $label, fields: $fields)';
}


}

/// @nodoc
abstract mixin class _$RssEditorTabCopyWith<$Res> implements $RssEditorTabCopyWith<$Res> {
  factory _$RssEditorTabCopyWith(_RssEditorTab value, $Res Function(_RssEditorTab) _then) = __$RssEditorTabCopyWithImpl;
@override @useResult
$Res call({
 int index, String label, List<RssEditorField> fields
});




}
/// @nodoc
class __$RssEditorTabCopyWithImpl<$Res>
    implements _$RssEditorTabCopyWith<$Res> {
  __$RssEditorTabCopyWithImpl(this._self, this._then);

  final _RssEditorTab _self;
  final $Res Function(_RssEditorTab) _then;

/// Create a copy of RssEditorTab
/// with the given fields replaced by the non-null parameter values.
@override @pragma('vm:prefer-inline') $Res call({Object? index = null,Object? label = null,Object? fields = null,}) {
  return _then(_RssEditorTab(
index: null == index ? _self.index : index // ignore: cast_nullable_to_non_nullable
as int,label: null == label ? _self.label : label // ignore: cast_nullable_to_non_nullable
as String,fields: null == fields ? _self._fields : fields // ignore: cast_nullable_to_non_nullable
as List<RssEditorField>,
  ));
}


}

/// @nodoc
mixin _$RssEditorSnapshot {

 int get generation; int get revision; bool get loading; bool get loaded; bool get busy; bool get missing; bool get canEdit; bool get editorPending; bool get finished; bool get savedResult; bool get exit; bool get expanded; bool get autoComplete; String? get error; String? get issue; int get tab; String? get focus; List<RssEditorTab> get tabs; Map<String, Object?> get options; List<Map<String, String>> get assists; int get keyboardRows; int get maxLines; List<String> get typeLabels; List<String> get articleStyleLabels;
/// Create a copy of RssEditorSnapshot
/// with the given fields replaced by the non-null parameter values.
@JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
$RssEditorSnapshotCopyWith<RssEditorSnapshot> get copyWith => _$RssEditorSnapshotCopyWithImpl<RssEditorSnapshot>(this as RssEditorSnapshot, _$identity);



@override
bool operator ==(Object other) {
  final _this = this as RssEditorSnapshot;
  return identical(this, other) || (other.runtimeType == runtimeType&&other is RssEditorSnapshot&&(identical(other.generation, _this.generation) || other.generation == _this.generation)&&(identical(other.revision, _this.revision) || other.revision == _this.revision)&&(identical(other.loading, _this.loading) || other.loading == _this.loading)&&(identical(other.loaded, _this.loaded) || other.loaded == _this.loaded)&&(identical(other.busy, _this.busy) || other.busy == _this.busy)&&(identical(other.missing, _this.missing) || other.missing == _this.missing)&&(identical(other.canEdit, _this.canEdit) || other.canEdit == _this.canEdit)&&(identical(other.editorPending, _this.editorPending) || other.editorPending == _this.editorPending)&&(identical(other.finished, _this.finished) || other.finished == _this.finished)&&(identical(other.savedResult, _this.savedResult) || other.savedResult == _this.savedResult)&&(identical(other.exit, _this.exit) || other.exit == _this.exit)&&(identical(other.expanded, _this.expanded) || other.expanded == _this.expanded)&&(identical(other.autoComplete, _this.autoComplete) || other.autoComplete == _this.autoComplete)&&(identical(other.error, _this.error) || other.error == _this.error)&&(identical(other.issue, _this.issue) || other.issue == _this.issue)&&(identical(other.tab, _this.tab) || other.tab == _this.tab)&&(identical(other.focus, _this.focus) || other.focus == _this.focus)&&const DeepCollectionEquality().equals(other.tabs, _this.tabs)&&const DeepCollectionEquality().equals(other.options, _this.options)&&const DeepCollectionEquality().equals(other.assists, _this.assists)&&(identical(other.keyboardRows, _this.keyboardRows) || other.keyboardRows == _this.keyboardRows)&&(identical(other.maxLines, _this.maxLines) || other.maxLines == _this.maxLines)&&const DeepCollectionEquality().equals(other.typeLabels, _this.typeLabels)&&const DeepCollectionEquality().equals(other.articleStyleLabels, _this.articleStyleLabels));
}


@override
int get hashCode {
  final _this = this as RssEditorSnapshot;
  return Object.hashAll([runtimeType,_this.generation,_this.revision,_this.loading,_this.loaded,_this.busy,_this.missing,_this.canEdit,_this.editorPending,_this.finished,_this.savedResult,_this.exit,_this.expanded,_this.autoComplete,_this.error,_this.issue,_this.tab,_this.focus,const DeepCollectionEquality().hash(_this.tabs),const DeepCollectionEquality().hash(_this.options),const DeepCollectionEquality().hash(_this.assists),_this.keyboardRows,_this.maxLines,const DeepCollectionEquality().hash(_this.typeLabels),const DeepCollectionEquality().hash(_this.articleStyleLabels)]);
}

@override
String toString() {
  final _this = this as RssEditorSnapshot;
  return 'RssEditorSnapshot(generation: ${_this.generation}, revision: ${_this.revision}, loading: ${_this.loading}, loaded: ${_this.loaded}, busy: ${_this.busy}, missing: ${_this.missing}, canEdit: ${_this.canEdit}, editorPending: ${_this.editorPending}, finished: ${_this.finished}, savedResult: ${_this.savedResult}, exit: ${_this.exit}, expanded: ${_this.expanded}, autoComplete: ${_this.autoComplete}, error: ${_this.error}, issue: ${_this.issue}, tab: ${_this.tab}, focus: ${_this.focus}, tabs: ${_this.tabs}, options: ${_this.options}, assists: ${_this.assists}, keyboardRows: ${_this.keyboardRows}, maxLines: ${_this.maxLines}, typeLabels: ${_this.typeLabels}, articleStyleLabels: ${_this.articleStyleLabels})';
}


}

/// @nodoc
abstract mixin class $RssEditorSnapshotCopyWith<$Res>  {
  factory $RssEditorSnapshotCopyWith(RssEditorSnapshot value, $Res Function(RssEditorSnapshot) _then) = _$RssEditorSnapshotCopyWithImpl;
@useResult
$Res call({
 int generation, int revision, bool loading, bool loaded, bool busy, bool missing, bool canEdit, bool editorPending, bool finished, bool savedResult, bool exit, bool expanded, bool autoComplete, String? error, String? issue, int tab, String? focus, List<RssEditorTab> tabs, Map<String, Object?> options, List<Map<String, String>> assists, int keyboardRows, int maxLines, List<String> typeLabels, List<String> articleStyleLabels
});




}
/// @nodoc
class _$RssEditorSnapshotCopyWithImpl<$Res>
    implements $RssEditorSnapshotCopyWith<$Res> {
  _$RssEditorSnapshotCopyWithImpl(this._self, this._then);

  final RssEditorSnapshot _self;
  final $Res Function(RssEditorSnapshot) _then;

/// Create a copy of RssEditorSnapshot
/// with the given fields replaced by the non-null parameter values.
@pragma('vm:prefer-inline') @override $Res call({Object? generation = null,Object? revision = null,Object? loading = null,Object? loaded = null,Object? busy = null,Object? missing = null,Object? canEdit = null,Object? editorPending = null,Object? finished = null,Object? savedResult = null,Object? exit = null,Object? expanded = null,Object? autoComplete = null,Object? error = freezed,Object? issue = freezed,Object? tab = null,Object? focus = freezed,Object? tabs = null,Object? options = null,Object? assists = null,Object? keyboardRows = null,Object? maxLines = null,Object? typeLabels = null,Object? articleStyleLabels = null,}) {
  return _then(RssEditorSnapshot(
generation: null == generation ? _self.generation : generation // ignore: cast_nullable_to_non_nullable
as int,revision: null == revision ? _self.revision : revision // ignore: cast_nullable_to_non_nullable
as int,loading: null == loading ? _self.loading : loading // ignore: cast_nullable_to_non_nullable
as bool,loaded: null == loaded ? _self.loaded : loaded // ignore: cast_nullable_to_non_nullable
as bool,busy: null == busy ? _self.busy : busy // ignore: cast_nullable_to_non_nullable
as bool,missing: null == missing ? _self.missing : missing // ignore: cast_nullable_to_non_nullable
as bool,canEdit: null == canEdit ? _self.canEdit : canEdit // ignore: cast_nullable_to_non_nullable
as bool,editorPending: null == editorPending ? _self.editorPending : editorPending // ignore: cast_nullable_to_non_nullable
as bool,finished: null == finished ? _self.finished : finished // ignore: cast_nullable_to_non_nullable
as bool,savedResult: null == savedResult ? _self.savedResult : savedResult // ignore: cast_nullable_to_non_nullable
as bool,exit: null == exit ? _self.exit : exit // ignore: cast_nullable_to_non_nullable
as bool,expanded: null == expanded ? _self.expanded : expanded // ignore: cast_nullable_to_non_nullable
as bool,autoComplete: null == autoComplete ? _self.autoComplete : autoComplete // ignore: cast_nullable_to_non_nullable
as bool,error: freezed == error ? _self.error : error // ignore: cast_nullable_to_non_nullable
as String?,issue: freezed == issue ? _self.issue : issue // ignore: cast_nullable_to_non_nullable
as String?,tab: null == tab ? _self.tab : tab // ignore: cast_nullable_to_non_nullable
as int,focus: freezed == focus ? _self.focus : focus // ignore: cast_nullable_to_non_nullable
as String?,tabs: null == tabs ? _self.tabs : tabs // ignore: cast_nullable_to_non_nullable
as List<RssEditorTab>,options: null == options ? _self.options : options // ignore: cast_nullable_to_non_nullable
as Map<String, Object?>,assists: null == assists ? _self.assists : assists // ignore: cast_nullable_to_non_nullable
as List<Map<String, String>>,keyboardRows: null == keyboardRows ? _self.keyboardRows : keyboardRows // ignore: cast_nullable_to_non_nullable
as int,maxLines: null == maxLines ? _self.maxLines : maxLines // ignore: cast_nullable_to_non_nullable
as int,typeLabels: null == typeLabels ? _self.typeLabels : typeLabels // ignore: cast_nullable_to_non_nullable
as List<String>,articleStyleLabels: null == articleStyleLabels ? _self.articleStyleLabels : articleStyleLabels // ignore: cast_nullable_to_non_nullable
as List<String>,
  ));
}

}


/// Adds pattern-matching-related methods to [RssEditorSnapshot].
extension RssEditorSnapshotPatterns on RssEditorSnapshot {
/// A variant of `map` that fallback to returning `orElse`.
///
/// It is equivalent to doing:
/// ```dart
/// switch (sealedClass) {
///   case final Subclass value:
///     return ...;
///   case _:
///     return orElse();
/// }
/// ```

@optionalTypeArgs TResult maybeMap<TResult extends Object?>(TResult Function( _RssEditorSnapshot value)?  $default,{required TResult orElse(),}){
final _that = this;
switch (_that) {
case _RssEditorSnapshot() when $default != null:
return $default(_that);case _:
  return orElse();

}
}
/// A `switch`-like method, using callbacks.
///
/// Callbacks receives the raw object, upcasted.
/// It is equivalent to doing:
/// ```dart
/// switch (sealedClass) {
///   case final Subclass value:
///     return ...;
///   case final Subclass2 value:
///     return ...;
/// }
/// ```

@optionalTypeArgs TResult map<TResult extends Object?>(TResult Function( _RssEditorSnapshot value)  $default,){
final _that = this;
switch (_that) {
case _RssEditorSnapshot():
return $default(_that);case _:
  throw StateError('Unexpected subclass');

}
}
/// A variant of `map` that fallback to returning `null`.
///
/// It is equivalent to doing:
/// ```dart
/// switch (sealedClass) {
///   case final Subclass value:
///     return ...;
///   case _:
///     return null;
/// }
/// ```

@optionalTypeArgs TResult? mapOrNull<TResult extends Object?>(TResult? Function( _RssEditorSnapshot value)?  $default,){
final _that = this;
switch (_that) {
case _RssEditorSnapshot() when $default != null:
return $default(_that);case _:
  return null;

}
}
/// A variant of `when` that fallback to an `orElse` callback.
///
/// It is equivalent to doing:
/// ```dart
/// switch (sealedClass) {
///   case Subclass(:final field):
///     return ...;
///   case _:
///     return orElse();
/// }
/// ```

@optionalTypeArgs TResult maybeWhen<TResult extends Object?>(TResult Function( int generation,  int revision,  bool loading,  bool loaded,  bool busy,  bool missing,  bool canEdit,  bool editorPending,  bool finished,  bool savedResult,  bool exit,  bool expanded,  bool autoComplete,  String? error,  String? issue,  int tab,  String? focus,  List<RssEditorTab> tabs,  Map<String, Object?> options,  List<Map<String, String>> assists,  int keyboardRows,  int maxLines,  List<String> typeLabels,  List<String> articleStyleLabels)?  $default,{required TResult orElse(),}) {final _that = this;
switch (_that) {
case _RssEditorSnapshot() when $default != null:
return $default(_that.generation,_that.revision,_that.loading,_that.loaded,_that.busy,_that.missing,_that.canEdit,_that.editorPending,_that.finished,_that.savedResult,_that.exit,_that.expanded,_that.autoComplete,_that.error,_that.issue,_that.tab,_that.focus,_that.tabs,_that.options,_that.assists,_that.keyboardRows,_that.maxLines,_that.typeLabels,_that.articleStyleLabels);case _:
  return orElse();

}
}
/// A `switch`-like method, using callbacks.
///
/// As opposed to `map`, this offers destructuring.
/// It is equivalent to doing:
/// ```dart
/// switch (sealedClass) {
///   case Subclass(:final field):
///     return ...;
///   case Subclass2(:final field2):
///     return ...;
/// }
/// ```

@optionalTypeArgs TResult when<TResult extends Object?>(TResult Function( int generation,  int revision,  bool loading,  bool loaded,  bool busy,  bool missing,  bool canEdit,  bool editorPending,  bool finished,  bool savedResult,  bool exit,  bool expanded,  bool autoComplete,  String? error,  String? issue,  int tab,  String? focus,  List<RssEditorTab> tabs,  Map<String, Object?> options,  List<Map<String, String>> assists,  int keyboardRows,  int maxLines,  List<String> typeLabels,  List<String> articleStyleLabels)  $default,) {final _that = this;
switch (_that) {
case _RssEditorSnapshot():
return $default(_that.generation,_that.revision,_that.loading,_that.loaded,_that.busy,_that.missing,_that.canEdit,_that.editorPending,_that.finished,_that.savedResult,_that.exit,_that.expanded,_that.autoComplete,_that.error,_that.issue,_that.tab,_that.focus,_that.tabs,_that.options,_that.assists,_that.keyboardRows,_that.maxLines,_that.typeLabels,_that.articleStyleLabels);case _:
  throw StateError('Unexpected subclass');

}
}
/// A variant of `when` that fallback to returning `null`
///
/// It is equivalent to doing:
/// ```dart
/// switch (sealedClass) {
///   case Subclass(:final field):
///     return ...;
///   case _:
///     return null;
/// }
/// ```

@optionalTypeArgs TResult? whenOrNull<TResult extends Object?>(TResult? Function( int generation,  int revision,  bool loading,  bool loaded,  bool busy,  bool missing,  bool canEdit,  bool editorPending,  bool finished,  bool savedResult,  bool exit,  bool expanded,  bool autoComplete,  String? error,  String? issue,  int tab,  String? focus,  List<RssEditorTab> tabs,  Map<String, Object?> options,  List<Map<String, String>> assists,  int keyboardRows,  int maxLines,  List<String> typeLabels,  List<String> articleStyleLabels)?  $default,) {final _that = this;
switch (_that) {
case _RssEditorSnapshot() when $default != null:
return $default(_that.generation,_that.revision,_that.loading,_that.loaded,_that.busy,_that.missing,_that.canEdit,_that.editorPending,_that.finished,_that.savedResult,_that.exit,_that.expanded,_that.autoComplete,_that.error,_that.issue,_that.tab,_that.focus,_that.tabs,_that.options,_that.assists,_that.keyboardRows,_that.maxLines,_that.typeLabels,_that.articleStyleLabels);case _:
  return null;

}
}

}

/// @nodoc


class _RssEditorSnapshot extends RssEditorSnapshot {
  const _RssEditorSnapshot({required this.generation, required this.revision, required this.loading, required this.loaded, required this.busy, required this.missing, required this.canEdit, required this.editorPending, required this.finished, required this.savedResult, required this.exit, required this.expanded, required this.autoComplete, this.error, this.issue, required this.tab, this.focus, required  List<RssEditorTab> tabs, required  Map<String, Object?> options, required  List<Map<String, String>> assists, required this.keyboardRows, required this.maxLines, required  List<String> typeLabels, required  List<String> articleStyleLabels}): _tabs = tabs,_options = options,_assists = assists,_typeLabels = typeLabels,_articleStyleLabels = articleStyleLabels,super._();


@override final  int generation;
@override final  int revision;
@override final  bool loading;
@override final  bool loaded;
@override final  bool busy;
@override final  bool missing;
@override final  bool canEdit;
@override final  bool editorPending;
@override final  bool finished;
@override final  bool savedResult;
@override final  bool exit;
@override final  bool expanded;
@override final  bool autoComplete;
@override final  String? error;
@override final  String? issue;
@override final  int tab;
@override final  String? focus;
 final  List<RssEditorTab> _tabs;
@override List<RssEditorTab> get tabs {
  if (_tabs is EqualUnmodifiableListView) return _tabs;
  // ignore: implicit_dynamic_type
  return EqualUnmodifiableListView(_tabs);
}

 final  Map<String, Object?> _options;
@override Map<String, Object?> get options {
  if (_options is EqualUnmodifiableMapView) return _options;
  // ignore: implicit_dynamic_type
  return EqualUnmodifiableMapView(_options);
}

 final  List<Map<String, String>> _assists;
@override List<Map<String, String>> get assists {
  if (_assists is EqualUnmodifiableListView) return _assists;
  // ignore: implicit_dynamic_type
  return EqualUnmodifiableListView(_assists);
}

@override final  int keyboardRows;
@override final  int maxLines;
 final  List<String> _typeLabels;
@override List<String> get typeLabels {
  if (_typeLabels is EqualUnmodifiableListView) return _typeLabels;
  // ignore: implicit_dynamic_type
  return EqualUnmodifiableListView(_typeLabels);
}

 final  List<String> _articleStyleLabels;
@override List<String> get articleStyleLabels {
  if (_articleStyleLabels is EqualUnmodifiableListView) return _articleStyleLabels;
  // ignore: implicit_dynamic_type
  return EqualUnmodifiableListView(_articleStyleLabels);
}


/// Create a copy of RssEditorSnapshot
/// with the given fields replaced by the non-null parameter values.
@override @JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
_$RssEditorSnapshotCopyWith<_RssEditorSnapshot> get copyWith => __$RssEditorSnapshotCopyWithImpl<_RssEditorSnapshot>(this, _$identity);



@override
bool operator ==(Object other) {
    return identical(this, other) || (other.runtimeType == runtimeType&&other is _RssEditorSnapshot&&(identical(other.generation, generation) || other.generation == generation)&&(identical(other.revision, revision) || other.revision == revision)&&(identical(other.loading, loading) || other.loading == loading)&&(identical(other.loaded, loaded) || other.loaded == loaded)&&(identical(other.busy, busy) || other.busy == busy)&&(identical(other.missing, missing) || other.missing == missing)&&(identical(other.canEdit, canEdit) || other.canEdit == canEdit)&&(identical(other.editorPending, editorPending) || other.editorPending == editorPending)&&(identical(other.finished, finished) || other.finished == finished)&&(identical(other.savedResult, savedResult) || other.savedResult == savedResult)&&(identical(other.exit, exit) || other.exit == exit)&&(identical(other.expanded, expanded) || other.expanded == expanded)&&(identical(other.autoComplete, autoComplete) || other.autoComplete == autoComplete)&&(identical(other.error, error) || other.error == error)&&(identical(other.issue, issue) || other.issue == issue)&&(identical(other.tab, tab) || other.tab == tab)&&(identical(other.focus, focus) || other.focus == focus)&&const DeepCollectionEquality().equals(other.tabs, _tabs)&&const DeepCollectionEquality().equals(other.options, _options)&&const DeepCollectionEquality().equals(other.assists, _assists)&&(identical(other.keyboardRows, keyboardRows) || other.keyboardRows == keyboardRows)&&(identical(other.maxLines, maxLines) || other.maxLines == maxLines)&&const DeepCollectionEquality().equals(other.typeLabels, _typeLabels)&&const DeepCollectionEquality().equals(other.articleStyleLabels, _articleStyleLabels));
}


@override
int get hashCode {
    return Object.hashAll([runtimeType,generation,revision,loading,loaded,busy,missing,canEdit,editorPending,finished,savedResult,exit,expanded,autoComplete,error,issue,tab,focus,const DeepCollectionEquality().hash(_tabs),const DeepCollectionEquality().hash(_options),const DeepCollectionEquality().hash(_assists),keyboardRows,maxLines,const DeepCollectionEquality().hash(_typeLabels),const DeepCollectionEquality().hash(_articleStyleLabels)]);
}

@override
String toString() {
    return 'RssEditorSnapshot(generation: $generation, revision: $revision, loading: $loading, loaded: $loaded, busy: $busy, missing: $missing, canEdit: $canEdit, editorPending: $editorPending, finished: $finished, savedResult: $savedResult, exit: $exit, expanded: $expanded, autoComplete: $autoComplete, error: $error, issue: $issue, tab: $tab, focus: $focus, tabs: $tabs, options: $options, assists: $assists, keyboardRows: $keyboardRows, maxLines: $maxLines, typeLabels: $typeLabels, articleStyleLabels: $articleStyleLabels)';
}


}

/// @nodoc
abstract mixin class _$RssEditorSnapshotCopyWith<$Res> implements $RssEditorSnapshotCopyWith<$Res> {
  factory _$RssEditorSnapshotCopyWith(_RssEditorSnapshot value, $Res Function(_RssEditorSnapshot) _then) = __$RssEditorSnapshotCopyWithImpl;
@override @useResult
$Res call({
 int generation, int revision, bool loading, bool loaded, bool busy, bool missing, bool canEdit, bool editorPending, bool finished, bool savedResult, bool exit, bool expanded, bool autoComplete, String? error, String? issue, int tab, String? focus, List<RssEditorTab> tabs, Map<String, Object?> options, List<Map<String, String>> assists, int keyboardRows, int maxLines, List<String> typeLabels, List<String> articleStyleLabels
});




}
/// @nodoc
class __$RssEditorSnapshotCopyWithImpl<$Res>
    implements _$RssEditorSnapshotCopyWith<$Res> {
  __$RssEditorSnapshotCopyWithImpl(this._self, this._then);

  final _RssEditorSnapshot _self;
  final $Res Function(_RssEditorSnapshot) _then;

/// Create a copy of RssEditorSnapshot
/// with the given fields replaced by the non-null parameter values.
@override @pragma('vm:prefer-inline') $Res call({Object? generation = null,Object? revision = null,Object? loading = null,Object? loaded = null,Object? busy = null,Object? missing = null,Object? canEdit = null,Object? editorPending = null,Object? finished = null,Object? savedResult = null,Object? exit = null,Object? expanded = null,Object? autoComplete = null,Object? error = freezed,Object? issue = freezed,Object? tab = null,Object? focus = freezed,Object? tabs = null,Object? options = null,Object? assists = null,Object? keyboardRows = null,Object? maxLines = null,Object? typeLabels = null,Object? articleStyleLabels = null,}) {
  return _then(_RssEditorSnapshot(
generation: null == generation ? _self.generation : generation // ignore: cast_nullable_to_non_nullable
as int,revision: null == revision ? _self.revision : revision // ignore: cast_nullable_to_non_nullable
as int,loading: null == loading ? _self.loading : loading // ignore: cast_nullable_to_non_nullable
as bool,loaded: null == loaded ? _self.loaded : loaded // ignore: cast_nullable_to_non_nullable
as bool,busy: null == busy ? _self.busy : busy // ignore: cast_nullable_to_non_nullable
as bool,missing: null == missing ? _self.missing : missing // ignore: cast_nullable_to_non_nullable
as bool,canEdit: null == canEdit ? _self.canEdit : canEdit // ignore: cast_nullable_to_non_nullable
as bool,editorPending: null == editorPending ? _self.editorPending : editorPending // ignore: cast_nullable_to_non_nullable
as bool,finished: null == finished ? _self.finished : finished // ignore: cast_nullable_to_non_nullable
as bool,savedResult: null == savedResult ? _self.savedResult : savedResult // ignore: cast_nullable_to_non_nullable
as bool,exit: null == exit ? _self.exit : exit // ignore: cast_nullable_to_non_nullable
as bool,expanded: null == expanded ? _self.expanded : expanded // ignore: cast_nullable_to_non_nullable
as bool,autoComplete: null == autoComplete ? _self.autoComplete : autoComplete // ignore: cast_nullable_to_non_nullable
as bool,error: freezed == error ? _self.error : error // ignore: cast_nullable_to_non_nullable
as String?,issue: freezed == issue ? _self.issue : issue // ignore: cast_nullable_to_non_nullable
as String?,tab: null == tab ? _self.tab : tab // ignore: cast_nullable_to_non_nullable
as int,focus: freezed == focus ? _self.focus : focus // ignore: cast_nullable_to_non_nullable
as String?,tabs: null == tabs ? _self._tabs : tabs // ignore: cast_nullable_to_non_nullable
as List<RssEditorTab>,options: null == options ? _self._options : options // ignore: cast_nullable_to_non_nullable
as Map<String, Object?>,assists: null == assists ? _self._assists : assists // ignore: cast_nullable_to_non_nullable
as List<Map<String, String>>,keyboardRows: null == keyboardRows ? _self.keyboardRows : keyboardRows // ignore: cast_nullable_to_non_nullable
as int,maxLines: null == maxLines ? _self.maxLines : maxLines // ignore: cast_nullable_to_non_nullable
as int,typeLabels: null == typeLabels ? _self._typeLabels : typeLabels // ignore: cast_nullable_to_non_nullable
as List<String>,articleStyleLabels: null == articleStyleLabels ? _self._articleStyleLabels : articleStyleLabels // ignore: cast_nullable_to_non_nullable
as List<String>,
  ));
}


}

// dart format on
