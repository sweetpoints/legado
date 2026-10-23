import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/intl.dart' as intl;

import 'app_localizations_en.dart';
import 'app_localizations_zh.dart';

// ignore_for_file: type=lint

/// Callers can lookup localized strings with an instance of AppLocalizations
/// returned by `AppLocalizations.of(context)`.
///
/// Applications need to include `AppLocalizations.delegate()` in their app's
/// `localizationDelegates` list, and the locales they support in the app's
/// `supportedLocales` list. For example:
///
/// ```dart
/// import 'generated/app_localizations.dart';
///
/// return MaterialApp(
///   localizationsDelegates: AppLocalizations.localizationsDelegates,
///   supportedLocales: AppLocalizations.supportedLocales,
///   home: MyApplicationHome(),
/// );
/// ```
///
/// ## Update pubspec.yaml
///
/// Please make sure to update your pubspec.yaml to include the following
/// packages:
///
/// ```yaml
/// dependencies:
///   # Internationalization support.
///   flutter_localizations:
///     sdk: flutter
///   intl: any # Use the pinned version from flutter_localizations
///
///   # Rest of dependencies
/// ```
///
/// ## iOS Applications
///
/// iOS applications define key application metadata, including supported
/// locales, in an Info.plist file that is built into the application bundle.
/// To configure the locales supported by your app, you’ll need to edit this
/// file.
///
/// First, open your project’s ios/Runner.xcworkspace Xcode workspace file.
/// Then, in the Project Navigator, open the Info.plist file under the Runner
/// project’s Runner folder.
///
/// Next, select the Information Property List item, select Add Item from the
/// Editor menu, then select Localizations from the pop-up menu.
///
/// Select and expand the newly-created Localizations item then, for each
/// locale your application supports, add a new item and select the locale
/// you wish to add from the pop-up menu in the Value field. This list should
/// be consistent with the languages listed in the AppLocalizations.supportedLocales
/// property.
abstract class AppLocalizations {
  AppLocalizations(String locale)
    : localeName = intl.Intl.canonicalizedLocale(locale.toString());

  final String localeName;

  static AppLocalizations of(BuildContext context) {
    return Localizations.of<AppLocalizations>(context, AppLocalizations)!;
  }

  static const LocalizationsDelegate<AppLocalizations> delegate =
      _AppLocalizationsDelegate();

  /// A list of this localizations delegate along with the default localizations
  /// delegates.
  ///
  /// Returns a list of localizations delegates containing this delegate along with
  /// GlobalMaterialLocalizations.delegate, GlobalCupertinoLocalizations.delegate,
  /// and GlobalWidgetsLocalizations.delegate.
  ///
  /// Additional delegates can be added by appending to this list in
  /// MaterialApp. This list does not have to be used at all if a custom list
  /// of delegates is preferred or required.
  static const List<LocalizationsDelegate<dynamic>> localizationsDelegates =
      <LocalizationsDelegate<dynamic>>[
        delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
      ];

  /// A list of this localizations delegate's supported locales.
  static const List<Locale> supportedLocales = <Locale>[
    Locale('en'),
    Locale('zh'),
  ];

  /// No description provided for @appTitle.
  ///
  /// In en, this message translates to:
  /// **'Legado'**
  String get appTitle;

  /// No description provided for @bookshelfTitle.
  ///
  /// In en, this message translates to:
  /// **'Bookshelf'**
  String get bookshelfTitle;

  /// No description provided for @exploreTitle.
  ///
  /// In en, this message translates to:
  /// **'Explore'**
  String get exploreTitle;

  /// No description provided for @settingsTitle.
  ///
  /// In en, this message translates to:
  /// **'Settings'**
  String get settingsTitle;

  /// No description provided for @searchTitle.
  ///
  /// In en, this message translates to:
  /// **'Search books'**
  String get searchTitle;

  /// No description provided for @sourcesTitle.
  ///
  /// In en, this message translates to:
  /// **'Book sources'**
  String get sourcesTitle;

  /// No description provided for @backupTitle.
  ///
  /// In en, this message translates to:
  /// **'Backup and restore'**
  String get backupTitle;

  /// No description provided for @importText.
  ///
  /// In en, this message translates to:
  /// **'Import TXT book'**
  String get importText;

  /// No description provided for @emptyShelf.
  ///
  /// In en, this message translates to:
  /// **'Your bookshelf is empty'**
  String get emptyShelf;

  /// No description provided for @emptyShelfHint.
  ///
  /// In en, this message translates to:
  /// **'Import a local book or search your book sources.'**
  String get emptyShelfHint;

  /// No description provided for @retry.
  ///
  /// In en, this message translates to:
  /// **'Retry'**
  String get retry;

  /// No description provided for @cancel.
  ///
  /// In en, this message translates to:
  /// **'Cancel'**
  String get cancel;

  /// No description provided for @confirm.
  ///
  /// In en, this message translates to:
  /// **'Confirm'**
  String get confirm;

  /// No description provided for @close.
  ///
  /// In en, this message translates to:
  /// **'Close'**
  String get close;

  /// No description provided for @delete.
  ///
  /// In en, this message translates to:
  /// **'Delete'**
  String get delete;

  /// No description provided for @save.
  ///
  /// In en, this message translates to:
  /// **'Save'**
  String get save;

  /// No description provided for @loading.
  ///
  /// In en, this message translates to:
  /// **'Loading…'**
  String get loading;

  /// No description provided for @operationFailed.
  ///
  /// In en, this message translates to:
  /// **'The operation failed. Please try again.'**
  String get operationFailed;

  /// No description provided for @startupFailed.
  ///
  /// In en, this message translates to:
  /// **'Unable to open the library. Your original data has not been deleted.'**
  String get startupFailed;

  /// No description provided for @bookRemoved.
  ///
  /// In en, this message translates to:
  /// **'Book removed'**
  String get bookRemoved;

  /// No description provided for @undo.
  ///
  /// In en, this message translates to:
  /// **'Undo'**
  String get undo;

  /// No description provided for @deleteBookTitle.
  ///
  /// In en, this message translates to:
  /// **'Remove this book?'**
  String get deleteBookTitle;

  /// No description provided for @deleteBookMessage.
  ///
  /// In en, this message translates to:
  /// **'The book and its cached chapters will be removed from this library.'**
  String get deleteBookMessage;

  /// No description provided for @importSucceeded.
  ///
  /// In en, this message translates to:
  /// **'Book imported'**
  String get importSucceeded;

  /// No description provided for @languageTitle.
  ///
  /// In en, this message translates to:
  /// **'Language'**
  String get languageTitle;

  /// No description provided for @systemLanguage.
  ///
  /// In en, this message translates to:
  /// **'Follow system'**
  String get systemLanguage;

  /// No description provided for @chineseLanguage.
  ///
  /// In en, this message translates to:
  /// **'简体中文'**
  String get chineseLanguage;

  /// No description provided for @englishLanguage.
  ///
  /// In en, this message translates to:
  /// **'English'**
  String get englishLanguage;

  /// No description provided for @appearanceTitle.
  ///
  /// In en, this message translates to:
  /// **'Appearance'**
  String get appearanceTitle;

  /// No description provided for @systemTheme.
  ///
  /// In en, this message translates to:
  /// **'Follow system'**
  String get systemTheme;

  /// No description provided for @lightTheme.
  ///
  /// In en, this message translates to:
  /// **'Light'**
  String get lightTheme;

  /// No description provided for @darkTheme.
  ///
  /// In en, this message translates to:
  /// **'Dark'**
  String get darkTheme;

  /// No description provided for @restoreZip.
  ///
  /// In en, this message translates to:
  /// **'Restore ZIP backup'**
  String get restoreZip;

  /// No description provided for @exportZip.
  ///
  /// In en, this message translates to:
  /// **'Export ZIP backup'**
  String get exportZip;

  /// No description provided for @restoreTitle.
  ///
  /// In en, this message translates to:
  /// **'Restore this backup?'**
  String get restoreTitle;

  /// No description provided for @restoreMessage.
  ///
  /// In en, this message translates to:
  /// **'Present backup collections will replace their matching library data. Missing collections are kept.'**
  String get restoreMessage;

  /// No description provided for @restoreSucceeded.
  ///
  /// In en, this message translates to:
  /// **'Backup restored'**
  String get restoreSucceeded;

  /// No description provided for @exportSucceeded.
  ///
  /// In en, this message translates to:
  /// **'Backup exported'**
  String get exportSucceeded;

  /// No description provided for @backupExplanation.
  ///
  /// In en, this message translates to:
  /// **'Restore a legacy Legado ZIP backup or export your current library.'**
  String get backupExplanation;

  /// No description provided for @noChapters.
  ///
  /// In en, this message translates to:
  /// **'No chapters are available'**
  String get noChapters;

  /// No description provided for @backToShelf.
  ///
  /// In en, this message translates to:
  /// **'Back to bookshelf'**
  String get backToShelf;

  /// No description provided for @invalidRoute.
  ///
  /// In en, this message translates to:
  /// **'This link cannot be opened.'**
  String get invalidRoute;

  /// No description provided for @bookMissing.
  ///
  /// In en, this message translates to:
  /// **'This book is no longer in the library.'**
  String get bookMissing;

  /// No description provided for @unsupportedText.
  ///
  /// In en, this message translates to:
  /// **'The text encoding is ambiguous, unsupported or damaged. UTF-8, UTF-16 with a BOM and GBK are supported. Check the encoding and convert the file to UTF-8 before importing.'**
  String get unsupportedText;

  /// No description provided for @textTooLarge.
  ///
  /// In en, this message translates to:
  /// **'This file exceeds the 20 MiB import limit.'**
  String get textTooLarge;

  /// No description provided for @backupTooLarge.
  ///
  /// In en, this message translates to:
  /// **'This backup exceeds the 128 MiB input limit.'**
  String get backupTooLarge;

  /// No description provided for @details.
  ///
  /// In en, this message translates to:
  /// **'Details'**
  String get details;

  /// No description provided for @bookCount.
  ///
  /// In en, this message translates to:
  /// **'{count} books'**
  String bookCount(int count);

  /// No description provided for @chapterProgress.
  ///
  /// In en, this message translates to:
  /// **'Chapter {chapter}'**
  String chapterProgress(int chapter);

  /// No description provided for @navigationRssTitle.
  ///
  /// In en, this message translates to:
  /// **'Feeds'**
  String get navigationRssTitle;

  /// No description provided for @epubTooLarge.
  ///
  /// In en, this message translates to:
  /// **'This EPUB exceeds the 128 MiB input limit.'**
  String get epubTooLarge;

  /// No description provided for @licensesTitle.
  ///
  /// In en, this message translates to:
  /// **'Licenses'**
  String get licensesTitle;

  /// No description provided for @shelfOptions.
  ///
  /// In en, this message translates to:
  /// **'Bookshelf options'**
  String get shelfOptions;

