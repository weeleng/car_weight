# 网页版 Codex 开发工作流

## 开始任务

使用当前云任务的现有检出。检查 `git status --short --branch` 和相关 `AGENTS.md`，保留已有改动。新任务从开发远程的目标分支派生，例如：

```bash
git fetch --no-tags origin refs/heads/master-jdk17:refs/remotes/origin/master-jdk17
git switch -c <任务分支> origin/master-jdk17
git rev-parse HEAD
```

将示例中的占位符替换为实际任务分支名，记录起始 SHA。若已有改动会被切换覆盖，先保护已有工作。上游同步须在任务范围内另行核对官方分支和提交关系。

## 实施和验证

1. 阅读[技术基线](../architecture/standards/technology-baseline.md)和相关决策原文。仅有索引时，将缺失决策记入待确认项。
2. 按授权修改，并根据[验证矩阵](validation-matrix.md)选择针对性检查。设备 ACK、重量精度、幂等和计费变更必须随附针对性测试。
3. 报告记录被验证的完整 SHA、工具版本、命令、退出码、测试数量或行为结果，以及跳过和待验证项。区分默认 Reactor、IoT Reactor、Gateway 启动及端到端场景。
4. 提交前检查 `git diff --check`、`git diff --cached --check` 和 `git status --short`，逐项确认提交范围及日志无凭证。

## 提交审查

只推送独立任务分支。创建 Draft PR，目标为 `master-jdk17`，说明范围、执行证据、待验证项和生产阻断。提交后报告文件清单、分支完整 SHA 和可验证的 PR 地址，等待人工审查。若受权限或网络阻断，记录实际错误及分支推送状态。禁止直接向 `master` 或 `master-jdk17` 提交和自动合并 PR。
