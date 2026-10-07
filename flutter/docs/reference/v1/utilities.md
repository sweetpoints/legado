# 编码与摘要扩展 API

本页接口由 `SourceUtilityHost` 提供，返回 Promise。默认 Android module 的 `createSourceEngine` 和 CLI 的 `createCliEngine` 注入该适配器；单独使用纯 Dart SourceEngine 时需要显式组装。方法名称属于新版 `source` 命名空间，底层复用经过验证的编码实现，不向新版暴露 `java`。

| API | 参数 | 返回值 |
|---|---|---|
| `source.encoding.base64EncodeWithFlags` | text、可选 Android flags（默认2） | Base64 字符串 |
| `source.encoding.base64DecodeWithCharset` | text、可选 charset（默认UTF-8） | 字符串 |
| `source.encoding.base64DecodeWithFlags` | text、可选 flags | UTF-8 字符串 |
| `source.encoding.base64DecodeBytes` | text、可选 flags | signed 字节数组或 null |
| `source.encoding.strToBytes` | text、可选 charset | signed 字节数组 |
| `source.encoding.bytesToStr` | bytes、可选 charset | 字符串 |
| `source.encoding.hexEncode` | text | UTF-8 小写十六进制 |
| `source.encoding.hexDecode` | hex | UTF-8 字符串 |
| `source.encoding.hexDecodeBytes` | hex | signed 字节数组 |
| `source.encoding.formEncode` | text、可选 charset | Java 表单编码，空格为+ |
| `source.encoding.unescapeHtml4` | text（必须为字符串） | HTML4 实体单次解码字符串 |
| `source.encoding.formDecode` | text、可选 charset | 表单解码，+为空格；非法百分号序列抛错 |
| `source.crypto.md5` | text | 小写32位MD5 |
| `source.crypto.md5Short` | text | MD5字符8..23 |
| `source.crypto.digestHex` | text、algorithm | 摘要小写十六进制 |
| `source.crypto.digestBase64` | text、algorithm | 摘要Base64 |

charset 默认 UTF-8；支持的字符集、Android flags 与摘要算法见[编码契约](../legacy/host.md)。扩展接口保持其解码替换行为、null、字节和异常契约，不应与核心简单 Base64 的严格 UTF-8 decode 混淆。

核心 `source.encoding.base64Encode(text)` 和 `base64Decode(text)` 保留单参数基础形式。多参数调用需要适配器支持；没有适配器时明确报 unsupported_host_api，不隐式忽略多余参数。推荐迁移后的书源使用上表明确重载名字。

测试依据：`packages/source_legacy/test/legacy_host_test.dart`；实际 V8 Promise 桥另由运行时与宿主集成测试覆盖。

`unescapeHtml4` 使用与 Commons Text 1.13.1 对应的252个HTML4命名实体表，实体必须带分号；不包含HTML5额外实体或 apos，未知实体保持原文。只解码一遍，例如 `&amp;lt;` 变为 `&lt;`。数字实体保留C1码点；超过0x10ffff的码点抛错，整数溢出的实体保持原文。此扩展不改变现代 parse.getString/getStringList 的默认语义。
