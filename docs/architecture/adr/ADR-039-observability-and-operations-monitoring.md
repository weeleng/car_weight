# ADR-039 系统可观测性与运维监控

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`699e700603d119a2e43c7ec877b8ece5029bf9ff`
- 编号、标题及设计取自 ENG-001B-02C-06，候选映射与历史批准原文待核验。

## 决策来源

ENG-001B-02C-06 提供候选路线。已只读核查 [pom.xml](../../../yudao-framework/yudao-spring-boot-starter-monitor/pom.xml)、[logback-spring.xml](../../../yudao-server/src/main/resources/logback-spring.xml)、[application-local.yaml](../../../yudao-server/src/main/resources/application-local.yaml)、[IotRabbitMQMessageBusConfirmTest.java](../../../yudao-module-iot/yudao-module-iot-core/src/test/java/cn/iocoder/yudao/module/iot/core/messagebus/core/rabbitmq/IotRabbitMQMessageBusConfirmTest.java)、[IotDeviceMessageSpoolServiceImplTest.java](../../../yudao-module-iot/yudao-module-iot-gateway/src/test/java/cn/iocoder/yudao/module/iot/gateway/service/device/spool/IotDeviceMessageSpoolServiceImplTest.java)、[application-unit-test.yaml](../../../yudao-module-system/src/test/resources/application-unit-test.yaml)。仓库存在 Actuator 暴露配置、Micrometer Prometheus、SkyWalking TraceId/日志 appender，local 声明管理端点暴露配置与 Druid 池。既有测试覆盖 confirm 与暂存入口，System 测试配置有 H2 MySQL 兼容模式及 Redis。配置存在不代表已安全启用、部署监控栈或达到生产可观测性。

## 背景

需要从原始设备事件追溯正式称重、账单和外部结果。Metrics、Logs、Traces 与 Audit 各自承担聚合、运行记录、链路关联和可靠审计。

## 决策

评估 Spring Boot Actuator、Micrometer、Prometheus、Grafana、Loki、OpenTelemetry 与 Alertmanager 或等价方案。

| 范围 | 候选指标 |
| --- | --- |
| 基础设施 | CPU、内存、磁盘容量/IOPS/延迟、网络吞吐和错误、容器进程、对时、文件描述符及连接 |
| Java/Web | 堆/非堆、GC 次数与暂停、线程、池队列与拒绝、HTTP 量和错误、P50/P95/P99、连接池、慢 SQL、定时与异步失败 |
| 三逻辑库 | 连接、活跃事务、提交、锁等待死锁、慢查询、容量索引、redo/binlog、复制、备份及演练结果 |
| Redis | 使用量、命中、延时、连接、淘汰、过期、主从与异常、回源、权限失效 |
| RabbitMQ | 发布和消费速率、确认延时、发布及路由失败、待消费/未确认、重复、消费者、死信、资源及节点 |
| Gateway | 连接与断开、认证成功及失败率、接入、CRC、认证与解析失败、身份冲突和重放、持久化、暂存量及容量、延迟、重连和补传 |
| L2 ACK | 接收、通过鉴权、持久化、INGEST 失败、新事件/重复成功 ACK、身份冲突、发送失败、重试、控制板确认及分段延迟 |
| Outbox/Inbox | 待发与最旧年龄、投递延迟、重试失败、Inbox 重复、事务失败、消费延迟、业务积压 |
| 业务 | 有效、原始和正式称重、未归属、RFID、重量、校准、任务、定位、交付差异、待计费、账单、发票及接口积压 |

- 分别记录服务器生成 ACK、终端收到和控制板确认，最后一项仅在协议支持时采集。在线连接不证明称重健康，重复设备消息不计为新增称重。Outbox P95≤2 秒仍为 ADR-035 候选。
- 核对确认事件与 INGEST、原始事件与 Outbox、投递与 Inbox、正式称重与计费依据、账单与发票。考虑处理延迟、重复、拒绝隔离及审核，禁止按不同阶段总量相减直接判定丢失。
- 结构化日志记录时间、服务、版本、环境、级别、追踪 ID、业务关联、消息及原始身份、必要租户项目、结果和错误。追踪 API、Gateway、INGEST、Relay、MQ 消费、BUSINESS、外调和 TRACK，异步显式传播。长离线及重试按稳定事件身份关联多个 Trace。
- 指标控制基数。UID、RFID、用户、车辆、商户、事件、订单与请求 ID 不作默认常规标签。对象级排查走受控日志、追踪或业务查询，评估租户项目标签成本。
- 候选看板覆盖整体、Java、MySQL、Redis、MQ、接入、ACK、Outbox/Inbox、离线、一致性、外调、安全、备份恢复和趋势。与政府和企业统计报表分离。
- 告警分基础设施、运行、可靠性、安全、业务与第三方；与 ADR-022 可关联而状态机独立。定义级别、持续窗口、阈值、聚合去重、抑制、恢复、责任与升级。高优先候选为持续 INGEST 失败、已确认数据不一致、Outbox 长积压、复制、磁盘、MQ 不可用、Gateway 大范围离线、认证失败和关键备份失败。

## 适用范围

指标、日志、追踪、告警、数据核对与监控访问。

## 约束与禁止事项

遵循 C-02 与脱敏，禁止日志保存完整 Token、密码、密钥或不必要个人信息。Actuator、采集、Grafana 和日志查询须认证、租户隔离与网络限制，禁止默认公网暴露。采集故障须限资源并降级，不得阻断已鉴权原始事件持久化或将必须可靠保存的审计降级为普通日志。跨模块通过公开契约。

## 备选方案及取舍

全量追踪利于定位但增加成本和隐私风险。按采样、聚合与受控查询平衡，独立审计坚持事务要求。

## 与其他 ADR/ACR 的关系

关联 [ADR-012](ADR-012-iot-ingestion.md)、[ADR-022](ADR-022-event-and-alarm-management.md)、[ADR-025](ADR-025-statistics-and-reporting.md)、[ADR-030](ADR-030-personal-information-and-data-protection.md)、[ADR-032](ADR-032-security-audit-and-traceability.md)、[ADR-035](ADR-035-capacity-and-performance.md)、[ADR-036](ADR-036-cache-and-asynchronous-processing.md)、[ADR-037](ADR-037-high-availability-and-disaster-recovery.md)、[ADR-038](ADR-038-deployment-and-environment-management.md)。遵循 C-02、C-04、C-05、[C-06](../standards/module-boundaries.md)。来源见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。

## 验证要求

覆盖应用 Gateway 故障、连接耗尽、Redis 与 MQ、Outbox、集中补传、ACK 失败、重复与一致性异常、脱敏、跨服务异步追踪、标签基数、去重恢复、采集降级、备份和认证告警。

## 待确认事项

实际选型和启用范围、权限及端口、指标采样基数、告警阈值和升级、审计和日志保留、设备最终确认的协议可见性待验。

## 批准记录

草案待人工审查，历史批准原文、版本、批准人、日期及证据待取得。本次仅核查源码、配置与文档，未执行运行验收。
