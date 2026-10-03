# UI 层

Compose 页面按功能组织，采用 `Route / Screen / UiState / ViewModel` 分工；共享 UI 放在
`components`，主题放在 `theme`，导航定义放在 `navigation`。静态页面无需强制创建 ViewModel。

完整约定及尚未迁移的边界见 [Compose 迁移说明](../../../../../../../../docs/compose-migration.md)。

现有功能目录：

* about 关于界面
* association 导入书源界面
* book\audio 音频播放界面
* book\arrange 书架整理界面
* book\info 书籍信息查看
* book\read 书籍阅读界面
* book\search 搜索书籍界面
* book\source 书源界面
* book\changeCover 封面换源界面
* book\changeSource 换源界面
* book\toc 目录界面
* book\download 下载界面
* book\explore 发现界面
* book\local 书籍导入界面
* document 文件选择界面
* config 配置界面
* main 主界面
* qrCode 二维码扫描界面
* replaceRule 替换净化界面
* rss\article 订阅条目界面
* rss\read 订阅阅读界面
* rss\source 订阅源界面
* welcome 欢迎界面
* widget 自定义插件