# 车载称重系统开发规则

本文件适用于整个仓库。任务开始前查看 [架构目录](docs/architecture/README.md)、[技术基线](docs/architecture/standards/technology-baseline.md)、[Codex 工作流](docs/engineering/codex-workflow.md)和[验证矩阵](docs/engineering/validation-matrix.md)。

## 分支与审批

- 所有正式开发任务从最新的开发远程 `master-jdk17` 派生独立任务分支。已有任务继续使用其任务分支。
- 禁止直接向 `master` 或 `master-jdk17` 提交或推送。以 `master-jdk17` 为目标创建 Draft PR，等待人工审查，禁止自动合并。
- 技术基线、架构边界和已批准的 ADR/ACR 需要明确批准才能变更。ADR/ACR 索引中的待导入条目缺少可引用正文。需要相关决策时请求已批准原文，禁止按编号推断或编造决策。
- 按任务授权控制变更范围。保留已有用户改动，检查暂存区，只提交本任务的文件。

## 代码与数据边界

- 遵循现有 RuoYi-Vue-Pro 模块化单体架构，IoT Gateway 独立部署。跨业务模块通过公开 API 或事件契约交互。禁止直接引用其他模块的 Mapper、DO 或内部 Biz 实现。
- 数据库结构变更使用纳入版本控制、可按序执行的迁移，并验证已有数据上的升级路径。
- 禁止在仓库中存放生产密钥或敏感凭证，包括配置、示例、测试数据、日志和报告。通过环境的密钥配置注入凭证。

## 验证与生产阻断

- 涉及设备 ACK、重量精度、幂等或计费的变更，必须提供对应的针对性测试。记录实际命令、退出码、结果及待验证项。
- 所有测试报告必须区分实际通过、失败、跳过、未执行及待验证。编译或最小启动只证明其覆盖的范围。
- RabbitMQ 与 SQLite 双暂存服务冲突是待解决的生产启用阻断。通过构建或定向测试后仍需完成修复与专项验收，方可启用受影响的生产配置。详见 [Gateway 分析](docs/engineering/iot-gateway-analysis.md)。
