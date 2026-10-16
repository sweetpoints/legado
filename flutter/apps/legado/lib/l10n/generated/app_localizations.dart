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
