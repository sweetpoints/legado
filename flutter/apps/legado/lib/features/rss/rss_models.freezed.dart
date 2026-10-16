// GENERATED CODE - DO NOT MODIFY BY HAND
// coverage:ignore-file
// ignore_for_file: type=lint, type=warning, deprecated_member_use, deprecated_member_use_from_same_package
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'rss_models.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// dart format off
T _$identity<T>(T value) => value;
/// @nodoc
mixin _$RssSource {

 String get url; String get name; Map<String, Object?> get original;
/// Create a copy of RssSource
/// with the given fields replaced by the non-null parameter values.
@JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
$RssSourceCopyWith<RssSource> get copyWith => _$RssSourceCopyWithImpl<RssSource>(this as RssSource, _$identity);



@override
bool operator ==(Object other) {
  final _this = this as RssSource;
  return identical(this, other) || (other.runtimeType == runtimeType&&other is RssSource&&(identical(other.url, _this.url) || other.url == _this.url)&&(identical(other.name, _this.name) || other.name == _this.name)&&const DeepCollectionEquality().equals(other.original, _this.original));
}


@override
int get hashCode {
  final _this = this as RssSource;
  return Object.hash(runtimeType,_this.url,_this.name,const DeepCollectionEquality().hash(_this.original));
}

@override
String toString() {
  final _this = this as RssSource;
  return 'RssSource(url: ${_this.url}, name: ${_this.name}, original: ${_this.original})';
}


}

/// @nodoc
abstract mixin class $RssSourceCopyWith<$Res>  {
  factory $RssSourceCopyWith(RssSource value, $Res Function(RssSource) _then) = _$RssSourceCopyWithImpl;
@useResult
$Res call({
 String url, String name, Map<String, Object?> original
});




}
/// @nodoc
class _$RssSourceCopyWithImpl<$Res>
    implements $RssSourceCopyWith<$Res> {
  _$RssSourceCopyWithImpl(this._self, this._then);

  final RssSource _self;
  final $Res Function(RssSource) _then;

/// Create a copy of RssSource
/// with the given fields replaced by the non-null parameter values.
@pragma('vm:prefer-inline') @override $Res call({Object? url = null,Object? name = null,Object? original = null,}) {
  return _then(RssSource(
url: null == url ? _self.url : url // ignore: cast_nullable_to_non_nullable
as String,name: null == name ? _self.name : name // ignore: cast_nullable_to_non_nullable
as String,original: null == original ? _self.original : original // ignore: cast_nullable_to_non_nullable
as Map<String, Object?>,
  ));
}

}


