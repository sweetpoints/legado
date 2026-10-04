# 宿主 API v1

新版命名空间是 `source`；`java.*` 属于旧版兼容层。所有宿主调用通过异步桥接返回 Promise，包括编码和变量操作。机器类型清单：[host-v1.json](../../../spec/api/host-v1.json)。

| API | 参数 | Promise 结果 |
|---|---|---|
| `source.net.get(url)` | URL 字符串，允许相对 URL | 响应正文字符串 |
| `source.net.request(options)` | 请求对象 | `{url,status,headers,body}` |
| `source.variables.get(key)` | 键 | 保存的值；不存在时为 null |
| `source.variables.put(key,value)` | 键、可传输值 | 保存并返回 value |
| `source.encoding.base64Encode(text)` | 文本 | UTF-8 Base64 |
| `source.encoding.base64Decode(text)` | Base64 文本 | UTF-8 字符串 |

`net.request` 的 options：`url` 为请求地址；`method` 默认 `GET`；`headers` 默认空对象；`body` 为可空字符串；`timeoutMs` 默认 `30000`。相对地址相对于书源 `baseUrl` 解析。响应 `url` 为最终 URL，`status` 为整数 HTTP 状态，`headers` 为字符串映射，`body` 为解码正文。

HTTP 状态码不会使上述宿主方法自动抛错；脚本自行检查状态。规则 stage 的请求则在状态码 >=400 时产生 `http_error`。

## 网络语义

默认 NetworkClient 只支持 HTTP(S)。最多跟随五次重定向；301/302 的 POST 和 303 改为 GET，307/308 保留方法。跨 origin 时移除显式 Authorization/Cookie。请求 body 用 UTF-8 编码；响应按 Content-Type charset 解码，未指定时为 UTF-8；GBK、GB2312、CP936 使用 GBK codec；GB18030 未内置。其他无法识别的编码报 `unsupported_charset`，接入方可通过 NetworkClient.charsetDecoder 提供扩展解码。

Cookie 在 NetworkClient 内存中按域、路径、Secure 和过期信息管理。默认 SourceEngine 为每个 source id 建立独立网络会话。关闭引擎或进程结束后不保证保留 Cookie。`browser.open` 返回的 Cookie 会自动导入当前书源 HTTP 会话，具体限制见下文；未提供所有 WebView/旧引擎会话的持续双向同步。注入一个共用 NetworkClient 会改变默认隔离边界，接入方需明确控制。

提供并发上限和最小请求启动间隔；没有自动重试、HTTP 缓存和浏览器登录。规则阶段可通过 nextPage 显式配置后续分页，详见规则 Reference。超时和取消会终止当前活动请求；JS 执行取消由运行时负责。

## 请求调度

默认每个书源的 NetworkClient 支持 `metadata.maxConcurrentRequests`（整数，默认4，至少1）和 `metadata.requestIntervalMs`（整数，默认0，至少0）。并发达到上限后进入队列；请求启动之间至少间隔配置的毫秒数，排队和间隔等待均可取消。底层重定向属于同一请求，不单独套用启动间隔。

调度配置在首次创建该 source id 的会话时读取。注入共用 NetworkClient 时采用该实例的构造配置。请求超时从实际网络执行开始计算，不包含排队和间隔等待；接入方若需要整个任务 deadline，应另行控制取消。

## 变量与编码

变量默认在 SourceEngine 实例内按 source id 保存，跨该书源多次任务可见，未持久化。并发任务会访问同一变量表，未提供事务或原子读改写保证。新版缺失变量为 null，旧版 `java.get` 为字符串空值，两者语义不同。

非法 Base64 或非法 UTF-8 可产生编码异常。编码接口只定义 UTF-8，不表示支持旧宿主所有字符集和字节重载。

## 脚本入口

`script` 非 null 时优先于 `stages` 执行。完整脚本定义如下函数：

| operation | 函数 |
|---|---|
| `search` | `search(input)` |
| `explore` | `explore(input)` |
| `info` | `getBookInfo(input)` |
| `toc` | `getChapters(input)` |
| `content` | `getContent(input)` |

函数可异步。返回一个对象、对象数组或 null；null 对应空结果，其他非对象项报 `invalid_result`。核心将代码放入 async IIFE 并等待函数返回，不会自动把缺失的函数换成规则 stage。

规则 `@js:` 把内容交给运行时；输入以 `result` 注入，HTML Element 会转换成 outer HTML。返回数组按数组元素使用，null 为空列表，其他值成为单元素列表。输入上下文还包含调用方 input、`sourceId`、`baseUrl`。

## Android 平台扩展

当 SourceEngine 注入 SourcePlatform 时，额外支持下列 Promise API：

| API | 参数 | 结果 |
|---|---|---|
| `source.storage.read(key)` | 字符串键 | 字符串或 null |
| `source.storage.write(key,value)` | 字符串键、可空字符串；null 删除 | null |
| `source.browser.open(url,title)` | URL、可选标题 | `{url,body,cookie?,cookies?}` 或 null |

storage 按 source id 隔离，具体由 Android 插件持久化；它与内存 variables 不同。browser 需要宿主 Activity 及验证适配，不是内嵌 V8 DOM。当前 Android 验证适配要求对应书源在 Room 中注册；source_host 的运行时自动补充当前任务 ID，以关联浏览器取消；作者不需要手动传第三参数。没有注入平台实现时，这些 API 明确失败。Cookie 的 Dart 插件方法存在，但没有单独列为 JS 宿主 API。

浏览器结果包含绝对 HTTP(S) `url` 时，若提供 `cookies: string[]`，各项按 Set-Cookie 解析并导入，保留已提供的 domain/path/secure/expiry 等属性；不属于结果主机的 domain 会忽略。否则若提供 `cookie: string`（Cookie 请求头形式），按 name=value 导入为 host-only、响应目录路径、HTTPS 时 Secure 的会话 Cookie。Cookie 请求头不包含原始属性，无法恢复原始 expiry、HttpOnly 或 path。导入在 browser.open Promise 完成之前执行，后续 net.get/request 即可使用匹配 Cookie。它不表示将所有旧 Cookie 存储迁入，也不提供 HTTP → WebView 的反向持续同步。

Dart 接入可直接调用 `NetworkClient.importCookies(uri, cookies)` 或 `importBrowserCookieHeader(uri, header)` 使用同一规则。

## Dart 接口

`SourceEngine.execute(source, operation, input: ..., cancellation: ...)` 返回 `Future<List<Map<String,Object?>>>`。操作只支持五种阶段，缺失阶段报 `missing_stage`，缺失 URL 输入报 `missing_input`。规则模式字段值为换行连接的文本；脚本模式保留可传输对象值。

可注入 `platform` 实现平台宿主扩展，通过 `hostAdapter` 包装宿主以接入旧 API。它们不改变新版格式版本。

`ScriptRuntime.evaluate(code, context, cancellation: ...)` 和 `close()` 定义运行时边界。`ScriptHost.call(method, arguments)` 定义宿主分派；method 为去掉 `source.` 后的名字，如 `net.get`。`ScriptContext.timeout` 默认 30 秒；最终是否按此中断取决于运行时实现。
