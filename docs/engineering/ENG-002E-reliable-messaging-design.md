# ENG-002E-01 Outbox / Inbox 可靠消息与事务一致性详细设计

状态为候选设计，待人工审查。工作包 E。本次交付范围为详细设计和隔离探针。DEC-006 与 BLOCK-003 保持开放。

## 1. 基线、依据与范围

目标仓库 `weeleng/car_weight`，目标 `master-jdk17`，任务分支 `engineering/eng-002e`。实际起点 `d9230a63521df3f4f8f53a061f6c070954fa8d7f` 与指定值相符。执行 `git status --short --branch`、`git ls-remote origin refs/heads/master-jdk17 refs/heads/engineering/eng-002e`、`git fetch --no-tags origin refs/heads/master-jdk17:refs/remotes/origin/master-jdk17` 和 `git rev-parse HEAD`。工作区原有未跟踪报告保留。ENG-002D 已合并，**ENG-002D-02R 尚未实施**，身份绑定及导出生命周期问题继续独立跟踪。

指定 Actions 网页 HTTP 200，运行对应 PR #18 合并提交 `d9230a6`，状态 **Success**，Job `140260885346`。工作流通过 JDK 21 与 Maven 3.9.12 执行默认 `mvn -B -ntp -C package --file pom.xml -Dmaven.test.skip=true`。这是主分支历史构建证据，测试被跳过，本轮未触发远程 CI。[Actions 运行](https://github.com/weeleng/car_weight/actions/runs/45923957432) · [Maven 工作流](../../.github/workflows/maven.yml)

