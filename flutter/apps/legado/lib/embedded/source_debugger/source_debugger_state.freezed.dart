// GENERATED CODE - DO NOT MODIFY BY HAND
// coverage:ignore-file
// ignore_for_file: type=lint, type=warning, deprecated_member_use, deprecated_member_use_from_same_package
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'source_debugger_state.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// dart format off
T _$identity<T>(T value) => value;
/// @nodoc
mixin _$SourceDebuggerSort {

 String get name; String get query; bool get enabled;
/// Create a copy of SourceDebuggerSort
/// with the given fields replaced by the non-null parameter values.
@JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
$SourceDebuggerSortCopyWith<SourceDebuggerSort> get copyWith => _$SourceDebuggerSortCopyWithImpl<SourceDebuggerSort>(this as SourceDebuggerSort, _$identity);



@override
bool operator ==(Object other) {
  final _this = this as SourceDebuggerSort;
  return identical(this, other) || (other.runtimeType == runtimeType&&other is SourceDebuggerSort&&(identical(other.name, _this.name) || other.name == _this.name)&&(identical(other.query, _this.query) || other.query == _this.query)&&(identical(other.enabled, _this.enabled) || other.enabled == _this.enabled));
}


@override
int get hashCode {
  final _this = this as SourceDebuggerSort;
  return Object.hash(runtimeType,_this.name,_this.query,_this.enabled);
}

@override
String toString() {
  final _this = this as SourceDebuggerSort;
  return 'SourceDebuggerSort(name: ${_this.name}, query: ${_this.query}, enabled: ${_this.enabled})';
}


}

/// @nodoc
abstract mixin class $SourceDebuggerSortCopyWith<$Res>  {
  factory $SourceDebuggerSortCopyWith(SourceDebuggerSort value, $Res Function(SourceDebuggerSort) _then) = _$SourceDebuggerSortCopyWithImpl;
@useResult
$Res call({
 String name, String query, bool enabled
});




}
/// @nodoc
class _$SourceDebuggerSortCopyWithImpl<$Res>
    implements $SourceDebuggerSortCopyWith<$Res> {
  _$SourceDebuggerSortCopyWithImpl(this._self, this._then);

  final SourceDebuggerSort _self;
  final $Res Function(SourceDebuggerSort) _then;

/// Create a copy of SourceDebuggerSort
/// with the given fields replaced by the non-null parameter values.
@pragma('vm:prefer-inline') @override $Res call({Object? name = null,Object? query = null,Object? enabled = null,}) {
  return _then(SourceDebuggerSort(
name: null == name ? _self.name : name // ignore: cast_nullable_to_non_nullable
as String,query: null == query ? _self.query : query // ignore: cast_nullable_to_non_nullable
as String,enabled: null == enabled ? _self.enabled : enabled // ignore: cast_nullable_to_non_nullable
as bool,
  ));
}

}


