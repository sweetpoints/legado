# 分支与合并规则

## 默认分支

`master` 是当前默认分支，用于集成已验证的变更。禁止直接推送、强制推送或删除；所有变更通过 PR 合并。管理员与自动化账号没有规则绕过权限。

规则集定义保存在 [default-branch.json](rulesets/default-branch.json)，对应 GitHub 仓库 Settings → Rules → Rulesets。该文件用于记录和复现规则；修改文件本身不会自动修改 GitHub 设置，需要管理员同步并读回核验。

## 开发分支

从最新 `master` 建立短期开发分支，按任务命名，例如 `feat/<topic>`、`fix/<topic>`、`chore/<topic>`；Codex 使用 `codex/<topic>`。PR 的目标分支为 `master`。在本仓库工作时，在项目内的独立 worktree 完成修改和验证。

不要求长期 `dev` 或 `release` 分支。发布从合并后的明确提交创建 tag，并通过手动发布工作流执行；测试版的 `beta` 浮动标签不作为固定版本依据。

## 合并门禁

- 所有 PR 必须具有由 GitHub Actions 上报的 `CI` 成功检查，并通过与最新目标分支组合后的验证。
- `CI` 汇总 release 单元测试、`release` / `releaseA` 的 ARM / 通用 APK 构建和 Web 测试、类型检查及构建。任何依赖失败、取消或跳过都会使门禁失败。
- 必需检查的工作流不使用路径过滤，文档和仅 Web 变更也会产生 `CI` 结果。
- 合并前解决全部审查讨论。仓库当前仅有一位管理员，因此审核批准数为 0；增加独立维护者后，可提高为至少 1 位审核者。
- 与路径相关的 UI、PDF、Cronet 模拟器回归仍按各自工作流执行；它们不作为全仓库的固定必需检查，涉及这些模块时应检查对应结果。

## 自动化与发布

Web 构建始终上传产物。需要自动更新内置 Web 资源时，设置 Actions Variable `ENABLE_WEB_ASSET_PR=true`，并配置 Secret `WEB_ASSET_TOKEN`；该 token 需具有本仓库 Contents 与 Pull requests 写权限，使生成的 `codex/web-assets` PR 能触发 CI。不要给自动化配置绕过 `master` 规则的权限，也不要使用直接推送 `master` 的脚本。

`ENABLE_TEST_RELEASE=true` 可启用合并后的测试版发布；否则手动触发。签名凭据仍由发布工作流检查。蓝奏云分发已移除。
