// GENERATED CODE - DO NOT MODIFY BY HAND
// coverage:ignore-file
// ignore_for_file: type=lint, type=warning, deprecated_member_use, deprecated_member_use_from_same_package
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'code_editor_state.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// dart format off
T _$identity<T>(T value) => value;
/// @nodoc
mixin _$CodeEditorSearch {

 String get query; String get replacement; bool get visible; bool get replaceVisible; bool get regex; bool get ignoreCase; int get queryStart; int get queryEnd; int get replacementStart; int get replacementEnd;
/// Create a copy of CodeEditorSearch
/// with the given fields replaced by the non-null parameter values.
@JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
$CodeEditorSearchCopyWith<CodeEditorSearch> get copyWith => _$CodeEditorSearchCopyWithImpl<CodeEditorSearch>(this as CodeEditorSearch, _$identity);



@override
bool operator ==(Object other) {
  final _this = this as CodeEditorSearch;
  return identical(this, other) || (other.runtimeType == runtimeType&&other is CodeEditorSearch&&(identical(other.query, _this.query) || other.query == _this.query)&&(identical(other.replacement, _this.replacement) || other.replacement == _this.replacement)&&(identical(other.visible, _this.visible) || other.visible == _this.visible)&&(identical(other.replaceVisible, _this.replaceVisible) || other.replaceVisible == _this.replaceVisible)&&(identical(other.regex, _this.regex) || other.regex == _this.regex)&&(identical(other.ignoreCase, _this.ignoreCase) || other.ignoreCase == _this.ignoreCase)&&(identical(other.queryStart, _this.queryStart) || other.queryStart == _this.queryStart)&&(identical(other.queryEnd, _this.queryEnd) || other.queryEnd == _this.queryEnd)&&(identical(other.replacementStart, _this.replacementStart) || other.replacementStart == _this.replacementStart)&&(identical(other.replacementEnd, _this.replacementEnd) || other.replacementEnd == _this.replacementEnd));
}


@override
int get hashCode {
  final _this = this as CodeEditorSearch;
  return Object.hash(runtimeType,_this.query,_this.replacement,_this.visible,_this.replaceVisible,_this.regex,_this.ignoreCase,_this.queryStart,_this.queryEnd,_this.replacementStart,_this.replacementEnd);
}

@override
String toString() {
  final _this = this as CodeEditorSearch;
  return 'CodeEditorSearch(query: ${_this.query}, replacement: ${_this.replacement}, visible: ${_this.visible}, replaceVisible: ${_this.replaceVisible}, regex: ${_this.regex}, ignoreCase: ${_this.ignoreCase}, queryStart: ${_this.queryStart}, queryEnd: ${_this.queryEnd}, replacementStart: ${_this.replacementStart}, replacementEnd: ${_this.replacementEnd})';
}


}

/// @nodoc
abstract mixin class $CodeEditorSearchCopyWith<$Res>  {
  factory $CodeEditorSearchCopyWith(CodeEditorSearch value, $Res Function(CodeEditorSearch) _then) = _$CodeEditorSearchCopyWithImpl;
@useResult
$Res call({
 String query, String replacement, bool visible, bool replaceVisible, bool regex, bool ignoreCase, int queryStart, int queryEnd, int replacementStart, int replacementEnd
});




}
/// @nodoc
class _$CodeEditorSearchCopyWithImpl<$Res>
    implements $CodeEditorSearchCopyWith<$Res> {
  _$CodeEditorSearchCopyWithImpl(this._self, this._then);

  final CodeEditorSearch _self;
  final $Res Function(CodeEditorSearch) _then;

/// Create a copy of CodeEditorSearch
/// with the given fields replaced by the non-null parameter values.
@pragma('vm:prefer-inline') @override $Res call({Object? query = null,Object? replacement = null,Object? visible = null,Object? replaceVisible = null,Object? regex = null,Object? ignoreCase = null,Object? queryStart = null,Object? queryEnd = null,Object? replacementStart = null,Object? replacementEnd = null,}) {
  return _then(CodeEditorSearch(
query: null == query ? _self.query : query // ignore: cast_nullable_to_non_nullable
as String,replacement: null == replacement ? _self.replacement : replacement // ignore: cast_nullable_to_non_nullable
as String,visible: null == visible ? _self.visible : visible // ignore: cast_nullable_to_non_nullable
as bool,replaceVisible: null == replaceVisible ? _self.replaceVisible : replaceVisible // ignore: cast_nullable_to_non_nullable
as bool,regex: null == regex ? _self.regex : regex // ignore: cast_nullable_to_non_nullable
as bool,ignoreCase: null == ignoreCase ? _self.ignoreCase : ignoreCase // ignore: cast_nullable_to_non_nullable
as bool,queryStart: null == queryStart ? _self.queryStart : queryStart // ignore: cast_nullable_to_non_nullable
as int,queryEnd: null == queryEnd ? _self.queryEnd : queryEnd // ignore: cast_nullable_to_non_nullable
as int,replacementStart: null == replacementStart ? _self.replacementStart : replacementStart // ignore: cast_nullable_to_non_nullable
as int,replacementEnd: null == replacementEnd ? _self.replacementEnd : replacementEnd // ignore: cast_nullable_to_non_nullable
as int,
  ));
}

}


