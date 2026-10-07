# Android / Compose 接入

当前接入采用官方 Flutter module 运行 Dart 入口，不显示 Flutter 页面。Compose 继续调用 Kotlin Repository，Repository 管理任务状态并调用共享 Flutter Engine。Compose 重组不创建 Engine。

## 默认执行与构建

WebBook 的 search/explore/info/toc/content 默认全部走 Dart/V8；`@engine` 不再选择执行引擎，失败不回退旧引擎。`@source:v1` 后接单行 JSON 仍是现代配置 carrier，没有时导入旧书源。RSS、TTS、分析规则/URL、登录与按钮、评论、MCP、图片脚本、正则替换、本地 TXT/导入/导出以及编辑器辅助脚本也使用同一 Dart/V8 backend；应用不再依赖 Rhino 模块或 Java AST 脚本运行时。

正常 Android 构建强制包含 Flutter AAR，显式 `flutterSourceEngine=false` 失败。在 Flutter 工作区运行 `bash tool/prepare-android-aar.sh all` 准备 Debug/Release AAR，或使用 `tool/build-android.sh` 构建当前模式及 App。脚本要求 JDK 21 和 Flutter 3.47.6。普通 Gradle 构建校验 Dart 源码摘要、native provenance、AAR/POM、Flutter assets 与所选 ABI 库；过期或缺少 AAR 不能退回旧引擎。App 默认包含 arm64-v8a 与 x86_64；`SOURCE_ENGINE_ANDROID_TARGETS` 可显式单选并映射 Gradle `flutterSourceAbis`。

V8 SDK 来源固定在 `tool/v8/release-pin.json` 的 Release、manifest 与目标资产摘要。`tool/v8/prepare_sdk.py` 下载并验证该 SDK，只编译应用的 `source_v8` FFI bridge；Flutter native-assets hook 复用同一流程。Legado 不拉取、配置或编译 V8 源码，旧 `tool/v8/build.py` 源码构建入口会明确失败。SDK 的下载验证、bridge 编译、AAR/APK 打包、设备执行和 CI 验收是独立证据；本文不保存某次构建的测试计数或制品哈希。

AAR子进程的专用Gradle home/init.d通过公开DSL将source_host module minSdk设为26，不修改生成.android，根App不使用该home；直接flutter build aar未配置module26会触发V8 API26门槛。

## 通信协议 v1

MethodChannel：`legado/source_engine`。当前使用请求/异步结果协议，未提供增量搜索批次事件协议。

| 方法 | 方向 | 内容 |
|---|---|---|
| `ready` | Dart → Kotlin | V8 执行 `1 + 1` 自检通过，协议版本为 1，完成握手后再发业务请求 |
| `startupError` | Dart → Kotlin | 初始化失败，错误码 `runtime_initialization_failed`，就绪等待立即失败 |
| `execute` | Kotlin → Dart | `protocolVersion:1`、`taskId`、`operation`、`sourceJson`、`input` |
| `cancel` | Kotlin → Dart | `taskId` |
| `evaluate` | Kotlin → Dart | protocolVersion:1、taskId、sourceJson、script、JSON bindings、可选严格bool ephemeral（默认false） |
| `migrate` | Kotlin → Dart | protocolVersion:1、旧格式sourceJson；离线预览，不创建运行时 |
| `evaluateAuxiliary` | Kotlin → Dart | protocolVersion:1、taskId、script、JSON bindings、可选 sourceId/sourceJson/prelude、timeoutMs |
| `checkAuxiliarySyntax` | Kotlin → Dart | protocolVersion:1、taskId、script；只编译，成功返回 null，失败返回 message/lineNumber/columnNumber |
| `clearSourceState` | Kotlin → Dart | protocolVersion:1、taskId、非空 sourceId；串行关闭该辅助源的真实 VM |

初始化会执行真实 V8 `1 + 1` 自检；脚本 timeout 5秒、Dart 等待10秒、关闭等待5秒。失败通过 startupError 报 `runtime_initialization_failed`，不会先发送 ready。Kotlin 等待 ready 最多30秒，收到启动错误立即失败；没有握手时给出明确启动超时错误。

