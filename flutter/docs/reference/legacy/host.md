# `java.*` 宿主 API 兼容 Reference

新版书源使用 `source.*`。机器 API 清单见 [legacy-host.json](../../../spec/api/legacy-host.json)。本页列出旧脚本由 `LegacyScriptHost` 和 `legacyScriptPrelude` 提供的明确子集，不提供任意 JVM 类调用。

旧 `java` Proxy 通过 V8 同步桥取得返回值；脚本工作 isolate 等待，父 isolate 执行异步 Dart 宿主能力。直接调用 Dart 的 `LegacyScriptHost.call` 仍返回 Future，不能替代脚本同步桥。

## Android 任务宿主

下表方法已通过 `LegacyScriptHost` → `TaskScriptHost` → Android `LegacyJavaHost` 接入生产。它们需要 Android App 宿主；独立 Dart/CLI 消费者必须注入对应宿主，不能把转发能力当成跨平台实现。脚本中的调用使用同步 V8 桥，直接返回值而非 Promise；Dart 分派仍是异步 Future。现代 `source.*` API 的异步合同独立，不由这些旧方法推导。

任务 ID 由调用方绑定，`TaskScriptHost` 添加受信回调标记；Android 按活动任务选择 source、tag 和协程上下文，进入方法前检查取消。脚本参数不能替换任务所有者。对象经 JSON 传输，`logType` 输出的是宿主接收到的对象类型，不提供原 Java 对象身份。

| 旧方法签名 | 返回值及语义 |
|---|---|
| `log(value)` | 返回原 JS 参数，同时向当前来源调试日志输出；value 可为 null 或可传输 JSON 值 |
| `logType(value)` | null；记录宿主值的类型，null 记录 `null` |
| `toast(value)`、`longToast(value)` | null；通过 Android UI 显示带来源 tag 的提示 |
| `timeFormat(timeMs)` | String；按 App 的 `AppConst.dateFormat` 格式化毫秒时间戳 |
| `timeFormatUTC(timeMs,format,offsetMs)` | 格式化字符串；format 使用 Java SimpleDateFormat，offset 是时区偏移毫秒，**不是小时** |
| `t2s(text)`、`s2t(text)` | String；分别调用 App 简繁转换，参数必须为 String |
| `getCookie(tag)`、`getCookie(tag,key)` | String；读取 Android CookieStore 的完整 Cookie 或指定项；key=null 等同完整 Cookie |
| `getWebViewUA()` | String；Android WebSettings 默认 User-Agent |
| `HMacHex(data,algorithm,key)` | String；使用旧 Hutool/JCA HMAC，输出十六进制；data/key 为 UTF-8 String |
| `HMacBase64(data,algorithm,key)` | String；同上，输出不换行的 Base64 |
| `androidId()` | String；AppConst.androidId；不是随机 UUID，也不是跨设备固定值 |
| `randomUUID()` | String；Java UUID.randomUUID().toString() |
| `toNumChapter(text)` | String 或 null；按 App 章节标题模式将匹配的中文数字转数字，未匹配保留原文，null 返回 null |

时间参数要求有符号整数毫秒：timeMs 在 Java Long 范围内，offsetMs 在 Java Int 范围内；非有限、小数和越界值拒绝。HMAC algorithm 交由现有 Android Hutool/JCA 支持并校验，例如 `HmacSHA256`，不保证所有提供者算法可用。CookieStore 是 Android 旧宿主存储，不等同于独立引擎 HTTP jar 的读取 API，也不由此承诺两者自动同步。

## 对称加密对象

`java.createSymmetricCrypto(transformation,key[,iv])` 返回有限 JS facade。transformation 为 String；key 为 String、整数数组或 null。String key 的 IV 只能为 String/null，按 UTF-8 转字节；数组/null key 的 IV 只能为数组/null。数组元素接受整数 -128..255，转 Java byte；返回字节为 signed -128..127。key=null 由原实现生成随机密钥，之后保留同一生成密钥；空/省略 IV 不成为显式参数。

| facade 方法 | 支持重载与返回值 |
|---|---|
| `encrypt(text[,charset])`、`encrypt(bytes)` | signed 字节数组 |
| `encryptHex(text[,charset])`、`encryptHex(bytes)` | 十六进制字符串 |
| `encryptBase64(text[,charset])`、`encryptBase64(bytes)` | Base64 字符串 |
| `decrypt(ciphertext)`、`decrypt(bytes)` | signed 字节数组；仅单参，ciphertext 按旧实现先识别 hex，否则 Base64 |
| `decryptStr(ciphertext[,charset])`、`decryptStr(bytes[,charset])` | 明文字符串 |
| `setIv(bytes)` | 返回当前 facade，可链式调用；只接受单个非null字节数组 |

