# ADR-037 高可用与灾难恢复

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`699e700603d119a2e43c7ec877b8ece5029bf9ff`
- 编号、标题及设计取自 ENG-001B-02C-06，候选映射与历史批准原文待核验。

## 决策来源

ENG-001B-02C-06 提供数据分级、恢复目标及候选拓扑。[ADR-035](ADR-035-capacity-and-performance.md)给出相同候选数字。已只读核查 [docker-compose.yml](../../../script/docker-compose/docker-compose.yml)、[ruoyi-vue-pro.sql](../../../sql/mysql/ruoyi-vue-pro.sql)、[IotGatewayServerApplication.java](../../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/IotGatewayServerApplication.java)。Compose 为单机服务示例，初始化脚本及独立入口无法证明集群、复制或灾备。

## 背景

SaaS、基础私有化与高可用私有化故障域、恢复能力及成本不同。目标必须绑定数据范围与实测条件。

## 决策

| 候选等级 | 保护内容 | 候选目标 |
| --- | --- | --- |
| S0 | 已返回 L2 成功 ACK 的原始称重、接入 Outbox、恢复所需身份和持久化证据 | 明确故障域及可靠持久化条件下 RPO=0，RTO≤30 分钟 |
| S1 | 正式称重、合同计费、账单发票、关键主数据、变更和审计 | RPO≤5 分钟，RTO≤1 小时 |

按业务影响分级缓存、临时结果、日志和轨迹，可重算结果仍依赖其原始事实。RPO 以故障点和最后可恢复数据衡量，RTO 以故障开始到经核验恢复业务衡量，具体起止与故障场景待定。

- 分别评估应用、Gateway、容器、OS、服务器、磁盘、数据库主节点、网络分区、机房、区域、误操作、逻辑损坏和恶意破坏。C-04 的 INGEST 原始事件与 Outbox 本地事务提交仍是成功 ACK 必要条件。声称特定故障域 RPO=0 前，验证 redo/binlog、存储刷盘、复制确认、切换及已确认事务可访问性。
- MySQL 比较单机、异步、半同步、InnoDB Cluster/Group Replication、具备明确保证的托管方案。核查提交延迟、丢失窗口、网络分区、可写性、备份、成本及运维。异步复制有丢失窗口，半同步的确认、超时回退和切换须实测。采用仲裁、选举、Fencing、旧主隔离、受控重入和写入口切换的候选策略，禁止冲突双写。
- RabbitMQ 评估单节点、集群、Quorum Queue、持久化、发布消费确认及节点恢复。Redis 评估单节点、Sentinel、Cluster 或托管，验证缓存、权限、临时状态、锁及切换。数据库唯一约束持续生效。双暂存仍为生产启用阻断。
- 模块化单体可多实例，核查无状态、负载均衡、健康、优雅停机、会话、定时任务、文件、缓存、总连接池和配置一致性。Gateway 独立多实例候选，检查连接分布、迁移、重连、会话、暂存故障、补传公平和原始身份不变。设备本地 16 MiB 环形方案、Android/PDA 缓存及服务器是三层，分别验证离线、满载、断电、损坏、换板、ACK 丢失和升级，禁止静默覆盖。
- 备份覆盖 INGEST、BUSINESS、TRACK、Outbox/Inbox、审计、附件、配置和受控密钥恢复材料。评估全量增量、binlog、时间点、异地、不可变、加密和完整性校验。复制不能替代备份。
- 流程候选为发现确认、隔离定界、保护数据及日志、恢复库和服务、核对原始事件/Outbox、Inbox/业务、账单和第三方状态、受控补传、验证一致性、恢复并记录实际 RPO/RTO 及复盘。逻辑库可能处于不同恢复点，不假定跨库原子恢复。对开票、红冲、支付、监管推送和交付按稳定业务 ID 查询和对账后再决定行动。

## 适用范围

三类部署的持久化、切换、备份和灾后恢复。

## 约束与禁止事项

禁止向基础单机通用承诺高可用目标、将单机提交等同 RPO=0、以 MQ、Redis、Gateway 内存或终端缓存替代可靠存储，禁止无条件重复开票、扣费或推送。禁止跨模块访问内部实现。

## 备选方案及取舍

提高复制确认可缩小数据丢失窗口，但影响提交延迟和分区写入可用性。按故障域和成本选择，保留独立备份及恢复对账。

## 与其他 ADR/ACR 的关系

关联 [ADR-006](ADR-006-database-architecture.md)、[ADR-012](ADR-012-iot-ingestion.md)、[ADR-013](ADR-013-vwcp-protocol.md)、[ADR-024](ADR-024-contract-and-billing.md)、[ADR-027](ADR-027-mobile-and-vehicle-terminals.md)、[ADR-028](ADR-028-third-party-integration.md)、[ADR-032](ADR-032-security-audit-and-traceability.md)、[ADR-033](ADR-033-cryptography-and-iot-security.md)及 ADR-035。遵循 [C-04](../standards/iot-ack-and-durability.md)、[C-05](../standards/transaction-boundaries.md)、[C-06](../standards/module-boundaries.md)。来源见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。

## 验证要求

实际恢复演练覆盖应用和 Gateway 重启、主库故障、网络分区、主从切换、MQ 节点及 Redis 故障、磁盘故障、误删、时间点和跨库恢复、已确认事件、Outbox/Inbox、财务和第三方对账、集中补传、配置与密钥。记录环境、规模、故障、备份和恢复点、耗时、数据完整性、正式和财务记录、外部核对及实测 RPO/RTO。

## 待确认事项

分级、适用故障域、复制确认和 Fencing、部署拓扑、备份频率与保留、操作手册及测量方案待确认。

## 批准记录

草案待人工审查，历史批准原文、版本、批准人、日期及证据待取得。本次仅核查源码、配置与文档，未执行运行验收。
