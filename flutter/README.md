# Dart 书源引擎工作区

工程使用 Flutter 3.47.6 / Dart 3.13.5 官方脚手架生成：核心、兼容、迁移、工具和一致性测试使用 `package`，V8 使用 `package_ffi`，平台适配使用 `plugin`，Android 接入使用 `module`。保留官方工程结构；业务包移除不需要的 Flutter 依赖。多个工程由 Pub workspace 管理。

## 使用

在本目录运行 `flutter pub get`。执行 `bash tool/check.sh` 进行静态检查与测试。原生包和跨包测试必须通过 `dart run test:test` 启动，以执行 native build hooks。

使用 [source_tools](packages/source_tools/README.md) 校验、迁移或执行书源。迁移输出原始配置、候选和报告，候选状态不是兼容验证通过。

Android 构建需要现有 SDK、NDK 和 JDK 21。设置 `SOURCE_ENGINE_JDK` 为 JDK 21 路径，在本目录运行 `bash tool/build-android.sh`。脚本构建 ARM64 Debug AAR 和启用新引擎的应用、仪器测试 APK；不会修改全局 Flutter JDK 设置。

请使用此wrapper构建AAR：Flutter 3.47官方module模板默认minSdk为24，新V8要求26。wrapper只对AAR子进程使用工作区专用`.gradle-source-host/init.d`，通过公开Gradle beforeProject/androidComponents.finalizeDsl配置source_host为26，保持生成的`.android`文件不变。仅复用公共Gradle缓存与wrapper，不复制用户properties、init脚本或凭据；根Android应用仍使用原Gradle home。直接flutter build aar未应用该配置时会在API26检查失败。

运行 `bash tool/build-android.sh --release` 构建 Release AAR 与启用新引擎的 Release 应用，包含现有 R8 缩减流程。签名使用项目既有配置，构建命令不发布产物。

对现有 ARM64 设备或模拟器设置 `ANDROID_SERIAL`，运行 `bash tool/test-android.sh` 可重建并执行专门的 Flutter 引擎仪器测试。脚本使用独立的 `.fluttertest` 应用 ID 后缀，避免覆盖模拟器上已有的 Debug 应用。测试覆盖 Android 实际 V8、异步搜索/目录/正文、WebBook 路由、无限脚本取消后恢复，以及引擎关闭重建后的会话恢复和书源隔离。

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

V8默认从官方源码自编译：当前固定稳定版本15.4.80.24、源码commit e422f6ef0c7b877b04e4872fd0bd3a1cc2ec2eee，depot_tools固定8a5434051036b32412a2ecb10c213a72e3f3ccb9。不默认使用第三方预编译引擎。源码构建入口为`python3 flutter/tool/v8/build.py build --target macos-arm64`（仓库根目录），Android ARM64需在Linux x86_64主机构建。来源、工具链、GN参数、最终库哈希及收集的许可证记录在自产manifest中，native hook只消费匹配的本地artifact。具体命令与manifest覆盖方式见[source_v8](packages/source_v8/README.md)。

当前自编译目标为macOS ARM64与Android ARM64；当前宿主构建脚本和启用引擎APK限制为Android ARM64。新版官方源码macOS ARM64库已完成链接、实际加载15.4.80.24，并通过V8本包21项测试及全工作区227项测试、8项静态检查。库大小46,032,368字节（43.90 MiB），SHA-256为`c704139a9965577130dedc8262170d13c119a0281ca15d719960f89fcbb0f8c7`，验证日志位于仓库根目录`tmp/flutter-source-check-source-identity-final.log`。

官方 Flutter FFI example 的macOS Debug/Release也构建并运行成功，显示实际V8 15.4.80.24结果42。Release主可执行文件及V8 framework仅ARM64；local codesign有allow-jit。尚未完成notarization或Hardened Runtime分发验收。

新版官方源码Android ARM64库也完成链接；Debug AAR/APK内库与manifest一致，为26,306,760字节（25.09 MiB）、SHA-256 `0a2874dcf11c44213b10fe208bac6130bc00121b4433fdac1e19ac984053a593`。本轮API36模拟器11项引擎仪器测试全部通过，日志位于仓库根目录 `tmp/flutter-android-test-source-identity-final.log`；这是11个case，不是11种设备配置。

本轮书源ID/基址修改的本地Release/R8已通过，日志 `tmp/flutter-android-release-source-identity.log` 明确 BUILD SUCCESSFUL in2m13s 且 minifyAppReleaseWithR8 实际执行。Debug AAR/APK的10项Flutter assets，以及Release AAR/APK的7项Flutter assets，均已逐项验证SHA-256一致。Release两产物内libapp.so均为3,277,704字节，SHA-256均为 `f9b6ed5e8ca365d2d7e99d499c8fae8f3ed31c3e2a87e23a8159cde3375e8250`；libsource_v8.so均为26,306,760字节，SHA-256一致且匹配上述manifest。未签名APK属于本地构建结果，不表示正式发布或CI验收；固定case通过不证明所有历史书源兼容或完整Flutter UI迁移完成。旧版本及较早轮次的验证属于历史记录，不能替代本轮证明。