execute 异步返回对象列表；operation 为 `search/explore/info/toc/content`。任务 ID 用于取消及关联状态。宿主把 taskId 注入 input 用于诊断；运行时自动将当前任务 ID 绑定到浏览器请求，使取消操作绑定到 Android 验证协程。Kotlin 将任务状态暴露给订阅者，Compose 不直接接触 V8 或平台通道。

启动自检的脚本限制为 5 秒，整体求值等待最多 10 秒；Kotlin 握手等待最多 30 秒。可捕获的原生资产加载错误通过 `startupError` 返回；原生进程崩溃不能通过通道错误恢复。

接口传输 JSON/标准通道数据；不传 Room 实体、Android Context、V8 对象或 Dart Future。Kotlin 负责将结果转回现有书籍、章节等应用模型。非空目录成功后通过既有 updateBookTocInfo 更新章节数、最新章、当前章、时间与 book type；空目录返回 TocEmptyException（chapter_list_empty 本地化文案），不修改书籍元数据。

Repository 关闭会让尚未完成的业务响应与 ready 等待以 IllegalStateException("Flutter source repository is closed") 结束；同步发送或编码异常也清理任务登记。此关闭行为未新增 MethodChannel 错误码。

## 平台交互

Dart 通过 `legado/source_host_platform` 的 `call` 方法发送 `{sourceId,taskId,method,arguments}`。任务 ID 由运行时注入，Kotlin 校验当前任务与源身份，沿提交者的 coroutine context 执行。`SourceHostCallbacks` 只处理显式白名单的纯 JSON 回调，包括分析/替换/本地目录、登录状态和 UI 操作；这些回调在浏览器的 Room 查找前派发，不要求局部脚本伪造已注册书源。

`browser.open` 接入现有 `SourceVerificationHelp`，返回 url、body、cookie 或 refetch 标记；浏览器依据源类型查找已注册的书源/RSS/TTS，并遵守调用方的导航抑制策略。抑制时等待验证的 open 明确失败，非等待的 show/start/openUrl/video 返回 null 并不打开 UI。Dart 在 open 返回时将 Cookie 导入对应 HTTP 会话；Cookie 头无法恢复原始属性，会按 host-only、响应目录与 HTTPS Secure 限制导入。平台若提供 Set-Cookie 值数组，可以保留属性。独立 preferences 管理适配存储，旧 Room 数据与全部旧 Cookie 不因此自动迁移，HTTP 与 WebView 也未提供持续双向同步。

当前并不提供跨进程任务恢复或系统级后台调度。无 Flutter 视图也不意味着浏览器/登录能力无需 Activity。生命周期、真机登录和后台行为需要 Android 验证，Dart 测试不能替代。

## 隔离测试应用

启用 Flutter 时 debug 包默认仍为 `com.legado.app.debug`。使用 `-PflutterSourceEngine=true -PflutterSourceTestSuffix=.fluttertest` 可生成独立测试应用 `com.legado.app.fluttertest`，与已有正式版/debug 版的数据空间分离。Android instrumentation 包按默认约定为 `com.legado.app.fluttertest.test`。仅此隔离标记会禁用 `processAppDebugGoogleServices`，因为测试应用没有 Firebase 客户端注册。

此选项是测试安装身份配置，不执行数据复制或旧数据库迁移。Release 应用保持原 release 后缀；不要把隔离测试包的成功当作用户原应用升级验收。

## 会话提交与并发

source_host 按 source id 与 legacy/modern 模式分开缓存引擎、保存会话；同一会话任务串行执行，不同书源可以并行。排队任务取消时不进入执行。规范化书源配置发生变化时关闭旧实例并创建新实例，避免继续使用旧规则。闲置缓存最多32个实例；关闭宿主时等待队列、状态提交及运行时释放。

