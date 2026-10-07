# JSoup 提取兼容子集

`@legacy:` 显式使用历史 HTML 提取语义；它是兼容模式扩展，不是新版 CSS 规则的别名。新版 `@css:` 保持自己的输出约定。支持范围以 `LegacyHtmlRule` 和固定输入测试为准，不能推断完整 JSoup selector 方言均已兼容。

## 遍历链

使用 `@` 连接遍历步骤和末尾输出，方括号、圆括号及引号内的 `@` 不拆分。开头一个或多个 `@` 会去掉。初始节点为传入 Element，或解析 HTML 的 documentElement。

| 形式 | 行为 |
|---|---|
| `class.name` | 当前根及后代中拥有该 class 的元素 |
| `tag.name` | 当前根及后代中匹配 tag 的元素，tag 转小写 |
| `id.name` | 当前根及后代中 id 相等的元素 |
| `text.words` | ownText 包含 words，比较不区分大小写 |
| `children` 或空选择步骤 | 当前元素的直接子元素 |
| CSS selector | 使用 Dart HTML CSS selector，匹配时包含当前根 |

每一步对上一阶段的每个节点执行，并按顺序累积。末尾是选择器时返回节点，末尾是输出名时提取值。

## 索引

支持 `.n`、`!n`，以及 `[n,m]`、`[start:end:step]`、`[!n,m]` 后缀。匹配的数字索引后缀允许ASCII空格，如 `tag.p. 0 : 2` 或 `tag.p.0: - 1`；只移除后缀中的ASCII空格，不移除CSS selector本身的空格，也不将tab等其他空白视为索引空格。索引从0开始，负索引从末尾计算；超出范围跳过。`.n:m` 表示多个独立索引，方括号 `start:end` 表示包含两端的范围，不能混淆。

范围允许省略端点、逆序和正步长；零步长报 `invalid_rule`。范围端点按有效索引范围裁剪。负步长按当前历史子集的特殊归一化处理，不作为 Python slice 解释。排除形式保留未选中的元素并保持原顺序。此语法不代表所有旧索引表达式均已覆盖。

## 输出

| 输出 | 行为 |
|---|---|
| `text` | 递归文本；忽略 script/style；按文本节点的preserveWhitespace上下文保留或归一化空白，块/br等边界形成分隔，末尾Java trim |
| `ownText` | 直接文本节点，br形成空格；每个节点按自身preserveWhitespace上下文处理，末尾Java trim |
| `textNodes` | 每个直接文本节点归一化并Java trim，跳过空值后以换行连接 |
| `html` | outer HTML，删除后代 script/style，多个节点以换行连接 |
| `all` | outer HTML，保留 script/style，多个节点以换行连接 |
| 其他末尾属性名 | 属性值；缺失/空值跳过，相同属性值去重 |

`html` 的 outer HTML 与新版 `@css:...@html` 的 inner HTML 不同。text/ownText/textNodes 空输出跳过；`all` 可以返回单个空字符串。CSS 选择器解析器不支持的表达式明确抛错，不静默转成其他规则类型。

HTML 使用 Dart 序列化，不保证 JSoup pretty printing 的字节级一致。固定输入测试位于 `packages/source_engine/test/legacy_html_test.dart`。

text/ownText不会在拼接后进行全局空白压缩。每个文本节点检查父元素及最多五级祖先中的pre/plaintext/title/textarea/script；保留上下文的原始空白，其余节点按JSoup子集归一化空白、移除零宽空格和软连字符。最终Java trim只移除两端U+0000..U+0020，不能与Dart trim混同。递归text仍忽略script/style内容。

## Scalar字段的最后解码

规则stage具有旧源provenance（`metadata.legacy == true` 或 `metadata.legacyOriginal` 为Map），字段规则去掉开头空白后显式以 `@legacy:` 开始（前缀大小写不敏感）时，search/explore/info/toc的scalar结果在规则替换、文本转换与换行join之后进行一次HTML4解码，再解析URL字段。`kind`、`downloadUrls`排除；content、列表规则及nextPage不采用此最后解码。现代CSS、无旧provenance的显式@legacy规则及mainJs返回值没有这个最后解码处理，不能用它推断所有提取路径均会反解实体。

解码复用同一HTML4语义：252个HTML4命名实体，要求分号，单次解码；未知实体与HTML5扩展保留。它不等于浏览器HTML5实体处理，也不会递归解码替换结果。

## 导入器的字面量替换子集

导入器仅在以下旧scalar字段放行 `selector##literalPattern##literalReplacement`，候选仍未验证：

| Stage | 旧字段 |
|---|---|
| search / explore | name、author、wordCount、lastChapter、intro、coverUrl、bookUrl |
| info | name、author、wordCount、lastChapter、intro、coverUrl、tocUrl |
| toc | chapterName、chapterUrl、updateTime、isVolume、isVip、isPay |

必须恰好有两个 `##`，选择器按支持的HTML兼容子集归一化为显式 `@legacy:`，末尾必须明确为text、ownText或textNodes。pattern非空，不能含正则元字符、反斜杠、换行、`#`或引号；replacement不能含 `$`、反斜杠、换行、`#`、引号或 `&`。因此实体替换与捕获组替换不在自动放行范围。脚本、变量、模板、复合规则、XPath及不透明regex等仍需人工处理。content的所有字段、nextPage、kind、downloadUrls、list、管线扩展、未知字段，以及html/属性输出仍不放行。此限制属于旧源导入的保守边界，现代规则的正则替换能力不受它影响。
