# Android / Compose 接入

当前接入采用官方 Flutter module 运行 Dart 入口，不显示 Flutter 页面。Compose 继续调用 Kotlin Repository，Repository 管理任务状态并调用共享 Flutter Engine。Compose 重组不创建 Engine。

## 启用

Android 构建通过 `-PflutterSourceEngine=true` 引用 Flutter AAR。先按 `flutter/modules/source_host` 和宿主构建说明生成 AAR；不启用时保留既有 Android 构建路径。当前启用 Flutter 的宿主 APK 限定 ARM64 ABI，避免其他 Android JNI 库使 APK 宣告 V8/AAR 尚未打包的架构。V8当前自编译Android目标也仅ARM64；未启用 Flutter 的既有构建不受此过滤影响。

构建AAR应从flutter工作区使用`bash tool/build-android.sh`（或`--release`）。wrapper为AAR子进程配置专用Gradle home/init.d，以公开DSL将官方source_host module的默认minSdk24提升为26；不编辑生成的.android，根app不使用该专用home。只复用公共cache/wrapper，不复制用户配置、init脚本或凭据。直接flutter build aar未配置module26时会触发V8 API26门槛错误。此配置检查不是新版Android native编译或设备验证证明。

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

初始化会执行真实 V8 `1 + 1` 自检；脚本 timeout 5秒、Dart 等待10秒、关闭等待5秒。失败通过 startupError 报 `runtime_initialization_failed`，不会先发送 ready。Kotlin 等待 ready 最多30秒，收到启动错误立即失败；没有握手时给出明确启动超时错误。

execute 异步返回对象列表；operation 为 `search/explore/info/toc/content`。任务 ID 用于取消及关联状态。宿主把 taskId 注入 input 用于诊断；运行时自动将当前任务 ID 绑定到浏览器请求，使取消操作绑定到 Android 验证协程。Kotlin 将任务状态暴露给订阅者，Compose 不直接接触 V8 或平台通道。

启动自检的脚本限制为 5 秒，整体求值等待最多 10 秒；Kotlin 握手等待最多 30 秒。可捕获的原生资产加载错误通过 `startupError` 返回；原生进程崩溃不能通过通道错误恢复。

接口传输 JSON/标准通道数据；不传 Room 实体、Android Context、V8 对象或 Dart Future。Kotlin 负责将结果转回现有书籍、章节等应用模型。

## 平台交互

Dart 可通过 `source_host_platform` 请求 Android 浏览器验证，宿主接入现有 `SourceVerificationHelp`。浏览器结果包含 url、body、cookie，当前要求对应书源已注册在 Room。Dart 引擎在浏览器调用完成前自动将返回 Cookie 导入该书源 HTTP 会话。现有 Android 适配返回 Cookie 头时无法恢复原始 Cookie 属性，会按 host-only、响应目录和 HTTPS Secure 限制导入；平台若能提供 cookies 数组的 Set-Cookie 值，可保留明确属性。存储插件使用独立 preferences 区域管理适配数据。旧 Room 数据和全部旧 Cookie 没有因此自动迁移，也未提供 HTTP 与 WebView 的持续双向同步。

当前并不提供跨进程任务恢复或系统级后台调度。无 Flutter 视图也不意味着浏览器/登录能力无需 Activity。生命周期、真机登录和后台行为需要 Android 验证，Dart 测试不能替代。

## 隔离测试应用

启用 Flutter 时 debug 包默认仍为 `com.legado.app.debug`。使用 `-PflutterSourceEngine=true -PflutterSourceTestSuffix=.fluttertest` 可生成独立测试应用 `com.legado.app.fluttertest`，与已有正式版/debug 版的数据空间分离。Android instrumentation 包按默认约定为 `com.legado.app.fluttertest.test`。仅此隔离标记会禁用 `processAppDebugGoogleServices`，因为测试应用没有 Firebase 客户端注册。

此选项是测试安装身份配置，不执行数据复制或旧数据库迁移。Release 应用保持原 release 后缀；不要把隔离测试包的成功当作用户原应用升级验收。

## 会话提交与并发

source_host 按 source id 与 legacy/modern 模式分开缓存引擎、保存会话；同一会话任务串行执行，不同书源可以并行。排队任务取消时不进入执行。规范化书源配置发生变化时关闭旧实例并创建新实例，避免继续使用旧规则。闲置缓存最多32个实例；关闭宿主时等待队列、状态提交及运行时释放。

默认 PlatformSessionStore 在按 source id SHA-256 分隔的私有 preferences 中，以保留键 `__engine.session.v1.legacy` 或 `__engine.session.v1.modern` 保存 `{formatVersion:1,origin,engine,runtime?}`。engine 包含新版变量与 HTTP Cookie；runtime 在支持时保存旧 java.get/put 变量。恢复只接受相同 baseUrl origin 的 v1 会话；恢复失败报 `session_restore_failed`，提交失败报 `session_write_failed`，不会静默当作没有会话。

执行结束的 finally 会尝试提交状态，包括失败或取消后已产生的会话变化；它不是业务事务回滚。脚本存储 API 禁止访问 `__engine.session.` 保留键空间。当前保存由 Android preferences 适配完成，不是旧 Room 或旧 Rhino 全局状态自动迁移。
