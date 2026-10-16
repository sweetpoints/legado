// ignore: unused_import
import 'package:intl/intl.dart' as intl;

import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Chinese (`zh`).
class AppLocalizationsZh extends AppLocalizations {
  AppLocalizationsZh([String locale = 'zh']) : super(locale);

  @override
  String get appTitle => '阅读';

  @override
  String get bookshelfTitle => '书架';

  @override
  String get exploreTitle => '发现';

  @override
  String get settingsTitle => '设置';

  @override
  String get searchTitle => '搜索图书';

  @override
  String get sourcesTitle => '书源管理';

  @override
  String get backupTitle => '备份与恢复';

  @override
  String get importText => '导入 TXT 图书';

  @override
  String get emptyShelf => '书架还没有图书';

  @override
  String get emptyShelfHint => '导入本地图书，或从书源搜索图书。';

  @override
  String get retry => '重试';

  @override
  String get cancel => '取消';

  @override
  String get confirm => '确认';

  @override
  String get close => '关闭';

  @override
  String get delete => '删除';

  @override
  String get save => '保存';

  @override
  String get loading => '正在加载…';

  @override
  String get operationFailed => '操作失败，请重试。';

  @override
  String get startupFailed => '无法打开书库，原始数据未删除。';

  @override
  String get bookRemoved => '图书已移除';

  @override
  String get undo => '撤销';

  @override
  String get deleteBookTitle => '移除这本书？';

  @override
  String get deleteBookMessage => '将从当前书库移除这本书及其缓存章节。';

  @override
  String get importSucceeded => '图书已导入';

  @override
  String get languageTitle => '语言';

  @override
  String get systemLanguage => '跟随系统';

  @override
  String get chineseLanguage => '简体中文';

  @override
  String get englishLanguage => 'English';

  @override
  String get appearanceTitle => '外观';

  @override
  String get systemTheme => '跟随系统';

  @override
  String get lightTheme => '浅色';

  @override
  String get darkTheme => '深色';

  @override
  String get restoreZip => '恢复 ZIP 备份';

  @override
  String get exportZip => '导出 ZIP 备份';

  @override
  String get restoreTitle => '恢复此备份？';

  @override
  String get restoreMessage => '备份中存在的数据集合将替换书库中对应的数据，备份未包含的集合会保留。';

  @override
  String get restoreSucceeded => '备份已恢复';

  @override
  String get exportSucceeded => '备份已导出';

  @override
  String get backupExplanation => '恢复旧版阅读 ZIP 备份，或导出当前书库。';

  @override
  String get noChapters => '暂无可用目录';

  @override
  String get backToShelf => '返回书架';

  @override
  String get invalidRoute => '无法打开此链接。';

  @override
  String get bookMissing => '这本书已不在书库中。';

  @override
  String get unsupportedText => '此文件不是有效 UTF-8 文本，请转换编码后导入。';

  @override
  String get textTooLarge => '此文件超过 20 MiB 导入上限。';

  @override
  String get backupTooLarge => '此备份超过 128 MiB 输入上限。';

  @override
  String get details => '详情';

  @override
  String bookCount(int count) {
    return '共 $count 本书';
  }

  @override
  String chapterProgress(int chapter) {
    return '第 $chapter 章';
  }

  @override
  String get navigationRssTitle => '订阅';

  @override
  String get readerTitle => '阅读';

  @override
  String get readerSettings => '阅读设置';

  @override
  String get readerFontSize => '字号';

  @override
  String get readerSystemTheme => '跟随系统';

  @override
  String get readerLightTheme => '浅色';

  @override
  String get readerDarkTheme => '深色';

  @override
  String get readerDone => '完成';

  @override
  String get readerRetry => '重试';

  @override
  String get readerPreviousChapter => '上一章';

  @override
  String get readerNextChapter => '下一章';

  @override
  String get readerCatalogue => '目录';

  @override
  String readerCatalogueCount(int count) {
    return '目录（$count章）';
  }

  @override
  String readerChapterPosition(int current, int total, String title) {
    return '$current/$total · $title';
  }

  @override
  String get readerBookMissing => '书籍不存在，请返回书架重新打开';

  @override
  String get readerTocMissing => '尚无目录，请先在详情页获取目录';

  @override
  String get readerTocEmpty => '目录中没有可阅读的章节';

  @override
  String get readerContentMissing => '本章正文尚未缓存，请返回详情页下载或重试';

  @override
  String readerProgressSaveFailed(String error) {
    return '阅读进度保存失败：$error';
  }

  @override
  String readerSettingsSaveFailed(String error) {
    return '阅读设置保存失败：$error';
  }

  @override
  String get rssTitle => '订阅';

  @override
  String get rssAddTooltip => '添加订阅 / 导入旧订阅';

  @override
  String get rssEmpty => '添加 RSS/Atom 地址，或导入旧版订阅 JSON';

  @override
  String get rssLegacySubtitle => 'CSS/DOM 网页订阅';

