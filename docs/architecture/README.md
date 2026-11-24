# 架构文档

工程规范从开发分支基线 `fe211d0333db30d3d260fbc4533682a51a750bbf` 建立。系统采用 RuoYi-Vue-Pro 模块化单体，IoT Gateway 独立部署。

| 目录或文件 | 内容 | 当前状态 |
| --- | --- | --- |
| [技术基线](standards/technology-baseline.md) | C-01 工具链、运行基线和部署边界 | ENG-001B-01 复核 |
| [时间规范](standards/time-conventions.md) | C-02 事件、接收和持久化时间 | 已整理已提供要点 |
| [重量规范](standards/weight-conventions.md) | C-03 单位、类型和精度 | 已整理已提供要点 |
| [ACK 与持久化](standards/iot-ack-and-durability.md) | C-04 设备确认与接入持久化 | 已整理已提供要点 |
| [事务边界](standards/transaction-boundaries.md) | C-05 本地事务与消息投递 | 已整理已提供要点 |
| [模块边界](standards/module-boundaries.md) | C-06 模块映射与交互约束 | 已整理已提供要点 |
| [ACR-001 核对记录](acr/ACR-001-reconciliation.md) | 六项要点与技术基线的核对 | 待核对完整批准正文 |
| [adr/README.md](adr/README.md) | ADR-001 至 ADR-040 索引 | 40 份 Proposed，历史批准原文待核验 |
| [acr/README.md](acr/README.md) | ACR-001 索引 | 待导入已批准正文 |
| [工程工作流](../engineering/codex-workflow.md) | Codex 分支、验证和交付约定 | ENG-001A 建立 |
| [验证矩阵](../engineering/validation-matrix.md) | 变更触发条件、证据和待验证项 | ENG-001A 建立 |
| [Gateway 分析](../engineering/iot-gateway-analysis.md) | 上游实现与可靠性边界 | 已有源码核查，生产阻断待解决 |

ADR 索引链接到候选草案和证据矩阵。历史批准原文由任务负责人提供，核验后登记来源与对应路径。

[ENG-001B-02D 全局一致性审核](reviews/ENG-001B-02D-consistency-review.md)记录逐项结论、待决策事项、实施差距与依赖路线。

[ENG-001B-03 ACR-001 核对及审批准备](acr/ACR-001-reconciliation.md#当前核对eng-001b-03)记录当前证据、人工决策入口、生产门槛与工程启动条件。