默认 PlatformSessionStore 在按 source id SHA-256 分隔的私有 preferences 中，以保留键 `__engine.session.v1.legacy` 或 `__engine.session.v1.modern` 保存 `{formatVersion:1,origin,engine,runtime?}`。engine 包含新版变量与 HTTP Cookie；runtime 在支持时保存旧 java.get/put 变量。恢复只接受相同 baseUrl origin 的 v1 会话；恢复失败报 `session_restore_failed`，提交失败报 `session_write_failed`，不会静默当作没有会话。

执行结束的 finally 会尝试提交状态，包括失败或取消后已产生的会话变化；它不是业务事务回滚。脚本存储 API 禁止访问 `__engine.session.` 保留键空间。当前保存由 Android preferences 适配完成，不会自动迁移旧 Room 数据或旧 Java 宿主全局状态。

## 章节字段契约

TOC 的 isVip/isPay/isVolume 原生Boolean直接保留。旧源的字符串按既有 String.isTrue() 判断：空白或精确 `null` 为false，trim后忽略大小写的 false/no/not/0/0.0 为false，其余为true。v1配置含 metadata.legacyOriginal JSON对象时也保留这套旧字段语义，即使迁移候选 metadata.legacy=false；该判断与运行时宿主模式独立。没有旧来源标记的现代配置只接受Boolean或精确小写 `"true"`/`"false"` 字符串，其他非null值拒绝。旧来源的 updateTime 字符串映射为章节 tag。

卷章节 isVolume=true 且 url 为空白时，TOC mapper 合成 title+index 作为URL；该规则也支持现代卷。正文在已有缓存读取之后、引擎路由之前，遇到 isVolume 且 url 以 title 开头时返回空字符串，不执行正文规则。

## 旧来源结果格式化与发现入口

旧来源判定包括没有v1配置的旧格式书源、v1 metadata.legacy=true，以及 metadata.legacyOriginal 为JSON对象的候选。search/explore/info 的旧来源结果在映射、过滤或写入前，沿用 BookHelp.formatBookName/formatBookAuthor 的旧regex与trim；数字wordCount按 StringUtils.wordCountFormat 处理，kind中已提取的换行改为逗号。现代来源不执行这些格式化；详情空字符串保持既有不覆盖行为。详情重命名权限：存在legacyOriginal时读取其ruleBookInfo.canReName是否非空，否则读取原BookSource规则是否非空；纯现代来源遵循调用方canReName。

Android发现输入同时提供原始url、exploreUrl和page，不再在Kotlin预展开page或拆解请求options。Dart请求适配器按旧来源的选中exploreUrl处理有限分页模板和每分类字面量options；现代入口仍可使用url。未知options和动态JS在Dart中明确拒绝，不把options作为URL发送。

## 受保护基址候选执行

source_host 对导入issue仅允许精确例外：metadata.legacyBaseUrlUnavailable必须为true，且每个issue的code均为 legacy.base_url_requires_review、path均为 bookSourceUrl。此时允许进入引擎，由规则阶段的绝对HTTP(S) URL检查保护请求。任意其他issue、不同path或缺少flag仍阻断执行；例外不把候选status改成unverified或verified，也不允许从锚点猜测相对host。

## 辅助脚本与临时配置提取

Kotlin 的 `V8ScriptExecutor.evaluate/evaluateString` 是 suspend 边界，`evaluateBlocking` 仅供后台同步读取路径使用；主线程必须 await，不能阻塞共享 Flutter Engine 的 MethodChannel。接口保留 caller coroutine context 与取消，接受正数 timeoutMillis，传给 backend 的 timeoutMs 与外层超时一致。JSON 标量、字符串键 Map、List 和明确的 DTO 快照可以跨通道；未知 JVM 对象、非有限数、循环或过深结构会被拒绝。`java/source/sourceApi/globalThis` 等宿主名字不能由 bindings 覆盖；书源元数据使用 sourceData，book/chapter 使用 JSON 快照，不能调用旧 Java 实体方法。

