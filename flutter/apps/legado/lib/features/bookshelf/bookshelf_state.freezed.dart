// GENERATED CODE - DO NOT MODIFY BY HAND
// coverage:ignore-file
// ignore_for_file: type=lint, type=warning, deprecated_member_use, deprecated_member_use_from_same_package
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'bookshelf_state.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// dart format off
T _$identity<T>(T value) => value;
/// @nodoc
mixin _$BookshelfViewState {

 List<LegacyBookGroup> get groups; int get groupId; BookshelfSort get sort;
/// Create a copy of BookshelfViewState
/// with the given fields replaced by the non-null parameter values.
@JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
$BookshelfViewStateCopyWith<BookshelfViewState> get copyWith => _$BookshelfViewStateCopyWithImpl<BookshelfViewState>(this as BookshelfViewState, _$identity);



@override
bool operator ==(Object other) {
  final _this = this as BookshelfViewState;
  return identical(this, other) || (other.runtimeType == runtimeType&&other is BookshelfViewState&&const DeepCollectionEquality().equals(other.groups, _this.groups)&&(identical(other.groupId, _this.groupId) || other.groupId == _this.groupId)&&(identical(other.sort, _this.sort) || other.sort == _this.sort));
}


@override
int get hashCode {
  final _this = this as BookshelfViewState;
  return Object.hash(runtimeType,const DeepCollectionEquality().hash(_this.groups),_this.groupId,_this.sort);
}

@override
String toString() {
  final _this = this as BookshelfViewState;
  return 'BookshelfViewState(groups: ${_this.groups}, groupId: ${_this.groupId}, sort: ${_this.sort})';
}


}

/// @nodoc
abstract mixin class $BookshelfViewStateCopyWith<$Res>  {
  factory $BookshelfViewStateCopyWith(BookshelfViewState value, $Res Function(BookshelfViewState) _then) = _$BookshelfViewStateCopyWithImpl;
@useResult
$Res call({
 List<LegacyBookGroup> groups, int groupId, BookshelfSort sort
});




}
/// @nodoc
class _$BookshelfViewStateCopyWithImpl<$Res>
    implements $BookshelfViewStateCopyWith<$Res> {
  _$BookshelfViewStateCopyWithImpl(this._self, this._then);

  final BookshelfViewState _self;
  final $Res Function(BookshelfViewState) _then;

/// Create a copy of BookshelfViewState
/// with the given fields replaced by the non-null parameter values.
@pragma('vm:prefer-inline') @override $Res call({Object? groups = null,Object? groupId = null,Object? sort = null,}) {
  return _then(BookshelfViewState(
groups: null == groups ? _self.groups : groups // ignore: cast_nullable_to_non_nullable
as List<LegacyBookGroup>,groupId: null == groupId ? _self.groupId : groupId // ignore: cast_nullable_to_non_nullable
as int,sort: null == sort ? _self.sort : sort // ignore: cast_nullable_to_non_nullable
as BookshelfSort,
  ));
}

}


