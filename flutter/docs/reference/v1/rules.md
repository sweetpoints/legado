# 规则语言 v1

机器语法：[rules-v1.ebnf](../../../spec/grammar/rules-v1.ebnf)。当前解析采用分隔扫描；不提供完整语言 AST，也不自动验证引号及括号是否平衡。

## 模式

| 形式 | 输入与结果 |
|---|---|
| `@css:selector` 或裸 CSS | HTML → Element 列表 |
| `@css:selector@text` | 元素文本 |
| `@css:selector@html` | 元素 inner HTML |
| `@css:selector@attribute` | 指定属性，缺失属性跳过 |
| `@json:path` 或以 `$` 开始 | JSON 字符串或对象 → 路径结果 |
| `@xpath:expression` | HTML → 查询节点的文本 |
| `@regex:pattern` | 文本 → 所有匹配的第一捕获组；无捕获组时为全匹配 |
| `@js:script` | 交给运行时，注入当前输入为 `result` |
| `@legacy:rule` | 使用明确的 [JSoup 兼容子集](../legacy/html.md)，与新版 CSS 语义分开 |

模式前缀不区分大小写；CSS 输出名字和属性名保持大小写。CSS 输出分隔符为最后一个顶层、未转义的 `@`；引号、属性方括号和函数圆括号内的 `@` 以及转义的 `\@` 不参与分割，例如 `[href*="@"]@text` 和 `.email\@marker@text`。`@text/@html` 仅是两个固定输出名字，其他名字按属性查询。

JSONPath 与 XPath 支持以当前锁定依赖及包测试为准；本阶段没有宣称与 Legado 的全部方言一致。JSON 字符串由 JSON 解析器读取，HTML 输入由 HTML 解析器读取；不会根据错误自动切换模式。

## 组合与优先级

1. 如果整个输入以 `@js:` 开始，余下内容是完全不透明的 JS，`||`、`&&`、`##` 不再作为规则分隔符。
2. 否则按顶层 `||` 拆分，从左到右执行；首个含非空值的结果列表立即返回。
3. 每个分支再按顶层 `&&` 拆分；所有结果按顺序拼接为列表。
4. 最后识别 `selector##pattern##replacement`，提取后逐个替换。

因此 `&&` 比 `||` 结合得更紧；`##` 在各原子提取结果上执行。`&&` 是列表拼接，不是字符串拼接。stage 字段最终将结果转成文本，用换行符连接。

分隔扫描会跳过引号内部及括号、方括号、花括号内部的分隔符，并跳过被反斜杠转义的字符。它不会去掉原有转义，也不识别正则字面量。规则不存在可用于整体分组的专门括号操作：括号仍属于所选模式。

`||` 的非空判定为值不为 null 且字符串表示不为空，不自动 trim。Element 的判定并不等于其可见文字非空。

## 替换

只接受零个或两个 `##` 分隔，即原规则或 `rule##pattern##replacement`。其他数量报 `invalid_rule`。正则开启 multiline。替换字符串中的 `$0` 是全匹配，`$1` 等为捕获组；不存在或未匹配的组替换为空。默认替换所有匹配。

固定输入与包测试对应的例子：

```text
输入：<div class="book"><h2> Book 123 </h2><a href="/book/1">open</a></div>
规则：@css:h2@text##[0-9]+##X
结果：[" Book X "]
规则：@regex:Book (\d+)
结果：["123"]
```

JSON 配置中反斜杠还需要 JSON 转义：`"@regex:Book (\\d+)"`。测试依据：`packages/source_engine/test/engine_test.dart`。

## URL 与字段

stage URL 将 `{{name}}` 替换为输入对应值。名字必须以 ASCII 字母开头，可继续使用数字与下划线。缺失或 null 值报 `missing_input`。名字以 `Url` 结尾时原样插入；其他值采用 UTF-8 URL component 编码一次，模板中原有 URL 转义保留，随后相对于 `baseUrl` 解析。

字段名为 `url` 或以 `Url` 结尾时，非空结果相对于最终响应 URL 转成绝对链接。字段列表先转文本并以换行连接，然后才进行该转换，因此一个 URL 字段应只提取一个链接。

取消在提取开始、组合分支和各记录之间检查；复杂同步正则或选择器并不因为有 CancellationToken 就获得可抢占中断能力。

## 后续分页

stage 配置 `nextPage` 时，每页提取结束后在整份响应正文上执行该规则，选取第一个非空文本结果，相对于最终响应 URL 解析下一页；无结果则结束。`maxPages` 默认20，允许1..1000。

各阶段分页结果按顺序累积，不自动去重。content 阶段若产生多条记录，保留第一条记录的其他字段，把所有记录的 `content` 按换行连接为一条记录。循环 URL 报 `pagination_cycle`；达到页数限制时仍存在下一页报 `pagination_limit`，不会静默返回截断结果。脚本模式自行编排分页，不使用 stage 的 nextPage。

## 阶段请求体

stage method 默认 GET，允许有效 HTTP token；实际请求转成大写。body 为字符串模板，使用同样的 {{name}} 上下文占位符，但插入原始字符串，不进行 URL component 编码或 JS 执行。缺少输入报 missing_input；JSON、表单等 body 编码格式由作者明确构造。全局/阶段静态 headers 不执行变量或脚本。新引擎不自动推断 Content-Type，表单/JSON 请求应显式设置。

charset 可指定该阶段原始请求体字节编码和响应解码；它不改变 URL 查询变量的UTF-8编码，也不是旧 AnalyzeUrl 的表单/查询charset语义。未指定时使用默认请求编码与响应检测。分页请求沿用该阶段 method、body、headers 和 charset，不自动改写成网站特有翻页表单。
