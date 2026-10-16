# 全应用 Flutter 迁移

范围基线：master `23e6c1c154ecff1c90121dc775f8969a8856f5a2`。目标为 Android、iOS、macOS、Windows、Linux，完整应用功能迁入 Dart/Flutter。当前为迁移开发分支，已有基础阅读流程；尚未替换 Android 发行入口，不是已完成的五平台应用。

## 技术栈

应用状态与依赖使用 Riverpod 3.4.3，持久化使用 Drift ^2.35.2（SQLite），业务模型使用 Freezed，应用请求使用 Dio，路由使用 go_router ^18.1.0，组件使用 material_ui ^1.7.0。旧书源引擎保留已有网络语义；不因应用层采用 Dio 重写其 Cookie/编码/重定向契约。Freezed 4.0.1 与 build_runner 2.16.1 固定以兼容当前 ffigen/analyzer 13；无 dependency override。国际化使用官方 ARB/gen_l10n，各功能 fragment 汇总并检查中英键一致。

## 工程边界

- `apps/legado`：官方 `flutter create --template app --platforms android,ios,macos,windows,linux --org io.legado --project-name legado_app --empty --no-pub` 生成的独立 App。所有业务页面、导航、交互、Material 3 和无障碍在此实现。平台目录保留官方结构。当前 application ID 为独立开发标识；正式 Android 更新必须保持原 release 包名、签名和升级路径，未验证前不得替换发行入口。
- `packages/app_core`：官方 Dart package，平台无关领域模型、备份兼容和后续业务契约。不得依赖 Android Room、Compose、Activity、JNI 或 Flutter widget。
- `packages/source_*`：沿用现有 V8、规则、兼容、迁移及 Reference。扩展跨平台适配，禁止在 App 中编译 V8，禁止回退 Rhino。现有 `modules/source_host` 保留 Android 引擎接入，直到替代路径验收；不把它作为五平台应用的运行前提。
- `packages/app_storage` 通过 Drift 统一持有 SQLite schema、事务、迁移与响应式查询；平台实现仅负责文件授权、安全存储、WebView、通知、媒体、系统朗读及后台任务。平台差异通过明确能力和真实错误表达，不能伪造成功。
- 原生 Java/Kotlin/Swift/C++ 可保留必要平台适配与 V8 FFI 桥接，业务功能不能永久依赖旧 Android App。

## 全量功能验收矩阵

下表每行均须有 Dart 实现、数据契约、行为测试和适用平台实测。没有列出的平台差异也不能自动删功能。`android-inventory.json` 保存基线页面、仓储、实体、服务和格式实现的真实路径；检查器检测后续遗漏，但不把路径数量当成功能等价证明。

| 范围 | 必须保留的行为 | 当前迁移状态 |
| --- | --- | --- |
| 书架、分组、排序、历史 | 稳定书籍身份、排序、刷新、批量管理、最近阅读、删除与撤销 | 书架、自定义分组、位掩码成员、分组筛选、六种持久化排序已实现；完整批量管理、历史及撤销待迁移 |
| 搜索、发现、书籍详情、换源、目录 | 搜索取消、分页、详情初始化、目录翻页、封面选项、预览书籍不入库 | 独立 Dart/V8 搜索、静态发现分页、详情/目录/正文与预览阅读已实现；完整旧宿主兼容待完成 |
| 正文阅读与排版 | UTF-16 阅读锚点、分页与滚动、翻页、字体/背景/间距、进度、手势、键盘、跨窗口重排 | 连续文本阅读、章节导航与 UTF-16 进度已实现；分页动画等待完成 |
| 本地图书及格式 | TXT 编码和目录规则、EPUB、PDF、漫画/图片、音频图书、导入导出、缓存 | TXT UTF-8/GBK/BOM UTF-16 与 EPUB 2/3 章节文本导入已实现；EPUB 原版式/图片、PDF、漫画和音频待迁移 |
| 书签、笔记、划线、替换、字典 | 编辑、撤销、批量导出、全文搜索、简繁转换、规则调试 | 精确章节书签、备注、删除/跳转、旧书签只读兼容与重开已实现；划线、编辑与导出等完整行为待迁移 |
| 书源和订阅管理 | 旧 JSON、变量、登录、编辑/调试、订阅更新、检查、迁移、全部 Reference 契约 | 引擎已有；App 与五平台宿主待迁移 |
| RSS、文章、图片及视频 | 分类、搜索、收藏、已读、翻页、图片请求选项、脚本内容、媒体播放 | RSS/Atom、有限 HTML/CSS、分页、收藏/已读、图文已实现；JS 模板与媒体播放待迁移 |
| 朗读与音频 | 系统 TTS、HTTP TTS、播放列表、语速、休眠、章节切换、媒体通知、后台恢复 | 待迁移 |
| 浏览器、登录及验证码 | Cookie 隔离、重定向、WebView 脚本、浏览器验证后的请求恢复 | Android 接口已有；五平台待迁移 |
| 下载、缓存、导出、自动任务 | 并发限制、取消、断点恢复、后台寿命、网络变化、失败重试 | 待迁移 |
| 备份、恢复、同步和数据升级 | 原文件与未知字段保留、全部备份项、媒体、加密配置、事务、回滚、重启恢复 | ZIP 校验、Drift 文本与媒体原子恢复/导出已实现；旧 RSS/分组/书签语义已接入；其他旧配置应用与同步待迁移 |
| 主题、配置、字体、背景、快捷入口 | Material 3、亮暗色与对比度、动态字号、屏幕阅读器、触屏/鼠标/键盘 | material_ui 亮暗主题、中英国际化/语言设置已实现；完整配置待迁移 |
| 文件关联、分享、二维码、Web/MCP 服务、小组件 | 平台可提供的入口；其他平台提供语义等价流程并记录差异 | 待迁移 |