  /// No description provided for @shelfManageGroups.
  ///
  /// In en, this message translates to:
  /// **'Manage groups'**
  String get shelfManageGroups;

  /// No description provided for @shelfCreateGroup.
  ///
  /// In en, this message translates to:
  /// **'Create group'**
  String get shelfCreateGroup;

  /// No description provided for @shelfRenameGroup.
  ///
  /// In en, this message translates to:
  /// **'Rename group'**
  String get shelfRenameGroup;

  /// No description provided for @shelfDeleteGroup.
  ///
  /// In en, this message translates to:
  /// **'Delete group'**
  String get shelfDeleteGroup;

  /// No description provided for @shelfDeleteGroupMessage.
  ///
  /// In en, this message translates to:
  /// **'Books will stay in your library. Only membership in this group will be removed.'**
  String get shelfDeleteGroupMessage;

  /// No description provided for @shelfGroupName.
  ///
  /// In en, this message translates to:
  /// **'Group name'**
  String get shelfGroupName;

  /// No description provided for @shelfGroupNameRequired.
  ///
  /// In en, this message translates to:
  /// **'Enter a group name.'**
  String get shelfGroupNameRequired;

  /// No description provided for @shelfNoGroups.
  ///
  /// In en, this message translates to:
  /// **'Create a group before assigning books.'**
  String get shelfNoGroups;

  /// No description provided for @shelfAssignGroups.
  ///
  /// In en, this message translates to:
  /// **'Assign groups'**
  String get shelfAssignGroups;

  /// No description provided for @shelfGroup.
  ///
  /// In en, this message translates to:
  /// **'Group'**
  String get shelfGroup;

  /// No description provided for @shelfSort.
  ///
  /// In en, this message translates to:
  /// **'Sort'**
  String get shelfSort;

  /// No description provided for @shelfAll.
  ///
  /// In en, this message translates to:
  /// **'All books'**
  String get shelfAll;

  /// No description provided for @shelfUngrouped.
  ///
  /// In en, this message translates to:
  /// **'Ungrouped'**
  String get shelfUngrouped;

  /// No description provided for @shelfLocal.
  ///
  /// In en, this message translates to:
  /// **'Local books'**
  String get shelfLocal;

  /// No description provided for @shelfAudio.
  ///
  /// In en, this message translates to:
  /// **'Audio books'**
  String get shelfAudio;

  /// No description provided for @shelfVideo.
  ///
  /// In en, this message translates to:
  /// **'Video books'**
  String get shelfVideo;

  /// No description provided for @shelfNetworkUngrouped.
  ///
  /// In en, this message translates to:
  /// **'Ungrouped online books'**
  String get shelfNetworkUngrouped;

  /// No description provided for @shelfLocalUngrouped.
  ///
  /// In en, this message translates to:
  /// **'Ungrouped local books'**
  String get shelfLocalUngrouped;

  /// No description provided for @shelfUpdateFailed.
  ///
  /// In en, this message translates to:
  /// **'Update failed'**
  String get shelfUpdateFailed;

  /// No description provided for @shelfSortRead.
  ///
  /// In en, this message translates to:
  /// **'Recently read'**
  String get shelfSortRead;

  /// No description provided for @shelfSortUpdated.
  ///
  /// In en, this message translates to:
  /// **'Latest update'**
  String get shelfSortUpdated;

  /// No description provided for @shelfSortTitle.
  ///
  /// In en, this message translates to:
  /// **'Title'**
  String get shelfSortTitle;

  /// No description provided for @shelfSortManual.
  ///
  /// In en, this message translates to:
  /// **'Manual order'**
  String get shelfSortManual;

  /// No description provided for @shelfSortActivity.
  ///
  /// In en, this message translates to:
  /// **'Recent activity'**
  String get shelfSortActivity;

  /// No description provided for @shelfSortAuthor.
  ///
  /// In en, this message translates to:
  /// **'Author'**
  String get shelfSortAuthor;

  /// No description provided for @shelfBookActions.
  ///
  /// In en, this message translates to:
  /// **'Book actions'**
  String get shelfBookActions;

  /// No description provided for @shelfMoveUp.
  ///
  /// In en, this message translates to:
  /// **'Move up'**
  String get shelfMoveUp;

  /// No description provided for @shelfMoveDown.
  ///
  /// In en, this message translates to:
  /// **'Move down'**
  String get shelfMoveDown;

  /// No description provided for @shelfEmptyGroup.
  ///
  /// In en, this message translates to:
  /// **'There are no books in this group.'**
  String get shelfEmptyGroup;

  /// No description provided for @shelfImportEncodedText.
  ///
  /// In en, this message translates to:
  /// **'Import TXT with encoding'**
  String get shelfImportEncodedText;

  /// No description provided for @shelfChooseTextEncoding.
  ///
  /// In en, this message translates to:
  /// **'Choose TXT encoding'**
  String get shelfChooseTextEncoding;

  /// No description provided for @shelfTextEncodingHint.
  ///
  /// In en, this message translates to:
  /// **'Choose the file’s known encoding. A byte order mark (BOM) takes priority; a conflicting choice will stop the import.'**
  String get shelfTextEncodingHint;

  /// No description provided for @shelfEncodingUtf8.
  ///
  /// In en, this message translates to:
  /// **'UTF-8'**
  String get shelfEncodingUtf8;

  /// No description provided for @shelfEncodingGbk.
  ///
  /// In en, this message translates to:
  /// **'GBK / GB2312'**
  String get shelfEncodingGbk;

  /// No description provided for @shelfEncodingUtf16Le.
  ///
  /// In en, this message translates to:
  /// **'UTF-16 Little Endian'**
  String get shelfEncodingUtf16Le;

  /// No description provided for @shelfEncodingUtf16Be.
  ///
  /// In en, this message translates to:
  /// **'UTF-16 Big Endian'**
  String get shelfEncodingUtf16Be;

  /// No description provided for @shelfEncodingConflict.
  ///
  /// In en, this message translates to:
  /// **'The selected encoding conflicts with this file’s BOM. Choose the encoding indicated by the BOM, or use automatic TXT import.'**
  String get shelfEncodingConflict;

  /// No description provided for @shelfEncodingUnavailable.
  ///
  /// In en, this message translates to:
  /// **'This file adapter cannot import a chosen encoding. Use a supported file adapter; automatic detection was not used.'**
  String get shelfEncodingUnavailable;

  /// No description provided for @codeEditorTitle.
  ///
  /// In en, this message translates to:
  /// **'Rule editor'**
  String get codeEditorTitle;

  /// No description provided for @codeEditorClose.
  ///
  /// In en, this message translates to:
  /// **'Close'**
  String get codeEditorClose;

  /// No description provided for @codeEditorSave.
  ///
  /// In en, this message translates to:
  /// **'Save'**
  String get codeEditorSave;

  /// No description provided for @codeEditorSearch.
  ///
  /// In en, this message translates to:
  /// **'Search'**
  String get codeEditorSearch;

  /// No description provided for @codeEditorDebug.
  ///
  /// In en, this message translates to:
  /// **'Debug source'**
  String get codeEditorDebug;

  /// No description provided for @codeEditorLogin.
  ///
  /// In en, this message translates to:
  /// **'Log in'**
  String get codeEditorLogin;

  /// No description provided for @codeEditorMenu.
  ///
  /// In en, this message translates to:
  /// **'More actions'**
  String get codeEditorMenu;

  /// No description provided for @codeEditorTheme.
  ///
  /// In en, this message translates to:
  /// **'Editor theme'**
  String get codeEditorTheme;

  /// No description provided for @codeEditorSelectAll.
  ///
  /// In en, this message translates to:
  /// **'Select all'**
  String get codeEditorSelectAll;

  /// No description provided for @codeEditorFormat.
  ///
  /// In en, this message translates to:
  /// **'Format code'**
  String get codeEditorFormat;

  /// No description provided for @codeEditorSyntax.
  ///
  /// In en, this message translates to:
  /// **'Check JavaScript syntax'**
  String get codeEditorSyntax;

  /// No description provided for @codeEditorCurl.
  ///
  /// In en, this message translates to:
  /// **'Convert cURL'**
  String get codeEditorCurl;

  /// No description provided for @codeEditorSettings.
  ///
  /// In en, this message translates to:
  /// **'Editor settings'**
  String get codeEditorSettings;

  /// No description provided for @codeEditorWrap.
  ///
  /// In en, this message translates to:
  /// **'Wrap lines'**
  String get codeEditorWrap;

  /// No description provided for @codeEditorLog.
  ///
  /// In en, this message translates to:
  /// **'Log'**
  String get codeEditorLog;

  /// No description provided for @codeEditorQuery.
  ///
  /// In en, this message translates to:
  /// **'Search'**
  String get codeEditorQuery;

  /// No description provided for @codeEditorReplace.
  ///
  /// In en, this message translates to:
  /// **'Replace'**
  String get codeEditorReplace;

  /// No description provided for @codeEditorRegex.
  ///
  /// In en, this message translates to:
  /// **'Regular expression'**
  String get codeEditorRegex;

  /// No description provided for @codeEditorIgnoreCase.
  ///
  /// In en, this message translates to:
  /// **'Ignore case'**
  String get codeEditorIgnoreCase;

  /// No description provided for @codeEditorPrevious.
  ///
  /// In en, this message translates to:
  /// **'Previous match'**
  String get codeEditorPrevious;

  /// No description provided for @codeEditorNext.
  ///
  /// In en, this message translates to:
  /// **'Next match'**
  String get codeEditorNext;

  /// No description provided for @codeEditorReplaceAll.
  ///
  /// In en, this message translates to:
  /// **'Replace all'**
  String get codeEditorReplaceAll;

  /// No description provided for @codeEditorHelp.
  ///
  /// In en, this message translates to:
  /// **'Help'**
  String get codeEditorHelp;

  /// No description provided for @codeEditorKeyboardConfig.
  ///
  /// In en, this message translates to:
  /// **'Configure input keys'**
  String get codeEditorKeyboardConfig;

  /// No description provided for @codeEditorRuleHelp.
  ///
  /// In en, this message translates to:
  /// **'Source reference'**
  String get codeEditorRuleHelp;

  /// No description provided for @codeEditorRssHelp.
  ///
  /// In en, this message translates to:
  /// **'RSS reference'**
  String get codeEditorRssHelp;

  /// No description provided for @codeEditorJsHelp.
  ///
  /// In en, this message translates to:
  /// **'JavaScript reference'**
  String get codeEditorJsHelp;

  /// No description provided for @codeEditorRegexHelp.
  ///
  /// In en, this message translates to:
  /// **'Regular expression reference'**
  String get codeEditorRegexHelp;

  /// No description provided for @codeEditorUndo.
  ///
  /// In en, this message translates to:
  /// **'Undo'**
  String get codeEditorUndo;

