# 书源迁移 Reference

迁移保留原始内容，输出新版候选及问题清单。**候选生成成功不代表已验证兼容，也不会自动替换原书源。**

## 整体迁移

`SourceMigrator.migrate(input)` 调用旧格式导入器，返回 `MigrationCandidate`。整体转换包含结构导入，并尝试转换阶段 `fields`、`list` 和 `nextPage` 中以 `@js:` 开始的脚本；URL 脚本和外部脚本库不自动转换。

报告字段：

| 字段 | 类型与意义 |
|---|---|
| `reportVersion` | 整数 `1` |
| `status` | `unverified` 或 `manualRequired` |
| `original` | 原始书源的独立 JSON 副本 |
| `candidate` | 新版格式候选 |
| `issues` | `{path, code, message}` 列表 |
| `verified` | 当前固定为 `false` |

整体迁移完成后按最终 issues 重算候选 metadata：没有 issue 时 `legacy:false`、`compatibility:"unverified"`，可使用 modern 运行模式；有 issue 时 `legacy:true`、`compatibility:"manualRequired"`，保留兼容模式标记。保留 `legacyOriginal` 不代表候选仍启用旧宿主，且两种状态的 verified 均为 false。

原始格式字段映射及字面量 URL,{JSON} 请求选项转换见[旧版 Reference](../reference/legacy/README.md)。严格字面量请求可生成method/body/bodyEncoding/bodyTemplateMode/headers；安全的已知模板表单在替换后编码，静态发现菜单形成legacyExploreItems，旧charset及动态或未知选项仍为manualRequired。可使用下文 compare 对照一个阶段 case，但没有完整旧 JVM 对照验证、持久化切换或回滚操作；调用方应保存原始版本，在验证完成后自行决定是否启用候选。

## 脚本转换

`SourceMigrator.migrateScript(script)` 是单独入口，返回 `ScriptMigration(original, candidate, issues)`。失败时 `candidate` 为 `null`。它使用受限词法扫描和调用节点替换，不是完整 JavaScript 编译器或通用异步转换器。

| 旧直接调用 | 新调用 |
|---|---|
| java.ajax(literalUrl) | await source.net.get(literalUrl) |
| java.ajax(literalUrl,literalTimeoutOrNull) | await source.net.request({url,timeoutMs}) 后读取 body |
| java.base64Encode | 单参数基础方法；双参数 encoding.base64EncodeWithFlags |
| java.base64Decode | encoding.base64DecodeWithFlags；字面量 charset 用 WithCharset |
| java.base64DecodeToByteArray | encoding.base64DecodeBytes |
| java.strToBytes / bytesToStr | encoding.strToBytes / bytesToStr |
| java.hexEncodeToString / hexDecodeToString / hexDecodeToByteArray | encoding.hexEncode / hexDecode / hexDecodeBytes |
| java.md5Encode / md5Encode16 | crypto.md5 / md5Short |
| java.digestHex / digestBase64Str | crypto.digestHex / digestBase64 |
| java.encodeURI | encoding.formEncode |
| java.get(key) | ((await source.variables.get(key)) ?? "") |
| java.put(key,value) | await source.variables.put(key,value) |
| java.getString / getStringList | 字面量规则转换为 source.parse 对应方法，捕获 result/baseUrl；非空规则的getString结果再await source.encoding.unescapeHtml4 |

规则提取转换只接受可解析字面量、无嵌套脚本的规则；默认内容 result、URL 标志 false 和当前 baseUrl 显式传入。空规则保持旧 getString 空字符串/getStringList null。兼容运行时支持 getString(rule,unescape:boolean)，迁移器仍只自动转换单个字面量规则参数，不自动转换该Boolean重载。动态规则报 migration.dynamic_rule；HTTP get/connect/post/head 和 Java DOM 方法不自动迁移，只在兼容模式中使用。

单参数 ajax 也必须是字面量 URL 字符串；动态参数、别名和提取结果需人工处理，报 migration.ambiguous_overload。直接数组或 Array 构造形式报 migration.ajax_array_requires_review，避免把旧“取首个数组元素”的语义直接传给新版 net.get。

base64Decode 第二参数必须是可判断的字面量 charset 或整数 flags；动态重载与非字面量 timed ajax 报 migration.ambiguous_overload。转换仍是受限直线脚本处理，不进行任意调用链异步传播。工具 API 必须由运行宿主注入 SourceUtilityHost，Reference 见[新版工具 API](../reference/v1/utilities.md)。

已带 `await` 的旧调用需要人工审查，不自动转换。变量读取额外把 null 转成空字符串，保持旧缺失值语义。支持的调用必须符合表中简单参数数量；已列出的字节、字符集和标志方法可转换；HTTP response 对象调用及未列出的重载需要人工处理。加括号是为了让属性访问和字符串拼接作用于最终结果。字符串和注释中的接口名字保留原字节，不按文本全局替换。

