// GENERATED CODE - DO NOT MODIFY BY HAND
// coverage:ignore-file
// ignore_for_file: type=lint, type=warning, deprecated_member_use, deprecated_member_use_from_same_package
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'source_editor_state.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// dart format off
T _$identity<T>(T value) => value;
/// @nodoc
mixin _$SourceEditorField {

 String get key; String get value; String get label; bool get external; int get originalLength; int get selectionStart; int get selectionEnd;
/// Create a copy of SourceEditorField
/// with the given fields replaced by the non-null parameter values.
@JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
$SourceEditorFieldCopyWith<SourceEditorField> get copyWith => _$SourceEditorFieldCopyWithImpl<SourceEditorField>(this as SourceEditorField, _$identity);



@override
bool operator ==(Object other) {
  final _this = this as SourceEditorField;
  return identical(this, other) || (other.runtimeType == runtimeType&&other is SourceEditorField&&(identical(other.key, _this.key) || other.key == _this.key)&&(identical(other.value, _this.value) || other.value == _this.value)&&(identical(other.label, _this.label) || other.label == _this.label)&&(identical(other.external, _this.external) || other.external == _this.external)&&(identical(other.originalLength, _this.originalLength) || other.originalLength == _this.originalLength)&&(identical(other.selectionStart, _this.selectionStart) || other.selectionStart == _this.selectionStart)&&(identical(other.selectionEnd, _this.selectionEnd) || other.selectionEnd == _this.selectionEnd));
}


@override
int get hashCode {
  final _this = this as SourceEditorField;
  return Object.hash(runtimeType,_this.key,_this.value,_this.label,_this.external,_this.originalLength,_this.selectionStart,_this.selectionEnd);
}

@override
String toString() {
  final _this = this as SourceEditorField;
  return 'SourceEditorField(key: ${_this.key}, value: ${_this.value}, label: ${_this.label}, external: ${_this.external}, originalLength: ${_this.originalLength}, selectionStart: ${_this.selectionStart}, selectionEnd: ${_this.selectionEnd})';
}


}

/// @nodoc
abstract mixin class $SourceEditorFieldCopyWith<$Res>  {
  factory $SourceEditorFieldCopyWith(SourceEditorField value, $Res Function(SourceEditorField) _then) = _$SourceEditorFieldCopyWithImpl;
@useResult
$Res call({
 String key, String value, String label, bool external, int originalLength, int selectionStart, int selectionEnd
});




}
/// @nodoc
class _$SourceEditorFieldCopyWithImpl<$Res>
    implements $SourceEditorFieldCopyWith<$Res> {
  _$SourceEditorFieldCopyWithImpl(this._self, this._then);

  final SourceEditorField _self;
  final $Res Function(SourceEditorField) _then;

/// Create a copy of SourceEditorField
/// with the given fields replaced by the non-null parameter values.
@pragma('vm:prefer-inline') @override $Res call({Object? key = null,Object? value = null,Object? label = null,Object? external = null,Object? originalLength = null,Object? selectionStart = null,Object? selectionEnd = null,}) {
  return _then(SourceEditorField(
key: null == key ? _self.key : key // ignore: cast_nullable_to_non_nullable
as String,value: null == value ? _self.value : value // ignore: cast_nullable_to_non_nullable
as String,label: null == label ? _self.label : label // ignore: cast_nullable_to_non_nullable
as String,external: null == external ? _self.external : external // ignore: cast_nullable_to_non_nullable
as bool,originalLength: null == originalLength ? _self.originalLength : originalLength // ignore: cast_nullable_to_non_nullable
as int,selectionStart: null == selectionStart ? _self.selectionStart : selectionStart // ignore: cast_nullable_to_non_nullable
as int,selectionEnd: null == selectionEnd ? _self.selectionEnd : selectionEnd // ignore: cast_nullable_to_non_nullable
as int,
  ));
}

}


