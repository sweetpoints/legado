// GENERATED CODE - DO NOT MODIFY BY HAND
// coverage:ignore-file
// ignore_for_file: type=lint, type=warning, deprecated_member_use, deprecated_member_use_from_same_package
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'reader_state.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// dart format off
T _$identity<T>(T value) => value;
/// @nodoc
mixin _$ReaderViewState {

 LegacyBookRecord? get book; List<ReaderChapter> get chapters; int get selected; int get characterOffset; String? get content; String? get error; String? get saveError; bool get loading; double get fontSize; String get theme;
/// Create a copy of ReaderViewState
/// with the given fields replaced by the non-null parameter values.
@JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
$ReaderViewStateCopyWith<ReaderViewState> get copyWith => _$ReaderViewStateCopyWithImpl<ReaderViewState>(this as ReaderViewState, _$identity);



@override
bool operator ==(Object other) {
  final _this = this as ReaderViewState;
  return identical(this, other) || (other.runtimeType == runtimeType&&other is ReaderViewState&&(identical(other.book, _this.book) || other.book == _this.book)&&const DeepCollectionEquality().equals(other.chapters, _this.chapters)&&(identical(other.selected, _this.selected) || other.selected == _this.selected)&&(identical(other.characterOffset, _this.characterOffset) || other.characterOffset == _this.characterOffset)&&(identical(other.content, _this.content) || other.content == _this.content)&&(identical(other.error, _this.error) || other.error == _this.error)&&(identical(other.saveError, _this.saveError) || other.saveError == _this.saveError)&&(identical(other.loading, _this.loading) || other.loading == _this.loading)&&(identical(other.fontSize, _this.fontSize) || other.fontSize == _this.fontSize)&&(identical(other.theme, _this.theme) || other.theme == _this.theme));
}


@override
int get hashCode {
  final _this = this as ReaderViewState;
  return Object.hash(runtimeType,_this.book,const DeepCollectionEquality().hash(_this.chapters),_this.selected,_this.characterOffset,_this.content,_this.error,_this.saveError,_this.loading,_this.fontSize,_this.theme);
}

@override
String toString() {
  final _this = this as ReaderViewState;
  return 'ReaderViewState(book: ${_this.book}, chapters: ${_this.chapters}, selected: ${_this.selected}, characterOffset: ${_this.characterOffset}, content: ${_this.content}, error: ${_this.error}, saveError: ${_this.saveError}, loading: ${_this.loading}, fontSize: ${_this.fontSize}, theme: ${_this.theme})';
}


}

/// @nodoc
abstract mixin class $ReaderViewStateCopyWith<$Res>  {
  factory $ReaderViewStateCopyWith(ReaderViewState value, $Res Function(ReaderViewState) _then) = _$ReaderViewStateCopyWithImpl;
@useResult
$Res call({
 LegacyBookRecord? book, List<ReaderChapter> chapters, int selected, int characterOffset, String? content, String? error, String? saveError, bool loading, double fontSize, String theme
});




}
/// @nodoc
class _$ReaderViewStateCopyWithImpl<$Res>
    implements $ReaderViewStateCopyWith<$Res> {
  _$ReaderViewStateCopyWithImpl(this._self, this._then);

  final ReaderViewState _self;
  final $Res Function(ReaderViewState) _then;

/// Create a copy of ReaderViewState
/// with the given fields replaced by the non-null parameter values.
@pragma('vm:prefer-inline') @override $Res call({Object? book = freezed,Object? chapters = null,Object? selected = null,Object? characterOffset = null,Object? content = freezed,Object? error = freezed,Object? saveError = freezed,Object? loading = null,Object? fontSize = null,Object? theme = null,}) {
  return _then(ReaderViewState(
book: freezed == book ? _self.book : book // ignore: cast_nullable_to_non_nullable
as LegacyBookRecord?,chapters: null == chapters ? _self.chapters : chapters // ignore: cast_nullable_to_non_nullable
as List<ReaderChapter>,selected: null == selected ? _self.selected : selected // ignore: cast_nullable_to_non_nullable
as int,characterOffset: null == characterOffset ? _self.characterOffset : characterOffset // ignore: cast_nullable_to_non_nullable
as int,content: freezed == content ? _self.content : content // ignore: cast_nullable_to_non_nullable
as String?,error: freezed == error ? _self.error : error // ignore: cast_nullable_to_non_nullable
as String?,saveError: freezed == saveError ? _self.saveError : saveError // ignore: cast_nullable_to_non_nullable
as String?,loading: null == loading ? _self.loading : loading // ignore: cast_nullable_to_non_nullable
as bool,fontSize: null == fontSize ? _self.fontSize : fontSize // ignore: cast_nullable_to_non_nullable
as double,theme: null == theme ? _self.theme : theme // ignore: cast_nullable_to_non_nullable
as String,
  ));
}

}