  /// No description provided for @codeEditorRedo.
  ///
  /// In en, this message translates to:
  /// **'Redo'**
  String get codeEditorRedo;

  /// No description provided for @codeEditorRetry.
  ///
  /// In en, this message translates to:
  /// **'Retry'**
  String get codeEditorRetry;

  /// No description provided for @codeEditorRestart.
  ///
  /// In en, this message translates to:
  /// **'Restart editor'**
  String get codeEditorRestart;

  /// No description provided for @codeEditorLoadingFailed.
  ///
  /// In en, this message translates to:
  /// **'Unable to load editor'**
  String get codeEditorLoadingFailed;

  /// No description provided for @codeEditorLoadFailed.
  ///
  /// In en, this message translates to:
  /// **'The editor could not load this document. Restart to try again.'**
  String get codeEditorLoadFailed;

  /// No description provided for @codeEditorReplacementFailed.
  ///
  /// In en, this message translates to:
  /// **'Replacement failed'**
  String get codeEditorReplacementFailed;

  /// No description provided for @codeEditorDiscardTitle.
  ///
  /// In en, this message translates to:
  /// **'Discard unsaved changes?'**
  String get codeEditorDiscardTitle;

  /// No description provided for @codeEditorDiscardBody.
  ///
  /// In en, this message translates to:
  /// **'Your changes have not been saved. Closing will discard this draft.'**
  String get codeEditorDiscardBody;

  /// No description provided for @codeEditorDiscard.
  ///
  /// In en, this message translates to:
  /// **'Discard changes'**
  String get codeEditorDiscard;

  /// No description provided for @codeEditorKeepEditing.
  ///
  /// In en, this message translates to:
  /// **'Keep editing'**
  String get codeEditorKeepEditing;

  /// No description provided for @codeEditorPlatformUnavailable.
  ///
  /// In en, this message translates to:
  /// **'This embedded editing surface is available on Android.'**
  String get codeEditorPlatformUnavailable;

  /// No description provided for @codeEditorSearchRejected.
  ///
  /// In en, this message translates to:
  /// **'The search update was rejected and restored. Retry or edit the search again before saving or replacing.'**
  String get codeEditorSearchRejected;

  /// No description provided for @dictionaryEditorTitle.
  ///
  /// In en, this message translates to:
  /// **'Dictionary rule'**
  String get dictionaryEditorTitle;

  /// No description provided for @dictionaryEditorName.
  ///
  /// In en, this message translates to:
  /// **'Name'**
  String get dictionaryEditorName;

  /// No description provided for @dictionaryEditorUrl.
  ///
  /// In en, this message translates to:
  /// **'URL rule'**
  String get dictionaryEditorUrl;

  /// No description provided for @dictionaryEditorShow.
  ///
  /// In en, this message translates to:
  /// **'Display rule'**
  String get dictionaryEditorShow;

  /// No description provided for @dictionaryEditorFullscreen.
  ///
  /// In en, this message translates to:
  /// **'Fullscreen editor'**
  String get dictionaryEditorFullscreen;

  /// No description provided for @dictionaryEditorSave.
  ///
  /// In en, this message translates to:
  /// **'Save'**
  String get dictionaryEditorSave;

  /// No description provided for @dictionaryEditorMenu.
  ///
  /// In en, this message translates to:
  /// **'Menu'**
  String get dictionaryEditorMenu;

  /// No description provided for @dictionaryEditorCopy.
  ///
  /// In en, this message translates to:
  /// **'Copy rule'**
  String get dictionaryEditorCopy;

  /// No description provided for @dictionaryEditorPaste.
  ///
  /// In en, this message translates to:
  /// **'Paste rule'**
  String get dictionaryEditorPaste;

  /// No description provided for @dictionaryEditorBridgeError.
  ///
  /// In en, this message translates to:
  /// **'Field not acknowledged. Edit it again to retry.'**
  String get dictionaryEditorBridgeError;

  /// No description provided for @dictionaryEditorExit.
  ///
  /// In en, this message translates to:
  /// **'Exit'**
  String get dictionaryEditorExit;

  /// No description provided for @dictionaryEditorExitMessage.
  ///
  /// In en, this message translates to:
  /// **'Keep editing the unsaved rule?'**
  String get dictionaryEditorExitMessage;

  /// No description provided for @dictionaryEditorKeep.
  ///
  /// In en, this message translates to:
  /// **'Keep editing'**
  String get dictionaryEditorKeep;

  /// No description provided for @dictionaryEditorDiscard.
  ///
  /// In en, this message translates to:
  /// **'Discard changes'**
  String get dictionaryEditorDiscard;

  /// No description provided for @pdfTitle.
  ///
  /// In en, this message translates to:
  /// **'PDF reader'**
  String get pdfTitle;

  /// No description provided for @pdfImportTitle.
  ///
  /// In en, this message translates to:
  /// **'Import PDF'**
  String get pdfImportTitle;

  /// No description provided for @pdfImportDescription.
  ///
  /// In en, this message translates to:
  /// **'Import a local PDF up to 32 MiB. Your document is stored in the library for offline reading.'**
  String get pdfImportDescription;

  /// No description provided for @pdfChooseFile.
  ///
  /// In en, this message translates to:
  /// **'Choose PDF'**
  String get pdfChooseFile;

  /// No description provided for @pdfTooLarge.
  ///
  /// In en, this message translates to:
  /// **'This PDF exceeds the 32 MiB limit.'**
  String get pdfTooLarge;

  /// No description provided for @pdfEncrypted.
  ///
  /// In en, this message translates to:
  /// **'Encrypted PDFs are not supported. Import an unencrypted copy.'**
  String get pdfEncrypted;

  /// No description provided for @pdfUnsupportedStorage.
  ///
  /// In en, this message translates to:
  /// **'This library does not support local PDF storage.'**
  String get pdfUnsupportedStorage;

  /// No description provided for @pdfOpenFailed.
  ///
  /// In en, this message translates to:
  /// **'The PDF is missing, damaged, or unsupported.'**
  String get pdfOpenFailed;

  /// No description provided for @pdfSaveFailed.
  ///
  /// In en, this message translates to:
  /// **'Reading progress could not be saved.'**
  String get pdfSaveFailed;

  /// No description provided for @pdfPreviousPage.
  ///
  /// In en, this message translates to:
  /// **'Previous page'**
  String get pdfPreviousPage;

  /// No description provided for @pdfNextPage.
  ///
  /// In en, this message translates to:
  /// **'Next page'**
  String get pdfNextPage;

  /// No description provided for @pdfPagePosition.
  ///
  /// In en, this message translates to:
  /// **'Page {page} of {total}'**
  String pdfPagePosition(int page, int total);

  /// No description provided for @readerTitle.
  ///
  /// In en, this message translates to:
  /// **'Read'**
  String get readerTitle;

  /// No description provided for @readerSettings.
  ///
  /// In en, this message translates to:
  /// **'Reading settings'**
  String get readerSettings;

  /// No description provided for @readerFontSize.
  ///
  /// In en, this message translates to:
  /// **'Font size'**
  String get readerFontSize;

  /// No description provided for @readerSystemTheme.
  ///
  /// In en, this message translates to:
  /// **'System'**
  String get readerSystemTheme;

  /// No description provided for @readerLightTheme.
  ///
  /// In en, this message translates to:
  /// **'Light'**
  String get readerLightTheme;

  /// No description provided for @readerDarkTheme.
  ///
  /// In en, this message translates to:
  /// **'Dark'**
  String get readerDarkTheme;

  /// No description provided for @readerDone.
  ///
  /// In en, this message translates to:
  /// **'Done'**
  String get readerDone;

  /// No description provided for @readerRetry.
  ///
  /// In en, this message translates to:
  /// **'Retry'**
  String get readerRetry;

  /// No description provided for @readerPreviousChapter.
  ///
  /// In en, this message translates to:
  /// **'Previous chapter'**
  String get readerPreviousChapter;

  /// No description provided for @readerNextChapter.
  ///
  /// In en, this message translates to:
  /// **'Next chapter'**
  String get readerNextChapter;

  /// No description provided for @readerCatalogue.
  ///
  /// In en, this message translates to:
  /// **'Chapters'**
  String get readerCatalogue;

  /// No description provided for @readerCatalogueCount.
  ///
  /// In en, this message translates to:
  /// **'Chapters ({count})'**
  String readerCatalogueCount(int count);

  /// No description provided for @readerChapterPosition.
  ///
  /// In en, this message translates to:
  /// **'{current}/{total} · {title}'**
  String readerChapterPosition(int current, int total, String title);

  /// No description provided for @readerBookMissing.
  ///
  /// In en, this message translates to:
  /// **'This book is missing. Return to the library and reopen it.'**
  String get readerBookMissing;

  /// No description provided for @readerTocMissing.
  ///
  /// In en, this message translates to:
  /// **'No chapter catalogue is available. Load it from the book details first.'**
  String get readerTocMissing;

  /// No description provided for @readerTocEmpty.
  ///
  /// In en, this message translates to:
  /// **'The catalogue contains no readable chapters.'**
  String get readerTocEmpty;

  /// No description provided for @readerContentMissing.
  ///
  /// In en, this message translates to:
  /// **'This chapter is not cached. Download it from book details or retry.'**
  String get readerContentMissing;

  /// No description provided for @readerProgressSaveFailed.
  ///
  /// In en, this message translates to:
  /// **'Could not save reading progress: {error}'**
  String readerProgressSaveFailed(String error);

  /// No description provided for @readerSettingsSaveFailed.
  ///
  /// In en, this message translates to:
  /// **'Could not save reading settings: {error}'**
  String readerSettingsSaveFailed(String error);

  /// No description provided for @readerEpubInvalidArchive.
  ///
  /// In en, this message translates to:
  /// **'The EPUB archive is corrupt or exceeds the import limits.'**
  String get readerEpubInvalidArchive;

  /// No description provided for @readerEpubInvalidPackage.
  ///
  /// In en, this message translates to:
  /// **'The EPUB package, catalogue, or chapter files are invalid.'**
  String get readerEpubInvalidPackage;

  /// No description provided for @readerEpubEncrypted.
  ///
  /// In en, this message translates to:
  /// **'Encrypted or obfuscated EPUB resources are not supported.'**
  String get readerEpubEncrypted;

  /// No description provided for @readerEpubUnsupported.
  ///
  /// In en, this message translates to:
  /// **'This EPUB contains a chapter format that the text reader cannot display.'**
  String get readerEpubUnsupported;

  /// No description provided for @readerEpubEmpty.
  ///
  /// In en, this message translates to:
  /// **'This EPUB contains no readable text.'**
  String get readerEpubEmpty;

  /// No description provided for @readerEpubImport.
  ///
  /// In en, this message translates to:
  /// **'Import EPUB'**
  String get readerEpubImport;

