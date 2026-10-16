// GENERATED CODE - DO NOT MODIFY BY HAND
// coverage:ignore-file
// ignore_for_file: type=lint, type=warning, deprecated_member_use, deprecated_member_use_from_same_package
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'network_book.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// dart format off
T _$identity<T>(T value) => value;
/// @nodoc
mixin _$NetworkBook {

 String get sourceId; Map<String, Object?> get metadata;
/// Create a copy of NetworkBook
/// with the given fields replaced by the non-null parameter values.
@JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
$NetworkBookCopyWith<NetworkBook> get copyWith => _$NetworkBookCopyWithImpl<NetworkBook>(this as NetworkBook, _$identity);



@override
bool operator ==(Object other) {
  final _this = this as NetworkBook;
  return identical(this, other) || (other.runtimeType == runtimeType&&other is NetworkBook&&(identical(other.sourceId, _this.sourceId) || other.sourceId == _this.sourceId)&&const DeepCollectionEquality().equals(other.metadata, _this.metadata));
}


@override
int get hashCode {
  final _this = this as NetworkBook;
  return Object.hash(runtimeType,_this.sourceId,const DeepCollectionEquality().hash(_this.metadata));
}

@override
String toString() {
  final _this = this as NetworkBook;
  return 'NetworkBook(sourceId: ${_this.sourceId}, metadata: ${_this.metadata})';
}


}

/// @nodoc
abstract mixin class $NetworkBookCopyWith<$Res>  {
  factory $NetworkBookCopyWith(NetworkBook value, $Res Function(NetworkBook) _then) = _$NetworkBookCopyWithImpl;
@useResult
$Res call({
 String sourceId, Map<String, Object?> metadata
});




}
/// @nodoc
class _$NetworkBookCopyWithImpl<$Res>
    implements $NetworkBookCopyWith<$Res> {
  _$NetworkBookCopyWithImpl(this._self, this._then);

  final NetworkBook _self;
  final $Res Function(NetworkBook) _then;

/// Create a copy of NetworkBook
/// with the given fields replaced by the non-null parameter values.
@pragma('vm:prefer-inline') @override $Res call({Object? sourceId = null,Object? metadata = null,}) {
  return _then(NetworkBook(
sourceId: null == sourceId ? _self.sourceId : sourceId // ignore: cast_nullable_to_non_nullable
as String,metadata: null == metadata ? _self.metadata : metadata // ignore: cast_nullable_to_non_nullable
as Map<String, Object?>,
  ));
}

}


/// Adds pattern-matching-related methods to [NetworkBook].
extension NetworkBookPatterns on NetworkBook {
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

@optionalTypeArgs TResult maybeMap<TResult extends Object?>(TResult Function( _NetworkBook value)?  $default,{required TResult orElse(),}){
final _that = this;
switch (_that) {
case _NetworkBook() when $default != null:
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

@optionalTypeArgs TResult map<TResult extends Object?>(TResult Function( _NetworkBook value)  $default,){
final _that = this;
switch (_that) {
case _NetworkBook():
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

@optionalTypeArgs TResult? mapOrNull<TResult extends Object?>(TResult? Function( _NetworkBook value)?  $default,){
final _that = this;
switch (_that) {
case _NetworkBook() when $default != null:
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

@optionalTypeArgs TResult maybeWhen<TResult extends Object?>(TResult Function( String sourceId,  Map<String, Object?> metadata)?  $default,{required TResult orElse(),}) {final _that = this;
switch (_that) {
case _NetworkBook() when $default != null:
return $default(_that.sourceId,_that.metadata);case _:
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

@optionalTypeArgs TResult when<TResult extends Object?>(TResult Function( String sourceId,  Map<String, Object?> metadata)  $default,) {final _that = this;
switch (_that) {
case _NetworkBook():
return $default(_that.sourceId,_that.metadata);case _:
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

@optionalTypeArgs TResult? whenOrNull<TResult extends Object?>(TResult? Function( String sourceId,  Map<String, Object?> metadata)?  $default,) {final _that = this;
switch (_that) {
case _NetworkBook() when $default != null:
return $default(_that.sourceId,_that.metadata);case _:
  return null;

}
}

}

/// @nodoc


class _NetworkBook extends NetworkBook {
  const _NetworkBook({required this.sourceId, required  Map<String, Object?> metadata}): _metadata = metadata,super._();


@override final  String sourceId;
 final  Map<String, Object?> _metadata;
@override Map<String, Object?> get metadata {
  if (_metadata is EqualUnmodifiableMapView) return _metadata;
  // ignore: implicit_dynamic_type
  return EqualUnmodifiableMapView(_metadata);
}


/// Create a copy of NetworkBook
/// with the given fields replaced by the non-null parameter values.
@override @JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
_$NetworkBookCopyWith<_NetworkBook> get copyWith => __$NetworkBookCopyWithImpl<_NetworkBook>(this, _$identity);



@override
bool operator ==(Object other) {
    return identical(this, other) || (other.runtimeType == runtimeType&&other is _NetworkBook&&(identical(other.sourceId, sourceId) || other.sourceId == sourceId)&&const DeepCollectionEquality().equals(other.metadata, _metadata));
}


@override
int get hashCode {
    return Object.hash(runtimeType,sourceId,const DeepCollectionEquality().hash(_metadata));
}

@override
String toString() {
    return 'NetworkBook(sourceId: $sourceId, metadata: $metadata)';
}


}

/// @nodoc
abstract mixin class _$NetworkBookCopyWith<$Res> implements $NetworkBookCopyWith<$Res> {
  factory _$NetworkBookCopyWith(_NetworkBook value, $Res Function(_NetworkBook) _then) = __$NetworkBookCopyWithImpl;
@override @useResult
$Res call({
 String sourceId, Map<String, Object?> metadata
});




}
/// @nodoc
class __$NetworkBookCopyWithImpl<$Res>
    implements _$NetworkBookCopyWith<$Res> {
  __$NetworkBookCopyWithImpl(this._self, this._then);

  final _NetworkBook _self;
  final $Res Function(_NetworkBook) _then;

/// Create a copy of NetworkBook
/// with the given fields replaced by the non-null parameter values.
@override @pragma('vm:prefer-inline') $Res call({Object? sourceId = null,Object? metadata = null,}) {
  return _then(_NetworkBook(
sourceId: null == sourceId ? _self.sourceId : sourceId // ignore: cast_nullable_to_non_nullable
as String,metadata: null == metadata ? _self._metadata : metadata // ignore: cast_nullable_to_non_nullable
as Map<String, Object?>,
  ));
}


}

// dart format on