例如，转换输入：

```javascript
const body = java.ajax('https://example.invalid/chapter');
return body;
```

候选：

```javascript
const body = (await source.net.get('https://example.invalid/chapter'));
return body;
```

此候选需在支持异步函数体的脚本入口中执行；示例 URL 不提供网络服务。对应转换断言位于 `packages/source_migration/test/source_migration_test.dart`。

## 需要人工处理

当前遇到下列形式会拒绝自动脚本转换：函数、箭头函数、类、控制流、对象块、模板字符串、正则或斜杠 token、动态执行、Java 互操作、动态成员访问、未知 `java.*` 方法，以及 `java` 的别名或遮蔽。拒绝斜杠 token 也会拒绝除法，属于保守处理。

错误标识：

| code | 意义 |
|---|---|
| `migration.syntax_requires_review` | 未闭合字符串、注释或不平衡括号等 |
| `migration.complex_script` | 超出受限直线脚本范围 |
| `migration.dynamic_java` | 不是直接 `java.method(...)` 调用 |
| `migration.unsupported_api` | 没有对应的新接口转换 |
| `migration.unsupported_overload` | 超出文档中的简单参数数量 |
| `migration.already_async` | 已带等待的旧调用需要审查 |
| `migration.ambiguous_overload` | 无法静态确定重载或 timed ajax 参数 |
| `migration.dynamic_rule` | 非字面量、不可解析或嵌套脚本的提取规则 |
| `migration.java_binding` | 可能存在别名或遮蔽 |
| `migration.ajax_array_requires_review` | ajax 数组输入需要显式保持首项语义 |
| `migration.host_binding_conflict` | 裸 source 标识符与新版宿主命名空间冲突；obj.source、字符串和注释不触发 |

受限扫描并不验证整个脚本语法和行为；没有 issue 的候选也必须交给实际运行时校验并进行行为对照。禁止把 `unverified` 报告当作自动发布依据。

## 单阶段 case 对照

从 Flutter 工作区运行：

```sh
dart run packages/source_tools/bin/source_tools.dart compare LEGACY_FILE CANDIDATE_FILE STAGE --variables '{"key":"示例","page":1}' --report comparison.json
```

STAGE 为 search/explore/info/toc/content。旧发现阶段通过 --variables 提供用户选择的 exploreUrl；现代发现入口使用url时提供url。旧输入经 LegacySourceImporter 在 Flutter legacy 模式执行；候选按 metadata 选择 modern 或 legacy 模式，报告明确给出双方 executionMode。两侧使用独立引擎与输入，只比较本次结果：映射键顺序不影响比较，列表顺序、值与类型严格比较。使用实时网络时，内容变化、时间、随机值与 Cookie 状态可能导致差异。

报告 reportVersion 为 1，baseline 为 flutterLegacyCompatibility、network 为 live、comparisonScope 为 stageResult、stateCompared 为 false；不比较变量、Cookie或持久化状态，caseEquivalent 只表示本阶段、此输入的结果等价；sourceVerified、verified、jvmCompared 固定为 false。它不验证原 Kotlin/Rhino JVM 引擎、整本书流程或全部历史书源。报告记录输入和变量 SHA-256、双方执行模式、结果摘要 SHA-256、列表数量或 errorCode，不写原始结果、异常 message 或 URL。

退出码：0 为本 case 等价，4 为两侧成功但结果不同，1 为执行失败，2 为无效输入，64 为命令用法错误，73 为报告文件已存在，74 为文件读写失败。已有报告不会覆盖。命令的其他用法见 [source_tools README](../../packages/source_tools/README.md)。

## 私有书源集合离线审计快照

本次用户backup.zip集合共554个书源（enabled546、disabled8），其中文本473、非文本81；enabled且文本466。最终仅做离线结构导入与迁移审计，生成526个候选，其中13个unverified、513个manualRequired；28个未生成候选。相对第二轮仅3个候选从manualRequired变为unverified；实际执行数与verified数均为0，不表示联网行为、旧JVM等价或整套历史书源兼容通过。

静态菜单metadata覆盖274个源、12,361个入口，其中JSON菜单150个源、9,243个入口，9,228个style字段保留；52个源使用legacyFormUtf8编码，89个源使用legacyJsonString模板模式。issue总数10,081，包含本轮新增逐菜单入口可审查项，不能直接把issue总数增加称为兼容退化。剩余28个未生成候选的静态分类为19个JS、4个非标准query模板、2个表达式空白、2个无search、1个无法确定锚点；这些分类不证明旧源无效。锚点与旧请求支持仍受明确边界约束，详情见[旧版Reference](../reference/legacy/README.md)。

这里只记录安全汇总；私有原书源及逐源数据不进入Git提交，最终审计summary保存在ignored的私有临时目录。
