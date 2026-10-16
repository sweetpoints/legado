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
}
