# ENG-002F-01 Gateway 双 SQLite 暂存专项整改详细设计

状态为候选设计，等待人工审批。工作包 F。[GitHub 分支比较](https://github.com/weeleng/car_weight/compare/master-jdk17...engineering/eng-002f?expand=1)。BLOCK-001 保持开放，DEC-006、DEC-016 及 BLOCK-003/007 保持开放。

## 1. 基线和依据

目标仓库 `weeleng/car_weight`，基线分支 `master-jdk17`，本次分支 `engineering/eng-002f`。执行 `git status --short --branch`、`git ls-remote origin refs/heads/master-jdk17 refs/heads/engineering/eng-002f`、`git fetch --no-tags origin refs/heads/master-jdk17:refs/remotes/origin/master-jdk17`、`git rev-parse HEAD`。实际起始 `ff2182d4fe628899f00d122071accd6b02451348` 与指定值相符。ENG-002A 已完成，B/C/D/E 已合并，ENG-002D-02R 继续独立跟踪。已有未跟踪环境报告保留。

PR #19 合并后的 Maven Actions 页面显示 Success，工作流使用 `-Dmaven.test.skip=true`。这是历史主分支构建，本任务不重新声明测试通过。[Actions](https://github.com/weeleng/car_weight/actions/runs/45933881841) · [Maven 工作流](../../.github/workflows/maven.yml)

| 依据 | 适用范围 |
| --- | --- |
| [AGENTS](../../AGENTS.md)、[架构目录](../architecture/README.md)、[Codex 工作流](codex-workflow.md)、[验证矩阵](validation-matrix.md) | 独立分支、授权边界、证据 |
| [C-01](../architecture/standards/technology-baseline.md)、[C-02](../architecture/standards/time-conventions.md)、[C-04](../architecture/standards/iot-ack-and-durability.md)、[C-05](../architecture/standards/transaction-boundaries.md)、[C-06](../architecture/standards/module-boundaries.md) | 独立 Gateway、UTC 时间、L2 ACK、三库事务及依赖边界 |
| [实施入口](../architecture/reviews/implementation-entry-gates.md)、[审批包](../architecture/reviews/decision-approval-packets.md)、[决策登记](../architecture/reviews/open-decision-register.md)、[实施差距](../architecture/reviews/implementation-gap-register.md)、[生产门槛](../architecture/reviews/production-gate-approval-matrix.md#block-001) | F 必须完成实际唯一接管、历史库及真实 Broker 专项；DEC-006/016 的本地事务、故障域与容量分别审批 |
| [既有分析](iot-gateway-analysis.md)、[ENG-002E 设计](ENG-002E-reliable-messaging-design.md)、[ENG-002E 测试报告](ENG-002E-reliable-messaging-validation-report.md)、[模块骨架](../../sanitation-modules/README.md) | 源码定位、46 项可靠消息证据及装配范围 |

已通过 ADR 索引定位及阅读 [ADR-012](../../docs/architecture/adr/ADR-012-iot-ingestion.md)、[ADR-013](../../docs/architecture/adr/ADR-013-vwcp-protocol.md)、[ADR-027](../../docs/architecture/adr/ADR-027-mobile-and-vehicle-terminals.md)、[ADR-036](../../docs/architecture/adr/ADR-036-cache-and-asynchronous-processing.md)、[ADR-037](../../docs/architecture/adr/ADR-037-high-availability-and-disaster-recovery.md)。正文均为 Proposed。设备、离线缓存、恢复与发布是候选设计来源，历史批准正文待核验。BLOCK-001 要求在真实 RabbitMQ 证明仅一个暂存接管者、存量安全迁移、异常恢复和未确认业务不丢。DEC-006 涉及所属本地事务及可靠消息，DEC-016 涉及保护范围、容量、RPO/RTO、拓扑和故障演练。

## 2. Gateway 源码审计

读取实际源码、独立 exec 构件和前置实际依赖 JSON 树。对 `16ff3321b3744186356983f0563bdbaa1305c798` 执行根 POM、依赖、Framework 和 IoT 运行源码的 Git 差异检查，当前生产输入无差异。隔离启动复用此前等效 Gateway exec JAR。证据位置见本表链接与第 4 节。

| # | 核查 | 行为及风险 | 源码或证据 |
| --- | --- | --- | --- |
| 01 | 启动入口 | `IotGatewayServerApplication#main` 使用 Spring Boot，Gateway 独立进程，扫描两个暂存服务。 | [IotGatewayServerApplication](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/IotGatewayServerApplication.java) |
| 02 | POM 及运行依赖 | exec JAR 保留 SQLite JDBC 3.49.1.0、Boot 3.5.16、Spring AMQP 3.2.12、AMQP Java Client 5.25.0。可在历史依赖树及 JAR 校验。 | [Gateway POM](../../yudao-module-iot/yudao-module-iot-gateway/pom.xml) · [前置构建](ENG-002E-reliable-messaging-validation-report.md) |
| 03 | 配置/Profile | Gateway 自身资源只含 application.yaml，默认 local profile、Redis 总线及 spool=false。Rabbit correlated、returns=true，共享模板 mandatory=false，专用模板另行设置。外部 Profile 需审批及验配置。 | [Gateway application.yaml](../../yudao-module-iot/yudao-module-iot-gateway/src/main/resources/application.yaml) |
| 04 | 消息服务 | `sendDeviceMessage` 生成 Gateway 消息 ID，使用可信设备记录注入 device/tenant/server。符合条件时只注入并调用确认版接口。 | [Gateway IotDeviceMessageServiceImpl](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/message/IotDeviceMessageServiceImpl.java) |
| 05 | 暂存接口 | `isSpoolableMessage`、`sendSpoolableMessage`、`isEnabled` 表达选路和失败语义。接口无 INGEST commit 证明。 | [确认版接口](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/spool/IotDeviceMessageSpoolService.java) |
| 06 | 确认版 | `init/open` 校验总线确认能力、打开 SQLite、启 worker。协议非空、属性/事件/批量非回复可用。 | [IotDeviceMessageSpoolServiceImpl](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/spool/IotDeviceMessageSpoolServiceImpl.java) |
| 07 | 旧版 | 同为 @Service；`init` 只认 Redis，早于打开文件校验。未由当前发送服务调用，它仍自动初始化和读取同表。 | [旧 Redis 暂存服务](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/message/spool/IotDeviceMessageSpoolService.java) |
| 08 | 属性 | 两套实现共用一个 `IotGatewayProperties` Bean，只有一个嵌套 `SpoolProperties`，并非两种独立属性类型。 | [IotGatewayProperties](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/config/IotGatewayProperties.java) · [IotGatewayConfiguration](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/config/IotGatewayConfiguration.java) |
| 09 | DDL | 两套均创建 `iot_device_message_spool` 和时间索引，无版本或所有权记录。 | [IotDeviceMessageSpoolStore](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/message/spool/IotDeviceMessageSpoolStore.java) · [IotDeviceMessageSpoolServiceImpl](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/spool/IotDeviceMessageSpoolServiceImpl.java) |
| 10 | 连接 | 旧版单 JDBC Connection，确认版 SingleConnectionDataSource/JdbcTemplate。各自锁及统计，跨 Bean/进程不共享锁。 | [IotDeviceMessageSpoolStore](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/message/spool/IotDeviceMessageSpoolStore.java) · [IotDeviceMessageSpoolServiceImpl](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/spool/IotDeviceMessageSpoolServiceImpl.java) |
| 11 | 补传任务 | 两个同名 `iot-gateway-spool-drain` 守护线程各自注册固定延迟任务。无共享领取或 fencing。 | [旧 Redis 暂存服务](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/message/spool/IotDeviceMessageSpoolService.java) · [IotDeviceMessageSpoolServiceImpl](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/spool/IotDeviceMessageSpoolServiceImpl.java) |
| 12 | 总线装配 | 按值 `redis/rabbitmq/local/kafka/rocketmq` 创建；没有 type 属性时 Local 匹配；无法识别的值无匹配时启动报依赖。 | [IotMessageBusAutoConfiguration](../../yudao-module-iot/yudao-module-iot-core/src/main/java/cn/iocoder/yudao/module/iot/core/messagebus/config/IotMessageBusAutoConfiguration.java) |
| 13 | Redis | 使用 Streams XADD，确认版检查返回 RecordId；group listener 成功后 XACK，可独立 pending 重投与清理。不是 Pub/Sub。 | [IotRedisMessageBus](../../yudao-module-iot/yudao-module-iot-core/src/main/java/cn/iocoder/yudao/module/iot/core/messagebus/core/redis/IotRedisMessageBus.java) |
| 14 | RabbitMQ | 独立 mandatory 模板、相关确认、returned，普通 `post` 不等确认；消费者容器 Spring MANUAL，订阅者返回后 ACK。 | [IotRabbitMQMessageBus](../../yudao-module-iot/yudao-module-iot-core/src/main/java/cn/iocoder/yudao/module/iot/core/messagebus/core/rabbitmq/IotRabbitMQMessageBus.java) |
| 15 | 确认接口 | `postConfirmed/validateConfirmable` 总线特定。暂时失败与 `IotMessageRejectedException` 的永久拒绝路径区别明显，当前 drain 有跳过并删除风险。 | [IotMessageBus](../../yudao-module-iot/yudao-module-iot-core/src/main/java/cn/iocoder/yudao/module/iot/core/messagebus/core/IotMessageBus.java) · [IotMessageRejectedException](../../yudao-module-iot/yudao-module-iot-core/src/main/java/cn/iocoder/yudao/module/iot/core/messagebus/core/IotMessageRejectedException.java) · [IotDeviceMessageProducer](../../yudao-module-iot/yudao-module-iot-core/src/main/java/cn/iocoder/yudao/module/iot/core/mq/producer/IotDeviceMessageProducer.java) |
| 16 | Kafka/RocketMQ | 装配还受类在 classpath 条件控制；实现提供确认发布路径。即使新服务认可，开启时旧服务仍拒绝非 Redis。 | [IotKafkaMessageBus](../../yudao-module-iot/yudao-module-iot-core/src/main/java/cn/iocoder/yudao/module/iot/core/messagebus/core/kafka/IotKafkaMessageBus.java) · [IotRocketMQMessageBus](../../yudao-module-iot/yudao-module-iot-core/src/main/java/cn/iocoder/yudao/module/iot/core/messagebus/core/rocketmq/IotRocketMQMessageBus.java) · [IotMessageBusAutoConfiguration](../../yudao-module-iot/yudao-module-iot-core/src/main/java/cn/iocoder/yudao/module/iot/core/messagebus/config/IotMessageBusAutoConfiguration.java) |
| 17 | 协议与回复 | HTTP/MQTT/TCP 上行处理器走业务消息服务，异常及协议回复各自处理。raw 入口与普通 JSON 校验路径不同，回复不走 spool。现有协议反馈未经真设备 L2 ACK 验证。 | [IotHttpUpstreamHandler](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/protocol/http/handler/upstream/IotHttpUpstreamHandler.java) · [IotMqttUpstreamHandler](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/protocol/mqtt/handler/upstream/IotMqttUpstreamHandler.java) · [IotTcpUpstreamHandler](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/protocol/tcp/handler/upstream/IotTcpUpstreamHandler.java) · [Gateway IotDeviceMessageServiceImpl](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/message/IotDeviceMessageServiceImpl.java) |
| 18 | 测试 | 上次 Confirm 2、Store 4、Spool 17，共 23 项 XML 通过。Confirm 测试主要是配置验证，不能代替实际启动冲突和交接。 | [IotRabbitMQMessageBusConfirmTest](../../yudao-module-iot/yudao-module-iot-core/src/test/java/cn/iocoder/yudao/module/iot/core/messagebus/core/rabbitmq/IotRabbitMQMessageBusConfirmTest.java) · [IotDeviceMessageSpoolStoreTest](../../yudao-module-iot/yudao-module-iot-gateway/src/test/java/cn/iocoder/yudao/module/iot/gateway/service/device/message/spool/IotDeviceMessageSpoolStoreTest.java) · [IotDeviceMessageSpoolServiceImplTest](../../yudao-module-iot/yudao-module-iot-gateway/src/test/java/cn/iocoder/yudao/module/iot/gateway/service/device/spool/IotDeviceMessageSpoolServiceImplTest.java) |
| 19 | 生命周期 | @PostConstruct 及协议管理启动，@PreDestroy 各 worker shutdownNow/最多等待 5 秒，再关闭存储；无法仅据此证明批次删除与异常关机的原子性。 | [IotProtocolManager](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/protocol/IotProtocolManager.java) · [旧 Redis 暂存服务](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/message/spool/IotDeviceMessageSpoolService.java) · [IotDeviceMessageSpoolServiceImpl](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/spool/IotDeviceMessageSpoolServiceImpl.java) |
| 20 | Server RPC | `IotDeviceMessageServiceImpl` 通过设备服务按产品/设备可信记录填充上行，配置 `yudao.iot.gateway.rpc.url` 指定平台；透传及配置 RPC 独立于 Broker 确认。 | [Gateway IotDeviceMessageServiceImpl](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/message/IotDeviceMessageServiceImpl.java) · [Gateway application.yaml](../../yudao-module-iot/yudao-module-iot-gateway/src/main/resources/application.yaml) · [Gateway IotDeviceServiceImpl](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/IotDeviceServiceImpl.java) |

## 3. 两套 SQLite 服务对比

本文称确认版为 S1，旧 Redis 版为 S2。双方都是扫描 Bean，属性开关控制初始化而非注册。

| 维度 | S1 确认版 | S2 旧 Redis 版 |
| --- | --- | --- |
| Bean、类型、注入 | `iotDeviceMessageSpoolServiceImpl` 实现 spool 下接口；发送服务注入此类型 | `iotDeviceMessageSpoolService` 为 message/spool 下具体类，未实现该接口 |
| 依赖与条件 | 无专用显式构造参数，通过 @Resource 注入同一个 gatewayProperties、deviceMessageProducer、messageBus；无条件注册 | 同样的 @Resource 及无条件注册 |
| PostConstruct | 检查 enabled、Local、容量、过期及 validateConfirmable，随后初始化文件、计数、调度 | 检查 enabled，仅 Redis，通过后打开 store、计数、调度 |
| 配置 / 路径 | 共用 `yudao.iot.gateway.spool`、enabled、`data/iot-gateway-spool.db` | 同一属性对象、同一路径 |
| 支持总线 / 范围 | Redis/Rabbit/Kafka/RocketMQ 按确认能力，Local 跳过。非空协议、非回复的指定上报方法 | Redis。方法范围相似，限制 MQTT/HTTP。当前不接管主上行入口 |
| SQLite 主键 / 表 / 索引 | `iot_device_message_spool(id INTEGER PRIMARY KEY AUTOINCREMENT, spool_time INTEGER NOT NULL, size INTEGER NOT NULL, message TEXT NOT NULL)`，`idx_iot_device_message_spool_time(spool_time)` | 完全相同。没有分服务表或业务身份索引 |
|序列化与身份| 同一个 `IotDeviceMessage` JSON，包含既有 id 与 spoolTime，无版本字段校验或唯一键。UTF-8 长度计费 | 同 JSON，补传读相同列，同一个 id 可保存多行 |
| 持久与事务 | 单连接 JDBC，`quick_check`、busy_timeout=5000、WAL、synchronous=FULL。普通 SQLite 自动提交，读取、网络等待、批量删除分别执行 | 单连接与相同 PRAGMA，类似自动提交。无跨发送事务 |
| 并发与计数 | 单独 JVM `lock`、本地 count/bytes；网络在锁外。独立单线程执行器 | 自身 `lock` + store 同步方法及单独 count/bytes，网络在锁外。与 S1 不共享，统计漂移风险 |
| 调度与顺序 | 1 秒 fixed delay，按 id 取 100 条，正向 drain rate 计算睡眠；新消息有积压优先写盘 | 同主要机制。两者之间没有统一顺序或抢占 |
| 重试 / 确认 | 暂时/未知确认停在该记录，下一轮重试。永久拒绝跳过；直发永久拒绝返回错误，不落库 | 普通 Redis post 无异常就视为发出；失败停在该行，下一轮重试 |
| 删除、过期 | 按已处理最大 id 范围删，处理包含确认、坏 JSON/缺 spoolTime、超过窗口及永久拒绝。每批可按 60 秒门槛清理，逐条还检查 spoolTime | 发送或坏数据处理后同方式范围删，轮次开始做定期清理 |
| 容量 | 逻辑配额取 SUM(size) 与本实例增减。默认 2GB、7d、200/s。文件、WAL及同文件其他写者无统一预算 | 同基础配置和本地统计 |
| 异常及关闭 | 文件打开失败可继续启动，只能尝试直发，落库应拒绝。启动前校验失败则终止。线程中断并等待后关闭 | SQLite SQL 打开错误可继续启动；不支持总线直接使 Bean 初始化失败。停线程后关闭连接 |

**已证实** 两个服务启用时会打开同库同表，Spring 配置实例相同，Redis 启动时各自调度并可重复补传。RabbitMQ 启用失败来自 S2 的 PostConstruct。SQLite 写锁只串行化写入，不能覆盖读→网络→删的空档。并发统计偏差、重复频率、删除/保管风险随实际部署和时序变化，不能凭单次证据估计生产发生率。

## 4. 实际启动及配置矩阵

执行前通过 `git diff --quiet 16ff3321b3744186356983f0563bdbaa1305c798 HEAD -- pom.xml yudao-dependencies yudao-framework yudao-module-iot` 检查等效源码。实际使用 `/workspace/eng002e02r-regression/iot/yudao-module-iot/yudao-module-iot-gateway/target/yudao-module-iot-gateway-exec.jar`，构件 SHA-256 为 `a00a4ac0d8f3f371f6f3c6a2de946ac2872d5f985639a49000148ae0ad271ef0`。运行镜像使用 Temurin 21，探针经 JDK 21 `javac --release 17 -proc:none` 编译。RabbitMQ 4.2.9 和 Redis 7.4.12 运行在内网容器，无宿主或外部设备端口。随机凭据经环境注入，profile 不连生产，RPC 指到容器内 `127.0.0.1:1`，未使用真实设备。SQLite JDBC **3.49.1.0** 调用 Java JDBC `SELECT sqlite_version()` 得 **3.49.1**。Python 辅助探针 SQLite 版本为 3.53.1，分开记录两种环境。

```bash
e=/workspace/eng002f01-evidence
bash "$e/boot.sh" > "$e/boot/runner.log" 2>&1
bash "$e/conditions.sh" > "$e/conditions/runner.log" 2>&1
python3 "$e/history/history.py"
bash "$e/traffic.sh" > "$e/traffic/runner.log" 2>&1
bash "$e/traffic-recovery.sh" > "$e/traffic-recovery/runner.log" 2>&1
```

启动脚本与总线条件复验脚本均退出 0。隔离容器内 RabbitMQ 开启用例的 Java 命令如下，Redis 用例替换总线值，两组开关都分别运行。镜像和网络配置保留在探针脚本与 `boot/image-digests`。

```bash
java -XX:ActiveProcessorCount=2 -Xmx256m \
  -Dloader.path=/validation/classes -Dloader.main=fprobe.Probe \
  -cp /validation/gateway-exec.jar org.springframework.boot.loader.launch.PropertiesLauncher \
  --debug --spring.main.web-application-type=none \
  --spring.data.redis.host=redis --spring.data.redis.port=6379 --spring.data.redis.database=15 \
  --spring.rabbitmq.host=rabbit --spring.rabbitmq.virtual-host=fixture \
  --yudao.iot.gateway.rpc.url=http://127.0.0.1:1 \
  --yudao.iot.message-bus.type=rabbitmq --yudao.iot.gateway.spool.enabled=true \
  --yudao.iot.gateway.spool.file=/data/spool.db
```

各例分别记录 `.ready`（0 表示 ApplicationReadyEvent）、`.exit`（真实容器退出码）、`.log`（堆栈、Condition Evaluation Report、Bean 及接管证据）。脚本用 `PropertiesLauncher` 运行同一 Gateway 应用，探针读取 Bean 生命周期与 Spring Boot 条件计算结果；启动参数为 `--debug --spring.main.web-application-type=none`，并显式传入总线、暂存开关与临时文件。Kafka/RocketMQ 未部署 broker。关停 143 来自主动停止。

| 配置／文件 | 实测或静态 | 结果 |
| --- | --- | --- |
| Redis + 关闭，RabbitMQ + 关闭 | 实测 | 两 Bean 完成生命周期但无 SQLite/worker，均就绪、主动退出 143 |
| Redis + 开启，文件原不存在 | 实测 | 就绪，生成单表/index。S1 后 S2 初始化完成，二者持有相同 Config Bean，各有执行器和同名 worker |
| RabbitMQ + 开启 | 实测 | S1 先打开、创建同表且启动执行器，S2 开始初始化后抛 `IllegalStateException: 离线暂存只支持 Redis 消息总线，当前为：IotRabbitMQMessageBus`。Condition report 命中 Rabbit 分支，未 ready，退出 1。失败仍在卷留下 SQLite 文件 |
| Kafka + 关/开；RocketMQ + 关/开 | 静态 | 关的情况下两 Bean 不开库，正常启动仍取决于总线客户端及环境。开时 S2 拒绝非 Redis。未启动这两种 broker |
| type 属性缺失，暂存关 | 实测 | 用从构件提取的配置副本只移除消息 type 项，Local 自动配置匹配，就绪，0 worker。若开，按源码 S1 跳过、S2 拒绝 |
| type 错误值 | 实测 | 暂存关也无法注入 IotMessageBus，退出 1 |
| 已有库及历史积压 | 实测 | Redis 正常启动，两个旧实现兼容读取；发生重复补传，见第 19 节 |
| 文件损坏 | 实测 | 两次 SQLITE_NOTADB，保留文件，应用就绪但 0 worker。此降级有写入保护但无自动修复 |
| 目录不存在且卷可写 | 实测 | 下级目录被创建，文件创建，两 worker |
| 现有文件置于只读卷 | 实测 | 两端 SQLITE_CANTOPEN，应用就绪、0 worker，不能据就绪判定暂存健康 |
| 新库目录置于只读卷 | 实测 | SQLite/路径创建失败，应用就绪、0 worker。单独权限模式与实际部署用户仍待验 |
| 两 Gateway 指同一文件 | 实测 | 两 JVM 均 ready、各两个 worker，没有唯一拥有者拒绝。该用例用空积压，未测生产双进程重复率 |
| 未知 user_version | 合成夹具 + 启动复验 | 版本 99 的库无批准迁移映射。当前代码不检查 user_version，待治理改为人工确认后才能接管 |

`conditions/injected-deps.log` 与 `conditions/rejected-legacy.log` 中的 Spring Boot Condition Evaluation Report 显示相应 Redis/RabbitMQ 类型选中，RabbitMQ 的 `rabbitAdmin` 也匹配。Kafka 和 RocketMQ 客户端类在构件中可见，但类型条件未命中。启动探针分别记录 `F01 POST`、`F01 STATE` 的配置对象 identity、连接/store 和执行器。当前线程测量统计同名工人线程，定时任务数由每个执行器与源码单个 `scheduleWithFixedDelay` 对照。实际启动次序属于本构件和配置，整改不得依赖同一次序。另对正确的 Bean 名 `iotGatewayDeviceMessageServiceImpl` 查询实际依赖，确认其依赖 S1 与 producer、Gateway 设备服务、序列化管理器及共享属性。S2 作为独立扫描 Bean 启动。

## 5. 当前数据流和保管责任

所有行从「协议接收→校验设备/RPC→填充 Gateway 的临时消息身份」开始。原始设备身份由 DEC-002/G 定义，当前 Gateway 生成 ID 只能用于其自身关联。设备 L2 ACK 均要求后续 INGEST 原始事实及可靠 Outbox 本地提交，表中前置阶段不会产生这个证据。

| 链路 | 发送、落盘、确认、删除与责任时点 |
| --- | --- |
| 1 Redis 正常 | → XADD → streams RecordId → 当前无本地积压时不写 SQLite。Redis 接收后负责流存储，消费者回调成功 XACK。发布返回也可发生在未建组或离线时，真实持久性及 INGEST 提交各有独立状态 |
| 2 Redis 失败 | → XADD 抛异常 → SQLite 自动提交且刷盘配置成功 → Gateway 文件暂时保管 → 后续 XADD 返回后批次删除。写盘失败则拒绝当前入口，设备有待确认原始事实 |
| 3 RabbitMQ 确认成功 | → 相关 attempt → Confirm ACK 且无 returned → 可讨论 Broker 接管，当前实现不查全部预期绑定。普通 `post` 无该确认。消费者提交及 ACK 更晚；正常开启暂存当前无法启动 |
| 4 RabbitMQ 失败 | → NACK/returned/未知 → S1 降级 SQLite 提交并保留 → 恢复后按原 JSON 重试。S2 会阻断原装配，独立探针只验证 S1 行为 |
| 5 重启补传 | → 按 SQL id 读取旧 JSON → 保持其已保存 Gateway ID → 发布 → 批次删除。若旧记录坏、超期或被视为永久拒绝，当前实现也可跳过删除，生产方案须保护关键事件 |
| 6 Confirm 丢失 | → Broker 可能已有消息 → 发布者结果未知 → 本地保留或落盘 → 原身份再次发送。订阅端去重；消费 ACK 和设备 L2 ACK 依然独立 |
| 7 发布成功、删除前退出 | → Broker 与本地文件暂同时保管 → 进程退出 → 下一进程重放同一 ID → 后续业务使用 Inbox。删除恢复需按确认证据再决定 |
| 8 暂存满额 | → 逻辑配额拒绝新写 → 旧记录仍在，未交接的新事实继续由上游持有。禁止以删除未确认记录腾空间 |
| 9 历史恢复 | → 校验独占、完整性和版本 → 一致性备份/WAL 恢复 → 按原 ID、Hash 迁移并验证 → 受控补发 → 分段核对 Broker、INGEST 与业务事实 → 按证据清理 |

## 6. 整改方案

| 方案 | 改动、兼容和数据 | 重复、恢复、容量与运营 | VWCP、E 边界及回滚 |
| --- | --- | --- | --- |
| A 保留 S1，停止 S2 接管 | 优先。最小变更 Spring 装配与启动断言，保留 Redis 和 Rabbit 路径，经验证再专门迁移。旧代码先隔离注册，避免误删 | 单接管消除双 drain，仍允许发布后删前重复，另加文件锁、恢复计量、保全与监控 | 为未来 VWCP 保留身份和交接接口。复用 E 验证标准，禁双写。只在无新增承诺时直接回滚 |
| B 重构统一组件 | 长期候选。独占协调器、存储、策略、Receiver/Relay 分层，改动及升级复测多 | 生命周期、容量和故障处理清晰，需验证更多客户端、迁移和观测 | 最适合多类别策略和正式契约。迁移/回滚复杂，先验证 A |
| C 按总线装配不同服务 | 能避免同一 JVM 两 Bean，仍有多个实现、相同库切换与不一致删除语义 | 维护重复代码，历史文件与多实例切换风险仍需统一锁和迁移 | 不建议作为长期接管。切换总线后的责任/回滚证明较重 |
| D 完全依赖终端和后端 | 本地设计简化，已有 Redis 及历史库搬运成本、旧终端协议兼容和离线压力增长 | 网关磁盘容量简单，未验证终端持久性时故障窗口变大 | 只有 G/I、E 正式闭环和历史保全获批后再考虑，当前否决 |

推荐 **A** 作为实际 Gateway 最小整改，**B** 保留长期候选。先通过隔离验证证明兼容和迁移，随后单独批准正式源码变更，最后以生产门槛审批启用。不存在以删除旧库或旧代码代替识别/迁移的捷径。

## 7. 唯一接管

- 启动以显式互斥条件仅激活一个接管实现，Spool 启用时做唯一实现及执行器断言。校验配额、相对路径、实际规范路径、文件或父目录、schema、支持的总线/策略。任一条件未经检查时进入隔离或失败关闭。
- 单 JVM 唯一 Bean 和跨 JVM 文件所有权分别验证。建议在同一可信本地持久卷使用文件 canonical path 与持久旁路锁，由进程获取 OS advisory exclusive lock 后才读、迁移、清理或调度。锁持有至停止接收、处理有界 in-flight、关闭数据库结束。锁文件存在不能证明有主；进程崩溃由 OS 回收锁。PID 复用和容器标识用于诊断，不能作为夺锁依据。
- SQLite 单写事务是数据安全底线，无法覆盖网络间隔。可对已独占实例用短领取事务及 owner/version/lease 防过期任务更新。出现锁残留、文件重命名/替换、软链接或卷迁移时拒绝盲接管。共享卷上的文件锁与 WAL 语义需显式验实；多实例默认各有独立路径和生命周期，不默认共享数据库。
- 遗留版本无新锁能力。先经调度/运维 fencing 确认旧进程退出并停用旧部署，保持只读备份和迁移审批登记。不能靠新代码的锁阻止旧二进制写入，滚动升级要区分独立卷和单卷停机迁移。

## 8. 本地持久化

| 策略 | 取舍 |
| --- | --- |
| A 正常直发，失败时写盘 | 维持一般遥测延迟，接收后写盘前及发送失败后降级写前有崩溃窗口。可能已到 Broker 却未知，依赖身份和上游重传 |
| B 所有关键事件先写盘 | 推荐评估关键计量。先事务落盘，再取得尝试，接收时延和 WAL/IO 上升，需盘水位、批量节流及磁盘验证 |
| C 按类别执行 | 建议目标。经批准分类后的关键计量先落盘，普通遥测保留直发且可降级。分类未知禁冒充关键确认；具体规则需协议/业务审批 |

SQLite 提交未知时按预期身份与载荷，在新连接和文件所有权验证后查询证据。避免因错误重发生成新身份。批量提交只允许向已证实的本地事务返回，并限定最大批大小、写锁时间和等待。WAL+FULL 不证明掉电、磁盘损坏和卷丢失后完整，终端重传和 INGEST 提交证据始终独立。

## 9. 历史 SQLite 与识别

从 S2 实际 `IotDeviceMessageSpoolStore.open/insert` 和 S1 实际 `open/insert` 生成合成库。相同单表、时间索引、`sqlite_sequence`、`user_version=0`、WAL，以及相同的原始字节和行摘要。相同结构不能从历史文件反证唯一写者或数据全由某个版本生成。记录时间 `spool_time` 是 Gateway 接收时刻毫秒，补传顺序按本地 AUTOINCREMENT id，JSON 自带 `spoolTime`。没有明确协议版本或设备事件 ID 列。用 `sqlite_master`、`table_info`、`index_list`、`quick_check`、行数、大小与摘要确认候选识别。历史内容不能仅凭字段存在判定可安全补发。

| 合成夹具 | 发现与处置 |
| --- | --- |
| 空库、S1、S2 | 空库需按期望版本初始化。两个实际 DDL 一致，`user_version=0` 用结构指纹与发行清单匹配。不能区分具体产出者 |
| 两套结构共存 | 同库原生两个 DDL 都为 IF NOT EXISTS 且同名，收敛成一表，不产生两套表。合成混合库加入未知额外表，模拟人工复制或外来结构，禁止静默忽略 |
| 积压、重复、无效 JSON、缺少 id | 合成积压保留原行及字节。去重与冲突只按经证实身份处理；坏或缺身份进入隔离，不猜测、覆盖或自动丢弃 |
| WAL/SHM、未知版本、损坏 | 未 checkpoint 的 WAL 含已经提交记录，副本只拷主文件可漏数据。SHM 为协调文件，迁移时不得凭是否存在判断事务。版本 99 无批准映射，损坏库先隔离并保全原文件 |

## 10. 历史迁移和回滚

1. 划定节点、卷、关键事件保管范围，停止旧接收和补传，核对旧进程与副本已退出。获取唯一所有权，记录路径、设备和版本。
2. 检查主库及 sidecar、SQLite 完整性/版本，使用 SQLite online backup API，或在已确认停写且作用域获批后 checkpoint 并验证再备份。统计原行数、ID 范围、重复及未知记录、哈希清单，单独保留只读源和备份。禁止带写入时只拷贝 .db。
3. 匹配经批准的 schema/载荷转换表。在目标临时文件中用原始保管键（来源站点/卷世代/原行 id）记录映射，再保留可验证的逻辑身份、原字节哈希和元数据。未知列、版本、重复异载荷、缺身份隔离待确认。不得删除源，禁止静默 DROP。
4. 用唯一迁移执行号、来源清单哈希和目标事务提交标记做幂等重复执行。验证结构、行数、各身份摘要、隔离清单、WAL 与磁盘余量，目标一致后原子更新接管标记或切换独立路径。启动唯一补发按批次对账，经人工批准恢复完整接收。
5. 迁移前失败保持源并退出。迁移事务失败保留源，回滚目标事务或重建临时文件。迁移已完成未补发且无新接收时可依据清单恢复旧读/写路径，仍须避免两个 writer。新版本已收新数据、已部分发布或已有身份外部生效时先闭环对账。清单导出新增或未交接记录，使用当前兼容路径前向修复，或审批后受控重放。禁止直接覆盖新版本文件或重新发号重试。

迁移装置的故障中断和重试经独立测试后审批。保持运行见证、备份校验、谁批准/谁接管、身份对账报告及回滚理由。没有 snapshot/容量/身份证据时暂停生产。

## 11. 候选暂存模式和状态机

候选字段为 `local_record_id,event_id,message_id,producer_namespace,payload,payload_hash,schema_version,message_category,source_protocol,received_at,persisted_at,status,attempt_count,attempt_id,lease_owner,lease_until,next_retry_at,last_error,broker_confirmed_at,handoff_evidence,version`。必要时另加迁移来源和审计键。事件发生与接收、落盘时刻分别记录。Schema、唯一索引和版本签署后实施，不能直接搬用 ENG-002E 的 MySQL 实验 DDL。

| 转移 | 持久证据和并发要求 |
| --- | --- |
| 接收→PENDING | 保留原 ID 和载荷、经过分类与容量检查，本地事务提交。只代表 Gateway 本地暂存 |
| PENDING/RETRY_WAIT→PUBLISHING | 短事务取得租约与 attemptId、version、owner，立即提交，网络在事务外 |
| PUBLISHING→RETRY_WAIT | 未知、暂时失败保留原身份，条件更新及下一时点。状态更新失败允许保留旧租约并恢复 |
| PUBLISHING→BROKER_CONFIRMED | Confirm ACK、无 returned，并核对相应拓扑资格，按 attempt/version 记证据；这一步尚未证明 INGEST 提交 |
| BROKER_CONFIRMED→HANDOFF_COMPLETE | 核查获批交接政策。关键数据等可审计的 INGEST T1 提交证明，或未来单独批准的可靠托付机制。先记可对账证据再按政策删除/归档 |
| 任意待处理→QUARANTINED | 格式、冲突、损坏或耗尽后保留原字节、原因及人工待办。恢复审批后复用原 ID，按证据重新调度 |

HANDOFF_COMPLETE 可只作为持久审计记录。没有已确认的保管方证据时不得清理关键数据。所属 SQLite 是网关近端暂存，INGEST Outbox 是正式提交中的可靠消息意图。

## 12. 身份、去重与负例

| 标识 | 已有行为 / 候选处理 |
| --- | --- |
| Gateway `id/messageId` | `IotDeviceMessageServiceImpl` 每次入口生成新 id。SQLite 重放直接解析旧 JSON，通常保留生成时 ID。设备重新走入口可能重新生成，不能作正式设备 Event ID |
| `requestId` | 设备提供时保留，缺失时补 Gateway id。用于请求响应相关，不能假定唯一或抗重放 |
| `deviceId,tenantId,serverId` | 来自可信设备记录/当前网关，重连和调拨需区分历史事件的身份与当前连接 |
| `method,reportTime,spoolTime` 与载荷 | 用于类型/接收及补传测量。重放保存原字节和已识别的 schema，不以当前时间、重量、RFID 或单独摘要生成新设备身份 |
| 四层身份 | 正式设备 Event ID 待 DEC-002/G，稳定逻辑 Message ID 及业务幂等键按 INGEST/E/业务合同。每次 Gateway/Broker 尝试另有 attemptId，重放不改逻辑身份 |

同身份同载荷可沿用原交接证据，同身份异载荷隔离并保持原事实，不同身份同载荷各留一份。覆盖入口重新编号、Broker 丢确认、进程重启、重复历史导入和迁移后补发。旧 JSON 无可靠 ID 时先记录源行映射，不借合成键冒充设备真实身份，涉及归属或计费由业务专项复核。暂存重复与 Broker 重复分别测；业务去重由 INGEST T1/Inbox/业务唯一约束，而非 SQLite 行 id。

## 13. RabbitMQ 交接与清理

优先复用 E 的原则，专用 mandatory，CorrelationData 用独立 attemptId，等待相关 Confirm，实际检查 returned 与所有预期绑定及持久性。ACK+returned、NACK、无效 exchange/Channel 关闭、确认超时或断线、错拓扑均留原始事实，重启按相同 ID 重放。Broker 已确认而 SQLite 标记失败视为双侧可能都有记录，旧 attempt 不可覆盖新状态。删后下游失败须有已批准的持久恢复责任，不能只靠默认 nack 丢弃。

| 清理条件 | 故障域与取舍 |
| --- | --- |
| A Broker 确认可靠接收后 | 降低文件占用，仍需拓扑完整、持久节点/卷、Broker 失败保全及恢复、INGEST 对账、终端可靠重传证据。Broker 事故和删后下游失败可成丢失窗口。关键事件暂不批准 |
| B INGEST 提交证据后 | 推荐关键事件。需要权威 T1 回传或查证，离线保存更久、容量量化、过期证据与故障调度都要验证 |
| C 分阶段 | 推荐组合。分别记录 SQLite、Broker、INGEST 或已审批托付的时点，关键数据仅在证据完整后删。非关键路径按获批留存策略，不阻断关键计量 |

设备成功 L2 ACK 仍由 C-04 定义，正式回传、编码及控制板释放留给 G/I。Gateway 只能上报已观察的各层状态。

## 14. Redis 兼容

现有 `IotRedisMessageBus` 是 **Redis Streams**。S2 普通 XADD 不检查返回值，S1 确认检查 RecordId。无 group 或消费者离线不阻止 XADD，是否可恢复依赖流存留、游标、消费者组/PEL、pending 重投、清理与 Redis RDB/AOF/故障切换配置。消费者方法返回后另有 XACK。Redis 连接异常才走暂存，重启恢复依当前部署持久化及故障域。本轮隔离 Redis 的配置不能代表生产。保留 Redis 基础模式，采用 S1 统一接管与兼容回归；在专项证据批准前不把 Redis 本身当作关键计量可靠交付终点。

## 15. 容量、保留和背压

逻辑量估算 `峰值事件/秒 × 单事件平均字节 × 最长离线秒 × 并发设备与峰值余量`，若峰值已是聚合值则避免重复乘设备。另按最大载荷/高分位、行/索引字节、WAL 峰值、临时迁移区、备份副本及文件系统 reserve 推算物理峰值。补传净处理速率是稳定发送速率减持续新增率。净速率非正就不承诺清理时长。重连成批补传、磁盘 IOPS、瞬时占用和进程数都应压测。

现有 2GB 只看消息 UTF-8 `size`，WAL、主库、索引和迁移占用不包含。7d 可删记录，200/s 为单 worker 默认并受网络时延影响。推荐分预警/高水/低水、磁盘保留空间、读写限流、关键优先和多设备公平。高水限速及暂停普通遥测，物理保留触发拒绝或上游背压。保持未交接关键记录及其失败证据，不按默认七天清理。满额时拒绝新增并明确上游仍持有责任。普通遥测按已批准策略降级，解除背压用低水滞回。所有阈值及过期规则列审批。

## 16. 重试、恢复和顺序

- 发送失败、结果未知、本地更新失败分别记原因，超时/瞬态用有上限指数退避加随机抖动。拓扑错先暂停受影响路由，坏格式、身份冲突、数据库损坏、永久不兼容进入隔离。失败次数、最大年龄、单次网络 timeout、最大速率和批量有界，耗尽仍保留关键事实。人工重放核查原身份、证据、目标消费者、批准人与结果，禁止重号绕过 Inbox。
- 多设备接收共享 SQLite 单写者，控制接收/清理/领取采用短事务；网络确认在事务外。续租/fence 保护陈旧回调。限时 checkpoint 防 WAL 压垮磁盘，长读/备份和关闭竞态须验。正常关闭先冻结接收及领取，按有界时间完成 in-flight，未确认证据保留后再关库和锁。崩溃后由新拥有者读现存状态及 lease。
- 允许不同设备并行。新事件和历史积压按优先级与公平性调度，同设备顺序只在获批业务约束下按聚合/序列校验。重投与失败都会乱序。Epoch、Seq 与发生时间语义留给 G。

## 17. ENG-002E 接口边界

Gateway 本地暂存保护网关收到但尚未按合同交接的数据。INGEST T1 原始事实与 Outbox 的原子写入承担正式可靠接入。网关重发保持原 Event ID 和逻辑 ID，由持久事件唯一键、内外字段与意图完整性核查识别。Broker 可以重复投递，BUSINESS 的 Inbox/事实/后续 Outbox 同事务，且提交后才消费 ACK。失败保全以及恢复用各层身份和持久证据对账，避免出现「Broker 有/SQLite 无」便推断 INGEST 已提交。正式 Gateway 通过独立获批接口交流确认。本轮在起始 SHA 的独立归档中重跑 E 验证工程，隔离 MySQL 8.4.13、RabbitMQ 4.2.9。A/B/C/D 实际执行 21/14/7/4 项，均退出 0，共 **46 项通过，0 失败、0 错误、0 跳过**。依赖树检查退出 0。该工程只作隔离参考，无 Gateway Bean 或生产依赖。

```bash
e=/workspace/eng002f01-evidence
dev=/workspace/car-weight-dev-jdk17
export JAVA_HOME=/workspace/car-weight-eng002a-validation/jdk
export PATH="$JAVA_HOME/bin:$dev/apache-maven-3.9.12/bin:$PATH"
cd "$e/e-source"
python3 script/messaging/validation/run-lab.py --evidence-dir "$e/e-reference" -- \
  -s "$dev/maven-settings.xml" \
  -Dmaven.repo.local=/workspace/eng002e02r-regression/m2-iot
```

实际结果见 `e-reference/{A,B,C,D}/result.json`、同目录 Maven 日志和 Surefire XML，以及 `e-runner.exit`。

## 18. 观测和部署

候选指标包括待交接记录与最老年龄、逻辑字节、主库/WAL 大小、磁盘剩余、拒绝和暂存提交失败、发布成功、Confirm 超时和 returned、重复补传、过期 lease、隔离记录、迁移差异、对账差异、接管冲突。结构化日志记录阶段、结果、总线/文件别名、节点、版本、稳定记录与尝试关联、错误类及经最小化的摘要。每个 eventId、deviceId 等不进常规指标标签。禁记密钥、密码及未经脱敏业务载荷。采样与关键审计留存待审批。

单网关使用验证过的本地持久卷及独占锁，非 root、授权读写 SQL 主文件和 sidecar，核查 umask、inode、挂载方式、文件系统写屏障和盘余量。多实例默认分别使用卷和文件，同时验证设备重连及同身份跨节点重投。滚动升级不能让新旧写同库。逐卷冻结、备份、验证、切换；新数据到达后不能覆盖回滚。切 Redis/Rabbit 需明确历史行、路由及所有权，不默许自动接管。读写失败、丢盘/丢主机分别告警及恢复。SQLite 的故障域为本地进程、文件系统与卷所在主机，不能声明跨主机 HA 或 RPO=0。DEC-016/BLOCK-007 仍开放。

## 19. 隔离实验记录

证据保留在 `/workspace/eng002f01-evidence`。分类为真实旧装配、实际 SQLite/JDBC 或 Broker、临时手工接管、受控模拟和待验。编译的诊断挂载、SQL 文件、JSON 与脚本均在仓库外。32 项试验范围如下。

| # | 场景 | 实际证据与边界 |
| --- | --- | --- |
| 01 | Redis 开 | 真实 Gateway 启动，两个服务同库同表，各一 worker。见 `boot/redis-on.log` |
| 02 | RabbitMQ 开 | 真实 Gateway，S2 抛初始化异常、退出 1，S1 先建库。见 `boot/rabbit-on.log` |
| 03 | 暂存关 | Redis/RabbitMQ 都就绪，0 worker |
| 04 | 初始化冲突 | 两个 Bean 的 PRE/POST、重复配置对象、条件报告和 RabbitMQ 异常 |
| 05 | 两进程共用文件 | 同卷两个 Redis Gateway 实例，各 2 worker，未互斥。使用空库 |
| 06 | 重启接管 | 独立 Rabbit 临时接管探针重启同卷，旧行可读取。正式唯一接管仍待实现 |
| 07 | S1 历史库 | 实际 SQLite JDBC 3.49.1.0 打开/插入，schema、行和校验快照 |
| 08 | S2 历史库 | 同上。实际 DDL、行、索引、hash 与 S1 一致 |
| 09 | 积压恢复 | 真实 Redis Gateway 读历史夹具并 drain，扣除过期和坏 JSON，库剩余 0 |
| 10 | 未 checkpoint WAL | 实际子进程提交后退出 37，WAL/SHM 存在。只复制 .db 少 1 行，一致性 backup 保留两行及摘要 |
| 11 | 只读 | 实际只读挂载启动，0 worker；SQLite 只读连接尝试写得到 SQLITE_READONLY |
| 12 | SQLITE_FULL | SQLite 页数上限受控触发 SQLITE_FULL，非实际盘满 |
| 13 | 锁竞争 | 两真实 SQLite 连接 BEGIN IMMEDIATE 有竞争，第二连接 SQLITE_BUSY |
| 14 | 未知 schema 版本 | user_version=99 合成库实际启动，旧逻辑仍开始双 drain，证实缺少接管版本门禁 |
| 15 | 损坏 | 真实 Gateway 对非数据库字节报 SQLITE_NOTADB，保留文件，0 worker |
| 16 | 迁移中断 | 临时 SQLite 转换事务在 commit 前使子进程退出 38；版本及行数不变。未执行正式迁移 |
| 17 | Confirm ACK | S1 临时独立接管，绑定存在时真实 Broker 收到消息，确认版 drain 后进入删除 |
| 18 | mandatory returned | 实际 RabbitMQ 无路由时 S1 记录 returned 路径并降级入库，保留 1 行 |
| 19 | Confirm 丢失 | E 独立参考实验覆盖回环传输截断及同 ID 重投。当前 Gateway 网络链路未注入确认丢失 |
| 20 | Broker 重启 | E 参考覆盖两队列同卷恢复。未将在途 Gateway 补发与 Broker 重启组合 |
| 21 | 连接断开 | E 参考验证发布者丢确认及隔离 Broker 停启，未验全部 Gateway 入口断网 |
| 22 | 成功后 SQLite 更新失败 | 临时触发器以 SQLITE_CONSTRAINT_TRIGGER 阻止 DELETE，真实投递已能读取，SQLite 仍 1 行 |
| 23 | 成功后删除前退出 | 捕获已投递及未删的阶段后实际 Gateway 探针进程 halt(37) |
| 24 | 重启重复 | 同卷故障消除，临时接管者再次投递 `stable-rabbit-id`，行数从 1 到 0 |
| 25 | 逻辑配额 | 1B 夹具上限下已有 137 字节积压，S1 实际拒绝额外提交。不代表生产 2GB 压测 |
| 26 | 物理容量 | 只有 12 的受控 SQLite 页数不足，实际文件系统满盘未执行 |
| 27 | 过期关键保护 | 真实旧版 Redis 路径删除了 8 天模拟记录，证明当前缺少关键类别保护。新策略未实现 |
| 28 | 长时间积压 | 小型存量库与过期样本验证，持续生产峰值和大规模积压未压测 |
| 29 | 恢复补传限速 | Redis 设置每 worker 1 条/秒以放大双读取窗口，流记录可核对间隔。生产 200/s、单接管总体吞吐未验 |
| 30 | 不重复业务副作用 | E 的真实 Broker/合成事实及 Inbox 对照，本轮 Gateway 未装配正式消费者 |
| 31 | 重放对账 | 核对同 ID、SQLite 行数、Redis 流位置及一致性备份 hash。跨正式业务库对账未执行 |
| 32 | 正常与异常退出 | 常规就绪实例主动停止 143；进程 37/38 为预期故障。未测真实掉电 |

`traffic/historic.log` 对真实 Redis 存量配 `drain-rate=1`。SQLite 初始 6 行，其中 3 行同一历史 ID、1 行坏 JSON、1 行可解析但无 ID、1 行过期。Redis 实际 8 条流记录，其中同一历史 ID 6 次、无 ID 2 次。SQLite 最终 0 行。观测到两个 drainer 对历史同一批多发，旧版对坏或过期记录存在删除路径，不能按流记录数推断唯一事实数。历史数据库真实探针 13 个断言通过，含 WAL、锁、只读、受控 FULL、版本和迁移中断。Python 辅助探针和真实 Gateway 构件已区分。

Rabbit 故障组保持 Spring 内两个原 Bean 处于关闭，用临时探针实例化并调用现有 S1 类及真实 Broker，不代表已修复装配。首轮实际 halt(37) 后，在宿主尝试写 Rootless Docker 创建的文件时遇到只读权限，恢复步骤未完成。将取消临时 DELETE 故障触发器的动作移入同用户的隔离容器后，复验 S1 成功重传并清空已交接样本。证据见 `traffic-recovery/rabbit-crash.log`、`rabbit-replay.log`。未操作生产库或真实设备。

## 20. ENG-002F-02 实施方案

| 选项 | 范围和取舍 |
| --- | --- |
| A 先建独立兼容验证 | 建议 `script/gateway-spool/validation/{README.md,pom.xml,run-lab.py,fixtures/,src/test/java/}`。完成两个历史 DDL、文件锁、租约、WAL、只读/损坏、满额、迁移中断/回滚、真实 Redis/Rabbit 交接与失败实验，保持默认 Reactor 及正式装配 |
| B 直接最小正式修复 | 需先审批改 [旧 Redis 暂存服务](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/message/spool/IotDeviceMessageSpoolService.java) 的 Bean 激活、[IotDeviceMessageSpoolServiceImpl](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/spool/IotDeviceMessageSpoolServiceImpl.java) 的接管及生命周期、[IotGatewayConfiguration](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/config/IotGatewayConfiguration.java) 的断言和 [IotGatewayProperties](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/config/IotGatewayProperties.java) 及其 YAML 的策略开关。存储和迁移可候选新增 `service/device/spool/SpoolFileOwnership.java`、`SpoolMigration.java`，包内实现。保留既有接口，除非另行批准契约 |
| C 分阶段 | 推荐 A 验收后单独批准 B，再单独批准相应生产形态启用。由于 BLOCK-001 是真实 Gateway 运行缺陷，只运行独立原型不足以关闭 |

实际最小整改优先沿用已有 JDBC/NIO 无需改变模块 POM，新增独立验证 POM 位于 Reactor 外。兼容旧表的停接管阶段不需要生产 schema 修改。引入状态字段及版本化 SQLite 迁移时另行审批。新增测试位置候选为 Gateway `src/test/java/.../service/device/spool/` 下 `IotGatewaySpoolAssemblyTest`、`SpoolFileOwnershipTest`、`SpoolMigrationTest` 和隔离外部 Broker 集成测试。原有 23 项测试保留，按实际需增加负例。预计配置包括显式版本、接管锁、所有者、保留策略和可靠交接，默认行为需先评审。回滚按第 10 节区分是否已经接收或发布。正式接管、角色和真实 L2 ACK 链路按各自任务审批。

候选验收入口为 `bash script/shell/check-build-toolchain.sh`、`mvn -f script/gateway-spool/validation/pom.xml clean verify`、`python3 script/gateway-spool/validation/run-lab.py --evidence-dir <独立路径>`，再运行隔离 IoT Reactor 的启动矩阵及以下回归。候选文件和命令待实际审核批准，本轮文档以外不提交。

## 21. 验收矩阵

| 条件与执行方式 | 预期及证据 |
| --- | --- |
| 真 Redis/RabbitMQ 开关、非法配置 | 状态、条件报告、Bean/线程数及启动码。合法开启时只有一个 Bean 能接管 |
| 两 JVM 指同一 canonical path | 文件锁后持有者就绪、另一实例拒绝，分别记录进程与持有期。异常退出后验证按真正持锁接管 |
| 兼容/混合/未知/坏库 | 源文件与 schema 指纹、WAL、历史行与隔离证据。未知和坏库保护原始数据 |
| 迁移、迁移中断、回滚或配置切换 | 事务完整、改前后载荷/身份 hash 一致。旧进程不得和新代码同时写。同卷 rollback 依据可审计 |
| SQLite 写入失败、逻辑满和物理低水 | 对连接、只读、锁、满盘/余量负例。拒绝新增，保留原事实、负载和磁盘指标 |
| 关键事件过期 | 跨越保留时间仍保留，普通遥测采用独立政策 |
| ACK/NACK/returned/无效路由/确认丢失 | 真 Broker，保留各 attempt 及瞬时状态。只在获准交接证据后删除 |
| Broker 重启、SQLite 删除前崩溃 | 重启后同 ID 可重放，留进程中止、真实 Broker 及 SQLite 前后快照 |
| 补发重复、消费失败保全 | E 的 Inbox 与业务唯一键证明不重复副作用，失败责任、消费提交和 ACK 时序可查 |
| 受控重放及对账 | 按稳定 ID/hash 对比各层，失败/未知保留，整改不改变 C-04 成功 L2 ACK 条件 |
| 装配 / 工程回归 | JDK21/Maven3.9.12。默认 44 项、隔离 IoT 23 项 Reactor，release17（class major61）、ENG-002B 查询有效依赖/边界、ENG-002C 三库隔离、ENG-002D 原 40 项、ENG-002E 46 项、Gateway 原 23 项 Surefire XML、Server 与 Gateway 最小启动。真实构建与跳测标记、命令、退出码随实施报告保存 |

文档交付检查包括 Git 暂存差异范围、`git diff --cached --check`、66 个相对链接及锚点、20 条源码审计、32 条实验记录、14 项人工审批条目和常见凭据模式扫描。两个 GitHub 链接分别指向 Actions 和本任务比较页面。

本设计轮没有重跑所有 Reactor、ENG-002B/C/D、完整旧 Gateway 定向 suite 或 Server 最小启动。此前相应证据见 ENG-002E 报告。设计轮实际执行项只按第 4、19 节及 E 参考复验记录计算。

## 22. 风险与人工审批

BLOCK-001 关闭需要审批后的实际 Gateway 修复和真实运行验收。保留风险包括 SQLite 本地磁盘故障、旧代码不遵循新锁、共享卷、未确认保留容量、业务去重和跨层身份不可证，以及 Broker 持久化拓扑调整。现有实测证据支持设计选择，不能用于生产启用、正式迁移、真实 L2 ACK 或 HA 结论。

| 审批项 | 待核验或批准内容 | 人工记录 |
| --- | --- | --- |
| 唯一接管实现 | S1 保留及 S2 退出接管，唯一 Bean 和 worker 不变量 | 待填写 |
| Redis 兼容 | Streams 责任、兼容回归、关键数据限制 | 待填写 |
| 关键事件持久化 | 先落盘的类别、未知分类、延迟与批量 | 待填写 |
| 文件所有权 | 规范路径、文件锁、旧进程 fencing 和独立卷 | 待填写 |
| 历史迁移 | WAL/备份、目标映射、隔离、校验、停机及幂等恢复 | 待填写 |
| Schema / 版本 | 字段、约束、schema 识别及变更策略 | 待填写 |
| 消息身份 | DEC-002/G、四层身份、历史边界及摘要 | 待填写 |
| 交接和本地删除 | 关键等 INGEST T1 证明，非关键按批准政策，分阶段托付 | 待填写 |
| 容量和保留 | 逻辑/物理水位、背压、预留和关键记录保护 | 待填写 |
| 重试和失败隔离 | 指数退避、限制、隔离及受控重放 | 待填写 |
| 并发和顺序 | lease/fence、同设备序列、跨设备并行及关停 | 待填写 |
| 部署和回滚 | 用户权限、卷、维护停机、灾后迁移和恢复 | 待填写 |
| ENG-002F-02 | A→B 的独立验收及正式修复授权，生产启用另审 | 待填写 |
| BLOCK-001 关闭证据 | 实际装配、真实 Broker、历史库及故障完整性，人审签署 | 待填写 |

审批记录填写批准人、日期、依据 SHA、范围、证据和限制。保留所有相关决策及生产阻断状态。
