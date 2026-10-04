# 书源 Reference

本目录描述仓库当前实现的书源契约。格式版本与 V8 版本分别管理；安装 V8 不会改变书源格式版本。

| 文档 | 内容 |
|---|---|
| [新版书源 v1](v1/README.md) | 格式、规则、执行流程、宿主 API 与错误 |
| [旧版兼容](legacy/README.md) | Legado 导入、`java.*` 的兼容范围 |
| [迁移 Reference](../migration/README.md) | 转换、报告与需要人工处理的情况 |
| [Android 接入](v1/android.md) | Kotlin ↔ Dart 请求、事件与生命周期 |

机器可读契约位于 [`flutter/spec`](../../spec)。文档中的“不支持”表示当前实现不会提供该行为，不能据此推断旧书源已经迁移完成。示例必须与包内测试一致；协议新增或变更时同步更新 Reference、Schema 与测试。
