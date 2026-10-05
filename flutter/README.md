# Dart 书源引擎工作区

工程使用 Flutter 3.47.6 / Dart 3.13.5 官方脚手架生成：核心、兼容、迁移、工具和一致性测试使用 `package`，V8 使用 `package_ffi`，平台适配使用 `plugin`，Android 接入使用 `module`。保留官方工程结构；业务包移除不需要的 Flutter 依赖。多个工程由 Pub workspace 管理。

## 使用

在本目录运行 `flutter pub get`。执行 `bash tool/check.sh` 进行静态检查与测试。原生包和跨包测试必须通过 `dart run test:test` 启动，以执行 native build hooks。

使用 [source_tools](packages/source_tools/README.md) 校验、迁移或执行书源。迁移输出原始配置、候选和报告，候选状态不是兼容验证通过。

Android构建强制包含Flutter/V8；显式flutterSourceEngine=false报错，不能恢复旧引擎。先运行 `bash tool/prepare-android-aar.sh all` 准备Debug/Release AAR，普通Gradle构建会校验当前Dart源码摘要、固定V8来源、AAR/POM、资源和所选ABI库是否匹配。Android 构建需要现有 SDK、NDK 和 JDK 21。设置 `SOURCE_ENGINE_JDK` 为 JDK 21 路径，在本目录运行 `bash tool/build-android.sh`。脚本默认构建arm64-v8a与x86_64双ABI Debug AAR 和默认使用新引擎的应用、仪器测试 APK；不会修改全局 Flutter JDK 设置。

请使用此wrapper构建AAR：Flutter 3.47官方module模板默认minSdk为24，新V8要求26。wrapper只对AAR子进程使用工作区专用`.gradle-source-host/init.d`，通过公开Gradle beforeProject/androidComponents.finalizeDsl配置source_host为26，保持生成的`.android`文件不变。仅复用公共Gradle缓存与wrapper，不复制用户properties、init脚本或凭据；根Android应用仍使用原Gradle home。直接flutter build aar未应用该配置时会在API26检查失败。

运行 `bash tool/build-android.sh --release` 构建 Release AAR 与启用新引擎的 Release 应用，包含现有 R8 缩减流程。签名使用项目既有配置，构建命令不发布产物。

对匹配所选ABI的设备或模拟器设置 `ANDROID_SERIAL`，运行 `bash tool/test-android.sh` 可重建并执行专门的 Flutter 引擎仪器测试。脚本使用独立的 `.fluttertest` 应用 ID 后缀，避免覆盖模拟器上已有的 Debug 应用。测试覆盖 Android 实际 V8、异步搜索/目录/正文、WebBook 路由、无限脚本取消后恢复，以及引擎关闭重建后的会话恢复和书源隔离。

Android书源的search/explore/info/toc/content默认全部调用Dart/V8，不再用@engine选择或失败后回退旧引擎。@source:v1后接单行JSON仍承载现代配置；没有此配置时导入旧格式。编辑器固定显示Flutter/V8，并提供离线迁移预览：合格候选只写入当前草稿，可撤销，用户仍需正式保存。复杂旧能力继续明确报告迁移问题，默认使用Dart不代表所有旧书源等价。

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

V8默认从官方源码自编译：当前固定稳定版本15.4.80.24、源码commit e422f6ef0c7b877b04e4872fd0bd3a1cc2ec2eee，depot_tools固定8a5434051036b32412a2ecb10c213a72e3f3ccb9。不默认使用第三方预编译引擎。源码构建入口为`python3 flutter/tool/v8/build.py build --target macos-arm64`（仓库根目录），Android ARM64/x86_64均需在Linux x86_64主机构建。来源、工具链、GN参数、最终库哈希及收集的许可证记录在自产manifest中，native hook只消费匹配的本地artifact。具体命令与manifest覆盖方式见[source_v8](packages/source_v8/README.md)。

