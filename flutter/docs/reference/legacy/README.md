# 旧版兼容 Reference

`source_legacy` 保留历史书源格式及 `java.*` 的兼容入口；它不属于新版 API。当前兼容层是明确的子集，不能宣称所有旧书源可原样执行。

## 导入

`LegacySourceImporter.import(input)` 要求 `bookSourceUrl` 为非空String，原样保存为source.id，不trim或通过Uri归一化。HTTP(S) ID仍用作正常baseUrl；非HTTP(S) ID按下述锚点契约处理。原始输入通过 JSON 深拷贝保留在 `LegacyImport.original` 和候选的 `metadata.legacyOriginal` 中。

| 旧字段 | 新字段 |
|---|---|
| `bookSourceUrl` | 原样 `id`；HTTP(S) ID提供正常 `baseUrl`，非HTTP(S)需静态请求锚点 |
| `bookSourceName` | `name`，缺失时使用 URL 主机名 |
| `ruleSearch`、`searchUrl`、`bookList` | `stages.search` 的字段、URL、列表规则 |
| `ruleExplore`、`exploreUrl`、`bookList` | `stages.explore`；URL固定为 `{{exploreUrl}}`，取调用方已选择的发现入口 |
| `ruleBookInfo` | `stages.info`，URL 为 `{{bookUrl}}` |
| `ruleToc`、`chapterList` | `stages.toc`，URL 为 `{{tocUrl}}` |
| `ruleContent` | `stages.content`，URL 为 `{{chapterUrl}}` |
| `nextTocUrl`、`nextContentUrl` | 对应 stage 的 `nextPage` |

未带模式前缀的历史 HTML 规则添加 `@legacy:` 前缀；目录 `chapterName` 转成 `title`、`chapterUrl` 转成 `url`；`lastChapter` 转成 `latestChapterTitle`，其他规则字段按原名保留。空规则跳过。简单直接的已知 `java.*` 调用脚本允许导入但仍为 unverified。提取方法只放行 `@js: [return] java.getString/getStringList(单个字面量规则) [;]`；嵌套脚本、组合规则、模板、变量、额外参数以及 getElement/getElements 仍需人工处理；复杂脚本、组合规则、XPath、变量、不支持的URL请求选项及应用能力产生 review issue。JSoup 简写、索引、ownText/textNodes 和链式提取按明确的兼容子集执行，详见 HTML Reference；不支持的 selector 仍可能在执行时失败。部分管线扩展产生 `legacy.pipeline_requires_review`。contentBatch/callBackJs 产生 legacy.pipeline_requires_review，不静默当作普通提取字段；`mainJs`、`jsLib`、登录字段、封面解码、并发配置等仅保存原始信息，不自动转换。非文本书源也需要人工处理。

静态 `header` 字符串中的 JSON 对象或直接字符串映射导入新版 headers；动态 JS、非法 JSON 或非字符串键值产生 `legacy.dynamic_header`。读取 enabledCookieJar 且值不是 true 时产生 `legacy.cookie_policy_requires_review`，不会悄悄改成自动 Cookie 策略。全局请求头非法 token/CRLF 仍可能由新版校验直接拒绝。

## 字面量请求选项

旧阶段 URL 的 `URL,{JSON}` 形式可转换为 SourceStage 请求字段。分隔符为逗号后紧接 JSON 对象（允许空白），选项必须为严格字面量 JSON；这项导入能力不改变 java.ajax 等宿主对旧逗号 URL 的拒绝。

| 选项 | 导入行为 |
|---|---|
| method | 字符串 POST/HEAD 保留，其他字符串按旧语义归为GET；非字符串需人工处理 |
| headers | 扁平对象或其JSON字符串；覆盖合并全局静态头，生成完整阶段头映射 |
| body | 字符串原样，其他非null JSON值序列化；只在POST阶段保留 |
| charset | 任意非空值均需人工迁移；不写入新版 stage.charset |