/// Adds pattern-matching-related methods to [SourceEditorField].
extension SourceEditorFieldPatterns on SourceEditorField {
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

@optionalTypeArgs TResult maybeMap<TResult extends Object?>(TResult Function( _SourceEditorField value)?  $default,{required TResult orElse(),}){
final _that = this;
switch (_that) {
case _SourceEditorField() when $default != null:
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

@optionalTypeArgs TResult map<TResult extends Object?>(TResult Function( _SourceEditorField value)  $default,){
final _that = this;
switch (_that) {
case _SourceEditorField():
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

@optionalTypeArgs TResult? mapOrNull<TResult extends Object?>(TResult? Function( _SourceEditorField value)?  $default,){
final _that = this;
switch (_that) {
case _SourceEditorField() when $default != null:
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

@optionalTypeArgs TResult maybeWhen<TResult extends Object?>(TResult Function( String key,  String value,  String label,  bool external,  int originalLength,  int selectionStart,  int selectionEnd)?  $default,{required TResult orElse(),}) {final _that = this;
switch (_that) {
case _SourceEditorField() when $default != null:
return $default(_that.key,_that.value,_that.label,_that.external,_that.originalLength,_that.selectionStart,_that.selectionEnd);case _:
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

@optionalTypeArgs TResult when<TResult extends Object?>(TResult Function( String key,  String value,  String label,  bool external,  int originalLength,  int selectionStart,  int selectionEnd)  $default,) {final _that = this;
switch (_that) {
case _SourceEditorField():
return $default(_that.key,_that.value,_that.label,_that.external,_that.originalLength,_that.selectionStart,_that.selectionEnd);case _:
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

@optionalTypeArgs TResult? whenOrNull<TResult extends Object?>(TResult? Function( String key,  String value,  String label,  bool external,  int originalLength,  int selectionStart,  int selectionEnd)?  $default,) {final _that = this;
switch (_that) {
case _SourceEditorField() when $default != null:
return $default(_that.key,_that.value,_that.label,_that.external,_that.originalLength,_that.selectionStart,_that.selectionEnd);case _:
  return null;

}
}

}

/// @nodoc


class _SourceEditorField extends SourceEditorField {
  const _SourceEditorField({required this.key, required this.value, required this.label, this.external = false, this.originalLength = 0, this.selectionStart = 0, this.selectionEnd = 0}): super._();


@override final  String key;
@override final  String value;
@override final  String label;
@override@JsonKey() final  bool external;
@override@JsonKey() final  int originalLength;
@override@JsonKey() final  int selectionStart;
@override@JsonKey() final  int selectionEnd;

/// Create a copy of SourceEditorField
/// with the given fields replaced by the non-null parameter values.
@override @JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
_$SourceEditorFieldCopyWith<_SourceEditorField> get copyWith => __$SourceEditorFieldCopyWithImpl<_SourceEditorField>(this, _$identity);



@override
bool operator ==(Object other) {
    return identical(this, other) || (other.runtimeType == runtimeType&&other is _SourceEditorField&&(identical(other.key, key) || other.key == key)&&(identical(other.value, value) || other.value == value)&&(identical(other.label, label) || other.label == label)&&(identical(other.external, external) || other.external == external)&&(identical(other.originalLength, originalLength) || other.originalLength == originalLength)&&(identical(other.selectionStart, selectionStart) || other.selectionStart == selectionStart)&&(identical(other.selectionEnd, selectionEnd) || other.selectionEnd == selectionEnd));
}


@override
int get hashCode {
    return Object.hash(runtimeType,key,value,label,external,originalLength,selectionStart,selectionEnd);
}

@override
String toString() {
    return 'SourceEditorField(key: $key, value: $value, label: $label, external: $external, originalLength: $originalLength, selectionStart: $selectionStart, selectionEnd: $selectionEnd)';
}


}

/// @nodoc
abstract mixin class _$SourceEditorFieldCopyWith<$Res> implements $SourceEditorFieldCopyWith<$Res> {
  factory _$SourceEditorFieldCopyWith(_SourceEditorField value, $Res Function(_SourceEditorField) _then) = __$SourceEditorFieldCopyWithImpl;
@override @useResult
$Res call({
 String key, String value, String label, bool external, int originalLength, int selectionStart, int selectionEnd
});




}
/// @nodoc
class __$SourceEditorFieldCopyWithImpl<$Res>
    implements _$SourceEditorFieldCopyWith<$Res> {
  __$SourceEditorFieldCopyWithImpl(this._self, this._then);

  final _SourceEditorField _self;
  final $Res Function(_SourceEditorField) _then;

/// Create a copy of SourceEditorField
/// with the given fields replaced by the non-null parameter values.
@override @pragma('vm:prefer-inline') $Res call({Object? key = null,Object? value = null,Object? label = null,Object? external = null,Object? originalLength = null,Object? selectionStart = null,Object? selectionEnd = null,}) {
  return _then(_SourceEditorField(
key: null == key ? _self.key : key // ignore: cast_nullable_to_non_nullable
as String,value: null == value ? _self.value : value // ignore: cast_nullable_to_non_nullable
as String,label: null == label ? _self.label : label // ignore: cast_nullable_to_non_nullable
as String,external: null == external ? _self.external : external // ignore: cast_nullable_to_non_nullable
as bool,originalLength: null == originalLength ? _self.originalLength : originalLength // ignore: cast_nullable_to_non_nullable
as int,selectionStart: null == selectionStart ? _self.selectionStart : selectionStart // ignore: cast_nullable_to_non_nullable
as int,selectionEnd: null == selectionEnd ? _self.selectionEnd : selectionEnd // ignore: cast_nullable_to_non_nullable
as int,
  ));
}


}

/// @nodoc
mixin _$SourceEditorOptions {

 bool get enabled; bool get enabledExplore; bool get enabledCookieJar; bool get enabledReview; int get type; bool get eventListener; bool get customButton;
/// Create a copy of SourceEditorOptions
/// with the given fields replaced by the non-null parameter values.
@JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
$SourceEditorOptionsCopyWith<SourceEditorOptions> get copyWith => _$SourceEditorOptionsCopyWithImpl<SourceEditorOptions>(this as SourceEditorOptions, _$identity);



@override
bool operator ==(Object other) {
  final _this = this as SourceEditorOptions;
  return identical(this, other) || (other.runtimeType == runtimeType&&other is SourceEditorOptions&&(identical(other.enabled, _this.enabled) || other.enabled == _this.enabled)&&(identical(other.enabledExplore, _this.enabledExplore) || other.enabledExplore == _this.enabledExplore)&&(identical(other.enabledCookieJar, _this.enabledCookieJar) || other.enabledCookieJar == _this.enabledCookieJar)&&(identical(other.enabledReview, _this.enabledReview) || other.enabledReview == _this.enabledReview)&&(identical(other.type, _this.type) || other.type == _this.type)&&(identical(other.eventListener, _this.eventListener) || other.eventListener == _this.eventListener)&&(identical(other.customButton, _this.customButton) || other.customButton == _this.customButton));
}


@override
int get hashCode {
  final _this = this as SourceEditorOptions;
  return Object.hash(runtimeType,_this.enabled,_this.enabledExplore,_this.enabledCookieJar,_this.enabledReview,_this.type,_this.eventListener,_this.customButton);
}

@override
String toString() {
  final _this = this as SourceEditorOptions;
  return 'SourceEditorOptions(enabled: ${_this.enabled}, enabledExplore: ${_this.enabledExplore}, enabledCookieJar: ${_this.enabledCookieJar}, enabledReview: ${_this.enabledReview}, type: ${_this.type}, eventListener: ${_this.eventListener}, customButton: ${_this.customButton})';
}


}

/// @nodoc
abstract mixin class $SourceEditorOptionsCopyWith<$Res>  {
  factory $SourceEditorOptionsCopyWith(SourceEditorOptions value, $Res Function(SourceEditorOptions) _then) = _$SourceEditorOptionsCopyWithImpl;
@useResult
$Res call({
 bool enabled, bool enabledExplore, bool enabledCookieJar, bool enabledReview, int type, bool eventListener, bool customButton
});




}
/// @nodoc
class _$SourceEditorOptionsCopyWithImpl<$Res>
    implements $SourceEditorOptionsCopyWith<$Res> {
  _$SourceEditorOptionsCopyWithImpl(this._self, this._then);

  final SourceEditorOptions _self;
  final $Res Function(SourceEditorOptions) _then;

/// Create a copy of SourceEditorOptions
/// with the given fields replaced by the non-null parameter values.
@pragma('vm:prefer-inline') @override $Res call({Object? enabled = null,Object? enabledExplore = null,Object? enabledCookieJar = null,Object? enabledReview = null,Object? type = null,Object? eventListener = null,Object? customButton = null,}) {
  return _then(SourceEditorOptions(
enabled: null == enabled ? _self.enabled : enabled // ignore: cast_nullable_to_non_nullable
as bool,enabledExplore: null == enabledExplore ? _self.enabledExplore : enabledExplore // ignore: cast_nullable_to_non_nullable
as bool,enabledCookieJar: null == enabledCookieJar ? _self.enabledCookieJar : enabledCookieJar // ignore: cast_nullable_to_non_nullable
as bool,enabledReview: null == enabledReview ? _self.enabledReview : enabledReview // ignore: cast_nullable_to_non_nullable
as bool,type: null == type ? _self.type : type // ignore: cast_nullable_to_non_nullable
as int,eventListener: null == eventListener ? _self.eventListener : eventListener // ignore: cast_nullable_to_non_nullable
as bool,customButton: null == customButton ? _self.customButton : customButton // ignore: cast_nullable_to_non_nullable
as bool,
  ));
}

}


/// Adds pattern-matching-related methods to [SourceEditorOptions].
extension SourceEditorOptionsPatterns on SourceEditorOptions {
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

@optionalTypeArgs TResult maybeMap<TResult extends Object?>(TResult Function( _SourceEditorOptions value)?  $default,{required TResult orElse(),}){
final _that = this;
switch (_that) {
case _SourceEditorOptions() when $default != null:
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

@optionalTypeArgs TResult map<TResult extends Object?>(TResult Function( _SourceEditorOptions value)  $default,){
final _that = this;
switch (_that) {
case _SourceEditorOptions():
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

@optionalTypeArgs TResult? mapOrNull<TResult extends Object?>(TResult? Function( _SourceEditorOptions value)?  $default,){
final _that = this;
switch (_that) {
case _SourceEditorOptions() when $default != null:
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

@optionalTypeArgs TResult maybeWhen<TResult extends Object?>(TResult Function( bool enabled,  bool enabledExplore,  bool enabledCookieJar,  bool enabledReview,  int type,  bool eventListener,  bool customButton)?  $default,{required TResult orElse(),}) {final _that = this;
switch (_that) {
case _SourceEditorOptions() when $default != null:
return $default(_that.enabled,_that.enabledExplore,_that.enabledCookieJar,_that.enabledReview,_that.type,_that.eventListener,_that.customButton);case _:
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

@optionalTypeArgs TResult when<TResult extends Object?>(TResult Function( bool enabled,  bool enabledExplore,  bool enabledCookieJar,  bool enabledReview,  int type,  bool eventListener,  bool customButton)  $default,) {final _that = this;
switch (_that) {
case _SourceEditorOptions():
return $default(_that.enabled,_that.enabledExplore,_that.enabledCookieJar,_that.enabledReview,_that.type,_that.eventListener,_that.customButton);case _:
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

@optionalTypeArgs TResult? whenOrNull<TResult extends Object?>(TResult? Function( bool enabled,  bool enabledExplore,  bool enabledCookieJar,  bool enabledReview,  int type,  bool eventListener,  bool customButton)?  $default,) {final _that = this;
switch (_that) {
case _SourceEditorOptions() when $default != null:
return $default(_that.enabled,_that.enabledExplore,_that.enabledCookieJar,_that.enabledReview,_that.type,_that.eventListener,_that.customButton);case _:
  return null;

}
}

}

/// @nodoc


class _SourceEditorOptions extends SourceEditorOptions {
  const _SourceEditorOptions({required this.enabled, required this.enabledExplore, required this.enabledCookieJar, required this.enabledReview, required this.type, required this.eventListener, required this.customButton}): super._();


@override final  bool enabled;
@override final  bool enabledExplore;
@override final  bool enabledCookieJar;
@override final  bool enabledReview;
@override final  int type;
@override final  bool eventListener;
@override final  bool customButton;

/// Create a copy of SourceEditorOptions
/// with the given fields replaced by the non-null parameter values.
@override @JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
_$SourceEditorOptionsCopyWith<_SourceEditorOptions> get copyWith => __$SourceEditorOptionsCopyWithImpl<_SourceEditorOptions>(this, _$identity);



@override
bool operator ==(Object other) {
    return identical(this, other) || (other.runtimeType == runtimeType&&other is _SourceEditorOptions&&(identical(other.enabled, enabled) || other.enabled == enabled)&&(identical(other.enabledExplore, enabledExplore) || other.enabledExplore == enabledExplore)&&(identical(other.enabledCookieJar, enabledCookieJar) || other.enabledCookieJar == enabledCookieJar)&&(identical(other.enabledReview, enabledReview) || other.enabledReview == enabledReview)&&(identical(other.type, type) || other.type == type)&&(identical(other.eventListener, eventListener) || other.eventListener == eventListener)&&(identical(other.customButton, customButton) || other.customButton == customButton));
}


@override
int get hashCode {
    return Object.hash(runtimeType,enabled,enabledExplore,enabledCookieJar,enabledReview,type,eventListener,customButton);
}

@override
String toString() {
    return 'SourceEditorOptions(enabled: $enabled, enabledExplore: $enabledExplore, enabledCookieJar: $enabledCookieJar, enabledReview: $enabledReview, type: $type, eventListener: $eventListener, customButton: $customButton)';
}


}

/// @nodoc
abstract mixin class _$SourceEditorOptionsCopyWith<$Res> implements $SourceEditorOptionsCopyWith<$Res> {
  factory _$SourceEditorOptionsCopyWith(_SourceEditorOptions value, $Res Function(_SourceEditorOptions) _then) = __$SourceEditorOptionsCopyWithImpl;
@override @useResult
$Res call({
 bool enabled, bool enabledExplore, bool enabledCookieJar, bool enabledReview, int type, bool eventListener, bool customButton
});




}
/// @nodoc
class __$SourceEditorOptionsCopyWithImpl<$Res>
    implements _$SourceEditorOptionsCopyWith<$Res> {
  __$SourceEditorOptionsCopyWithImpl(this._self, this._then);

  final _SourceEditorOptions _self;
  final $Res Function(_SourceEditorOptions) _then;

/// Create a copy of SourceEditorOptions
/// with the given fields replaced by the non-null parameter values.
@override @pragma('vm:prefer-inline') $Res call({Object? enabled = null,Object? enabledExplore = null,Object? enabledCookieJar = null,Object? enabledReview = null,Object? type = null,Object? eventListener = null,Object? customButton = null,}) {
  return _then(_SourceEditorOptions(
enabled: null == enabled ? _self.enabled : enabled // ignore: cast_nullable_to_non_nullable
as bool,enabledExplore: null == enabledExplore ? _self.enabledExplore : enabledExplore // ignore: cast_nullable_to_non_nullable
as bool,enabledCookieJar: null == enabledCookieJar ? _self.enabledCookieJar : enabledCookieJar // ignore: cast_nullable_to_non_nullable
as bool,enabledReview: null == enabledReview ? _self.enabledReview : enabledReview // ignore: cast_nullable_to_non_nullable
as bool,type: null == type ? _self.type : type // ignore: cast_nullable_to_non_nullable
as int,eventListener: null == eventListener ? _self.eventListener : eventListener // ignore: cast_nullable_to_non_nullable
as bool,customButton: null == customButton ? _self.customButton : customButton // ignore: cast_nullable_to_non_nullable
as bool,
  ));
}


}

/// @nodoc
mixin _$SourceEditorDocument {

 int get revision; int get selectedTab; String? get focusedKey; bool get optionsExpanded; bool get autoComplete; bool get finished; bool get dirty; bool get hasLogin; SourceEditorOptions get options; List<List<SourceEditorField>> get tabs;
/// Create a copy of SourceEditorDocument
/// with the given fields replaced by the non-null parameter values.
@JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
$SourceEditorDocumentCopyWith<SourceEditorDocument> get copyWith => _$SourceEditorDocumentCopyWithImpl<SourceEditorDocument>(this as SourceEditorDocument, _$identity);



@override
bool operator ==(Object other) {
  final _this = this as SourceEditorDocument;
  return identical(this, other) || (other.runtimeType == runtimeType&&other is SourceEditorDocument&&(identical(other.revision, _this.revision) || other.revision == _this.revision)&&(identical(other.selectedTab, _this.selectedTab) || other.selectedTab == _this.selectedTab)&&(identical(other.focusedKey, _this.focusedKey) || other.focusedKey == _this.focusedKey)&&(identical(other.optionsExpanded, _this.optionsExpanded) || other.optionsExpanded == _this.optionsExpanded)&&(identical(other.autoComplete, _this.autoComplete) || other.autoComplete == _this.autoComplete)&&(identical(other.finished, _this.finished) || other.finished == _this.finished)&&(identical(other.dirty, _this.dirty) || other.dirty == _this.dirty)&&(identical(other.hasLogin, _this.hasLogin) || other.hasLogin == _this.hasLogin)&&(identical(other.options, _this.options) || other.options == _this.options)&&const DeepCollectionEquality().equals(other.tabs, _this.tabs));
}


@override
int get hashCode {
  final _this = this as SourceEditorDocument;
  return Object.hash(runtimeType,_this.revision,_this.selectedTab,_this.focusedKey,_this.optionsExpanded,_this.autoComplete,_this.finished,_this.dirty,_this.hasLogin,_this.options,const DeepCollectionEquality().hash(_this.tabs));
}

@override
String toString() {
  final _this = this as SourceEditorDocument;
  return 'SourceEditorDocument(revision: ${_this.revision}, selectedTab: ${_this.selectedTab}, focusedKey: ${_this.focusedKey}, optionsExpanded: ${_this.optionsExpanded}, autoComplete: ${_this.autoComplete}, finished: ${_this.finished}, dirty: ${_this.dirty}, hasLogin: ${_this.hasLogin}, options: ${_this.options}, tabs: ${_this.tabs})';
}


}

/// @nodoc
abstract mixin class $SourceEditorDocumentCopyWith<$Res>  {
  factory $SourceEditorDocumentCopyWith(SourceEditorDocument value, $Res Function(SourceEditorDocument) _then) = _$SourceEditorDocumentCopyWithImpl;
@useResult
$Res call({
 int revision, int selectedTab, String? focusedKey, bool optionsExpanded, bool autoComplete, bool finished, bool dirty, bool hasLogin, SourceEditorOptions options, List<List<SourceEditorField>> tabs
});


$SourceEditorOptionsCopyWith<$Res> get options;

}
/// @nodoc
class _$SourceEditorDocumentCopyWithImpl<$Res>
    implements $SourceEditorDocumentCopyWith<$Res> {
  _$SourceEditorDocumentCopyWithImpl(this._self, this._then);

  final SourceEditorDocument _self;
  final $Res Function(SourceEditorDocument) _then;

/// Create a copy of SourceEditorDocument
/// with the given fields replaced by the non-null parameter values.
@pragma('vm:prefer-inline') @override $Res call({Object? revision = null,Object? selectedTab = null,Object? focusedKey = freezed,Object? optionsExpanded = null,Object? autoComplete = null,Object? finished = null,Object? dirty = null,Object? hasLogin = null,Object? options = null,Object? tabs = null,}) {
  return _then(SourceEditorDocument(
revision: null == revision ? _self.revision : revision // ignore: cast_nullable_to_non_nullable
as int,selectedTab: null == selectedTab ? _self.selectedTab : selectedTab // ignore: cast_nullable_to_non_nullable
as int,focusedKey: freezed == focusedKey ? _self.focusedKey : focusedKey // ignore: cast_nullable_to_non_nullable
as String?,optionsExpanded: null == optionsExpanded ? _self.optionsExpanded : optionsExpanded // ignore: cast_nullable_to_non_nullable
as bool,autoComplete: null == autoComplete ? _self.autoComplete : autoComplete // ignore: cast_nullable_to_non_nullable
as bool,finished: null == finished ? _self.finished : finished // ignore: cast_nullable_to_non_nullable
as bool,dirty: null == dirty ? _self.dirty : dirty // ignore: cast_nullable_to_non_nullable
as bool,hasLogin: null == hasLogin ? _self.hasLogin : hasLogin // ignore: cast_nullable_to_non_nullable
as bool,options: null == options ? _self.options : options // ignore: cast_nullable_to_non_nullable
as SourceEditorOptions,tabs: null == tabs ? _self.tabs : tabs // ignore: cast_nullable_to_non_nullable
as List<List<SourceEditorField>>,
  ));
}
/// Create a copy of SourceEditorDocument
/// with the given fields replaced by the non-null parameter values.
@override
@pragma('vm:prefer-inline')
$SourceEditorOptionsCopyWith<$Res> get options {

  return $SourceEditorOptionsCopyWith<$Res>(_self.options, (value) {
    return _then(_self.copyWith(options: value));
  });
}
}


/// Adds pattern-matching-related methods to [SourceEditorDocument].
extension SourceEditorDocumentPatterns on SourceEditorDocument {
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

@optionalTypeArgs TResult maybeMap<TResult extends Object?>(TResult Function( _SourceEditorDocument value)?  $default,{required TResult orElse(),}){
final _that = this;
switch (_that) {
case _SourceEditorDocument() when $default != null:
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

@optionalTypeArgs TResult map<TResult extends Object?>(TResult Function( _SourceEditorDocument value)  $default,){
final _that = this;
switch (_that) {
case _SourceEditorDocument():
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

@optionalTypeArgs TResult? mapOrNull<TResult extends Object?>(TResult? Function( _SourceEditorDocument value)?  $default,){
final _that = this;
switch (_that) {
case _SourceEditorDocument() when $default != null:
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

@optionalTypeArgs TResult maybeWhen<TResult extends Object?>(TResult Function( int revision,  int selectedTab,  String? focusedKey,  bool optionsExpanded,  bool autoComplete,  bool finished,  bool dirty,  bool hasLogin,  SourceEditorOptions options,  List<List<SourceEditorField>> tabs)?  $default,{required TResult orElse(),}) {final _that = this;
switch (_that) {
case _SourceEditorDocument() when $default != null:
return $default(_that.revision,_that.selectedTab,_that.focusedKey,_that.optionsExpanded,_that.autoComplete,_that.finished,_that.dirty,_that.hasLogin,_that.options,_that.tabs);case _:
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

@optionalTypeArgs TResult when<TResult extends Object?>(TResult Function( int revision,  int selectedTab,  String? focusedKey,  bool optionsExpanded,  bool autoComplete,  bool finished,  bool dirty,  bool hasLogin,  SourceEditorOptions options,  List<List<SourceEditorField>> tabs)  $default,) {final _that = this;
switch (_that) {
case _SourceEditorDocument():
return $default(_that.revision,_that.selectedTab,_that.focusedKey,_that.optionsExpanded,_that.autoComplete,_that.finished,_that.dirty,_that.hasLogin,_that.options,_that.tabs);case _:
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

@optionalTypeArgs TResult? whenOrNull<TResult extends Object?>(TResult? Function( int revision,  int selectedTab,  String? focusedKey,  bool optionsExpanded,  bool autoComplete,  bool finished,  bool dirty,  bool hasLogin,  SourceEditorOptions options,  List<List<SourceEditorField>> tabs)?  $default,) {final _that = this;
switch (_that) {
case _SourceEditorDocument() when $default != null:
return $default(_that.revision,_that.selectedTab,_that.focusedKey,_that.optionsExpanded,_that.autoComplete,_that.finished,_that.dirty,_that.hasLogin,_that.options,_that.tabs);case _:
  return null;

}
}

}

/// @nodoc


class _SourceEditorDocument extends SourceEditorDocument {
  const _SourceEditorDocument({required this.revision, required this.selectedTab, this.focusedKey, required this.optionsExpanded, required this.autoComplete, required this.finished, required this.dirty, required this.hasLogin, required this.options, required  List<List<SourceEditorField>> tabs}): _tabs = tabs,super._();


@override final  int revision;
@override final  int selectedTab;
@override final  String? focusedKey;
@override final  bool optionsExpanded;
@override final  bool autoComplete;
@override final  bool finished;
@override final  bool dirty;
@override final  bool hasLogin;
@override final  SourceEditorOptions options;
 final  List<List<SourceEditorField>> _tabs;
@override List<List<SourceEditorField>> get tabs {
  if (_tabs is EqualUnmodifiableListView) return _tabs;
  // ignore: implicit_dynamic_type
  return EqualUnmodifiableListView(_tabs);
}


/// Create a copy of SourceEditorDocument
/// with the given fields replaced by the non-null parameter values.
@override @JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
_$SourceEditorDocumentCopyWith<_SourceEditorDocument> get copyWith => __$SourceEditorDocumentCopyWithImpl<_SourceEditorDocument>(this, _$identity);



@override
bool operator ==(Object other) {
    return identical(this, other) || (other.runtimeType == runtimeType&&other is _SourceEditorDocument&&(identical(other.revision, revision) || other.revision == revision)&&(identical(other.selectedTab, selectedTab) || other.selectedTab == selectedTab)&&(identical(other.focusedKey, focusedKey) || other.focusedKey == focusedKey)&&(identical(other.optionsExpanded, optionsExpanded) || other.optionsExpanded == optionsExpanded)&&(identical(other.autoComplete, autoComplete) || other.autoComplete == autoComplete)&&(identical(other.finished, finished) || other.finished == finished)&&(identical(other.dirty, dirty) || other.dirty == dirty)&&(identical(other.hasLogin, hasLogin) || other.hasLogin == hasLogin)&&(identical(other.options, options) || other.options == options)&&const DeepCollectionEquality().equals(other.tabs, _tabs));
}


@override
int get hashCode {
    return Object.hash(runtimeType,revision,selectedTab,focusedKey,optionsExpanded,autoComplete,finished,dirty,hasLogin,options,const DeepCollectionEquality().hash(_tabs));
}

@override
String toString() {
    return 'SourceEditorDocument(revision: $revision, selectedTab: $selectedTab, focusedKey: $focusedKey, optionsExpanded: $optionsExpanded, autoComplete: $autoComplete, finished: $finished, dirty: $dirty, hasLogin: $hasLogin, options: $options, tabs: $tabs)';
}


}

/// @nodoc
abstract mixin class _$SourceEditorDocumentCopyWith<$Res> implements $SourceEditorDocumentCopyWith<$Res> {
  factory _$SourceEditorDocumentCopyWith(_SourceEditorDocument value, $Res Function(_SourceEditorDocument) _then) = __$SourceEditorDocumentCopyWithImpl;
@override @useResult
$Res call({
 int revision, int selectedTab, String? focusedKey, bool optionsExpanded, bool autoComplete, bool finished, bool dirty, bool hasLogin, SourceEditorOptions options, List<List<SourceEditorField>> tabs
});


@override $SourceEditorOptionsCopyWith<$Res> get options;

}
/// @nodoc
class __$SourceEditorDocumentCopyWithImpl<$Res>
    implements _$SourceEditorDocumentCopyWith<$Res> {
  __$SourceEditorDocumentCopyWithImpl(this._self, this._then);

  final _SourceEditorDocument _self;
  final $Res Function(_SourceEditorDocument) _then;

/// Create a copy of SourceEditorDocument
/// with the given fields replaced by the non-null parameter values.
@override @pragma('vm:prefer-inline') $Res call({Object? revision = null,Object? selectedTab = null,Object? focusedKey = freezed,Object? optionsExpanded = null,Object? autoComplete = null,Object? finished = null,Object? dirty = null,Object? hasLogin = null,Object? options = null,Object? tabs = null,}) {
  return _then(_SourceEditorDocument(
revision: null == revision ? _self.revision : revision // ignore: cast_nullable_to_non_nullable
as int,selectedTab: null == selectedTab ? _self.selectedTab : selectedTab // ignore: cast_nullable_to_non_nullable
as int,focusedKey: freezed == focusedKey ? _self.focusedKey : focusedKey // ignore: cast_nullable_to_non_nullable
as String?,optionsExpanded: null == optionsExpanded ? _self.optionsExpanded : optionsExpanded // ignore: cast_nullable_to_non_nullable
as bool,autoComplete: null == autoComplete ? _self.autoComplete : autoComplete // ignore: cast_nullable_to_non_nullable
as bool,finished: null == finished ? _self.finished : finished // ignore: cast_nullable_to_non_nullable
as bool,dirty: null == dirty ? _self.dirty : dirty // ignore: cast_nullable_to_non_nullable
as bool,hasLogin: null == hasLogin ? _self.hasLogin : hasLogin // ignore: cast_nullable_to_non_nullable
as bool,options: null == options ? _self.options : options // ignore: cast_nullable_to_non_nullable
as SourceEditorOptions,tabs: null == tabs ? _self._tabs : tabs // ignore: cast_nullable_to_non_nullable
as List<List<SourceEditorField>>,
  ));
}

/// Create a copy of SourceEditorDocument
/// with the given fields replaced by the non-null parameter values.
@override
@pragma('vm:prefer-inline')
$SourceEditorOptionsCopyWith<$Res> get options {

  return $SourceEditorOptionsCopyWith<$Res>(_self.options, (value) {
    return _then(_self.copyWith(options: value));
  });
}
}

/// @nodoc
mixin _$SourceEditorMigration {

 int get revision; String get status; bool get requiresManualWork; bool get canApply; List<Map<String, Object?>> get issues;
/// Create a copy of SourceEditorMigration
/// with the given fields replaced by the non-null parameter values.
@JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
$SourceEditorMigrationCopyWith<SourceEditorMigration> get copyWith => _$SourceEditorMigrationCopyWithImpl<SourceEditorMigration>(this as SourceEditorMigration, _$identity);



@override
bool operator ==(Object other) {
  final _this = this as SourceEditorMigration;
  return identical(this, other) || (other.runtimeType == runtimeType&&other is SourceEditorMigration&&(identical(other.revision, _this.revision) || other.revision == _this.revision)&&(identical(other.status, _this.status) || other.status == _this.status)&&(identical(other.requiresManualWork, _this.requiresManualWork) || other.requiresManualWork == _this.requiresManualWork)&&(identical(other.canApply, _this.canApply) || other.canApply == _this.canApply)&&const DeepCollectionEquality().equals(other.issues, _this.issues));
}


@override
int get hashCode {
  final _this = this as SourceEditorMigration;
  return Object.hash(runtimeType,_this.revision,_this.status,_this.requiresManualWork,_this.canApply,const DeepCollectionEquality().hash(_this.issues));
}

@override
String toString() {
  final _this = this as SourceEditorMigration;
  return 'SourceEditorMigration(revision: ${_this.revision}, status: ${_this.status}, requiresManualWork: ${_this.requiresManualWork}, canApply: ${_this.canApply}, issues: ${_this.issues})';
}


}

/// @nodoc
abstract mixin class $SourceEditorMigrationCopyWith<$Res>  {
  factory $SourceEditorMigrationCopyWith(SourceEditorMigration value, $Res Function(SourceEditorMigration) _then) = _$SourceEditorMigrationCopyWithImpl;
@useResult
$Res call({
 int revision, String status, bool requiresManualWork, bool canApply, List<Map<String, Object?>> issues
});




}
/// @nodoc
class _$SourceEditorMigrationCopyWithImpl<$Res>
    implements $SourceEditorMigrationCopyWith<$Res> {
  _$SourceEditorMigrationCopyWithImpl(this._self, this._then);

  final SourceEditorMigration _self;
  final $Res Function(SourceEditorMigration) _then;

/// Create a copy of SourceEditorMigration
/// with the given fields replaced by the non-null parameter values.
@pragma('vm:prefer-inline') @override $Res call({Object? revision = null,Object? status = null,Object? requiresManualWork = null,Object? canApply = null,Object? issues = null,}) {
  return _then(SourceEditorMigration(
revision: null == revision ? _self.revision : revision // ignore: cast_nullable_to_non_nullable
as int,status: null == status ? _self.status : status // ignore: cast_nullable_to_non_nullable
as String,requiresManualWork: null == requiresManualWork ? _self.requiresManualWork : requiresManualWork // ignore: cast_nullable_to_non_nullable
as bool,canApply: null == canApply ? _self.canApply : canApply // ignore: cast_nullable_to_non_nullable
as bool,issues: null == issues ? _self.issues : issues // ignore: cast_nullable_to_non_nullable
as List<Map<String, Object?>>,
  ));
}

}


/// Adds pattern-matching-related methods to [SourceEditorMigration].
extension SourceEditorMigrationPatterns on SourceEditorMigration {
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

@optionalTypeArgs TResult maybeMap<TResult extends Object?>(TResult Function( _SourceEditorMigration value)?  $default,{required TResult orElse(),}){
final _that = this;
switch (_that) {
case _SourceEditorMigration() when $default != null:
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

@optionalTypeArgs TResult map<TResult extends Object?>(TResult Function( _SourceEditorMigration value)  $default,){
final _that = this;
switch (_that) {
case _SourceEditorMigration():
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

@optionalTypeArgs TResult? mapOrNull<TResult extends Object?>(TResult? Function( _SourceEditorMigration value)?  $default,){
final _that = this;
switch (_that) {
case _SourceEditorMigration() when $default != null:
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

@optionalTypeArgs TResult maybeWhen<TResult extends Object?>(TResult Function( int revision,  String status,  bool requiresManualWork,  bool canApply,  List<Map<String, Object?>> issues)?  $default,{required TResult orElse(),}) {final _that = this;
switch (_that) {
case _SourceEditorMigration() when $default != null:
return $default(_that.revision,_that.status,_that.requiresManualWork,_that.canApply,_that.issues);case _:
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

@optionalTypeArgs TResult when<TResult extends Object?>(TResult Function( int revision,  String status,  bool requiresManualWork,  bool canApply,  List<Map<String, Object?>> issues)  $default,) {final _that = this;
switch (_that) {
case _SourceEditorMigration():
return $default(_that.revision,_that.status,_that.requiresManualWork,_that.canApply,_that.issues);case _:
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

@optionalTypeArgs TResult? whenOrNull<TResult extends Object?>(TResult? Function( int revision,  String status,  bool requiresManualWork,  bool canApply,  List<Map<String, Object?>> issues)?  $default,) {final _that = this;
switch (_that) {
case _SourceEditorMigration() when $default != null:
return $default(_that.revision,_that.status,_that.requiresManualWork,_that.canApply,_that.issues);case _:
  return null;

}
}

}

/// @nodoc


class _SourceEditorMigration extends SourceEditorMigration {
  const _SourceEditorMigration({required this.revision, required this.status, required this.requiresManualWork, required this.canApply, required  List<Map<String, Object?>> issues}): _issues = issues,super._();


@override final  int revision;
@override final  String status;
@override final  bool requiresManualWork;
@override final  bool canApply;
 final  List<Map<String, Object?>> _issues;
@override List<Map<String, Object?>> get issues {
  if (_issues is EqualUnmodifiableListView) return _issues;
  // ignore: implicit_dynamic_type
  return EqualUnmodifiableListView(_issues);
}


/// Create a copy of SourceEditorMigration
/// with the given fields replaced by the non-null parameter values.
@override @JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
_$SourceEditorMigrationCopyWith<_SourceEditorMigration> get copyWith => __$SourceEditorMigrationCopyWithImpl<_SourceEditorMigration>(this, _$identity);



@override
bool operator ==(Object other) {
    return identical(this, other) || (other.runtimeType == runtimeType&&other is _SourceEditorMigration&&(identical(other.revision, revision) || other.revision == revision)&&(identical(other.status, status) || other.status == status)&&(identical(other.requiresManualWork, requiresManualWork) || other.requiresManualWork == requiresManualWork)&&(identical(other.canApply, canApply) || other.canApply == canApply)&&const DeepCollectionEquality().equals(other.issues, _issues));
}


@override
int get hashCode {
    return Object.hash(runtimeType,revision,status,requiresManualWork,canApply,const DeepCollectionEquality().hash(_issues));
}

@override
String toString() {
    return 'SourceEditorMigration(revision: $revision, status: $status, requiresManualWork: $requiresManualWork, canApply: $canApply, issues: $issues)';
}


}

/// @nodoc
abstract mixin class _$SourceEditorMigrationCopyWith<$Res> implements $SourceEditorMigrationCopyWith<$Res> {
  factory _$SourceEditorMigrationCopyWith(_SourceEditorMigration value, $Res Function(_SourceEditorMigration) _then) = __$SourceEditorMigrationCopyWithImpl;
@override @useResult
$Res call({
 int revision, String status, bool requiresManualWork, bool canApply, List<Map<String, Object?>> issues
});




}
/// @nodoc
class __$SourceEditorMigrationCopyWithImpl<$Res>
    implements _$SourceEditorMigrationCopyWith<$Res> {
  __$SourceEditorMigrationCopyWithImpl(this._self, this._then);

  final _SourceEditorMigration _self;
  final $Res Function(_SourceEditorMigration) _then;

/// Create a copy of SourceEditorMigration
/// with the given fields replaced by the non-null parameter values.
@override @pragma('vm:prefer-inline') $Res call({Object? revision = null,Object? status = null,Object? requiresManualWork = null,Object? canApply = null,Object? issues = null,}) {
  return _then(_SourceEditorMigration(
revision: null == revision ? _self.revision : revision // ignore: cast_nullable_to_non_nullable
as int,status: null == status ? _self.status : status // ignore: cast_nullable_to_non_nullable
as String,requiresManualWork: null == requiresManualWork ? _self.requiresManualWork : requiresManualWork // ignore: cast_nullable_to_non_nullable
as bool,canApply: null == canApply ? _self.canApply : canApply // ignore: cast_nullable_to_non_nullable
as bool,issues: null == issues ? _self._issues : issues // ignore: cast_nullable_to_non_nullable
as List<Map<String, Object?>>,
  ));
}


}

/// @nodoc
mixin _$SourceEditorSnapshot {

 SourceEditorDocument? get document; bool get busy; String? get error; bool get confirmDiscard; List<String>? get groups; String? get variable; String? get variableComment; int get maxLines; int get keyboardRows; List<Map<String, String>> get assists; bool get migrationAvailable; bool get migrationRunning; String? get migrationError; SourceEditorMigration? get migrationReport;
/// Create a copy of SourceEditorSnapshot
/// with the given fields replaced by the non-null parameter values.
@JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
$SourceEditorSnapshotCopyWith<SourceEditorSnapshot> get copyWith => _$SourceEditorSnapshotCopyWithImpl<SourceEditorSnapshot>(this as SourceEditorSnapshot, _$identity);



@override
bool operator ==(Object other) {
  final _this = this as SourceEditorSnapshot;
  return identical(this, other) || (other.runtimeType == runtimeType&&other is SourceEditorSnapshot&&(identical(other.document, _this.document) || other.document == _this.document)&&(identical(other.busy, _this.busy) || other.busy == _this.busy)&&(identical(other.error, _this.error) || other.error == _this.error)&&(identical(other.confirmDiscard, _this.confirmDiscard) || other.confirmDiscard == _this.confirmDiscard)&&const DeepCollectionEquality().equals(other.groups, _this.groups)&&(identical(other.variable, _this.variable) || other.variable == _this.variable)&&(identical(other.variableComment, _this.variableComment) || other.variableComment == _this.variableComment)&&(identical(other.maxLines, _this.maxLines) || other.maxLines == _this.maxLines)&&(identical(other.keyboardRows, _this.keyboardRows) || other.keyboardRows == _this.keyboardRows)&&const DeepCollectionEquality().equals(other.assists, _this.assists)&&(identical(other.migrationAvailable, _this.migrationAvailable) || other.migrationAvailable == _this.migrationAvailable)&&(identical(other.migrationRunning, _this.migrationRunning) || other.migrationRunning == _this.migrationRunning)&&(identical(other.migrationError, _this.migrationError) || other.migrationError == _this.migrationError)&&(identical(other.migrationReport, _this.migrationReport) || other.migrationReport == _this.migrationReport));
}


@override
int get hashCode {
  final _this = this as SourceEditorSnapshot;
  return Object.hash(runtimeType,_this.document,_this.busy,_this.error,_this.confirmDiscard,const DeepCollectionEquality().hash(_this.groups),_this.variable,_this.variableComment,_this.maxLines,_this.keyboardRows,const DeepCollectionEquality().hash(_this.assists),_this.migrationAvailable,_this.migrationRunning,_this.migrationError,_this.migrationReport);
}

@override
String toString() {
  final _this = this as SourceEditorSnapshot;
  return 'SourceEditorSnapshot(document: ${_this.document}, busy: ${_this.busy}, error: ${_this.error}, confirmDiscard: ${_this.confirmDiscard}, groups: ${_this.groups}, variable: ${_this.variable}, variableComment: ${_this.variableComment}, maxLines: ${_this.maxLines}, keyboardRows: ${_this.keyboardRows}, assists: ${_this.assists}, migrationAvailable: ${_this.migrationAvailable}, migrationRunning: ${_this.migrationRunning}, migrationError: ${_this.migrationError}, migrationReport: ${_this.migrationReport})';
}


}

/// @nodoc
abstract mixin class $SourceEditorSnapshotCopyWith<$Res>  {
  factory $SourceEditorSnapshotCopyWith(SourceEditorSnapshot value, $Res Function(SourceEditorSnapshot) _then) = _$SourceEditorSnapshotCopyWithImpl;
@useResult
$Res call({
 SourceEditorDocument? document, bool busy, String? error, bool confirmDiscard, List<String>? groups, String? variable, String? variableComment, int maxLines, int keyboardRows, List<Map<String, String>> assists, bool migrationAvailable, bool migrationRunning, String? migrationError, SourceEditorMigration? migrationReport
});


$SourceEditorDocumentCopyWith<$Res>? get document;$SourceEditorMigrationCopyWith<$Res>? get migrationReport;

}
/// @nodoc
class _$SourceEditorSnapshotCopyWithImpl<$Res>
    implements $SourceEditorSnapshotCopyWith<$Res> {
  _$SourceEditorSnapshotCopyWithImpl(this._self, this._then);

  final SourceEditorSnapshot _self;
  final $Res Function(SourceEditorSnapshot) _then;

/// Create a copy of SourceEditorSnapshot
/// with the given fields replaced by the non-null parameter values.
@pragma('vm:prefer-inline') @override $Res call({Object? document = freezed,Object? busy = null,Object? error = freezed,Object? confirmDiscard = null,Object? groups = freezed,Object? variable = freezed,Object? variableComment = freezed,Object? maxLines = null,Object? keyboardRows = null,Object? assists = null,Object? migrationAvailable = null,Object? migrationRunning = null,Object? migrationError = freezed,Object? migrationReport = freezed,}) {
  return _then(SourceEditorSnapshot(
document: freezed == document ? _self.document : document // ignore: cast_nullable_to_non_nullable
as SourceEditorDocument?,busy: null == busy ? _self.busy : busy // ignore: cast_nullable_to_non_nullable
as bool,error: freezed == error ? _self.error : error // ignore: cast_nullable_to_non_nullable
as String?,confirmDiscard: null == confirmDiscard ? _self.confirmDiscard : confirmDiscard // ignore: cast_nullable_to_non_nullable
as bool,groups: freezed == groups ? _self.groups : groups // ignore: cast_nullable_to_non_nullable
as List<String>?,variable: freezed == variable ? _self.variable : variable // ignore: cast_nullable_to_non_nullable
as String?,variableComment: freezed == variableComment ? _self.variableComment : variableComment // ignore: cast_nullable_to_non_nullable
as String?,maxLines: null == maxLines ? _self.maxLines : maxLines // ignore: cast_nullable_to_non_nullable
as int,keyboardRows: null == keyboardRows ? _self.keyboardRows : keyboardRows // ignore: cast_nullable_to_non_nullable
as int,assists: null == assists ? _self.assists : assists // ignore: cast_nullable_to_non_nullable
as List<Map<String, String>>,migrationAvailable: null == migrationAvailable ? _self.migrationAvailable : migrationAvailable // ignore: cast_nullable_to_non_nullable
as bool,migrationRunning: null == migrationRunning ? _self.migrationRunning : migrationRunning // ignore: cast_nullable_to_non_nullable
as bool,migrationError: freezed == migrationError ? _self.migrationError : migrationError // ignore: cast_nullable_to_non_nullable
as String?,migrationReport: freezed == migrationReport ? _self.migrationReport : migrationReport // ignore: cast_nullable_to_non_nullable
as SourceEditorMigration?,
  ));
}
/// Create a copy of SourceEditorSnapshot
/// with the given fields replaced by the non-null parameter values.
@override
@pragma('vm:prefer-inline')
$SourceEditorDocumentCopyWith<$Res>? get document {
    if (_self.document == null) {
    return null;
  }

  return $SourceEditorDocumentCopyWith<$Res>(_self.document!, (value) {
    return _then(_self.copyWith(document: value));
  });
}/// Create a copy of SourceEditorSnapshot
/// with the given fields replaced by the non-null parameter values.
@override
@pragma('vm:prefer-inline')
$SourceEditorMigrationCopyWith<$Res>? get migrationReport {
    if (_self.migrationReport == null) {
    return null;
  }

  return $SourceEditorMigrationCopyWith<$Res>(_self.migrationReport!, (value) {
    return _then(_self.copyWith(migrationReport: value));
  });
}
}


/// Adds pattern-matching-related methods to [SourceEditorSnapshot].
extension SourceEditorSnapshotPatterns on SourceEditorSnapshot {
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

@optionalTypeArgs TResult maybeMap<TResult extends Object?>(TResult Function( _SourceEditorSnapshot value)?  $default,{required TResult orElse(),}){
final _that = this;
switch (_that) {
case _SourceEditorSnapshot() when $default != null:
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

@optionalTypeArgs TResult map<TResult extends Object?>(TResult Function( _SourceEditorSnapshot value)  $default,){
final _that = this;
switch (_that) {
case _SourceEditorSnapshot():
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

@optionalTypeArgs TResult? mapOrNull<TResult extends Object?>(TResult? Function( _SourceEditorSnapshot value)?  $default,){
final _that = this;
switch (_that) {
case _SourceEditorSnapshot() when $default != null:
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

@optionalTypeArgs TResult maybeWhen<TResult extends Object?>(TResult Function( SourceEditorDocument? document,  bool busy,  String? error,  bool confirmDiscard,  List<String>? groups,  String? variable,  String? variableComment,  int maxLines,  int keyboardRows,  List<Map<String, String>> assists,  bool migrationAvailable,  bool migrationRunning,  String? migrationError,  SourceEditorMigration? migrationReport)?  $default,{required TResult orElse(),}) {final _that = this;
switch (_that) {
case _SourceEditorSnapshot() when $default != null:
return $default(_that.document,_that.busy,_that.error,_that.confirmDiscard,_that.groups,_that.variable,_that.variableComment,_that.maxLines,_that.keyboardRows,_that.assists,_that.migrationAvailable,_that.migrationRunning,_that.migrationError,_that.migrationReport);case _:
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

@optionalTypeArgs TResult when<TResult extends Object?>(TResult Function( SourceEditorDocument? document,  bool busy,  String? error,  bool confirmDiscard,  List<String>? groups,  String? variable,  String? variableComment,  int maxLines,  int keyboardRows,  List<Map<String, String>> assists,  bool migrationAvailable,  bool migrationRunning,  String? migrationError,  SourceEditorMigration? migrationReport)  $default,) {final _that = this;
switch (_that) {
case _SourceEditorSnapshot():
return $default(_that.document,_that.busy,_that.error,_that.confirmDiscard,_that.groups,_that.variable,_that.variableComment,_that.maxLines,_that.keyboardRows,_that.assists,_that.migrationAvailable,_that.migrationRunning,_that.migrationError,_that.migrationReport);case _:
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

@optionalTypeArgs TResult? whenOrNull<TResult extends Object?>(TResult? Function( SourceEditorDocument? document,  bool busy,  String? error,  bool confirmDiscard,  List<String>? groups,  String? variable,  String? variableComment,  int maxLines,  int keyboardRows,  List<Map<String, String>> assists,  bool migrationAvailable,  bool migrationRunning,  String? migrationError,  SourceEditorMigration? migrationReport)?  $default,) {final _that = this;
switch (_that) {
case _SourceEditorSnapshot() when $default != null:
return $default(_that.document,_that.busy,_that.error,_that.confirmDiscard,_that.groups,_that.variable,_that.variableComment,_that.maxLines,_that.keyboardRows,_that.assists,_that.migrationAvailable,_that.migrationRunning,_that.migrationError,_that.migrationReport);case _:
  return null;

}
}

}

/// @nodoc


class _SourceEditorSnapshot extends SourceEditorSnapshot {
  const _SourceEditorSnapshot({this.document, this.busy = false, this.error, this.confirmDiscard = false,  List<String>? groups, this.variable, this.variableComment, this.maxLines = 6, this.keyboardRows = 1,  List<Map<String, String>> assists = const [], this.migrationAvailable = false, this.migrationRunning = false, this.migrationError, this.migrationReport}): _groups = groups,_assists = assists,super._();


@override final  SourceEditorDocument? document;
@override@JsonKey() final  bool busy;
@override final  String? error;
@override@JsonKey() final  bool confirmDiscard;
 final  List<String>? _groups;
@override List<String>? get groups {
  final value = _groups;
  if (value == null) return null;
  if (_groups is EqualUnmodifiableListView) return _groups;
  // ignore: implicit_dynamic_type
  return EqualUnmodifiableListView(value);
}

@override final  String? variable;
@override final  String? variableComment;
@override@JsonKey() final  int maxLines;
@override@JsonKey() final  int keyboardRows;
 final  List<Map<String, String>> _assists;
@override@JsonKey() List<Map<String, String>> get assists {
  if (_assists is EqualUnmodifiableListView) return _assists;
  // ignore: implicit_dynamic_type
  return EqualUnmodifiableListView(_assists);
}

@override@JsonKey() final  bool migrationAvailable;
@override@JsonKey() final  bool migrationRunning;
@override final  String? migrationError;
@override final  SourceEditorMigration? migrationReport;

/// Create a copy of SourceEditorSnapshot
/// with the given fields replaced by the non-null parameter values.
@override @JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
_$SourceEditorSnapshotCopyWith<_SourceEditorSnapshot> get copyWith => __$SourceEditorSnapshotCopyWithImpl<_SourceEditorSnapshot>(this, _$identity);



@override
bool operator ==(Object other) {
    return identical(this, other) || (other.runtimeType == runtimeType&&other is _SourceEditorSnapshot&&(identical(other.document, document) || other.document == document)&&(identical(other.busy, busy) || other.busy == busy)&&(identical(other.error, error) || other.error == error)&&(identical(other.confirmDiscard, confirmDiscard) || other.confirmDiscard == confirmDiscard)&&const DeepCollectionEquality().equals(other.groups, _groups)&&(identical(other.variable, variable) || other.variable == variable)&&(identical(other.variableComment, variableComment) || other.variableComment == variableComment)&&(identical(other.maxLines, maxLines) || other.maxLines == maxLines)&&(identical(other.keyboardRows, keyboardRows) || other.keyboardRows == keyboardRows)&&const DeepCollectionEquality().equals(other.assists, _assists)&&(identical(other.migrationAvailable, migrationAvailable) || other.migrationAvailable == migrationAvailable)&&(identical(other.migrationRunning, migrationRunning) || other.migrationRunning == migrationRunning)&&(identical(other.migrationError, migrationError) || other.migrationError == migrationError)&&(identical(other.migrationReport, migrationReport) || other.migrationReport == migrationReport));
}


@override
int get hashCode {
    return Object.hash(runtimeType,document,busy,error,confirmDiscard,const DeepCollectionEquality().hash(_groups),variable,variableComment,maxLines,keyboardRows,const DeepCollectionEquality().hash(_assists),migrationAvailable,migrationRunning,migrationError,migrationReport);
}

@override
String toString() {
    return 'SourceEditorSnapshot(document: $document, busy: $busy, error: $error, confirmDiscard: $confirmDiscard, groups: $groups, variable: $variable, variableComment: $variableComment, maxLines: $maxLines, keyboardRows: $keyboardRows, assists: $assists, migrationAvailable: $migrationAvailable, migrationRunning: $migrationRunning, migrationError: $migrationError, migrationReport: $migrationReport)';
}


}

/// @nodoc
abstract mixin class _$SourceEditorSnapshotCopyWith<$Res> implements $SourceEditorSnapshotCopyWith<$Res> {
  factory _$SourceEditorSnapshotCopyWith(_SourceEditorSnapshot value, $Res Function(_SourceEditorSnapshot) _then) = __$SourceEditorSnapshotCopyWithImpl;
@override @useResult
$Res call({
 SourceEditorDocument? document, bool busy, String? error, bool confirmDiscard, List<String>? groups, String? variable, String? variableComment, int maxLines, int keyboardRows, List<Map<String, String>> assists, bool migrationAvailable, bool migrationRunning, String? migrationError, SourceEditorMigration? migrationReport
});


@override $SourceEditorDocumentCopyWith<$Res>? get document;@override $SourceEditorMigrationCopyWith<$Res>? get migrationReport;

}
/// @nodoc
class __$SourceEditorSnapshotCopyWithImpl<$Res>
    implements _$SourceEditorSnapshotCopyWith<$Res> {
  __$SourceEditorSnapshotCopyWithImpl(this._self, this._then);

  final _SourceEditorSnapshot _self;
  final $Res Function(_SourceEditorSnapshot) _then;

/// Create a copy of SourceEditorSnapshot
/// with the given fields replaced by the non-null parameter values.
@override @pragma('vm:prefer-inline') $Res call({Object? document = freezed,Object? busy = null,Object? error = freezed,Object? confirmDiscard = null,Object? groups = freezed,Object? variable = freezed,Object? variableComment = freezed,Object? maxLines = null,Object? keyboardRows = null,Object? assists = null,Object? migrationAvailable = null,Object? migrationRunning = null,Object? migrationError = freezed,Object? migrationReport = freezed,}) {
  return _then(_SourceEditorSnapshot(
document: freezed == document ? _self.document : document // ignore: cast_nullable_to_non_nullable
as SourceEditorDocument?,busy: null == busy ? _self.busy : busy // ignore: cast_nullable_to_non_nullable
as bool,error: freezed == error ? _self.error : error // ignore: cast_nullable_to_non_nullable
as String?,confirmDiscard: null == confirmDiscard ? _self.confirmDiscard : confirmDiscard // ignore: cast_nullable_to_non_nullable
as bool,groups: freezed == groups ? _self._groups : groups // ignore: cast_nullable_to_non_nullable
as List<String>?,variable: freezed == variable ? _self.variable : variable // ignore: cast_nullable_to_non_nullable
as String?,variableComment: freezed == variableComment ? _self.variableComment : variableComment // ignore: cast_nullable_to_non_nullable
as String?,maxLines: null == maxLines ? _self.maxLines : maxLines // ignore: cast_nullable_to_non_nullable
as int,keyboardRows: null == keyboardRows ? _self.keyboardRows : keyboardRows // ignore: cast_nullable_to_non_nullable
as int,assists: null == assists ? _self._assists : assists // ignore: cast_nullable_to_non_nullable
as List<Map<String, String>>,migrationAvailable: null == migrationAvailable ? _self.migrationAvailable : migrationAvailable // ignore: cast_nullable_to_non_nullable
as bool,migrationRunning: null == migrationRunning ? _self.migrationRunning : migrationRunning // ignore: cast_nullable_to_non_nullable
as bool,migrationError: freezed == migrationError ? _self.migrationError : migrationError // ignore: cast_nullable_to_non_nullable
as String?,migrationReport: freezed == migrationReport ? _self.migrationReport : migrationReport // ignore: cast_nullable_to_non_nullable
as SourceEditorMigration?,
  ));
}

/// Create a copy of SourceEditorSnapshot
/// with the given fields replaced by the non-null parameter values.
@override
@pragma('vm:prefer-inline')
$SourceEditorDocumentCopyWith<$Res>? get document {
    if (_self.document == null) {
    return null;
  }

  return $SourceEditorDocumentCopyWith<$Res>(_self.document!, (value) {
    return _then(_self.copyWith(document: value));
  });
}/// Create a copy of SourceEditorSnapshot
/// with the given fields replaced by the non-null parameter values.
@override
@pragma('vm:prefer-inline')
$SourceEditorMigrationCopyWith<$Res>? get migrationReport {
    if (_self.migrationReport == null) {
    return null;
  }

  return $SourceEditorMigrationCopyWith<$Res>(_self.migrationReport!, (value) {
    return _then(_self.copyWith(migrationReport: value));
  });
}
}

// dart format on
