// ignore: unused_import
import 'package:intl/intl.dart' as intl;

import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppLocalizationsEn extends AppLocalizations {
  AppLocalizationsEn([String locale = 'en']) : super(locale);

  @override
  String get appTitle => 'Legado';

  @override
  String get bookshelfTitle => 'Bookshelf';

  @override
  String get exploreTitle => 'Explore';

  @override
  String get rssTitle => 'Feeds';

  @override
  String get settingsTitle => 'Settings';

  @override
  String get searchTitle => 'Search books';

  @override
  String get sourcesTitle => 'Book sources';

  @override
  String get backupTitle => 'Backup and restore';

  @override
  String get importText => 'Import TXT book';

  @override
  String get emptyShelf => 'Your bookshelf is empty';

  @override
  String get emptyShelfHint =>
      'Import a local book or search your book sources.';

  @override
  String get retry => 'Retry';

  @override
  String get cancel => 'Cancel';

  @override
  String get confirm => 'Confirm';

  @override
  String get close => 'Close';

  @override
  String get delete => 'Delete';

  @override
  String get save => 'Save';

  @override
  String get loading => 'Loading…';

  @override
  String get operationFailed => 'The operation failed. Please try again.';

  @override
  String get startupFailed =>
      'Unable to open the library. Your original data has not been deleted.';

  @override
  String get bookRemoved => 'Book removed';

  @override
  String get undo => 'Undo';

  @override
  String get deleteBookTitle => 'Remove this book?';

  @override
  String get deleteBookMessage =>
      'The book and its cached chapters will be removed from this library.';

  @override
  String get importSucceeded => 'Book imported';

  @override
  String get languageTitle => 'Language';

  @override
  String get systemLanguage => 'Follow system';

  @override
  String get chineseLanguage => '简体中文';

  @override
  String get englishLanguage => 'English';

  @override
  String get appearanceTitle => 'Appearance';

  @override
  String get systemTheme => 'Follow system';

  @override
  String get lightTheme => 'Light';

  @override
  String get darkTheme => 'Dark';

  @override
  String get restoreZip => 'Restore ZIP backup';

  @override
  String get exportZip => 'Export ZIP backup';

  @override
  String get restoreTitle => 'Restore this backup?';

  @override
  String get restoreMessage =>
      'Present backup collections will replace their matching library data. Missing collections are kept.';

  @override
  String get restoreSucceeded => 'Backup restored';

  @override
  String get exportSucceeded => 'Backup exported';

  @override
  String get backupExplanation =>
      'Restore a legacy Legado ZIP backup or export your current library.';

  @override
  String get noChapters => 'No chapters are available';

  @override
  String get backToShelf => 'Back to bookshelf';

  @override
  String get invalidRoute => 'This link cannot be opened.';

  @override
  String get bookMissing => 'This book is no longer in the library.';

  @override
  String get unsupportedText =>
      'This text file is not valid UTF-8. Convert its encoding before importing.';

  @override
  String get textTooLarge => 'This file exceeds the 20 MiB import limit.';

  @override
  String get backupTooLarge => 'This backup exceeds the 128 MiB input limit.';

  @override
  String get details => 'Details';

  @override
  String bookCount(int count) {
    return '$count books';
  }

  @override
  String chapterProgress(int chapter) {
    return 'Chapter $chapter';
  }
}
