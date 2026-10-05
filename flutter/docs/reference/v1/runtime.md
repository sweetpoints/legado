# JavaScript 运行时

书源脚本由 V8 执行。V8 适配包与书源核心分离：规则解析、请求编排和提取由 Dart 完成，运行时负责执行 JS、宿主回调和返回值转换。

## 执行环境

嵌入式 V8 不是浏览器。书源不能因使用 V8 就假定 `window`、`document`、DOM、浏览器 `fetch` 或页面 Cookie 已经存在。只有 Reference 中列出的宿主 API 才属于书源契约。网页执行和登录必须通过平台能力接入。

`java.*` 仅属于旧版兼容环境；新版宿主命名空间为 `source`。迁移器与兼容层的支持范围见[旧版 Reference](../legacy/README.md)。

## 异步行为

异步 API 返回 Promise。脚本必须等待需要的结果，不能把 Promise 当作正文、响应对象或字符串使用。宿主负责在外部操作完成时解决或拒绝 Promise，并推进 V8 的微任务队列；单纯执行脚本并读取即时结果不能替代这一过程。

`V8Runtime` 每次 evaluate 创建独立 JS Context，工作 isolate 执行并轮询宿主请求。新版 `source` 调用为 Promise，父 isolate 分派后回传结果并推进微任务。变量及结果以 JSON 传输；循环对象、BigInt、函数等不能作为普通 JSON 结果。

默认 `heapLimitMb=64` 设置 V8 old-generation 约束，不是整个进程硬内存上限。脚本超时由 watchdog 终止执行，取消也调用 V8 TerminateExecution；宿主自身的任务取消还需按其接口处理。超时报 `script_timeout`，其他脚本异常为 `script_error`。close 拒绝后续执行并取消活动执行。

脚本先按表达式放入 async 包装，编译失败再按函数体尝试，因而支持表达式以及带 return 的异步函数体。每次执行的 JS 全局状态不保留，跨任务共享变量使用宿主变量 API。当前自编译配置关闭 Intl 和 Temporal，因此不提供相应 API；书源不能假设系统国际化行为自动可用。

## 原生依赖

运行时需要与目标 CPU、操作系统匹配的 V8 库。原生构建通过官方 `package_ffi` 模板的构建 hook 接入。当前固定官方 V8 稳定版本15.4.80.24（源码 commite422f6ef0c7b877b04e4872fd0bd3a1cc2ec2eee），采用官方源码与固定 depot_tools 8a5434051036b32412a2ecb10c213a72e3f3ccb9 的自产构建，不默认下载第三方预编译引擎。构建脚本与引擎/桥接共享GN和C++运行时，生成本地manifest；hook按manifest选择并校验本地最终C ABI库。当前源码构建目标为macOS arm64（13.0）和Android arm64（API26），其他平台/ABI不提供此构建。构建命令、manifest与覆盖路径见[source_v8 README](../../../packages/source_v8/README.md)。Dart 分析或单元测试通过，不能单独证明 Android 包已包含可加载的 V8，或真机的书源流程已经通过。

V8 编译参数、版本及引擎可用性应由诊断接口明确报告。运行时不可用时应报告错误，不得暗中转到 Rhino、JSC 或其他引擎并继续声称正在使用 V8。

## 官方机制参考