构建实现现支持macOS ARM64及Android ARM64/x86_64；Android脚本/App默认双ABI，SOURCE_ENGINE_ANDROID_TARGETS可显式单选并映射flutterSourceAbis，单选仍强制Dart引擎。Android x86_64固定官方V8源码已在Linux amd64容器完成编译，库28,321,712字节（27.01 MiB），SHA-256为 `0fd5e7d637ed676d11573c68e9f7d5e2f33d3a7c213e3a5ca7e5ba9589f07ce4`；ELF machine62、3个LOAD的16KiB对齐与10个sv8导出检查通过。默认双ABI Debug/Release AAR准备和两ABI哈希校验已通过，日志 `tmp/flutter-app-replacement-prepare-dual.log`；当前App验收见下文。新版官方源码macOS ARM64库已完成链接、实际加载15.4.80.24，本轮Flutter工作区378项测试、8项静态检查通过（64/60/15/23/22/173/1/20）。库大小46,032,368字节（43.90 MiB），SHA-256为`c704139a9965577130dedc8262170d13c119a0281ca15d719960f89fcbb0f8c7`，验证日志位于仓库根目录`tmp/flutter-source-check-legacy-scalars.log`。

此前官方 Flutter FFI example 的macOS Debug/Release已构建并运行成功，显示实际V8 15.4.80.24结果42。Release主可执行文件及V8 framework仅ARM64；local codesign有allow-jit。尚未完成notarization或Hardened Runtime分发验收。

新版官方源码Android ARM64库也完成链接；Debug AAR/APK内库与manifest一致，为26,306,760字节（25.09 MiB）、SHA-256 `0a2874dcf11c44213b10fe208bac6130bc00121b4433fdac1e19ac984053a593`。此前API36模拟器13项引擎仪器测试全部通过，日志位于仓库根目录 `tmp/flutter-android-test-page-requests-final.log`；这是13个case，不是13种设备配置。

此前旧表单轮次的JVM 4组golden测试、16个固定输入已真实通过，日志 `tmp/flutter-legacy-form-jvm-golden.log`。该证明限于固定输入的旧表单行为，不代表整个书源或完整旧JVM引擎等价。

旧JVM AnalyzeUrlPageTemplateGoldenTest的7项测试已通过，日志 `tmp/flutter-legacy-page-jvm-golden.log`；仅证明这组分页模板行为，不代表所有旧书源或任意动态JS兼容。

当前scalar兼容验收：Flutter工作区378项测试、8项静态检查及42项Python契约测试（12项native及30项工具）通过。默认双ABI Debug/Release AAR在2 GiB heap、1 GiB metaspace下实构建并通过native哈希校验，日志 `tmp/flutter-legacy-scalars-prepare-dual-2g.log`。ARM64 API36完整设备脚本的7类65项唯一测试全部通过，无失败、错误或跳过，日志 `tmp/flutter-legacy-scalars-device-final-65.log` 记录 BUILD SUCCESSFUL in13s。

本轮App Release在8 GiB heap配置通过全部任务，3,949项JVM测试无失败、错误或跳过，lint、R8及assemble成功；日志 `tmp/flutter-legacy-scalars-release-final-8g.log` 记录 BUILD SUCCESSFUL in3m28s。4 GiB full Release初试因GC thrashing保护终止；4 GiB App Debug设备验证与2 GiB AAR构建已通过，不能将这些结果视为4 GiB Release成功。最终APK内容哈希链已核验，证据 `tmp/flutter-final-release-apk-evidence.json`：APK为112,607,581字节，SHA-256 `eb76a5454a4f2baca369f5ba0413e31ecf7c46e783264a6a5520b58171666c1e`；Release AAR为22,595,466字节，SHA-256 `283857c85b48c9b5506f5ea78c91a4d4b5f53db18ba67810b14136b754a6ef77`。源码摘要与stamp一致，双ABI V8与manifest/AAR一致，AOT与当前AAR一致，NativeAssets双映射及Flutter SDK到APK的库链核对通过。APK未签名，x86_64设备及远端CI尚未验收；固定测试不证明全历史书源兼容或完整Flutter UI迁移。
