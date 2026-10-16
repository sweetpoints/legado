# Legado Flutter App（迁移开发中）

Flutter 3.47.6 官方 app 模板，包含 Android、iOS、macOS、Windows、Linux 工程。当前运行入口仅为旧书架 JSON 的只读预览，用于核对数据契约；不提供完整阅读器、备份恢复或五平台 V8 验收。

在工作区 `flutter pub get` 后进入本目录运行 `flutter run -d macos`。Material 3 支持系统亮暗主题；界面只使用 ColorScheme 颜色角色。

完整范围、阻塞项与完成定义见 [全应用迁移清单](../../docs/app-migration/README.md)。当前使用独立开发 application ID，不覆盖原 App 数据。平台模板的 Release 签名仍为开发默认，禁止作为正式发行签名。
