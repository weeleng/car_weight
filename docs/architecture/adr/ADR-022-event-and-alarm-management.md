# ADR-022 异常事件与告警管理

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`f378d3f33d4b96f50eac15e3c361d012bd3b9891`
- 编号、标题及设计要点来自 ENG-001B-02C-03，候选映射与历史批准原文待核验。

## 决策来源

ENG-001B-02C-03 提供运营异常及处置方案。已只读核查 [BpmProcessInstanceApiImpl.java](../../../yudao-module-bpm/src/main/java/cn/iocoder/yudao/module/bpm/api/task/BpmProcessInstanceApiImpl.java)、[BpmProcessInstanceEventPublisher.java](../../../yudao-module-bpm/src/main/java/cn/iocoder/yudao/module/bpm/framework/flowable/core/event/BpmProcessInstanceEventPublisher.java)、[pom.xml](../../../yudao-dependencies/pom.xml)、[pom.xml](../../../yudao-server/pom.xml)、[根 POM](../../../pom.xml)，框架有 Flowable 流程实例 API 与 Spring 事件发布，BPM 和 IoT 未纳入默认 Server 装配。另核查 [NotifySendServiceImpl.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/notify/NotifySendServiceImpl.java)、[IotSceneRuleMessageSubscriber.java](../../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/mq/consumer/rule/IotSceneRuleMessageSubscriber.java)、[IotAlertTriggerSceneRuleAction.java](../../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/service/rule/scene/action/IotAlertTriggerSceneRuleAction.java)、[IotAlertRecordServiceImpl.java](../../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/service/alert/IotAlertRecordServiceImpl.java)、[IotRabbitMQMessageBus.java](../../../yudao-module-iot/yudao-module-iot-core/src/main/java/cn/iocoder/yudao/module/iot/core/messagebus/core/rabbitmq/IotRabbitMQMessageBus.java)、[IotDeviceMessageSubscriber.java](../../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/mq/consumer/device/IotDeviceMessageSubscriber.java)，源码支持站内模板通知、IoT 场景规则与告警记录、RabbitMQ 发布确认和消费确认，以及设备消息的租户上下文。上述通用入口未验证为项目的告警状态机、可靠交付或端到端处置能力。

## 背景

断连、重复消息与定位漂移可能产生相似告警。系统应保留原始异常事实，并区分待核验异常与已确认违规。

## 决策

| 领域 | 拟覆盖异常 |
| --- | --- |
| 设备 | 离线、通信、称重传感器、RFID 读卡、GNSS、存储不足、未确认事件积压、配置或身份冲突 |
| 称重 | 超量程、异常负值、不完整、双桶归属不明、RFID 未识别或冲突、校准异常、重复身份冲突、任务未匹配 |
| 作业与线路 | 未按时开始、超时、站点未完成、计划外清运、偏离、越界、异常停留、调度冲突及授权失效 |
| 末端交付 | 未按计划到厂、拒收、记录缺失、地磅异常、重量差异、无法匹配批次及重复凭证 |

- 区分原始异常事实、异常事件、告警规则、实例、通知、工单、处理结果和审计。人工工单按需生成。排查偏离时考虑漂移、道路施工、合法绕行及任务变更。
- 规则表达类型、租户及项目、适用对象、触发条件、等级、持续时间、去重和抑制、恢复条件、通知对象、版本及生效时间。历史告警保留适用版本。
- 候选状态为待确认、已确认、处理中、待复核、已解决、已关闭和误报。分别记录触发、送达、人员确认、问题恢复及关闭。拟支持确认、分配、说明、附件、处理日志、复核、关闭和重开。
- 重投、持续故障、高频抖动、聚合、通知限频和恢复分别处理。去重保留原始事实，通知失败不得回滚已持久化事件。
- 领域模块识别事实，`sanitation-event` 经公开 API 或事件契约管理异常和告警。可靠异步遵循 C-05 的 Outbox/Inbox。修正称重、补录主体或调整账单调用各领域的授权操作。

## 适用范围

环卫项目及必要业务安全场景的异常、告警、通知与处理审计。

## 约束与禁止事项

禁止把无设备数据当作无作业、把偏离自动当作违规倾倒，或在线恢复自动关闭全部历史异常。禁止覆盖原始重量及在各模块重复硬编码阈值。INGEST 事务禁止同步等待通知、工单或外部消息服务。

## 备选方案及取舍

普通告警用业务状态机，复杂审批可评估 Flowable。为每条设备告警创建流程会增加负载和处置噪声。通知与事件持久化分离，便于独立重试与限频。

## 与其他 ADR/ACR 的关系

围栏与线路见 [ADR-011](ADR-011-geofencing.md)、[ADR-019](ADR-019-collection-route-management.md)，重量见 [ADR-015](ADR-015-weighing-model.md)，作业与交付见 [ADR-021](ADR-021-collection-operation-execution.md)、[ADR-023](ADR-023-disposal-facility-and-delivery.md)。计费调整遵循 [ADR-024](ADR-024-contract-and-billing.md)。遵循 C-02、C-03、C-04、[C-05](../standards/transaction-boundaries.md)、C-06，出处见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。

## 验证要求

后续覆盖重复告警、持续故障、抖动、风暴、通知失败、版本变更、恢复、误报、迟到、租户项目隔离、操作越权和原始称重保护。

## 待确认事项

离线与重量阈值、告警触发和恢复、末端差异、责任、规则去重键、最终状态机、审批及通知策略待确认。

## 批准记录

草案待人工审查。历史批准原文、版本、批准人、日期及证据待取得。本次仅核查源码和文档，未执行运行验收。