/// Adds pattern-matching-related methods to [CodeEditorSearch].
extension CodeEditorSearchPatterns on CodeEditorSearch {
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

@optionalTypeArgs TResult maybeMap<TResult extends Object?>(TResult Function( _CodeEditorSearch value)?  $default,{required TResult orElse(),}){
final _that = this;
switch (_that) {
case _CodeEditorSearch() when $default != null:
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

@optionalTypeArgs TResult map<TResult extends Object?>(TResult Function( _CodeEditorSearch value)  $default,){
final _that = this;
switch (_that) {
case _CodeEditorSearch():
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

@optionalTypeArgs TResult? mapOrNull<TResult extends Object?>(TResult? Function( _CodeEditorSearch value)?  $default,){
final _that = this;
switch (_that) {
case _CodeEditorSearch() when $default != null:
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

@optionalTypeArgs TResult maybeWhen<TResult extends Object?>(TResult Function( String query,  String replacement,  bool visible,  bool replaceVisible,  bool regex,  bool ignoreCase,  int queryStart,  int queryEnd,  int replacementStart,  int replacementEnd)?  $default,{required TResult orElse(),}) {final _that = this;
switch (_that) {
case _CodeEditorSearch() when $default != null:
return $default(_that.query,_that.replacement,_that.visible,_that.replaceVisible,_that.regex,_that.ignoreCase,_that.queryStart,_that.queryEnd,_that.replacementStart,_that.replacementEnd);case _:
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

@optionalTypeArgs TResult when<TResult extends Object?>(TResult Function( String query,  String replacement,  bool visible,  bool replaceVisible,  bool regex,  bool ignoreCase,  int queryStart,  int queryEnd,  int replacementStart,  int replacementEnd)  $default,) {final _that = this;
switch (_that) {
case _CodeEditorSearch():
return $default(_that.query,_that.replacement,_that.visible,_that.replaceVisible,_that.regex,_that.ignoreCase,_that.queryStart,_that.queryEnd,_that.replacementStart,_that.replacementEnd);case _:
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

@optionalTypeArgs TResult? whenOrNull<TResult extends Object?>(TResult? Function( String query,  String replacement,  bool visible,  bool replaceVisible,  bool regex,  bool ignoreCase,  int queryStart,  int queryEnd,  int replacementStart,  int replacementEnd)?  $default,) {final _that = this;
switch (_that) {
case _CodeEditorSearch() when $default != null:
return $default(_that.query,_that.replacement,_that.visible,_that.replaceVisible,_that.regex,_that.ignoreCase,_that.queryStart,_that.queryEnd,_that.replacementStart,_that.replacementEnd);case _:
  return null;

}
}

}

/// @nodoc


class _CodeEditorSearch extends CodeEditorSearch {
  const _CodeEditorSearch({this.query = '', this.replacement = '', this.visible = false, this.replaceVisible = false, this.regex = true, this.ignoreCase = false, this.queryStart = 0, this.queryEnd = 0, this.replacementStart = 0, this.replacementEnd = 0}): super._();


@override@JsonKey() final  String query;
@override@JsonKey() final  String replacement;
@override@JsonKey() final  bool visible;
@override@JsonKey() final  bool replaceVisible;
@override@JsonKey() final  bool regex;
@override@JsonKey() final  bool ignoreCase;
@override@JsonKey() final  int queryStart;
@override@JsonKey() final  int queryEnd;
@override@JsonKey() final  int replacementStart;
@override@JsonKey() final  int replacementEnd;

/// Create a copy of CodeEditorSearch
/// with the given fields replaced by the non-null parameter values.
@override @JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
_$CodeEditorSearchCopyWith<_CodeEditorSearch> get copyWith => __$CodeEditorSearchCopyWithImpl<_CodeEditorSearch>(this, _$identity);



@override
bool operator ==(Object other) {
    return identical(this, other) || (other.runtimeType == runtimeType&&other is _CodeEditorSearch&&(identical(other.query, query) || other.query == query)&&(identical(other.replacement, replacement) || other.replacement == replacement)&&(identical(other.visible, visible) || other.visible == visible)&&(identical(other.replaceVisible, replaceVisible) || other.replaceVisible == replaceVisible)&&(identical(other.regex, regex) || other.regex == regex)&&(identical(other.ignoreCase, ignoreCase) || other.ignoreCase == ignoreCase)&&(identical(other.queryStart, queryStart) || other.queryStart == queryStart)&&(identical(other.queryEnd, queryEnd) || other.queryEnd == queryEnd)&&(identical(other.replacementStart, replacementStart) || other.replacementStart == replacementStart)&&(identical(other.replacementEnd, replacementEnd) || other.replacementEnd == replacementEnd));
}


@override
int get hashCode {
    return Object.hash(runtimeType,query,replacement,visible,replaceVisible,regex,ignoreCase,queryStart,queryEnd,replacementStart,replacementEnd);
}

@override
String toString() {
    return 'CodeEditorSearch(query: $query, replacement: $replacement, visible: $visible, replaceVisible: $replaceVisible, regex: $regex, ignoreCase: $ignoreCase, queryStart: $queryStart, queryEnd: $queryEnd, replacementStart: $replacementStart, replacementEnd: $replacementEnd)';
}


}

/// @nodoc
abstract mixin class _$CodeEditorSearchCopyWith<$Res> implements $CodeEditorSearchCopyWith<$Res> {
  factory _$CodeEditorSearchCopyWith(_CodeEditorSearch value, $Res Function(_CodeEditorSearch) _then) = __$CodeEditorSearchCopyWithImpl;
@override @useResult
$Res call({
 String query, String replacement, bool visible, bool replaceVisible, bool regex, bool ignoreCase, int queryStart, int queryEnd, int replacementStart, int replacementEnd
});




}
/// @nodoc
class __$CodeEditorSearchCopyWithImpl<$Res>
    implements _$CodeEditorSearchCopyWith<$Res> {
  __$CodeEditorSearchCopyWithImpl(this._self, this._then);

  final _CodeEditorSearch _self;
  final $Res Function(_CodeEditorSearch) _then;

/// Create a copy of CodeEditorSearch
/// with the given fields replaced by the non-null parameter values.
@override @pragma('vm:prefer-inline') $Res call({Object? query = null,Object? replacement = null,Object? visible = null,Object? replaceVisible = null,Object? regex = null,Object? ignoreCase = null,Object? queryStart = null,Object? queryEnd = null,Object? replacementStart = null,Object? replacementEnd = null,}) {
  return _then(_CodeEditorSearch(
query: null == query ? _self.query : query // ignore: cast_nullable_to_non_nullable
as String,replacement: null == replacement ? _self.replacement : replacement // ignore: cast_nullable_to_non_nullable
as String,visible: null == visible ? _self.visible : visible // ignore: cast_nullable_to_non_nullable
as bool,replaceVisible: null == replaceVisible ? _self.replaceVisible : replaceVisible // ignore: cast_nullable_to_non_nullable
as bool,regex: null == regex ? _self.regex : regex // ignore: cast_nullable_to_non_nullable
as bool,ignoreCase: null == ignoreCase ? _self.ignoreCase : ignoreCase // ignore: cast_nullable_to_non_nullable
as bool,queryStart: null == queryStart ? _self.queryStart : queryStart // ignore: cast_nullable_to_non_nullable
as int,queryEnd: null == queryEnd ? _self.queryEnd : queryEnd // ignore: cast_nullable_to_non_nullable
as int,replacementStart: null == replacementStart ? _self.replacementStart : replacementStart // ignore: cast_nullable_to_non_nullable
as int,replacementEnd: null == replacementEnd ? _self.replacementEnd : replacementEnd // ignore: cast_nullable_to_non_nullable
as int,
  ));
}


}

/// @nodoc
mixin _$CodeEditorEngine {

 bool get ready; bool get reading; bool get failed; bool get replacing; String? get replacementError; String get searchResult;
/// Create a copy of CodeEditorEngine
/// with the given fields replaced by the non-null parameter values.
@JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
$CodeEditorEngineCopyWith<CodeEditorEngine> get copyWith => _$CodeEditorEngineCopyWithImpl<CodeEditorEngine>(this as CodeEditorEngine, _$identity);



@override
bool operator ==(Object other) {
  final _this = this as CodeEditorEngine;
  return identical(this, other) || (other.runtimeType == runtimeType&&other is CodeEditorEngine&&(identical(other.ready, _this.ready) || other.ready == _this.ready)&&(identical(other.reading, _this.reading) || other.reading == _this.reading)&&(identical(other.failed, _this.failed) || other.failed == _this.failed)&&(identical(other.replacing, _this.replacing) || other.replacing == _this.replacing)&&(identical(other.replacementError, _this.replacementError) || other.replacementError == _this.replacementError)&&(identical(other.searchResult, _this.searchResult) || other.searchResult == _this.searchResult));
}


@override
int get hashCode {
  final _this = this as CodeEditorEngine;
  return Object.hash(runtimeType,_this.ready,_this.reading,_this.failed,_this.replacing,_this.replacementError,_this.searchResult);
}

@override
String toString() {
  final _this = this as CodeEditorEngine;
  return 'CodeEditorEngine(ready: ${_this.ready}, reading: ${_this.reading}, failed: ${_this.failed}, replacing: ${_this.replacing}, replacementError: ${_this.replacementError}, searchResult: ${_this.searchResult})';
}


}

/// @nodoc
abstract mixin class $CodeEditorEngineCopyWith<$Res>  {
  factory $CodeEditorEngineCopyWith(CodeEditorEngine value, $Res Function(CodeEditorEngine) _then) = _$CodeEditorEngineCopyWithImpl;
@useResult
$Res call({
 bool ready, bool reading, bool failed, bool replacing, String? replacementError, String searchResult
});




}
/// @nodoc
class _$CodeEditorEngineCopyWithImpl<$Res>
    implements $CodeEditorEngineCopyWith<$Res> {
  _$CodeEditorEngineCopyWithImpl(this._self, this._then);

  final CodeEditorEngine _self;
  final $Res Function(CodeEditorEngine) _then;

/// Create a copy of CodeEditorEngine
/// with the given fields replaced by the non-null parameter values.
@pragma('vm:prefer-inline') @override $Res call({Object? ready = null,Object? reading = null,Object? failed = null,Object? replacing = null,Object? replacementError = freezed,Object? searchResult = null,}) {
  return _then(CodeEditorEngine(
ready: null == ready ? _self.ready : ready // ignore: cast_nullable_to_non_nullable
as bool,reading: null == reading ? _self.reading : reading // ignore: cast_nullable_to_non_nullable
as bool,failed: null == failed ? _self.failed : failed // ignore: cast_nullable_to_non_nullable
as bool,replacing: null == replacing ? _self.replacing : replacing // ignore: cast_nullable_to_non_nullable
as bool,replacementError: freezed == replacementError ? _self.replacementError : replacementError // ignore: cast_nullable_to_non_nullable
as String?,searchResult: null == searchResult ? _self.searchResult : searchResult // ignore: cast_nullable_to_non_nullable
as String,
  ));
}

}


/// Adds pattern-matching-related methods to [CodeEditorEngine].
extension CodeEditorEnginePatterns on CodeEditorEngine {
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

@optionalTypeArgs TResult maybeMap<TResult extends Object?>(TResult Function( _CodeEditorEngine value)?  $default,{required TResult orElse(),}){
final _that = this;
switch (_that) {
case _CodeEditorEngine() when $default != null:
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

@optionalTypeArgs TResult map<TResult extends Object?>(TResult Function( _CodeEditorEngine value)  $default,){
final _that = this;
switch (_that) {
case _CodeEditorEngine():
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

@optionalTypeArgs TResult? mapOrNull<TResult extends Object?>(TResult? Function( _CodeEditorEngine value)?  $default,){
final _that = this;
switch (_that) {
case _CodeEditorEngine() when $default != null:
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

@optionalTypeArgs TResult maybeWhen<TResult extends Object?>(TResult Function( bool ready,  bool reading,  bool failed,  bool replacing,  String? replacementError,  String searchResult)?  $default,{required TResult orElse(),}) {final _that = this;
switch (_that) {
case _CodeEditorEngine() when $default != null:
return $default(_that.ready,_that.reading,_that.failed,_that.replacing,_that.replacementError,_that.searchResult);case _:
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

@optionalTypeArgs TResult when<TResult extends Object?>(TResult Function( bool ready,  bool reading,  bool failed,  bool replacing,  String? replacementError,  String searchResult)  $default,) {final _that = this;
switch (_that) {
case _CodeEditorEngine():
return $default(_that.ready,_that.reading,_that.failed,_that.replacing,_that.replacementError,_that.searchResult);case _:
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

@optionalTypeArgs TResult? whenOrNull<TResult extends Object?>(TResult? Function( bool ready,  bool reading,  bool failed,  bool replacing,  String? replacementError,  String searchResult)?  $default,) {final _that = this;
switch (_that) {
case _CodeEditorEngine() when $default != null:
return $default(_that.ready,_that.reading,_that.failed,_that.replacing,_that.replacementError,_that.searchResult);case _:
  return null;

}
}

}

/// @nodoc


class _CodeEditorEngine extends CodeEditorEngine {
  const _CodeEditorEngine({this.ready = false, this.reading = false, this.failed = false, this.replacing = false, this.replacementError, this.searchResult = '0'}): super._();


@override@JsonKey() final  bool ready;
@override@JsonKey() final  bool reading;
@override@JsonKey() final  bool failed;
@override@JsonKey() final  bool replacing;
@override final  String? replacementError;
@override@JsonKey() final  String searchResult;

/// Create a copy of CodeEditorEngine
/// with the given fields replaced by the non-null parameter values.
@override @JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
_$CodeEditorEngineCopyWith<_CodeEditorEngine> get copyWith => __$CodeEditorEngineCopyWithImpl<_CodeEditorEngine>(this, _$identity);



@override
bool operator ==(Object other) {
    return identical(this, other) || (other.runtimeType == runtimeType&&other is _CodeEditorEngine&&(identical(other.ready, ready) || other.ready == ready)&&(identical(other.reading, reading) || other.reading == reading)&&(identical(other.failed, failed) || other.failed == failed)&&(identical(other.replacing, replacing) || other.replacing == replacing)&&(identical(other.replacementError, replacementError) || other.replacementError == replacementError)&&(identical(other.searchResult, searchResult) || other.searchResult == searchResult));
}


@override
int get hashCode {
    return Object.hash(runtimeType,ready,reading,failed,replacing,replacementError,searchResult);
}

@override
String toString() {
    return 'CodeEditorEngine(ready: $ready, reading: $reading, failed: $failed, replacing: $replacing, replacementError: $replacementError, searchResult: $searchResult)';
}


}

/// @nodoc
abstract mixin class _$CodeEditorEngineCopyWith<$Res> implements $CodeEditorEngineCopyWith<$Res> {
  factory _$CodeEditorEngineCopyWith(_CodeEditorEngine value, $Res Function(_CodeEditorEngine) _then) = __$CodeEditorEngineCopyWithImpl;
@override @useResult
$Res call({
 bool ready, bool reading, bool failed, bool replacing, String? replacementError, String searchResult
});




}
/// @nodoc
class __$CodeEditorEngineCopyWithImpl<$Res>
    implements _$CodeEditorEngineCopyWith<$Res> {
  __$CodeEditorEngineCopyWithImpl(this._self, this._then);

  final _CodeEditorEngine _self;
  final $Res Function(_CodeEditorEngine) _then;

/// Create a copy of CodeEditorEngine
/// with the given fields replaced by the non-null parameter values.
@override @pragma('vm:prefer-inline') $Res call({Object? ready = null,Object? reading = null,Object? failed = null,Object? replacing = null,Object? replacementError = freezed,Object? searchResult = null,}) {
  return _then(_CodeEditorEngine(
ready: null == ready ? _self.ready : ready // ignore: cast_nullable_to_non_nullable
as bool,reading: null == reading ? _self.reading : reading // ignore: cast_nullable_to_non_nullable
as bool,failed: null == failed ? _self.failed : failed // ignore: cast_nullable_to_non_nullable
as bool,replacing: null == replacing ? _self.replacing : replacing // ignore: cast_nullable_to_non_nullable
as bool,replacementError: freezed == replacementError ? _self.replacementError : replacementError // ignore: cast_nullable_to_non_nullable
as String?,searchResult: null == searchResult ? _self.searchResult : searchResult // ignore: cast_nullable_to_non_nullable
as String,
  ));
}


}

/// @nodoc
mixin _$CodeEditorSnapshot {

 bool get busy; String? get error; bool get confirmDiscard; bool get hasSession; String? get title; bool get writable; bool get safe; bool get finished; bool get showDebugSource; bool get showLoginSource; bool get checkJavaScriptSyntax; int get generation; int get keyboardRows; bool get autoWrap; List<Map<String, String>> get assists; CodeEditorSearch get search; CodeEditorEngine get engine;
/// Create a copy of CodeEditorSnapshot
/// with the given fields replaced by the non-null parameter values.
@JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
$CodeEditorSnapshotCopyWith<CodeEditorSnapshot> get copyWith => _$CodeEditorSnapshotCopyWithImpl<CodeEditorSnapshot>(this as CodeEditorSnapshot, _$identity);



@override
bool operator ==(Object other) {
  final _this = this as CodeEditorSnapshot;
  return identical(this, other) || (other.runtimeType == runtimeType&&other is CodeEditorSnapshot&&(identical(other.busy, _this.busy) || other.busy == _this.busy)&&(identical(other.error, _this.error) || other.error == _this.error)&&(identical(other.confirmDiscard, _this.confirmDiscard) || other.confirmDiscard == _this.confirmDiscard)&&(identical(other.hasSession, _this.hasSession) || other.hasSession == _this.hasSession)&&(identical(other.title, _this.title) || other.title == _this.title)&&(identical(other.writable, _this.writable) || other.writable == _this.writable)&&(identical(other.safe, _this.safe) || other.safe == _this.safe)&&(identical(other.finished, _this.finished) || other.finished == _this.finished)&&(identical(other.showDebugSource, _this.showDebugSource) || other.showDebugSource == _this.showDebugSource)&&(identical(other.showLoginSource, _this.showLoginSource) || other.showLoginSource == _this.showLoginSource)&&(identical(other.checkJavaScriptSyntax, _this.checkJavaScriptSyntax) || other.checkJavaScriptSyntax == _this.checkJavaScriptSyntax)&&(identical(other.generation, _this.generation) || other.generation == _this.generation)&&(identical(other.keyboardRows, _this.keyboardRows) || other.keyboardRows == _this.keyboardRows)&&(identical(other.autoWrap, _this.autoWrap) || other.autoWrap == _this.autoWrap)&&const DeepCollectionEquality().equals(other.assists, _this.assists)&&(identical(other.search, _this.search) || other.search == _this.search)&&(identical(other.engine, _this.engine) || other.engine == _this.engine));
}


@override
int get hashCode {
  final _this = this as CodeEditorSnapshot;
  return Object.hash(runtimeType,_this.busy,_this.error,_this.confirmDiscard,_this.hasSession,_this.title,_this.writable,_this.safe,_this.finished,_this.showDebugSource,_this.showLoginSource,_this.checkJavaScriptSyntax,_this.generation,_this.keyboardRows,_this.autoWrap,const DeepCollectionEquality().hash(_this.assists),_this.search,_this.engine);
}

@override
String toString() {
  final _this = this as CodeEditorSnapshot;
  return 'CodeEditorSnapshot(busy: ${_this.busy}, error: ${_this.error}, confirmDiscard: ${_this.confirmDiscard}, hasSession: ${_this.hasSession}, title: ${_this.title}, writable: ${_this.writable}, safe: ${_this.safe}, finished: ${_this.finished}, showDebugSource: ${_this.showDebugSource}, showLoginSource: ${_this.showLoginSource}, checkJavaScriptSyntax: ${_this.checkJavaScriptSyntax}, generation: ${_this.generation}, keyboardRows: ${_this.keyboardRows}, autoWrap: ${_this.autoWrap}, assists: ${_this.assists}, search: ${_this.search}, engine: ${_this.engine})';
}


}

/// @nodoc
abstract mixin class $CodeEditorSnapshotCopyWith<$Res>  {
  factory $CodeEditorSnapshotCopyWith(CodeEditorSnapshot value, $Res Function(CodeEditorSnapshot) _then) = _$CodeEditorSnapshotCopyWithImpl;
@useResult
$Res call({
 bool busy, String? error, bool confirmDiscard, bool hasSession, String? title, bool writable, bool safe, bool finished, bool showDebugSource, bool showLoginSource, bool checkJavaScriptSyntax, int generation, int keyboardRows, bool autoWrap, List<Map<String, String>> assists, CodeEditorSearch search, CodeEditorEngine engine
});


$CodeEditorSearchCopyWith<$Res> get search;$CodeEditorEngineCopyWith<$Res> get engine;

}
/// @nodoc
class _$CodeEditorSnapshotCopyWithImpl<$Res>
    implements $CodeEditorSnapshotCopyWith<$Res> {
  _$CodeEditorSnapshotCopyWithImpl(this._self, this._then);

  final CodeEditorSnapshot _self;
  final $Res Function(CodeEditorSnapshot) _then;

/// Create a copy of CodeEditorSnapshot
/// with the given fields replaced by the non-null parameter values.
@pragma('vm:prefer-inline') @override $Res call({Object? busy = null,Object? error = freezed,Object? confirmDiscard = null,Object? hasSession = null,Object? title = freezed,Object? writable = null,Object? safe = null,Object? finished = null,Object? showDebugSource = null,Object? showLoginSource = null,Object? checkJavaScriptSyntax = null,Object? generation = null,Object? keyboardRows = null,Object? autoWrap = null,Object? assists = null,Object? search = null,Object? engine = null,}) {
  return _then(CodeEditorSnapshot(
busy: null == busy ? _self.busy : busy // ignore: cast_nullable_to_non_nullable
as bool,error: freezed == error ? _self.error : error // ignore: cast_nullable_to_non_nullable
as String?,confirmDiscard: null == confirmDiscard ? _self.confirmDiscard : confirmDiscard // ignore: cast_nullable_to_non_nullable
as bool,hasSession: null == hasSession ? _self.hasSession : hasSession // ignore: cast_nullable_to_non_nullable
as bool,title: freezed == title ? _self.title : title // ignore: cast_nullable_to_non_nullable
as String?,writable: null == writable ? _self.writable : writable // ignore: cast_nullable_to_non_nullable
as bool,safe: null == safe ? _self.safe : safe // ignore: cast_nullable_to_non_nullable
as bool,finished: null == finished ? _self.finished : finished // ignore: cast_nullable_to_non_nullable
as bool,showDebugSource: null == showDebugSource ? _self.showDebugSource : showDebugSource // ignore: cast_nullable_to_non_nullable
as bool,showLoginSource: null == showLoginSource ? _self.showLoginSource : showLoginSource // ignore: cast_nullable_to_non_nullable
as bool,checkJavaScriptSyntax: null == checkJavaScriptSyntax ? _self.checkJavaScriptSyntax : checkJavaScriptSyntax // ignore: cast_nullable_to_non_nullable
as bool,generation: null == generation ? _self.generation : generation // ignore: cast_nullable_to_non_nullable
as int,keyboardRows: null == keyboardRows ? _self.keyboardRows : keyboardRows // ignore: cast_nullable_to_non_nullable
as int,autoWrap: null == autoWrap ? _self.autoWrap : autoWrap // ignore: cast_nullable_to_non_nullable
as bool,assists: null == assists ? _self.assists : assists // ignore: cast_nullable_to_non_nullable
as List<Map<String, String>>,search: null == search ? _self.search : search // ignore: cast_nullable_to_non_nullable
as CodeEditorSearch,engine: null == engine ? _self.engine : engine // ignore: cast_nullable_to_non_nullable
as CodeEditorEngine,
  ));
}
/// Create a copy of CodeEditorSnapshot
/// with the given fields replaced by the non-null parameter values.
@override
@pragma('vm:prefer-inline')
$CodeEditorSearchCopyWith<$Res> get search {

  return $CodeEditorSearchCopyWith<$Res>(_self.search, (value) {
    return _then(_self.copyWith(search: value));
  });
}/// Create a copy of CodeEditorSnapshot
/// with the given fields replaced by the non-null parameter values.
@override
@pragma('vm:prefer-inline')
$CodeEditorEngineCopyWith<$Res> get engine {

  return $CodeEditorEngineCopyWith<$Res>(_self.engine, (value) {
    return _then(_self.copyWith(engine: value));
  });
}
}


/// Adds pattern-matching-related methods to [CodeEditorSnapshot].
extension CodeEditorSnapshotPatterns on CodeEditorSnapshot {
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

@optionalTypeArgs TResult maybeMap<TResult extends Object?>(TResult Function( _CodeEditorSnapshot value)?  $default,{required TResult orElse(),}){
final _that = this;
switch (_that) {
case _CodeEditorSnapshot() when $default != null:
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

@optionalTypeArgs TResult map<TResult extends Object?>(TResult Function( _CodeEditorSnapshot value)  $default,){
final _that = this;
switch (_that) {
case _CodeEditorSnapshot():
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

@optionalTypeArgs TResult? mapOrNull<TResult extends Object?>(TResult? Function( _CodeEditorSnapshot value)?  $default,){
final _that = this;
switch (_that) {
case _CodeEditorSnapshot() when $default != null:
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

@optionalTypeArgs TResult maybeWhen<TResult extends Object?>(TResult Function( bool busy,  String? error,  bool confirmDiscard,  bool hasSession,  String? title,  bool writable,  bool safe,  bool finished,  bool showDebugSource,  bool showLoginSource,  bool checkJavaScriptSyntax,  int generation,  int keyboardRows,  bool autoWrap,  List<Map<String, String>> assists,  CodeEditorSearch search,  CodeEditorEngine engine)?  $default,{required TResult orElse(),}) {final _that = this;
switch (_that) {
case _CodeEditorSnapshot() when $default != null:
return $default(_that.busy,_that.error,_that.confirmDiscard,_that.hasSession,_that.title,_that.writable,_that.safe,_that.finished,_that.showDebugSource,_that.showLoginSource,_that.checkJavaScriptSyntax,_that.generation,_that.keyboardRows,_that.autoWrap,_that.assists,_that.search,_that.engine);case _:
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

@optionalTypeArgs TResult when<TResult extends Object?>(TResult Function( bool busy,  String? error,  bool confirmDiscard,  bool hasSession,  String? title,  bool writable,  bool safe,  bool finished,  bool showDebugSource,  bool showLoginSource,  bool checkJavaScriptSyntax,  int generation,  int keyboardRows,  bool autoWrap,  List<Map<String, String>> assists,  CodeEditorSearch search,  CodeEditorEngine engine)  $default,) {final _that = this;
switch (_that) {
case _CodeEditorSnapshot():
return $default(_that.busy,_that.error,_that.confirmDiscard,_that.hasSession,_that.title,_that.writable,_that.safe,_that.finished,_that.showDebugSource,_that.showLoginSource,_that.checkJavaScriptSyntax,_that.generation,_that.keyboardRows,_that.autoWrap,_that.assists,_that.search,_that.engine);case _:
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

@optionalTypeArgs TResult? whenOrNull<TResult extends Object?>(TResult? Function( bool busy,  String? error,  bool confirmDiscard,  bool hasSession,  String? title,  bool writable,  bool safe,  bool finished,  bool showDebugSource,  bool showLoginSource,  bool checkJavaScriptSyntax,  int generation,  int keyboardRows,  bool autoWrap,  List<Map<String, String>> assists,  CodeEditorSearch search,  CodeEditorEngine engine)?  $default,) {final _that = this;
switch (_that) {
case _CodeEditorSnapshot() when $default != null:
return $default(_that.busy,_that.error,_that.confirmDiscard,_that.hasSession,_that.title,_that.writable,_that.safe,_that.finished,_that.showDebugSource,_that.showLoginSource,_that.checkJavaScriptSyntax,_that.generation,_that.keyboardRows,_that.autoWrap,_that.assists,_that.search,_that.engine);case _:
  return null;

}
}

}

/// @nodoc


class _CodeEditorSnapshot extends CodeEditorSnapshot {
  const _CodeEditorSnapshot({this.busy = false, this.error, this.confirmDiscard = false, this.hasSession = false, this.title, this.writable = false, this.safe = false, this.finished = false, this.showDebugSource = false, this.showLoginSource = false, this.checkJavaScriptSyntax = false, this.generation = 0, this.keyboardRows = 1, this.autoWrap = false,  List<Map<String, String>> assists = const [], this.search = const CodeEditorSearch(), this.engine = const CodeEditorEngine()}): _assists = assists,super._();


@override@JsonKey() final  bool busy;
@override final  String? error;
@override@JsonKey() final  bool confirmDiscard;
@override@JsonKey() final  bool hasSession;
@override final  String? title;
@override@JsonKey() final  bool writable;
@override@JsonKey() final  bool safe;
@override@JsonKey() final  bool finished;
@override@JsonKey() final  bool showDebugSource;
@override@JsonKey() final  bool showLoginSource;
@override@JsonKey() final  bool checkJavaScriptSyntax;
@override@JsonKey() final  int generation;
@override@JsonKey() final  int keyboardRows;
@override@JsonKey() final  bool autoWrap;
 final  List<Map<String, String>> _assists;
@override@JsonKey() List<Map<String, String>> get assists {
  if (_assists is EqualUnmodifiableListView) return _assists;
  // ignore: implicit_dynamic_type
  return EqualUnmodifiableListView(_assists);
}

@override@JsonKey() final  CodeEditorSearch search;
@override@JsonKey() final  CodeEditorEngine engine;

/// Create a copy of CodeEditorSnapshot
/// with the given fields replaced by the non-null parameter values.
@override @JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
_$CodeEditorSnapshotCopyWith<_CodeEditorSnapshot> get copyWith => __$CodeEditorSnapshotCopyWithImpl<_CodeEditorSnapshot>(this, _$identity);



@override
bool operator ==(Object other) {
    return identical(this, other) || (other.runtimeType == runtimeType&&other is _CodeEditorSnapshot&&(identical(other.busy, busy) || other.busy == busy)&&(identical(other.error, error) || other.error == error)&&(identical(other.confirmDiscard, confirmDiscard) || other.confirmDiscard == confirmDiscard)&&(identical(other.hasSession, hasSession) || other.hasSession == hasSession)&&(identical(other.title, title) || other.title == title)&&(identical(other.writable, writable) || other.writable == writable)&&(identical(other.safe, safe) || other.safe == safe)&&(identical(other.finished, finished) || other.finished == finished)&&(identical(other.showDebugSource, showDebugSource) || other.showDebugSource == showDebugSource)&&(identical(other.showLoginSource, showLoginSource) || other.showLoginSource == showLoginSource)&&(identical(other.checkJavaScriptSyntax, checkJavaScriptSyntax) || other.checkJavaScriptSyntax == checkJavaScriptSyntax)&&(identical(other.generation, generation) || other.generation == generation)&&(identical(other.keyboardRows, keyboardRows) || other.keyboardRows == keyboardRows)&&(identical(other.autoWrap, autoWrap) || other.autoWrap == autoWrap)&&const DeepCollectionEquality().equals(other.assists, _assists)&&(identical(other.search, search) || other.search == search)&&(identical(other.engine, engine) || other.engine == engine));
}


@override
int get hashCode {
    return Object.hash(runtimeType,busy,error,confirmDiscard,hasSession,title,writable,safe,finished,showDebugSource,showLoginSource,checkJavaScriptSyntax,generation,keyboardRows,autoWrap,const DeepCollectionEquality().hash(_assists),search,engine);
}

@override
String toString() {
    return 'CodeEditorSnapshot(busy: $busy, error: $error, confirmDiscard: $confirmDiscard, hasSession: $hasSession, title: $title, writable: $writable, safe: $safe, finished: $finished, showDebugSource: $showDebugSource, showLoginSource: $showLoginSource, checkJavaScriptSyntax: $checkJavaScriptSyntax, generation: $generation, keyboardRows: $keyboardRows, autoWrap: $autoWrap, assists: $assists, search: $search, engine: $engine)';
}


}

/// @nodoc
abstract mixin class _$CodeEditorSnapshotCopyWith<$Res> implements $CodeEditorSnapshotCopyWith<$Res> {
  factory _$CodeEditorSnapshotCopyWith(_CodeEditorSnapshot value, $Res Function(_CodeEditorSnapshot) _then) = __$CodeEditorSnapshotCopyWithImpl;
@override @useResult
$Res call({
 bool busy, String? error, bool confirmDiscard, bool hasSession, String? title, bool writable, bool safe, bool finished, bool showDebugSource, bool showLoginSource, bool checkJavaScriptSyntax, int generation, int keyboardRows, bool autoWrap, List<Map<String, String>> assists, CodeEditorSearch search, CodeEditorEngine engine
});


@override $CodeEditorSearchCopyWith<$Res> get search;@override $CodeEditorEngineCopyWith<$Res> get engine;

}
/// @nodoc
class __$CodeEditorSnapshotCopyWithImpl<$Res>
    implements _$CodeEditorSnapshotCopyWith<$Res> {
  __$CodeEditorSnapshotCopyWithImpl(this._self, this._then);

  final _CodeEditorSnapshot _self;
  final $Res Function(_CodeEditorSnapshot) _then;

/// Create a copy of CodeEditorSnapshot
/// with the given fields replaced by the non-null parameter values.
@override @pragma('vm:prefer-inline') $Res call({Object? busy = null,Object? error = freezed,Object? confirmDiscard = null,Object? hasSession = null,Object? title = freezed,Object? writable = null,Object? safe = null,Object? finished = null,Object? showDebugSource = null,Object? showLoginSource = null,Object? checkJavaScriptSyntax = null,Object? generation = null,Object? keyboardRows = null,Object? autoWrap = null,Object? assists = null,Object? search = null,Object? engine = null,}) {
  return _then(_CodeEditorSnapshot(
busy: null == busy ? _self.busy : busy // ignore: cast_nullable_to_non_nullable
as bool,error: freezed == error ? _self.error : error // ignore: cast_nullable_to_non_nullable
as String?,confirmDiscard: null == confirmDiscard ? _self.confirmDiscard : confirmDiscard // ignore: cast_nullable_to_non_nullable
as bool,hasSession: null == hasSession ? _self.hasSession : hasSession // ignore: cast_nullable_to_non_nullable
as bool,title: freezed == title ? _self.title : title // ignore: cast_nullable_to_non_nullable
as String?,writable: null == writable ? _self.writable : writable // ignore: cast_nullable_to_non_nullable
as bool,safe: null == safe ? _self.safe : safe // ignore: cast_nullable_to_non_nullable
as bool,finished: null == finished ? _self.finished : finished // ignore: cast_nullable_to_non_nullable
as bool,showDebugSource: null == showDebugSource ? _self.showDebugSource : showDebugSource // ignore: cast_nullable_to_non_nullable
as bool,showLoginSource: null == showLoginSource ? _self.showLoginSource : showLoginSource // ignore: cast_nullable_to_non_nullable
as bool,checkJavaScriptSyntax: null == checkJavaScriptSyntax ? _self.checkJavaScriptSyntax : checkJavaScriptSyntax // ignore: cast_nullable_to_non_nullable
as bool,generation: null == generation ? _self.generation : generation // ignore: cast_nullable_to_non_nullable
as int,keyboardRows: null == keyboardRows ? _self.keyboardRows : keyboardRows // ignore: cast_nullable_to_non_nullable
as int,autoWrap: null == autoWrap ? _self.autoWrap : autoWrap // ignore: cast_nullable_to_non_nullable
as bool,assists: null == assists ? _self._assists : assists // ignore: cast_nullable_to_non_nullable
as List<Map<String, String>>,search: null == search ? _self.search : search // ignore: cast_nullable_to_non_nullable
as CodeEditorSearch,engine: null == engine ? _self.engine : engine // ignore: cast_nullable_to_non_nullable
as CodeEditorEngine,
  ));
}

/// Create a copy of CodeEditorSnapshot
/// with the given fields replaced by the non-null parameter values.
@override
@pragma('vm:prefer-inline')
$CodeEditorSearchCopyWith<$Res> get search {

  return $CodeEditorSearchCopyWith<$Res>(_self.search, (value) {
    return _then(_self.copyWith(search: value));
  });
}/// Create a copy of CodeEditorSnapshot
/// with the given fields replaced by the non-null parameter values.
@override
@pragma('vm:prefer-inline')
$CodeEditorEngineCopyWith<$Res> get engine {

  return $CodeEditorEngineCopyWith<$Res>(_self.engine, (value) {
    return _then(_self.copyWith(engine: value));
  });
}
}

// dart format on
