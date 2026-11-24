# ADR-035 系统容量与性能指标

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`b3fd4cea75c2b4fe305271eda970df5688765d72`
- 编号、标题、设计及量化指标取自 ENG-001B-02C-05，候选映射与历史批准原文待核验。

## 决策来源

ENG-001B-02C-05 提供候选数字。已只读核查 [application-local.yaml](../../../yudao-server/src/main/resources/application-local.yaml)、[YudaoAsyncAutoConfiguration.java](../../../yudao-framework/yudao-spring-boot-starter-job/src/main/java/cn/iocoder/yudao/framework/quartz/config/YudaoAsyncAutoConfiguration.java)、[SchedulerManager.java](../../../yudao-framework/yudao-spring-boot-starter-job/src/main/java/cn/iocoder/yudao/framework/quartz/core/scheduler/SchedulerManager.java)、[IotRabbitMQMessageBus.java](../../../yudao-module-iot/yudao-module-iot-core/src/main/java/cn/iocoder/yudao/module/iot/core/messagebus/core/rabbitmq/IotRabbitMQMessageBus.java)、[IotDeviceMessageSpoolServiceImpl.java](../../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/spool/IotDeviceMessageSpoolServiceImpl.java)、[IotDeviceMessageSpoolStore.java](../../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/message/spool/IotDeviceMessageSpoolStore.java)、[pom.xml](../../../yudao-framework/yudao-spring-boot-starter-monitor/pom.xml)、[IotRabbitMQMessageBusConfirmTest.java](../../../yudao-module-iot/yudao-module-iot-core/src/test/java/cn/iocoder/yudao/module/iot/core/messagebus/core/rabbitmq/IotRabbitMQMessageBusConfirmTest.java)、[IotDeviceMessageSpoolServiceImplTest.java](../../../yudao-module-iot/yudao-module-iot-gateway/src/test/java/cn/iocoder/yudao/module/iot/gateway/service/device/spool/IotDeviceMessageSpoolServiceImplTest.java)、[OAuth2TokenServiceImplTest.java](../../../yudao-module-system/src/test/java/cn/iocoder/yudao/module/system/service/oauth2/OAuth2TokenServiceImplTest.java)。local 有 Druid 连接池及 Actuator 暴露配置，异步装配包装适用执行器，Quartz 在 local 排除自动装配。未发现当前 local 配置显式定制 Web 容器线程上限，实际 Web/异步线程配置需测量。监控 starter 声明 Micrometer Prometheus 依赖，RabbitMQ 具备确认路径，SQLite 代码配置 WAL 和 synchronous=FULL。现有定向测试源覆盖 Token、RabbitMQ 确认和暂存逻辑，未查到项目目标下的完整负载基准，本次未执行这些测试。只读版本检查为 OpenJDK 21.0.13、Maven 3.9.12。

## 背景

容量验收应同时衡量接入可靠性、业务、地图、统计、第三方集成和资源，并区分负载条件。下表为拟议基线，实测结果和生产 SLA 待确定。

## 决策

| 项目 | 全部量化候选基线 |
| --- | --- |
| P0 对象规模 | 清运车辆 300 辆；Android/PDA 终端 1000 台；IoT 设备 3000 台；责任主体 100000 个；服务点 200000 个；业务空间点位 300000 个 |
| P0 日业务量 | 作业任务 3000 个/日；称重记录 150000 条/日 |
| 用户并发 | 管理端 200；小程序 500 |
| IoT 接入 | 持续 300 条消息/秒；突发 1000 条消息/秒 |
| 第三方集成 | 持续 500 条/秒；突发 2000 条/秒；外部 API 200 QPS |
| P95 接口目标 | 常规读 ≤500 ms；常规写 ≤800 ms；地图常规查询 ≤1.5 秒；Outbox 正常投递 ≤2 秒 |
| 数据量推算 | 若正式称重 150000 条/日，则 30 日约 450 万条、365 日约 5475 万条；IoT 300 条/秒连续 24 小时约 2592 万条/日 |
| 压测阶段 | 30 分钟基础；8 小时持续；24 小时稳定性；168 小时长期运行 |
| 高可用候选 | S0 已确认称重在明确故障域与持久化条件下 RPO=0、RTO≤30 分钟；S1 业务数据 RPO≤5 分钟、RTO≤1 小时 |

