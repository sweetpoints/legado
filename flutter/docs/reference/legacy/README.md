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

未带模式前缀的历史 HTML 规则添加 `@legacy:` 前缀；目录 `chapterName` 转成 `title`、`chapterUrl` 转成 `url`；`lastChapter` 转成 `latestChapterTitle`，其他规则字段按原名保留。空规则跳过。简单直接的已知 `java.*` 调用脚本允许导入但仍为 unverified；复杂脚本、组合规则、XPath、变量、URL 请求选项及应用能力产生 review issue。JSoup 简写、索引、ownText/textNodes 和链式提取按明确的兼容子集执行，详见 HTML Reference；不支持的 selector 仍可能在执行时失败。部分管线扩展产生 `legacy.pipeline_requires_review`。`mainJs`、`jsLib`、登录字段、封面解码、并发配置等仅保存原始信息，不自动转换。非文本书源也需要人工处理。

静态 `header` 字符串中的 JSON 对象或直接字符串映射导入新版 headers；动态 JS、非法 JSON 或非字符串键值产生 `legacy.dynamic_header`。读取 enabledCookieJar 且值不是 true 时产生 `legacy.cookie_policy_requires_review`，不会悄悄改成自动 Cookie 策略。全局请求头非法 token/CRLF 仍可能由新版校验直接拒绝。

没有 issue 时状态仍是 `unverified`；存在 issue 时为 `manualRequired`。两者都不表示行为对照已通过。

## `java.*` 宿主分派

完整参数、返回值与差异见 [`java.*` 宿主 API Reference](host.md)。目前覆盖部分 HTTP、变量、Base64、字符集、字节、hex 和摘要能力，通过同步桥运行。

这不是任意 Java 类互操作，也没有完整旧 API 覆盖。新版只使用 `source.*`；迁移器能转换的调用仍少于兼容层支持的调用。

## HTML 兼容

显式 `@legacy:` 使用 [JSoup 提取兼容子集](html.md)。其中 html 是 outer HTML，新版 CSS 的 html 是 inner HTML。支持语法与导入转换必须分别验证，不能因为提取器支持就认为所有旧书源已自动转换。

测试依据：`packages/source_legacy/test`。转换方法及限制见[迁移 Reference](../../migration/README.md)。
