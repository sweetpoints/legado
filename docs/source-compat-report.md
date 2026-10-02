# 书源兼容性扫描报告（跨平台可行性判据）

> 由 `tools/source-compat-scan.py` 生成。分类逻辑对齐 `AnalyzeRule.splitSourceRule` / `SourceRule.init`。

## 1. 数据来源

| 文件 | 书源数 |
| --- | --- |
| `2a1f129b.json` | 1554 |
| `3bb7b751.json` | 2117 |
| `b778fe6b.json` | 3907 |
| `e29e19ee.json` | 939 |
| `xiu2-shuyuan.json` | 22 |

- 原始条数：**8539**
- 按 `bookSourceUrl` 去重后：**3310**（重复 5227）

## 2. 规则类型分布（片段级）

| 规则类型 | 片段数 | 占比 | iOS 端影响 |
| --- | --- | --- | --- |
| CSS | 65561 | 41.0% | ✅ 可移植（Ksoup） |
| 模板变量 | 50779 | 31.8% | ✅ 仅取值，**不需要** JS 引擎 |
| XPath | 24069 | 15.1% | ❌ **JsoupXpath 无法移植，需改写或降级** |
| 模板表达式(JS) | 9157 | 5.7% | ⚠️ 需 JS 引擎（可能含宿主调用） |
| JSONPath | 5734 | 3.6% | ⚠️ 需换 JSONPath 实现（语义待核对） |
| JS | 4501 | 2.8% | ⚠️ 需换 JS 引擎 + **重写宿主绑定层** |
| Regex(字段) | 1256 | 0.8% | ✅ 正则，可移植 |
| CSS(转义) | 9 | 0.0% | ✅ 无解析 |
| **合计** | **159810** | | |

## 3. 书源级影响面

| 指标 | 书源数 | 占比 |
| --- | --- | --- |
| 总数 | 3310 | 100% |
| 含 XPath 规则 | 1332 | 40.2% |
| 含 JS 代码（`@js`/`<js>`/`@webjs`/复杂 `{}`） | 1746 | 52.7% |
| 含**硬 Java 互操作** JS | 41 | 1.2% |

### 3.1 可移植性分层

| 层级 | 判定条件 | 书源数 | 占比 | 说明 |
| --- | --- | --- | --- | --- |
| **A** | 无 XPath、无硬互操作、**无 JS** | 1074 | 32.4% | 纯 CSS/JSONPath，换解析库即可 |
| **B** | 无 XPath、无硬互操作，但有 JS | 869 | 26.3% | 需 JS 引擎 + 重建宿主层，但语法无阻塞 |
| **C** | 含 XPath（可能同时有 JS） | 1332 | 40.2% | 需把 XPath 改写为 CSS 或自研 XPath 子集 |
| **D** | 含硬 Java 互操作 | 41 | 1.2% | 对应 JS 段在 iOS 上**无法直接运行** |

> A/B/C/D 会重叠（一个书源可同时命中多项），因此占比之和大于 100%。

**结论**：`A + B` = **1943（58.7%）** 的书源**不含 XPath**，其失效风险只来自 JS 引擎与宿主层；剩下 **1332（40.2%）** 含 XPath，是 JsoupXpath 缺失的直接受害面。

## 4. JS 可移植性细分

- JS 代码块总数（`@js` / `<js>` / `@webjs` / 复杂 `{}`）：**13658**
- 其中含硬 Java 互操作：**128**（0.9%）

> 关键区分：`java.ajax(...)` 这类**宿主方法调用**与 `Packages.java.util.Base64` 这类**直接引用 Java 类**，跨平台代价完全不同。前者只需在目标平台重建等价方法，后者在 QuickJS / JavaScriptCore 中**没有对应语法**。

### 4.1 硬阻塞语法（在 QuickJS / JavaScriptCore 中无对应物）

| 语法 | 出现次数 |
| --- | --- |
| `Packages.*` | 124 |
| `JavaImporter(...)` | 112 |
| `importPackage(...)` | 112 |
| `javax.*` | 107 |
| `java.<包>.<类> 直接引用` | 8 |
| `importClass(...)` | 4 |

### 4.2 需要重建的宿主 API（`java.*` 调用，按频次）

> 这些**不是** Rhino 特有语法，只是调用绑定到 JS 的 Kotlin 对象。理论上可移植，但需要在目标平台重新实现同等语义的方法——下表即为工作量清单。