- 对象、终端与设备分别计数，日称重按原始、正式、组合及子桶分层。业务空间点位单独计数，与日轨迹点数分开。并发用户区分在线、活跃、请求并发、到达率、操作与刷新频率、WebSocket/SSE 连接，200+500 用户不能直接折算为 700 QPS。
- IoT 负载拆分心跳、GNSS、称重、RFID、状态、配置及确认和补传，明确尺寸、比例、设备在线数、重复率与峰值持续时长。集成区分内部生成、消费、外部请求、单请求批量、限流及业务确认，按批量或有界缓冲处理速率差；长期输入大于输出须调整，不承诺无限积压。
- P95 需定义起止、样本、并发、数据量、缓存、网络、错误与超时比例。大报表、全量轨迹及第三方长期等待分开计量。地图分别测后端、厂商及渲染；Outbox 从本地提交到可靠发布确认，另测 L2 ACK 和业务处理。
- ACK 分段测上传、Gateway 鉴权、校验、INGEST 事务、服务端生成、返回终端、转发控制板到设备确认。本次不设置最终 L2 ACK SLA；需实机、网络模拟和服务端压测，成功不得早于提交。
- 按在线比例、频率、报文与记录大小、索引、Outbox/Inbox、审计、轨迹、附件报表、副本、备份和留存估算。每日轨迹点数 = 在线定位设备数 × 每日有效运行秒数 ÷ 平均上报间隔秒数，区分控制板、车载机、PDA、主备、无效、重复及补传。
- 数据库覆盖 INGEST/Outbox、BUSINESS/Inbox、并发投递、去重、历史称重、RFID 有效期、轨迹、租户项目过滤、空间、统计、财务及审计。分析索引、批写、连接池、锁、慢 SQL、事务、分区、扫描和归档。
- 消息监控发布和消费速率、积压量与最旧年龄、Outbox 待发、重试与死信、重复与失败、处理延迟及恢复清积压时长。测试单车长期离线及多车/终端集中恢复、实时与补传并存、重复、ACK 丢失及设备/服务重启，评估公平、背压与限流。
- 资源采集 CPU、JVM 堆、GC、线程与连接池、磁盘容量/IOPS/吞吐、事务日志、Redis 与 RabbitMQ 内存、RabbitMQ 磁盘、带宽、文件容量和 Gateway 连接。各阶段定义数据、业务和 IoT 比例、负载、突发时长、故障、资源、一致性和阈值，分正常、峰值、长期和恢复场景。
- 工具候选为 JMeter、k6、Gatling、VWCP 和 Android/PDA 模拟器、RabbitMQ 与 MySQL 分析工具、Prometheus 及 Grafana。

## 适用范围

平台、IoT、统计、第三方、资源及故障恢复的容量设计和验收。

## 约束与禁止事项

禁止为性能取消唯一约束、事务、认证、完整性、租户过滤、项目授权或业务审计。正式测试启用 TLS、用户和设备认证、完整性、授权、审计及必要日志。禁止提前 ACK、静默丢弃已鉴权有效事件或清除未处理消息。168 小时不隐含全程 1000 条/秒。禁止承诺未验证服务器规格或无条件 RPO=0，双暂存冲突仍为生产启用阻断。

## 备选方案及取舍

平均请求量便于起算，分离常态、峰值、补传和故障恢复才能衡量长期稳定性。以资源和数据正确性共同判定扩容。

## 与其他 ADR/ACR 的关系

关联 [ADR-006](ADR-006-database-architecture.md)、[ADR-012](ADR-012-iot-ingestion.md)、[ADR-014](ADR-014-gnss-and-tracking.md)、[ADR-025](ADR-025-statistics-and-reporting.md)、[ADR-028](ADR-028-third-party-integration.md)、[ADR-029](ADR-029-mlps-and-security-compliance.md)、[ADR-032](ADR-032-security-audit-and-traceability.md)、[ADR-033](ADR-033-cryptography-and-iot-security.md)。恢复关联候选 [ADR-037](ADR-037-high-availability-and-disaster-recovery.md)，正文为 Proposed，待批准。遵循 C-01 至 C-06，证据见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。

## 验证要求

启用必要安全机制后，验证表中规模、并发、速率、P95、长期稳定和恢复，覆盖补传、积压、重复及同身份异载荷。核对已确认事件无未解释丢失、身份、正式称重及计费防重、任务站点统计、Outbox/Inbox、事务失败无成功 ACK、重启恢复及审计完整。

## 待确认事项

数据定义、消息结构、持续窗口、定位频率及保留、测试工具、环境规格、安全配置、误差与验收阈值、S0/S1 故障域及复制备份设计待确认。

## 批准记录

草案待人工审查，历史批准原文、版本、批准人、日期及证据待取得。本次只读核查源码、配置和本地工具版本，未运行安全、合规、功能或性能验收。
