// ignore: unused_import
import 'package:intl/intl.dart' as intl;

import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppLocalizationsEn extends AppLocalizations {
  AppLocalizationsEn([String locale = 'en']) : super(locale);

  @override
  String get allBookmarksTitle => 'All bookmarks';

  @override
  String get allBookmarksExportJson => 'Export JSON';

  @override
  String get allBookmarksExportMarkdown => 'Export Markdown';

  @override
  String get allBookmarksRetry => 'Retry';

  @override
  String get allBookmarksError => 'Unable to load bookmarks. Try again.';

  @override
  String get appTitle => 'Legado';

  @override
  String get bookshelfTitle => 'Bookshelf';

  @override
  String get exploreTitle => 'Explore';

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
      'The text encoding is ambiguous, unsupported or damaged. UTF-8, UTF-16 with a BOM and GBK are supported. Check the encoding and convert the file to UTF-8 before importing.';

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

  @override
  String get navigationRssTitle => 'Feeds';

  @override
  String get epubTooLarge => 'This EPUB exceeds the 128 MiB input limit.';

  @override
  String get licensesTitle => 'Licenses';

  @override
  String get appLogsTitle => 'Logs';

  @override
  String get appLogsExport => 'Export';

  @override
  String get appLogsEmpty => 'No logs';

  @override
  String get appLogsShareFailed => 'Unable to share logs';

  @override
  String get appLogsClearConfirm => 'Clear all logs?';

  @override
  String get diagnosticsClose => 'Close';

  @override
  String get diagnosticsClear => 'Clear';

  @override
  String get diagnosticsCancel => 'Cancel';

  @override
  String get diagnosticsRetry => 'Retry';

  @override
  String get diagnosticsError => 'Unable to load logs. Try again.';

  @override
  String get diagnosticsDetails => 'Details';

  @override
  String get diagnosticsPrevious => 'Previous';

  @override
  String get diagnosticsNext => 'Next';

  @override
  String get bookmarkEditorTitle => 'Bookmark';

  @override
  String get bookmarkEditorText => 'Content';

  @override
  String get bookmarkEditorContent => 'Note';

  @override
  String get bookmarkEditorDelete => 'Delete';

  @override
  String get bookmarkEditorCancel => 'Cancel';

  @override
  String get bookmarkEditorConfirm => 'OK';

  @override
  String get bookmarkEditorBridgeError =>
      'Bookmark editor is unavailable. Retry.';

  @override
  String get shelfOptions => 'Bookshelf options';

  @override
  String get shelfManageGroups => 'Manage groups';

  @override
  String get shelfCreateGroup => 'Create group';

  @override
  String get shelfRenameGroup => 'Rename group';

  @override
  String get shelfDeleteGroup => 'Delete group';

  @override
  String get shelfDeleteGroupMessage =>
      'Books will stay in your library. Only membership in this group will be removed.';

  @override
  String get shelfGroupName => 'Group name';

  @override
  String get shelfGroupNameRequired => 'Enter a group name.';

  @override
  String get shelfNoGroups => 'Create a group before assigning books.';

  @override
  String get shelfAssignGroups => 'Assign groups';

  @override
  String get shelfGroup => 'Group';

  @override
  String get shelfSort => 'Sort';

  @override
  String get shelfAll => 'All books';

  @override
  String get shelfUngrouped => 'Ungrouped';

  @override
  String get shelfLocal => 'Local books';

  @override
  String get shelfAudio => 'Audio books';

  @override
  String get shelfVideo => 'Video books';

  @override
  String get shelfNetworkUngrouped => 'Ungrouped online books';

  @override
  String get shelfLocalUngrouped => 'Ungrouped local books';

  @override
  String get shelfUpdateFailed => 'Update failed';

  @override
  String get shelfSortRead => 'Recently read';

  @override
  String get shelfSortUpdated => 'Latest update';

  @override
  String get shelfSortTitle => 'Title';

  @override
  String get shelfSortManual => 'Manual order';

  @override
  String get shelfSortActivity => 'Recent activity';

  @override
  String get shelfSortAuthor => 'Author';

  @override
  String get shelfBookActions => 'Book actions';

  @override
  String get shelfMoveUp => 'Move up';

  @override
  String get shelfMoveDown => 'Move down';

  @override
  String get shelfEmptyGroup => 'There are no books in this group.';

  @override
  String get shelfImportEncodedText => 'Import TXT with encoding';

  @override
  String get shelfChooseTextEncoding => 'Choose TXT encoding';

  @override
  String get shelfTextEncodingHint =>
      'Choose the file’s known encoding. A byte order mark (BOM) takes priority; a conflicting choice will stop the import.';

  @override
  String get shelfEncodingUtf8 => 'UTF-8';

  @override
  String get shelfEncodingGbk => 'GBK / GB2312';

  @override
  String get shelfEncodingUtf16Le => 'UTF-16 Little Endian';

  @override
  String get shelfEncodingUtf16Be => 'UTF-16 Big Endian';

  @override
  String get shelfEncodingConflict =>
      'The selected encoding conflicts with this file’s BOM. Choose the encoding indicated by the BOM, or use automatic TXT import.';

  @override
  String get shelfEncodingUnavailable =>
      'This file adapter cannot import a chosen encoding. Use a supported file adapter; automatic detection was not used.';

  @override
  String get codeEditorTitle => 'Rule editor';

  @override
  String get codeEditorClose => 'Close';

  @override
  String get codeEditorSave => 'Save';

  @override
  String get codeEditorSearch => 'Search';

  @override
  String get codeEditorDebug => 'Debug source';

  @override
  String get codeEditorLogin => 'Log in';

  @override
  String get codeEditorMenu => 'More actions';

  @override
  String get codeEditorTheme => 'Editor theme';

  @override
  String get codeEditorSelectAll => 'Select all';

  @override
  String get codeEditorFormat => 'Format code';

  @override
  String get codeEditorSyntax => 'Check JavaScript syntax';

  @override
  String get codeEditorCurl => 'Convert cURL';

  @override
  String get codeEditorSettings => 'Editor settings';

  @override
  String get codeEditorWrap => 'Wrap lines';

  @override
  String get codeEditorLog => 'Log';

  @override
  String get codeEditorQuery => 'Search';

  @override
  String get codeEditorReplace => 'Replace';

  @override
  String get codeEditorRegex => 'Regular expression';

  @override
  String get codeEditorIgnoreCase => 'Ignore case';

  @override
  String get codeEditorPrevious => 'Previous match';

  @override
  String get codeEditorNext => 'Next match';

  @override
  String get codeEditorReplaceAll => 'Replace all';

  @override
  String get codeEditorHelp => 'Help';

  @override
  String get codeEditorKeyboardConfig => 'Configure input keys';

  @override
  String get codeEditorRuleHelp => 'Source reference';

  @override
  String get codeEditorRssHelp => 'RSS reference';

  @override
  String get codeEditorJsHelp => 'JavaScript reference';

  @override
  String get codeEditorRegexHelp => 'Regular expression reference';

  @override
  String get codeEditorUndo => 'Undo';

  @override
  String get codeEditorRedo => 'Redo';

  @override
  String get codeEditorRetry => 'Retry';

  @override
  String get codeEditorRestart => 'Restart editor';

  @override
  String get codeEditorLoadingFailed => 'Unable to load editor';

  @override
  String get codeEditorLoadFailed =>
      'The editor could not load this document. Restart to try again.';

  @override
  String get codeEditorReplacementFailed => 'Replacement failed';

  @override
  String get codeEditorDiscardTitle => 'Discard unsaved changes?';

  @override
  String get codeEditorDiscardBody =>
      'Your changes have not been saved. Closing will discard this draft.';

  @override
  String get codeEditorDiscard => 'Discard changes';

  @override
  String get codeEditorKeepEditing => 'Keep editing';

  @override
  String get codeEditorPlatformUnavailable =>
      'This embedded editing surface is available on Android.';

  @override
  String get codeEditorSearchRejected =>
      'The search update was rejected and restored. Retry or edit the search again before saving or replacing.';

  @override
  String get crashLogsTitle => 'Crash logs';

  @override
  String get crashLogsEmpty => 'No crash logs';

  @override
  String get dictionaryEditorTitle => 'Dictionary rule';

  @override
  String get dictionaryEditorName => 'Name';

  @override
  String get dictionaryEditorUrl => 'URL rule';

  @override
  String get dictionaryEditorShow => 'Display rule';

  @override
  String get dictionaryEditorFullscreen => 'Fullscreen editor';

  @override
  String get dictionaryEditorSave => 'Save';

  @override
  String get dictionaryEditorMenu => 'Menu';

  @override
  String get dictionaryEditorCopy => 'Copy rule';

  @override
  String get dictionaryEditorPaste => 'Paste rule';

  @override
  String get dictionaryEditorBridgeError =>
      'Field not acknowledged. Edit it again to retry.';

  @override
  String get dictionaryEditorExit => 'Exit';

  @override
  String get dictionaryEditorExitMessage => 'Keep editing the unsaved rule?';

  @override
  String get dictionaryEditorKeep => 'Keep editing';

  @override
  String get dictionaryEditorDiscard => 'Discard changes';

  @override
  String get highlightEditorBridgeError =>
      'Field not acknowledged. Edit it again to retry.';

  @override
  String get highlightColorBridgeError =>
      'Color input not acknowledged. Edit it again to retry.';

  @override
  String get highlightColorInvalidHex => 'Enter a valid hexadecimal color.';

  @override
  String get highlightEditorPendingExit =>
      'Some input has not been acknowledged. Keep editing?';

  @override
  String get highlightEditorKeep => 'Keep editing';

  @override
  String get highlightEditorDiscard => 'Discard changes';

  @override
  String get highlightManagerUnavailable =>
      'Highlight operation could not be completed. Retry or cancel.';

  @override
  String get namedGroupsTitle => 'Group management';

  @override
  String get namedGroupsAdd => 'Add group';

  @override
  String get namedGroupsEdit => 'Edit';

  @override
  String get namedGroupsDelete => 'Delete';

  @override
  String get namedGroupsName => 'Group name';

  @override
  String get namedGroupsConfirm => 'OK';

  @override
  String get namedGroupsCancel => 'Cancel';

  @override
  String get namedGroupsRetry => 'Retry';

  @override
  String get namedGroupsError => 'Group operation failed';

  @override
  String get pdfTitle => 'PDF reader';

  @override
  String get pdfImportTitle => 'Import PDF';

  @override
  String get pdfImportDescription =>
      'Import a local PDF up to 32 MiB. Your document is stored in the library for offline reading.';

  @override
  String get pdfChooseFile => 'Choose PDF';

  @override
  String get pdfTooLarge => 'This PDF exceeds the 32 MiB limit.';

  @override
  String get pdfEncrypted =>
      'Encrypted PDFs are not supported. Import an unencrypted copy.';

  @override
  String get pdfUnsupportedStorage =>
      'This library does not support local PDF storage.';

  @override
  String get pdfOpenFailed => 'The PDF is missing, damaged, or unsupported.';

  @override
  String get pdfSaveFailed => 'Reading progress could not be saved.';

  @override
  String get pdfPreviousPage => 'Previous page';

  @override
  String get pdfNextPage => 'Next page';

  @override
  String pdfPagePosition(int page, int total) {
    return 'Page $page of $total';
  }

  @override
  String get readerTitle => 'Read';

  @override
  String get readerSettings => 'Reading settings';

  @override
  String get readerFontSize => 'Font size';

  @override
  String get readerSystemTheme => 'System';

  @override
  String get readerLightTheme => 'Light';

  @override
  String get readerDarkTheme => 'Dark';

  @override
  String get readerDone => 'Done';

  @override
  String get readerRetry => 'Retry';

  @override
  String get readerPreviousChapter => 'Previous chapter';

  @override
  String get readerNextChapter => 'Next chapter';

  @override
  String get readerCatalogue => 'Chapters';

  @override
  String readerCatalogueCount(int count) {
    return 'Chapters ($count)';
  }

  @override
  String readerChapterPosition(int current, int total, String title) {
    return '$current/$total · $title';
  }

  @override
  String get readerBookMissing =>
      'This book is missing. Return to the library and reopen it.';

  @override
  String get readerTocMissing =>
      'No chapter catalogue is available. Load it from the book details first.';

  @override
  String get readerTocEmpty => 'The catalogue contains no readable chapters.';

  @override
  String get readerContentMissing =>
      'This chapter is not cached. Download it from book details or retry.';

  @override
  String readerProgressSaveFailed(String error) {
    return 'Could not save reading progress: $error';
  }

  @override
  String readerSettingsSaveFailed(String error) {
    return 'Could not save reading settings: $error';
  }

  @override
  String get readerEpubInvalidArchive =>
      'The EPUB archive is corrupt or exceeds the import limits.';

  @override
  String get readerEpubInvalidPackage =>
      'The EPUB package, catalogue, or chapter files are invalid.';

  @override
  String get readerEpubEncrypted =>
      'Encrypted or obfuscated EPUB resources are not supported.';

  @override
  String get readerEpubUnsupported =>
      'This EPUB contains a chapter format that the text reader cannot display.';

  @override
  String get readerEpubEmpty => 'This EPUB contains no readable text.';

  @override
  String get readerEpubImport => 'Import EPUB';

  @override
  String get readerBookmarks => 'Bookmarks';

  @override
  String get readerAddBookmark => 'Bookmark this position';

  @override
  String get readerBookmarkNote => 'Note (optional)';

  @override
  String get readerBookmarkCancel => 'Cancel';

  @override
  String get readerBookmarkSave => 'Save';

  @override
  String get readerBookmarkAdded => 'Bookmark saved';

  @override
  String readerBookmarkFailed(String error) {
    return 'Could not update bookmarks: $error';
  }

  @override
  String get readerBookmarksEmpty => 'No bookmarks yet';

  @override
  String get readerBookmarkUnavailable =>
      'This bookmark cannot be matched to the current book and chapter.';

  @override
  String get readerDeleteBookmark => 'Delete bookmark';

  @override
  String get rssTitle => 'Subscriptions';

  @override
  String get rssAddTooltip => 'Add or import subscriptions';

  @override
  String get rssEmpty =>
      'Add an RSS/Atom URL or import legacy subscription JSON';

  @override
  String get rssLegacySubtitle => 'CSS/DOM HTML subscription';

  @override
  String get rssAdd => 'Add subscription';

  @override
  String get rssImportHelp =>
      'Paste an RSS/Atom URL or legacy rssSources.json. CSS/DOM HTML rules are supported; JavaScript rules are preserved with explicit capability errors.';

  @override
  String get rssInput => 'URL or subscription JSON';

  @override
  String get rssCancel => 'Cancel';

  @override
  String get rssSaving => 'Saving…';

  @override
  String get rssSave => 'Save';

  @override
  String get rssCancelRefresh => 'Cancel refresh';

  @override
  String get rssRefresh => 'Refresh feed';

  @override
  String get rssLegacyHelp =>
      'HTML lists, CSS/DOM rules and safe templates are supported. JavaScript and browser verification require a separate host.';

  @override
  String get rssFetch => 'Fetch articles';

  @override
  String get rssArticle => 'Article';

  @override
  String get rssMissingArticle =>
      'Article unavailable. Go back and refresh the subscription.';

  @override
  String get rssUnstar => 'Remove bookmark';

  @override
  String get rssStar => 'Bookmark article';

  @override
  String get rssCopyLink => 'Copy original link';

  @override
  String get rssCopied => 'Original link copied';

  @override
  String get rssRetry => 'Retry';

  @override
  String get rssInvalidUrl => 'Subscription URL must use HTTP or HTTPS';

  @override
  String get rssCorruptStorage =>
      'Subscription data is invalid. Restore a backup; the original data is preserved.';

  @override
  String get rssInvalidSource => 'Each subscription must be a JSON object';

  @override
  String get rssEmptyResponse => 'The feed returned an empty response';

  @override
  String get rssTooLarge => 'The feed exceeds the 5 MiB limit';

  @override
  String get rssInvalidFeed =>
      'Cannot parse RSS/Atom. Check the URL and feed format.';

  @override
  String get rssNetworkError =>
      'Feed request failed. Check the network and URL, then retry.';

  @override
  String get rssOperationError =>
      'Subscription operation failed. Retry; existing data is preserved.';

  @override
  String get rssScriptCapability =>
      'This rule requires a JavaScript or browser host which is not connected yet. The original rules are preserved; use the existing Android app.';

  @override
  String get rssUnsupportedRule =>
      'This rule format is not supported yet. Use CSS/Legado DOM rules or the existing Android app.';

  @override
  String get rssPaginationCycle =>
      'Pagination contains a cycle. Requests stopped; fix the next-page rule.';

  @override
  String get rssPageLimit =>
      'Pagination exceeded 20 pages. Requests stopped; narrow the category.';

  @override
  String get rssInvalidArticleLink =>
      'The article link is invalid. Check the link extraction rule.';

  @override
  String get rssImageError =>
      'Image could not load. Copy the original article link to view it.';

  @override
  String get rssDelete => 'Delete subscription';

  @override
  String get rssDeleteHelp =>
      'The subscription will stay deleted across restarts. Original backups, offline articles and bookmarks are kept. Explicitly importing the same URL adds it back.';

  @override
  String rssRestoreSummary(int restored, int conflicts) {
    return 'Legacy backup: $restored subscriptions available; $conflicts URL conflicts keep the current configuration. Original backup data is unchanged.';
  }

  @override
  String get rssInvalidRestore =>
      'The legacy subscription backup is invalid. Original data is preserved; check rssSources.json.';

  @override
  String get rssDuplicateRestoreSource =>
      'The legacy backup contains duplicate sourceUrl identities. Fix the ambiguity and restore again. Original data is preserved.';

  @override
  String get rssFavoritesTitle => 'RSS favorites';

  @override
  String get rssFavoritesGroups => 'Groups';

  @override
  String get rssFavoritesMenu => 'Menu';

  @override
  String get rssFavoritesDeleteGroup => 'Delete current group';

  @override
  String get rssFavoritesDeleteAll => 'Delete all favorites';

  @override
  String get rssFavoritesError => 'Favorites operation failed. Please retry.';

  @override
  String get rssFavoritesConfirmDelete => 'Confirm deletion?';

  @override
  String get rssFavoriteConfigHeading => 'Favorite';

  @override
  String get rssFavoriteConfigTitle => 'Title';

  @override
  String get rssFavoriteConfigGroup => 'Group name';

  @override
  String get rssFavoriteConfigConfirm => 'OK';

  @override
  String get rssEditorTitle => 'RSS source editor';

  @override
  String get rssEditorEditRejected =>
      'The edit was rejected. Correct the field before saving.';

  @override
  String get rssEditorFailed => 'Unable to update the RSS source editor.';

  @override
  String get rssEditorMissing => 'The RSS source is no longer available.';

  @override
  String get rssEditorRequired => 'Source name and URL are required.';

  @override
  String get rssEditorFormat => 'Invalid source format.';

  @override
  String get rssEditorFocus => 'Focus a text field first.';

  @override
  String get rssEditorNoLogin => 'This source has no login.';

  @override
  String get rssEditorEnableJs => 'Enable JavaScript';

  @override
  String get rssEditorBaseUrl => 'Load with base URL';

  @override
  String get rssEditorWebLog => 'Show WebView log';

  @override
  String get rssEditorCacheFirst => 'Prefer cache';

  @override
  String get rssEditorSingleUrl => 'Single URL';

  @override
  String get rssEditorPreload => 'Enable preload';

  @override
  String get rssEditorArticleStyle => 'Article layout';

  @override
  String get rssEditorExternal =>
      'Open the full screen editor to edit this field safely.';

  @override
  String get rssEditorUrlOverride =>
      'URL navigation interception (JavaScript; return true to intercept; url is available to open links, search, or add books)';

  @override
  String get rssEditorStart => 'Start';

  @override
  String get rssEditorList => 'List rules';

  @override
  String get rssEditorWebView => 'WebView';

  @override
  String get rssEditorWebPage => 'Web page';

  @override
  String get rssEditorLayoutList => 'List';

  @override
  String get rssEditorLayoutSingle => 'Single column';

  @override
  String get rssEditorLayoutDouble => 'Two columns';

  @override
  String get rssEditorLayoutWaterfall => 'Masonry';

  @override
  String get rssEditorLayoutTriple => 'Three columns';

  @override
  String get rssEditorType => 'Source type';

  @override
  String get ruleSubscriptionsTitle => 'Rule subscriptions';

  @override
  String get ruleSubscriptionsEmpty =>
      'No subscriptions. Add a subscription URL to begin.';

  @override
  String get ruleSubscriptionsName => 'Name';

  @override
  String get ruleSubscriptionsType => 'Type';

  @override
  String get ruleSubscriptionsBook => 'Book sources';

  @override
  String get ruleSubscriptionsRss => 'RSS sources';

  @override
  String get ruleSubscriptionsReplace => 'Replacement rules';

  @override
  String get ruleSubscriptionsAutomatic => 'Automatic update';

  @override
  String get ruleSubscriptionsSilent => 'Silent update';

  @override
  String get ruleSubscriptionsInterval => 'Update interval (hours)';

  @override
  String get ruleSubscriptionsPending =>
      'Save is pending. Retry to finish the existing save.';

  @override
  String get ruleSubscriptionsRejected =>
      'An edit was not acknowledged. Edit the field again before saving.';

  @override
  String get ruleSubscriptionsEmptyUrl => 'Enter a subscription URL.';

  @override
  String get ruleSubscriptionsDuplicate => 'This URL already exists.';

  @override
  String get ruleSubscriptionsMissing => 'This subscription no longer exists.';

  @override
  String get ruleSubscriptionsConflict =>
      'The subscription changed elsewhere. Cancel and reopen it.';

  @override
  String get ruleSubscriptionsFailure => 'The operation failed. Retry.';

  @override
  String get ruleSubscriptionsUp => 'Move up';

  @override
  String get ruleSubscriptionsDown => 'Move down';

  @override
  String get ruleSubscriptionsAdd => 'Add';

  @override
  String get ruleSubscriptionsEdit => 'Edit';

  @override
  String get ruleSubscriptionsActions => 'Actions';

  @override
  String get debuggerTitle => 'Source debugger';

  @override
  String get debuggerSearch => 'Search';

  @override
  String get debuggerClose => 'Back';

  @override
  String get debuggerMenu => 'More options';

  @override
  String get debuggerScan => 'Scan QR code';

  @override
  String get debuggerRefresh => 'Refresh explore categories';

  @override
  String get debuggerInstructions => 'Help';

  @override
  String get debuggerRetry => 'Retry';

  @override
  String get debuggerSearchHtml => 'Search HTML';

  @override
  String get debuggerBookHtml => 'Book details HTML';

  @override
  String get debuggerTocHtml => 'Table of contents HTML';

  @override
  String get debuggerContentHtml => 'Content HTML';

  @override
  String get debuggerListHtml => 'List HTML';

  @override
  String get debuggerCategories => 'Choose category';

  @override
  String get debuggerSearchHelp =>
      'Debug search: enter a keyword, for example:';

  @override
  String get debuggerBookCategoryHelp =>
      'Debug explore: enter an explore URL, for example:';

  @override
  String get debuggerRssCategoryHelp =>
      'Debug category: enter a category URL, for example:';

  @override
  String get debuggerDetailsHelp =>
      'Debug book information: enter the details URL:';

  @override
  String get debuggerTocHelp =>
      'Debug table of contents: enter a URL prefixed with ++:';

  @override
  String get debuggerContentHelp =>
      'Debug chapter content: enter a URL prefixed with --:';

  @override
  String get debuggerRssContentHelp =>
      'Debug content: enter a content page URL:';

  @override
  String get debuggerBusy => 'Debug channel is busy. Try again later.';

  @override
  String get debuggerInterrupted =>
      'The previous debug run was interrupted. Run again to continue.';

  @override
  String get debuggerConnectionError =>
      'Unable to connect to the source debugger.';

  @override
  String get debuggerCommandError =>
      'The action could not be completed. Please try again.';

  @override
  String get debuggerNoCategories => 'No categories are available.';

  @override
  String get debuggerLoading => 'Loading source…';

  @override
  String get debuggerLinkError => 'Unable to open this link.';

  @override
  String get sourceEditorTitle => 'Edit book source';

  @override
  String get sourceEditorClose => 'Close';

  @override
  String get sourceEditorSave => 'Save';

  @override
  String get sourceEditorFullScreen => 'Rule editor';

  @override
  String get sourceEditorMenu => 'More actions';

  @override
  String get sourceEditorBase => 'Basic';

  @override
  String get sourceEditorSearch => 'Search';

  @override
  String get sourceEditorExplore => 'Explore';

  @override
  String get sourceEditorInfo => 'Details';

  @override
  String get sourceEditorToc => 'Contents';

  @override
  String get sourceEditorContent => 'Content';

  @override
  String get sourceEditorReview => 'Reviews';

  @override
  String get sourceEditorEnabled => 'Enabled';

  @override
  String get sourceEditorExploreEnabled => 'Discovery';

  @override
  String get sourceEditorCookie => 'Save cookies';

  @override
  String get sourceEditorReviewEnabled => 'Reviews';

  @override
  String get sourceEditorListener => 'Event listener';

  @override
  String get sourceEditorCustomButton => 'Custom button';

  @override
  String get sourceEditorText => 'Text';

  @override
  String get sourceEditorAudio => 'Audio';

  @override
  String get sourceEditorImage => 'Image';

  @override
  String get sourceEditorFile => 'File';

  @override
  String get sourceEditorVideo => 'Video';

  @override
  String get sourceEditorDebug => 'Debug source';

  @override
  String get sourceEditorLogin => 'Log in';

  @override
  String get sourceEditorClearCookie => 'Clear cookies';

  @override
  String get sourceEditorAutoComplete => 'Auto complete';

  @override
  String get sourceEditorCopy => 'Copy source';

  @override
  String get sourceEditorPaste => 'Paste source';

  @override
  String get sourceEditorQr => 'Scan QR code';

  @override
  String get sourceEditorShare => 'Share source';

  @override
  String get sourceEditorQrShare => 'Share QR code';

  @override
  String get sourceEditorVariable => 'Source variable';

  @override
  String get sourceEditorLog => 'Log';

  @override
  String get sourceEditorHelp => 'Source reference';

  @override
  String get sourceEditorRetry => 'Retry';

  @override
  String get sourceEditorDiscardTitle => 'Discard unsaved changes?';

  @override
  String get sourceEditorDiscardBody =>
      'Your changes have not been saved. Closing will discard this draft.';

  @override
  String get sourceEditorDiscard => 'Discard changes';

  @override
  String get sourceEditorKeepEditing => 'Keep editing';

  @override
  String get sourceEditorGroups => 'Source groups';

  @override
  String get sourceEditorCancel => 'Cancel';

  @override
  String get sourceEditorUndo => 'Undo';

  @override
  String get sourceEditorRedo => 'Redo';

  @override
  String get sourceEditorKeyboardHelp => 'Input tools';

  @override
  String get sourceEditorKeyboardConfig => 'Configure input keys';

  @override
  String get sourceEditorUrlOptions => 'Insert URL options';

  @override
  String get sourceEditorJsHelp => 'JavaScript reference';

  @override
  String get sourceEditorRegexHelp => 'Regular expression reference';

  @override
  String get sourceEditorInsertGroup => 'Insert group';

  @override
  String get sourceEditorChooseFile => 'Choose file';

  @override
  String get sourceEditorEngine => 'Source engine: Flutter / V8';

  @override
  String get sourceEditorEngineBody =>
      'The engine supports legacy sources. Preview rule changes before applying migration.';

  @override
  String get sourceEditorMigration => 'Preview migration';

  @override
  String get sourceEditorMigrationRunning => 'Analyzing source…';

  @override
  String get sourceEditorMigrationTitle => 'Source migration preview';

  @override
  String get sourceEditorMigrationApply => 'Apply migration candidate';

  @override
  String get sourceEditorMigrationManual => 'Some rules need manual changes.';

  @override
  String get sourceEditorMigrationNone => 'No rule changes were identified.';

  @override
  String get sourceEditorLargeField =>
      'This field contains long or complex text. Open the rule editor to edit it.';

  @override
  String get sourceEditorFailed => 'Unable to load source editor';

  @override
  String get sourceEditorOptions => 'Source options';

  @override
  String get sourceEditorType => 'Source type';

  @override
  String get sourceEditorEditRejected =>
      'The edit was rejected and the field was restored. Retry or edit that field again before saving.';

  @override
  String get sourceManagerTitle => 'Source manager';

  @override
  String get sourceManagerQuery => 'Search sources';

  @override
  String get sourceManagerMenu => 'Menu';

  @override
  String get sourceManagerSelectionMenu => 'Selection actions';

  @override
  String get sourceManagerBack => 'Back';

  @override
  String get sourceManagerSelectAll => 'Select all';

  @override
  String get sourceManagerInvert => 'Invert selection';

  @override
  String get sourceManagerDelete => 'Delete';

  @override
  String get sourceManagerEdit => 'Edit';

  @override
  String get sourceManagerEnabled => 'Enable source';

  @override
  String get sourceManagerSelect => 'Select source';

  @override
  String get sourceManagerCancel => 'Cancel';

  @override
  String get sourceManagerConfirm => 'Confirm';

  @override
  String get sourceManagerRetry => 'Retry';

  @override
  String get sourceManagerDraft => 'Value';

  @override
  String get sourceManagerJsBadge => 'JS source';

  @override
  String get sourceManagerExploreOnBadge => 'Browse enabled';

  @override
  String get sourceManagerExploreOffBadge => 'Browse disabled';

  @override
  String get sourceManagerDrag => 'Hold to reorder';

  @override
  String get sourceManagerSlide => 'Hold and slide to select';

  @override
  String get sourceManagerEmpty => 'No matching sources';

  @override
  String get sourceManagerGroups => 'Groups';

  @override
  String get sourceManagerAdd => 'Add source';

  @override
  String get sourceManagerAddJs => 'Add JavaScript source';

  @override
  String get sourceManagerQr => 'Import QR code';

  @override
  String get sourceManagerLocal => 'Import local file';

  @override
  String get sourceManagerOnline => 'Import online';

  @override
  String get sourceManagerGroupManage => 'Manage groups';

  @override
  String get sourceManagerFilterEnabled => 'Enabled';

  @override
  String get sourceManagerFilterDisabled => 'Disabled';

  @override
  String get sourceManagerFilterLogin => 'Requires login';

  @override
  String get sourceManagerFilterNoGroup => 'Ungrouped';

  @override
  String get sourceManagerFilterExplore => 'Browse enabled sources';

  @override
  String get sourceManagerFilterNoExplore => 'Browse disabled sources';

  @override
  String get sourceManagerDescending => 'Descending';

  @override
  String get sourceManagerDomain => 'Group by domain';

  @override
  String get sourceManagerShowStatus => 'Show check status';

  @override
  String get sourceManagerBlockNavigation => 'Block source navigation';

  @override
  String get sourceManagerRefresh => 'Refresh';

  @override
  String get sourceManagerHelp => 'Help';

  @override
  String get sourceManagerSortDefault => 'Manual order';

  @override
  String get sourceManagerSortWeight => 'Weight order';

  @override
  String get sourceManagerSortName => 'Sort by name';

  @override
  String get sourceManagerSortUrl => 'Sort by URL';

  @override
  String get sourceManagerSortUpdate => 'Sort by update time';

  @override
  String get sourceManagerSortRespond => 'Sort by response time';

  @override
  String get sourceManagerSortEnable => 'Sort by enabled state';

  @override
  String get sourceManagerEnableSelection => 'Enable selected';

  @override
  String get sourceManagerDisableSelection => 'Disable selected';

  @override
  String get sourceManagerAddGroup => 'Add group';

  @override
  String get sourceManagerRemoveGroup => 'Remove group';

  @override
  String get sourceManagerEnableExplore => 'Enable browse';

  @override
  String get sourceManagerDisableExplore => 'Disable browse';

  @override
  String get sourceManagerTopSelection => 'Move selected to top';

  @override
  String get sourceManagerBottomSelection => 'Move selected to bottom';

  @override
  String get sourceManagerExport => 'Export selected';

  @override
  String get sourceManagerShare => 'Share selected';

  @override
  String get sourceManagerCheck => 'Check selected';

  @override
  String get sourceManagerInterval => 'Select interval';

  @override
  String get sourceManagerTop => 'Move to top';

  @override
  String get sourceManagerBottom => 'Move to bottom';

  @override
  String get sourceManagerUp => 'Move up';

  @override
  String get sourceManagerDown => 'Move down';

  @override
  String get sourceManagerLogin => 'Login';

  @override
  String get sourceManagerSearch => 'Search';

  @override
  String get sourceManagerDebug => 'Debug';

  @override
  String get sourceManagerExplore => 'Toggle browse';

  @override
  String get sourceManagerStatusAll => 'All statuses';

  @override
  String get sourceManagerStatusNeeded => 'Needs check';

  @override
  String get sourceManagerStatusPassed => 'Passed';

  @override
  String get sourceManagerStatusFailed => 'Failed';

  @override
  String get sourceManagerDeletePrompt => 'Delete the selected sources?';

  @override
  String get sourceManagerCheckKeyword => 'Check keyword';

  @override
  String get sourceManagerExportSuccess => 'Export complete';

  @override
  String get sourceManagerPassphrase => 'Share passphrase';

  @override
  String get sourceManagerCheckConfig => 'Check settings';

  @override
  String get sourceManagerForgetImport => 'Forget import history';

  @override
  String get sourceManagerLoadFailed =>
      'Cannot read the native source manager. Retry to reconnect.';

  @override
  String sourceManagerCount(int selected, int total) {
    return 'Selected $selected/$total';
  }

  @override
  String sourceManagerBookCount(int count) {
    return '$count books in library';
  }

  @override
  String get sourcesManage => 'Source management';

  @override
  String get sourcesImport => 'Import';

  @override
  String get sourcesImportJson => 'Import source JSON';

  @override
  String get sourcesEdit => 'Edit source';

  @override
  String get sourcesJsonHint => 'A source object or array of sources';

  @override
  String get sourcesCancel => 'Cancel';

  @override
  String get sourcesSave => 'Save';

  @override
  String get sourcesCopy => 'Copy all sources as JSON';

  @override
  String get sourcesCopied => 'Sources copied as JSON';

  @override
  String get sourcesEmpty => 'No sources. Import source JSON to get started.';

  @override
  String get sourcesDelete => 'Delete source';

  @override
  String get sourcesDeleteConfirm => 'Delete this source?';

  @override
  String get sourcesDeleteAction => 'Delete';

  @override
  String get sourcesSearchTitle => 'Search books';

  @override
  String get sourcesSourceLabel => 'Source';

  @override
  String get sourcesKeyword => 'Book title or author';

  @override
  String get sourcesSearch => 'Search';

  @override
  String get sourcesNeedSource =>
      'Import and enable a source in source management first.';

  @override
  String get sourcesSearchPrompt => 'Enter a keyword to search';

  @override
  String get sourcesNoResults => 'No results';

  @override
  String get sourcesRefresh => 'Refresh chapters';

  @override
  String get sourcesAdd => 'Add to bookshelf';

  @override
  String get sourcesAdded => 'Added to bookshelf';

  @override
  String get sourcesEmptyToc =>
      'The source returned no chapters. Check its rules and website status.';

  @override
  String get sourcesIdentityImmutable =>
      'Source identity cannot be changed while editing. Import it as a new source instead.';

  @override
  String sourcesChapterCount(int count) {
    return 'Contents · $count chapters';
  }

  @override
  String sourcesDetailError(String error) {
    return 'Could not load details or chapters\n$error';
  }

  @override
  String get sourcesBrowse => 'Browse categories';

  @override
  String get sourcesCategory => 'Category';

  @override
  String get sourcesNoCategories =>
      'This source has no static browse categories.';

  @override
  String get sourcesPrevious => 'Previous page';

  @override
  String get sourcesNext => 'Next page';

  @override
  String sourcesPage(int page) {
    return 'Page $page';
  }

  @override
  String get txtTocManagerTitle => 'TXT TOC rules';

  @override
  String get txtTocManagerBridgeError =>
      'Change not acknowledged. Edit again or retry.';

  @override
  String get txtTocEditorTitle => 'TXT TOC rule';

  @override
  String get txtTocEditorName => 'Name';

  @override
  String get txtTocEditorRegex => 'Regex';

  @override
  String get txtTocEditorReplacement => 'Replacement (JavaScript supported)';

  @override
  String get txtTocEditorFullscreen => 'Fullscreen editor';

  @override
  String get txtTocEditorSave => 'Save';

  @override
  String get txtTocEditorMenu => 'Menu';

  @override
  String get txtTocEditorCopy => 'Copy rule';

  @override
  String get txtTocEditorPaste => 'Paste rule';

  @override
  String get txtTocEditorBridgeError =>
      'Field not acknowledged. Edit it again to retry.';

  @override
  String get txtTocEditorExit => 'Exit';

  @override
  String get txtTocEditorExitMessage => 'Keep editing the unsaved rule?';

  @override
  String get txtTocEditorKeep => 'Keep editing';

  @override
  String get txtTocEditorDiscard => 'Discard changes';

  @override
  String get txtTocEditorExample => 'Example';
}