/// Adds pattern-matching-related methods to [SourceDebuggerSort].
extension SourceDebuggerSortPatterns on SourceDebuggerSort {
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

@optionalTypeArgs TResult maybeMap<TResult extends Object?>(TResult Function( _SourceDebuggerSort value)?  $default,{required TResult orElse(),}){
final _that = this;
switch (_that) {
case _SourceDebuggerSort() when $default != null:
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

@optionalTypeArgs TResult map<TResult extends Object?>(TResult Function( _SourceDebuggerSort value)  $default,){
final _that = this;
switch (_that) {
case _SourceDebuggerSort():
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

@optionalTypeArgs TResult? mapOrNull<TResult extends Object?>(TResult? Function( _SourceDebuggerSort value)?  $default,){
final _that = this;
switch (_that) {
case _SourceDebuggerSort() when $default != null:
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

@optionalTypeArgs TResult maybeWhen<TResult extends Object?>(TResult Function( String name,  String query,  bool enabled)?  $default,{required TResult orElse(),}) {final _that = this;
switch (_that) {
case _SourceDebuggerSort() when $default != null:
return $default(_that.name,_that.query,_that.enabled);case _:
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

@optionalTypeArgs TResult when<TResult extends Object?>(TResult Function( String name,  String query,  bool enabled)  $default,) {final _that = this;
switch (_that) {
case _SourceDebuggerSort():
return $default(_that.name,_that.query,_that.enabled);case _:
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

@optionalTypeArgs TResult? whenOrNull<TResult extends Object?>(TResult? Function( String name,  String query,  bool enabled)?  $default,) {final _that = this;
switch (_that) {
case _SourceDebuggerSort() when $default != null:
return $default(_that.name,_that.query,_that.enabled);case _:
  return null;

}
}

}

/// @nodoc


class _SourceDebuggerSort implements SourceDebuggerSort {
  const _SourceDebuggerSort({required this.name, required this.query, required this.enabled});


@override final  String name;
@override final  String query;
@override final  bool enabled;

/// Create a copy of SourceDebuggerSort
/// with the given fields replaced by the non-null parameter values.
@override @JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
_$SourceDebuggerSortCopyWith<_SourceDebuggerSort> get copyWith => __$SourceDebuggerSortCopyWithImpl<_SourceDebuggerSort>(this, _$identity);



@override
bool operator ==(Object other) {
    return identical(this, other) || (other.runtimeType == runtimeType&&other is _SourceDebuggerSort&&(identical(other.name, name) || other.name == name)&&(identical(other.query, query) || other.query == query)&&(identical(other.enabled, enabled) || other.enabled == enabled));
}


@override
int get hashCode {
    return Object.hash(runtimeType,name,query,enabled);
}

@override
String toString() {
    return 'SourceDebuggerSort(name: $name, query: $query, enabled: $enabled)';
}


}

/// @nodoc
abstract mixin class _$SourceDebuggerSortCopyWith<$Res> implements $SourceDebuggerSortCopyWith<$Res> {
  factory _$SourceDebuggerSortCopyWith(_SourceDebuggerSort value, $Res Function(_SourceDebuggerSort) _then) = __$SourceDebuggerSortCopyWithImpl;
@override @useResult
$Res call({
 String name, String query, bool enabled
});




}
/// @nodoc
class __$SourceDebuggerSortCopyWithImpl<$Res>
    implements _$SourceDebuggerSortCopyWith<$Res> {
  __$SourceDebuggerSortCopyWithImpl(this._self, this._then);

  final _SourceDebuggerSort _self;
  final $Res Function(_SourceDebuggerSort) _then;

/// Create a copy of SourceDebuggerSort
/// with the given fields replaced by the non-null parameter values.
@override @pragma('vm:prefer-inline') $Res call({Object? name = null,Object? query = null,Object? enabled = null,}) {
  return _then(_SourceDebuggerSort(
name: null == name ? _self.name : name // ignore: cast_nullable_to_non_nullable
as String,query: null == query ? _self.query : query // ignore: cast_nullable_to_non_nullable
as String,enabled: null == enabled ? _self.enabled : enabled // ignore: cast_nullable_to_non_nullable
as bool,
  ));
}


}

/// @nodoc
mixin _$SourceDebuggerSnapshot {

 int get generation; int get revision; String get kind; String get name; String get keyword; String get query; int get queryStart; int get queryEnd; String get output; bool get help; bool get loading; bool get loaded; bool get running; bool get missing; bool get closed; List<SourceDebuggerSort> get sorts; int get selectedSort; List<String> get htmlStages; String? get error; String? get issue; bool get canRetry;
/// Create a copy of SourceDebuggerSnapshot
/// with the given fields replaced by the non-null parameter values.
@JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
$SourceDebuggerSnapshotCopyWith<SourceDebuggerSnapshot> get copyWith => _$SourceDebuggerSnapshotCopyWithImpl<SourceDebuggerSnapshot>(this as SourceDebuggerSnapshot, _$identity);



@override
bool operator ==(Object other) {
  final _this = this as SourceDebuggerSnapshot;
  return identical(this, other) || (other.runtimeType == runtimeType&&other is SourceDebuggerSnapshot&&(identical(other.generation, _this.generation) || other.generation == _this.generation)&&(identical(other.revision, _this.revision) || other.revision == _this.revision)&&(identical(other.kind, _this.kind) || other.kind == _this.kind)&&(identical(other.name, _this.name) || other.name == _this.name)&&(identical(other.keyword, _this.keyword) || other.keyword == _this.keyword)&&(identical(other.query, _this.query) || other.query == _this.query)&&(identical(other.queryStart, _this.queryStart) || other.queryStart == _this.queryStart)&&(identical(other.queryEnd, _this.queryEnd) || other.queryEnd == _this.queryEnd)&&(identical(other.output, _this.output) || other.output == _this.output)&&(identical(other.help, _this.help) || other.help == _this.help)&&(identical(other.loading, _this.loading) || other.loading == _this.loading)&&(identical(other.loaded, _this.loaded) || other.loaded == _this.loaded)&&(identical(other.running, _this.running) || other.running == _this.running)&&(identical(other.missing, _this.missing) || other.missing == _this.missing)&&(identical(other.closed, _this.closed) || other.closed == _this.closed)&&const DeepCollectionEquality().equals(other.sorts, _this.sorts)&&(identical(other.selectedSort, _this.selectedSort) || other.selectedSort == _this.selectedSort)&&const DeepCollectionEquality().equals(other.htmlStages, _this.htmlStages)&&(identical(other.error, _this.error) || other.error == _this.error)&&(identical(other.issue, _this.issue) || other.issue == _this.issue)&&(identical(other.canRetry, _this.canRetry) || other.canRetry == _this.canRetry));
}


@override
int get hashCode {
  final _this = this as SourceDebuggerSnapshot;
  return Object.hashAll([runtimeType,_this.generation,_this.revision,_this.kind,_this.name,_this.keyword,_this.query,_this.queryStart,_this.queryEnd,_this.output,_this.help,_this.loading,_this.loaded,_this.running,_this.missing,_this.closed,const DeepCollectionEquality().hash(_this.sorts),_this.selectedSort,const DeepCollectionEquality().hash(_this.htmlStages),_this.error,_this.issue,_this.canRetry]);
}

@override
String toString() {
  final _this = this as SourceDebuggerSnapshot;
  return 'SourceDebuggerSnapshot(generation: ${_this.generation}, revision: ${_this.revision}, kind: ${_this.kind}, name: ${_this.name}, keyword: ${_this.keyword}, query: ${_this.query}, queryStart: ${_this.queryStart}, queryEnd: ${_this.queryEnd}, output: ${_this.output}, help: ${_this.help}, loading: ${_this.loading}, loaded: ${_this.loaded}, running: ${_this.running}, missing: ${_this.missing}, closed: ${_this.closed}, sorts: ${_this.sorts}, selectedSort: ${_this.selectedSort}, htmlStages: ${_this.htmlStages}, error: ${_this.error}, issue: ${_this.issue}, canRetry: ${_this.canRetry})';
}


}

/// @nodoc
abstract mixin class $SourceDebuggerSnapshotCopyWith<$Res>  {
  factory $SourceDebuggerSnapshotCopyWith(SourceDebuggerSnapshot value, $Res Function(SourceDebuggerSnapshot) _then) = _$SourceDebuggerSnapshotCopyWithImpl;
@useResult
$Res call({
 int generation, int revision, String kind, String name, String keyword, String query, int queryStart, int queryEnd, String output, bool help, bool loading, bool loaded, bool running, bool missing, bool closed, List<SourceDebuggerSort> sorts, int selectedSort, List<String> htmlStages, String? error, String? issue, bool canRetry
});




}
/// @nodoc
class _$SourceDebuggerSnapshotCopyWithImpl<$Res>
    implements $SourceDebuggerSnapshotCopyWith<$Res> {
  _$SourceDebuggerSnapshotCopyWithImpl(this._self, this._then);

  final SourceDebuggerSnapshot _self;
  final $Res Function(SourceDebuggerSnapshot) _then;

/// Create a copy of SourceDebuggerSnapshot
/// with the given fields replaced by the non-null parameter values.
@pragma('vm:prefer-inline') @override $Res call({Object? generation = null,Object? revision = null,Object? kind = null,Object? name = null,Object? keyword = null,Object? query = null,Object? queryStart = null,Object? queryEnd = null,Object? output = null,Object? help = null,Object? loading = null,Object? loaded = null,Object? running = null,Object? missing = null,Object? closed = null,Object? sorts = null,Object? selectedSort = null,Object? htmlStages = null,Object? error = freezed,Object? issue = freezed,Object? canRetry = null,}) {
  return _then(SourceDebuggerSnapshot(
generation: null == generation ? _self.generation : generation // ignore: cast_nullable_to_non_nullable
as int,revision: null == revision ? _self.revision : revision // ignore: cast_nullable_to_non_nullable
as int,kind: null == kind ? _self.kind : kind // ignore: cast_nullable_to_non_nullable
as String,name: null == name ? _self.name : name // ignore: cast_nullable_to_non_nullable
as String,keyword: null == keyword ? _self.keyword : keyword // ignore: cast_nullable_to_non_nullable
as String,query: null == query ? _self.query : query // ignore: cast_nullable_to_non_nullable
as String,queryStart: null == queryStart ? _self.queryStart : queryStart // ignore: cast_nullable_to_non_nullable
as int,queryEnd: null == queryEnd ? _self.queryEnd : queryEnd // ignore: cast_nullable_to_non_nullable
as int,output: null == output ? _self.output : output // ignore: cast_nullable_to_non_nullable
as String,help: null == help ? _self.help : help // ignore: cast_nullable_to_non_nullable
as bool,loading: null == loading ? _self.loading : loading // ignore: cast_nullable_to_non_nullable
as bool,loaded: null == loaded ? _self.loaded : loaded // ignore: cast_nullable_to_non_nullable
as bool,running: null == running ? _self.running : running // ignore: cast_nullable_to_non_nullable
as bool,missing: null == missing ? _self.missing : missing // ignore: cast_nullable_to_non_nullable
as bool,closed: null == closed ? _self.closed : closed // ignore: cast_nullable_to_non_nullable
as bool,sorts: null == sorts ? _self.sorts : sorts // ignore: cast_nullable_to_non_nullable
as List<SourceDebuggerSort>,selectedSort: null == selectedSort ? _self.selectedSort : selectedSort // ignore: cast_nullable_to_non_nullable
as int,htmlStages: null == htmlStages ? _self.htmlStages : htmlStages // ignore: cast_nullable_to_non_nullable
as List<String>,error: freezed == error ? _self.error : error // ignore: cast_nullable_to_non_nullable
as String?,issue: freezed == issue ? _self.issue : issue // ignore: cast_nullable_to_non_nullable
as String?,canRetry: null == canRetry ? _self.canRetry : canRetry // ignore: cast_nullable_to_non_nullable
as bool,
  ));
}

}


/// Adds pattern-matching-related methods to [SourceDebuggerSnapshot].
extension SourceDebuggerSnapshotPatterns on SourceDebuggerSnapshot {
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

@optionalTypeArgs TResult maybeMap<TResult extends Object?>(TResult Function( _SourceDebuggerSnapshot value)?  $default,{required TResult orElse(),}){
final _that = this;
switch (_that) {
case _SourceDebuggerSnapshot() when $default != null:
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

@optionalTypeArgs TResult map<TResult extends Object?>(TResult Function( _SourceDebuggerSnapshot value)  $default,){
final _that = this;
switch (_that) {
case _SourceDebuggerSnapshot():
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

@optionalTypeArgs TResult? mapOrNull<TResult extends Object?>(TResult? Function( _SourceDebuggerSnapshot value)?  $default,){
final _that = this;
switch (_that) {
case _SourceDebuggerSnapshot() when $default != null:
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

@optionalTypeArgs TResult maybeWhen<TResult extends Object?>(TResult Function( int generation,  int revision,  String kind,  String name,  String keyword,  String query,  int queryStart,  int queryEnd,  String output,  bool help,  bool loading,  bool loaded,  bool running,  bool missing,  bool closed,  List<SourceDebuggerSort> sorts,  int selectedSort,  List<String> htmlStages,  String? error,  String? issue,  bool canRetry)?  $default,{required TResult orElse(),}) {final _that = this;
switch (_that) {
case _SourceDebuggerSnapshot() when $default != null:
return $default(_that.generation,_that.revision,_that.kind,_that.name,_that.keyword,_that.query,_that.queryStart,_that.queryEnd,_that.output,_that.help,_that.loading,_that.loaded,_that.running,_that.missing,_that.closed,_that.sorts,_that.selectedSort,_that.htmlStages,_that.error,_that.issue,_that.canRetry);case _:
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

@optionalTypeArgs TResult when<TResult extends Object?>(TResult Function( int generation,  int revision,  String kind,  String name,  String keyword,  String query,  int queryStart,  int queryEnd,  String output,  bool help,  bool loading,  bool loaded,  bool running,  bool missing,  bool closed,  List<SourceDebuggerSort> sorts,  int selectedSort,  List<String> htmlStages,  String? error,  String? issue,  bool canRetry)  $default,) {final _that = this;
switch (_that) {
case _SourceDebuggerSnapshot():
return $default(_that.generation,_that.revision,_that.kind,_that.name,_that.keyword,_that.query,_that.queryStart,_that.queryEnd,_that.output,_that.help,_that.loading,_that.loaded,_that.running,_that.missing,_that.closed,_that.sorts,_that.selectedSort,_that.htmlStages,_that.error,_that.issue,_that.canRetry);case _:
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

@optionalTypeArgs TResult? whenOrNull<TResult extends Object?>(TResult? Function( int generation,  int revision,  String kind,  String name,  String keyword,  String query,  int queryStart,  int queryEnd,  String output,  bool help,  bool loading,  bool loaded,  bool running,  bool missing,  bool closed,  List<SourceDebuggerSort> sorts,  int selectedSort,  List<String> htmlStages,  String? error,  String? issue,  bool canRetry)?  $default,) {final _that = this;
switch (_that) {
case _SourceDebuggerSnapshot() when $default != null:
return $default(_that.generation,_that.revision,_that.kind,_that.name,_that.keyword,_that.query,_that.queryStart,_that.queryEnd,_that.output,_that.help,_that.loading,_that.loaded,_that.running,_that.missing,_that.closed,_that.sorts,_that.selectedSort,_that.htmlStages,_that.error,_that.issue,_that.canRetry);case _:
  return null;

}
}

}

/// @nodoc


class _SourceDebuggerSnapshot extends SourceDebuggerSnapshot {
  const _SourceDebuggerSnapshot({required this.generation, required this.revision, required this.kind, this.name = '', this.keyword = '', this.query = '', this.queryStart = 0, this.queryEnd = 0, this.output = '', this.help = true, this.loading = true, this.loaded = false, this.running = false, this.missing = false, this.closed = false,  List<SourceDebuggerSort> sorts = const [], this.selectedSort = 0,  List<String> htmlStages = const [], this.error, this.issue, this.canRetry = false}): _sorts = sorts,_htmlStages = htmlStages,super._();


@override final  int generation;
@override final  int revision;
@override final  String kind;
@override@JsonKey() final  String name;
@override@JsonKey() final  String keyword;
@override@JsonKey() final  String query;
@override@JsonKey() final  int queryStart;
@override@JsonKey() final  int queryEnd;
@override@JsonKey() final  String output;
@override@JsonKey() final  bool help;
@override@JsonKey() final  bool loading;
@override@JsonKey() final  bool loaded;
@override@JsonKey() final  bool running;
@override@JsonKey() final  bool missing;
@override@JsonKey() final  bool closed;
 final  List<SourceDebuggerSort> _sorts;
@override@JsonKey() List<SourceDebuggerSort> get sorts {
  if (_sorts is EqualUnmodifiableListView) return _sorts;
  // ignore: implicit_dynamic_type
  return EqualUnmodifiableListView(_sorts);
}

@override@JsonKey() final  int selectedSort;
 final  List<String> _htmlStages;
@override@JsonKey() List<String> get htmlStages {
  if (_htmlStages is EqualUnmodifiableListView) return _htmlStages;
  // ignore: implicit_dynamic_type
  return EqualUnmodifiableListView(_htmlStages);
}

@override final  String? error;
@override final  String? issue;
@override@JsonKey() final  bool canRetry;

/// Create a copy of SourceDebuggerSnapshot
/// with the given fields replaced by the non-null parameter values.
@override @JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
_$SourceDebuggerSnapshotCopyWith<_SourceDebuggerSnapshot> get copyWith => __$SourceDebuggerSnapshotCopyWithImpl<_SourceDebuggerSnapshot>(this, _$identity);



@override
bool operator ==(Object other) {
    return identical(this, other) || (other.runtimeType == runtimeType&&other is _SourceDebuggerSnapshot&&(identical(other.generation, generation) || other.generation == generation)&&(identical(other.revision, revision) || other.revision == revision)&&(identical(other.kind, kind) || other.kind == kind)&&(identical(other.name, name) || other.name == name)&&(identical(other.keyword, keyword) || other.keyword == keyword)&&(identical(other.query, query) || other.query == query)&&(identical(other.queryStart, queryStart) || other.queryStart == queryStart)&&(identical(other.queryEnd, queryEnd) || other.queryEnd == queryEnd)&&(identical(other.output, output) || other.output == output)&&(identical(other.help, help) || other.help == help)&&(identical(other.loading, loading) || other.loading == loading)&&(identical(other.loaded, loaded) || other.loaded == loaded)&&(identical(other.running, running) || other.running == running)&&(identical(other.missing, missing) || other.missing == missing)&&(identical(other.closed, closed) || other.closed == closed)&&const DeepCollectionEquality().equals(other.sorts, _sorts)&&(identical(other.selectedSort, selectedSort) || other.selectedSort == selectedSort)&&const DeepCollectionEquality().equals(other.htmlStages, _htmlStages)&&(identical(other.error, error) || other.error == error)&&(identical(other.issue, issue) || other.issue == issue)&&(identical(other.canRetry, canRetry) || other.canRetry == canRetry));
}


@override
int get hashCode {
    return Object.hashAll([runtimeType,generation,revision,kind,name,keyword,query,queryStart,queryEnd,output,help,loading,loaded,running,missing,closed,const DeepCollectionEquality().hash(_sorts),selectedSort,const DeepCollectionEquality().hash(_htmlStages),error,issue,canRetry]);
}

@override
String toString() {
    return 'SourceDebuggerSnapshot(generation: $generation, revision: $revision, kind: $kind, name: $name, keyword: $keyword, query: $query, queryStart: $queryStart, queryEnd: $queryEnd, output: $output, help: $help, loading: $loading, loaded: $loaded, running: $running, missing: $missing, closed: $closed, sorts: $sorts, selectedSort: $selectedSort, htmlStages: $htmlStages, error: $error, issue: $issue, canRetry: $canRetry)';
}


}

/// @nodoc
abstract mixin class _$SourceDebuggerSnapshotCopyWith<$Res> implements $SourceDebuggerSnapshotCopyWith<$Res> {
  factory _$SourceDebuggerSnapshotCopyWith(_SourceDebuggerSnapshot value, $Res Function(_SourceDebuggerSnapshot) _then) = __$SourceDebuggerSnapshotCopyWithImpl;
@override @useResult
$Res call({
 int generation, int revision, String kind, String name, String keyword, String query, int queryStart, int queryEnd, String output, bool help, bool loading, bool loaded, bool running, bool missing, bool closed, List<SourceDebuggerSort> sorts, int selectedSort, List<String> htmlStages, String? error, String? issue, bool canRetry
});




}
/// @nodoc
class __$SourceDebuggerSnapshotCopyWithImpl<$Res>
    implements _$SourceDebuggerSnapshotCopyWith<$Res> {
  __$SourceDebuggerSnapshotCopyWithImpl(this._self, this._then);

  final _SourceDebuggerSnapshot _self;
  final $Res Function(_SourceDebuggerSnapshot) _then;

/// Create a copy of SourceDebuggerSnapshot
/// with the given fields replaced by the non-null parameter values.
@override @pragma('vm:prefer-inline') $Res call({Object? generation = null,Object? revision = null,Object? kind = null,Object? name = null,Object? keyword = null,Object? query = null,Object? queryStart = null,Object? queryEnd = null,Object? output = null,Object? help = null,Object? loading = null,Object? loaded = null,Object? running = null,Object? missing = null,Object? closed = null,Object? sorts = null,Object? selectedSort = null,Object? htmlStages = null,Object? error = freezed,Object? issue = freezed,Object? canRetry = null,}) {
  return _then(_SourceDebuggerSnapshot(
generation: null == generation ? _self.generation : generation // ignore: cast_nullable_to_non_nullable
as int,revision: null == revision ? _self.revision : revision // ignore: cast_nullable_to_non_nullable
as int,kind: null == kind ? _self.kind : kind // ignore: cast_nullable_to_non_nullable
as String,name: null == name ? _self.name : name // ignore: cast_nullable_to_non_nullable
as String,keyword: null == keyword ? _self.keyword : keyword // ignore: cast_nullable_to_non_nullable
as String,query: null == query ? _self.query : query // ignore: cast_nullable_to_non_nullable
as String,queryStart: null == queryStart ? _self.queryStart : queryStart // ignore: cast_nullable_to_non_nullable
as int,queryEnd: null == queryEnd ? _self.queryEnd : queryEnd // ignore: cast_nullable_to_non_nullable
as int,output: null == output ? _self.output : output // ignore: cast_nullable_to_non_nullable
as String,help: null == help ? _self.help : help // ignore: cast_nullable_to_non_nullable
as bool,loading: null == loading ? _self.loading : loading // ignore: cast_nullable_to_non_nullable
as bool,loaded: null == loaded ? _self.loaded : loaded // ignore: cast_nullable_to_non_nullable
as bool,running: null == running ? _self.running : running // ignore: cast_nullable_to_non_nullable
as bool,missing: null == missing ? _self.missing : missing // ignore: cast_nullable_to_non_nullable
as bool,closed: null == closed ? _self.closed : closed // ignore: cast_nullable_to_non_nullable
as bool,sorts: null == sorts ? _self._sorts : sorts // ignore: cast_nullable_to_non_nullable
as List<SourceDebuggerSort>,selectedSort: null == selectedSort ? _self.selectedSort : selectedSort // ignore: cast_nullable_to_non_nullable
as int,htmlStages: null == htmlStages ? _self._htmlStages : htmlStages // ignore: cast_nullable_to_non_nullable
as List<String>,error: freezed == error ? _self.error : error // ignore: cast_nullable_to_non_nullable
as String?,issue: freezed == issue ? _self.issue : issue // ignore: cast_nullable_to_non_nullable
as String?,canRetry: null == canRetry ? _self.canRetry : canRetry // ignore: cast_nullable_to_non_nullable
as bool,
  ));
}


}

// dart format on
