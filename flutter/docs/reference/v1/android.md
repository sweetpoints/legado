# Android / Compose 接入

当前接入采用官方 Flutter module 运行 Dart 入口，不显示 Flutter 页面。Compose 继续调用 Kotlin Repository，Repository 管理任务状态并调用共享 Flutter Engine。Compose 重组不创建 Engine。

## 默认执行与构建

WebBook的search/explore/info/toc/content默认全部走Dart/V8；@engine不再选择执行引擎，失败不回退旧AnalyzeRule/AnalyzeUrl/JsSourceEngine。@source:v1后接单行JSON仍是现代配置carrier，没有时导入旧书源。BookSource进入旧规则/URL/JS执行入口报engine_migration_required；共享RSS和无BookSource场景不属于此次书源替换范围。

正常Android构建强制包含Flutter AAR，显式flutterSourceEngine=false失败。工作区运行 `bash tool/prepare-android-aar.sh all` 准备Debug/Release AAR，或使用build-android.sh构建当前模式及App。普通Gradle构建校验Dart源码摘要、官方native provenance、AAR/POM、Flutter assets与所选ABI库；过期或缺少AAR不能退回旧引擎。App构建默认arm64-v8a+x86_64；SOURCE_ENGINE_ANDROID_TARGETS可显式单选并映射Gradle flutterSourceAbis。单ABI不会启用旧引擎。Android官方两ABI native构建已完成。当前验收为378项Flutter测试、8项analysis、42项工具/native测试；默认双ABI AAR在2 GiB heap/1 GiB metaspace构建及hash验证通过（tmp/flutter-legacy-scalars-prepare-dual-2g.log）。ARM64 API36完整脚本7类65项测试无失败/错误/跳过（tmp/flutter-legacy-scalars-device-final-65.log）。App全量Release在8 GiB heap通过3949项JVM测试、lint、R8及assemble（tmp/flutter-legacy-scalars-release-final-8g.log）；4 GiB初试因GC thrashing终止，不能标为Release成功。最终APK哈希链全部通过：APK112,607,581B/SHA eb76a5454a4f2baca369f5ba0413e31ecf7c46e783264a6a5520b58171666c1e；ReleaseAAR22,595,466B/SHA 283857c85b48c9b5506f5ea78c91a4d4b5f53db18ba67810b14136b754a6ef77，证据tmp/flutter-final-release-apk-evidence.json。双ABI native/AOT/NativeAssets及SDK到APK链一致；APK未签名，x86_64设备/远端CI待验，macOS仍仅ARM64。

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

初始化会执行真实 V8 `1 + 1` 自检；脚本 timeout 5秒、Dart 等待10秒、关闭等待5秒。失败通过 startupError 报 `runtime_initialization_failed`，不会先发送 ready。Kotlin 等待 ready 最多30秒，收到启动错误立即失败；没有握手时给出明确启动超时错误。

execute 异步返回对象列表；operation 为 `search/explore/info/toc/content`。任务 ID 用于取消及关联状态。宿主把 taskId 注入 input 用于诊断；运行时自动将当前任务 ID 绑定到浏览器请求，使取消操作绑定到 Android 验证协程。Kotlin 将任务状态暴露给订阅者，Compose 不直接接触 V8 或平台通道。

启动自检的脚本限制为 5 秒，整体求值等待最多 10 秒；Kotlin 握手等待最多 30 秒。可捕获的原生资产加载错误通过 `startupError` 返回；原生进程崩溃不能通过通道错误恢复。

接口传输 JSON/标准通道数据；不传 Room 实体、Android Context、V8 对象或 Dart Future。Kotlin 负责将结果转回现有书籍、章节等应用模型。非空目录成功后通过既有 updateBookTocInfo 更新章节数、最新章、当前章、时间与 book type；空目录返回 TocEmptyException（chapter_list_empty 本地化文案），不修改书籍元数据。

Repository 关闭会让尚未完成的业务响应与 ready 等待以 IllegalStateException("Flutter source repository is closed") 结束；同步发送或编码异常也清理任务登记。此关闭行为未新增 MethodChannel 错误码。

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

## 章节字段契约

TOC 的 isVip/isPay/isVolume 原生Boolean直接保留。旧源的字符串按既有 String.isTrue() 判断：空白或精确 `null` 为false，trim后忽略大小写的 false/no/not/0/0.0 为false，其余为true。v1配置含 metadata.legacyOriginal JSON对象时也保留这套旧字段语义，即使迁移候选 metadata.legacy=false；该判断与运行时宿主模式独立。没有旧来源标记的现代配置只接受Boolean或精确小写 `"true"`/`"false"` 字符串，其他非null值拒绝。旧来源的 updateTime 字符串映射为章节 tag。

