# ACR-001 核对记录

本页用于核对 ENG-001B-01 提供的决策要点。ACR-001 的完整批准正文仍待导入，本记录不具备该正文的效力。任务起始提交为 `8e3165ae630d7eabe30cf28ebafff6fefe293a95`，来源为最新 `origin/master-jdk17`，该提交已合并 ENG-001A。

| 编号 | 已知批准结论 | 对应规范 |
| --- | --- | --- |
| C-01 | OpenJDK 21、Maven 3.9.12、Spring Boot 3.5.16、MySQL 8.4，当前 Java 编译目标 17，保留模块化单体及独立 Gateway | [技术基线](../standards/technology-baseline.md) |
| C-02 | 设备 Epoch 毫秒、Java Instant、按 UTC 约定保存的 DATETIME(3)、API ISO 8601 | [时间规范](../standards/time-conventions.md) |
| C-03 | VWCP INT32 克、业务 BIGINT 克、正式 DECIMAL(12,3) 千克，保留原始数据并避免父子记录重复计费 | [重量规范](../standards/weight-conventions.md) |
| C-04 | 成功 L2 ACK 以 INGEST 原始事件与 Outbox 的本地事务提交为条件 | [ACK 与持久化](../standards/iot-ack-and-durability.md) |
| C-05 | INGEST、BUSINESS、TRACK 分离，以本地事务与 Outbox/Inbox 衔接，提交后确认消费 | [事务边界](../standards/transaction-boundaries.md) |
| C-06 | 九个逻辑模块经公开 API 或事件交互，禁止依赖内部实现或形成循环，物理 Maven 划分沿用既有建议 | [模块边界](../standards/module-boundaries.md) |

结论来源为 ENG-001B-01 明确提供的 C-01 至 C-06 要点。C-01 延续 ENG-001A 技术基线。OpenJDK 21 是当前最新正式技术决策，用于构建与运行。Java 编译目标仍为 17，现有 CI 仍按 POM 选择 JDK 17。历史 JDK 21 构建证据见 [UPSTREAM-001 构建报告](../../engineering/jdk21-build-report.md)。

受影响 ADR 编号目前无法确认。现有 [ADR-001 至 ADR-040 索引](../adr/README.md)未提供决策正文，且任务材料未给出结论到 ADR 的对应编号。待获得 ACR-001 正文、相关 ADR 正文及其批准记录后再登记确切映射。当前索引中的所有条目继续保留“待导入已批准正文”。

## 本次文档验收

暂存差异检查覆盖 9 份架构文档，`git diff --cached --check` 通过。文档检查脚本核验了 36 处相对链接、六份规范的编号及五个要求章节、ACR 核对记录的六项映射与来源状态，并检查了时间、重量、ACK、事务术语和九模块名称。常见密钥、令牌及明文凭证模式扫描无匹配。本次未运行项目构建、功能测试或部署验证。
