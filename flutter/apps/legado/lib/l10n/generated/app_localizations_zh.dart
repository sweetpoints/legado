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
  String get unsupportedText =>
      '无法可靠识别此文本的编码，或文件已损坏、使用了混合编码。支持 UTF-8、带 BOM 的 UTF-16 和 GBK；请确认编码后转换为 UTF-8 再导入。';

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
  String get epubTooLarge => '此 EPUB 超过 128 MiB 输入上限。';

  @override
  String get licensesTitle => '开源许可证';

  @override
  String get shelfOptions => '书架选项';

  @override
  String get shelfManageGroups => '管理分组';

  @override
  String get shelfCreateGroup => '新建分组';

  @override
  String get shelfRenameGroup => '重命名分组';

  @override
  String get shelfDeleteGroup => '删除分组';

  @override
  String get shelfDeleteGroupMessage => '书籍会保留在书架中，仅移除它们与此分组的关联。';

  @override
  String get shelfGroupName => '分组名称';

  @override
  String get shelfGroupNameRequired => '请输入分组名称。';

  @override
  String get shelfNoGroups => '请先新建分组，再为书籍设置分组。';

  @override
  String get shelfAssignGroups => '设置分组';

  @override
  String get shelfGroup => '分组';

  @override
  String get shelfSort => '排序';

  @override
  String get shelfAll => '全部图书';

  @override
  String get shelfUngrouped => '未分组';

  @override
  String get shelfLocal => '本地图书';

  @override
  String get shelfAudio => '有声图书';

  @override
  String get shelfVideo => '视频图书';

  @override
  String get shelfNetworkUngrouped => '未分组的网络图书';

  @override
  String get shelfLocalUngrouped => '未分组的本地图书';

  @override
  String get shelfUpdateFailed => '更新失败';

  @override
  String get shelfSortRead => '最近阅读';

  @override
  String get shelfSortUpdated => '最新更新';

  @override
  String get shelfSortTitle => '书名';

  @override
  String get shelfSortManual => '手动排序';

  @override
  String get shelfSortActivity => '最近活动';

  @override
  String get shelfSortAuthor => '作者';

  @override
  String get shelfBookActions => '图书操作';

  @override
  String get shelfMoveUp => '上移';

  @override
  String get shelfMoveDown => '下移';

  @override
  String get shelfEmptyGroup => '此分组暂无图书。';

  @override
  String get shelfImportEncodedText => '按编码导入 TXT';

  @override
  String get shelfChooseTextEncoding => '选择 TXT 编码';

  @override
  String get shelfTextEncodingHint =>
      '请选择文件的已知编码。文件中的字节顺序标记（BOM）优先，所选编码与 BOM 冲突时将停止导入。';

  @override
  String get shelfEncodingUtf8 => 'UTF-8';

  @override
  String get shelfEncodingGbk => 'GBK / GB2312';

  @override
  String get shelfEncodingUtf16Le => 'UTF-16 小端序';

  @override
  String get shelfEncodingUtf16Be => 'UTF-16 大端序';

  @override
  String get shelfEncodingConflict =>
      '所选编码与文件的 BOM 不一致，请选择 BOM 对应的编码，或使用普通 TXT 导入自动识别。';

  @override
  String get shelfEncodingUnavailable =>
      '当前文件适配器不支持按指定编码导入，已停止操作，请更换支持此功能的文件适配器。';

  @override
  String get codeEditorTitle => '规则编辑';

  @override
  String get codeEditorClose => '关闭';

  @override
  String get codeEditorSave => '保存';

  @override
  String get codeEditorSearch => '搜索';

  @override
  String get codeEditorDebug => '调试书源';

  @override
  String get codeEditorLogin => '登录';

  @override
  String get codeEditorMenu => '更多操作';

  @override
  String get codeEditorTheme => '编辑器主题';

  @override
  String get codeEditorSelectAll => '全选';

  @override
  String get codeEditorFormat => '格式化代码';

  @override
  String get codeEditorSyntax => '检查 JavaScript 语法';

  @override
  String get codeEditorCurl => '转换 cURL';

  @override
  String get codeEditorSettings => '编辑器设置';

  @override
  String get codeEditorWrap => '自动换行';

  @override
  String get codeEditorLog => '日志';

  @override
  String get codeEditorQuery => '搜索';

  @override
  String get codeEditorReplace => '替换';

  @override
  String get codeEditorRegex => '正则表达式';

  @override
  String get codeEditorIgnoreCase => '忽略大小写';

  @override
  String get codeEditorPrevious => '上一匹配';

  @override
  String get codeEditorNext => '下一匹配';

  @override
  String get codeEditorReplaceAll => '全部替换';

  @override
  String get codeEditorHelp => '帮助';

  @override
  String get codeEditorKeyboardConfig => '辅助按键配置';

  @override
  String get codeEditorRuleHelp => '书源 Reference';

  @override
  String get codeEditorRssHelp => '订阅源 Reference';

  @override
  String get codeEditorJsHelp => 'JavaScript Reference';

  @override
  String get codeEditorRegexHelp => '正则 Reference';

  @override
  String get codeEditorUndo => '撤销';

  @override
  String get codeEditorRedo => '重做';

  @override
  String get codeEditorRetry => '重试';

  @override
  String get codeEditorRestart => '重启编辑器';

  @override
  String get codeEditorLoadingFailed => '无法加载编辑器';

  @override
  String get codeEditorLoadFailed => '无法加载此文档，请重启编辑器后重试。';

  @override
  String get codeEditorReplacementFailed => '替换失败';

  @override
  String get codeEditorDiscardTitle => '放弃未保存的修改？';

  @override
  String get codeEditorDiscardBody => '修改尚未保存。退出将丢弃本次编辑内容。';

  @override
  String get codeEditorDiscard => '放弃修改';

  @override
  String get codeEditorKeepEditing => '继续编辑';

  @override
  String get codeEditorPlatformUnavailable => '此嵌入式编辑区域仅支持 Android。';

  @override
  String get codeEditorSearchRejected => '搜索修改未被接受，已恢复。请重试或重新编辑搜索后再保存或替换。';

  @override
  String get pdfTitle => 'PDF 阅读';

  @override
  String get pdfImportTitle => '导入 PDF';

  @override
  String get pdfImportDescription => '导入不超过 32 MiB 的本地 PDF，文档将保存在书库中供离线阅读。';

  @override
  String get pdfChooseFile => '选择 PDF';

  @override
  String get pdfTooLarge => 'PDF 超过 32 MiB 大小限制。';

  @override
  String get pdfEncrypted => '暂不支持加密 PDF，请导入未加密副本。';

  @override
  String get pdfUnsupportedStorage => '当前书库不支持本地 PDF 存储。';

  @override
  String get pdfOpenFailed => 'PDF 缺失、损坏或格式不受支持。';

  @override
  String get pdfSaveFailed => '阅读进度保存失败。';

  @override
  String get pdfPreviousPage => '上一页';

  @override
  String get pdfNextPage => '下一页';

  @override
  String pdfPagePosition(int page, int total) {
    return '第 $page 页 / 共 $total 页';
  }

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
  String get readerEpubInvalidArchive => 'EPUB 压缩包已损坏或超出导入限额。';

  @override
  String get readerEpubInvalidPackage => 'EPUB 包信息、目录或章节文件无效。';

  @override
  String get readerEpubEncrypted => '暂不支持加密或混淆资源的 EPUB。';

  @override
  String get readerEpubUnsupported => '此 EPUB 含有文本阅读器暂不支持的章节格式。';

  @override
  String get readerEpubEmpty => '此 EPUB 没有可阅读的文本。';

  @override
  String get readerEpubImport => '导入 EPUB';

  @override
  String get readerBookmarks => '书签';

  @override
  String get readerAddBookmark => '添加当前位置';

  @override
  String get readerBookmarkNote => '备注（可选）';

  @override
  String get readerBookmarkCancel => '取消';

  @override
  String get readerBookmarkSave => '保存';

  @override
  String get readerBookmarkAdded => '书签已保存';

  @override
  String readerBookmarkFailed(String error) {
    return '书签操作失败：$error';
  }

  @override
  String get readerBookmarksEmpty => '暂无书签';

  @override
  String get readerBookmarkUnavailable => '此书签无法与当前图书和章节匹配。';

  @override
  String get readerDeleteBookmark => '删除书签';

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
  String get rssDelete => '删除订阅';

  @override
  String get rssDeleteHelp => '删除后不会因旧备份记录再次出现。原备份、离线文章和收藏仍保留；显式导入同地址可重新添加。';

  @override
  String rssRestoreSummary(int restored, int conflicts) {
    return '来自旧备份：$restored 个订阅可用；$conflicts 个同地址冲突保留当前配置。原备份保持不变。';
  }

  @override
  String get rssInvalidRestore => '旧订阅备份格式无效，原数据已保留；请检查 rssSources.json。';

  @override
  String get rssDuplicateRestoreSource =>
      '旧订阅备份有重复 sourceUrl，无法确定使用哪条；请修正备份后重新恢复。原数据已保留。';

  @override
  String get rssFavoritesTitle => 'RSS收藏';

  @override
  String get rssFavoritesGroups => '分组';

  @override
  String get rssFavoritesMenu => '菜单';

  @override
  String get rssFavoritesDeleteGroup => '删除当前分组';

  @override
  String get rssFavoritesDeleteAll => '删除全部收藏';

  @override
  String get rssFavoritesError => '收藏操作失败，请重试';

  @override
  String get rssFavoritesConfirmDelete => '确认删除？';

  @override
  String get rssFavoriteConfigHeading => '收藏';

  @override
  String get rssFavoriteConfigTitle => '标题';

  @override
  String get rssFavoriteConfigGroup => '分组名';

  @override
  String get rssFavoriteConfigConfirm => '确定';

  @override
  String get rssEditorTitle => '订阅源编辑';

  @override
  String get rssEditorEditRejected => '编辑未被接受，请修正字段后再保存。';

  @override
  String get rssEditorFailed => '无法更新订阅源编辑器。';

  @override
  String get rssEditorMissing => '订阅源已不存在。';

  @override
  String get rssEditorRequired => '源名称和 URL 不能为空。';

  @override
  String get rssEditorFormat => '源格式错误。';

  @override
  String get rssEditorFocus => '请先将光标移至文本框。';

  @override
  String get rssEditorNoLogin => '该源未配置登录。';

  @override
  String get rssEditorEnableJs => '启用 JavaScript';

  @override
  String get rssEditorBaseUrl => '使用基础 URL 加载';

  @override
  String get rssEditorWebLog => '显示 WebView 日志';

  @override
  String get rssEditorCacheFirst => '优先使用缓存';

  @override
  String get rssEditorSingleUrl => '单网址';

  @override
  String get rssEditorPreload => '启用预加载';

  @override
  String get rssEditorArticleStyle => '文章布局';

  @override
  String get rssEditorExternal => '请打开全屏编辑器以安全编辑此字段。';

  @override
  String get rssEditorUrlOverride =>
      'URL 跳转拦截（JavaScript；返回 true 拦截，可通过 url 变量打开链接、搜索或添加书架）';

  @override
  String get rssEditorStart => '启动';

  @override
  String get rssEditorList => '列表规则';

  @override
  String get rssEditorWebView => 'WebView';

  @override
  String get rssEditorWebPage => '网页';

  @override
  String get rssEditorLayoutList => '列表';

  @override
  String get rssEditorLayoutSingle => '单列';

  @override
  String get rssEditorLayoutDouble => '双列';

  @override
  String get rssEditorLayoutWaterfall => '瀑布';

  @override
  String get rssEditorLayoutTriple => '三列';

  @override
  String get rssEditorType => '源类型';

  @override
  String get debuggerTitle => '调试源';

  @override
  String get debuggerSearch => '搜索';

  @override
  String get debuggerClose => '返回';

  @override
  String get debuggerMenu => '更多选项';

  @override
  String get debuggerScan => '扫描二维码';

  @override
  String get debuggerRefresh => '刷新发现';

  @override
  String get debuggerInstructions => '帮助';

  @override
  String get debuggerRetry => '重试';

  @override
  String get debuggerSearchHtml => '搜索源码';

  @override
  String get debuggerBookHtml => '详情源码';

  @override
  String get debuggerTocHtml => '目录源码';

  @override
  String get debuggerContentHtml => '正文源码';

  @override
  String get debuggerListHtml => '列表源码';

  @override
  String get debuggerCategories => '选择分类';

  @override
  String get debuggerSearchHelp => '调试搜索：输入关键字，例如：';

  @override
  String get debuggerBookCategoryHelp => '调试发现：输入发现 URL，例如：';

  @override
  String get debuggerRssCategoryHelp => '调试分类：输入分类 URL，例如：';

  @override
  String get debuggerDetailsHelp => '调试详情：输入详情页 URL：';

  @override
  String get debuggerTocHelp => '调试目录：输入以 ++ 开头的目录页 URL：';

  @override
  String get debuggerContentHelp => '调试正文：输入以 -- 开头的正文页 URL：';

  @override
  String get debuggerRssContentHelp => '调试内容：输入内容页 URL：';

  @override
  String get debuggerBusy => '调试通道占用中，请稍后重试';

  @override
  String get debuggerInterrupted => '上次调试已中断，请重新运行';

  @override
  String get debuggerConnectionError => '无法连接书源调试器';

  @override
  String get debuggerCommandError => '操作未完成，请重试';

  @override
  String get debuggerNoCategories => '暂无可用分类';

  @override
  String get debuggerLoading => '正在加载源…';

  @override
  String get debuggerLinkError => '无法打开此链接';

  @override
  String get sourceEditorTitle => '编辑书源';

  @override
  String get sourceEditorClose => '关闭';

  @override
  String get sourceEditorSave => '保存';

  @override
  String get sourceEditorFullScreen => '规则编辑';

  @override
  String get sourceEditorMenu => '更多操作';

  @override
  String get sourceEditorBase => '基本';

  @override
  String get sourceEditorSearch => '搜索';

  @override
  String get sourceEditorExplore => '发现';

  @override
  String get sourceEditorInfo => '详情';

  @override
  String get sourceEditorToc => '目录';

  @override
  String get sourceEditorContent => '正文';

  @override
  String get sourceEditorReview => '段评';

  @override
  String get sourceEditorEnabled => '启用';

  @override
  String get sourceEditorExploreEnabled => '发现';

  @override
  String get sourceEditorCookie => '自动保存 Cookie';

  @override
  String get sourceEditorReviewEnabled => '段评';

  @override
  String get sourceEditorListener => '事件监听';

  @override
  String get sourceEditorCustomButton => '自定义按钮';

  @override
  String get sourceEditorText => '文字';

  @override
  String get sourceEditorAudio => '音频';

  @override
  String get sourceEditorImage => '图片';

  @override
  String get sourceEditorFile => '文件';

  @override
  String get sourceEditorVideo => '视频';

  @override
  String get sourceEditorDebug => '调试书源';

  @override
  String get sourceEditorLogin => '登录';

  @override
  String get sourceEditorClearCookie => '清除 Cookie';

  @override
  String get sourceEditorAutoComplete => '自动补全';

  @override
  String get sourceEditorCopy => '复制书源';

  @override
  String get sourceEditorPaste => '粘贴书源';

  @override
  String get sourceEditorQr => '扫码导入';

  @override
  String get sourceEditorShare => '分享书源';

  @override
  String get sourceEditorQrShare => '二维码分享';

  @override
  String get sourceEditorVariable => '设置源变量';

  @override
  String get sourceEditorLog => '日志';

  @override
  String get sourceEditorHelp => '书源 Reference';

  @override
  String get sourceEditorRetry => '重试';

  @override
  String get sourceEditorDiscardTitle => '放弃未保存的修改？';

  @override
  String get sourceEditorDiscardBody => '修改尚未保存。退出将丢弃本次编辑内容。';

  @override
  String get sourceEditorDiscard => '放弃修改';

  @override
  String get sourceEditorKeepEditing => '继续编辑';

  @override
  String get sourceEditorGroups => '书源分组';

  @override
  String get sourceEditorCancel => '取消';

  @override
  String get sourceEditorUndo => '撤销';

  @override
  String get sourceEditorRedo => '重做';

  @override
  String get sourceEditorKeyboardHelp => '辅助输入';

  @override
  String get sourceEditorKeyboardConfig => '辅助按键配置';

  @override
  String get sourceEditorUrlOptions => '插入 URL 参数';

  @override
  String get sourceEditorJsHelp => 'JavaScript Reference';

  @override
  String get sourceEditorRegexHelp => '正则 Reference';

  @override
  String get sourceEditorInsertGroup => '插入分组';

  @override
  String get sourceEditorChooseFile => '选择文件';

  @override
  String get sourceEditorEngine => '书源引擎：Flutter / V8';

  @override
  String get sourceEditorEngineBody => '旧书源由新引擎兼容解析，可预览需要调整的规则。';

  @override
  String get sourceEditorMigration => '预览迁移';

  @override
  String get sourceEditorMigrationRunning => '正在分析书源…';

  @override
  String get sourceEditorMigrationTitle => '书源迁移预览';

  @override
  String get sourceEditorMigrationApply => '应用迁移候选';

  @override
  String get sourceEditorMigrationManual => '部分规则需要手动调整。';

  @override
  String get sourceEditorMigrationNone => '未发现需要调整的规则。';

  @override
  String get sourceEditorLargeField => '内容较长或包含复杂组合字符，请使用规则编辑器。';

  @override
  String get sourceEditorFailed => '无法加载书源编辑器';

  @override
  String get sourceEditorOptions => '书源选项';

  @override
  String get sourceEditorType => '书源类型';

  @override
  String get sourceEditorEditRejected => '修改未被接受，字段已恢复。请重试或重新编辑该字段后再保存。';

  @override
  String get sourceManagerTitle => '书源管理';

  @override
  String get sourceManagerQuery => '搜索书源';

  @override
  String get sourceManagerMenu => '菜单';

  @override
  String get sourceManagerSelectionMenu => '选中操作';

  @override
  String get sourceManagerBack => '返回';

  @override
  String get sourceManagerSelectAll => '全选';

  @override
  String get sourceManagerInvert => '反选';

  @override
  String get sourceManagerDelete => '删除';

  @override
  String get sourceManagerEdit => '编辑';

  @override
  String get sourceManagerEnabled => '启用书源';

  @override
  String get sourceManagerSelect => '选择书源';

  @override
  String get sourceManagerCancel => '取消';

  @override
  String get sourceManagerConfirm => '确认';

  @override
  String get sourceManagerRetry => '重试';

  @override
  String get sourceManagerDraft => '输入内容';

  @override
  String get sourceManagerJsBadge => 'JS源';

  @override
  String get sourceManagerExploreOnBadge => '发现已启用';

  @override
  String get sourceManagerExploreOffBadge => '发现已停用';

  @override
  String get sourceManagerDrag => '长按拖动排序';

  @override
  String get sourceManagerSlide => '长按滑动选择';

  @override
  String get sourceManagerEmpty => '没有符合条件的书源';

  @override
  String get sourceManagerGroups => '分组';

  @override
  String get sourceManagerAdd => '添加书源';

  @override
  String get sourceManagerAddJs => '添加JS书源';

  @override
  String get sourceManagerQr => '二维码导入';

  @override
  String get sourceManagerLocal => '本地导入';

  @override
  String get sourceManagerOnline => '网络导入';

  @override
  String get sourceManagerGroupManage => '管理分组';

  @override
  String get sourceManagerFilterEnabled => '已启用';

  @override
  String get sourceManagerFilterDisabled => '已禁用';

  @override
  String get sourceManagerFilterLogin => '需要登录';

  @override
  String get sourceManagerFilterNoGroup => '无分组';

  @override
  String get sourceManagerFilterExplore => '已启用发现';

  @override
  String get sourceManagerFilterNoExplore => '未启用发现';

  @override
  String get sourceManagerDescending => '降序';

  @override
  String get sourceManagerDomain => '按域名分组';

  @override
  String get sourceManagerShowStatus => '显示校验状态';

  @override
  String get sourceManagerBlockNavigation => '阻止源站跳转';

  @override
  String get sourceManagerRefresh => '刷新';

  @override
  String get sourceManagerHelp => '帮助';

  @override
  String get sourceManagerSortDefault => '手动排序';

  @override
  String get sourceManagerSortWeight => '自动排序（权重）';

  @override
  String get sourceManagerSortName => '按名称排序';

  @override
  String get sourceManagerSortUrl => '按地址排序';

  @override
  String get sourceManagerSortUpdate => '按更新时间排序';

  @override
  String get sourceManagerSortRespond => '按响应时间排序';

  @override
  String get sourceManagerSortEnable => '按启用状态排序';

  @override
  String get sourceManagerEnableSelection => '启用选中';

  @override
  String get sourceManagerDisableSelection => '禁用选中';

  @override
  String get sourceManagerAddGroup => '添加分组';

  @override
  String get sourceManagerRemoveGroup => '移除分组';

  @override
  String get sourceManagerEnableExplore => '启用发现';

  @override
  String get sourceManagerDisableExplore => '禁用发现';

  @override
  String get sourceManagerTopSelection => '选中置顶';

  @override
  String get sourceManagerBottomSelection => '选中置底';

  @override
  String get sourceManagerExport => '导出选中';

  @override
  String get sourceManagerShare => '分享选中';

  @override
  String get sourceManagerCheck => '校验选中';

  @override
  String get sourceManagerInterval => '选择区间';

  @override
  String get sourceManagerTop => '置顶';

  @override
  String get sourceManagerBottom => '置底';

  @override
  String get sourceManagerUp => '上移';

  @override
  String get sourceManagerDown => '下移';

  @override
  String get sourceManagerLogin => '登录';

  @override
  String get sourceManagerSearch => '搜索';

  @override
  String get sourceManagerDebug => '调试';

  @override
  String get sourceManagerExplore => '切换发现';

  @override
  String get sourceManagerStatusAll => '全部';

  @override
  String get sourceManagerStatusNeeded => '待校验';

  @override
  String get sourceManagerStatusPassed => '校验通过';

  @override
  String get sourceManagerStatusFailed => '校验失败';

  @override
  String get sourceManagerDeletePrompt => '确定删除选中的书源？';

  @override
  String get sourceManagerCheckKeyword => '校验关键词';

  @override
  String get sourceManagerExportSuccess => '导出成功';

  @override
  String get sourceManagerPassphrase => '分享口令';

  @override
  String get sourceManagerCheckConfig => '校验设置';

  @override
  String get sourceManagerForgetImport => '删除导入记录';

  @override
  String get sourceManagerLoadFailed => '无法获取原生书源管理状态，请重试';

  @override
  String sourceManagerCount(int selected, int total) {
    return '已选 $selected/$total';
  }

  @override
  String sourceManagerBookCount(int count) {
    return '书架 $count 本';
  }

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