| 已核对材料 | 用途 |
| --- | --- |
| [AGENTS](../../AGENTS.md)、[架构目录](../architecture/README.md)、[工程工作流](codex-workflow.md)、[验证矩阵](validation-matrix.md) | 分支、审批、提交和证据 |
| [C-01](../architecture/standards/technology-baseline.md)、[C-02](../architecture/standards/time-conventions.md)、[C-04](../architecture/standards/iot-ack-and-durability.md)、[C-05](../architecture/standards/transaction-boundaries.md)、[C-06](../architecture/standards/module-boundaries.md) | 工具链、UTC、L2 成功前提、本地事务和模块约束 |
| [实施入口](../architecture/reviews/implementation-entry-gates.md)、[审批包](../architecture/reviews/decision-approval-packets.md#dec-006)、[开放决策](../architecture/reviews/open-decision-register.md)、[差距](../architecture/reviews/implementation-gap-register.md)、[生产门槛](../architecture/reviews/production-gate-approval-matrix.md#block-003) | DEC-006 的原子性、唯一键与恢复证据，BLOCK-003 的生产签署及跨工作包条件 |
| [ENG-002B 设计](ENG-002B-module-skeleton-design.md)、[ENG-002B 报告](ENG-002B-module-skeleton-report.md)、[环卫骨架](../../sanitation-modules/README.md) | 独立 Gateway、模块白名单及默认 44 项 Reactor |
| [ENG-002C 设计](ENG-002C-database-foundation-design.md)、[ENG-002C 报告](ENG-002C-database-foundation-report.md) | MySQL 8.4 三库、隔离迁移/具名事务经理验证，正式装配待审 |
| [ENG-002D 设计](ENG-002D-tenant-project-authorization-design.md)、[ENG-002D 报告](ENG-002D-authorization-validation-report.md) | 可信执行身份及合成范围检查，真实消息授权和补充验证待执行 |

通过 [ADR 索引](../architecture/adr/README.md) 核对以下 **Proposed** 实际正文。完整历史批准原文待核验。ADR-006/012/013/014 描述数据库、设备接入、VWCP 与 TRACK 候选。ADR-024/027 涵盖计费和终端补传，ADR-029/031/033 涵盖安全与设备身份，ADR-035/036/037/038/039 涵盖容量、异步、恢复、部署与运维。各 ADR 来源链接在附录。

DEC-002 保留设备身份、Epoch、Seq、旧事件接收和凭据。DEC-003 保留 L2 ACK 编码及安全。DEC-008 保留异步授权，DEC-011 保留业务事实与状态机，DEC-015 保留关键审计，DEC-016 保留 RPO/RTO 与容量承诺。BLOCK-003 生产签署由 C/E/I 工作包联合提供 MySQL 8.4 与真实 Broker 证据，验证 T1/T2、确认丢失、并发、回滚、路由、重放及对账，关联设备专项验收。BLOCK-001 交 ENG-002F，HA/BLOCK-007 另行验收。

## 2. 现有 RabbitMQ 与 Gateway 源码核查

来源为基线源码及 ENG-002D 已解析 Gateway/Server 依赖树、Gateway exec JAR。执行 `git diff --quiet a012f9f60e0d03785fecb9135fe498834166b2fe HEAD -- pom.xml yudao-dependencies yudao-module-iot yudao-framework yudao-server` 退出 0。版本证据来自既有构件，不代表在本任务重新构建生产模块。Gateway 基础 YAML 当前选择 Redis，总线的 RabbitMQ 装配依赖类型开关；本轮未更改配置。下表的证据编号在后面给出实际路径。

| # | 项目 | 当前行为与可复用点 | 差距及风险 | 证据 |
| --- | --- | --- | --- | --- |
| 01 | RabbitMQ Java 客户端 | Gateway exec/有效树包含 `amqp-client 5.25.0` | 可用现有版本做隔离探针，未证明生产代理或重连兼容 | E1 |
| 02 | Spring AMQP | `spring-rabbit`、`spring-amqp` 均为 3.2.12 | 可用现有相关确认 API，实际接管与启动须单独验收 | E1 |
| 03 | Boot 与 BOM | Spring Boot 3.5.16，BOM 导入 Boot 管理，IoT Core 声明 Spring Rabbit 依赖。默认 Server 既有树无 Rabbit 客户端 | 保持现有版本管理。生产 Server 装配另审 | E1 |
| 04 | RabbitTemplate | 自动配置注入共享模板，确认发布另外 new 一个模板，共用连接工厂 | 独立模板可复用。Bean 后处理、转换器、headers 未必传播至手工模板 | E2/E3/E7 |
| 05 | ConnectionFactory | 确认模板取共享 `getConnectionFactory`，Boot 路径能配置缓存连接工厂，实际布署工厂需核对 Bean | 发布与消费故障域可候选分离。需要连接恢复、池和超时的环境实证 | E2/E3/E4 |
| 06 | Confirm | `validateConfirmable` 检查工厂支持 confirms/returns 与超时。确认调用等待 `getFuture`，NACK 或异常抛 ConfirmException | 能复用联合判定思路。普通发布没有等待确认，也没有 Outbox 状态条件更新 | E2 |
| 07 | Returns | Gateway 配置 `publisher-returns: true`，专用模板回调记路由失败，结果由 `getReturned` 决定 | 回调保持短小，使用一次尝试绑定。先后顺序在实际 AMQP 版本验证 | E2/E4 |
| 08 | mandatory | 专用模板 true，共享模板在 Gateway YAML 显式 false | 普通发布不能证明可路由。mandatory 只验证至少一路 | E2/E4 |
| 09 | 持久化属性 | 构造器只调用 `MessageBuilder.withBody(...).build()`。Spring AMQP 3.2.12 的默认 `MessageProperties.DEFAULT_DELIVERY_MODE` 为 PERSISTENT | 目标发布显式设置 PERSISTENT，持久队列、Broker 磁盘及故障域仍需共同验证 | E2/E1 |
| 10 | Exchange/Queue | `register` 为 subscriber 声明 topic exchange，队列名等于 group，绑定模式 `#`，发送 routing key 为常量 `#` | 依赖订阅注册时点，错名称或迁移窗口会丢预期订阅。目标采用经核验的预声明契约 | E2 |
| 11 | 队列类型及持久化 | 队列 durable=true、exclusive=false、autoDelete=false，交换机构造使用默认 durable。未显式指定 `x-queue-type` | 实际类型取环境默认。目标拓扑必须明确队列类型和迁移策略 | E2 |
| 12 | Listener Container | `register` 手工 new `SimpleMessageListenerContainer` | 共享 Boot listener 相关 YAML 不会自动应用至手工容器，目标明确列出所需参数 | E2 |
| 13 | 消费 ACK | MANUAL，`subscriber.onMessage` 返回后 `basicAck(tag,false)` | 无统一事务提交证明。批准的目标需要独立本地事务边界与失败分类 | E2 |
| 14 | Prefetch／并发 | 并发 1–10，手工容器未设置 prefetch，实际 Spring AMQP 常量默认 250 | 需要按连接、线程、数据库池和处置延迟校准。上游配置不能当目标值 | E2/E1 |
| 15 | 消费重试 | 仅首次配送且由 ConfirmException 导致时 requeue 一次，其他异常 `basicNack(...,false,false)` | 复用异常分类的思路。目标需要持久重试或隔离，无界立即重投与静默丢弃均禁止 | E2 |
| 16 | 死信 | `register` 未声明 DLX/DLQ 或队列参数。外部政策可能另配，暂无运行证据 | requeue=false 可能丢弃。需独立失败保全及目标环境绑定审计 | E2 |
| 17 | 序列化 | MQ starter 有 Jackson 消息转换器。IoT 专用路径显式转 JSON 字节，并用目标泛型解析，绕开转换器 | 框架 JSON 可借用。目标信封需固定类型、版本兼容、大小限制与原始字节证据 | E2/E5 |
| 18 | 消息 ID | 专用模板生成一次 UUID 作为 CorrelationData。IoT payload 有自己的消息 ID，发布未显式设置 AMQP messageId | 相关 ID 可绑定尝试。业务逻辑消息 ID 稳定且持久记录，与跟踪、业务幂等分开 | E2/E6 |
| 19 | 租户上下文 | Rabbit initializer 为受管 RabbitTemplate 加发送后处理器。仓库覆盖同名 Spring `InvocableHandlerMethod` 解析租户头；IoT 手工 ChannelAware listener 直接调用 subscriber，不经此路径 | headers 只作提示。手工新模板与直接消费需单独验证归属和清理，不能自动授予访问范围 | E2/E7 |
| 20 | Gateway 发布 | `sendDeviceMessage` 选择普通 producer 或识别可暂存消息并走 `sendSpoolableMessage`；确认补传由 `sendDeviceMessageConfirmed` 调总线 | 可参考现有确认调用，正式 T1 及 Relay 主权应独立。MQ 确认与设备成功 L2 ACK 分离 | E2/E6/E8 |
| 21 | SQLite 暂存 | 两个 `@Service` 使用相同开关/文件。确认版校验总线，WAL + FULL；另一版只允许 Redis。现配置关闭。确认版有容量/过期及退避处理 | Rabbit 启用时旧服务可能在初始化拒绝，Redis 双线程存在竞争。BLOCK-001 由 F 处理，SQLite 无 INGEST 提交语义 | E4/E8 |
| 22 | 失败与恢复 | Gateway 连接和确认等待均配置 5s。ConfirmException 后确认版进暂存，排空确认后更新/删除 SQLite 记录。共享 Broker 配置和真实断线恢复尚未按 F 验证 | 超时是未知，Broker 可能已接受。重试和补传会重复。将长期状态和故障值留在所属库 | E2/E4/E8 |
| 23 | 已有测试 | Confirm 测试 2 项仅验证配置接受及非法超时，Spool Store 4 项和 Spool Service 17 项已有 ENG-002D 定向证据 | 上游 23 项不等于真 Broker 联合判定及 T1/T2 验收。此次执行新隔离探针 | E9 |
| 24 | 权限与 vhost | Gateway 声明回环默认端口及开发账号；当前 YAML 未见明确独立 vhost、TLS 或细分授权。Server local 配置有 Rabbit 连接及确认项 | 候选分环境 vhost、拓扑运维/Relay/消费主体分权，凭据外部注入，TLS、认证与最小权限按目标部署验证 | E4/E10 |

**源码证据索引**

- E1。实际构件和 BOM 关系可由 `unzip -Z1 <gateway-exec.jar>`、ENG-002D 的 `evidence/{default/yudao-server,iot/yudao-module-iot-gateway}-tree.json` 及 `javap -constants AbstractMessageListenerContainer` 复核。[依赖 BOM](../../yudao-dependencies/pom.xml#L152) · [IoT Core POM](../../yudao-module-iot/yudao-module-iot-core/pom.xml)
- E2。共享、确认模板及手工容器。[IotRabbitMQMessageBus](../../yudao-module-iot/yudao-module-iot-core/src/main/java/cn/iocoder/yudao/module/iot/core/messagebus/core/rabbitmq/IotRabbitMQMessageBus.java)
- E3。总线类型装配以及 RabbitAdmin 条件 Bean。[IotMessageBusAutoConfiguration](../../yudao-module-iot/yudao-module-iot-core/src/main/java/cn/iocoder/yudao/module/iot/core/messagebus/config/IotMessageBusAutoConfiguration.java)
- E4。Gateway YAML 的 RabbitMQ 及总线、暂存配置。[Gateway application.yaml](../../yudao-module-iot/yudao-module-iot-gateway/src/main/resources/application.yaml)
- E5。通用框架转换器。[YudaoRabbitMQAutoConfiguration](../../yudao-framework/yudao-spring-boot-starter-mq/src/main/java/cn/iocoder/yudao/framework/mq/rabbitmq/config/YudaoRabbitMQAutoConfiguration.java)
- E6。生产者及载荷对象。[IotDeviceMessageProducer](../../yudao-module-iot/yudao-module-iot-core/src/main/java/cn/iocoder/yudao/module/iot/core/mq/producer/IotDeviceMessageProducer.java) · [IotDeviceMessage](../../yudao-module-iot/yudao-module-iot-core/src/main/java/cn/iocoder/yudao/module/iot/core/mq/message/IotDeviceMessage.java)
- E7。租户发送后处理器及覆盖的 Spring 消费调用链。[TenantRabbitMQInitializer](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/mq/rabbitmq/TenantRabbitMQInitializer.java) · [TenantRabbitMQMessagePostProcessor](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/mq/rabbitmq/TenantRabbitMQMessagePostProcessor.java) · [InvocableHandlerMethod](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/org/springframework/messaging/handler/invocation/InvocableHandlerMethod.java)
- E8。Gateway 发送器及两个 SQLite 实现。[Gateway IotDeviceMessageServiceImpl](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/message/IotDeviceMessageServiceImpl.java) · [旧 SQLite 暂存服务](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/message/spool/IotDeviceMessageSpoolService.java) · [IotDeviceMessageSpoolServiceImpl](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/spool/IotDeviceMessageSpoolServiceImpl.java) · [IotDeviceMessageSpoolStore](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/message/spool/IotDeviceMessageSpoolStore.java)
- E9。既有测试证据。[IotRabbitMQMessageBusConfirmTest](../../yudao-module-iot/yudao-module-iot-core/src/test/java/cn/iocoder/yudao/module/iot/core/messagebus/core/rabbitmq/IotRabbitMQMessageBusConfirmTest.java) · [IotDeviceMessageSpoolStoreTest](../../yudao-module-iot/yudao-module-iot-gateway/src/test/java/cn/iocoder/yudao/module/iot/gateway/service/device/message/spool/IotDeviceMessageSpoolStoreTest.java) · [IotDeviceMessageSpoolServiceImplTest](../../yudao-module-iot/yudao-module-iot-gateway/src/test/java/cn/iocoder/yudao/module/iot/gateway/service/device/spool/IotDeviceMessageSpoolServiceImplTest.java) · [ENG-002D 回归](ENG-002D-authorization-validation-report.md)
- E10。Server Profile 仅记录配置名称和边界。 [Server local 配置](../../yudao-server/src/main/resources/application-local.yaml#L146) · [Server dev 配置](../../yudao-server/src/main/resources/application-dev.yaml#L136)

## 3. 可靠性及本库事务

1. **T1 / INGEST**。可信接入校验后，同一 INGEST 事务写入不可变原始事实及必需 Outbox（含所有已批准的发布意图）。提交成功或新连接核实同一事件、同一载荷和持久意图完整后，才有资格生成成功设备 L2 ACK。设备收到与控制板释放各有独立证据。Relay 在事务外工作，设备是否收到 ACK 无须阻塞已提交 Outbox。失败或未知提交须复核，不能先确认再补写。
2. **T2 / BUSINESS**。消费者用 BUSINESS 本地事务写 Inbox、正式事实及必要下游 Outbox。业务唯一键保护同一事实的多条逻辑消息。提交成功后才在原 Channel 上成功确认。副作用不可与 DB 原子提交时，用业务 Outbox 和稳定外部调用/查询键审计并对账。
3. **TRACK**。独立连接、事务和权限，按需有本地 Inbox 与 Outbox。TRACK 不可用只能积压、暂存已批准的意图或告警，INGEST 原始事实及 BUSINESS 提交的含义保持独立。

不使用跨逻辑库外键、跨库事务/XA、Redis 锁替代唯一约束或日志代替持久化事实。数据库提交、Broker ACK+无 returned、消费者本地提交、Consumer ACK、设备 L2 ACK 是不同阶段。系统按至少一次投递及可重复处理设计，允许超时、崩溃、恢复、重投产生重复，唯一约束与本地事务收敛业务副作用。上线需证明批准故障域的持久性与丢失窗口。

## 4. 身份及信封

| 身份 | 创建、稳定范围和使用 |
| --- | --- |
| 原始 Event ID | 认证后的硬件身份命名空间及 DEC-002 待批准的 Epoch/Seq 等字段形成唯一事件键。候选另存服务端稳定 eventId 及复合键。重传、换终端与补传保持同一事件身份，初次提交后不重发号 |
| 逻辑 Message ID | 事务写入时创建，属于稳定发布者命名空间，每条逻辑发布意图唯一。一次事件可产生多条不同意图。重试、受控重放保持 messageId 和载荷及其哈希 |
| Publish Attempt ID | 每次实际发布前生成新身份并随租约写入 Outbox。CorrelationData 与 attemptId 关联，唯一到每个发送，日志可通过 messageId 查询 |
| Business Idempotency Key | 领域稳定语义键，候选由来源事件、业务事实类型、经批准归属与明细/修正身份组成。正式称重总量、双桶明细、审核、合同、账务各定义版本与唯一粒度，由 DEC-011 和财务专项决定。不同消息可能命中同一事实 |
| 逻辑 consumerId | 代表职责及受控处理语义，多个部署实例共享。不同逻辑订阅各自建立 Inbox。实例编号、队列转发地址或随意改处理版本不能用来绕过已存在的消费身份 |

候选信封包含 `messageId`、`eventId`、`eventType`、`schemaVersion`、`tenantId`、`projectId`、`source`、`occurredAt`、`recordedAt`、`correlationId`、`causationId`、`payload`、`payloadHash`。source 是声明的来源命名空间，correlationId 串联一次业务链，causationId 指向直接原因，均不用于去重。发生时间、记录时间按 C-02，事件时间质量另行保存。业务归属由可信登记及历史关系确认，项目未确认时不得凭客户端补填。信封中的 actorId、租户及项目仅可用于关联，不生成执行身份或权限。认证、版本、大小以及位置、状态等业务类型均先校验。

候选双摘要方案以版本化 SHA-256 分别计算经规则提取的不可变设备原始字节，以及持久化发布字节。重试尽量重发同一字节串。若跨语言必须重建，先批准 UTF-8、键顺序、数值/时间、空值、Unicode 与签名覆盖字段等规范化规则及黄金样本。终端附加字段和瞬时会话数据另存，不应让同一事实因传输重封装误报冲突。Inbox 比较同一命名空间的稳定消息摘要，必要时核对字节或受保护存证。摘要提供完整性比对，设备鉴权、防重放、旧密钥/换板的历史补传另按 DEC-002 审批。

## 5. INGEST 原始事件与冲突

| 情况 | 处置及 L2 ACK 条件 |
| --- | --- |
| 首次合法事件 | T1 插入原始事实、接收时间、时间质量、认证来源、摘要及 Outbox。确认提交后才可回复成功 |
| 同身份同载荷 | 唯一冲突后回滚失败事务，以新事务读已提交行及可靠意图，支持活动状态和可核验归档。确认完整后可重发成功 ACK，不新增事实或消息 |
| 同身份异载荷 | 保留原行，单独记录冲突时刻、双方摘要、可隔离的受限样本、认证来源和告警。禁止普通成功 ACK、覆盖或自动计费。避免把未鉴权报文混入正式原始事实 |
| 不同身份同载荷 | 仍按身份分别保留。共享摘要不能合并，后续领域唯一约束另行判断 |
| 并发重复／唯一键冲突 | 依赖数据库唯一键，以事务结果为准。区分正式身份唯一键冲突和其他约束错误，后者拒绝并诊断。禁止只用先查后写 |
| 已有原始事件但可靠消息缺失 | 不按普通成功处理。暂停受影响范围，读取归档及提交证据。仅在明确原始字节、稳定消息 ID、订阅版本与审批后恢复意图 |
| 提交结果未知 | 新连接核对唯一键与整个 T1 不变量。确认无记录才考虑原身份重试。来源不可用或冲突时保持未确认 |
| 迟到或时钟不可信 | 保留时间质量、接收时刻及原始值。按历史证据确认，不能按当前绑定或到达顺序推断归属和计费 |
| 无效设备或损坏载荷 | 拒绝普通 T1 路径，仅按批准的隔离及审计策略保留最少样本，阻断业务派生 |

原始事实不可变。后续审核与修正写独立记录并引用原始身份。本轮无 L2 ACK 编码、签名、控制板释放动作定版。

## 6. Relay 方案与状态机

| 维度 | A MySQL 轮询 Relay，优先 | B MySQL Binlog / CDC |
| --- | --- | --- |
| 部署、成本及权限 | 在应用或独立进程执行。需要出站账户、短事务更新和观测，无新生产组件 | 需要连接器、运行和恢复设施、复制权限、offset 及拓扑运维。暂未批准 |
| 压力与延迟 | 轮询和索引写影响主库。有界批次、自适应空闲和连接池限额，延迟随负载与间隔 | 减少业务表轮询，增加日志保留、带宽及追赶成本，需验证切换和断档 |
| 多实例、顺序与恢复 | 行领取及租约可水平扩展，先后依赖显式业务约束。状态回读与持久重试清晰 | 原始顺序与多个分区/下游顺序不同。重启与重放需处理 offset、Binlog 源变更 |
| 可观测与三库 | 每库独立状态、水位和账户。相同实例的三个库共享 CPU/IO 和故障域 | 每来源快照和权限独立治理，共享实例也需要避免隐式全库权限 |
| 取舍 | 实现与运营成本小，先做实证再定指标 | 适用于经验证的高流量变更流和获准专门团队，不在本阶段引入 Kafka/Debezium 或插件 |

候选字段 `message_id,event_id,event_type,schema_version,tenant_id,payload,payload_hash,exchange,routing_key,status,attempt_count,attempt_id,lease_owner,lease_until,next_retry_at,last_error,created_at,published_at,version`。另记发布者、不可变意图种类及拓扑版本、首次/最近失败时刻和脱敏错误。唯一约束建议 `(producer_namespace,message_id)`，以及对同一事件的逻辑意图键（事件、目的意图、必要业务版本）；消费者分队列广播只需一个发布意图的情形另审。索引候选 `(status,next_retry_at,id)`、`(status,lease_until,id)` 和按 event_id 关联，时间按 UTC 毫秒。载荷大小上限后审。大载荷优先只在同一库保存一次并引用其持久版本，外置引用需验证对象与发布意图的一致性、校验和及恢复。

| 当前→目标 | 事件及短事务条件 | 失败和恢复 |
| --- | --- | --- |
| 创建→PENDING | 随 T1 或 T2 原子写入 | 业务回滚则没有发布意图 |
| PENDING/RETRY_WAIT→PUBLISHING | 到期可领取并锁定后写 version、attemptId、owner、lease_until、增加计数；立即提交 | 领取后未发、实例重启由租约恢复，持久化计数包含已领取未发送，发送证据单独计 |
| PUBLISHING→PUBLISHED | 事务外发送，Confirm ACK 且同 attempt 无 returned，按当前 attemptId、version 和状态条件更新 | 更新失败视为未登记，可能重复发送，保持原 messageId。重复回调和旧 attempt 更新为 0 行，只记录诊断 |
| PUBLISHING→RETRY_WAIT | 重试类失败、未知结果或领回过期租约，记录原因及下次时点。回收或改领必须撤销旧 fence | 已发但未确认不能推断未送达。租约重复发布是预期失效模式 |
| PUBLISHING/RETRY_WAIT→DEAD | 到达待批次数/年龄或永久不可路由等原因时持久保留状态及证据 | 进入告警/人工处理。不删原始事实，受控重放以新 attempt 启动原 messageId |
| DEAD→RETRY_WAIT | 已批准重放操作在本库记申请、依据与审计，条件更新再调度 | 防并发重复申请；终审前不扩大原投递路由与业务授权 |

批量领取只取即将发送的有限工作量，按数据库时钟判断到期，所有权比较用 version/attemptId。将状态时间统一依赖相应主库 UTC，主库切换可能跳时，需记录偏差、避免提前过期或长挂。续租可用短条件更新，持有者失去 fence 后停止继续发，但已发网络调用不由数据库租约撤销。为租约设最大延长和管理告警。发布持锁期为零，DB 连接在等待 Broker 前归还。清理只能在归档、重放和去重凭证周期获批后执行，PENDING、未知、DEAD 及外部未核对记录保留。

## 7. 并发领取

| 方案 | 优势与限制 |
| --- | --- |
| A `SELECT … FOR UPDATE SKIP LOCKED` | 推荐。有索引条件和有限排序/批次，不阻塞已领行，适配多实例。实际调度可跳过慢行，不保证严格顺序。记录等待、饥饿、锁扩张及查询计划 |
| B 条件 UPDATE/乐观锁 | 架构简单，在相同候选行竞争时重读及冲突频繁。多行写入需验证不同版本、索引和排序语义 |
| C 单实例加接管 | 容易控制速度和顺序，并发吞吐及恢复有局限，仍需要持久 lease/fence，不能用调度单例替代去重 |

建议比较 READ COMMITTED 和默认隔离级别对索引锁的影响，用 MySQL 8.4 实证同事务领取与状态更新，示例在第 19 节验证两连接、强制 ready_idx、批次 1 的选择行为。生产候选应分别扫描到期与过期租约、对照 EXPLAIN 和繁忙密度，控制连接池共享、及时归还连接。每轮配额和偶发全龄扫描预防饥饿。领取批次、并发、连接池、重试、租约相对确认窗口与续租策略须审批。网络慢、超时、租约过期与多实例即使同用 A，仍可能重复发布。

## 8. Confirm、returned 与发布失败

Spring AMQP 3.2.12 的 `CorrelationData(String)`、`getFuture()`、`Confirm.isAck()/getReason()`、`getReturned()` 实际可用，版本探针编译并运行。框架处理返回时会写关联数据，当前版本探针证实 future 完成时可读 returned。读 future 与关联 returned，回调仅短小采集，不在回调线程访问数据库或依赖固定 sleep。相关 ID 必须每次唯一，避免多个共享连接工厂上的并发返回串线。测试及实现还需避免慢回调、执行器拥塞导致回调延迟。

| 状态或故障 | 候选 Relay 动作 |
| --- | --- |
| ACK 且无 returned | 在验证目标拓扑与持久条件后，短事务按 attemptId/version 更新 PUBLISHED，记录 Broker 接纳。其后仍有 Consumer 本地提交及 ACK |
| ACK + returned | 按路由失败，保留 reply code、目标和订阅版本，启动拓扑告警。禁止 PUBLISHED |
| NACK | 保留原因。区分 Broker NACK（例如容量策略）和框架因 Channel 关闭结算的 NACK。路由、权限错误可能需人工修复 |
| 确认超时、连接在发送期间中断、Channel 关闭、Broker 重启 | 结果可能未知，保留数据并以同一 messageId 重试。区分确认前还是后，禁止因连接报错推断 Broker 必未接收 |
| Exchange 不存在／Queue 未绑定 | 前者会关闭 Channel 或发送抛错，后者可收到 ACK+returned。记录不一致拓扑。部分预期绑定缺失也可能 ACK 无 returned |
| 已确认但数据库写入失败 | 未登记 PUBLISHED。回读状态，租约失效后恢复，相同逻辑 ID，允许重复 |
| 延迟回调或旧尝试重复到达 | 验证状态、版本和尝试，不适用变更的回调只记诊断，不更新新租约 |

候选每版本记录交换机、路由、所有预期逻辑消费者、队列参数及绑定。上线前通过获准拓扑账号进行声明、被动核验存在性和权限，并比对运维控制面中的绑定与队列类型；被动声明本身没有完整绑定证据。启动/更新采用版本准入。持续监测绑定缺失并暂停受影响路由、核对缺失期间的消费与补发。mandatory 只能验证至少有有效路由。不得拿一次 Broker ACK 证明所有预期订阅都存在。

## 9. 队列、Broker 与权限

| 维度 | Classic durable | Quorum durable |
| --- | --- | --- |
| 模型 | 单副本。持久消息随持久卷及 Broker 有序重启可恢复 | Raft 多副本，确认和可用性依赖就绪副本及多数派。单节点只有一份可用 |
| 发布确认 | 持久消息进入满足条件的持久队列后结算，仍依赖本节点磁盘和挂载正确 | 在相关法定多数已接受的前提下确认，副本数量、拓扑和实际丢失窗口待测 |
| 单机／多节点 | 基础单机资源开销小。多节点部署不使单个 Classic 队列复制 | 单机可验证语义无故障冗余。HA 候选跨独立节点和故障域，并核查选举与存活 |
| 磁盘、顺序与运维 | 磁盘较低，拓扑较简。并发、重投会造成乱序 | 复制和日志成本更高，磁盘告警、队列治理、升级、接管必须演练，同样无业务全序保证 |
| 死信与投递限制 | 标准 DLX 路径，可靠性依配置且通常无法证明无丢失，应用仍需持久失败记录 | 支持特定配置下至少一次死信及投递次数限制，默认和实际政策须验证，重试上限仍由业务证据决定 |

隔离测试建议同时运行两种类型。基础部署候选优先显式 durable Classic 或在同预算下评估单节点 Quorum 以便后续一致测试。需多节点冗余的正式形态优先评估跨故障域 Quorum，经容量及故障验收后审批。禁用临时队列承载正式可靠流。Exchange durable、Queue durable、消息 PERSISTENT、盘卷/挂载、Confirm、至少一路路由及预期绑定清单共同成为发布准入。命名、路由版本和升级迁移按拓扑清单审批。

建议 vhost 按环境隔离，拓扑管理身份配 configure 权，Relay 限指定 exchange 的 write，消费者限指定 queue 的 read，维护和重放身份再收窄，部署环境检查 TLS、证书、rotation、时钟和审计。管理端和数据端不默认对公网开放。隔离实验账号只用于临时 vhost，未模拟生产细粒度权限。

## 10. Inbox、业务幂等与 ACK

候选 Inbox 键 `UNIQUE(consumer_id,message_id)` 依赖全局无冲突且可证明的 messageId。若只在发布者范围唯一，推荐 `UNIQUE(consumer_id,producer_namespace,message_id)`。tenantId 是可信归属快照，可用于审计和查询，但不能从未经认证的消息字段扩大分区来绕过去重。logical consumerId 应由配置登记且稳定，滚动升级新实例不生成新的消费身份。

| 输入或故障 | T2 模型与确认 |
| --- | --- |
| 首次 | 校验版本与可信身份，事务写 Inbox、对应业务事实、必要 Outbox，保留摘要及事实引用。原 Channel 手动确认 |
| 并发重复／已提交重投 | 唯一键仲裁，以新事务核实完整且同摘要的已提交处理。缺事实或必要 Outbox 则异常，不按已处理 ACK |
| 同 Message ID 异载荷 | 独立隔离、告警与审计，保持旧 Inbox/事实，禁止覆盖、普通成功或删除旧记录 |
| 不同 Message ID 同业务键 | 同一业务唯一键仲裁。语义相同可在同一事务登记 canonical fact 引用而不新增副作用；内容矛盾进入业务核验 |
| Inbox／事实／业务 Outbox 任一步失败 | 整体回滚，保留 Broker 消息或持久失败意图。不能分事务先提交 Inbox 或吞掉业务异常后提交成功 |
| 提交失败或结果未知 | 无成功 ACK。通过重投和读权威事实验证，避免把未知当未执行 |
| 已提交但 ACK 失败／Channel 关闭 | 保留已提交事实。由 Broker 重投重新走精确 Inbox 判定，取本次 Channel 的 deliveryTag 再确认 |

建议以明确具名事务管理器使用 **TransactionTemplate**。监听线程无外层共享事务，T2 及持久化同步在该线程完成，注册仅设置布尔提交证明的 `afterCommit`。外层只有 `execute` 成功返回且标记为真才调用 `basicAck(tag,false)`。禁止被吞异常，显式处理 rollback-only，避免仅看业务返回值或某些主动 rollback-only 的正常返回。遇事务管理器异常、afterCommit 失败、标记缺失，即使可能已经提交仍等待可安全重投。标记不执行业务或网络调用。若消息已登记同摘要，复核原子性后也走可验证的确认路径。

| 实现 | 取舍 |
| --- | --- |
| A TransactionTemplate 返回后手动 ACK + 本地提交证明 | 推荐。业务事务和 AMQP Channel 解绑，容易测试提交异常、线程和网络失败。外层不得无意加入更大事务 |
| B 纯事务同步 afterCommit 中 ACK | 只有 commit 完成才调用，但网络失败处理隐蔽。需证明回调与 Channel 同线程，且无批量误确认及合适异常处理 |
| C 代理事务方法返回后确认／独立消费适配器 | 可选。Spring `@Transactional` 自调用、代理 bypass、错误事务管理器、外层事务参与和异常配置容易破坏条件。同步 DB 与 AMQP 也不构成 XA |

Channel deliveryTag 只在本 Channel 有效。用 `multiple=false` 单条确认，初期不批量 ACK，避免已确认未提交消息。无跨线程持有 Channel 或错用重连前的 Tag。ACK IO 异常时关闭/交回管理连接并等待重投，不伪造成功或循环重试旧 Tag。真实隔离 JVM 提交后、ACK 前中止及重投详见第 19 节。

## 11. 失败分类、重试与死信

| 失败类 | 自动或人工决策 |
| --- | --- |
| 网络/数据库暂时失败、Broker 不可用、超时未知 | 有限指数退避加抖动。发布由所属 Outbox `next_retry_at` 调度。未知结果仍持久记录和按原 ID 重试 |
| 无效路由或拓扑差异 | 快速告警并暂停受影响意图。配置修复后专项重试，保留相同 ID |
| 格式、版本不支持、身份冲突 | 审计分类并持久隔离。等待契约升级、认证或人工核验。不重复解析造成风暴 |
| 业务依赖未齐 | 有期限等待依赖或交由对账。可重试条件必须明确，保护顺序敏感聚合 |
| 权限拒绝、永久业务错误 | 按来源拒绝并持久保存；受控流程可复核。设备原始事实不因用户撤权丢弃 |
| 未知错误 | 有界重试后列入持久失败状态，保留调查链 |

消费暂时失败优先考虑本地持久消费失败/重试意图和继发 Relay。原始 T2 回滚后另起失败记录事务。失败路径先证明可恢复责任已持久移交，再通过经验证的拒绝/死信流程终结原 delivery，不走 T2 成功 ACK 路径。两类结果分别监控和审计。若消费库不可写，暂停取新消息并保留未确认消息或依经验证的 Broker 路径延迟，避免无界即时 requeue。RabbitMQ TTL+DLX 或独立延迟队列可降低数据库轮询，队首 TTL、重新排队、延迟队列与 dead-letter 安全性须单独验证。延迟交换机插件增加兼容与恢复成本，本阶段不引入。最大次数、总年龄、单次超时、抖动、上限、告警及自动/人工恢复阈值列审批；实验数值只服务夹具。

发布死信用仍在来源库的 DEAD 与分析证据修复，消费死信用针对逻辑 consumer 的隔离记录及可能的 DLQ。记录原 messageId、eventId、原始持久载荷/引用及摘要、失败原因、时刻、尝试编号、来源交换机/队列、目标 consumer、关联追踪和审核。DLX 转移在目标不可用或政策不适配时可丢失，只有验证选定队列配置以及目标可达性后才作保证，业务失败证据仍需保全。积压按原因、最老年龄、容量、是否可重放和不一致分类告警。

重放步骤为锁定单个受影响作用域、审批申请及理由、比对原身份/摘要及订阅版本、限制目标消费者和速率、记录执行结果并对账。重放保持 messageId、eventId、载荷和业务幂等键，新 attemptId 并留审计。已成功处理而确需重算时，用独立授权的修正命令或补偿事实关联原事件，保留 Inbox、Outbox 和历史。审批角色和保留期限按 DEC-015。

## 12. 顺序和因果

| 方案 | 判定 |
| --- | --- |
| A 全局顺序 | 不推荐。多设备、离线、故障恢复及独立库均会破坏单一到达顺序。强制串行成本高且不代表发生时刻 |
| B 按聚合键局部顺序 | 仅供状态机确有需要的命令，例如任务阶段变更、同账单修正。候选使用同域聚合 ID、版本及序号，并定义缺口、迟到和过期事件处置 |
| C 允许乱序，由事实及版本判断 | 推荐默认。原始存证、幂等轨迹追加、统计重算可使用事件发生时刻和版本，缺少先决条件放入可恢复等待 |

多 Relay、消费者并发、同车多设备、暂存补传和重试都可重排。同控制板的 Epoch/Seq 用于已有经批准语义范围，不能直接跨设备或异常时钟排序。保留时间质量、correlationId、causationId、业务状态前置条件和历史归属。某消息失败时，后续独立事实可继续处理，依赖其结果的状态更新须显式等待或拒绝。作业、称重、计费正式状态机由 DEC-011 决定。

## 13. TRACK 独立处理

可信定位事件写 INGEST 原始证据，TRACK 使用自己的事务、连接及逻辑 consumerId。候选本地 Inbox 按稳定消息身份去重，轨迹以定位事实唯一键约束。最新位置以批准的时间质量、发生时刻和同粒度版本保护；迟到点可补历史并触发受控重算，不能无条件覆盖最新点。TRACK 不可用时按失败策略保留可重投意图，并可恢复时经水位和对账补算。称重使用与事件有关的历史位置身份或带版本快照，缺失标记待核验。坐标、分区、保留及质量阈值由 DEC-009 专项处理。TRACK 故障不得覆盖、回滚或删除 INGEST。

## 14. 异步身份及上下文

| 消息 | 执行边界 |
| --- | --- |
| 用户发起的异步命令 | 从权威持久工作单重建发起人和业务归属，受限服务复核当前授权。高风险指令过期或撤销后失败关闭，保留审计 |
| 系统内部事件 | 按登记的生产者及事件种类验证，执行服务仅拥有指定操作和库范围，按权威业务记录定位归属 |
| 已可靠登记的设备事实 | 校验原始保存的来源与归属，以服务职责接续处理。用户后续撤权不静默丢弃已持久事实，归属异常进入隔离 |
| 人工重放 | 审批人、申请人、执行服务三类身份可审计，核对当前角色、范围、原因、目标及原身份，禁止普通消费者自行扩大 |

头中的 tenantId、projectId、actorId 是声明。校验消息来源服务、权威归属、权限及契约后建立最小作用域。线程入口和 finally 清理 SecurityContext、TenantContext、项目、ignore 和审计上下文，回调与重投重新建立。ENG-002D 提供合成验证，ENG-002D-02R 未完成，身份绑定与导出生命周期补充测试留在独立任务。

## 15. 跨逻辑库恢复与对账

候选记录按库的备份标识、GTID/binlog 位点或等效恢复向量、UTC 时窗、稳定事件键、各意图和订阅版本、发布 attempt、Inbox 和业务事实引用、对外请求 ID、源和目标审计摘要、ACK 层级及时间。水位按库、分区或流记录，维护未关闭缺口集合，不能用最大 ID 或总数相减推断无缺失。Broker 队列深度是运行快照，无法代替历史消息归属及消费证据。

| 差异 | 候选处置 |
| --- | --- |
| INGEST 早于 BUSINESS | 先保护已有业务及对外副作用。核查来源备份、设备或终端可靠补传、已确认见证及关联版本。已确认但恢复后缺失应作为持久性事故单独上报 |
| BUSINESS 早于 INGEST | 使用原稳定消息重放缺失的目标消费；复核旧 Inbox、业务唯一约束、已交付的外部副作用，以及需恢复的下游 Outbox |
| TRACK 恢复点不同 | 按定位源和质量有界补算轨迹及最新点；业务历史位置保持原引用并核验 |
| Broker 早于或晚于 DB | Outbox PUBLISHED 不是完整订阅证据。按绑定及消费者缺口清单重投。较新的 Broker 可重投已完成的消息，按 Inbox 去重 |
| Outbox 与 Broker 状态不符 | 保留 attempt 和拓扑版本，未知状态允许重发原 ID。已发仍未消费按消费水位核对 |
| Inbox 和业务事实不符 | 暂停相关消费，核查版本、恢复点、哈希和副作用。先恢复可信证据与缺失事实，按审批修正，不清空 Inbox |
| 已提交但设备未确认／未确认控制板收到 | 保持 T1 不变量和原始消息，允许设备按原身份重试。并记录服务器生成、终端收到和控制板收到三个不同阶段 |
| 已消费但后续 Outbox 未发或缺失 | 先判断是否仍 PENDING 或 DEAD。完整事务缺项视为异常，按权威记录评审恢复，不重新执行已有副作用 |

恢复执行顺序为定界与审批、停受影响写入/Relay、守住证据和备份、识别各库与 Broker 水位、生成缺失/重复/冲突清单、恢复原发布意图、受控重放、核对事实及外部副作用、记录复核签字后逐步恢复。禁止全量盲重放、删除 Outbox 或清空 Inbox。三逻辑库同实例共享故障和资源，独立连接不提供副本冗余。恢复报告按故障域记录保护集合、实际丢失窗口和恢复时间，RPO/RTO 与 HA 由 DEC-016、BLOCK-007 审批。

## 16. 容量、观测及保留

- **Relay 与 Broker。** 待发数、最老年龄、成功率和 confirm latency 分布，按 NACK、returned、超时、拓扑差异分类。观测租约过期、领取/更新冲突、重试、死信、重放和 Broker 队列 ready/unacked、连接、磁盘及副本状态。
- **消费与对账。** 消费端到端延迟、重复、Inbox 摘要冲突、事务回滚、处理种类、对账差异、库连接和磁盘。采集原始提交、服务器生成 L2 ACK、终端收到、控制板收到四个独立点，各报失败率和延时。后两者需要协议和终端证据。messageId、deviceId、eventId 仅进入受控结构化追踪或业务查询，不作无界指标标签。
- **容量估算。** Outbox 至少覆盖 `峰值入库率 ×（故障积压时段 + 正常等待）× 每事件平均消息数`，另加 `成功保留时段 × 平均发布率`、死信保留、索引、binlog/redo、备份和膨胀系数。Inbox 行数约为消费唯一消息率 × 保留时长 × 不同逻辑消费方（若速率尚未乘分发系数），加重试或冲突证据及索引。按平均/高分位报文估算主体、复制、压缩及磁盘放大。恢复净清理速率应大于持续新入速率，估算清理时长为 backlog ÷ (处理速率−新增速率)。参数用实测有效吞吐，峰值及余量待审批。
- **清理与归档。** 确认和业务引用未核对、未发布、待隔离或重放中均不得清理。归档要保持设备重复检查、消息/载荷或等价凭证和对账能力。Inbox 保留覆盖批准的设备/消息重投和受控重放时段。历史业务唯一键可减少重复副作用，但 Inbox 清理后仍需摘要及冲突取证能力。具体保留、容量目标、阈值与报警责任不在本阶段定版。

## 17. 模块、事务管理器与运行边界

| 候选归属 | 约束与组合方式 |
| --- | --- |
| sanitation-event API/Biz | event API 可表达通用技术信封及可靠发布入口，event Biz 管理 Relay、领取和消费基础设施候选。通过注入的公开契约或运行组合登记领域处理器，event Biz 无各领域 Biz 编译依赖 |
| sanitation-common | 纯值类型，不承担状态、数据库操作或领域编排 |
| sanitation-device | INGEST 原始事件及设备事实，可信协议接入和身份验收另行批准 |
| sanitation-operation / billing | 作业、称重、计费的业务键、事实和业务 Outbox 分属实际批准的业务事务，DEC-011 保留所有权 |
| sanitation-gis | TRACK 消费、最新点和轨迹版本，候选独立的本地去重 |

INGEST、BUSINESS、TRACK 各有具名 DataSource、事务管理器、mapper factory 与最小权限连接。发布契约调用在相应所属事务内落库，不能在事务 API 隐式切到 RuoYi master。Relay 只读/写同库发布意图及审计，没有跨库 SQL 或共享内部 Mapper。运行组合分别核准与 Server Spring 装配和上线流程。Gateway 仍独立，候选只接公开设备/可靠存证契约，不依赖 sanitation Biz、Server 或双暂存内部实现。若 Gateway 将来负责 T1，可通过独立获批存证服务或仅 INGEST 的专用装配，具体选择待审。F 工作包未完成前不把遗留 SQLite 和新 Relay 同时指定为同一可靠意图的发布主权。

未来可能需要已使用的领域 Biz → event-api、event-api → common，以及应用组合根 → 实际所需组件，逐条核对 ENG-002B 候选白名单。若需 event Biz 依赖额外公开领域 API，先审可替代的注入/注册路径、依赖图和职责。Gateway 如需设备公开协议只走独立审批。当前文件不调整依赖或装配。

## 18. ENG-002E-02 实施候选

| 选项 | 文件、依赖及适用范围 |
| --- | --- |
| A 独立验证工程，推荐 | `script/messaging/validation/{README.md,pom.xml,run-lab.py}`，`fixtures/{ingest,business,track}/`，`src/{main,test}/java/`。候选 JDK21 / Java release17 / Maven3.9.12，按当前 Spring AMQP/Rabbit Client 及 MySQL 驱动版本锁定和校验，JUnit、具名本地事务、隔离 MySQL8.4 与真实 RabbitMQ。用合成事件、事实及本文矩阵覆盖发布、Broker、回滚、重放及对账。报告 `docs/engineering/ENG-002E-reliable-messaging-validation-report.md` |
| B 正式但未启用的组件 | 在 sanitation-event 及所属领域建设经批准的 API、Biz、Mapper 和版本化 DDL。需要所有权、依赖白名单、权限、兼容性、数据迁移与运行时专项审批，回归范围更广 |

方案 A 的候选命令是先 `bash script/shell/check-build-toolchain.sh`，运行 `mvn -f script/messaging/validation/pom.xml clean verify` 与 `python3 script/messaging/validation/run-lab.py --evidence-dir <隔离目录>`，再执行第 20 节工程回归。引入测试库、拓扑、测试脚本和复验范围须人工批准。正式业务表、生产拓扑及 Gateway 接管另审。

## 19. 隔离实验与复验

本轮使用本机 Docker 动态回环端口、独立随机临时账号及 vhost，并核对 MySQL 报告标志、实例 UUID 与 catalog。MySQL **8.4.13**、RabbitMQ **4.2.9** 单节点。镜像摘要分别为 `sha256:8c233bad33d520a7aae1c1e619c3a9775f4a1ba2d463d692fada04ec7f40be00`、`sha256:d15c432b200a107c9602b9aafe6499b5ac7a56d870d367b53a7922f6f04e14df`。使用持久交换机、显式 durable Classic 和单节点 Quorum、持久消息。数据库会话使用 UTC，领取实验为 READ COMMITTED。重启用同容器及其数据卷。MySQL 测试用 InnoDB、实际唯一约束和本地事务，T1/T2 模拟表位于同一临时 schema 但用不同本地事务，TRACK 用另一 schema/连接。未执行真实三库拓扑或独立故障域恢复。

临时 Java 使用 Temurin OpenJDK 21.0.13.1、`javac --release 17`，复用基线等效 Gateway exec 的 Spring AMQP 3.2.12、AMQP Client 5.25.0 与隔离 Maven 缓存 Connector/J 9.7.0。探针与日志留在仓库外 `/workspace/eng002e01-evidence`。执行命令如下。

```bash
e=/workspace/eng002e01-evidence
docker pull rabbitmq:4.2
rc=0
bash "$e/run.sh" > "$e/lab-final.log" 2>&1 || rc=$?
printf '%s\n' "$rc" > "$e/lab-final.exit"
rg '^(PASS|pre-ack-process-exit)' "$e/lab-final.log"
```

`run.sh` 顺序执行 `javac --release 17`、`Lab setup`、`Lab halt`、`Lab redelivery`、回环代理 `Lab interrupted`、Broker stop 后 `Lab offline`、Broker start 后重新读宿主动态端口和 `Lab restart`。最终脚本退出 0，有 **50 个通过断言**，消费者故障注入进程刻意在数据库 commit 后且发 ACK 前 `Runtime.halt(37)`，实际退出 37。首次探针在重启后使用了失效/尚未就绪的动态端口，退出 1。修订为重读端口并有限等待新连接。第二次试验假定 Quorum 队列达到阈值后紧接着就 NACK，实际断言失败并退出 1。改用明确 `reject-publish` 的 Classic 评估原生 NACK，Quorum 仅独立测试确认、重投和重启。前两轮日志为 `lab-first.log`、`lab-second.log`。加入回环断连后，首次传输探针的四个业务断言已通过，但 JDBC 与 Broker 全流程在关闭已断开的 AMQP Channel 时因 AlreadyClosedException 退出 1。复验只在四项断言都完成后接受该清理异常。另一次启动遇到 RootlessKit 自动宿主端口冲突，退出 125 后改为由脚本先选择不同的可用回环端口，再显式映射。保留 `lab-core.log`、`lab-transport-first.log`、`lab-port-conflict.log`、`lab-final.log` 和对应退出码。多个 SLF4J provider 警告来自临时解包 classpath，未改生产依赖。容器与卷均已清理。

| # | 场景 | 实际条件、结果与局限 |
| --- | --- | --- |
| 01 | 原始和 Outbox 原子写入 | 真实 InnoDB 本地事务提交后查到一对 |
| 02 | 原始写入后故障 | 控制触发应用异常，真实回滚，无部分写入 |
| 03 | Outbox 写失败 | 真实唯一冲突，原始记录回滚 |
| 04 | 同身份同载荷并发 | 两连接屏障同时插入，唯有一对提交。第二请求识别为唯一冲突，未模拟真实 ACK 生成 |
| 05 | 同身份异载荷 | 使用不同夹具摘要，真实唯一冲突且原摘要保留，待实现冲突隔离记录 |
| 06 | 多 Relay 选择 | READ COMMITTED，索引 `ready_idx`，批次 1，两连接锁交叠时获得不同 ID。第二选择事务未持久标记第二行，端到端多 Relay 仍待 |
| 07 | 租约过期 | MySQL UTC 表达式及条件 UPDATE 接管成功，时间为夹具参数 |
| 08 | 旧尝试更新 | 旧 attempt 条件更新 0 行。未运行真实异步回调注入 |
| 09 | 正常 Confirm ACK | Spring AMQP 调用真实 Broker，路由确认且无 returned |
| 10 | mandatory 无路由 | 真实 Broker 补充返回。相同事件见 11 |
| 11 | ACK + returned | future 完成后同时观测 ACK 与关联 returned，联合判定不得成功 |
| 12 | 不存在交换机 | 真实 Channel 关闭（404），Spring future 给出 NACK。另测有界 Classic reject-publish 原生 NACK |
| 13 | 发布期间断连 | 回环 TCP 代理在 Channel 开启 Confirm 后暂扣 Broker→发布者数据，独立连接证实 Broker 已投递时关闭代理两端。发布者得不到确认，原消息 ID 重投又送达。另证 Broker 停止前置连接被拒 |
| 14 | 确认丢失 | 两种证据。Spring AMQP 已完成真实确认后模拟未登记；独立 AMQP 客户端经回环代理实际丢失确认传输并重投。正式 Relay 与代理的整合验收仍待 |
| 15 | Broker 重启 | 真实 stop/start 后，同数据卷在 Classic 和 Quorum 分别读回持久消息。单节点有序重启，不覆盖宕机、磁盘丢失或多数派故障 |
| 16 | 确认后数据库状态更新失败 | 真实 JDBC 已关会话拒绝 UPDATE，重新连接读到状态仍可接管。数据库服务故障未注入 |
| 17 | Inbox、事实和业务 Outbox 同事务 | 真实提交，三行完整 |
| 18 | Inbox 后业务失败 | 应用注入异常，真实事务回滚 |
| 19 | 业务后 Outbox 失败 | 真实唯一冲突，三部分均回滚 |
| 20 | 重复消费 | 同 ID 同摘要只保留已提交事实 |
| 21 | 并发重复 | 两连接并发，保留一个事实 |
| 22 | 提交后 ACK 前进程退出 | 实际 JVM halt=37，Broker 重投 Redelivered=true。第二进程同 ID 查 Inbox，仅一份事实和一份业务 Outbox |
| 23 | 同 Message ID 异载荷 | 真实 Inbox 唯一冲突及摘要比对，候选隔离未装配 |
| 24 | 不同消息同业务键 | 第二 Inbox 可留同事实结果，业务唯一键拒绝双事实 |
| 25 | 退避 | 真实 SQL 写未来待重试点，立即可领数为零，未运行自动指数/抖动调度器 |
| 26 | 重试上限与保留 | 真实 SQL 转 DEAD，原始事实及 Outbox 仍有匹配。上限为夹具选值 |
| 27 | 受控重放 | 合成批准原因下，同库事务恢复原 ID 并留审计、新尝试；未触发正式 API 或真实消费重放审批 |
| 28 | TRACK 故障隔离 | 独立 schema 写入触发约束失败并回滚，INGEST 已保存不变。同实例尚不能提供故障域隔离 |
| 29 | 跨库恢复点差异 | 合成水位，使用真实 SQL 比对缺口，未执行物理还原或备份恢复 |
| 30 | 对账与副作用 | 真实 SQL 查出缺失原始事件 ID；真实重投后验证一份业务副作用。未对接外部操作 |

正式设备 ACK、生产真机链和 Broker HA 未执行。探针中的模拟摘要、合成授权与固定重试参数仅用于验证机制。

## 20. 第二阶段验收矩阵

下表为建议测试条件、期望和需交证据。第 19 节已有探针仅作为可行性证据。正式版本、拓扑、权限和阈值必须专项批准。

| 检查项 | 条件与动作 | 预期及需保存证据 |
| --- | --- | --- |
| 原始 + Outbox 原子性 | MySQL 8.4，真实写入及每步异常 | 无半提交，保留事务状态和行级快照 |
| L2 成功前置 | 事务未结束、回滚、提交未知、完成重传 | 前三者不产生成功，记录提交及 ACK 时序。H/I 真机另验收到和控制板释放 |
| 同 ID 同载荷、同 ID 异载荷 | 真库、冲突并行和历史摘要 | 同摘要一对，异摘要取证且原事实不变，保存审计与快照 |
| 并发与多 Relay | 两进程、同候选池、READ COMMITTED/默认隔离分别测 | 正确领取及恢复，保存执行计划和锁、吞吐与行状态 |
| 过期租约与旧回调 | 暂停旧 worker，另一实例接管，再恢复旧确认 | 旧尝试无法更新，保留 ID、计数、时序及条件 UPDATE |
| ACK、NACK、returned、无效路由 | 真实 Broker，触发满额、无绑定、缺交换机和部分订阅缺失 | 仅有效 ACK+无 returned AND 拓扑核对标记成功。留 Broker 状态、返回、确认、尝试 ID |
| 确认丢失、Broker 重启 | 真连接故障与 Docker/节点重启，持久挂载 | 原 ID 再投，保留重复与恢复证据。副本及 HA 另测 |
| 确认后数据库失败 | 真查/更新失败或进程中止，租约接管 | 不静默置成功，审计持久意图与重复发布 |
| Inbox + 事实和业务 Outbox 原子性 | MySQL 8.4 各步约束失败、rollback-only 及提交失败 | 全回滚、无成功 ACK，提供事务和 Broker trace |
| 提交后 ACK 失败 | 真实进程中止，再投 | 一份事实、同 hash 重投重新确认，提供进程退出、查库及 Broker tag 证据 |
| 重复消息、不同 ID 同事实 | 并发、成功重投、异载荷 | 收敛一次副作用、冲突隔离，核对唯一约束和业务快照 |
| 版本不兼容、权限上下文缺失 | 合成未支持版本、错误来源和作用域 | 拒绝并持久隔离，保存失败类别及内部审计 |
| 异步上下文清理 | 执行后同线程复用，异常、超时、重投 | 身份、tenant、项目及审计不残留，保存线程与套件证据 |
| 重试与退避 | 受控时钟和真实 Relay，有界并发 | 时点与重试预算可核验，故障不导致快速无限重投 |
| 死信保留和受控重放 | 断开 DLQ、恢复后批准针对特定 consumer 重放 | 保留失败、原 ID 和摘要、审计及回读。不影响已成功副作用 |
| TRACK 独立故障 | 独立目标连接不可用与恢复，注入迟到 | INGEST 原始事实完整，轨迹受控补算与版本留证 |
| 跨库恢复对账 | 独立备份点差异、Broker 恢复、外部已生效 | 缺失、重复、冲突和审批恢复清单，禁止仅比总量 |
| 原始事实不可变 | 同身份冲突与纠正记录 | 原行及原哈希留存，另有修正审计 |
| 容量、积压和告警 | 批量压测、降速、磁盘/消费不可用 | 连接池健康，待发年龄与告警阈值证据。实际负载与承诺待 DEC-016 |

| 工程回归 | 条件与验收证据 |
| --- | --- |
| 默认 44 项 Reactor | 现有 JDK21/Maven3.9.12 干净构建，本轮未重跑。第二阶段保存退出码、项目数、测试开关 |
| Java release 17 | 检查有效 POM、项目 class major 61 和空 JAR，不把空 JAR 计为已验证 class |
| ENG-002B | 当前边界检查器、有效 POM/依赖树、项目码及指定反例 |
| ENG-002C | 隔离数据库位点、实际物理连接、事务阻断及迁移回归 |
| ENG-002D | 重跑已合并授权验证，分别报告合成、真实 MySQL 和 ENG-002D-02R 遗留 |
| Server | 独立 smoke、原始 Bean 图及授权相关定向校验 |
| Gateway | 临时 IoT Reactor 独立 exec、指定原有三类测试读取 Surefire XML，再检查 Local/现有运行边界。F 的双暂存专项单独验收 |

本轮验证仅执行第 19 节探针和文档检查，现有模块历史回归证据见 ENG-002B/C/D。完整生产环境的 Broker 磁盘、两库事务形成端到端可恢复链、持久 ACK 证据、压测和 HA 都待验。BLOCK-003 的签署需要后续完整验收。

## 21. 风险、未决及人工审批

风险集中在 T1 原始/意图不变量破损、Broker 发布未知、部分绑定缺失、跨库恢复回退、Actor 声明误用和重放跨权。跨库快照及 Broker 位点仍无可保证的原子边界。设计审批不等同 DEC-006 关闭。BLOCK-001、BLOCK-003、BLOCK-007 保持开放，关联 DEC 和 ENG-002D-02R 按各自任务处理。

| 人工审批项 | 待确认内容 | 批准人与记录 |
| --- | --- | --- |
| 原始事件及消息身份 | 设备身份/补传映射、消息命名空间、信封版本和哈希格式 | 待填写 |
| Outbox 键及状态机 | 意图键、查证时的归档、条件更新和持久证据 | 待填写 |
| Relay 领取及租约 | 隔离级别、索引、批量、租期、fence、连接预算 | 待填写 |
| Confirm/returned | 联合成功谓词、超时及重连证据、拓扑核验 | 待填写 |
| RabbitMQ 队列/拓扑 | 初始形态、队列类型、HA、路由、TLS、vhost、发布/消费/运维权限 | 待填写 |
| Inbox 键 | consumerId 和发布者命名空间，载荷冲突区分 | 待填写 |
| 消费 ACK 时点 | T2 事务管理器、提交证明和 Channel 线程 | 待填写 |
| 业务幂等原则 | 合成键和正式领域键、恢复与外部副作用 | 待填写 |
| 重试与失败隔离 | 最大次数/时长/超时、退避上限、死信政策、失败保全 | 待填写 |
| 受控重放 | 原身份、范围、审批、速率、审计及修正命令 | 待填写 |
| TRACK 独立 | 本地去重、定位引用、最新状态和补算 | 待填写 |
| 跨库恢复对账 | 备份拓扑、保护集合、各库与 Broker 水位、差异审批流程 | 待填写 |
| 容量与保留 | 负载预算、Inbox/Outbox/冲突保留与归档、告警和 SLA | 待填写 |
| 模块所有权与依赖 | 组合位置、API 边、正式表和 Gateway 接入 | 待填写 |
| ENG-002E-02 范围 | 优先独立工程 A，版本、隔离条件、退出标准及验收矩阵 | 待填写 |

审批记录应填写实际人、日期、依据 SHA、精确范围及证据。待批准后进入 ENG-002E-02。

## 附录 · 实际 ADR 正文

[ADR-006](../architecture/adr/ADR-006-database-architecture.md) · [ADR-012](../architecture/adr/ADR-012-iot-ingestion.md) · [ADR-013](../architecture/adr/ADR-013-vwcp-protocol.md) · [ADR-014](../architecture/adr/ADR-014-gnss-and-tracking.md) · [ADR-024](../architecture/adr/ADR-024-contract-and-billing.md) · [ADR-027](../architecture/adr/ADR-027-mobile-and-vehicle-terminals.md) · [ADR-029](../architecture/adr/ADR-029-mlps-and-security-compliance.md) · [ADR-031](../architecture/adr/ADR-031-authentication-and-access-security.md) · [ADR-033](../architecture/adr/ADR-033-cryptography-and-iot-security.md) · [ADR-035](../architecture/adr/ADR-035-capacity-and-performance.md) · [ADR-036](../architecture/adr/ADR-036-cache-and-asynchronous-processing.md) · [ADR-037](../architecture/adr/ADR-037-high-availability-and-disaster-recovery.md) · [ADR-038](../architecture/adr/ADR-038-deployment-and-environment-management.md) · [ADR-039](../architecture/adr/ADR-039-observability-and-operations-monitoring.md)

交付检查执行 Git 差异范围与空白检查、Markdown 链接及锚点检查、24 项源码清单、30 项实验场景、14 份 ADR 链接、15 项审批栏和常见凭证模式扫描。
