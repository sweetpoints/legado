import 'package:freezed_annotation/freezed_annotation.dart';

part 'preferences.freezed.dart';

@freezed
abstract class AppPreferences with _$AppPreferences {
  const factory AppPreferences({
    @Default('') String localeTag,
    @Default('system') String themeTag,
  }) = _AppPreferences;
}