  /// No description provided for @readerBookmarks.
  ///
  /// In en, this message translates to:
  /// **'Bookmarks'**
  String get readerBookmarks;

  /// No description provided for @readerAddBookmark.
  ///
  /// In en, this message translates to:
  /// **'Bookmark this position'**
  String get readerAddBookmark;

  /// No description provided for @readerBookmarkNote.
  ///
  /// In en, this message translates to:
  /// **'Note (optional)'**
  String get readerBookmarkNote;

  /// No description provided for @readerBookmarkCancel.
  ///
  /// In en, this message translates to:
  /// **'Cancel'**
  String get readerBookmarkCancel;

  /// No description provided for @readerBookmarkSave.
  ///
  /// In en, this message translates to:
  /// **'Save'**
  String get readerBookmarkSave;

  /// No description provided for @readerBookmarkAdded.
  ///
  /// In en, this message translates to:
  /// **'Bookmark saved'**
  String get readerBookmarkAdded;

  /// No description provided for @readerBookmarkFailed.
  ///
  /// In en, this message translates to:
  /// **'Could not update bookmarks: {error}'**
  String readerBookmarkFailed(String error);

  /// No description provided for @readerBookmarksEmpty.
  ///
  /// In en, this message translates to:
  /// **'No bookmarks yet'**
  String get readerBookmarksEmpty;

  /// No description provided for @readerBookmarkUnavailable.
  ///
  /// In en, this message translates to:
  /// **'This bookmark cannot be matched to the current book and chapter.'**
  String get readerBookmarkUnavailable;

  /// No description provided for @readerDeleteBookmark.
  ///
  /// In en, this message translates to:
  /// **'Delete bookmark'**
  String get readerDeleteBookmark;

  /// No description provided for @rssTitle.
  ///
  /// In en, this message translates to:
  /// **'Subscriptions'**
  String get rssTitle;

  /// No description provided for @rssAddTooltip.
  ///
  /// In en, this message translates to:
  /// **'Add or import subscriptions'**
  String get rssAddTooltip;

  /// No description provided for @rssEmpty.
  ///
  /// In en, this message translates to:
  /// **'Add an RSS/Atom URL or import legacy subscription JSON'**
  String get rssEmpty;

  /// No description provided for @rssLegacySubtitle.
  ///
  /// In en, this message translates to:
  /// **'CSS/DOM HTML subscription'**
  String get rssLegacySubtitle;

  /// No description provided for @rssAdd.
  ///
  /// In en, this message translates to:
  /// **'Add subscription'**
  String get rssAdd;

  /// No description provided for @rssImportHelp.
  ///
  /// In en, this message translates to:
  /// **'Paste an RSS/Atom URL or legacy rssSources.json. CSS/DOM HTML rules are supported; JavaScript rules are preserved with explicit capability errors.'**
  String get rssImportHelp;

  /// No description provided for @rssInput.
  ///
  /// In en, this message translates to:
  /// **'URL or subscription JSON'**
  String get rssInput;

  /// No description provided for @rssCancel.
  ///
  /// In en, this message translates to:
  /// **'Cancel'**
  String get rssCancel;

  /// No description provided for @rssSaving.
  ///
  /// In en, this message translates to:
  /// **'Saving…'**
  String get rssSaving;

  /// No description provided for @rssSave.
  ///
  /// In en, this message translates to:
  /// **'Save'**
  String get rssSave;

  /// No description provided for @rssCancelRefresh.
  ///
  /// In en, this message translates to:
  /// **'Cancel refresh'**
  String get rssCancelRefresh;

  /// No description provided for @rssRefresh.
  ///
  /// In en, this message translates to:
  /// **'Refresh feed'**
  String get rssRefresh;

  /// No description provided for @rssLegacyHelp.
  ///
  /// In en, this message translates to:
  /// **'HTML lists, CSS/DOM rules and safe templates are supported. JavaScript and browser verification require a separate host.'**
  String get rssLegacyHelp;

  /// No description provided for @rssFetch.
  ///
  /// In en, this message translates to:
  /// **'Fetch articles'**
  String get rssFetch;

  /// No description provided for @rssArticle.
  ///
  /// In en, this message translates to:
  /// **'Article'**
  String get rssArticle;

  /// No description provided for @rssMissingArticle.
  ///
  /// In en, this message translates to:
  /// **'Article unavailable. Go back and refresh the subscription.'**
  String get rssMissingArticle;

  /// No description provided for @rssUnstar.
  ///
  /// In en, this message translates to:
  /// **'Remove bookmark'**
  String get rssUnstar;

  /// No description provided for @rssStar.
  ///
  /// In en, this message translates to:
  /// **'Bookmark article'**
  String get rssStar;

  /// No description provided for @rssCopyLink.
  ///
  /// In en, this message translates to:
  /// **'Copy original link'**
  String get rssCopyLink;

  /// No description provided for @rssCopied.
  ///
  /// In en, this message translates to:
  /// **'Original link copied'**
  String get rssCopied;

  /// No description provided for @rssRetry.
  ///
  /// In en, this message translates to:
  /// **'Retry'**
  String get rssRetry;

  /// No description provided for @rssInvalidUrl.
  ///
  /// In en, this message translates to:
  /// **'Subscription URL must use HTTP or HTTPS'**
  String get rssInvalidUrl;

  /// No description provided for @rssCorruptStorage.
  ///
  /// In en, this message translates to:
  /// **'Subscription data is invalid. Restore a backup; the original data is preserved.'**
  String get rssCorruptStorage;

  /// No description provided for @rssInvalidSource.
  ///
  /// In en, this message translates to:
  /// **'Each subscription must be a JSON object'**
  String get rssInvalidSource;

  /// No description provided for @rssEmptyResponse.
  ///
  /// In en, this message translates to:
  /// **'The feed returned an empty response'**
  String get rssEmptyResponse;

  /// No description provided for @rssTooLarge.
  ///
  /// In en, this message translates to:
  /// **'The feed exceeds the 5 MiB limit'**
  String get rssTooLarge;

  /// No description provided for @rssInvalidFeed.
  ///
  /// In en, this message translates to:
  /// **'Cannot parse RSS/Atom. Check the URL and feed format.'**
  String get rssInvalidFeed;

  /// No description provided for @rssNetworkError.
  ///
  /// In en, this message translates to:
  /// **'Feed request failed. Check the network and URL, then retry.'**
  String get rssNetworkError;

  /// No description provided for @rssOperationError.
  ///
  /// In en, this message translates to:
  /// **'Subscription operation failed. Retry; existing data is preserved.'**
  String get rssOperationError;

  /// No description provided for @rssScriptCapability.
  ///
  /// In en, this message translates to:
  /// **'This rule requires a JavaScript or browser host which is not connected yet. The original rules are preserved; use the existing Android app.'**
  String get rssScriptCapability;

  /// No description provided for @rssUnsupportedRule.
  ///
  /// In en, this message translates to:
  /// **'This rule format is not supported yet. Use CSS/Legado DOM rules or the existing Android app.'**
  String get rssUnsupportedRule;

  /// No description provided for @rssPaginationCycle.
  ///
  /// In en, this message translates to:
  /// **'Pagination contains a cycle. Requests stopped; fix the next-page rule.'**
  String get rssPaginationCycle;

  /// No description provided for @rssPageLimit.
  ///
  /// In en, this message translates to:
  /// **'Pagination exceeded 20 pages. Requests stopped; narrow the category.'**
  String get rssPageLimit;

  /// No description provided for @rssInvalidArticleLink.
  ///
  /// In en, this message translates to:
  /// **'The article link is invalid. Check the link extraction rule.'**
  String get rssInvalidArticleLink;

  /// No description provided for @rssImageError.
  ///
  /// In en, this message translates to:
  /// **'Image could not load. Copy the original article link to view it.'**
  String get rssImageError;

  /// No description provided for @rssDelete.
  ///
  /// In en, this message translates to:
  /// **'Delete subscription'**
  String get rssDelete;

  /// No description provided for @rssDeleteHelp.
  ///
  /// In en, this message translates to:
  /// **'The subscription will stay deleted across restarts. Original backups, offline articles and bookmarks are kept. Explicitly importing the same URL adds it back.'**
  String get rssDeleteHelp;

  /// No description provided for @rssRestoreSummary.
  ///
  /// In en, this message translates to:
  /// **'Legacy backup: {restored} subscriptions available; {conflicts} URL conflicts keep the current configuration. Original backup data is unchanged.'**
  String rssRestoreSummary(int restored, int conflicts);

  /// No description provided for @rssInvalidRestore.
  ///
  /// In en, this message translates to:
  /// **'The legacy subscription backup is invalid. Original data is preserved; check rssSources.json.'**
  String get rssInvalidRestore;

  /// No description provided for @rssDuplicateRestoreSource.
  ///
  /// In en, this message translates to:
  /// **'The legacy backup contains duplicate sourceUrl identities. Fix the ambiguity and restore again. Original data is preserved.'**
  String get rssDuplicateRestoreSource;

  /// No description provided for @rssFavoritesTitle.
  ///
  /// In en, this message translates to:
  /// **'RSS favorites'**
  String get rssFavoritesTitle;

  /// No description provided for @rssFavoritesGroups.
  ///
  /// In en, this message translates to:
  /// **'Groups'**
  String get rssFavoritesGroups;

  /// No description provided for @rssFavoritesMenu.
  ///
  /// In en, this message translates to:
  /// **'Menu'**
  String get rssFavoritesMenu;

  /// No description provided for @rssFavoritesDeleteGroup.
  ///
  /// In en, this message translates to:
  /// **'Delete current group'**
  String get rssFavoritesDeleteGroup;

  /// No description provided for @rssFavoritesDeleteAll.
  ///
  /// In en, this message translates to:
  /// **'Delete all favorites'**
  String get rssFavoritesDeleteAll;

  /// No description provided for @rssFavoritesError.
  ///
  /// In en, this message translates to:
  /// **'Favorites operation failed. Please retry.'**
  String get rssFavoritesError;

  /// No description provided for @rssFavoritesConfirmDelete.
  ///
  /// In en, this message translates to:
  /// **'Confirm deletion?'**
  String get rssFavoritesConfirmDelete;

  /// No description provided for @rssFavoriteConfigHeading.
  ///
  /// In en, this message translates to:
  /// **'Favorite'**
  String get rssFavoriteConfigHeading;

  /// No description provided for @rssFavoriteConfigTitle.
  ///
  /// In en, this message translates to:
  /// **'Title'**
  String get rssFavoriteConfigTitle;

  /// No description provided for @rssFavoriteConfigGroup.
  ///
  /// In en, this message translates to:
  /// **'Group name'**
  String get rssFavoriteConfigGroup;