/// Adds pattern-matching-related methods to [RssSource].
extension RssSourcePatterns on RssSource {
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

@optionalTypeArgs TResult maybeMap<TResult extends Object?>(TResult Function( _RssSource value)?  $default,{required TResult orElse(),}){
final _that = this;
switch (_that) {
case _RssSource() when $default != null:
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

@optionalTypeArgs TResult map<TResult extends Object?>(TResult Function( _RssSource value)  $default,){
final _that = this;
switch (_that) {
case _RssSource():
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

@optionalTypeArgs TResult? mapOrNull<TResult extends Object?>(TResult? Function( _RssSource value)?  $default,){
final _that = this;
switch (_that) {
case _RssSource() when $default != null:
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

@optionalTypeArgs TResult maybeWhen<TResult extends Object?>(TResult Function( String url,  String name,  Map<String, Object?> original)?  $default,{required TResult orElse(),}) {final _that = this;
switch (_that) {
case _RssSource() when $default != null:
return $default(_that.url,_that.name,_that.original);case _:
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

@optionalTypeArgs TResult when<TResult extends Object?>(TResult Function( String url,  String name,  Map<String, Object?> original)  $default,) {final _that = this;
switch (_that) {
case _RssSource():
return $default(_that.url,_that.name,_that.original);case _:
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

@optionalTypeArgs TResult? whenOrNull<TResult extends Object?>(TResult? Function( String url,  String name,  Map<String, Object?> original)?  $default,) {final _that = this;
switch (_that) {
case _RssSource() when $default != null:
return $default(_that.url,_that.name,_that.original);case _:
  return null;

}
}

}

/// @nodoc


class _RssSource extends RssSource {
  const _RssSource({required this.url, required this.name,  Map<String, Object?> original = const <String, Object?>{}}): _original = original,super._();
  

@override final  String url;
@override final  String name;
 final  Map<String, Object?> _original;
@override@JsonKey() Map<String, Object?> get original {
  if (_original is EqualUnmodifiableMapView) return _original;
  // ignore: implicit_dynamic_type
  return EqualUnmodifiableMapView(_original);
}


/// Create a copy of RssSource
/// with the given fields replaced by the non-null parameter values.
@override @JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
_$RssSourceCopyWith<_RssSource> get copyWith => __$RssSourceCopyWithImpl<_RssSource>(this, _$identity);



@override
bool operator ==(Object other) {
    return identical(this, other) || (other.runtimeType == runtimeType&&other is _RssSource&&(identical(other.url, url) || other.url == url)&&(identical(other.name, name) || other.name == name)&&const DeepCollectionEquality().equals(other.original, _original));
}


@override
int get hashCode {
    return Object.hash(runtimeType,url,name,const DeepCollectionEquality().hash(_original));
}

@override
String toString() {
    return 'RssSource(url: $url, name: $name, original: $original)';
}


}

/// @nodoc
abstract mixin class _$RssSourceCopyWith<$Res> implements $RssSourceCopyWith<$Res> {
  factory _$RssSourceCopyWith(_RssSource value, $Res Function(_RssSource) _then) = __$RssSourceCopyWithImpl;
@override @useResult
$Res call({
 String url, String name, Map<String, Object?> original
});




}
/// @nodoc
class __$RssSourceCopyWithImpl<$Res>
    implements _$RssSourceCopyWith<$Res> {
  __$RssSourceCopyWithImpl(this._self, this._then);

  final _RssSource _self;
  final $Res Function(_RssSource) _then;

/// Create a copy of RssSource
/// with the given fields replaced by the non-null parameter values.
@override @pragma('vm:prefer-inline') $Res call({Object? url = null,Object? name = null,Object? original = null,}) {
  return _then(_RssSource(
url: null == url ? _self.url : url // ignore: cast_nullable_to_non_nullable
as String,name: null == name ? _self.name : name // ignore: cast_nullable_to_non_nullable
as String,original: null == original ? _self._original : original // ignore: cast_nullable_to_non_nullable
as Map<String, Object?>,
  ));
}


}

/// @nodoc
mixin _$RssArticle {

 String get id; String get title; String get link; String get content; String get published; bool get read; bool get starred; Map<String, Object?> get original;
/// Create a copy of RssArticle
/// with the given fields replaced by the non-null parameter values.
@JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
$RssArticleCopyWith<RssArticle> get copyWith => _$RssArticleCopyWithImpl<RssArticle>(this as RssArticle, _$identity);



@override
bool operator ==(Object other) {
  final _this = this as RssArticle;
  return identical(this, other) || (other.runtimeType == runtimeType&&other is RssArticle&&(identical(other.id, _this.id) || other.id == _this.id)&&(identical(other.title, _this.title) || other.title == _this.title)&&(identical(other.link, _this.link) || other.link == _this.link)&&(identical(other.content, _this.content) || other.content == _this.content)&&(identical(other.published, _this.published) || other.published == _this.published)&&(identical(other.read, _this.read) || other.read == _this.read)&&(identical(other.starred, _this.starred) || other.starred == _this.starred)&&const DeepCollectionEquality().equals(other.original, _this.original));
}


@override
int get hashCode {
  final _this = this as RssArticle;
  return Object.hash(runtimeType,_this.id,_this.title,_this.link,_this.content,_this.published,_this.read,_this.starred,const DeepCollectionEquality().hash(_this.original));
}

@override
String toString() {
  final _this = this as RssArticle;
  return 'RssArticle(id: ${_this.id}, title: ${_this.title}, link: ${_this.link}, content: ${_this.content}, published: ${_this.published}, read: ${_this.read}, starred: ${_this.starred}, original: ${_this.original})';
}


}

/// @nodoc
abstract mixin class $RssArticleCopyWith<$Res>  {
  factory $RssArticleCopyWith(RssArticle value, $Res Function(RssArticle) _then) = _$RssArticleCopyWithImpl;
@useResult
$Res call({
 String id, String title, String link, String content, String published, bool read, bool starred, Map<String, Object?> original
});




}
/// @nodoc
class _$RssArticleCopyWithImpl<$Res>
    implements $RssArticleCopyWith<$Res> {
  _$RssArticleCopyWithImpl(this._self, this._then);

  final RssArticle _self;
  final $Res Function(RssArticle) _then;

/// Create a copy of RssArticle
/// with the given fields replaced by the non-null parameter values.
@pragma('vm:prefer-inline') @override $Res call({Object? id = null,Object? title = null,Object? link = null,Object? content = null,Object? published = null,Object? read = null,Object? starred = null,Object? original = null,}) {
  return _then(RssArticle(
id: null == id ? _self.id : id // ignore: cast_nullable_to_non_nullable
as String,title: null == title ? _self.title : title // ignore: cast_nullable_to_non_nullable
as String,link: null == link ? _self.link : link // ignore: cast_nullable_to_non_nullable
as String,content: null == content ? _self.content : content // ignore: cast_nullable_to_non_nullable
as String,published: null == published ? _self.published : published // ignore: cast_nullable_to_non_nullable
as String,read: null == read ? _self.read : read // ignore: cast_nullable_to_non_nullable
as bool,starred: null == starred ? _self.starred : starred // ignore: cast_nullable_to_non_nullable
as bool,original: null == original ? _self.original : original // ignore: cast_nullable_to_non_nullable
as Map<String, Object?>,
  ));
}

}


/// Adds pattern-matching-related methods to [RssArticle].
extension RssArticlePatterns on RssArticle {
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

@optionalTypeArgs TResult maybeMap<TResult extends Object?>(TResult Function( _RssArticle value)?  $default,{required TResult orElse(),}){
final _that = this;
switch (_that) {
case _RssArticle() when $default != null:
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

@optionalTypeArgs TResult map<TResult extends Object?>(TResult Function( _RssArticle value)  $default,){
final _that = this;
switch (_that) {
case _RssArticle():
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

@optionalTypeArgs TResult? mapOrNull<TResult extends Object?>(TResult? Function( _RssArticle value)?  $default,){
final _that = this;
switch (_that) {
case _RssArticle() when $default != null:
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

@optionalTypeArgs TResult maybeWhen<TResult extends Object?>(TResult Function( String id,  String title,  String link,  String content,  String published,  bool read,  bool starred,  Map<String, Object?> original)?  $default,{required TResult orElse(),}) {final _that = this;
switch (_that) {
case _RssArticle() when $default != null:
return $default(_that.id,_that.title,_that.link,_that.content,_that.published,_that.read,_that.starred,_that.original);case _:
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

@optionalTypeArgs TResult when<TResult extends Object?>(TResult Function( String id,  String title,  String link,  String content,  String published,  bool read,  bool starred,  Map<String, Object?> original)  $default,) {final _that = this;
switch (_that) {
case _RssArticle():
return $default(_that.id,_that.title,_that.link,_that.content,_that.published,_that.read,_that.starred,_that.original);case _:
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

@optionalTypeArgs TResult? whenOrNull<TResult extends Object?>(TResult? Function( String id,  String title,  String link,  String content,  String published,  bool read,  bool starred,  Map<String, Object?> original)?  $default,) {final _that = this;
switch (_that) {
case _RssArticle() when $default != null:
return $default(_that.id,_that.title,_that.link,_that.content,_that.published,_that.read,_that.starred,_that.original);case _:
  return null;

}
}

}

/// @nodoc


class _RssArticle extends RssArticle {
  const _RssArticle({required this.id, required this.title, required this.link, required this.content, this.published = '', this.read = false, this.starred = false,  Map<String, Object?> original = const <String, Object?>{}}): _original = original,super._();
  

@override final  String id;
@override final  String title;
@override final  String link;
@override final  String content;
@override@JsonKey() final  String published;
@override@JsonKey() final  bool read;
@override@JsonKey() final  bool starred;
 final  Map<String, Object?> _original;
@override@JsonKey() Map<String, Object?> get original {
  if (_original is EqualUnmodifiableMapView) return _original;
  // ignore: implicit_dynamic_type
  return EqualUnmodifiableMapView(_original);
}


/// Create a copy of RssArticle
/// with the given fields replaced by the non-null parameter values.
@override @JsonKey(includeFromJson: false, includeToJson: false)
@pragma('vm:prefer-inline')
_$RssArticleCopyWith<_RssArticle> get copyWith => __$RssArticleCopyWithImpl<_RssArticle>(this, _$identity);



@override
bool operator ==(Object other) {
    return identical(this, other) || (other.runtimeType == runtimeType&&other is _RssArticle&&(identical(other.id, id) || other.id == id)&&(identical(other.title, title) || other.title == title)&&(identical(other.link, link) || other.link == link)&&(identical(other.content, content) || other.content == content)&&(identical(other.published, published) || other.published == published)&&(identical(other.read, read) || other.read == read)&&(identical(other.starred, starred) || other.starred == starred)&&const DeepCollectionEquality().equals(other.original, _original));
}


@override
int get hashCode {
    return Object.hash(runtimeType,id,title,link,content,published,read,starred,const DeepCollectionEquality().hash(_original));
}

@override
String toString() {
    return 'RssArticle(id: $id, title: $title, link: $link, content: $content, published: $published, read: $read, starred: $starred, original: $original)';
}


}

/// @nodoc
abstract mixin class _$RssArticleCopyWith<$Res> implements $RssArticleCopyWith<$Res> {
  factory _$RssArticleCopyWith(_RssArticle value, $Res Function(_RssArticle) _then) = __$RssArticleCopyWithImpl;
@override @useResult
$Res call({
 String id, String title, String link, String content, String published, bool read, bool starred, Map<String, Object?> original
});




}
/// @nodoc
class __$RssArticleCopyWithImpl<$Res>
    implements _$RssArticleCopyWith<$Res> {
  __$RssArticleCopyWithImpl(this._self, this._then);

  final _RssArticle _self;
  final $Res Function(_RssArticle) _then;

/// Create a copy of RssArticle
/// with the given fields replaced by the non-null parameter values.
@override @pragma('vm:prefer-inline') $Res call({Object? id = null,Object? title = null,Object? link = null,Object? content = null,Object? published = null,Object? read = null,Object? starred = null,Object? original = null,}) {
  return _then(_RssArticle(
id: null == id ? _self.id : id // ignore: cast_nullable_to_non_nullable
as String,title: null == title ? _self.title : title // ignore: cast_nullable_to_non_nullable
as String,link: null == link ? _self.link : link // ignore: cast_nullable_to_non_nullable
as String,content: null == content ? _self.content : content // ignore: cast_nullable_to_non_nullable
as String,published: null == published ? _self.published : published // ignore: cast_nullable_to_non_nullable
as String,read: null == read ? _self.read : read // ignore: cast_nullable_to_non_nullable
as bool,starred: null == starred ? _self.starred : starred // ignore: cast_nullable_to_non_nullable
as bool,original: null == original ? _self._original : original // ignore: cast_nullable_to_non_nullable
as Map<String, Object?>,
  ));
}


}

// dart format on
