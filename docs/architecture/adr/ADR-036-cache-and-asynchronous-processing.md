# ADR-036 缓存与异步处理架构

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`699e700603d119a2e43c7ec877b8ece5029bf9ff`
- 编号、标题及设计取自 ENG-001B-02C-06，候选映射与历史批准原文待核验。

## 决策来源

ENG-001B-02C-06 提供候选设计。已只读核查 [YudaoRedisAutoConfiguration.java](../../../yudao-framework/yudao-spring-boot-starter-redis/src/main/java/cn/iocoder/yudao/framework/redis/config/YudaoRedisAutoConfiguration.java)、[TenantRedisCacheManager.java](../../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/redis/TenantRedisCacheManager.java)、[OAuth2AccessTokenRedisDAO.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/redis/oauth2/OAuth2AccessTokenRedisDAO.java)、[DictFrameworkUtils.java](../../../yudao-framework/yudao-spring-boot-starter-excel/src/main/java/cn/iocoder/yudao/framework/dict/core/DictFrameworkUtils.java)、[CacheUtils.java](../../../yudao-framework/yudao-common/src/main/java/cn/iocoder/yudao/framework/common/util/cache/CacheUtils.java)、[pom.xml](../../../yudao-dependencies/pom.xml)，仓库有 RedisTemplate、适用缓存的租户后缀、Token 查询缓存、字典本地异步刷新，以及 Lock4j/Redisson 依赖管理。依赖声明不能证明所有锁与失效路径均已启用。

已核查 [IotRabbitMQMessageBus.java](../../../yudao-module-iot/yudao-module-iot-core/src/main/java/cn/iocoder/yudao/module/iot/core/messagebus/core/rabbitmq/IotRabbitMQMessageBus.java)、[IotDeviceMessageSpoolService.java](../../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/message/spool/IotDeviceMessageSpoolService.java)、[IotDeviceMessageSpoolServiceImpl.java](../../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/spool/IotDeviceMessageSpoolServiceImpl.java)、[IotDeviceMessageSpoolStore.java](../../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/message/spool/IotDeviceMessageSpoolStore.java)、[YudaoAsyncAutoConfiguration.java](../../../yudao-framework/yudao-spring-boot-starter-job/src/main/java/cn/iocoder/yudao/framework/quartz/config/YudaoAsyncAutoConfiguration.java)、[SchedulerManager.java](../../../yudao-framework/yudao-spring-boot-starter-job/src/main/java/cn/iocoder/yudao/framework/quartz/core/scheduler/SchedulerManager.java)及 [application.yaml](../../../yudao-module-iot/yudao-module-iot-gateway/src/main/resources/application.yaml)、[application-local.yaml](../../../yudao-server/src/main/resources/application-local.yaml)。RabbitMQ 的确认发布处理退回和确认超时，消费路径手动确认，特定确认异常可按实现重新入队。示例 Gateway 配置选 Redis 总线，SQLite 暂存关闭。代码存在两套 SQLite 服务，旧实现仅接受 Redis，两者共用启用条件与文件配置。异步执行器有 TTL 装饰，local 排除 Quartz 自动装配。以上为源码和配置证据，未做生产验证。

## 背景

缓存与异步用于缩短响应和隔离故障。原始事件、正式业务和接入确认要求独立可靠边界。

## 决策

- Redis 候选范围为令牌、字典基础数据、项目配置、设备在线、最新位置、地图热点、短期统计、限流、短期幂等辅助、锁及临时会话。分类为可重建查询、身份权限、设备状态、业务配置、计算结果及禁止仅存缓存的数据。分别记录来源、键、租户项目及授权范围、TTL、更新失效、一致性及降级。
- 查询优先评估 Cache Aside，提交后失效，验证并发回填、多节点、失效失败、穿透、击穿、雪崩、热点和过期。计价、RFID、设备配置和权限使用版本、有效期及可靠失效。监管与普通租户缓存不得无条件共享。权限变更检查跨节点撤销。线程与消费者建立可信租户上下文并清理。
- Redis 锁仅协调并发。设备去重、正式称重、账单、开票、回调和任务生成依稳定业务身份、数据库唯一约束及事务。测试锁过期、续期失败、暂停及 Redis 故障。区分普通回源、认证和权限失败、位置缺失、锁与限流降级，授权不得默认放行。
- RabbitMQ 按至少一次与业务幂等设计，分别验证持久消息与队列、Publisher Confirms、路由、手动消费确认、并发与 Prefetch、重试、死信、版本、追溯与背压。Outbox Relay 只读取已提交记录，核对确认和路由后可靠更新投递状态。BUSINESS 在本地事务提交 Inbox、正式记录及必要业务 Outbox 后才确认消费。