## 数据兼容约束

1. Android 原数据库、配置和备份是迁移输入，绝不就地试写；先创建一致性快照，再在新存储事务中转换。保留原始文件、版本、校验和、映射与失败报告。
2. 书籍主键 `bookUrl`、非 URL 书源身份、来源、分组位值、变量、阅读位置不能按显示名或列表位置重建。阅读位置保存章节身份和 UTF-16 字符位置，不能只存翻页页码。
3. `bookshelf.json`、书签/笔记/划线、分组、书源、RSS、规则、历史、TTS、自动任务、Cookie、运行时变量、设置与媒体均列入恢复清单。缺失文件、空集合、显式 null、解密失败和格式错误须分别处理。加密服务器配置不得当空集合或默认值恢复。
4. 迁移预览不等于提交；校验或事务失败不能部分覆盖。回滚必须恢复数据与版本号；进程中断后不能重复产生业务副作用。
5. 新旧版本往返和跨平台备份必须保留未知字段。`LegacyBackupDocument` 处理已解包文本并保留未知 JSON 字段；`app_backup` 负责有界 ZIP 校验与不可变暂存；`app_storage` 在一个 Drift 事务中恢复文本和二进制条目。原始未知条目保留不等于其业务配置已生效。ZIP 限制展开大小、文件数、路径和链接，不直接展开到应用目录。

## 五平台阻塞项与验证责任

- V8 Flutter hook 当前支持 Android ARM64/x64、macOS ARM64/x64；macOS x64 已真实链接但未运行。iOS 已有双 slice SDK、jitless 原生 probe（9 项通过）及 Flutter native assets 接入；示例 App 已在 ARM64 模拟器通过 FFI 集成测试，设备 AOT 无签名构建通过，物理设备尚未验收。必须扩展 SDK 身份、工具链、桥接链接、native assets、发布校验和、签名验收。iOS/模拟器、macOS x64、Linux 和 Windows 当前不能因为 App 目录存在就标为可运行。iOS 的 V8 执行模式需单独验证，不把 Android JIT 权限复制到 iOS。
- `source_platform` 目前仅 Android 插件；旧书源 DOM、HTTP、JSoup 互操作等部分能力仍由 Android 宿主提供。要逐项移至 Dart 或五平台等价适配，保持异常、取消、编码、Cookie、重定向与会话语义。
- EPUB 原版式、PDF、媒体、桌面 WebView、后台任务和安全存储须按插件当前官方支持与许可证选择；缺少平台实现时不能以空方法或假结果绕过验收。
- 本机 macOS 可编译 Apple 与 Android；Windows/Linux 的构建与设备验收在对应 runner。五个平台各自保存最终提交、产物哈希、实际测试结果，不能用 widget 测试代替设备验收。

## 完成条件

所有矩阵项和 Android 路径均有明确归属；全部业务 UI 为 Flutter，无旧 Compose/Activity 业务页面回退；旧书源五阶段及已报错样例的完整 App 链路通过；真实备份恢复和迁移中断回滚通过；五平台 Release 构建、运行、签名及更新路径通过。之后才能切换发行入口、删除旧实现并称“全面迁移完成”。

## 当前验证入口

```sh
cd flutter
flutter pub get
(cd packages/app_core && dart analyze && dart run test:test)
(cd apps/legado && flutter analyze && flutter test)
python3 tool/app-migration-inventory.py
```

原 Android 与书源引擎 CI 仍保留。迁移开发包不能替代之前交付的 Release 测试 APK。