/// Adds pattern-matching-related methods to [BookshelfViewState].
extension BookshelfViewStatePatterns on BookshelfViewState {
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

@optionalTypeArgs TResult maybeMap<TResult extends Object?>(TResult Function( _BookshelfViewState value)?  $default,{required TResult orElse(),}){
final _that = this;
switch (_that) {
case _BookshelfViewState() when $default != null:
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

@optionalTypeArgs TResult map<TResult extends Object?>(TResult Function( _BookshelfViewState value)  $default,){
final _that = this;
switch (_that) {
case _BookshelfViewState():
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

@optionalTypeArgs TResult? mapOrNull<TResult extends Object?>(TResult? Function( _BookshelfViewState value)?  $default,){
final _that = this;
switch (_that) {
case _BookshelfViewState() when $default != null:
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

@optionalTypeArgs TResult maybeWhen<TResult extends Object?>(TResult Function( List<LegacyBookGroup> groups,  int groupId,  BookshelfSort sort)?  $default,{required TResult orElse(),}) {final _that = this;
switch (_that) {
case _BookshelfViewState() when $default != null:
return $default(_that.groups,_that.groupId,_that.sort);case _:
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

@optionalTypeArgs TResult when<TResult extends Object?>(TResult Function( List<LegacyBookGroup> groups,  int groupId,  BookshelfSort sort)  $default,) {final _that = this;
switch (_that) {
case _BookshelfViewState():
return $default(_that.groups,_that.groupId,_that.sort);case _:
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

@optionalTypeArgs TResult? whenOrNull<TResult extends Object?>(TResult? Function( List<LegacyBookGroup> groups,  int groupId,  BookshelfSort sort)?  $default,) {final _that = this;
switch (_that) {
case _BookshelfViewState() when $default != null:
return $default(_that.groups,_that.groupId,_that.sort);case _:
  return null;

}
}

}

/// @nodoc


class _BookshelfViewState implements BookshelfViewState {
  const _BookshelfViewState({ List<LegacyBookGroup> groups = const <LegacyBookGroup>[], this.groupId = -1, this.sort = BookshelfSort.recentlyRead}): _groups = groups;


 final  List<LegacyBookGroup> _groups;
@override@JsonKey() List<LegacyBookGroup> get groups {
  if (_groups is EqualUnmodifiableListView) return _groups;
  // ignore: implicit_dynamic_type
  return EqualUnmodifiableListView(_groups);
}

@override@JsonKey() final  int groupId;
@override@JsonKey() final  BookshelfSort sort;

/// Create a copy of BookshelfViewState
/// with the given fields replaced by the non-null parameter values.
@override @JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
_$BookshelfViewStateCopyWith<_BookshelfViewState> get copyWith => __$BookshelfViewStateCopyWithImpl<_BookshelfViewState>(this, _$identity);



@override
bool operator ==(Object other) {
    return identical(this, other) || (other.runtimeType == runtimeType&&other is _BookshelfViewState&&const DeepCollectionEquality().equals(other.groups, _groups)&&(identical(other.groupId, groupId) || other.groupId == groupId)&&(identical(other.sort, sort) || other.sort == sort));
}


@override
int get hashCode {
    return Object.hash(runtimeType,const DeepCollectionEquality().hash(_groups),groupId,sort);
}

@override
String toString() {
    return 'BookshelfViewState(groups: $groups, groupId: $groupId, sort: $sort)';
}


}

/// @nodoc
abstract mixin class _$BookshelfViewStateCopyWith<$Res> implements $BookshelfViewStateCopyWith<$Res> {
  factory _$BookshelfViewStateCopyWith(_BookshelfViewState value, $Res Function(_BookshelfViewState) _then) = __$BookshelfViewStateCopyWithImpl;
@override @useResult
$Res call({
 List<LegacyBookGroup> groups, int groupId, BookshelfSort sort
});




}
/// @nodoc
class __$BookshelfViewStateCopyWithImpl<$Res>
    implements _$BookshelfViewStateCopyWith<$Res> {
  __$BookshelfViewStateCopyWithImpl(this._self, this._then);

  final _BookshelfViewState _self;
  final $Res Function(_BookshelfViewState) _then;

/// Create a copy of BookshelfViewState
/// with the given fields replaced by the non-null parameter values.
@override @pragma('vm:prefer-inline') $Res call({Object? groups = null,Object? groupId = null,Object? sort = null,}) {
  return _then(_BookshelfViewState(
groups: null == groups ? _self._groups : groups // ignore: cast_nullable_to_non_nullable
as List<LegacyBookGroup>,groupId: null == groupId ? _self.groupId : groupId // ignore: cast_nullable_to_non_nullable
as int,sort: null == sort ? _self.sort : sort // ignore: cast_nullable_to_non_nullable
as BookshelfSort,
  ));
}


}

// dart format on