卷章节 isVolume=true 且 url 为空白时，TOC mapper 合成 title+index 作为URL；该规则也支持现代卷。正文在已有缓存读取之后、引擎路由之前，遇到 isVolume 且 url 以 title 开头时返回空字符串，不执行正文规则。

## 旧来源结果格式化与发现入口

旧来源判定包括没有v1配置的旧格式书源、v1 metadata.legacy=true，以及 metadata.legacyOriginal 为JSON对象的候选。search/explore/info 的旧来源结果在映射、过滤或写入前，沿用 BookHelp.formatBookName/formatBookAuthor 的旧regex与trim；数字wordCount按 StringUtils.wordCountFormat 处理，kind中已提取的换行改为逗号。现代来源不执行这些格式化；详情空字符串保持既有不覆盖行为。详情重命名权限：存在legacyOriginal时读取其ruleBookInfo.canReName是否非空，否则读取原BookSource规则是否非空；纯现代来源遵循调用方canReName。

Android发现输入同时提供原始url、exploreUrl和page，不再在Kotlin预展开page或拆解请求options。Dart请求适配器按旧来源的选中exploreUrl处理有限分页模板和每分类字面量options；现代入口仍可使用url。未知options和动态JS在Dart中明确拒绝，不把options作为URL发送。

## 受保护基址候选执行

source_host 对导入issue仅允许精确例外：metadata.legacyBaseUrlUnavailable必须为true，且每个issue的code均为 legacy.base_url_requires_review、path均为 bookSourceUrl。此时允许进入引擎，由规则阶段的绝对HTTP(S) URL检查保护请求。任意其他issue、不同path或缺少flag仍阻断执行；例外不把候选status改成unverified或verified，也不允许从锚点猜测相对host。

## 辅助脚本与临时配置提取

evaluate返回 `{value: JSON结果}`，可取消并检查重复taskId。ephemeral=true为每次调用创建独立引擎和任务队列，finally关闭；不读取/写入sessionStore、不进入缓存或修改既有会话。配置提取evaluateConfiguration使用此模式。旧格式辅助脚本当前要求HTTP(S)书源identity及静态字符串headers，jsLib/动态headers仍报legacy_requires_migration；这与规则阶段的非HTTP ID锚点支持范围不同。

BookSource同步evalJS在主线程报engine_migration_required，后台转到V8。bindings只接受显式JSON标量、字符串键Map、List、Book/BookChapter DTO；未知对象、非有限数、循环及深层结构报engine_bindings_requires_migration，不反射Java宿主。保留宿主名字java/source/sourceApi/cookie/cache/global/globalThis不从旧bindings传入。DTO没有旧Java实体方法。

发现菜单、viewName/action脚本通过evaluate运行，infoMap为字符串键值JSON快照；校验成功后保存允许变更，旧InfoMap实体方法不兼容。动态菜单错误可显示ERROR条目或标签，取消仍传播。JS书源配置提取在后台的临时V8 lexical async IIFE捕获config/旧source并校验入口函数；原mainJs保留，配置能力检测只做静态分析：AST分析配合现代顶层声明lexer后备支持async及箭头函数，跳过注释、字符串、正则及模板字面量；它不执行脚本。

preUpdateJs用V8辅助入口，返回后先校验book/sourceInfo再原子回写允许的元数据；允许的非空字符串字段为bookUrl/tocUrl/name/author，可空字符串字段为coverUrl/intro/kind/wordCount/latestChapterTitle；删除、其他字段变化、类型变化或只读sourceInfo变化明确拒绝且不部分写入。批量正文调度通过单章Dart路径处理待下载章节；这不表示旧批量缓存/回调协议或contentBatch已经等价支持。

评论入口也通过V8 evaluate运行。可选的reviewSummary、reviewDetail、reviewReplies保留原位置参数，book/chapter作为JSON快照传入；包装器独立捕获mainJs中的函数并await返回值，缺失函数明确返回不存在。字符串结果保留，其他非空结果序列化为JSON。

登录UI v2在async作用域调用并await `loginUi(state)` 或 `loginAction(action,state,form)`；状态和表单由JSON解析得到，book/chapter绑定为JSON快照。旧Java实体方法不能通过这些绑定调用。

MCP书源脚本通过V8 evaluate执行：此上下文的source将JSON书源元数据置于现代host API原型之上，保留受保护的API名称，sourceApi为同一对象。它与mainJs中仅为JSON数据的source/sourceApi有不同绑定合同。RSS及无书源MCP脚本仍在各自原作用域执行；书源执行错误不会触发旧引擎回退。旧JsSourceBook已删除，JsSourceEngine仅保留非书源消费者使用的public normalizeJsResult。