字符集省略时 UTF-8，显式字符集由 Android Charset.forName 校验。encrypt 系列的字节数组重载不接受额外 charset。对象不支持 InputStream、任意 Hutool 方法或 Java 参数对象。

facade 保存 `{schemaVersion,ownerId,transformation,key,iv}` JSON 状态，每次调用重建 SymmetricCrypto，再恢复配置的 IV；没有按句柄增长的原生对象注册表。生产 ownerId 为受信任务的 sourceId；跨 source 的状态调用拒绝。新密钥只在 create 时生成；后续操作不重新生成密钥。只有显式 create IV 或 setIv 更新配置，提供者在一次操作中生成的 IV 不自动提升为下一次配置；这保留旧对象按配置重新初始化的行为，也不保证随机参数模式可解密或每次密文相同。状态包含密钥，不能作为安全加密存储或跨来源令牌使用。

`PBE*` transformation 在这个 JSON 状态对象入口明确拒绝为 `legacy.unsupported_crypto_parameter_snapshot`：尚未复刻 PBE 参数/盐等完整状态。其他 transformation 仍取决于 Android 提供者，非法密钥、IV、padding 或操作原样失败，不自动降级。

两个旧快捷入口也已接入：`aesBase64DecodeToString(text,key,transformation,iv)` 返回解密文本；`desEncodeToBase64String(data,key,transformation,iv)` 返回加密 Base64。两者恰好四个 String 参数，调用原 JsEncodeUtils 的对应方法，并不意味着其他 AES/DES/3DES、非对称、签名或流重载已覆盖。

实现与回归入口：[LegacyScriptHost](../../../packages/source_legacy/lib/src/legacy_host.dart)、[Dart 参数/分派测试](../../../packages/source_legacy/test/legacy_java_host_test.dart)、[Android 分派与密钥/IV 序列测试](../../../../app/src/test/java/io/legado/app/model/sourceEngine/LegacyJavaHostTest.kt)。这些测试分别验证调用链子合同，不替代真实书源或完整阶段验收。

## 网络与响应

| 方法 | 支持的参数 | 结果与限制 |
|---|---|---|
| `ajax(url[,timeoutMs])` | URL 或取首项的 URL 数组，可选正整数毫秒 | 正文字符串，跟随重定向 |
| `connect(url[,headersJson[,timeoutMs]])` | 字符串 URL；headers 为 JSON 字符串或 null | StrResponse 兼容对象，跟随重定向 |
| `ajaxAll(urls[,false])` | 字符串 URL 数组；可选参数只接受 false | 有序响应数组，并行任务仍受调度器限制 |
| `get(url,headers[,timeoutMs])` | 字符串 URL；headers 为对象、JSON 字符串或 null | JSoup Response 兼容对象，不跟随重定向 |
| `post(url,body,headers[,timeoutMs])` | 字符串 URL/body；headers 同 get | JSoup Response，不跟随重定向 |
| `head(url,headers[,timeoutMs])` | 参数同 get | JSoup Response，不跟随重定向 |

URL 中逗号形式的旧请求选项报 `legacy.url_options_require_migration`。显式 timeout 必须为正整数毫秒。`ajaxAll(...,true)` 报 `legacy.unsupported_skip_rate_limit`，不会绕过新引擎调度。

当前 headers 对象的键和值不可为 null，转换成字符串。connect 特别限制 headers 为 JSON 字符串或 null。JSoup get/post/head 在响应>=400时报 legacy.http_error；POST 未明确 Content-Type 时设为 application/x-www-form-urlencoded; charset=UTF-8。相对 URL 由当前规则上下文或源脚本基础地址解析；不表示已复刻旧 AnalyzeUrl 的动态 URL、请求选项与登录能力。

响应由 Dart JSON 传输，再由 JS prelude 建立方法：`body()`、`url()`、`code()`、`statusCode()`、`headers()`、`header(name)`、`hasHeader(name)`、`message()`、`statusMessage()`、`isSuccessful()`、`callTime()`、`toString()`。header 查找不区分大小写，缺失返回 null；headers() 返回带 get(name) 的头对象；isSuccessful 对 2xx 为 true。callTime 是包括宿主等待的耗时毫秒，message 未提供时为空字符串。StrResponse header 使用重复头的最后一个值，JSoup header 使用合并值。bodyAsBytes() 返回 signed 原始响应字节，multiHeaders() 返回多值头对象，cookies()/cookie(name)/hasCookie(name) 读取本响应 Cookie 映射。raw()/errorBody() 明确报 legacy.unsupported_response_api。

body 和 url 为可调用对象，支持字符串强制转换以兼容属性形式，但 `response.body === "text"` 不会等价于字符串属性；需要 `response.body()` 或明确字符串转换。这是已知差异，不能宣称完整 StrResponse/JSoup 类型兼容。

