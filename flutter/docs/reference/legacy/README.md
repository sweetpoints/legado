# 旧版兼容 Reference

`source_legacy` 保留历史书源格式及 `java.*` 的兼容入口；它不属于新版 API。当前兼容层是明确的子集，不能宣称所有旧书源可原样执行。

## 导入

`LegacySourceImporter.import(input)` 要求 `bookSourceUrl` 为绝对 HTTP(S) URL。原始输入通过 JSON 深拷贝保留在 `LegacyImport.original` 和候选的 `metadata.legacyOriginal` 中。

| 旧字段 | 新字段 |
|---|---|
| `bookSourceUrl` | `id`、`baseUrl` |
| `bookSourceName` | `name`，缺失时使用 URL 主机名 |
| `ruleSearch`、`searchUrl`、`bookList` | `stages.search` 的字段、URL、列表规则 |
| `ruleExplore`、`exploreUrl`、`bookList` | `stages.explore` |
| `ruleBookInfo` | `stages.info`，URL 为 `{{bookUrl}}` |
| `ruleToc`、`chapterList` | `stages.toc`，URL 为 `{{tocUrl}}` |
| `ruleContent` | `stages.content`，URL 为 `{{chapterUrl}}` |
| `nextTocUrl`、`nextContentUrl` | 对应 stage 的 `nextPage` |

普通裸 CSS 规则添加 `@css:` 前缀；目录 `chapterName` 转成 `title`、`chapterUrl` 转成 `url`，其他规则字段按原名保留。空规则跳过。简单直接的已知 `java.*` 调用脚本允许导入但仍为 unverified；复杂脚本、组合规则、XPath、变量、URL 请求选项及应用能力产生 review issue。JSoup `class./tag./id.` 简写、数字索引、ownText/textNodes、链式提取等产生 `legacy.jsoup_dsl`，不得当作普通 CSS 宣称兼容。部分管线扩展产生 `legacy.pipeline_requires_review`。`mainJs`、`jsLib`、`header`、登录字段、封面解码、并发配置等仅保存原始信息，不自动转换。非文本书源也需要人工处理。

没有 issue 时状态仍是 `unverified`；存在 issue 时为 `manualRequired`。两者都不表示行为对照已通过。

## `java.*` 宿主分派

`LegacyScriptHost.call(method, arguments)` 在 Dart 侧为异步。使用 `legacyScriptPrelude` 注入的 `java` Proxy 调用 V8 的同步桥；脚本工作 isolate 等待，父 isolate 处理异步宿主能力。以下行为已实现：

| 方法 | 参数 | 返回及语义 |
|---|---|---|
| `java.base64Encode` | 一个字符串 | UTF-8 字节的 Base64 字符串 |
| `java.base64Decode` | 一个 Base64 字符串 | UTF-8 解码字符串；非法编码抛出异常 |
| `java.md5Encode` | 一个字符串 | UTF-8 字节的 MD5 小写十六进制 |
| `java.ajax` | 一个不带旧请求选项的 URL 字符串 | 委托 `net.get`，同步返回正文 |
| `java.get` | 字符串键 | 已存字符串；缺失时返回空字符串 |
| `java.put` | 字符串键、字符串值 | 存储并返回值 |

变量在该 host 实例中保存；不意味着已实现旧引擎所有持久化及作用域行为。必须明确选择带 `legacyScriptPrelude` 的运行时和 `LegacyScriptHost`；新版运行时默认不注入 `java`。

`java.getRequest`、`java.post`、`java.connect` 明确拒绝，错误包含 `legacy.sync_network_unsupported`。未列出的参数重载报 `legacy.unsupported_overload`，例如 `java.get(url, headers)`、Base64 字节/字符集参数。其他 `java.*` 方法报 `legacy.unsupported_api`。非 `java.*` 调用交给新版宿主。

## 兼容边界

上述少量接口可通过同步桥保留返回语义；它不是全量旧 API 兼容。通过 Dart 直接调用分派得到的 Future 也不能替代同步桥。任意 Java 类、Rhino 互操作、网页登录、验证码、持久化 Cookie 和旧脚本库均未因导入成功而获得兼容。

测试依据：`packages/source_legacy/test/source_legacy_test.dart`。转换方法及限制见[迁移 Reference](../../migration/README.md)。
