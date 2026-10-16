# Legado Flutter App（迁移开发中）

Flutter 3.47.6 官方五平台 app 工程。当前已有 Drift 书架存储、TXT 导入与连续阅读、书源管理/搜索/发现/详情、RSS/Atom/有限 HTML 订阅、ZIP 备份原子恢复与导出、语言和主题设置。

统一技术栈：Drift + Riverpod、Freezed、Dio、go_router ^18.1.0、material_ui ^1.7.0、官方 ARB/gen_l10n 中英国际化。

在工作区 `flutter pub get`，再执行 `python3 tool/merge-app-localizations.py`；进入本目录运行 `flutter gen-l10n`、`dart run build_runner build`、`flutter run -d macos`。V8 消费固定预编译 SDK，禁止在此工程构建 V8。App 测试目前使用支持 V8 的 macOS；Windows/Linux 的数据包测试与整个 App 原生支持是不同证据。

当前尚未实现 EPUB/PDF、完整分页排版、朗读、同步、全部旧宿主兼容和五平台 Release 验收。详见[完整迁移清单](../../docs/app-migration/README.md)。使用独立开发标识，不覆盖原 App；平台默认开发签名不可作为正式发行签名。
