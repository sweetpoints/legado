# Legado Flutter App（迁移开发中）

Flutter 3.47.6 官方五平台 app 工程，作为渐进迁移的跨平台开发与验收宿主。Android 仍以现有 App 为发行入口，Flutter 功能须逐项完成嵌入接入、数据兼容和回归后切换；独立开发包不等于已接入。首个接入点为现有 Android App 的“我的 → Flutter 书源管理”，复用本工程的管理页面并直接读写原生 Room；不走本独立 App 的 Drift 启动流程。当前已有 Drift 书架/分组/排序、TXT/EPUB 文本导入与连续阅读、PDF 导入/缩放/翻页/进度、持久化书签与备注、书源管理/搜索/发现/详情、RSS/Atom/有限 HTML 订阅、ZIP 备份原子恢复与导出、语言和主题设置。

TXT 支持严格 UTF-8、带 BOM 的 UTF-16 与 GBK；自动判定不确定时可使用编码选择入口，不支持 GB18030 四字节编码。

统一技术栈：Drift + Riverpod（官方注解与代码生成）、Freezed、Dio、go_router ^18.1.0、material_ui ^1.7.0、官方 ARB/gen_l10n 中英国际化。

在工作区 `flutter pub get`，再执行 `python3 tool/merge-app-localizations.py`；进入本目录运行 `flutter gen-l10n`、`dart run build_runner build`、`flutter run -d macos`。V8 消费固定预编译 SDK，禁止在此工程构建 V8。App 测试目前使用支持 V8 的 macOS；Windows/Linux 的数据包测试与整个 App 原生支持是不同证据。

EPUB 当前只提取章节文本，不呈现原版式、图片和字体。PDF 当前限制 32 MiB，并明确拒绝加密文档；采用固定预编译 PDFium，包含全部平台的原生第三方许可证。当前尚未实现完整文本分页排版、朗读、同步、全部旧宿主兼容和五平台 Release 验收。详见[完整迁移清单](../../docs/app-migration/README.md)。使用独立开发标识，不覆盖原 App；平台默认开发签名不可作为正式发行签名。
