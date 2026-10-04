# 新版书源 v1 Reference

本页对应 `SourceDefinition.schemaVersion = 1`。这是当前实现的契约，完整 Legado 功能兼容仍需查看[旧版 Reference](../legacy/README.md)。

## 格式

机器校验定义：[source-v1.schema.json](../../../spec/schemas/source-v1.schema.json)。

| 字段 | 类型 | 要求与默认值 |
|---|---|---|
| `schemaVersion` | 整数 | 默认 `1`；其他值报 `unsupported_version` |
| `id` | 字符串 | 非空，稳定书源标识 |
| `name` | 字符串 | 非空 |
| `baseUrl` | 字符串 | 绝对 HTTP(S) URL，必须有主机 |
| `stages` | 对象 | 默认空对象；只接受 `search/explore/info/toc/content` |
| `metadata` | 对象 | 默认空对象；支持请求调度配置，见宿主 API |
| `headers` | 字符串映射 | 默认空对象；静态全局请求头 |
| `script` | 字符串或 null | 非 null 时以完整 JS 入口执行，优先于 `stages` |

每个 stage 包含：`url`（字符串，默认空）、`list`（可空的规则字符串）、`fields`（字段名到规则字符串的映射，默认空）。另有 `nextPage`（可空规则字符串）和 `maxPages`（整数，默认20，读取配置时要求1..1000）。另有 method（默认GET）、body（可空字符串）、headers（可空静态字符串映射）、charset（可空字符集覆盖）。URL 为空不表示该阶段可执行。

Schema 定义作者应提交的严格字段类型。当前 Dart 读取器会把 `fields` 中部分非字符串值转成字符串，并忽略部分未知字段；这种宽容读取不应作为新书源作者的格式保证。顶层未知字段不会由 `toJson` 自动保留；需要保存的扩展信息放入 `metadata`。

## 执行

详情见[规则语言](rules.md)、[宿主 API](api.md)、[运行时](runtime.md)与[Android 接入](android.md)。

每个阶段先解析请求 URL并加载响应，再按列表与字段规则提取结果。默认网络是 HTTP(S)，不等于浏览器页面执行。旧请求选项、网页登录和任意 Java 调用不会自动转换。

URL 上下文占位符包括 `{{key}}`、`{{page}}`、`{{bookUrl}}`、`{{tocUrl}}`、`{{chapterUrl}}`。具体编码及缺失变量行为以规则 Reference 和测试为准。

## 最小格式示例

```json
{
  "schemaVersion": 1,
  "id": "example",
  "name": "Example",
  "baseUrl": "https://example.invalid/",
  "stages": {
    "search": {
      "url": "/search?q={{key}}&page={{page}}",
      "list": "@css:article",
      "fields": {
        "name": "@css:h2@text",
        "bookUrl": "@css:a@href"
      }
    }
  }
}
```

`example.invalid` 是示例地址；此示例说明格式，不提供可联网书源。可运行的固定输入与本地服务示例位于 `packages/source_engine/test/engine_test.dart`，避免真实网站变化影响规范判断。

## 错误

`EngineException` 包含 `code`、`message`，不是所有 Dart 异常都被转换成此类型。输入类型错误可能产生类型异常，解析器和运行时也可保留各自错误。

| code | 含义 |
|---|---|
| `unsupported_version` | 不支持书源规范版本 |
| `invalid_source` | 必填值、基础 URL 或阶段名不合法 |
| `cancelled` | 调用方已取消任务 |
| `invalid_url` | 请求协议不是 HTTP(S) |
| `redirect_limit` | 超过重定向限制 |
| `unsupported_charset` | 响应字符集无法解码 |
| `timeout` | 网络请求超时 |
| `unsupported_operation` | 操作名不在五种阶段之中 |
| `missing_stage` | 规则模式中缺少所请求的阶段 |
| `missing_input` | URL 占位符缺少输入值 |
| `http_error` | 规则阶段的响应状态码 >=400 |
| `invalid_arguments` | parse 宿主调用缺少规则或内容 |
| `unsupported_rule` | parse 宿主不支持嵌套脚本规则 |
| `reserved_storage_key` | 脚本访问会话保留存储键 |
| `invalid_rule` | 替换分隔符数量错误 |
| `invalid_result` | 完整脚本返回的记录不是对象 |
| `unsupported_host_api` | 未知宿主方法 |
| `pagination_cycle` | 下一页重复已请求 URL |
| `pagination_limit` | 仍有下一页但达到 maxPages |

本实现不能仅凭导入成功、V8 可加载或单元测试成功，认定旧书源端到端兼容。
