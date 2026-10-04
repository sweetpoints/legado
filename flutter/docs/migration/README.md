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

原始格式字段映射见[旧版 Reference](../reference/legacy/README.md)。当前没有自动新旧引擎对照验证、持久化切换或回滚操作；调用方应保存原始版本，在验证完成后自行决定是否启用候选。

## 脚本转换

`SourceMigrator.migrateScript(script)` 是单独入口，返回 `ScriptMigration(original, candidate, issues)`。失败时 `candidate` 为 `null`。它使用受限词法扫描和调用节点替换，不是完整 JavaScript 编译器或通用异步转换器。

| 旧直接调用 | 新调用 |
|---|---|
| `java.ajax(...)` | `(await source.net.get(...))` |
| `java.base64Encode(...)` | `(await source.encoding.base64Encode(...))` |
| `java.base64Decode(...)` | `(await source.encoding.base64Decode(...))` |
| `java.get(...)` | `((await source.variables.get(...)) ?? "")` |
| `java.put(...)` | `(await source.variables.put(...))` |

已带 `await` 的旧调用需要人工审查，不自动转换。变量读取额外把 null 转成空字符串，保持旧缺失值语义。支持的调用必须符合表中简单参数数量；字节、字符集、标志及 HTTP 重载不自动转换。加括号是为了让属性访问和字符串拼接作用于最终结果。字符串和注释中的接口名字保留原字节，不按文本全局替换。

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
| `migration.java_binding` | 可能存在别名或遮蔽 |

受限扫描并不验证整个脚本语法和行为；没有 issue 的候选也必须交给实际运行时校验并进行行为对照。禁止把 `unverified` 报告当作自动发布依据。
