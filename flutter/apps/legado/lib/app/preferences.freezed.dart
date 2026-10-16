// GENERATED CODE - DO NOT MODIFY BY HAND
// coverage:ignore-file
// ignore_for_file: type=lint, type=warning, deprecated_member_use, deprecated_member_use_from_same_package
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'preferences.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// dart format off
T _$identity<T>(T value) => value;
/// @nodoc
mixin _$AppPreferences {

 String get localeTag; String get themeTag;
/// Create a copy of AppPreferences
/// with the given fields replaced by the non-null parameter values.
@JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
$AppPreferencesCopyWith<AppPreferences> get copyWith => _$AppPreferencesCopyWithImpl<AppPreferences>(this as AppPreferences, _$identity);



@override
bool operator ==(Object other) {
  final _this = this as AppPreferences;
  return identical(this, other) || (other.runtimeType == runtimeType&&other is AppPreferences&&(identical(other.localeTag, _this.localeTag) || other.localeTag == _this.localeTag)&&(identical(other.themeTag, _this.themeTag) || other.themeTag == _this.themeTag));
}


@override
int get hashCode {
  final _this = this as AppPreferences;
  return Object.hash(runtimeType,_this.localeTag,_this.themeTag);
}

@override
String toString() {
  final _this = this as AppPreferences;
  return 'AppPreferences(localeTag: ${_this.localeTag}, themeTag: ${_this.themeTag})';
}


}

/// @nodoc
abstract mixin class $AppPreferencesCopyWith<$Res>  {
  factory $AppPreferencesCopyWith(AppPreferences value, $Res Function(AppPreferences) _then) = _$AppPreferencesCopyWithImpl;
@useResult
$Res call({
 String localeTag, String themeTag
});




}
/// @nodoc
class _$AppPreferencesCopyWithImpl<$Res>
    implements $AppPreferencesCopyWith<$Res> {
  _$AppPreferencesCopyWithImpl(this._self, this._then);

  final AppPreferences _self;
  final $Res Function(AppPreferences) _then;

/// Create a copy of AppPreferences
/// with the given fields replaced by the non-null parameter values.
@pragma('vm:prefer-inline') @override $Res call({Object? localeTag = null,Object? themeTag = null,}) {
  return _then(AppPreferences(
localeTag: null == localeTag ? _self.localeTag : localeTag // ignore: cast_nullable_to_non_nullable
as String,themeTag: null == themeTag ? _self.themeTag : themeTag // ignore: cast_nullable_to_non_nullable
as String,
  ));
}

}


/// Adds pattern-matching-related methods to [AppPreferences].
extension AppPreferencesPatterns on AppPreferences {
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

@optionalTypeArgs TResult maybeMap<TResult extends Object?>(TResult Function( _AppPreferences value)?  $default,{required TResult orElse(),}){
final _that = this;
switch (_that) {
case _AppPreferences() when $default != null:
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

@optionalTypeArgs TResult map<TResult extends Object?>(TResult Function( _AppPreferences value)  $default,){
final _that = this;
switch (_that) {
case _AppPreferences():
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

@optionalTypeArgs TResult? mapOrNull<TResult extends Object?>(TResult? Function( _AppPreferences value)?  $default,){
final _that = this;
switch (_that) {
case _AppPreferences() when $default != null:
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

@optionalTypeArgs TResult maybeWhen<TResult extends Object?>(TResult Function( String localeTag,  String themeTag)?  $default,{required TResult orElse(),}) {final _that = this;
switch (_that) {
case _AppPreferences() when $default != null:
return $default(_that.localeTag,_that.themeTag);case _:
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

@optionalTypeArgs TResult when<TResult extends Object?>(TResult Function( String localeTag,  String themeTag)  $default,) {final _that = this;
switch (_that) {
case _AppPreferences():
return $default(_that.localeTag,_that.themeTag);case _:
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

@optionalTypeArgs TResult? whenOrNull<TResult extends Object?>(TResult? Function( String localeTag,  String themeTag)?  $default,) {final _that = this;
switch (_that) {
case _AppPreferences() when $default != null:
return $default(_that.localeTag,_that.themeTag);case _:
  return null;

}
}

}

/// @nodoc


class _AppPreferences implements AppPreferences {
  const _AppPreferences({this.localeTag = '', this.themeTag = 'system'});


@override@JsonKey() final  String localeTag;
@override@JsonKey() final  String themeTag;

/// Create a copy of AppPreferences
/// with the given fields replaced by the non-null parameter values.
@override @JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
_$AppPreferencesCopyWith<_AppPreferences> get copyWith => __$AppPreferencesCopyWithImpl<_AppPreferences>(this, _$identity);



@override
bool operator ==(Object other) {
    return identical(this, other) || (other.runtimeType == runtimeType&&other is _AppPreferences&&(identical(other.localeTag, localeTag) || other.localeTag == localeTag)&&(identical(other.themeTag, themeTag) || other.themeTag == themeTag));
}


@override
int get hashCode {
    return Object.hash(runtimeType,localeTag,themeTag);
}

@override
String toString() {
    return 'AppPreferences(localeTag: $localeTag, themeTag: $themeTag)';
}


}

/// @nodoc
abstract mixin class _$AppPreferencesCopyWith<$Res> implements $AppPreferencesCopyWith<$Res> {
  factory _$AppPreferencesCopyWith(_AppPreferences value, $Res Function(_AppPreferences) _then) = __$AppPreferencesCopyWithImpl;
@override @useResult
$Res call({
 String localeTag, String themeTag
});




}
/// @nodoc
class __$AppPreferencesCopyWithImpl<$Res>
    implements _$AppPreferencesCopyWith<$Res> {
  __$AppPreferencesCopyWithImpl(this._self, this._then);

  final _AppPreferences _self;
  final $Res Function(_AppPreferences) _then;

/// Create a copy of AppPreferences
/// with the given fields replaced by the non-null parameter values.
@override @pragma('vm:prefer-inline') $Res call({Object? localeTag = null,Object? themeTag = null,}) {
  return _then(_AppPreferences(
localeTag: null == localeTag ? _self.localeTag : localeTag // ignore: cast_nullable_to_non_nullable
as String,themeTag: null == themeTag ? _self.themeTag : themeTag // ignore: cast_nullable_to_non_nullable
as String,
  ));
}


}

// dart format on
