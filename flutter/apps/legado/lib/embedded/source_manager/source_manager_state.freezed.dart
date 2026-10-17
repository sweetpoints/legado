// GENERATED CODE - DO NOT MODIFY BY HAND
// coverage:ignore-file
// ignore_for_file: type=lint, type=warning, deprecated_member_use, deprecated_member_use_from_same_package
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'source_manager_state.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// dart format off
T _$identity<T>(T value) => value;
/// @nodoc
mixin _$SourceManagerRow {

 String get url; String get name; String? get group; int get order; bool get enabled; bool get exploreEnabled; bool get hasExplore; bool get hasLogin; bool get hasJs; int get updated; int get response; int get weight; String get checkStatus; String get checkDetail; String get host;
/// Create a copy of SourceManagerRow
/// with the given fields replaced by the non-null parameter values.
@JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
$SourceManagerRowCopyWith<SourceManagerRow> get copyWith => _$SourceManagerRowCopyWithImpl<SourceManagerRow>(this as SourceManagerRow, _$identity);



@override
bool operator ==(Object other) {
  final _this = this as SourceManagerRow;
  return identical(this, other) || (other.runtimeType == runtimeType&&other is SourceManagerRow&&(identical(other.url, _this.url) || other.url == _this.url)&&(identical(other.name, _this.name) || other.name == _this.name)&&(identical(other.group, _this.group) || other.group == _this.group)&&(identical(other.order, _this.order) || other.order == _this.order)&&(identical(other.enabled, _this.enabled) || other.enabled == _this.enabled)&&(identical(other.exploreEnabled, _this.exploreEnabled) || other.exploreEnabled == _this.exploreEnabled)&&(identical(other.hasExplore, _this.hasExplore) || other.hasExplore == _this.hasExplore)&&(identical(other.hasLogin, _this.hasLogin) || other.hasLogin == _this.hasLogin)&&(identical(other.hasJs, _this.hasJs) || other.hasJs == _this.hasJs)&&(identical(other.updated, _this.updated) || other.updated == _this.updated)&&(identical(other.response, _this.response) || other.response == _this.response)&&(identical(other.weight, _this.weight) || other.weight == _this.weight)&&(identical(other.checkStatus, _this.checkStatus) || other.checkStatus == _this.checkStatus)&&(identical(other.checkDetail, _this.checkDetail) || other.checkDetail == _this.checkDetail)&&(identical(other.host, _this.host) || other.host == _this.host));
}


@override
int get hashCode {
  final _this = this as SourceManagerRow;
  return Object.hash(runtimeType,_this.url,_this.name,_this.group,_this.order,_this.enabled,_this.exploreEnabled,_this.hasExplore,_this.hasLogin,_this.hasJs,_this.updated,_this.response,_this.weight,_this.checkStatus,_this.checkDetail,_this.host);
}

@override
String toString() {
  final _this = this as SourceManagerRow;
  return 'SourceManagerRow(url: ${_this.url}, name: ${_this.name}, group: ${_this.group}, order: ${_this.order}, enabled: ${_this.enabled}, exploreEnabled: ${_this.exploreEnabled}, hasExplore: ${_this.hasExplore}, hasLogin: ${_this.hasLogin}, hasJs: ${_this.hasJs}, updated: ${_this.updated}, response: ${_this.response}, weight: ${_this.weight}, checkStatus: ${_this.checkStatus}, checkDetail: ${_this.checkDetail}, host: ${_this.host})';
}


}

/// @nodoc
abstract mixin class $SourceManagerRowCopyWith<$Res>  {
  factory $SourceManagerRowCopyWith(SourceManagerRow value, $Res Function(SourceManagerRow) _then) = _$SourceManagerRowCopyWithImpl;
@useResult
$Res call({
 String url, String name, String? group, int order, bool enabled, bool exploreEnabled, bool hasExplore, bool hasLogin, bool hasJs, int updated, int response, int weight, String checkStatus, String checkDetail, String host
});




}
/// @nodoc
class _$SourceManagerRowCopyWithImpl<$Res>
    implements $SourceManagerRowCopyWith<$Res> {
  _$SourceManagerRowCopyWithImpl(this._self, this._then);

  final SourceManagerRow _self;
  final $Res Function(SourceManagerRow) _then;

/// Create a copy of SourceManagerRow
/// with the given fields replaced by the non-null parameter values.
@pragma('vm:prefer-inline') @override $Res call({Object? url = null,Object? name = null,Object? group = freezed,Object? order = null,Object? enabled = null,Object? exploreEnabled = null,Object? hasExplore = null,Object? hasLogin = null,Object? hasJs = null,Object? updated = null,Object? response = null,Object? weight = null,Object? checkStatus = null,Object? checkDetail = null,Object? host = null,}) {
  return _then(SourceManagerRow(
url: null == url ? _self.url : url // ignore: cast_nullable_to_non_nullable
as String,name: null == name ? _self.name : name // ignore: cast_nullable_to_non_nullable
as String,group: freezed == group ? _self.group : group // ignore: cast_nullable_to_non_nullable
as String?,order: null == order ? _self.order : order // ignore: cast_nullable_to_non_nullable
as int,enabled: null == enabled ? _self.enabled : enabled // ignore: cast_nullable_to_non_nullable
as bool,exploreEnabled: null == exploreEnabled ? _self.exploreEnabled : exploreEnabled // ignore: cast_nullable_to_non_nullable
as bool,hasExplore: null == hasExplore ? _self.hasExplore : hasExplore // ignore: cast_nullable_to_non_nullable
as bool,hasLogin: null == hasLogin ? _self.hasLogin : hasLogin // ignore: cast_nullable_to_non_nullable
as bool,hasJs: null == hasJs ? _self.hasJs : hasJs // ignore: cast_nullable_to_non_nullable
as bool,updated: null == updated ? _self.updated : updated // ignore: cast_nullable_to_non_nullable
as int,response: null == response ? _self.response : response // ignore: cast_nullable_to_non_nullable
as int,weight: null == weight ? _self.weight : weight // ignore: cast_nullable_to_non_nullable
as int,checkStatus: null == checkStatus ? _self.checkStatus : checkStatus // ignore: cast_nullable_to_non_nullable
as String,checkDetail: null == checkDetail ? _self.checkDetail : checkDetail // ignore: cast_nullable_to_non_nullable
as String,host: null == host ? _self.host : host // ignore: cast_nullable_to_non_nullable
as String,
  ));
}

}