- [V8 嵌入指南](https://v8.dev/docs/embed)：宿主函数、Context 与值转换。
- [V8 Isolate API](https://v8.github.io/api/head/classv8_1_1Isolate.html)：Isolate、微任务与执行终止接口。

这些链接描述 V8 机制；本项目的实际接口与支持状态见新版 Reference 和包测试。


官方源码自编译 macOS ARM64 已完成链接并实际加载 V8 15.4.80.24。最终库包含10个 sv8 导出，大小46,032,368字节（43.90 MiB），SHA-256为 `c704139a9965577130dedc8262170d13c119a0281ca15d719960f89fcbb0f8c7`。本轮Flutter全工作区8项静态检查无问题，355项测试通过（51/53/12/23/22/173/1/20），日志为仓库根目录 `tmp/flutter-source-check-app-replacement-current.log`。manifest的初始validation字段仍表示构建阶段，执行验证证据单独记录。

此前官方 Flutter FFI example 的 Debug、Release 构建及界面执行已通过，界面显示实际 V8 15.4.80.24 计算结果42。Release主可执行文件和source_v8 framework经file检查均仅ARM64，local codesign检查确认allow-jit。这里只确认本地构建、运行和签名配置；尚未完成notarization或Hardened Runtime分发验收。

官方源码Android ARM64库也完成链接，Debug AAR与APK中库均为26,306,760字节（25.09 MiB），SHA-256为 `0a2874dcf11c44213b10fe208bac6130bc00121b4433fdac1e19ac984053a593`，与manifest一致。ELF检查确认ARM64、PT_LOAD 16 KiB对齐和仅c/dl/log/m系统依赖。此前API36模拟器的13项引擎仪器测试通过，日志为 `tmp/flutter-android-test-page-requests-final.log`；这里是13个测试case，不是13种设备配置。

此前分页/选中请求修改的本地Release/R8已通过，日志 `tmp/flutter-android-release-page-requests.log` 明确 BUILD SUCCESSFUL in4m1s 且 minifyAppReleaseWithR8 实际执行。Debug AAR/APK的10项Flutter assets，以及Release AAR/APK的7项Flutter assets，均已逐项验证SHA-256一致。Release两产物内libapp.so均为3,277,704字节，SHA-256均为 `dd95b81cdb3bd83feddbb39de938159a3889a2ceb757196ec0a860fe60b2ec91`；libsource_v8.so均为26,306,760字节，SHA-256一致且匹配上述manifest。未签名APK属于本地构建结果，不表示正式发布或CI验收；固定case通过不证明所有历史书源兼容或完整Flutter UI迁移完成。旧版本及较早轮次的验证属于历史记录，不能替代本轮证明。

此前旧表单轮次的JVM 4组golden测试、16个固定输入已真实通过，日志 `tmp/flutter-legacy-form-jvm-golden.log`。该证明限于固定输入的旧表单行为，不代表整个书源或完整旧JVM引擎等价。

旧JVM AnalyzeUrlPageTemplateGoldenTest的7项测试已通过，日志 `tmp/flutter-legacy-page-jvm-golden.log`；仅证明这组分页模板行为，不代表所有旧书源或任意动态JS兼容。

此前分页请求轮次的Android最终重跑13个case均通过，fail/error/skip均为0；最终Debug AAR/APK的10项Flutter assets也已重新核对一致。本轮Release产物校验证据为 `tmp/flutter-page-requests-artifact-proof.json`。

本轮默认Dart-only书源执行、迁移预览及评论、登录和MCP辅助入口已接入。Flutter工作区355项测试、8项静态检查及40项Python契约测试（12/18/10）已通过。默认双ABI Android全量Release构建已通过：3,949项JVM测试无失败或错误，lintAppRelease、R8及assembleAppRelease成功，日志为 `tmp/flutter-app-replacement-release-final.log`。未签名APK大小112,607,581字节，SHA-256为 `3c9701ba9bf501886f72953dfab62a469973269a6578d2bb3c16d0960932d78e`。默认双ABI构建在ARM64 API36设备通过完整 `test-android.sh` 验证：7个测试类、63项唯一测试全部成功，无失败、错误或跳过；本次 `--since` 严格结果校验通过，日志 `tmp/flutter-app-replacement-device-final-63.log` 记录 BUILD SUCCESSFUL in12s。x86_64 CI配置已具备，远端CI及x86_64设备验收尚未完成。此前13个设备case属于早先分页请求轮次。

Android x86_64目标CPU=x64、ABI=x86_64、minAPI26；官方源码已编译成功（40m21s），最终库28,321,712字节（27.01 MiB），SHA-256 `0fd5e7d637ed676d11573c68e9f7d5e2f33d3a7c213e3a5ca7e5ba9589f07ce4`，ELF machine62、3个LOAD的16KiB对齐及10个sv8导出检查通过。默认双ABI Debug/Release AAR准备和native哈希校验通过，日志 `tmp/flutter-app-replacement-prepare-dual.log`；双ABI Release App已构建成功且APK SHA已记录；ARM64完整脚本63项测试通过，x86_64设备及远端CI验收仍待完成。Android默认arm64-v8a+x86_64，显式单选仍强制Dart/V8；macOS继续仅ARM64。