  /// No description provided for @rssFavoriteConfigConfirm.
  ///
  /// In en, this message translates to:
  /// **'OK'**
  String get rssFavoriteConfigConfirm;

  /// No description provided for @rssEditorTitle.
  ///
  /// In en, this message translates to:
  /// **'RSS source editor'**
  String get rssEditorTitle;

  /// No description provided for @rssEditorEditRejected.
  ///
  /// In en, this message translates to:
  /// **'The edit was rejected. Correct the field before saving.'**
  String get rssEditorEditRejected;

  /// No description provided for @rssEditorFailed.
  ///
  /// In en, this message translates to:
  /// **'Unable to update the RSS source editor.'**
  String get rssEditorFailed;

  /// No description provided for @rssEditorMissing.
  ///
  /// In en, this message translates to:
  /// **'The RSS source is no longer available.'**
  String get rssEditorMissing;

  /// No description provided for @rssEditorRequired.
  ///
  /// In en, this message translates to:
  /// **'Source name and URL are required.'**
  String get rssEditorRequired;

  /// No description provided for @rssEditorFormat.
  ///
  /// In en, this message translates to:
  /// **'Invalid source format.'**
  String get rssEditorFormat;

  /// No description provided for @rssEditorFocus.
  ///
  /// In en, this message translates to:
  /// **'Focus a text field first.'**
  String get rssEditorFocus;

  /// No description provided for @rssEditorNoLogin.
  ///
  /// In en, this message translates to:
  /// **'This source has no login.'**
  String get rssEditorNoLogin;

  /// No description provided for @rssEditorEnableJs.
  ///
  /// In en, this message translates to:
  /// **'Enable JavaScript'**
  String get rssEditorEnableJs;

  /// No description provided for @rssEditorBaseUrl.
  ///
  /// In en, this message translates to:
  /// **'Load with base URL'**
  String get rssEditorBaseUrl;

  /// No description provided for @rssEditorWebLog.
  ///
  /// In en, this message translates to:
  /// **'Show WebView log'**
  String get rssEditorWebLog;

  /// No description provided for @rssEditorCacheFirst.
  ///
  /// In en, this message translates to:
  /// **'Prefer cache'**
  String get rssEditorCacheFirst;

  /// No description provided for @rssEditorSingleUrl.
  ///
  /// In en, this message translates to:
  /// **'Single URL'**
  String get rssEditorSingleUrl;

  /// No description provided for @rssEditorPreload.
  ///
  /// In en, this message translates to:
  /// **'Enable preload'**
  String get rssEditorPreload;

  /// No description provided for @rssEditorArticleStyle.
  ///
  /// In en, this message translates to:
  /// **'Article layout'**
  String get rssEditorArticleStyle;

  /// No description provided for @rssEditorExternal.
  ///
  /// In en, this message translates to:
  /// **'Open the full screen editor to edit this field safely.'**
  String get rssEditorExternal;

  /// No description provided for @rssEditorUrlOverride.
  ///
  /// In en, this message translates to:
  /// **'URL navigation interception (JavaScript; return true to intercept; url is available to open links, search, or add books)'**
  String get rssEditorUrlOverride;

  /// No description provided for @rssEditorStart.
  ///
  /// In en, this message translates to:
  /// **'Start'**
  String get rssEditorStart;

  /// No description provided for @rssEditorList.
  ///
  /// In en, this message translates to:
  /// **'List rules'**
  String get rssEditorList;

  /// No description provided for @rssEditorWebView.
  ///
  /// In en, this message translates to:
  /// **'WebView'**
  String get rssEditorWebView;

  /// No description provided for @rssEditorWebPage.
  ///
  /// In en, this message translates to:
  /// **'Web page'**
  String get rssEditorWebPage;

  /// No description provided for @rssEditorLayoutList.
  ///
  /// In en, this message translates to:
  /// **'List'**
  String get rssEditorLayoutList;

  /// No description provided for @rssEditorLayoutSingle.
  ///
  /// In en, this message translates to:
  /// **'Single column'**
  String get rssEditorLayoutSingle;

  /// No description provided for @rssEditorLayoutDouble.
  ///
  /// In en, this message translates to:
  /// **'Two columns'**
  String get rssEditorLayoutDouble;

  /// No description provided for @rssEditorLayoutWaterfall.
  ///
  /// In en, this message translates to:
  /// **'Masonry'**
  String get rssEditorLayoutWaterfall;

  /// No description provided for @rssEditorLayoutTriple.
  ///
  /// In en, this message translates to:
  /// **'Three columns'**
  String get rssEditorLayoutTriple;

  /// No description provided for @rssEditorType.
  ///
  /// In en, this message translates to:
  /// **'Source type'**
  String get rssEditorType;

  /// No description provided for @ruleSubscriptionsTitle.
  ///
  /// In en, this message translates to:
  /// **'Rule subscriptions'**
  String get ruleSubscriptionsTitle;

  /// No description provided for @ruleSubscriptionsEmpty.
  ///
  /// In en, this message translates to:
  /// **'No subscriptions. Add a subscription URL to begin.'**
  String get ruleSubscriptionsEmpty;

  /// No description provided for @ruleSubscriptionsName.
  ///
  /// In en, this message translates to:
  /// **'Name'**
  String get ruleSubscriptionsName;

  /// No description provided for @ruleSubscriptionsType.
  ///
  /// In en, this message translates to:
  /// **'Type'**
  String get ruleSubscriptionsType;

  /// No description provided for @ruleSubscriptionsBook.
  ///
  /// In en, this message translates to:
  /// **'Book sources'**
  String get ruleSubscriptionsBook;

  /// No description provided for @ruleSubscriptionsRss.
  ///
  /// In en, this message translates to:
  /// **'RSS sources'**
  String get ruleSubscriptionsRss;

  /// No description provided for @ruleSubscriptionsReplace.
  ///
  /// In en, this message translates to:
  /// **'Replacement rules'**
  String get ruleSubscriptionsReplace;

  /// No description provided for @ruleSubscriptionsAutomatic.
  ///
  /// In en, this message translates to:
  /// **'Automatic update'**
  String get ruleSubscriptionsAutomatic;

  /// No description provided for @ruleSubscriptionsSilent.
  ///
  /// In en, this message translates to:
  /// **'Silent update'**
  String get ruleSubscriptionsSilent;

  /// No description provided for @ruleSubscriptionsInterval.
  ///
  /// In en, this message translates to:
  /// **'Update interval (hours)'**
  String get ruleSubscriptionsInterval;

  /// No description provided for @ruleSubscriptionsPending.
  ///
  /// In en, this message translates to:
  /// **'Save is pending. Retry to finish the existing save.'**
  String get ruleSubscriptionsPending;

  /// No description provided for @ruleSubscriptionsRejected.
  ///
  /// In en, this message translates to:
  /// **'An edit was not acknowledged. Edit the field again before saving.'**
  String get ruleSubscriptionsRejected;

  /// No description provided for @ruleSubscriptionsEmptyUrl.
  ///
  /// In en, this message translates to:
  /// **'Enter a subscription URL.'**
  String get ruleSubscriptionsEmptyUrl;

  /// No description provided for @ruleSubscriptionsDuplicate.
  ///
  /// In en, this message translates to:
  /// **'This URL already exists.'**
  String get ruleSubscriptionsDuplicate;

  /// No description provided for @ruleSubscriptionsMissing.
  ///
  /// In en, this message translates to:
  /// **'This subscription no longer exists.'**
  String get ruleSubscriptionsMissing;

  /// No description provided for @ruleSubscriptionsConflict.
  ///
  /// In en, this message translates to:
  /// **'The subscription changed elsewhere. Cancel and reopen it.'**
  String get ruleSubscriptionsConflict;

  /// No description provided for @ruleSubscriptionsFailure.
  ///
  /// In en, this message translates to:
  /// **'The operation failed. Retry.'**
  String get ruleSubscriptionsFailure;

  /// No description provided for @ruleSubscriptionsUp.
  ///
  /// In en, this message translates to:
  /// **'Move up'**
  String get ruleSubscriptionsUp;

  /// No description provided for @ruleSubscriptionsDown.
  ///
  /// In en, this message translates to:
  /// **'Move down'**
  String get ruleSubscriptionsDown;

  /// No description provided for @ruleSubscriptionsAdd.
  ///
  /// In en, this message translates to:
  /// **'Add'**
  String get ruleSubscriptionsAdd;

  /// No description provided for @ruleSubscriptionsEdit.
  ///
  /// In en, this message translates to:
  /// **'Edit'**
  String get ruleSubscriptionsEdit;

  /// No description provided for @ruleSubscriptionsActions.
  ///
  /// In en, this message translates to:
  /// **'Actions'**
  String get ruleSubscriptionsActions;

  /// No description provided for @debuggerTitle.
  ///
  /// In en, this message translates to:
  /// **'Source debugger'**
  String get debuggerTitle;

  /// No description provided for @debuggerSearch.
  ///
  /// In en, this message translates to:
  /// **'Search'**
  String get debuggerSearch;

  /// No description provided for @debuggerClose.
  ///
  /// In en, this message translates to:
  /// **'Back'**
  String get debuggerClose;

  /// No description provided for @debuggerMenu.
  ///
  /// In en, this message translates to:
  /// **'More options'**
  String get debuggerMenu;

  /// No description provided for @debuggerScan.
  ///
  /// In en, this message translates to:
  /// **'Scan QR code'**
  String get debuggerScan;

  /// No description provided for @debuggerRefresh.
  ///
  /// In en, this message translates to:
  /// **'Refresh explore categories'**
  String get debuggerRefresh;

  /// No description provided for @debuggerInstructions.
  ///
  /// In en, this message translates to:
  /// **'Help'**
  String get debuggerInstructions;

  /// No description provided for @debuggerRetry.
  ///
  /// In en, this message translates to:
  /// **'Retry'**
  String get debuggerRetry;

  /// No description provided for @debuggerSearchHtml.
  ///
  /// In en, this message translates to:
  /// **'Search HTML'**
  String get debuggerSearchHtml;

  /// No description provided for @debuggerBookHtml.
  ///
  /// In en, this message translates to:
  /// **'Book details HTML'**
  String get debuggerBookHtml;

  /// No description provided for @debuggerTocHtml.
  ///
  /// In en, this message translates to:
  /// **'Table of contents HTML'**
  String get debuggerTocHtml;

  /// No description provided for @debuggerContentHtml.
  ///
  /// In en, this message translates to:
  /// **'Content HTML'**
  String get debuggerContentHtml;

  /// No description provided for @debuggerListHtml.
  ///
  /// In en, this message translates to:
  /// **'List HTML'**
  String get debuggerListHtml;

  /// No description provided for @debuggerCategories.
  ///
  /// In en, this message translates to:
  /// **'Choose category'**
  String get debuggerCategories;