  @override
  String get rssAdd => '添加订阅';

  @override
  String get rssImportHelp =>
      '粘贴 RSS/Atom 地址或旧版 rssSources.json。支持 CSS/DOM 网页规则；JavaScript 规则会保留并明确提示所需能力。';

  @override
  String get rssInput => '地址或订阅 JSON';

  @override
  String get rssCancel => '取消';

  @override
  String get rssSaving => '保存中…';

  @override
  String get rssSave => '保存';

  @override
  String get rssCancelRefresh => '取消刷新';

  @override
  String get rssRefresh => '刷新订阅';

  @override
  String get rssLegacyHelp =>
      '支持 HTML 列表、CSS/DOM 规则与安全模板；JavaScript 和浏览器验证需要单独宿主。';

  @override
  String get rssFetch => '获取订阅文章';

  @override
  String get rssArticle => '文章';

  @override
  String get rssMissingArticle => '文章不存在，请返回订阅并刷新';

  @override
  String get rssUnstar => '取消收藏';

  @override
  String get rssStar => '收藏文章';

  @override
  String get rssCopyLink => '复制原文链接';

  @override
  String get rssCopied => '已复制原文链接';

  @override
  String get rssRetry => '重试';

  @override
  String get rssInvalidUrl => '订阅地址必须是 HTTP 或 HTTPS 链接';

  @override
  String get rssCorruptStorage => '订阅数据损坏，请从备份恢复；原数据已保留';

  @override
  String get rssInvalidSource => '订阅源必须是 JSON 对象';

  @override
  String get rssEmptyResponse => '订阅响应为空';

  @override
  String get rssTooLarge => '订阅文件超过 5 MiB 上限';

  @override
  String get rssInvalidFeed => '无法解析 RSS/Atom，请检查地址和数据格式';

  @override
  String get rssNetworkError => '订阅请求失败，请检查网络和地址后重试';

  @override
  String get rssOperationError => '订阅操作失败，请重试；已有数据仍保留';

  @override
  String get rssScriptCapability =>
      '此规则需要 JavaScript 或浏览器宿主，当前未接入；原规则仍保留，可使用原 Android App。';

  @override
  String get rssUnsupportedRule =>
      '当前规则格式尚未支持；请使用 CSS/Legado DOM 规则或原 Android App。';

  @override
  String get rssPaginationCycle => '分页链接形成循环，已停止请求；请修正下一页规则。';

  @override
  String get rssPageLimit => '分页超过 20 页，已停止请求；请缩小分类范围。';

  @override
  String get rssInvalidArticleLink => '文章链接无效，请检查链接提取规则。';

  @override
  String get rssImageError => '图片加载失败，可复制原文链接查看。';

  @override
  String get sourcesManage => '书源管理';

  @override
  String get sourcesImport => '导入';

  @override
  String get sourcesImportJson => '导入书源 JSON';

  @override
  String get sourcesEdit => '编辑书源';

  @override
  String get sourcesJsonHint => '单个书源对象或书源数组';

  @override
  String get sourcesCancel => '取消';

  @override
  String get sourcesSave => '保存';

  @override
  String get sourcesCopy => '复制全部书源 JSON';

  @override
  String get sourcesCopied => '书源 JSON 已复制';

  @override
  String get sourcesEmpty => '暂无书源，点击导入添加 JSON 书源';

  @override
  String get sourcesDelete => '删除书源';

  @override
  String get sourcesDeleteConfirm => '删除书源？';

  @override
  String get sourcesDeleteAction => '删除';

  @override
  String get sourcesSearchTitle => '搜索图书';

  @override
  String get sourcesSourceLabel => '书源';

  @override
  String get sourcesKeyword => '书名或作者';

  @override
  String get sourcesSearch => '搜索';

  @override
  String get sourcesNeedSource => '请先在书源管理中导入并启用书源';

  @override
  String get sourcesSearchPrompt => '输入关键词开始搜索';

  @override
  String get sourcesNoResults => '没有搜索结果';

  @override
  String get sourcesRefresh => '刷新目录';

  @override
  String get sourcesAdd => '加入书架';

  @override
  String get sourcesAdded => '已加入书架';

  @override
  String get sourcesEmptyToc => '书源没有返回目录，请检查书源规则与站点状态。';

  @override
  String get sourcesIdentityImmutable => '编辑时不能更改书源标识，请使用导入新增书源';

  @override
  String sourcesChapterCount(int count) {
    return '目录 · $count 章';
  }

  @override
  String sourcesDetailError(String error) {
    return '获取详情或目录失败\n$error';
  }

  @override
  String get sourcesBrowse => '发现分类';

  @override
  String get sourcesCategory => '分类';

  @override
  String get sourcesNoCategories => '书源没有静态发现分类';

  @override
  String get sourcesPrevious => '上一页';

  @override
  String get sourcesNext => '下一页';

  @override
  String sourcesPage(int page) {
    return '第 $page 页';
  }
}