POST没有明确非空 Content-Type 时：形似JSON对象、数组或XML的body按旧行为设置 application/json; charset=UTF-8；其他body设置表单Content-Type，固定字面量按UTF-8表单编码。完全符合安全字符/有效百分号转义的片段保留，不符合时编码；分隔 & 和首个 = 保留。templated form需要替换后编码，明确产生 legacy.request_options，不自动宣称转换等价。

旧 charset控制查询/表单百分号编码，与新版原始body字节/响应覆盖不同，因此即使UTF-8也保持人工审查。method/headers 中的模板、替换后可能改变默认 Content-Type 推断的 body、非UTF-8 Content-Type、不同大小写的content-type键、动态JS、未知选项（包括webview/retry/type）、非法JSON/headers和未知模板表达式均产生 legacy.request_options。所有旧URL（包括没有请求选项的URL）均只接受已知模板名 key/page/bookUrl/tocUrl/chapterUrl/baseUrl/exploreUrl；复杂表达式、未闭合模板及 `<1,2,3>` 等页码选择语法需人工迁移。候选执行仍要求输入提供对应值。

测试依据：packages/source_legacy/test/source_legacy_test.dart 中 literal URL options、fixed form、unsupported URL options 用例。导入器保留原始书源；存在issue的候选仍为manualRequired。

没有 issue 时状态仍是 `unverified`；存在 issue 时为 `manualRequired`。两者都不表示行为对照已通过。

## `java.*` 宿主分派

完整参数、返回值与差异见 [`java.*` 宿主 API Reference](host.md)。目前覆盖部分 HTTP、变量、Base64、字符集、字节、hex 和摘要能力，通过同步桥运行。

这不是任意 Java 类互操作，也没有完整旧 API 覆盖。新版只使用 `source.*`；迁移器能转换的调用仍少于兼容层支持的调用。

## HTML 兼容

显式 `@legacy:` 使用 [JSoup 提取兼容子集](html.md)。其中 html 是 outer HTML，新版 CSS 的 html 是 inner HTML。支持语法与导入转换必须分别验证，不能因为提取器支持就认为所有旧书源已自动转换。

测试依据：`packages/source_legacy/test`。转换方法及限制见[迁移 Reference](../../migration/README.md)。

## 发现入口输入

旧发现菜单只保存在 metadata.legacyOriginal，导入器不将整个菜单当请求URL。stages.explore 固定GET、无body和阶段headers，使用全局静态headers，URL为 `{{exploreUrl}}`。调用方提供 input.exploreUrl，即用户选中的具体URL；现代书源入口仍可使用 input.url。CLI execute/compare 旧发现阶段也必须通过 --variables 提供 exploreUrl。

菜单脚本、逗号请求选项和复杂模板均需人工迁移；逗号菜单选项产生 legacy.explore_options，不会套用到所有选中URL。@js:/<js>等脚本标记按大小写不敏感检测。选中入口自身的动态请求参数也必须显式迁移。

## 书源ID与请求基址

非HTTP(S)书源ID不等于无效旧源。导入器仅在searchUrl能提供确定的静态绝对HTTP(S)地址时，用其origin作为新版结构baseUrl锚点：可取逗号请求选项之前的URL，query中的已知模板允许；JS、host/path模板、userinfo、fragment或空白不能提供此锚点。候选增加 legacy.base_url_requires_review，并设置 metadata.legacyBaseUrlUnavailable=true，因此仍为manualRequired，不宣称已恢复旧源完整基址语义。无法确定锚点时报清晰的Cannot determine base FormatException；该失败不是书源ID无效的证明。

此标记下，所有规则阶段URL在模板替换后的最终值必须显式包含HTTP(S) scheme和host；相对路径及 `//host` 在发请求之前报 legacy_base_url_required，不借search origin猜测旧请求host。绝对URL可带fragment。正常HTTP来源及现代书源行为不变；此检查针对规则阶段请求，不扩展为脚本分支的全调用分析。提取链接和nextPage仍按实际response URL解析。

默认source_host对manual issue阻断；只在flag为true且所有issue均精确为code=legacy.base_url_requires_review、path=bookSourceUrl时，允许受阶段绝对URL guard保护的执行。候选仍为manualRequired，其他issue继续阻断，详见[宿主例外](../v1/android.md)。