  /// No description provided for @debuggerSearchHelp.
  ///
  /// In en, this message translates to:
  /// **'Debug search: enter a keyword, for example:'**
  String get debuggerSearchHelp;

  /// No description provided for @debuggerBookCategoryHelp.
  ///
  /// In en, this message translates to:
  /// **'Debug explore: enter an explore URL, for example:'**
  String get debuggerBookCategoryHelp;

  /// No description provided for @debuggerRssCategoryHelp.
  ///
  /// In en, this message translates to:
  /// **'Debug category: enter a category URL, for example:'**
  String get debuggerRssCategoryHelp;

  /// No description provided for @debuggerDetailsHelp.
  ///
  /// In en, this message translates to:
  /// **'Debug book information: enter the details URL:'**
  String get debuggerDetailsHelp;

  /// No description provided for @debuggerTocHelp.
  ///
  /// In en, this message translates to:
  /// **'Debug table of contents: enter a URL prefixed with ++:'**
  String get debuggerTocHelp;

  /// No description provided for @debuggerContentHelp.
  ///
  /// In en, this message translates to:
  /// **'Debug chapter content: enter a URL prefixed with --:'**
  String get debuggerContentHelp;

  /// No description provided for @debuggerRssContentHelp.
  ///
  /// In en, this message translates to:
  /// **'Debug content: enter a content page URL:'**
  String get debuggerRssContentHelp;

  /// No description provided for @debuggerBusy.
  ///
  /// In en, this message translates to:
  /// **'Debug channel is busy. Try again later.'**
  String get debuggerBusy;

  /// No description provided for @debuggerInterrupted.
  ///
  /// In en, this message translates to:
  /// **'The previous debug run was interrupted. Run again to continue.'**
  String get debuggerInterrupted;

  /// No description provided for @debuggerConnectionError.
  ///
  /// In en, this message translates to:
  /// **'Unable to connect to the source debugger.'**
  String get debuggerConnectionError;

  /// No description provided for @debuggerCommandError.
  ///
  /// In en, this message translates to:
  /// **'The action could not be completed. Please try again.'**
  String get debuggerCommandError;

  /// No description provided for @debuggerNoCategories.
  ///
  /// In en, this message translates to:
  /// **'No categories are available.'**
  String get debuggerNoCategories;

  /// No description provided for @debuggerLoading.
  ///
  /// In en, this message translates to:
  /// **'Loading source…'**
  String get debuggerLoading;

  /// No description provided for @debuggerLinkError.
  ///
  /// In en, this message translates to:
  /// **'Unable to open this link.'**
  String get debuggerLinkError;

  /// No description provided for @sourceEditorTitle.
  ///
  /// In en, this message translates to:
  /// **'Edit book source'**
  String get sourceEditorTitle;

  /// No description provided for @sourceEditorClose.
  ///
  /// In en, this message translates to:
  /// **'Close'**
  String get sourceEditorClose;

  /// No description provided for @sourceEditorSave.
  ///
  /// In en, this message translates to:
  /// **'Save'**
  String get sourceEditorSave;

  /// No description provided for @sourceEditorFullScreen.
  ///
  /// In en, this message translates to:
  /// **'Rule editor'**
  String get sourceEditorFullScreen;

  /// No description provided for @sourceEditorMenu.
  ///
  /// In en, this message translates to:
  /// **'More actions'**
  String get sourceEditorMenu;

  /// No description provided for @sourceEditorBase.
  ///
  /// In en, this message translates to:
  /// **'Basic'**
  String get sourceEditorBase;

  /// No description provided for @sourceEditorSearch.
  ///
  /// In en, this message translates to:
  /// **'Search'**
  String get sourceEditorSearch;

  /// No description provided for @sourceEditorExplore.
  ///
  /// In en, this message translates to:
  /// **'Explore'**
  String get sourceEditorExplore;

  /// No description provided for @sourceEditorInfo.
  ///
  /// In en, this message translates to:
  /// **'Details'**
  String get sourceEditorInfo;

  /// No description provided for @sourceEditorToc.
  ///
  /// In en, this message translates to:
  /// **'Contents'**
  String get sourceEditorToc;

  /// No description provided for @sourceEditorContent.
  ///
  /// In en, this message translates to:
  /// **'Content'**
  String get sourceEditorContent;

  /// No description provided for @sourceEditorReview.
  ///
  /// In en, this message translates to:
  /// **'Reviews'**
  String get sourceEditorReview;

  /// No description provided for @sourceEditorEnabled.
  ///
  /// In en, this message translates to:
  /// **'Enabled'**
  String get sourceEditorEnabled;

  /// No description provided for @sourceEditorExploreEnabled.
  ///
  /// In en, this message translates to:
  /// **'Discovery'**
  String get sourceEditorExploreEnabled;

  /// No description provided for @sourceEditorCookie.
  ///
  /// In en, this message translates to:
  /// **'Save cookies'**
  String get sourceEditorCookie;

  /// No description provided for @sourceEditorReviewEnabled.
  ///
  /// In en, this message translates to:
  /// **'Reviews'**
  String get sourceEditorReviewEnabled;

  /// No description provided for @sourceEditorListener.
  ///
  /// In en, this message translates to:
  /// **'Event listener'**
  String get sourceEditorListener;

  /// No description provided for @sourceEditorCustomButton.
  ///
  /// In en, this message translates to:
  /// **'Custom button'**
  String get sourceEditorCustomButton;

  /// No description provided for @sourceEditorText.
  ///
  /// In en, this message translates to:
  /// **'Text'**
  String get sourceEditorText;

  /// No description provided for @sourceEditorAudio.
  ///
  /// In en, this message translates to:
  /// **'Audio'**
  String get sourceEditorAudio;

  /// No description provided for @sourceEditorImage.
  ///
  /// In en, this message translates to:
  /// **'Image'**
  String get sourceEditorImage;

  /// No description provided for @sourceEditorFile.
  ///
  /// In en, this message translates to:
  /// **'File'**
  String get sourceEditorFile;

  /// No description provided for @sourceEditorVideo.
  ///
  /// In en, this message translates to:
  /// **'Video'**
  String get sourceEditorVideo;

  /// No description provided for @sourceEditorDebug.
  ///
  /// In en, this message translates to:
  /// **'Debug source'**
  String get sourceEditorDebug;

  /// No description provided for @sourceEditorLogin.
  ///
  /// In en, this message translates to:
  /// **'Log in'**
  String get sourceEditorLogin;

  /// No description provided for @sourceEditorClearCookie.
  ///
  /// In en, this message translates to:
  /// **'Clear cookies'**
  String get sourceEditorClearCookie;

  /// No description provided for @sourceEditorAutoComplete.
  ///
  /// In en, this message translates to:
  /// **'Auto complete'**
  String get sourceEditorAutoComplete;

  /// No description provided for @sourceEditorCopy.
  ///
  /// In en, this message translates to:
  /// **'Copy source'**
  String get sourceEditorCopy;

  /// No description provided for @sourceEditorPaste.
  ///
  /// In en, this message translates to:
  /// **'Paste source'**
  String get sourceEditorPaste;

  /// No description provided for @sourceEditorQr.
  ///
  /// In en, this message translates to:
  /// **'Scan QR code'**
  String get sourceEditorQr;

  /// No description provided for @sourceEditorShare.
  ///
  /// In en, this message translates to:
  /// **'Share source'**
  String get sourceEditorShare;

  /// No description provided for @sourceEditorQrShare.
  ///
  /// In en, this message translates to:
  /// **'Share QR code'**
  String get sourceEditorQrShare;

  /// No description provided for @sourceEditorVariable.
  ///
  /// In en, this message translates to:
  /// **'Source variable'**
  String get sourceEditorVariable;

  /// No description provided for @sourceEditorLog.
  ///
  /// In en, this message translates to:
  /// **'Log'**
  String get sourceEditorLog;

  /// No description provided for @sourceEditorHelp.
  ///
  /// In en, this message translates to:
  /// **'Source reference'**
  String get sourceEditorHelp;

  /// No description provided for @sourceEditorRetry.
  ///
  /// In en, this message translates to:
  /// **'Retry'**
  String get sourceEditorRetry;

  /// No description provided for @sourceEditorDiscardTitle.
  ///
  /// In en, this message translates to:
  /// **'Discard unsaved changes?'**
  String get sourceEditorDiscardTitle;

  /// No description provided for @sourceEditorDiscardBody.
  ///
  /// In en, this message translates to:
  /// **'Your changes have not been saved. Closing will discard this draft.'**
  String get sourceEditorDiscardBody;

  /// No description provided for @sourceEditorDiscard.
  ///
  /// In en, this message translates to:
  /// **'Discard changes'**
  String get sourceEditorDiscard;

  /// No description provided for @sourceEditorKeepEditing.
  ///
  /// In en, this message translates to:
  /// **'Keep editing'**
  String get sourceEditorKeepEditing;

  /// No description provided for @sourceEditorGroups.
  ///
  /// In en, this message translates to:
  /// **'Source groups'**
  String get sourceEditorGroups;

  /// No description provided for @sourceEditorCancel.
  ///
  /// In en, this message translates to:
  /// **'Cancel'**
  String get sourceEditorCancel;

  /// No description provided for @sourceEditorUndo.
  ///
  /// In en, this message translates to:
  /// **'Undo'**
  String get sourceEditorUndo;

  /// No description provided for @sourceEditorRedo.
  ///
  /// In en, this message translates to:
  /// **'Redo'**
  String get sourceEditorRedo;

  /// No description provided for @sourceEditorKeyboardHelp.
  ///
  /// In en, this message translates to:
  /// **'Input tools'**
  String get sourceEditorKeyboardHelp;

  /// No description provided for @sourceEditorKeyboardConfig.
  ///
  /// In en, this message translates to:
  /// **'Configure input keys'**
  String get sourceEditorKeyboardConfig;

  /// No description provided for @sourceEditorUrlOptions.
  ///
  /// In en, this message translates to:
  /// **'Insert URL options'**
  String get sourceEditorUrlOptions;

  /// No description provided for @sourceEditorJsHelp.
  ///
  /// In en, this message translates to:
  /// **'JavaScript reference'**
  String get sourceEditorJsHelp;

  /// No description provided for @sourceEditorRegexHelp.
  ///
  /// In en, this message translates to:
  /// **'Regular expression reference'**
  String get sourceEditorRegexHelp;

  /// No description provided for @sourceEditorInsertGroup.
  ///
  /// In en, this message translates to:
  /// **'Insert group'**
  String get sourceEditorInsertGroup;

  /// No description provided for @sourceEditorChooseFile.
  ///
  /// In en, this message translates to:
  /// **'Choose file'**
  String get sourceEditorChooseFile;

  /// No description provided for @sourceEditorEngine.
  ///
  /// In en, this message translates to:
  /// **'Source engine: Flutter / V8'**
  String get sourceEditorEngine;

