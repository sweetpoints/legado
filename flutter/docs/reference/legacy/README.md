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

未带模式前缀的历史 HTML 规则添加 `@legacy:` 前缀；目录 `chapterName` 转成 `title`、`chapterUrl` 转成 `url`；`lastChapter` 转成 `latestChapterTitle`，其他规则字段按原名保留。空规则跳过。简单直接的已知 `java.*` 调用脚本允许导入但仍为 unverified。提取方法只放行 `@js: [return] java.getString/getStringList(单个字面量规则) [;]`；嵌套脚本、组合规则、模板、变量、额外参数以及 getElement/getElements 仍需人工处理；复杂脚本、组合规则、XPath、变量、不支持的URL请求选项及应用能力产生 review issue。JSoup 简写、索引、ownText/textNodes 和链式提取按明确的兼容子集执行，详见 HTML Reference；不支持的 selector 仍可能在执行时失败。部分管线扩展产生 `legacy.pipeline_requires_review`。contentBatch/callBackJs 产生 legacy.pipeline_requires_review，不静默当作普通提取字段；非空mainJs按下文V8 wrapper执行；jsLib、登录字段、封面解码、并发配置等仍需明确迁移。非文本书源也需要人工处理。

静态 `header` 字符串中的 JSON 对象或直接字符串映射导入新版 headers；动态 JS、非法 JSON 或非字符串键值产生 `legacy.dynamic_header`。读取 enabledCookieJar 且值不是 true 时产生 `legacy.cookie_policy_requires_review`，不会悄悄改成自动 Cookie 策略。全局请求头非法 token/CRLF 仍可能由新版校验直接拒绝。

## 字面量请求选项

旧阶段 URL 的 `URL,{JSON}` 形式可转换为 SourceStage 请求字段。分隔符为逗号后紧接 JSON 对象（允许空白），选项必须为严格字面量 JSON；这项导入能力不改变 java.ajax 等宿主对旧逗号 URL 的拒绝。

| 选项 | 导入行为 |
|---|---|
| method | 字符串 POST/HEAD 保留，其他字符串按旧语义归为GET；非字符串需人工处理 |
| headers | 扁平对象或其JSON字符串；覆盖合并全局静态头，生成完整阶段头映射 |
| body | 字符串原样，其他非null JSON值序列化；只在POST阶段保留 |
| charset | 任意非空值均需人工迁移；不写入新版 stage.charset |

POST没有明确非空 Content-Type 时：形似JSON对象或数组的body按旧行为设置 application/json; charset=UTF-8；其他body设置原 application/x-www-form-urlencoded。无charset覆盖时，固定表单不预编码，保留原body并设置bodyEncoding=legacyFormUtf8，在替换输入后编码。模板表单要求以固定非空ASCII参数名和等号开头（`^[A-Za-z0-9*._-]+=`）且只含已知输入占位符；纯 `{{key}}`、替换后可能改变body类型、非空非blank但编码为空的表单（如全&）仍需人工处理。运行时模板值限制见[阶段请求体](../v1/rules.md)。

旧 charset控制查询/表单百分号编码，与新版原始body字节/响应覆盖不同，因此即使UTF-8也保持人工审查。method/headers 中的模板、替换后可能改变默认 Content-Type 推断的 body、非UTF-8 Content-Type、不同大小写的content-type键、动态JS、未知选项（包括webview/retry/type）、非法JSON/headers和未知模板表达式均产生 legacy.request_options。所有旧URL（包括没有请求选项的URL）均只接受已知模板名 key/page/bookUrl/tocUrl/chapterUrl/baseUrl/exploreUrl；有限page加减canonical整数及URL `<a,b,...>` 由legacyPageTemplates支持，其他复杂表达式和未闭合模板需人工迁移。候选执行仍要求输入提供对应值。

测试依据：packages/source_legacy/test/source_legacy_test.dart 中 literal URL options、fixed form、unsupported URL options 用例。导入器保留原始书源；存在issue的候选仍为manualRequired。

没有 issue 时状态仍是 `unverified`；存在 issue 时为 `manualRequired`。两者都不表示行为对照已通过。

## `java.*` 宿主分派

完整参数、返回值与差异见 [`java.*` 宿主 API Reference](host.md)。目前覆盖部分 HTTP、变量、Base64、字符集、字节、hex 和摘要能力，通过同步桥运行。

这不是任意 Java 类互操作，也没有完整旧 API 覆盖。新版只使用 `source.*`；迁移器能转换的调用仍少于兼容层支持的调用。

## HTML 兼容

显式 `@legacy:` 使用 [JSoup 提取兼容子集](html.md)。其中 html 是 outer HTML，新版 CSS 的 html 是 inner HTML。支持语法与导入转换必须分别验证，不能因为提取器支持就认为所有旧书源已自动转换。

测试依据：`packages/source_legacy/test`。转换方法及限制见[迁移 Reference](../../migration/README.md)。

## 发现入口输入

原发现菜单保存在metadata.legacyOriginal；静态JSON数组 `[{"title":"入口","url":"https://example.invalid/"}]` 或 `title::URL` 行（换行/&&分隔）另解析为metadata.legacyExploreItems，条目统一为title/url字符串及可选style字段；style可为null或JSON对象，保持深拷贝、嵌套内容及显式null键。style只提供presentation数据，不执行或参与URL规则检查。未知type/action等键、primitive/list类型style仍产生legacy.explore_menu_requires_review。普通单URL保留空title入口。每个URL独立检查，标题不按脚本标记误判；动态菜单、非法形状及未知或动态每项请求options仍需人工处理，不把整个菜单当URL/options。导入阶段标记legacyRequestInput=exploreUrl，静态菜单URL保留原请求options；运行前adaptLegacyRequest按当前选中URL解析GET/POST/HEAD、literal body/headers及已知模板，阶段请求头覆盖合并全局头。调用方提供input.exploreUrl，即用户选中的具体URL；现代入口仍可使用input.url。CLI execute/compare旧发现阶段也必须通过--variables提供exploreUrl。