| 方法 | 调用次数 |
| --- | --- |
| `java.md5Encode(...)` | 1016 |
| `java.ajax(...)` | 776 |
| `java.put(...)` | 773 |
| `java.get(...)` | 746 |
| `java.getString(...)` | 570 |
| `java.log(...)` | 320 |
| `java.timeFormat(...)` | 227 |
| `java.base64DecodeToByteArray(...)` | 165 |
| `java.getElements(...)` | 73 |
| `java.encodeURI(...)` | 73 |
| `java.toast(...)` | 62 |
| `java.base64Encode(...)` | 62 |
| `java.base64Decode(...)` | 60 |
| `java.t2s(...)` | 57 |
| `java.setContent(...)` | 54 |
| `java.aesBase64DecodeToString(...)` | 35 |
| `java.getElement(...)` | 34 |
| `java.getStringList(...)` | 34 |
| `java.longToast(...)` | 33 |
| `java.startBrowserAwait(...)` | 25 |
| `java.toNumChapter(...)` | 24 |
| `java.startBrowser(...)` | 20 |
| `java.post(...)` | 18 |
| `java.HMacHex(...)` | 18 |
| `java.timeFormatUTC(...)` | 16 |
| `java.createSymmetricCrypto(...)` | 11 |
| `java.getCookie(...)` | 11 |
| `java.hexDecodeToString(...)` | 10 |
| `java.desEncodeToBase64String(...)` | 8 |
| `java.head(...)` | 7 |
| `java.getVerificationCode(...)` | 7 |
| `java.connect(...)` | 6 |
| `java.getWebViewUA(...)` | 6 |
| `java.refreshTocUrl(...)` | 5 |
| `java.ajaxAll(...)` | 4 |
| `java.getUserAgent(...)` | 4 |
| `java.webView(...)` | 4 |
| `java.s2t(...)` | 3 |
| `java.randomUUID(...)` | 2 |
| `java.androidId(...)` | 2 |
| **不同方法数** | **51** |

### 4.3 被引用的宿主绑定对象

| 绑定 | 引用次数 |
| --- | --- |
| `page` | 5516 |
| `result` | 2788 |
| `key` | 1118 |
| `source` | 823 |
| `baseUrl` | 710 |
| `book` | 600 |
| `cookie` | 322 |
| `title` | 291 |
| `src` | 253 |
| `chapter` | 196 |
| `chapters` | 23 |
| `cache` | 14 |
| `nextChapterUrl` | 7 |
| `infoMap` | 3 |

## 5. 字段级分布（前 25）

| 字段 | CSS | XPath | JSONPath | JS | WebJs |
| --- | --- | --- | --- | --- | --- |
| `exploreUrl` | 929 | 0 | 0 | 106 | 0 |
| `searchUrl` | 1636 | 983 | 0 | 164 | 0 |
| `ruleBookInfo.intro` | 2243 | 47 | 134 | 151 | 0 |
| `ruleSearch.bookUrl` | 2525 | 61 | 127 | 229 | 0 |
| `ruleToc.chapterUrl` | 2599 | 40 | 71 | 224 | 0 |
| `ruleContent.content` | 2321 | 9 | 258 | 505 | 0 |
| `ruleToc.chapterList` | 2354 | 49 | 317 | 217 | 0 |
| `ruleToc.chapterName` | 2493 | 4 | 286 | 107 | 0 |
| `ruleSearch.bookList` | 2305 | 19 | 425 | 114 | 0 |
| `ruleSearch.name` | 2366 | 7 | 389 | 28 | 0 |
| `ruleSearch.author` | 2177 | 7 | 378 | 24 | 0 |
| `ruleSearch.coverUrl` | 1814 | 6 | 304 | 347 | 0 |
| `ruleSearch.kind` | 1836 | 0 | 218 | 37 | 0 |
| `ruleBookInfo.kind` | 1980 | 36 | 81 | 41 | 0 |
| `ruleBookInfo.lastChapter` | 1941 | 34 | 162 | 102 | 0 |
| `ruleBookInfo.name` | 1990 | 48 | 191 | 21 | 0 |
| `ruleBookInfo.author` | 1975 | 45 | 184 | 17 | 0 |
| `ruleBookInfo.coverUrl` | 1901 | 41 | 152 | 31 | 0 |
| `ruleSearch.lastChapter` | 1444 | 3 | 217 | 129 | 0 |
| `exploreUrl[2].url` | 206 | 666 | 0 | 5 | 0 |
| `exploreUrl[3].url` | 207 | 660 | 0 | 4 | 0 |
| `exploreUrl[1].url` | 197 | 663 | 0 | 5 | 0 |
| `exploreUrl[4].url` | 202 | 629 | 0 | 5 | 0 |
| `exploreUrl[6].url` | 202 | 618 | 0 | 5 | 0 |
| `exploreUrl[5].url` | 199 | 620 | 0 | 5 | 0 |

## 6. 含硬互操作的书源（样例，最多 400）

