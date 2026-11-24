# ACR-001 核对记录

## 历史记录｜ENG-001B-01

以下为 ENG-001B-01 阶段快照。核对记录提交 `7d7a46972eacf4bb5b6ba193c081948526dc6dca` 的 Git 提交日期为 2026-11-23。下文“当前索引”“本次文档验收”描述当时状态，当前核对结果见后续章节。

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

## 当前核对｜ENG-001B-03

起始提交为 `c9bc342f1a1324be200566db38e3460eed42eade`，已核对最新 `origin/master-jdk17`，包含 ENG-001B-02D 的 [PR #13](https://github.com/weeleng/car_weight/pull/13)，其审核提交为 `56416ded55abe80d5524e981432cc17c5412579e`。

| 核对项 | 当前证据及状态 |
| --- | --- |
| ADR 文档 | 40 份 Proposed v0.1，0 份正文待建立，0 份经核验的历史批准原文。审批准备与正式批准分开。 |
| ACR-001 | 完整批准正文和可验证批准记录均未找到，索引仍待导入已批准正文。 |
| 统一约束 | 沿用 ENG-001B-01 的 C-01 至 C-06，以及 ENG-001A 的技术基线。当前草案关系由正文追溯，历史 ACR-001 的影响范围仍未核验。 |
| 实现与环境 | 02D 已完成静态核对。POM 编译目标 17，Actions 配 JDK 17、服务 Dockerfile 用 JRE 17、Jenkins 声明 jdk17/maven3。当前云检出执行 `java -version` 得 OpenJDK 21.0.13。默认 PATH 的 `mvn -version` 返回 127；现有 `/workspace/car-weight-dev-jdk17/apache-maven-3.9.12/bin/mvn -version` 返回 0，确认 Maven 3.9.12 及 Java 21.0.13。该证据仅适用本次云环境和版本查询。历史构建证据仍对应历史 SHA。 |
| 决策与门槛 | DEC-001 至 DEC-018 均开放。7 项门槛均未关闭，BLOCK-001 为既有阻断，另外六项为候选。A 至 S 共 19 个工作包仍待各自实施授权。 |

### 历史批准证据查找

- 当前仓库用 `git ls-files` 与 `rg` 搜索 ACR、ADR、审批及签署路径，并核对 ADR 正文、索引、证据矩阵及 ACR 核对记录。找到既有规范、40 份候选稿和各自来源说明，未找到完整批准正文或可核验的批准记录。
- 检查本地可访问 Git 引用的历史路径和文字差异，执行 `git log --all --name-only --format=`、`git log --all --format='%H %cs %s' -- docs/architecture/acr`、`git log --all --format='%h %s' -G '状态.*(Accepted|Approved|已批准)|正式批准记录' -- '*.md'`。匹配为工程框架、证据清点及 02D 审核说明。ENG-001A 的 `3c9ee55572ca51ed44469d7abdbe488ffb3d4213` 建立索引，ENG-001B-01 的 `7d7a46972eacf4bb5b6ba193c081948526dc6dca` 记录六项约束。各批草案的版本与来源见 [ADR 证据矩阵](../adr/decision-evidence-matrix.md)。
- 查找结论限于当前仓库、本地可访问 Git 历史及当前任务材料。Git 提交、PR 合并和 AI 审核记录用于追踪来源。正式批准须能核对具体内容、版本、批准角色、时间和证据。

### 人工审批入口

1. 核对[统一约束追溯](../reviews/architecture-constraint-traceability.md)和[逐项就绪矩阵](../reviews/adr-approval-readiness-matrix.md)，将现有约束的确认与候选原则的批准分别记录。
2. 优先审议设备事件身份与可靠 ACK、三库事务及幂等、项目隔离和数据归属。字段、算法、DDL、财税规则及 HA 保证留待对应专项。详见 [18 项审批卡](../reviews/decision-approval-packets.md)。
3. 依[工程启动条件](../reviews/implementation-entry-gates.md)分别授权 A、G，B/C/D 可并行准备设计。各[生产门槛](../reviews/production-gate-approval-matrix.md)按功能及形态专项验收。实际批准写入[人工审批模板](../reviews/architecture-approval-record-template.md)。

### ENG-001B-03 文档验收

| 实际命令 | 结果 |
| --- | --- |
| `git ls-remote origin refs/heads/master-jdk17`、`git fetch origin master-jdk17`、`git rev-parse origin/master-jdk17` | 退出码 0，远程和本地跟踪 SHA 均为约定基线。 |
| `git diff --check`、`git diff --cached --check`、`git diff --cached --name-status` | 退出码 0。新增 6 份审核文档，更新 3 份允许文档。 |
| `python /tmp/eng03_validate.py` | 退出码 0。核验历史记录原文保留、40 份 ADR 及受保护文件完全一致、6 项规范及 161 个追溯关系、40 行就绪状态、18 张卡、7 项开放门槛、19 个工作包、5 种审批类型。检查 65 份文件中的 1,333 个相对链接，含 255 个锚点。常见凭证模式未匹配。临时脚本用于本次静态验收。 |

本次审批准备未获得 ACR-001 或 ADR 的正式批准，未决定 DEC、授权实施工作包或关闭生产门槛。未执行构建、应用运行、迁移、压测、安全扫描、真机联调、第三方沙箱、部署或灾难恢复演练。