菜单脚本、未知options和超出受限语法的复杂模板需人工迁移；支持的每分类字面量options只用于当前选中入口，不会套用到所有入口。@js:/<js>等脚本标记按大小写不敏感检测。选中入口自身的动态请求参数也必须显式迁移。

## 书源ID与请求基址

非HTTP(S)书源ID不等于无效旧源。导入器仅在searchUrl能提供确定的静态绝对HTTP(S)地址时，用其origin作为新版结构baseUrl锚点：可取逗号请求选项之前的URL，query中的已知模板允许；JS、host/path模板、userinfo、fragment或空白不能提供此锚点。候选增加 legacy.base_url_requires_review，并设置 metadata.legacyBaseUrlUnavailable=true，因此仍为manualRequired，不宣称已恢复旧源完整基址语义。无法确定锚点时报清晰的Cannot determine base FormatException；该失败不是书源ID无效的证明。

此标记下，所有规则阶段URL在模板替换后的最终值必须显式包含HTTP(S) scheme和host；相对路径及 `//host` 在发请求之前报 legacy_base_url_required，不借search origin猜测旧请求host。绝对URL可带fragment。正常HTTP来源及现代书源行为不变；此检查针对规则阶段请求，不扩展为脚本分支的全调用分析。提取链接和nextPage仍按实际response URL解析。

默认source_host对manual issue阻断；只在flag为true且所有issue均精确为code=legacy.base_url_requires_review、path=bookSourceUrl时，允许受阶段绝对URL guard保护的执行。候选仍为manualRequired，其他issue继续阻断，详见[宿主例外](../v1/android.md)。

旧POST请求选项的字符串body含模板时，无论表单、JSON或显式Content-Type，均设置bodyTemplateMode=legacyJsonString，替换前按新版规则的保护条件拒绝会改变旧JSON字符串解析的输入。body为object/array且含模板时，因外层JSON替换层不同产生legacy.request_options，需人工迁移；固定object/array仍支持序列化，固定无模板body保持raw模式。

普通HTTP IPv6发现URL可以保留；title::URL行若包含额外 `::`（例如带IPv6的分类URL），metadata保留完整URL，但产生legacy.explore_menu_requires_review并保持manualRequired，因为旧split全部 `::` 的结果可能截断，不宣称自动等价。

旧请求options的body含任意 `<` 或 `>` 时需人工迁移，包括XML。旧引擎会先对整份options展开角括号，不能把XML自动兼容成普通raw body；此前XML自动兼容说明已撤回。现代raw XML请求不受此旧来源限制。受限page算术/URL choice及适配器注册契约见[规则Reference](../v1/rules.md)。

URL choice必须为静态分支：其内部任意 `{{...}}`（包括page算术）、嵌套/未闭合角括号需人工处理。算术可独立出现在choice之外。旧URL/body模板动态插入角括号由运行时保护拒绝，不能借此自动模拟旧options全字符串替换顺序。

导入器对包含已知input模板的普通旧URL也设置legacyPageTemplates=true，确保动态角括号输入无法绕过保护；并不因此允许任意JavaScript表达式。静态search锚点的query可包含有限page算术，host/path模板仍不能提供确定锚点。单项 `<>` 也不属于支持的URL choice。

## 旧mainJs的V8 wrapper

非空字符串mainJs导入SourceDefinition.script，标记legacyMainJs=true并保留legacyOriginal；迁移器保持legacy=true兼容模式，不自动转译整个脚本或宣称verified。代码在独立Function词法工厂捕获入口，按旧位置参数调用：search(key,page默认1)、explore(exploreUrl,page)、getBookInfo(book)、getChapters(book)、getContent(chapter,book,nextChapterUrl)。工厂提供key/page/url/book/chapter/nextChapterUrl/source/sourceApi；source/sourceApi为原始JSON DTO数据，没有Java DTO方法。book取input.book或flat input副本；chapter取input.chapter或仅包含url/title的chapterUrl/chapterTitle映射，不猜index或volume。

search/explore/toc允许JSON字符串解析后必须为数组；info必须为对象，缺失函数或null/undefined/空字符串时返回输入book；content字符串原样、null为空字符串，其他值JSON.stringify。java仍仅提供旧宿主白名单，未知接口在运行时明确unsupported_api。jsLib及未实现能力继续manual；每次求值独立context，不提供跨阶段JS全局内存。默认Dart执行并不意味着所有旧mainJs均可执行。

内置JS模板和App帮助示例按当前V8更新：不导入org/Packages或任意Java类；source/sourceApi为JSON snapshot，不能调用Room/登录信息/登录头对象方法。book/chapter及java.ajax返回文本为原生JS String，length是属性、严格相等与空字符串真值均遵JS，不提供Java String包装重载。jsLib/CryptoJS等库仍需迁移，已覆盖的摘要接口为java.md5Encode/java.digestHex。模板保留五阶段、文件源downloadUrls、发现/登录配置及评论位置参数，但保留配置形状不代表所有平台宿主能力已经支持。