| 书源 | 命中语法 |
| --- | --- |
| 七猫小说 | JavaImporter(...), Packages.*, importPackage(...), javax.* |
| ♛ 连城读书 #渊呀1107 | JavaImporter(...), Packages.*, importPackage(...), javax.* |
| ㊣♛书耽▪︎API #渊呀 | JavaImporter(...), Packages.*, importPackage(...), javax.* |
| 笔趣阁a | Packages.*, java.<包>.<类> 直接引用 |
| ▪︎✾柚免费耽美 | JavaImporter(...), Packages.*, importPackage(...), javax.* |
| 🎉啃书小说网 | importClass(...) |
| 🔖读书阁① | JavaImporter(...), Packages.*, importPackage(...), javax.* |
| 晋江① | JavaImporter(...), Packages.*, importPackage(...), javax.* |
| 🎉 七猫小说 | JavaImporter(...), Packages.*, importPackage(...), javax.* |
| 🎉 西瓜小说 | JavaImporter(...), Packages.*, importPackage(...), javax.* |
| 笔书阁❶ | JavaImporter(...), Packages.*, importPackage(...), javax.* |
| 笔书阁❷ | JavaImporter(...), Packages.*, importPackage(...), javax.* |
| 如文网 | importClass(...) |
| 七猫API | JavaImporter(...), Packages.*, importPackage(...), javax.* |
| 📚 梧桐中文 | JavaImporter(...), Packages.*, importPackage(...), javax.* |
| SF轻小说 | importClass(...) |
| 文趣阁 | JavaImporter(...), Packages.*, importPackage(...), javax.* |
| 连城读书 | JavaImporter(...), Packages.*, importPackage(...), javax.* |
| 柚免费耽美 | JavaImporter(...), Packages.*, importPackage(...), javax.* |
| 🔞 书耽 | JavaImporter(...), Packages.*, importPackage(...), javax.* |
| ♛ 连城读书▪︎API #渊呀 | JavaImporter(...), Packages.*, importPackage(...), javax.* |
| 西瓜小说 | JavaImporter(...), Packages.*, importPackage(...) |
| 七猫小说2 | JavaImporter(...), Packages.*, importPackage(...), javax.* |
| 🔰笔趣阁.pysmei | JavaImporter(...), Packages.*, importPackage(...), javax.* |
| ▪︎✾柚免费耽美▪︎API #渊呀 | JavaImporter(...), Packages.*, importPackage(...), javax.* |
| ㊕ ㊣✾柚免费耽美▪︎API #一程 | JavaImporter(...), Packages.*, importPackage(...), javax.* |
| 书耽▪︎API  | JavaImporter(...), Packages.*, importPackage(...), javax.* |
| 顶点小说36 | Packages.*, java.<包>.<类> 直接引用 |
| 搜书2 | JavaImporter(...), Packages.*, importPackage(...), javax.* |
| 晋江自用 | JavaImporter(...), Packages.*, importPackage(...), javax.* |
| 🎨追追漫画₁🅰 | JavaImporter(...), Packages.*, importPackage(...) |
| 📖 SF轻小说 💰 | importClass(...) |
| 酷匠阅读 | Packages.* |
| ️顶点小说️ | Packages.*, java.<包>.<类> 直接引用 |
| 晋江[言情] | JavaImporter(...), Packages.*, importPackage(...), javax.* |
| 💵 酷匠网 | JavaImporter(...), Packages.*, importPackage(...) |
| 📥当书网 | Packages.*, java.<包>.<类> 直接引用 |
| 太极小说（优+） | Packages.* |
| 万通蜡笔（优） | Packages.*, java.<包>.<类> 直接引用 |
| 凤凰网书城 | Packages.* |
| 🎃酷匠网🎃 | Packages.* |

（共 41 个，此处仅列前 41 个）

## 7. 局限性与假设（务必阅读）

1. **`isJSON` 无法静态判定**。App 在 `setContent()` 里根据**实际响应内容**是否可解析为 JSON 来决定 `isJSON`，而它的判断优先级**高于**「`/` 开头即 XPath」。本报告默认按 `isJSON=False`（即响应为 HTML）统计——这是对 XPath 暴露量的**保守上界**。若某书源实际走 JSON API，其无前缀规则会被判为 JSONPath 而非 CSS/XPath。
2. **未执行任何规则**。本工具是纯静态分析，不联网、不验证选择器是否真的能取到内容。
3. **XPath 计数含误报可能**：`/` 开头也可能是被误写的 CSS，反之 CSS 里也可能出现 `/`。
4. **硬互操作清单是正则匹配**，可能漏掉动态构造类名的写法（如 `Packages[cls]`）。
5. 书源的 `enabled` 状态、权重、实际可用性均未参与统计——本报告统计的是**规则语法形态**。