/// Adds pattern-matching-related methods to [ReaderViewState].
extension ReaderViewStatePatterns on ReaderViewState {
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

@optionalTypeArgs TResult maybeMap<TResult extends Object?>(TResult Function( _ReaderViewState value)?  $default,{required TResult orElse(),}){
final _that = this;
switch (_that) {
case _ReaderViewState() when $default != null:
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

@optionalTypeArgs TResult map<TResult extends Object?>(TResult Function( _ReaderViewState value)  $default,){
final _that = this;
switch (_that) {
case _ReaderViewState():
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

@optionalTypeArgs TResult? mapOrNull<TResult extends Object?>(TResult? Function( _ReaderViewState value)?  $default,){
final _that = this;
switch (_that) {
case _ReaderViewState() when $default != null:
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

@optionalTypeArgs TResult maybeWhen<TResult extends Object?>(TResult Function( LegacyBookRecord? book,  List<ReaderChapter> chapters,  int selected,  int characterOffset,  String? content,  String? error,  String? saveError,  bool loading,  double fontSize,  String theme)?  $default,{required TResult orElse(),}) {final _that = this;
switch (_that) {
case _ReaderViewState() when $default != null:
return $default(_that.book,_that.chapters,_that.selected,_that.characterOffset,_that.content,_that.error,_that.saveError,_that.loading,_that.fontSize,_that.theme);case _:
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

@optionalTypeArgs TResult when<TResult extends Object?>(TResult Function( LegacyBookRecord? book,  List<ReaderChapter> chapters,  int selected,  int characterOffset,  String? content,  String? error,  String? saveError,  bool loading,  double fontSize,  String theme)  $default,) {final _that = this;
switch (_that) {
case _ReaderViewState():
return $default(_that.book,_that.chapters,_that.selected,_that.characterOffset,_that.content,_that.error,_that.saveError,_that.loading,_that.fontSize,_that.theme);case _:
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

@optionalTypeArgs TResult? whenOrNull<TResult extends Object?>(TResult? Function( LegacyBookRecord? book,  List<ReaderChapter> chapters,  int selected,  int characterOffset,  String? content,  String? error,  String? saveError,  bool loading,  double fontSize,  String theme)?  $default,) {final _that = this;
switch (_that) {
case _ReaderViewState() when $default != null:
return $default(_that.book,_that.chapters,_that.selected,_that.characterOffset,_that.content,_that.error,_that.saveError,_that.loading,_that.fontSize,_that.theme);case _:
  return null;

}
}

}

/// @nodoc


class _ReaderViewState implements ReaderViewState {
  const _ReaderViewState({this.book,  List<ReaderChapter> chapters = const <ReaderChapter>[], this.selected = 0, this.characterOffset = 0, this.content, this.error, this.saveError, this.loading = true, this.fontSize = 20, this.theme = 'system'}): _chapters = chapters;
  

@override final  LegacyBookRecord? book;
 final  List<ReaderChapter> _chapters;
@override@JsonKey() List<ReaderChapter> get chapters {
  if (_chapters is EqualUnmodifiableListView) return _chapters;
  // ignore: implicit_dynamic_type
  return EqualUnmodifiableListView(_chapters);
}

@override@JsonKey() final  int selected;
@override@JsonKey() final  int characterOffset;
@override final  String? content;
@override final  String? error;
@override final  String? saveError;
@override@JsonKey() final  bool loading;
@override@JsonKey() final  double fontSize;
@override@JsonKey() final  String theme;

/// Create a copy of ReaderViewState
/// with the given fields replaced by the non-null parameter values.
@override @JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
_$ReaderViewStateCopyWith<_ReaderViewState> get copyWith => __$ReaderViewStateCopyWithImpl<_ReaderViewState>(this, _$identity);



@override
bool operator ==(Object other) {
    return identical(this, other) || (other.runtimeType == runtimeType&&other is _ReaderViewState&&(identical(other.book, book) || other.book == book)&&const DeepCollectionEquality().equals(other.chapters, _chapters)&&(identical(other.selected, selected) || other.selected == selected)&&(identical(other.characterOffset, characterOffset) || other.characterOffset == characterOffset)&&(identical(other.content, content) || other.content == content)&&(identical(other.error, error) || other.error == error)&&(identical(other.saveError, saveError) || other.saveError == saveError)&&(identical(other.loading, loading) || other.loading == loading)&&(identical(other.fontSize, fontSize) || other.fontSize == fontSize)&&(identical(other.theme, theme) || other.theme == theme));
}


@override
int get hashCode {
    return Object.hash(runtimeType,book,const DeepCollectionEquality().hash(_chapters),selected,characterOffset,content,error,saveError,loading,fontSize,theme);
}

@override
String toString() {
    return 'ReaderViewState(book: $book, chapters: $chapters, selected: $selected, characterOffset: $characterOffset, content: $content, error: $error, saveError: $saveError, loading: $loading, fontSize: $fontSize, theme: $theme)';
}


}

/// @nodoc
abstract mixin class _$ReaderViewStateCopyWith<$Res> implements $ReaderViewStateCopyWith<$Res> {
  factory _$ReaderViewStateCopyWith(_ReaderViewState value, $Res Function(_ReaderViewState) _then) = __$ReaderViewStateCopyWithImpl;
@override @useResult
$Res call({
 LegacyBookRecord? book, List<ReaderChapter> chapters, int selected, int characterOffset, String? content, String? error, String? saveError, bool loading, double fontSize, String theme
});




}
/// @nodoc
class __$ReaderViewStateCopyWithImpl<$Res>
    implements _$ReaderViewStateCopyWith<$Res> {
  __$ReaderViewStateCopyWithImpl(this._self, this._then);

  final _ReaderViewState _self;
  final $Res Function(_ReaderViewState) _then;

/// Create a copy of ReaderViewState
/// with the given fields replaced by the non-null parameter values.
@override @pragma('vm:prefer-inline') $Res call({Object? book = freezed,Object? chapters = null,Object? selected = null,Object? characterOffset = null,Object? content = freezed,Object? error = freezed,Object? saveError = freezed,Object? loading = null,Object? fontSize = null,Object? theme = null,}) {
  return _then(_ReaderViewState(
book: freezed == book ? _self.book : book // ignore: cast_nullable_to_non_nullable
as LegacyBookRecord?,chapters: null == chapters ? _self._chapters : chapters // ignore: cast_nullable_to_non_nullable
as List<ReaderChapter>,selected: null == selected ? _self.selected : selected // ignore: cast_nullable_to_non_nullable
as int,characterOffset: null == characterOffset ? _self.characterOffset : characterOffset // ignore: cast_nullable_to_non_nullable
as int,content: freezed == content ? _self.content : content // ignore: cast_nullable_to_non_nullable
as String?,error: freezed == error ? _self.error : error // ignore: cast_nullable_to_non_nullable
as String?,saveError: freezed == saveError ? _self.saveError : saveError // ignore: cast_nullable_to_non_nullable
as String?,loading: null == loading ? _self.loading : loading // ignore: cast_nullable_to_non_nullable
as bool,fontSize: null == fontSize ? _self.fontSize : fontSize // ignore: cast_nullable_to_non_nullable
as double,theme: null == theme ? _self.theme : theme // ignore: cast_nullable_to_non_nullable
as String,
  ));
}


}

// dart format on
