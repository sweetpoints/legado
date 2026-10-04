# `java.*` 宿主 API 兼容 Reference

新版书源使用 `source.*`。机器 API 清单见 [legacy-host.json](../../../spec/api/legacy-host.json)。本页列出旧脚本由 `LegacyScriptHost` 和 `legacyScriptPrelude` 提供的明确子集，不提供任意 JVM 类调用。

旧 `java` Proxy 通过 V8 同步桥取得返回值；脚本工作 isolate 等待，父 isolate 执行异步 Dart 宿主能力。直接调用 Dart 的 `LegacyScriptHost.call` 仍返回 Future，不能替代脚本同步桥。

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

不支持的参数数量报 `legacy.unsupported_overload`；不支持的方法报 `legacy.unsupported_api`。非法类型、非法 Base64/hex 等还会产生 ArgumentError/FormatException。网络错误、取消和超时由下层传播。未列出的文件、加解密、浏览器、Cookie、任意 Java 类、脚本库与应用控制接口不因这些方法存在而自动兼容。

测试依据：`packages/source_legacy/test`；真实 V8 同步桥需要 `source_v8` 测试或 Android 验收另行证明。

## 内容提取与元素 facade

`java.getString(rule)`、`getStringList(rule)` 默认使用当前 result；可指定内容及 isUrl。`getString(rule,boolean)` 的旧 unescape 重载明确不支持。`getElement(rule)`、`getElements(rule)` 当前仅支持单规则参数，由同步桥注入当前 result 和 baseUrl。裸规则和旧 @CSS/@text 等入口转换为 @legacy，其他模式保持其前缀。

空规则的旧约定：getString 返回空字符串、getStringList 返回 null、getElement 返回 null、getElements 返回空数组，与新版 getStringList 空数组不同。

HTML 字符串在旧 JS 环境转为有限元素 facade：text()、attr(name)、outerHtml()、select(selector)、selectFirst(selector)、toString()、toJSON()。元素列表提供 size()、get(index)、first()、last()、text()、attr(name)、select(selector)。JSON 值保留其对象形态。序列化转换不保留原始 DOM 对象身份；元素不是完整 Java JSoup 对象，修改 DOM、父子关系与任意方法不保证支持。

元素 facade 的 html() 明确报 `legacy.unsupported_element_api`。列表缺失 first/last 返回 null，attr 在空列表时为空字符串；get 越界返回 undefined。html 规则输出与 html() 方法不是同一能力。未知元素序列化报 `legacy.invalid_element_serialization`。
