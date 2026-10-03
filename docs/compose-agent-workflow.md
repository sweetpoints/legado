# Compose 多 agent 工作流程

迁移使用项目内独立 Git worktree。每个 subagent 只在自己的目录和分支写入，整合分支为 `codex/compose-migration`，项目原始 checkout 保留不动。

| 职责 | 项目内目录 | 分支 |
| --- | --- | --- |
| 主 agent 整合 | `.worktrees/compose-migration` | `codex/compose-migration` |
| 搜索页、远程书库 | `.worktrees/compose-search` | `codex/compose-search` |
| 书籍详情 | `.worktrees/compose-book-detail` | `codex/compose-book-detail` |
| 替换规则、文件选择 | `.worktrees/compose-replace-files` | `codex/compose-replace-files` |
| 书源管理 | `.worktrees/compose-source-manager` | `codex/compose-source-manager` |
| 书源编辑、代码编辑 | `.worktrees/compose-source-editor` | `codex/compose-source-editor` |
| 音频页面 | `.worktrees/compose-audio` | `codex/compose-audio` |
| 漫画页面 | `.worktrees/compose-manga` | `codex/compose-manga` |
| 视频页面 | `.worktrees/compose-video` | `codex/compose-video` |
| 正文阅读器 | `.worktrees/compose-reader` | `codex/compose-reader` |
| 隔离验证 | `.worktrees/compose-validation` | detached HEAD |

## 开发与提交

1. 每个功能在自己的 worktree 开发，所有命令明确工作目录。共享类和测试也在各自分支修改，不跨目录写入。
2. 数据操作、状态与页面迁移按可独立验证的功能批次提交。明确 stage 本批文件；后续功能的半成品不得混入。
3. 每批运行 `:app:compileAppDebugAndroidTestKotlin` 和 `:app:testAppDebugUnitTest`，使用已有 SDK、`--offline --console=plain --max-workers=2`。报告不可变 commit SHA、验证结果及日志位置；Android 测试代码编译通过不代表设备测试执行通过。
4. 大输入及回执使用私有会话，保持原引擎和公开调用入口的兼容性。删除 XML 前验证最后消费者。

## Rebase 与整合

1. 主 agent 选择已提交的功能批次并审查精确 commit 范围。
2. 子分支在安全检查点保存后续在途改动，使工作区干净，再 rebase 到最新 `codex/compose-migration`。解决冲突时保留各功能已验证的行为和测试，不覆盖另一功能的改动。
3. 对 rebase 后的代码完成对应验证，报告新的不可变 SHA。主 agent 检查验证与 SHA 一致，在整合 worktree 使用 fast-forward 合入该 SHA。
4. 逐个分支整合；其他 subagent 可继续在自己的 worktree 开发。下一批整合前再次 rebase，不能直接合入未审查的后续提交。
5. 需要暂存半成品以进行 rebase 时，仅保存自己 worktree 的改动，并记录创建后的不可变 stash SHA 与文件哈希。Git stash 栈在所有 worktree 之间共享；恢复使用 `git stash apply <精确 SHA>`，不得使用动态序号 `stash@{0}` 或直接 `pop`，避免取错另一 agent 的条目。恢复后校验文件归属与哈希，解决冲突；保留 stash 和恢复快照直到确认内容已迁入独立提交。

2026-10-03 开始采用独立 worktree 流程；此前共享 worktree 的在途改动已保留恢复快照与 Git stash，没有作为混合功能提交。

## 可读性与格式化

迁移代码使用描述性命名、显式 import 和职责明确的函数。每行表达一个步骤，不用分号压缩多条语句；Compose 层级、构造参数和条件保持清楚。涉及取消、原生资源所有权、接受回执与恢复时，简短说明必要的执行顺序。可读性整理与功能迁移分别提交。

提交前对本批 Kotlin 文件实际执行 `scripts/format-kotlin.sh <files...>`，再执行 `scripts/format-kotlin.sh --check <files...>` 和 `git diff --check`。脚本固定 ktfmt 0.64 与 Maven Central SHA-256，使用 Kotlin language style、四空格缩进和 100 字符行宽；只处理明确列出的文件，不将其他功能的格式改动混入提交。

十个 agent 包含主 agent 与九个 subagent。实现可以并行，完整 Gradle 验证最多同时运行两个；subagent 在准备验证后向主 agent 请求构建名额，完成后立即释放。整合仍按精确提交顺序 rebase、验证与 fast-forward。
