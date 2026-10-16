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
  String get rssTitle => '订阅';

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
}