/// Adds pattern-matching-related methods to [SourceManagerRow].
extension SourceManagerRowPatterns on SourceManagerRow {
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

@optionalTypeArgs TResult maybeMap<TResult extends Object?>(TResult Function( _SourceManagerRow value)?  $default,{required TResult orElse(),}){
final _that = this;
switch (_that) {
case _SourceManagerRow() when $default != null:
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

@optionalTypeArgs TResult map<TResult extends Object?>(TResult Function( _SourceManagerRow value)  $default,){
final _that = this;
switch (_that) {
case _SourceManagerRow():
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

@optionalTypeArgs TResult? mapOrNull<TResult extends Object?>(TResult? Function( _SourceManagerRow value)?  $default,){
final _that = this;
switch (_that) {
case _SourceManagerRow() when $default != null:
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

@optionalTypeArgs TResult maybeWhen<TResult extends Object?>(TResult Function( String url,  String name,  String? group,  int order,  bool enabled,  bool exploreEnabled,  bool hasExplore,  bool hasLogin,  bool hasJs,  int updated,  int response,  int weight,  String checkStatus,  String checkDetail,  String host)?  $default,{required TResult orElse(),}) {final _that = this;
switch (_that) {
case _SourceManagerRow() when $default != null:
return $default(_that.url,_that.name,_that.group,_that.order,_that.enabled,_that.exploreEnabled,_that.hasExplore,_that.hasLogin,_that.hasJs,_that.updated,_that.response,_that.weight,_that.checkStatus,_that.checkDetail,_that.host);case _:
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

@optionalTypeArgs TResult when<TResult extends Object?>(TResult Function( String url,  String name,  String? group,  int order,  bool enabled,  bool exploreEnabled,  bool hasExplore,  bool hasLogin,  bool hasJs,  int updated,  int response,  int weight,  String checkStatus,  String checkDetail,  String host)  $default,) {final _that = this;
switch (_that) {
case _SourceManagerRow():
return $default(_that.url,_that.name,_that.group,_that.order,_that.enabled,_that.exploreEnabled,_that.hasExplore,_that.hasLogin,_that.hasJs,_that.updated,_that.response,_that.weight,_that.checkStatus,_that.checkDetail,_that.host);case _:
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

@optionalTypeArgs TResult? whenOrNull<TResult extends Object?>(TResult? Function( String url,  String name,  String? group,  int order,  bool enabled,  bool exploreEnabled,  bool hasExplore,  bool hasLogin,  bool hasJs,  int updated,  int response,  int weight,  String checkStatus,  String checkDetail,  String host)?  $default,) {final _that = this;
switch (_that) {
case _SourceManagerRow() when $default != null:
return $default(_that.url,_that.name,_that.group,_that.order,_that.enabled,_that.exploreEnabled,_that.hasExplore,_that.hasLogin,_that.hasJs,_that.updated,_that.response,_that.weight,_that.checkStatus,_that.checkDetail,_that.host);case _:
  return null;

}
}

}

/// @nodoc


class _SourceManagerRow extends SourceManagerRow {
  const _SourceManagerRow({required this.url, required this.name, this.group, this.order = 0, this.enabled = false, this.exploreEnabled = false, this.hasExplore = false, this.hasLogin = false, this.hasJs = false, this.updated = 0, this.response = 0, this.weight = 0, this.checkStatus = 'NEEDS_CHECK', this.checkDetail = '', this.host = '#'}): super._();


@override final  String url;
@override final  String name;
@override final  String? group;
@override@JsonKey() final  int order;
@override@JsonKey() final  bool enabled;
@override@JsonKey() final  bool exploreEnabled;
@override@JsonKey() final  bool hasExplore;
@override@JsonKey() final  bool hasLogin;
@override@JsonKey() final  bool hasJs;
@override@JsonKey() final  int updated;
@override@JsonKey() final  int response;
@override@JsonKey() final  int weight;
@override@JsonKey() final  String checkStatus;
@override@JsonKey() final  String checkDetail;
@override@JsonKey() final  String host;

/// Create a copy of SourceManagerRow
/// with the given fields replaced by the non-null parameter values.
@override @JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
_$SourceManagerRowCopyWith<_SourceManagerRow> get copyWith => __$SourceManagerRowCopyWithImpl<_SourceManagerRow>(this, _$identity);



@override
bool operator ==(Object other) {
    return identical(this, other) || (other.runtimeType == runtimeType&&other is _SourceManagerRow&&(identical(other.url, url) || other.url == url)&&(identical(other.name, name) || other.name == name)&&(identical(other.group, group) || other.group == group)&&(identical(other.order, order) || other.order == order)&&(identical(other.enabled, enabled) || other.enabled == enabled)&&(identical(other.exploreEnabled, exploreEnabled) || other.exploreEnabled == exploreEnabled)&&(identical(other.hasExplore, hasExplore) || other.hasExplore == hasExplore)&&(identical(other.hasLogin, hasLogin) || other.hasLogin == hasLogin)&&(identical(other.hasJs, hasJs) || other.hasJs == hasJs)&&(identical(other.updated, updated) || other.updated == updated)&&(identical(other.response, response) || other.response == response)&&(identical(other.weight, weight) || other.weight == weight)&&(identical(other.checkStatus, checkStatus) || other.checkStatus == checkStatus)&&(identical(other.checkDetail, checkDetail) || other.checkDetail == checkDetail)&&(identical(other.host, host) || other.host == host));
}


@override
int get hashCode {
    return Object.hash(runtimeType,url,name,group,order,enabled,exploreEnabled,hasExplore,hasLogin,hasJs,updated,response,weight,checkStatus,checkDetail,host);
}

@override
String toString() {
    return 'SourceManagerRow(url: $url, name: $name, group: $group, order: $order, enabled: $enabled, exploreEnabled: $exploreEnabled, hasExplore: $hasExplore, hasLogin: $hasLogin, hasJs: $hasJs, updated: $updated, response: $response, weight: $weight, checkStatus: $checkStatus, checkDetail: $checkDetail, host: $host)';
}


}

/// @nodoc
abstract mixin class _$SourceManagerRowCopyWith<$Res> implements $SourceManagerRowCopyWith<$Res> {
  factory _$SourceManagerRowCopyWith(_SourceManagerRow value, $Res Function(_SourceManagerRow) _then) = __$SourceManagerRowCopyWithImpl;
@override @useResult
$Res call({
 String url, String name, String? group, int order, bool enabled, bool exploreEnabled, bool hasExplore, bool hasLogin, bool hasJs, int updated, int response, int weight, String checkStatus, String checkDetail, String host
});




}
/// @nodoc
class __$SourceManagerRowCopyWithImpl<$Res>
    implements _$SourceManagerRowCopyWith<$Res> {
  __$SourceManagerRowCopyWithImpl(this._self, this._then);

  final _SourceManagerRow _self;
  final $Res Function(_SourceManagerRow) _then;

/// Create a copy of SourceManagerRow
/// with the given fields replaced by the non-null parameter values.
@override @pragma('vm:prefer-inline') $Res call({Object? url = null,Object? name = null,Object? group = freezed,Object? order = null,Object? enabled = null,Object? exploreEnabled = null,Object? hasExplore = null,Object? hasLogin = null,Object? hasJs = null,Object? updated = null,Object? response = null,Object? weight = null,Object? checkStatus = null,Object? checkDetail = null,Object? host = null,}) {
  return _then(_SourceManagerRow(
url: null == url ? _self.url : url // ignore: cast_nullable_to_non_nullable
as String,name: null == name ? _self.name : name // ignore: cast_nullable_to_non_nullable
as String,group: freezed == group ? _self.group : group // ignore: cast_nullable_to_non_nullable
as String?,order: null == order ? _self.order : order // ignore: cast_nullable_to_non_nullable
as int,enabled: null == enabled ? _self.enabled : enabled // ignore: cast_nullable_to_non_nullable
as bool,exploreEnabled: null == exploreEnabled ? _self.exploreEnabled : exploreEnabled // ignore: cast_nullable_to_non_nullable
as bool,hasExplore: null == hasExplore ? _self.hasExplore : hasExplore // ignore: cast_nullable_to_non_nullable
as bool,hasLogin: null == hasLogin ? _self.hasLogin : hasLogin // ignore: cast_nullable_to_non_nullable
as bool,hasJs: null == hasJs ? _self.hasJs : hasJs // ignore: cast_nullable_to_non_nullable
as bool,updated: null == updated ? _self.updated : updated // ignore: cast_nullable_to_non_nullable
as int,response: null == response ? _self.response : response // ignore: cast_nullable_to_non_nullable
as int,weight: null == weight ? _self.weight : weight // ignore: cast_nullable_to_non_nullable
as int,checkStatus: null == checkStatus ? _self.checkStatus : checkStatus // ignore: cast_nullable_to_non_nullable
as String,checkDetail: null == checkDetail ? _self.checkDetail : checkDetail // ignore: cast_nullable_to_non_nullable
as String,host: null == host ? _self.host : host // ignore: cast_nullable_to_non_nullable
as String,
  ));
}


}

/// @nodoc
mixin _$SourceManagerFeedback {

 String get url; String get summary; bool get canSharePassphrase; String? get passphrase;
/// Create a copy of SourceManagerFeedback
/// with the given fields replaced by the non-null parameter values.
@JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
$SourceManagerFeedbackCopyWith<SourceManagerFeedback> get copyWith => _$SourceManagerFeedbackCopyWithImpl<SourceManagerFeedback>(this as SourceManagerFeedback, _$identity);



@override
bool operator ==(Object other) {
  final _this = this as SourceManagerFeedback;
  return identical(this, other) || (other.runtimeType == runtimeType&&other is SourceManagerFeedback&&(identical(other.url, _this.url) || other.url == _this.url)&&(identical(other.summary, _this.summary) || other.summary == _this.summary)&&(identical(other.canSharePassphrase, _this.canSharePassphrase) || other.canSharePassphrase == _this.canSharePassphrase)&&(identical(other.passphrase, _this.passphrase) || other.passphrase == _this.passphrase));
}


@override
int get hashCode {
  final _this = this as SourceManagerFeedback;
  return Object.hash(runtimeType,_this.url,_this.summary,_this.canSharePassphrase,_this.passphrase);
}

@override
String toString() {
  final _this = this as SourceManagerFeedback;
  return 'SourceManagerFeedback(url: ${_this.url}, summary: ${_this.summary}, canSharePassphrase: ${_this.canSharePassphrase}, passphrase: ${_this.passphrase})';
}


}

/// @nodoc
abstract mixin class $SourceManagerFeedbackCopyWith<$Res>  {
  factory $SourceManagerFeedbackCopyWith(SourceManagerFeedback value, $Res Function(SourceManagerFeedback) _then) = _$SourceManagerFeedbackCopyWithImpl;
@useResult
$Res call({
 String url, String summary, bool canSharePassphrase, String? passphrase
});




}
/// @nodoc
class _$SourceManagerFeedbackCopyWithImpl<$Res>
    implements $SourceManagerFeedbackCopyWith<$Res> {
  _$SourceManagerFeedbackCopyWithImpl(this._self, this._then);

  final SourceManagerFeedback _self;
  final $Res Function(SourceManagerFeedback) _then;

/// Create a copy of SourceManagerFeedback
/// with the given fields replaced by the non-null parameter values.
@pragma('vm:prefer-inline') @override $Res call({Object? url = null,Object? summary = null,Object? canSharePassphrase = null,Object? passphrase = freezed,}) {
  return _then(SourceManagerFeedback(
url: null == url ? _self.url : url // ignore: cast_nullable_to_non_nullable
as String,summary: null == summary ? _self.summary : summary // ignore: cast_nullable_to_non_nullable
as String,canSharePassphrase: null == canSharePassphrase ? _self.canSharePassphrase : canSharePassphrase // ignore: cast_nullable_to_non_nullable
as bool,passphrase: freezed == passphrase ? _self.passphrase : passphrase // ignore: cast_nullable_to_non_nullable
as String?,
  ));
}

}


/// Adds pattern-matching-related methods to [SourceManagerFeedback].
extension SourceManagerFeedbackPatterns on SourceManagerFeedback {
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

@optionalTypeArgs TResult maybeMap<TResult extends Object?>(TResult Function( _SourceManagerFeedback value)?  $default,{required TResult orElse(),}){
final _that = this;
switch (_that) {
case _SourceManagerFeedback() when $default != null:
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

@optionalTypeArgs TResult map<TResult extends Object?>(TResult Function( _SourceManagerFeedback value)  $default,){
final _that = this;
switch (_that) {
case _SourceManagerFeedback():
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

@optionalTypeArgs TResult? mapOrNull<TResult extends Object?>(TResult? Function( _SourceManagerFeedback value)?  $default,){
final _that = this;
switch (_that) {
case _SourceManagerFeedback() when $default != null:
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

@optionalTypeArgs TResult maybeWhen<TResult extends Object?>(TResult Function( String url,  String summary,  bool canSharePassphrase,  String? passphrase)?  $default,{required TResult orElse(),}) {final _that = this;
switch (_that) {
case _SourceManagerFeedback() when $default != null:
return $default(_that.url,_that.summary,_that.canSharePassphrase,_that.passphrase);case _:
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

@optionalTypeArgs TResult when<TResult extends Object?>(TResult Function( String url,  String summary,  bool canSharePassphrase,  String? passphrase)  $default,) {final _that = this;
switch (_that) {
case _SourceManagerFeedback():
return $default(_that.url,_that.summary,_that.canSharePassphrase,_that.passphrase);case _:
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

@optionalTypeArgs TResult? whenOrNull<TResult extends Object?>(TResult? Function( String url,  String summary,  bool canSharePassphrase,  String? passphrase)?  $default,) {final _that = this;
switch (_that) {
case _SourceManagerFeedback() when $default != null:
return $default(_that.url,_that.summary,_that.canSharePassphrase,_that.passphrase);case _:
  return null;

}
}

}

/// @nodoc


class _SourceManagerFeedback extends SourceManagerFeedback {
  const _SourceManagerFeedback({required this.url, required this.summary, required this.canSharePassphrase, this.passphrase}): super._();


@override final  String url;
@override final  String summary;
@override final  bool canSharePassphrase;
@override final  String? passphrase;

/// Create a copy of SourceManagerFeedback
/// with the given fields replaced by the non-null parameter values.
@override @JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
_$SourceManagerFeedbackCopyWith<_SourceManagerFeedback> get copyWith => __$SourceManagerFeedbackCopyWithImpl<_SourceManagerFeedback>(this, _$identity);



@override
bool operator ==(Object other) {
    return identical(this, other) || (other.runtimeType == runtimeType&&other is _SourceManagerFeedback&&(identical(other.url, url) || other.url == url)&&(identical(other.summary, summary) || other.summary == summary)&&(identical(other.canSharePassphrase, canSharePassphrase) || other.canSharePassphrase == canSharePassphrase)&&(identical(other.passphrase, passphrase) || other.passphrase == passphrase));
}


@override
int get hashCode {
    return Object.hash(runtimeType,url,summary,canSharePassphrase,passphrase);
}

@override
String toString() {
    return 'SourceManagerFeedback(url: $url, summary: $summary, canSharePassphrase: $canSharePassphrase, passphrase: $passphrase)';
}


}

/// @nodoc
abstract mixin class _$SourceManagerFeedbackCopyWith<$Res> implements $SourceManagerFeedbackCopyWith<$Res> {
  factory _$SourceManagerFeedbackCopyWith(_SourceManagerFeedback value, $Res Function(_SourceManagerFeedback) _then) = __$SourceManagerFeedbackCopyWithImpl;
@override @useResult
$Res call({
 String url, String summary, bool canSharePassphrase, String? passphrase
});




}
/// @nodoc
class __$SourceManagerFeedbackCopyWithImpl<$Res>
    implements _$SourceManagerFeedbackCopyWith<$Res> {
  __$SourceManagerFeedbackCopyWithImpl(this._self, this._then);

  final _SourceManagerFeedback _self;
  final $Res Function(_SourceManagerFeedback) _then;

/// Create a copy of SourceManagerFeedback
/// with the given fields replaced by the non-null parameter values.
@override @pragma('vm:prefer-inline') $Res call({Object? url = null,Object? summary = null,Object? canSharePassphrase = null,Object? passphrase = freezed,}) {
  return _then(_SourceManagerFeedback(
url: null == url ? _self.url : url // ignore: cast_nullable_to_non_nullable
as String,summary: null == summary ? _self.summary : summary // ignore: cast_nullable_to_non_nullable
as String,canSharePassphrase: null == canSharePassphrase ? _self.canSharePassphrase : canSharePassphrase // ignore: cast_nullable_to_non_nullable
as bool,passphrase: freezed == passphrase ? _self.passphrase : passphrase // ignore: cast_nullable_to_non_nullable
as String?,
  ));
}


}

/// @nodoc
mixin _$SourceManagerSnapshot {

 String get query; List<SourceManagerRow> get rows; List<String> get groups; Set<String> get selected; Map<String, int> get counts; String get sort; bool get ascending; bool get byDomain; bool get showStatus; String get status; bool get busy; bool get loading; String? get error; String? get checkMessage; Map<String, String> get debugMessages; String? get dialog; String get draft; bool get blockNavigation; String? get draggingKey; List<String> get importHistory; SourceManagerFeedback? get feedback; bool get canMove;
/// Create a copy of SourceManagerSnapshot
/// with the given fields replaced by the non-null parameter values.
@JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
$SourceManagerSnapshotCopyWith<SourceManagerSnapshot> get copyWith => _$SourceManagerSnapshotCopyWithImpl<SourceManagerSnapshot>(this as SourceManagerSnapshot, _$identity);



@override
bool operator ==(Object other) {
  final _this = this as SourceManagerSnapshot;
  return identical(this, other) || (other.runtimeType == runtimeType&&other is SourceManagerSnapshot&&(identical(other.query, _this.query) || other.query == _this.query)&&const DeepCollectionEquality().equals(other.rows, _this.rows)&&const DeepCollectionEquality().equals(other.groups, _this.groups)&&const DeepCollectionEquality().equals(other.selected, _this.selected)&&const DeepCollectionEquality().equals(other.counts, _this.counts)&&(identical(other.sort, _this.sort) || other.sort == _this.sort)&&(identical(other.ascending, _this.ascending) || other.ascending == _this.ascending)&&(identical(other.byDomain, _this.byDomain) || other.byDomain == _this.byDomain)&&(identical(other.showStatus, _this.showStatus) || other.showStatus == _this.showStatus)&&(identical(other.status, _this.status) || other.status == _this.status)&&(identical(other.busy, _this.busy) || other.busy == _this.busy)&&(identical(other.loading, _this.loading) || other.loading == _this.loading)&&(identical(other.error, _this.error) || other.error == _this.error)&&(identical(other.checkMessage, _this.checkMessage) || other.checkMessage == _this.checkMessage)&&const DeepCollectionEquality().equals(other.debugMessages, _this.debugMessages)&&(identical(other.dialog, _this.dialog) || other.dialog == _this.dialog)&&(identical(other.draft, _this.draft) || other.draft == _this.draft)&&(identical(other.blockNavigation, _this.blockNavigation) || other.blockNavigation == _this.blockNavigation)&&(identical(other.draggingKey, _this.draggingKey) || other.draggingKey == _this.draggingKey)&&const DeepCollectionEquality().equals(other.importHistory, _this.importHistory)&&(identical(other.feedback, _this.feedback) || other.feedback == _this.feedback)&&(identical(other.canMove, _this.canMove) || other.canMove == _this.canMove));
}


@override
int get hashCode {
  final _this = this as SourceManagerSnapshot;
  return Object.hashAll([runtimeType,_this.query,const DeepCollectionEquality().hash(_this.rows),const DeepCollectionEquality().hash(_this.groups),const DeepCollectionEquality().hash(_this.selected),const DeepCollectionEquality().hash(_this.counts),_this.sort,_this.ascending,_this.byDomain,_this.showStatus,_this.status,_this.busy,_this.loading,_this.error,_this.checkMessage,const DeepCollectionEquality().hash(_this.debugMessages),_this.dialog,_this.draft,_this.blockNavigation,_this.draggingKey,const DeepCollectionEquality().hash(_this.importHistory),_this.feedback,_this.canMove]);
}

@override
String toString() {
  final _this = this as SourceManagerSnapshot;
  return 'SourceManagerSnapshot(query: ${_this.query}, rows: ${_this.rows}, groups: ${_this.groups}, selected: ${_this.selected}, counts: ${_this.counts}, sort: ${_this.sort}, ascending: ${_this.ascending}, byDomain: ${_this.byDomain}, showStatus: ${_this.showStatus}, status: ${_this.status}, busy: ${_this.busy}, loading: ${_this.loading}, error: ${_this.error}, checkMessage: ${_this.checkMessage}, debugMessages: ${_this.debugMessages}, dialog: ${_this.dialog}, draft: ${_this.draft}, blockNavigation: ${_this.blockNavigation}, draggingKey: ${_this.draggingKey}, importHistory: ${_this.importHistory}, feedback: ${_this.feedback}, canMove: ${_this.canMove})';
}


}

/// @nodoc
abstract mixin class $SourceManagerSnapshotCopyWith<$Res>  {
  factory $SourceManagerSnapshotCopyWith(SourceManagerSnapshot value, $Res Function(SourceManagerSnapshot) _then) = _$SourceManagerSnapshotCopyWithImpl;
@useResult
$Res call({
 String query, List<SourceManagerRow> rows, List<String> groups, Set<String> selected, Map<String, int> counts, String sort, bool ascending, bool byDomain, bool showStatus, String status, bool busy, bool loading, String? error, String? checkMessage, Map<String, String> debugMessages, String? dialog, String draft, bool blockNavigation, String? draggingKey, List<String> importHistory, SourceManagerFeedback? feedback, bool canMove
});


$SourceManagerFeedbackCopyWith<$Res>? get feedback;

}
/// @nodoc
class _$SourceManagerSnapshotCopyWithImpl<$Res>
    implements $SourceManagerSnapshotCopyWith<$Res> {
  _$SourceManagerSnapshotCopyWithImpl(this._self, this._then);

  final SourceManagerSnapshot _self;
  final $Res Function(SourceManagerSnapshot) _then;

/// Create a copy of SourceManagerSnapshot
/// with the given fields replaced by the non-null parameter values.
@pragma('vm:prefer-inline') @override $Res call({Object? query = null,Object? rows = null,Object? groups = null,Object? selected = null,Object? counts = null,Object? sort = null,Object? ascending = null,Object? byDomain = null,Object? showStatus = null,Object? status = null,Object? busy = null,Object? loading = null,Object? error = freezed,Object? checkMessage = freezed,Object? debugMessages = null,Object? dialog = freezed,Object? draft = null,Object? blockNavigation = null,Object? draggingKey = freezed,Object? importHistory = null,Object? feedback = freezed,Object? canMove = null,}) {
  return _then(SourceManagerSnapshot(
query: null == query ? _self.query : query // ignore: cast_nullable_to_non_nullable
as String,rows: null == rows ? _self.rows : rows // ignore: cast_nullable_to_non_nullable
as List<SourceManagerRow>,groups: null == groups ? _self.groups : groups // ignore: cast_nullable_to_non_nullable
as List<String>,selected: null == selected ? _self.selected : selected // ignore: cast_nullable_to_non_nullable
as Set<String>,counts: null == counts ? _self.counts : counts // ignore: cast_nullable_to_non_nullable
as Map<String, int>,sort: null == sort ? _self.sort : sort // ignore: cast_nullable_to_non_nullable
as String,ascending: null == ascending ? _self.ascending : ascending // ignore: cast_nullable_to_non_nullable
as bool,byDomain: null == byDomain ? _self.byDomain : byDomain // ignore: cast_nullable_to_non_nullable
as bool,showStatus: null == showStatus ? _self.showStatus : showStatus // ignore: cast_nullable_to_non_nullable
as bool,status: null == status ? _self.status : status // ignore: cast_nullable_to_non_nullable
as String,busy: null == busy ? _self.busy : busy // ignore: cast_nullable_to_non_nullable
as bool,loading: null == loading ? _self.loading : loading // ignore: cast_nullable_to_non_nullable
as bool,error: freezed == error ? _self.error : error // ignore: cast_nullable_to_non_nullable
as String?,checkMessage: freezed == checkMessage ? _self.checkMessage : checkMessage // ignore: cast_nullable_to_non_nullable
as String?,debugMessages: null == debugMessages ? _self.debugMessages : debugMessages // ignore: cast_nullable_to_non_nullable
as Map<String, String>,dialog: freezed == dialog ? _self.dialog : dialog // ignore: cast_nullable_to_non_nullable
as String?,draft: null == draft ? _self.draft : draft // ignore: cast_nullable_to_non_nullable
as String,blockNavigation: null == blockNavigation ? _self.blockNavigation : blockNavigation // ignore: cast_nullable_to_non_nullable
as bool,draggingKey: freezed == draggingKey ? _self.draggingKey : draggingKey // ignore: cast_nullable_to_non_nullable
as String?,importHistory: null == importHistory ? _self.importHistory : importHistory // ignore: cast_nullable_to_non_nullable
as List<String>,feedback: freezed == feedback ? _self.feedback : feedback // ignore: cast_nullable_to_non_nullable
as SourceManagerFeedback?,canMove: null == canMove ? _self.canMove : canMove // ignore: cast_nullable_to_non_nullable
as bool,
  ));
}
/// Create a copy of SourceManagerSnapshot
/// with the given fields replaced by the non-null parameter values.
@override
@pragma('vm:prefer-inline')
$SourceManagerFeedbackCopyWith<$Res>? get feedback {
    if (_self.feedback == null) {
    return null;
  }

  return $SourceManagerFeedbackCopyWith<$Res>(_self.feedback!, (value) {
    return _then(_self.copyWith(feedback: value));
  });
}
}


/// Adds pattern-matching-related methods to [SourceManagerSnapshot].
extension SourceManagerSnapshotPatterns on SourceManagerSnapshot {
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

@optionalTypeArgs TResult maybeMap<TResult extends Object?>(TResult Function( _SourceManagerSnapshot value)?  $default,{required TResult orElse(),}){
final _that = this;
switch (_that) {
case _SourceManagerSnapshot() when $default != null:
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

@optionalTypeArgs TResult map<TResult extends Object?>(TResult Function( _SourceManagerSnapshot value)  $default,){
final _that = this;
switch (_that) {
case _SourceManagerSnapshot():
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

@optionalTypeArgs TResult? mapOrNull<TResult extends Object?>(TResult? Function( _SourceManagerSnapshot value)?  $default,){
final _that = this;
switch (_that) {
case _SourceManagerSnapshot() when $default != null:
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

@optionalTypeArgs TResult maybeWhen<TResult extends Object?>(TResult Function( String query,  List<SourceManagerRow> rows,  List<String> groups,  Set<String> selected,  Map<String, int> counts,  String sort,  bool ascending,  bool byDomain,  bool showStatus,  String status,  bool busy,  bool loading,  String? error,  String? checkMessage,  Map<String, String> debugMessages,  String? dialog,  String draft,  bool blockNavigation,  String? draggingKey,  List<String> importHistory,  SourceManagerFeedback? feedback,  bool canMove)?  $default,{required TResult orElse(),}) {final _that = this;
switch (_that) {
case _SourceManagerSnapshot() when $default != null:
return $default(_that.query,_that.rows,_that.groups,_that.selected,_that.counts,_that.sort,_that.ascending,_that.byDomain,_that.showStatus,_that.status,_that.busy,_that.loading,_that.error,_that.checkMessage,_that.debugMessages,_that.dialog,_that.draft,_that.blockNavigation,_that.draggingKey,_that.importHistory,_that.feedback,_that.canMove);case _:
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

@optionalTypeArgs TResult when<TResult extends Object?>(TResult Function( String query,  List<SourceManagerRow> rows,  List<String> groups,  Set<String> selected,  Map<String, int> counts,  String sort,  bool ascending,  bool byDomain,  bool showStatus,  String status,  bool busy,  bool loading,  String? error,  String? checkMessage,  Map<String, String> debugMessages,  String? dialog,  String draft,  bool blockNavigation,  String? draggingKey,  List<String> importHistory,  SourceManagerFeedback? feedback,  bool canMove)  $default,) {final _that = this;
switch (_that) {
case _SourceManagerSnapshot():
return $default(_that.query,_that.rows,_that.groups,_that.selected,_that.counts,_that.sort,_that.ascending,_that.byDomain,_that.showStatus,_that.status,_that.busy,_that.loading,_that.error,_that.checkMessage,_that.debugMessages,_that.dialog,_that.draft,_that.blockNavigation,_that.draggingKey,_that.importHistory,_that.feedback,_that.canMove);case _:
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

@optionalTypeArgs TResult? whenOrNull<TResult extends Object?>(TResult? Function( String query,  List<SourceManagerRow> rows,  List<String> groups,  Set<String> selected,  Map<String, int> counts,  String sort,  bool ascending,  bool byDomain,  bool showStatus,  String status,  bool busy,  bool loading,  String? error,  String? checkMessage,  Map<String, String> debugMessages,  String? dialog,  String draft,  bool blockNavigation,  String? draggingKey,  List<String> importHistory,  SourceManagerFeedback? feedback,  bool canMove)?  $default,) {final _that = this;
switch (_that) {
case _SourceManagerSnapshot() when $default != null:
return $default(_that.query,_that.rows,_that.groups,_that.selected,_that.counts,_that.sort,_that.ascending,_that.byDomain,_that.showStatus,_that.status,_that.busy,_that.loading,_that.error,_that.checkMessage,_that.debugMessages,_that.dialog,_that.draft,_that.blockNavigation,_that.draggingKey,_that.importHistory,_that.feedback,_that.canMove);case _:
  return null;

}
}

}

/// @nodoc


class _SourceManagerSnapshot extends SourceManagerSnapshot {
  const _SourceManagerSnapshot({this.query = '',  List<SourceManagerRow> rows = const [],  List<String> groups = const [],  Set<String> selected = const {},  Map<String, int> counts = const {}, this.sort = 'Default', this.ascending = true, this.byDomain = false, this.showStatus = false, this.status = '', this.busy = false, this.loading = true, this.error, this.checkMessage,  Map<String, String> debugMessages = const {}, this.dialog, this.draft = '', this.blockNavigation = false, this.draggingKey,  List<String> importHistory = const [], this.feedback, this.canMove = false}): _rows = rows,_groups = groups,_selected = selected,_counts = counts,_debugMessages = debugMessages,_importHistory = importHistory,super._();


@override@JsonKey() final  String query;
 final  List<SourceManagerRow> _rows;
@override@JsonKey() List<SourceManagerRow> get rows {
  if (_rows is EqualUnmodifiableListView) return _rows;
  // ignore: implicit_dynamic_type
  return EqualUnmodifiableListView(_rows);
}

 final  List<String> _groups;
@override@JsonKey() List<String> get groups {
  if (_groups is EqualUnmodifiableListView) return _groups;
  // ignore: implicit_dynamic_type
  return EqualUnmodifiableListView(_groups);
}

 final  Set<String> _selected;
@override@JsonKey() Set<String> get selected {
  if (_selected is EqualUnmodifiableSetView) return _selected;
  // ignore: implicit_dynamic_type
  return EqualUnmodifiableSetView(_selected);
}

 final  Map<String, int> _counts;
@override@JsonKey() Map<String, int> get counts {
  if (_counts is EqualUnmodifiableMapView) return _counts;
  // ignore: implicit_dynamic_type
  return EqualUnmodifiableMapView(_counts);
}

@override@JsonKey() final  String sort;
@override@JsonKey() final  bool ascending;
@override@JsonKey() final  bool byDomain;
@override@JsonKey() final  bool showStatus;
@override@JsonKey() final  String status;
@override@JsonKey() final  bool busy;
@override@JsonKey() final  bool loading;
@override final  String? error;
@override final  String? checkMessage;
 final  Map<String, String> _debugMessages;
@override@JsonKey() Map<String, String> get debugMessages {
  if (_debugMessages is EqualUnmodifiableMapView) return _debugMessages;
  // ignore: implicit_dynamic_type
  return EqualUnmodifiableMapView(_debugMessages);
}

@override final  String? dialog;
@override@JsonKey() final  String draft;
@override@JsonKey() final  bool blockNavigation;
@override final  String? draggingKey;
 final  List<String> _importHistory;
@override@JsonKey() List<String> get importHistory {
  if (_importHistory is EqualUnmodifiableListView) return _importHistory;
  // ignore: implicit_dynamic_type
  return EqualUnmodifiableListView(_importHistory);
}

@override final  SourceManagerFeedback? feedback;
@override@JsonKey() final  bool canMove;

/// Create a copy of SourceManagerSnapshot
/// with the given fields replaced by the non-null parameter values.
@override @JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
_$SourceManagerSnapshotCopyWith<_SourceManagerSnapshot> get copyWith => __$SourceManagerSnapshotCopyWithImpl<_SourceManagerSnapshot>(this, _$identity);



@override
bool operator ==(Object other) {
    return identical(this, other) || (other.runtimeType == runtimeType&&other is _SourceManagerSnapshot&&(identical(other.query, query) || other.query == query)&&const DeepCollectionEquality().equals(other.rows, _rows)&&const DeepCollectionEquality().equals(other.groups, _groups)&&const DeepCollectionEquality().equals(other.selected, _selected)&&const DeepCollectionEquality().equals(other.counts, _counts)&&(identical(other.sort, sort) || other.sort == sort)&&(identical(other.ascending, ascending) || other.ascending == ascending)&&(identical(other.byDomain, byDomain) || other.byDomain == byDomain)&&(identical(other.showStatus, showStatus) || other.showStatus == showStatus)&&(identical(other.status, status) || other.status == status)&&(identical(other.busy, busy) || other.busy == busy)&&(identical(other.loading, loading) || other.loading == loading)&&(identical(other.error, error) || other.error == error)&&(identical(other.checkMessage, checkMessage) || other.checkMessage == checkMessage)&&const DeepCollectionEquality().equals(other.debugMessages, _debugMessages)&&(identical(other.dialog, dialog) || other.dialog == dialog)&&(identical(other.draft, draft) || other.draft == draft)&&(identical(other.blockNavigation, blockNavigation) || other.blockNavigation == blockNavigation)&&(identical(other.draggingKey, draggingKey) || other.draggingKey == draggingKey)&&const DeepCollectionEquality().equals(other.importHistory, _importHistory)&&(identical(other.feedback, feedback) || other.feedback == feedback)&&(identical(other.canMove, canMove) || other.canMove == canMove));
}


@override
int get hashCode {
    return Object.hashAll([runtimeType,query,const DeepCollectionEquality().hash(_rows),const DeepCollectionEquality().hash(_groups),const DeepCollectionEquality().hash(_selected),const DeepCollectionEquality().hash(_counts),sort,ascending,byDomain,showStatus,status,busy,loading,error,checkMessage,const DeepCollectionEquality().hash(_debugMessages),dialog,draft,blockNavigation,draggingKey,const DeepCollectionEquality().hash(_importHistory),feedback,canMove]);
}

@override
String toString() {
    return 'SourceManagerSnapshot(query: $query, rows: $rows, groups: $groups, selected: $selected, counts: $counts, sort: $sort, ascending: $ascending, byDomain: $byDomain, showStatus: $showStatus, status: $status, busy: $busy, loading: $loading, error: $error, checkMessage: $checkMessage, debugMessages: $debugMessages, dialog: $dialog, draft: $draft, blockNavigation: $blockNavigation, draggingKey: $draggingKey, importHistory: $importHistory, feedback: $feedback, canMove: $canMove)';
}


}

/// @nodoc
abstract mixin class _$SourceManagerSnapshotCopyWith<$Res> implements $SourceManagerSnapshotCopyWith<$Res> {
  factory _$SourceManagerSnapshotCopyWith(_SourceManagerSnapshot value, $Res Function(_SourceManagerSnapshot) _then) = __$SourceManagerSnapshotCopyWithImpl;
@override @useResult
$Res call({
 String query, List<SourceManagerRow> rows, List<String> groups, Set<String> selected, Map<String, int> counts, String sort, bool ascending, bool byDomain, bool showStatus, String status, bool busy, bool loading, String? error, String? checkMessage, Map<String, String> debugMessages, String? dialog, String draft, bool blockNavigation, String? draggingKey, List<String> importHistory, SourceManagerFeedback? feedback, bool canMove
});


@override $SourceManagerFeedbackCopyWith<$Res>? get feedback;

}
/// @nodoc
class __$SourceManagerSnapshotCopyWithImpl<$Res>
    implements _$SourceManagerSnapshotCopyWith<$Res> {
  __$SourceManagerSnapshotCopyWithImpl(this._self, this._then);

  final _SourceManagerSnapshot _self;
  final $Res Function(_SourceManagerSnapshot) _then;

/// Create a copy of SourceManagerSnapshot
/// with the given fields replaced by the non-null parameter values.
@override @pragma('vm:prefer-inline') $Res call({Object? query = null,Object? rows = null,Object? groups = null,Object? selected = null,Object? counts = null,Object? sort = null,Object? ascending = null,Object? byDomain = null,Object? showStatus = null,Object? status = null,Object? busy = null,Object? loading = null,Object? error = freezed,Object? checkMessage = freezed,Object? debugMessages = null,Object? dialog = freezed,Object? draft = null,Object? blockNavigation = null,Object? draggingKey = freezed,Object? importHistory = null,Object? feedback = freezed,Object? canMove = null,}) {
  return _then(_SourceManagerSnapshot(
query: null == query ? _self.query : query // ignore: cast_nullable_to_non_nullable
as String,rows: null == rows ? _self._rows : rows // ignore: cast_nullable_to_non_nullable
as List<SourceManagerRow>,groups: null == groups ? _self._groups : groups // ignore: cast_nullable_to_non_nullable
as List<String>,selected: null == selected ? _self._selected : selected // ignore: cast_nullable_to_non_nullable
as Set<String>,counts: null == counts ? _self._counts : counts // ignore: cast_nullable_to_non_nullable
as Map<String, int>,sort: null == sort ? _self.sort : sort // ignore: cast_nullable_to_non_nullable
as String,ascending: null == ascending ? _self.ascending : ascending // ignore: cast_nullable_to_non_nullable
as bool,byDomain: null == byDomain ? _self.byDomain : byDomain // ignore: cast_nullable_to_non_nullable
as bool,showStatus: null == showStatus ? _self.showStatus : showStatus // ignore: cast_nullable_to_non_nullable
as bool,status: null == status ? _self.status : status // ignore: cast_nullable_to_non_nullable
as String,busy: null == busy ? _self.busy : busy // ignore: cast_nullable_to_non_nullable
as bool,loading: null == loading ? _self.loading : loading // ignore: cast_nullable_to_non_nullable
as bool,error: freezed == error ? _self.error : error // ignore: cast_nullable_to_non_nullable
as String?,checkMessage: freezed == checkMessage ? _self.checkMessage : checkMessage // ignore: cast_nullable_to_non_nullable
as String?,debugMessages: null == debugMessages ? _self._debugMessages : debugMessages // ignore: cast_nullable_to_non_nullable
as Map<String, String>,dialog: freezed == dialog ? _self.dialog : dialog // ignore: cast_nullable_to_non_nullable
as String?,draft: null == draft ? _self.draft : draft // ignore: cast_nullable_to_non_nullable
as String,blockNavigation: null == blockNavigation ? _self.blockNavigation : blockNavigation // ignore: cast_nullable_to_non_nullable
as bool,draggingKey: freezed == draggingKey ? _self.draggingKey : draggingKey // ignore: cast_nullable_to_non_nullable
as String?,importHistory: null == importHistory ? _self._importHistory : importHistory // ignore: cast_nullable_to_non_nullable
as List<String>,feedback: freezed == feedback ? _self.feedback : feedback // ignore: cast_nullable_to_non_nullable
as SourceManagerFeedback?,canMove: null == canMove ? _self.canMove : canMove // ignore: cast_nullable_to_non_nullable
as bool,
  ));
}

/// Create a copy of SourceManagerSnapshot
/// with the given fields replaced by the non-null parameter values.
@override
@pragma('vm:prefer-inline')
$SourceManagerFeedbackCopyWith<$Res>? get feedback {
    if (_self.feedback == null) {
    return null;
  }

  return $SourceManagerFeedbackCopyWith<$Res>(_self.feedback!, (value) {
    return _then(_self.copyWith(feedback: value));
  });
}
}

// dart format on