  /// No description provided for @sourceEditorEngineBody.
  ///
  /// In en, this message translates to:
  /// **'The engine supports legacy sources. Preview rule changes before applying migration.'**
  String get sourceEditorEngineBody;

  /// No description provided for @sourceEditorMigration.
  ///
  /// In en, this message translates to:
  /// **'Preview migration'**
  String get sourceEditorMigration;

  /// No description provided for @sourceEditorMigrationRunning.
  ///
  /// In en, this message translates to:
  /// **'Analyzing source…'**
  String get sourceEditorMigrationRunning;

  /// No description provided for @sourceEditorMigrationTitle.
  ///
  /// In en, this message translates to:
  /// **'Source migration preview'**
  String get sourceEditorMigrationTitle;

  /// No description provided for @sourceEditorMigrationApply.
  ///
  /// In en, this message translates to:
  /// **'Apply migration candidate'**
  String get sourceEditorMigrationApply;

  /// No description provided for @sourceEditorMigrationManual.
  ///
  /// In en, this message translates to:
  /// **'Some rules need manual changes.'**
  String get sourceEditorMigrationManual;

  /// No description provided for @sourceEditorMigrationNone.
  ///
  /// In en, this message translates to:
  /// **'No rule changes were identified.'**
  String get sourceEditorMigrationNone;

  /// No description provided for @sourceEditorLargeField.
  ///
  /// In en, this message translates to:
  /// **'This field contains long or complex text. Open the rule editor to edit it.'**
  String get sourceEditorLargeField;

  /// No description provided for @sourceEditorFailed.
  ///
  /// In en, this message translates to:
  /// **'Unable to load source editor'**
  String get sourceEditorFailed;

  /// No description provided for @sourceEditorOptions.
  ///
  /// In en, this message translates to:
  /// **'Source options'**
  String get sourceEditorOptions;

  /// No description provided for @sourceEditorType.
  ///
  /// In en, this message translates to:
  /// **'Source type'**
  String get sourceEditorType;

  /// No description provided for @sourceEditorEditRejected.
  ///
  /// In en, this message translates to:
  /// **'The edit was rejected and the field was restored. Retry or edit that field again before saving.'**
  String get sourceEditorEditRejected;

  /// No description provided for @sourceManagerTitle.
  ///
  /// In en, this message translates to:
  /// **'Source manager'**
  String get sourceManagerTitle;

  /// No description provided for @sourceManagerQuery.
  ///
  /// In en, this message translates to:
  /// **'Search sources'**
  String get sourceManagerQuery;

  /// No description provided for @sourceManagerMenu.
  ///
  /// In en, this message translates to:
  /// **'Menu'**
  String get sourceManagerMenu;

  /// No description provided for @sourceManagerSelectionMenu.
  ///
  /// In en, this message translates to:
  /// **'Selection actions'**
  String get sourceManagerSelectionMenu;

  /// No description provided for @sourceManagerBack.
  ///
  /// In en, this message translates to:
  /// **'Back'**
  String get sourceManagerBack;

  /// No description provided for @sourceManagerSelectAll.
  ///
  /// In en, this message translates to:
  /// **'Select all'**
  String get sourceManagerSelectAll;

  /// No description provided for @sourceManagerInvert.
  ///
  /// In en, this message translates to:
  /// **'Invert selection'**
  String get sourceManagerInvert;

  /// No description provided for @sourceManagerDelete.
  ///
  /// In en, this message translates to:
  /// **'Delete'**
  String get sourceManagerDelete;

  /// No description provided for @sourceManagerEdit.
  ///
  /// In en, this message translates to:
  /// **'Edit'**
  String get sourceManagerEdit;

  /// No description provided for @sourceManagerEnabled.
  ///
  /// In en, this message translates to:
  /// **'Enable source'**
  String get sourceManagerEnabled;

  /// No description provided for @sourceManagerSelect.
  ///
  /// In en, this message translates to:
  /// **'Select source'**
  String get sourceManagerSelect;

  /// No description provided for @sourceManagerCancel.
  ///
  /// In en, this message translates to:
  /// **'Cancel'**
  String get sourceManagerCancel;

  /// No description provided for @sourceManagerConfirm.
  ///
  /// In en, this message translates to:
  /// **'Confirm'**
  String get sourceManagerConfirm;

  /// No description provided for @sourceManagerRetry.
  ///
  /// In en, this message translates to:
  /// **'Retry'**
  String get sourceManagerRetry;

  /// No description provided for @sourceManagerDraft.
  ///
  /// In en, this message translates to:
  /// **'Value'**
  String get sourceManagerDraft;

  /// No description provided for @sourceManagerJsBadge.
  ///
  /// In en, this message translates to:
  /// **'JS source'**
  String get sourceManagerJsBadge;

  /// No description provided for @sourceManagerExploreOnBadge.
  ///
  /// In en, this message translates to:
  /// **'Browse enabled'**
  String get sourceManagerExploreOnBadge;

  /// No description provided for @sourceManagerExploreOffBadge.
  ///
  /// In en, this message translates to:
  /// **'Browse disabled'**
  String get sourceManagerExploreOffBadge;

  /// No description provided for @sourceManagerDrag.
  ///
  /// In en, this message translates to:
  /// **'Hold to reorder'**
  String get sourceManagerDrag;

  /// No description provided for @sourceManagerSlide.
  ///
  /// In en, this message translates to:
  /// **'Hold and slide to select'**
  String get sourceManagerSlide;

  /// No description provided for @sourceManagerEmpty.
  ///
  /// In en, this message translates to:
  /// **'No matching sources'**
  String get sourceManagerEmpty;

  /// No description provided for @sourceManagerGroups.
  ///
  /// In en, this message translates to:
  /// **'Groups'**
  String get sourceManagerGroups;

  /// No description provided for @sourceManagerAdd.
  ///
  /// In en, this message translates to:
  /// **'Add source'**
  String get sourceManagerAdd;

  /// No description provided for @sourceManagerAddJs.
  ///
  /// In en, this message translates to:
  /// **'Add JavaScript source'**
  String get sourceManagerAddJs;

  /// No description provided for @sourceManagerQr.
  ///
  /// In en, this message translates to:
  /// **'Import QR code'**
  String get sourceManagerQr;

  /// No description provided for @sourceManagerLocal.
  ///
  /// In en, this message translates to:
  /// **'Import local file'**
  String get sourceManagerLocal;

  /// No description provided for @sourceManagerOnline.
  ///
  /// In en, this message translates to:
  /// **'Import online'**
  String get sourceManagerOnline;

  /// No description provided for @sourceManagerGroupManage.
  ///
  /// In en, this message translates to:
  /// **'Manage groups'**
  String get sourceManagerGroupManage;

  /// No description provided for @sourceManagerFilterEnabled.
  ///
  /// In en, this message translates to:
  /// **'Enabled'**
  String get sourceManagerFilterEnabled;

  /// No description provided for @sourceManagerFilterDisabled.
  ///
  /// In en, this message translates to:
  /// **'Disabled'**
  String get sourceManagerFilterDisabled;

  /// No description provided for @sourceManagerFilterLogin.
  ///
  /// In en, this message translates to:
  /// **'Requires login'**
  String get sourceManagerFilterLogin;

  /// No description provided for @sourceManagerFilterNoGroup.
  ///
  /// In en, this message translates to:
  /// **'Ungrouped'**
  String get sourceManagerFilterNoGroup;

  /// No description provided for @sourceManagerFilterExplore.
  ///
  /// In en, this message translates to:
  /// **'Browse enabled sources'**
  String get sourceManagerFilterExplore;

  /// No description provided for @sourceManagerFilterNoExplore.
  ///
  /// In en, this message translates to:
  /// **'Browse disabled sources'**
  String get sourceManagerFilterNoExplore;

  /// No description provided for @sourceManagerDescending.
  ///
  /// In en, this message translates to:
  /// **'Descending'**
  String get sourceManagerDescending;

  /// No description provided for @sourceManagerDomain.
  ///
  /// In en, this message translates to:
  /// **'Group by domain'**
  String get sourceManagerDomain;

  /// No description provided for @sourceManagerShowStatus.
  ///
  /// In en, this message translates to:
  /// **'Show check status'**
  String get sourceManagerShowStatus;

  /// No description provided for @sourceManagerBlockNavigation.
  ///
  /// In en, this message translates to:
  /// **'Block source navigation'**
  String get sourceManagerBlockNavigation;

  /// No description provided for @sourceManagerRefresh.
  ///
  /// In en, this message translates to:
  /// **'Refresh'**
  String get sourceManagerRefresh;

  /// No description provided for @sourceManagerHelp.
  ///
  /// In en, this message translates to:
  /// **'Help'**
  String get sourceManagerHelp;

  /// No description provided for @sourceManagerSortDefault.
  ///
  /// In en, this message translates to:
  /// **'Manual order'**
  String get sourceManagerSortDefault;

  /// No description provided for @sourceManagerSortWeight.
  ///
  /// In en, this message translates to:
  /// **'Weight order'**
  String get sourceManagerSortWeight;

  /// No description provided for @sourceManagerSortName.
  ///
  /// In en, this message translates to:
  /// **'Sort by name'**
  String get sourceManagerSortName;

  /// No description provided for @sourceManagerSortUrl.
  ///
  /// In en, this message translates to:
  /// **'Sort by URL'**
  String get sourceManagerSortUrl;

  /// No description provided for @sourceManagerSortUpdate.
  ///
  /// In en, this message translates to:
  /// **'Sort by update time'**
  String get sourceManagerSortUpdate;

  /// No description provided for @sourceManagerSortRespond.
  ///
  /// In en, this message translates to:
  /// **'Sort by response time'**
  String get sourceManagerSortRespond;

  /// No description provided for @sourceManagerSortEnable.
  ///
  /// In en, this message translates to:
  /// **'Sort by enabled state'**
  String get sourceManagerSortEnable;

  /// No description provided for @sourceManagerEnableSelection.
  ///
  /// In en, this message translates to:
  /// **'Enable selected'**
  String get sourceManagerEnableSelection;

  /// No description provided for @sourceManagerDisableSelection.
  ///
  /// In en, this message translates to:
  /// **'Disable selected'**
  String get sourceManagerDisableSelection;

  /// No description provided for @sourceManagerAddGroup.
  ///
  /// In en, this message translates to:
  /// **'Add group'**
  String get sourceManagerAddGroup;

  /// No description provided for @sourceManagerRemoveGroup.
  ///
  /// In en, this message translates to:
  /// **'Remove group'**
  String get sourceManagerRemoveGroup;

  /// No description provided for @sourceManagerEnableExplore.
  ///
  /// In en, this message translates to:
  /// **'Enable browse'**
  String get sourceManagerEnableExplore;

  /// No description provided for @sourceManagerDisableExplore.
  ///
  /// In en, this message translates to:
  /// **'Disable browse'**
  String get sourceManagerDisableExplore;