## 变量

`get(key)` 的单参数形式读取变量，缺失为空字符串；它与两/三参数 HTTP get 不同。`put(key,value)` 接受字符串键和值，保存并返回值。变量属于该 LegacyScriptHost 使用的变量表，不自动持久化到 Room。

## 编码与字节

| 方法 | 参数及结果 |
|---|---|
| `base64Encode(text[,flags])` | UTF-8 文本 → Base64；默认 flags=2 |
| `base64Decode(text[,flagsOrCharset])` | Base64 → 字符串，默认 UTF-8；null 返回 null，空字符串返回空 |
| `base64DecodeToByteArray(text[,flags])` | 返回 signed Java 字节数组；null/空白输入返回 null |
| `strToBytes(text[,charset])` | 默认 UTF-8，返回 signed 字节数组 |
| `bytesToStr(bytes[,charset])` | 输入整数数组，默认 UTF-8 |
| `hexDecodeToByteArray(hex)` | 十六进制 → signed 字节数组；奇数长度前补0 |
| `hexDecodeToString(hex)` | 十六进制 → UTF-8 文本 |
| `hexEncodeToString(text)` | UTF-8 文本 → 小写十六进制 |
| `encodeURI(text[,charset])` | Java 表单编码，空格为 `+`；不是 JS 原生 encodeURI |

Android Base64 flags 接受0..31：1去 padding，2不换行，4使用 CRLF，8使用 URL-safe 字母表，16为无关闭流标志（字符串操作无流可关闭）。换行形式每76字符换行并追加末尾换行。解码忽略空白；URL-safe 字符需 flag8。base64DecodeToByteArray 不接受 charset 字符串重载。

字节输入允许-128..255并转成无符号字节；输出使用-128..127。字符集名称忽略大小写、`-` 和 `_`：UTF-8，GBK/GB2312/CP936，ISO-8859-1/Latin1，ASCII/US-ASCII，UTF-16/UTF-16BE/UTF-16LE。UTF-16编码有BE BOM，解码按 BOM 判断，无 BOM 默认BE；奇数尾字节替换为 U+FFFD。UTF-8解码允许损坏字节替换。encodeURI 遇到不支持字符集返回空字符串，其他编解码报 `legacy.unsupported_charset`。

## 摘要

| 方法 | 行为 |
|---|---|
| `md5Encode(text)` | UTF-8 MD5，小写32位十六进制 |
| `md5Encode16(text)` | 32位MD5的字符8..23 |
| `digestHex(text,algorithm)` | UTF-8摘要，小写十六进制 |
| `digestBase64Str(text,algorithm)` | UTF-8摘要，Base64 |

algorithm 支持 MD5、SHA-1、SHA-224、SHA-256、SHA-384、SHA-512，忽略大小写和连字符。其他算法报 `legacy.unsupported_digest`。

## 错误与范围

不支持的参数数量报 `legacy.unsupported_overload`；不支持的方法报 `legacy.unsupported_api`。非法类型、非法 Base64/hex 等还会产生 ArgumentError/FormatException。网络错误、取消和超时由下层传播。未列出的文件、加解密重载、浏览器、Cookie 操作、任意 Java 类、脚本库与应用控制接口不因这些方法存在而自动兼容。

测试依据：`packages/source_legacy/test`；真实 V8 同步桥需要 `source_v8` 测试或 Android 验收另行证明。

## 内容提取与元素 facade

`java.getString(rule)`、`getStringList(rule)` 默认使用当前 result；可指定内容及 isUrl。`getString(rule,unescape:boolean)` 使用当前 result 并控制HTML4反解。getString 默认单次反解HTML4实体，getStringList不反解。isUrl=true 的标量先反解再解析URL；非空规则提取到空值时回退到当前 baseUrl，空规则仍返回空字符串。现代 parse 默认语义保持不变；HTML4实体细节见[工具API](../v1/utilities.md)。`getElement(rule)`、`getElements(rule)` 当前仅支持单规则参数，由同步桥注入当前 result 和 baseUrl。裸规则和旧 @CSS/@text 等入口转换为 @legacy，其他模式保持其前缀。

空规则的旧约定：getString 返回空字符串、getStringList 返回 null、getElement 返回 null、getElements 返回空数组，与新版 getStringList 空数组不同。

生产已接入 typed DOM：Android `LegacyDomHost` 将 Jsoup/JXNode 转为带节点表、类型、baseUri、属性、子节点和文档输出设置的 JSON 快照，V8 prelude 将其物化为只读方法 facade。共享 Elements 快照保留同一树中的节点索引，支持 text()/html()/attr(name)/select(selector)/toString()；列表另有 size()/get(index)/first()/last()/toArray()。toArray() 仅无参，返回数组副本；不会复制成可反射的 Java 数组。

