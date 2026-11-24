# ADR-012 IoT 接入架构

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 编号与标题为 ENG-001B-02B-01 提供的候选映射，原始 ADR 正文尚未核验。历史版本与完整批准原文待取得。
- 本次起始基线：`3f831747a7fe0c72b8906366caa26f0145884788`

## 决策来源

ENG-001B-02B-01 提供拟议接入链路。[C-04](../standards/iot-ack-and-durability.md)与[C-05](../standards/transaction-boundaries.md)规定提交及 ACK 边界。[Gateway 分析](../../engineering/iot-gateway-analysis.md)和[历史验证状态](../../engineering/validation-status.md)记录当前能力与限制。

## 背景

设备连接与正式业务处理存在不同的失败和重试时点。历史证据覆盖独立 JAR、最小启动及部分定向测试。生产可靠性仍待端到端验证，RabbitMQ 与 SQLite 双暂存服务冲突仍为生产启用阻断。

## 决策

IoT Gateway 独立部署，负责连接、协议解析与校验、事件接收、持久化确认和消息投递。拟议链路如下。

1. 设备上报，Gateway 解析与校验。
2. INGEST 原始事件与对应 Outbox 在同一本地事务成功提交。
3. Gateway 在提交成功后发送设备 L2 ACK。
4. Outbox Relay 将已提交记录投递到 RabbitMQ，并处理 Publisher Confirm。
5. BUSINESS 消费者执行 Inbox 幂等处理，Inbox、正式业务记录与业务 Outbox 在同一本地事务提交。
6. BUSINESS 事务成功提交后确认消费。

L2 ACK 与 Relay 的共同前置条件是 INGEST 提交。Relay 可靠投递独立于设备是否实际收到 ACK。

## 适用范围

Gateway、INGEST、RabbitMQ 与 BUSINESS 消费衔接及相应的故障恢复。

## 约束与禁止事项

- 成功 L2 ACK 仅确认 INGEST 原始事件及 Outbox 提交。正式称重、责任主体归属及计费有各自的状态。RabbitMQ Publisher Confirm 和消费确认均不能替代设备 L2 ACK。
- ACK 丢失时，设备重传须按事件身份幂等处理。RabbitMQ 按至少一次语义设计，消费端处理重复投递。
- Redis 缓存和 SQLite 暂存不能充当 INGEST 提交证据。受双暂存冲突影响的生产配置仍不可启用。
- RPO=0 声明须先验证相应故障域、数据库持久化和高可用条件。现有 Gateway 的最小启动证据只覆盖当时配置。

## 备选方案及取舍

接收即 ACK 或在本地暂存后 ACK 可以减少等待，但不满足 C-04 的提交条件。等待正式业务完成再确认会把设备重试与计费链路耦合。以 INGEST 本地提交作为确认点，将接入存证和后续业务处理分开。

## 与其他 ADR/ACR 的关系

适用 [C-02](../standards/time-conventions.md)、[C-03](../standards/weight-conventions.md)、C-04、C-05 和 [C-06](../standards/module-boundaries.md)。拟与 ADR-001 的部署边界和 ADR-006 的事务边界配套。[ACR-001 核对记录](../acr/ACR-001-reconciliation.md)提供约定出处。

## 验证要求

后续测试提交前失败、提交后 ACK 丢失、设备重传、发布确认丢失、重复消费及业务回滚。使用真实 RabbitMQ 验证确认时点和失败恢复，覆盖断网补传及数据库故障域，专项验证暂存冲突解除情况。本次未执行这些运行验证。

## 待确认事项

连接协议、事件身份完整字段、L2 ACK 编码、重试参数、连接鉴权、断网补传和故障恢复方案待确认。

## 批准记录

- 本草案审批：待审查。
- 历史批准版本、批准人、日期和可核验的批准证据：待取得。
