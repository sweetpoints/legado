# Android / Compose 接入

当前接入采用官方 Flutter module 运行 Dart 入口，不显示 Flutter 页面。Compose 继续调用 Kotlin Repository，Repository 管理任务状态并调用共享 Flutter Engine。Compose 重组不创建 Engine。

## 启用

Android 构建通过 `-PflutterSourceEngine=true` 引用 Flutter AAR。先按 `flutter/modules/source_host` 和宿主构建说明生成 AAR；不启用时保留既有 Android 构建路径。原生 V8 的 ABI 支持与 Flutter AAR 产物必须同时满足。

书源注释标记：

- `@engine:dart`：选择 Dart 旧格式导入链路；有需要人工处理的 issue 时明确失败。
- `@source:v1 <single-line JSON>`：提供新版书源配置；JSON 必须为单行。

选中 Dart 链路后失败会报告错误，不静默回到 Kotlin/Rhino。未标记的现有书源仍走既有链路，这不是已完成全量切换。

## 通信协议 v1

MethodChannel：`legado/source_engine`。当前使用请求/异步结果协议，未提供增量搜索批次事件协议。

| 方法 | 方向 | 内容 |
|---|---|---|
| `ready` | Dart → Kotlin | V8 执行 `1 + 1` 自检通过，协议版本为 1，完成握手后再发业务请求 |
| `startupError` | Dart → Kotlin | 初始化失败，错误码 `runtime_initialization_failed`，就绪等待立即失败 |
| `execute` | Kotlin → Dart | `protocolVersion:1`、`taskId`、`operation`、`sourceJson`、`input` |
| `cancel` | Kotlin → Dart | `taskId` |

execute 异步返回对象列表；operation 为 `search/explore/info/toc/content`。任务 ID 用于取消及关联状态。宿主把 taskId 注入 input 用于诊断；运行时自动将当前任务 ID 绑定到浏览器请求，使取消操作绑定到 Android 验证协程。Kotlin 将任务状态暴露给订阅者，Compose 不直接接触 V8 或平台通道。

启动自检的脚本限制为 5 秒，整体求值等待最多 10 秒；Kotlin 握手等待最多 30 秒。可捕获的原生资产加载错误通过 `startupError` 返回；原生进程崩溃不能通过通道错误恢复。

接口传输 JSON/标准通道数据；不传 Room 实体、Android Context、V8 对象或 Dart Future。Kotlin 负责将结果转回现有书籍、章节等应用模型。

## 平台交互

Dart 可通过 `source_host_platform` 请求 Android 浏览器验证，宿主接入现有 `SourceVerificationHelp`。浏览器结果包含 url、body、cookie，当前要求对应书源已注册在 Room。Dart 引擎在浏览器调用完成前自动将返回 Cookie 导入该书源 HTTP 会话。现有 Android 适配返回 Cookie 头时无法恢复原始 Cookie 属性，会按 host-only、响应目录和 HTTPS Secure 限制导入；平台若能提供 cookies 数组的 Set-Cookie 值，可保留明确属性。存储插件使用独立 preferences 区域管理适配数据。旧 Room 数据和全部旧 Cookie 没有因此自动迁移，也未提供 HTTP 与 WebView 的持续双向同步。

当前并不提供跨进程任务恢复或系统级后台调度。无 Flutter 视图也不意味着浏览器/登录能力无需 Activity。生命周期、真机登录和后台行为需要 Android 验证，Dart 测试不能替代。
