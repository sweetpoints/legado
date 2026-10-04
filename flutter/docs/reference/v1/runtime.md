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

运行时需要与目标 CPU、操作系统匹配的 V8 库。原生构建通过官方 `package_ffi` 模板的构建 hook 接入。当前固定官方 V8 tag15.4.80.24（源码 commite422f6ef0c7b877b04e4872fd0bd3a1cc2ec2eee），采用官方源码与固定 depot_tools 8a5434051036b32412a2ecb10c213a72e3f3ccb9 的自产构建，不默认下载第三方预编译引擎。构建脚本与引擎/桥接共享GN和C++运行时，生成本地manifest；hook按manifest选择并校验本地最终C ABI库。当前源码构建目标为macOS arm64（13.0）和Android arm64（API26），其他平台/ABI不提供此构建。构建命令、manifest与覆盖路径见[source_v8 README](../../../packages/source_v8/README.md)。Dart 分析或单元测试通过，不能单独证明 Android 包已包含可加载的 V8，或真机的书源流程已经通过。

V8 编译参数、版本及引擎可用性应由诊断接口明确报告。运行时不可用时应报告错误，不得暗中转到 Rhino、JSC 或其他引擎并继续声称正在使用 V8。

## 官方机制参考

- [V8 嵌入指南](https://v8.dev/docs/embed)：宿主函数、Context 与值转换。
- [V8 Isolate API](https://v8.github.io/api/head/classv8_1_1Isolate.html)：Isolate、微任务与执行终止接口。

这些链接描述 V8 机制；本项目的实际接口与支持状态见新版 Reference 和包测试。


官方源码自编译 macOS ARM64 已完成链接并实际加载 V8 15.4.80.24。最终库包含10个 sv8 导出，大小46,032,368字节（43.90 MiB），SHA-256为 `c704139a9965577130dedc8262170d13c119a0281ca15d719960f89fcbb0f8c7`。V8包21项测试通过；全工作区8项静态检查无问题，139项测试通过，日志为仓库根目录 `tmp/flutter-source-check-stable-v8.log`。manifest的初始validation字段仍表示构建阶段，执行验证证据单独记录。

旧136项测试、三项Android设备测试及Release/R8结果属于14.3基线。新版macOS结果不能证明新版Android库、APK、设备和Release/R8通过；Android官方源码编译仍在进行，尚不声明新版Android验收通过。