### 故障窗口

| 故障窗口 | 拟议处理 |
| --- | --- |
| INGEST 原始事件和 Outbox 提交前退出 | 禁止成功 L2 ACK，由设备重试 |
| 同事务提交后、ACK 前退出 | 保留原记录，以原设备身份判定合法重试 |
| 读取 Outbox 后、发布前退出 | 待发记录可恢复投递 |
| 发布后确认丢失，或确认后投递状态写入失败 | 保留不确定结果，允许重复发布，由 Inbox 防重 |
| BUSINESS 提交前退出 | 回滚业务和 Inbox，重投后再处理 |
| BUSINESS 提交后消费 ACK 丢失 | 用已提交 Inbox 识别重复，无重复业务副作用 |

- 消息元数据拟含消息 ID、类型、版本、模块、租户、项目、业务对象、原设备身份、发生及创建时间、关联与追踪 ID、必要顺序。消息 ID 区别于硬件 UID、Epoch、Seq，重试保持业务幂等身份。
- 不要求全局有序。按设备事件、车辆、任务、RFID 绑定、账单和第三方请求评估局部顺序，消费者处理重复、迟到、乱序和版本冲突。临时网络、数据库、第三方限流或超时与前置缺失、格式错误、认证失败、身份冲突分类处理。候选为退避、抖动、上限、死信、人工核验、授权重放及审计。
- 按接入、正式称重、外调、统计、导出、告警、审计归档隔离执行资源。明确线程数、队列上限、拒绝、超时、上下文、异常与指标。Quartz 只用于周期调度，验证集群运行、Misfire、重复触发、并发、超时、幂等、租户和审计。
- 监测生产消费速率、积压与最旧年龄、Outbox、重试死信、失败率及线程排队。持续超载按扩容、批量、限流、背压和优先级解决。

## 适用范围

业务缓存、可靠异步、定时任务及 Gateway 本地缓冲。

## 约束与禁止事项

MySQL 为正式业务和接入事务权威存储。禁止仅用 Redis 保存原始事件、正式称重、账单、发票或关键审计，禁止统一 TTL、以当前缓存改写历史或用锁代替幂等。SQLite 仅是待验证本地缓冲。INGEST 原始事件及 Outbox 同一本地 MySQL 事务成功提交是 L2 成功 ACK 前提。禁止用暂存或发布确认释放控制板未确认事件、跨库原子事务、提前标记投递成功、承诺端到端 Exactly Once、无限重试、无界队列或删除未处理的有效称重事件。人工重放保留原业务身份，死信须核验。RabbitMQ 与 SQLite 双暂存冲突仍为生产启用阻断。

## 备选方案及取舍

同步外调简化反馈却扩大故障域。缓存旁路可先复用数据库，可靠异步增加对账与恢复成本但保护接入时延。

## 与其他 ADR/ACR 的关系

关联 [ADR-003](ADR-003-saas-tenancy.md)、[ADR-007](ADR-007-dictionary-and-coding.md)、[ADR-012](ADR-012-iot-ingestion.md)、[ADR-013](ADR-013-vwcp-protocol.md)、[ADR-024](ADR-024-contract-and-billing.md)、[ADR-028](ADR-028-third-party-integration.md)、[ADR-031](ADR-031-authentication-and-access-security.md)、[ADR-035](ADR-035-capacity-and-performance.md)。遵循 C-02、[C-04](../standards/iot-ack-and-durability.md)、[C-05](../standards/transaction-boundaries.md)、[C-06](../standards/module-boundaries.md)。来源见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。

## 验证要求

覆盖 Redis 故障、失效、越权、撤销、锁过期、重复事件、RabbitMQ 不可用、路由失败和确认丢失、Outbox 全窗口、Inbox 回滚及重投、死信重放、乱序、离线突发、执行器耗尽、Quartz 重复触发、SQLite 双服务专项。

## 待确认事项

缓存分级 TTL、版本和失效方式、锁与幂等键、队列及路由策略、重试次数、死信、顺序、线程池和调度容量、暂存修复和运行验收待确认。

## 批准记录

草案待人工审查，历史批准原文、版本、批准人、日期及证据待取得。本次仅核查源码、配置与文档，未执行运行验收。
