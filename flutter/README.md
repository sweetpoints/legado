# Dart 书源引擎工作区

工程使用 Flutter 3.47.6 / Dart 3.13.5 官方脚手架生成：核心、兼容、迁移、工具和一致性测试使用 `package`，V8 使用 `package_ffi`，平台适配使用 `plugin`，Android 接入使用 `module`。保留官方工程结构；业务包移除不需要的 Flutter 依赖。多个工程由 Pub workspace 管理。

## 使用

在本目录运行 `flutter pub get`。执行 `bash tool/check.sh` 进行静态检查与测试。原生包和跨包测试必须通过 `dart run test:test` 启动，以执行 native build hooks。

使用 [source_tools](packages/source_tools/README.md) 校验、迁移或执行书源。迁移输出原始配置、候选和报告，候选状态不是兼容验证通过。

Android 构建需要现有 SDK、NDK 和 JDK 21。设置 `SOURCE_ENGINE_JDK` 为 JDK 21 路径，在本目录运行 `bash tool/build-android.sh`。脚本构建 ARM64 Debug AAR 和启用新引擎的应用、仪器测试 APK；不会修改全局 Flutter JDK 设置。

对现有 ARM64 设备或模拟器设置 `ANDROID_SERIAL`，运行 `bash tool/test-android.sh` 可重建并执行专门的 Flutter 引擎仪器测试。脚本使用独立的 `.fluttertest` 应用 ID 后缀，避免覆盖模拟器上已有的 Debug 应用。测试覆盖 Android 实际 V8、异步搜索/目录/正文、WebBook 路由及无限脚本取消后恢复。

在现有书源编辑器的注释中加入独立一行 `@engine:dart`，选择旧版兼容入口；使用 `@source:v1 ` 后接单行新版 JSON，选择新版入口。未选择的书源仍使用原引擎。选中 Dart 的书源失败时不会静默回退。构建未启用 `-PflutterSourceEngine=true` 时，选择 Dart 会明确报告缺少后端。

## 包边界

| 包 | 职责 |
| --- | --- |
| source_engine | 纯 Dart 规则、提取、流程、网络会话、请求调度和分页 |
| source_v8 | 固定版本真实 V8、FFI、异步和同步宿主桥、超时与取消 |
| source_legacy | 旧书源结构与有限 `java.*` 契约 |
| source_migration | 保守转换，保留原始输入并报告人工处理项 |
| source_platform | 持久化、Cookie 和浏览器平台接口 |
| source_host | Kotlin/Dart 请求协议和当前 Android 宿主组装 |
| source_tools | 校验、迁移、执行命令 |
| source_conformance | 本地固定 HTTP 和实际 V8 的跨包测试 |

## Reference 与兼容边界

正式契约入口为 [书源 Reference](docs/reference/README.md)，另有独立的 [旧版兼容 Reference](docs/reference/legacy/README.md) 与 [迁移说明](docs/migration/README.md)。机器规范位于 `spec/`。

当前不是全部历史书源的等价替代。复杂旧脚本、Java 类互操作、未实现的宿主重载与 JSoup 专有规则会报告人工处理。分页、请求限速和部分编码已实现；浏览器、运行时与平台边界以 Reference 和实际测试为准。固定样本通过不代表所有联网书源或所有设备通过。

V8 原生产物固定版本并校验 SHA-256，自动下载至忽略目录，不提交二进制缓存。当前构建适配 macOS ARM64 和 Android ARM/ARM64/x64；其他平台没有宣称实现。