节点提供 attr(name)、hasAttr(name)、text()、ownText()、html()、outerHtml()、data()、tagName()、id()、className()、select(selector)、selectFirst(selector)、getElementsByTag(name)、getElementsByClass(name)、getElementById(id)、parent()、children()、nextElementSibling()、previousElementSibling()。方法适用节点类型由 Jsoup 校验，例如 TextNode 没有 Element.text() 能力。未知方法、非法参数和无效快照明确失败；不支持 DOM 写操作、任意 Java 方法或进程级 Java 对象身份。旧字符串内容的有限 fallback facade 仍可存在，不能由 typed DOM 的 html() 支持推导字符串 fallback 全部重载也已实现。

原生规则提取另有 task-local 节点/值引用：仅当前活动任务可恢复，任务关闭或取消即清理；不能跨任务传递这些 token。typed JSON 快照与这种受限任务引用是两种传输形式，均不开放 Java 反射。

## 发现脚本 InfoMap

Android 发现菜单/按钮脚本的 infoMap 已绑定原 InfoMap 宿主，不再只是任意 JSON 草稿。键和值必须为 String。支持 get() 返回映射 view、get(key)、put(key,value)、remove(key)、set(map)、putAll(map)、containsKey/containsValue、size()/isEmpty()/clear()、keySet()/values()/entrySet()，以及属性读取/写入/删除。get(key) 缺失返回 null，属性形式缺失为 undefined；put/remove 返回原值或 null。entrySet() 返回 key/value JSON 条目，不是任意 Java Entry 对象。

save([timeSeconds[,need]]) 默认 0/true，time 必须为 Int 范围整数秒，need 必须为 Boolean；它只记录原 InfoMap 的 TTL 与 needSave，不立即持久化。saveNow() 按最后配置的秒 TTL 写入 CacheManager 后清除 needSave；needSave 属性及 getNeedSave()/setNeedSave(bool) 控制标记，sourceUrl/getSourceUrl() 只读。没有另设毫秒 saveTTL API，也不把 save() 改成无条件立即保存。任务回调绑定当前来源的 InfoMap。

## 原生规则宿主与变量层

Android 已接入原 AnalyzeRule 的 JSON RPC，由 Dart 编排请求、分页及阶段结果，JavaScript 仍由 V8 执行。info 的 ruleBookInfo.init 在字段提取之前执行，得到的新内容用于后续字段；null 结果报 legacy_init_empty。content 每页使用原正文提取/格式化，分页后合并；subContent 在分页后以第一页原内容与上下文求值，再执行原在线文本追加或音频 lyric/视频 danmaku 分支；普通文字来源不因此追加 subContent。最后对合并文本逐行 trim 后执行 replaceRegex，在线文本分支随后缩进，再求标题。可选 URL/media 处理失败沿原路径处理，取消仍传播；提取失败不会静默吞掉。

变量作用域携带 source/book/chapter 三层字符串映射及固定 target。chapter 读取顺序 chapter→book→source，book 为 book→source，source 只读本层；空值允许继续回退。put 写入当前 target，null 删除当前层键；脏键不会被后续旧 snapshot 覆盖。book/chapter 脚本快照携带对应 variable 数据，任务返回更新层供 App 回写，不把所有变量混成单一来源 map，也不承诺跨来源共享。

WebJS 是独立 Android 后台能力：仅外层声明式规则显式允许时执行 BackstageWebView，使用原 URL/HTML/headers/result、10秒超时和任务协程上下文；主线程调用拒绝。java 提取回调仍禁止递归 JS/WebJS，报 nested_script_requires_migration。任务取消和关闭传播并清理引用，不将 WebJS 作为独立 Dart/CLI 的默认能力。

Release 的反射注册入口需要既有 keep 规则：JsoupXpath AxisSelector/NodeTest/Function 实现和 Jsoup 类，以及 Flutter GeneratedPluginRegistrant.registerWith。保留这些原生注册路径不等于 Java 反射对脚本开放。

实现入口：[LegacyDomHost](../../../../app/src/main/java/io/legado/app/model/sourceEngine/LegacyDomHost.kt)、[DOM prelude](../../../packages/source_legacy/lib/src/legacy_dom.dart)、[原生规则宿主](../../../../app/src/main/java/io/legado/app/model/sourceEngine/LegacyRuleHost.kt)、[InfoMap 分派](../../../../app/src/main/java/io/legado/app/help/source/BookSourceExtensions.kt)。当前固定公开四源采样仅一项成功，其余为两项 HTTP/script 错误及一项 TLS 错误；新的最终采样尚未执行。局部 checkpoint 或方法接线不能标记整源 fully compatible/verified，也不能写成完整验收已通过。