  /// No description provided for @sourceManagerTopSelection.
  ///
  /// In en, this message translates to:
  /// **'Move selected to top'**
  String get sourceManagerTopSelection;

  /// No description provided for @sourceManagerBottomSelection.
  ///
  /// In en, this message translates to:
  /// **'Move selected to bottom'**
  String get sourceManagerBottomSelection;

  /// No description provided for @sourceManagerExport.
  ///
  /// In en, this message translates to:
  /// **'Export selected'**
  String get sourceManagerExport;

  /// No description provided for @sourceManagerShare.
  ///
  /// In en, this message translates to:
  /// **'Share selected'**
  String get sourceManagerShare;

  /// No description provided for @sourceManagerCheck.
  ///
  /// In en, this message translates to:
  /// **'Check selected'**
  String get sourceManagerCheck;

  /// No description provided for @sourceManagerInterval.
  ///
  /// In en, this message translates to:
  /// **'Select interval'**
  String get sourceManagerInterval;

  /// No description provided for @sourceManagerTop.
  ///
  /// In en, this message translates to:
  /// **'Move to top'**
  String get sourceManagerTop;

  /// No description provided for @sourceManagerBottom.
  ///
  /// In en, this message translates to:
  /// **'Move to bottom'**
  String get sourceManagerBottom;

  /// No description provided for @sourceManagerUp.
  ///
  /// In en, this message translates to:
  /// **'Move up'**
  String get sourceManagerUp;

  /// No description provided for @sourceManagerDown.
  ///
  /// In en, this message translates to:
  /// **'Move down'**
  String get sourceManagerDown;

  /// No description provided for @sourceManagerLogin.
  ///
  /// In en, this message translates to:
  /// **'Login'**
  String get sourceManagerLogin;

  /// No description provided for @sourceManagerSearch.
  ///
  /// In en, this message translates to:
  /// **'Search'**
  String get sourceManagerSearch;

  /// No description provided for @sourceManagerDebug.
  ///
  /// In en, this message translates to:
  /// **'Debug'**
  String get sourceManagerDebug;

  /// No description provided for @sourceManagerExplore.
  ///
  /// In en, this message translates to:
  /// **'Toggle browse'**
  String get sourceManagerExplore;

  /// No description provided for @sourceManagerStatusAll.
  ///
  /// In en, this message translates to:
  /// **'All statuses'**
  String get sourceManagerStatusAll;

  /// No description provided for @sourceManagerStatusNeeded.
  ///
  /// In en, this message translates to:
  /// **'Needs check'**
  String get sourceManagerStatusNeeded;

  /// No description provided for @sourceManagerStatusPassed.
  ///
  /// In en, this message translates to:
  /// **'Passed'**
  String get sourceManagerStatusPassed;

  /// No description provided for @sourceManagerStatusFailed.
  ///
  /// In en, this message translates to:
  /// **'Failed'**
  String get sourceManagerStatusFailed;

  /// No description provided for @sourceManagerDeletePrompt.
  ///
  /// In en, this message translates to:
  /// **'Delete the selected sources?'**
  String get sourceManagerDeletePrompt;

  /// No description provided for @sourceManagerCheckKeyword.
  ///
  /// In en, this message translates to:
  /// **'Check keyword'**
  String get sourceManagerCheckKeyword;

  /// No description provided for @sourceManagerExportSuccess.
  ///
  /// In en, this message translates to:
  /// **'Export complete'**
  String get sourceManagerExportSuccess;

  /// No description provided for @sourceManagerPassphrase.
  ///
  /// In en, this message translates to:
  /// **'Share passphrase'**
  String get sourceManagerPassphrase;

  /// No description provided for @sourceManagerCheckConfig.
  ///
  /// In en, this message translates to:
  /// **'Check settings'**
  String get sourceManagerCheckConfig;

  /// No description provided for @sourceManagerForgetImport.
  ///
  /// In en, this message translates to:
  /// **'Forget import history'**
  String get sourceManagerForgetImport;

  /// No description provided for @sourceManagerLoadFailed.
  ///
  /// In en, this message translates to:
  /// **'Cannot read the native source manager. Retry to reconnect.'**
  String get sourceManagerLoadFailed;

  /// No description provided for @sourceManagerCount.
  ///
  /// In en, this message translates to:
  /// **'Selected {selected}/{total}'**
  String sourceManagerCount(int selected, int total);

  /// No description provided for @sourceManagerBookCount.
  ///
  /// In en, this message translates to:
  /// **'{count} books in library'**
  String sourceManagerBookCount(int count);

  /// No description provided for @sourcesManage.
  ///
  /// In en, this message translates to:
  /// **'Source management'**
  String get sourcesManage;

  /// No description provided for @sourcesImport.
  ///
  /// In en, this message translates to:
  /// **'Import'**
  String get sourcesImport;

  /// No description provided for @sourcesImportJson.
  ///
  /// In en, this message translates to:
  /// **'Import source JSON'**
  String get sourcesImportJson;

  /// No description provided for @sourcesEdit.
  ///
  /// In en, this message translates to:
  /// **'Edit source'**
  String get sourcesEdit;

  /// No description provided for @sourcesJsonHint.
  ///
  /// In en, this message translates to:
  /// **'A source object or array of sources'**
  String get sourcesJsonHint;

  /// No description provided for @sourcesCancel.
  ///
  /// In en, this message translates to:
  /// **'Cancel'**
  String get sourcesCancel;

  /// No description provided for @sourcesSave.
  ///
  /// In en, this message translates to:
  /// **'Save'**
  String get sourcesSave;

  /// No description provided for @sourcesCopy.
  ///
  /// In en, this message translates to:
  /// **'Copy all sources as JSON'**
  String get sourcesCopy;

  /// No description provided for @sourcesCopied.
  ///
  /// In en, this message translates to:
  /// **'Sources copied as JSON'**
  String get sourcesCopied;

  /// No description provided for @sourcesEmpty.
  ///
  /// In en, this message translates to:
  /// **'No sources. Import source JSON to get started.'**
  String get sourcesEmpty;

  /// No description provided for @sourcesDelete.
  ///
  /// In en, this message translates to:
  /// **'Delete source'**
  String get sourcesDelete;

  /// No description provided for @sourcesDeleteConfirm.
  ///
  /// In en, this message translates to:
  /// **'Delete this source?'**
  String get sourcesDeleteConfirm;

  /// No description provided for @sourcesDeleteAction.
  ///
  /// In en, this message translates to:
  /// **'Delete'**
  String get sourcesDeleteAction;

  /// No description provided for @sourcesSearchTitle.
  ///
  /// In en, this message translates to:
  /// **'Search books'**
  String get sourcesSearchTitle;

  /// No description provided for @sourcesSourceLabel.
  ///
  /// In en, this message translates to:
  /// **'Source'**
  String get sourcesSourceLabel;

  /// No description provided for @sourcesKeyword.
  ///
  /// In en, this message translates to:
  /// **'Book title or author'**
  String get sourcesKeyword;

  /// No description provided for @sourcesSearch.
  ///
  /// In en, this message translates to:
  /// **'Search'**
  String get sourcesSearch;

  /// No description provided for @sourcesNeedSource.
  ///
  /// In en, this message translates to:
  /// **'Import and enable a source in source management first.'**
  String get sourcesNeedSource;

  /// No description provided for @sourcesSearchPrompt.
  ///
  /// In en, this message translates to:
  /// **'Enter a keyword to search'**
  String get sourcesSearchPrompt;

  /// No description provided for @sourcesNoResults.
  ///
  /// In en, this message translates to:
  /// **'No results'**
  String get sourcesNoResults;

  /// No description provided for @sourcesRefresh.
  ///
  /// In en, this message translates to:
  /// **'Refresh chapters'**
  String get sourcesRefresh;

  /// No description provided for @sourcesAdd.
  ///
  /// In en, this message translates to:
  /// **'Add to bookshelf'**
  String get sourcesAdd;

  /// No description provided for @sourcesAdded.
  ///
  /// In en, this message translates to:
  /// **'Added to bookshelf'**
  String get sourcesAdded;

  /// No description provided for @sourcesEmptyToc.
  ///
  /// In en, this message translates to:
  /// **'The source returned no chapters. Check its rules and website status.'**
  String get sourcesEmptyToc;

  /// No description provided for @sourcesIdentityImmutable.
  ///
  /// In en, this message translates to:
  /// **'Source identity cannot be changed while editing. Import it as a new source instead.'**
  String get sourcesIdentityImmutable;

  /// No description provided for @sourcesChapterCount.
  ///
  /// In en, this message translates to:
  /// **'Contents · {count} chapters'**
  String sourcesChapterCount(int count);

  /// No description provided for @sourcesDetailError.
  ///
  /// In en, this message translates to:
  /// **'Could not load details or chapters\n{error}'**
  String sourcesDetailError(String error);

  /// No description provided for @sourcesBrowse.
  ///
  /// In en, this message translates to:
  /// **'Browse categories'**
  String get sourcesBrowse;

  /// No description provided for @sourcesCategory.
  ///
  /// In en, this message translates to:
  /// **'Category'**
  String get sourcesCategory;

  /// No description provided for @sourcesNoCategories.
  ///
  /// In en, this message translates to:
  /// **'This source has no static browse categories.'**
  String get sourcesNoCategories;

  /// No description provided for @sourcesPrevious.
  ///
  /// In en, this message translates to:
  /// **'Previous page'**
  String get sourcesPrevious;

  /// No description provided for @sourcesNext.
  ///
  /// In en, this message translates to:
  /// **'Next page'**
  String get sourcesNext;

  /// No description provided for @sourcesPage.
  ///
  /// In en, this message translates to:
  /// **'Page {page}'**
  String sourcesPage(int page);
}

class _AppLocalizationsDelegate
    extends LocalizationsDelegate<AppLocalizations> {
  const _AppLocalizationsDelegate();

  @override
  Future<AppLocalizations> load(Locale locale) {
    return SynchronousFuture<AppLocalizations>(lookupAppLocalizations(locale));
  }

  @override
  bool isSupported(Locale locale) =>
      <String>['en', 'zh'].contains(locale.languageCode);

  @override
  bool shouldReload(_AppLocalizationsDelegate old) => false;
}

AppLocalizations lookupAppLocalizations(Locale locale) {
  // Lookup logic when only language code is specified.
  switch (locale.languageCode) {
    case 'en':
      return AppLocalizationsEn();
    case 'zh':
      return AppLocalizationsZh();
  }

  throw FlutterError(
    'AppLocalizations.delegate failed to load unsupported locale "$locale". This is likely '
    'an issue with the localizations generation tool. Please file an issue '
    'on GitHub with a reproducible sample app and the gen-l10n configuration '
    'that was used.',
  );
}
