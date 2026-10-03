# Compose 多 agent 工作流程

迁移使用项目内独立 Git worktree。每个 subagent 只在自己的目录和分支写入，整合分支为 `codex/compose-migration`，项目原始 checkout 保留不动。

| 职责 | 项目内目录 | 分支 |
| --- | --- | --- |
| 主 agent 整合 | `.worktrees/compose-migration` | `codex/compose-migration` |
| 搜索页、远程书库 | `.worktrees/compose-search` | `codex/compose-search` |
| 书籍详情 | `.worktrees/compose-book-detail` | `codex/compose-book-detail` |
| 替换规则、文件选择 | `.worktrees/compose-replace-files` | `codex/compose-replace-files` |
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
5. 需要暂存半成品以进行 rebase 时，仅对自己 worktree 创建恢复 stash；整合后恢复并解决冲突。保留恢复快照直到确认内容已迁入独立提交。

2026-10-03 开始采用独立 worktree 流程；此前共享 worktree 的在途改动已保留恢复快照与 Git stash，没有作为混合功能提交。