辅助调用返回 `{value: JSON结果}`。传入 source 时，facade 以原始源类型与 key 生成 owner ID，并传递实际 baseUrl、headers、登录 headers 与导航源信息；相同 URL 的不同源类型不会共用 VM。非空 sourceId 保留真实 V8 VM，同 owner 的调用串行、不同 owner 可并行。prelude 为 jsLib/CryptoJS 源码，只在首次使用或内容变化后初始化；它不是每次调用重新执行的 Java Scope。`clearSourceState` 会关闭该 VM；无 sourceId 的辅助调用创建临时 VM 并在 finally 关闭。这些辅助 VM 不等同于前述持久化书源会话，也没有跨进程恢复承诺。

`checkAuxiliarySyntax` 使用独立 V8 context，仅编译原源码，不执行源码、prelude 或 host callback。成功返回 null，错误诊断带原源码的一基 lineNumber/columnNumber，编辑器据此定位。代码格式化在 V8 中执行现有 beautify 库，输入走 JSON binding，不借助 WebView 缓存共享输入。

旧 `evaluate` 协议仍供书源辅助包装器和配置提取使用，返回 `{value: JSON结果}`，检查重复 taskId 并支持取消。ephemeral=true 创建独立引擎，finally 关闭，不读取/写入 sessionStore 或修改既有缓存；`evaluateConfiguration` 使用此模式。发现菜单与 viewName/action、preUpdateJs、评论和登录 UI 在 V8 中 await 执行；允许回写的数据仍由 Kotlin 校验，失败不部分写入。JS 配置入口函数能力检测使用静态源码分析，不执行配置脚本。

批量正文通过 `WebBook.getContentBatchAwait` 运行 contentBatch 或 JS 书源的 getContentBatch。`java.cacheContent` 的实际任务 RPC 为 `[batchId,identifier,content]`，回存结果必须是 boolean；batch ID、章节匹配、内容保存 token、部分回存与过期编辑检查由 Kotlin BatchContentContext 管理。取消和失败不意味着撤销已成功保存的章节，也不能把失败当作整批已完成。

preUpdateJs用V8辅助入口，返回后先校验book/sourceInfo再原子回写允许的元数据；允许的非空字符串字段为bookUrl/tocUrl/name/author，可空字符串字段为coverUrl/intro/kind/wordCount/latestChapterTitle；删除、其他字段变化、类型变化或只读sourceInfo变化明确拒绝且不部分写入。

评论入口也通过V8 evaluate运行。可选的reviewSummary、reviewDetail、reviewReplies保留原位置参数，book/chapter作为JSON快照传入；包装器独立捕获mainJs中的函数并await返回值，缺失函数明确返回不存在。字符串结果保留，其他非空结果序列化为JSON。

登录UI v2在async作用域调用并await `loginUi(state)` 或 `loginAction(action,state,form)`；状态和表单由JSON解析得到，book/chapter绑定为JSON快照。旧Java实体方法不能通过这些绑定调用。

MCP书源脚本通过V8 evaluate执行：此上下文的source将JSON书源元数据置于现代host API原型之上，保留受保护的API名称，sourceApi为同一对象。它与mainJs中仅为JSON数据的source/sourceApi有不同绑定合同。RSS MCP 脚本通过 BaseSource 的 V8 辅助入口执行，无书源脚本使用临时 V8ScriptExecutor；均不回退旧引擎。JsSourceEngine 只归一化 V8 返回的 JSON 结果，不创建旧脚本运行时。

## 同步回调与 RSS 导航边界

同步 JS host callback 占用当前 owner VM；Android 提取回调若递归调用该 VM 的 JavaScript，会报 `nested_script_requires_migration`，避免等待同一串行会话而死锁。需要再次执行脚本的逻辑应移到外层可 await 的脚本流程，不能在同步 Java 提取回调中隐式嵌套执行。

RSS 的主 frame 导航拦截脚本使用可取消的 V8 异步求值，再由 Android 按结果处理原始导航。WebView 的子 frame 请求无法通过主 frame 的 loadUrl 等价重放：配置了 shouldOverrideUrlLoading 脚本时，子 frame 路径记录 `unsupported_subframe_interception`，保留原生 HTTP frame 加载和现有外部 scheme 路由，不执行该脚本。该边界不表示 RSS 页面内容不再使用 WebView，也不表示任意子 frame 拦截已兼容。
