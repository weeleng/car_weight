# ENG-002F-02B-1 Gateway 双暂存源码整改报告

## 基线及交付范围

主分支 `master-jdk17` 起始 `ff2182d4fe628899f00d122071accd6b02451348`。任务分支 `engineering/eng-002f` 本地与远程实际起始均为已审核 02A 交付 `3d828deb28453393b114e159a741135c52b5741f`。执行 `git status --short -b`、`git ls-remote`、按引用 fetch、`git rev-parse`、`git merge-base --is-ancestor`。主分支是任务分支祖先，已有未跟踪环境报告保留。[AGENTS](../../AGENTS.md) · [02A 验证报告](ENG-002F-gateway-spool-validation-report.md) · [详细设计](ENG-002F-gateway-spool-remediation-design.md) · [GitHub 比较](https://github.com/weeleng/car_weight/compare/master-jdk17...engineering/eng-002f?expand=1)

源码及专项验证工程提交 `3325f866180b013823b134d3f3249c7e8aed16a7`。报告随本分支提交，远程交付引用见比较页面。

| 位置 | 文件 |
| --- | --- |
| Gateway `config/` | `IotGatewayProperties.java`，说明显式开启的确认能力要求 |
| Gateway `service/device/message/spool/` | `IotDeviceMessageSpoolService.java`，停止 S2 自动装配，保留旧源码 |
| Gateway `service/device/spool/` | `IotDeviceMessageSpoolServiceImpl.java`、`SpoolFileOwnership.java`、`SpoolStartupValidator.java` |
| Gateway 同包测试 | `IotDeviceMessageSpoolServiceImplTest.java` |
| 独立验证 `script/gateway-spool/validation/` | `README.md`、`run-lab.py`、`fixtures/{GatewayProbe.java,SpoolRabbitFixture.java,gateway.py,remediation.py}` |
| 工程报告 | 本文件 |

IoT Core、所有 POM、应用 YAML、生产部署、数据库迁移和设备协议源码未变。默认总线仍为 Redis，暂存仍默认关闭。02A 测试断言仍以原构件为对象，复用的观察器仅在显式启用独立故障夹具时发送合成消息。

## 实施与兼容边界

全仓核对旧服务类型引用。只有 S1 接口注入 Gateway 设备消息服务，S2 无独立生产调用者。S2 退出 Spring 扫描，S1 保留原注入名及消息方法。暂存关闭时保留 S1 外观 Bean，发送器及存储均不启动。显式开启要求有效配额、一个 worker、支持外部确认的总线和健康可写的本地暂存。校验拒绝导致启动失败。Local 或 RabbitMQ 禁用 publisher returns 等配置拒绝启动，避免误报为启用可靠暂存。旧 YAML 的 Local 注释仍按原默认关闭模式理解，显式开启以源码校验为准。

持有同 JVM 路径登记、OS 旁路文件锁、SQLite 原生独占连接三个边界。OS 文件锁不能约束未采用旁路协议的旧版。新版在本地 SQLite/WAL 下另外持有原生独占连接，防止同一文件被原版成功打开。文件锁贯穿校验、连接与 worker 生命周期。关闭先拒绝新工作并中断 worker，再由执行器终止回调关闭连接及释放旁路锁。worker 五秒未终止时继续持锁并告警。进程异常退出后 OS 与 SQLite 释放相应锁。网络等待在 SQLite 事务外。

启动校验 canonical path，记录目录、文件、锁文件身份，拒绝软链接和 UNIX 硬链接，检查 SQLite sidecar，路径替换后拒绝发送或修改。文件和目录权限及 SQLite 写事务均通过才进入就绪。部署必须限制目录写权限，保证同一受信本地卷和固定路径，禁止在运行中替换目录或文件。未验证共享文件系统和跨主机语义。

已有库以只读预检 `user_version`、`sqlite_master`、列、索引和 `quick_check`。获 SQLite 独占后复检，沿用 WAL 和 `synchronous=FULL`。允许精确 v0 历史结构和空库，空库按原 DDL 初始化，继续使用 `iot_device_message_spool(id,spool_time,size,message)` 及时间索引。未知、混合或损坏库原数据保留，拒绝自动清理或重建。独占期间同机的其他 SQLite 客户端也会受阻，备份和外部校验须在停写并释放所有权后进行。

## 实际 Gateway 与 Broker

隔离 IoT Reactor 的实际 exec JAR 由 Temurin 21.0.13.1 / Maven 3.9.12 在 release 17 下构建。隔离容器启动使用原 Spring Boot Gateway 入口，观察器只记录 Bean/条件和 worker。故障用例使用观察器将合成消息送入同一个正式 S1 接口、正式 DeviceMessageProducer 与实际 Redis 或 RabbitMQ。AMQP 差错由实际 Broker 或隔离中继注入。Redis 7.4.12、RabbitMQ 4.2.9、SQLite JDBC 3.49.1.0。所有 Gateway 容器仅在内部网络运行，AMQP 证据客户端用宿主回环端口。未连接生产或设备。新版 exec JAR 的 SHA-256 为 `46c9cd7047679432dceec03a1aced4f7d9941c6486447f017be4ab018b7b7de1`，02A 对照构件为 `e51472bd5fc17ac5b8d5e650702879b6f952a7c78a2ebb318633d5a8671e6789`。

```bash
r=/workspace/eng002f02b1
old=/workspace/eng002f02a
jar="$r/iot/yudao-module-iot/yudao-module-iot-gateway/target/yudao-module-iot-gateway-exec.jar"
oldjar="$old/iot/yudao-module-iot/yudao-module-iot-gateway/target/yudao-module-iot-gateway-exec.jar"
python3 script/gateway-spool/validation/run-lab.py \
  --gateway "$jar" --legacy-gateway "$oldjar" --evidence-dir "$r/real-final"
```

| 配置 | S1 Bean | S2 Bean | worker | 进程结果 |
| --- | ---: | ---: | ---: | --- |
| Redis 暂存关闭 | 1 | 0 | 0 | 就绪，主动停止 143 |
| Redis 暂存开启 | 1 | 0 | 1 | 就绪，主动停止 143 |
| RabbitMQ 暂存关闭 | 1 | 0 | 0 | 就绪，主动停止 143 |
| RabbitMQ 暂存开启 | 1 | 0 | 1 | 就绪，主动停止 143 |

Local 加只读路径在关闭暂存时仍就绪。显式开启 Local 或关闭 RabbitMQ publisher returns 时均退出 1，且未建暂存库。

跨 JVM 实测中，第二新版进程在旁路锁检查处退出 1。第一新版进程在就绪后被 SIGKILL，退出 137。锁标记仍保留，后续新版正常获得同一文件。新版先运行时，原版仍可被 Spring 标为 ready，但两个旧装配路径均报 SQLite BUSY，实际 worker 为 0。原版先运行时其 worker 为 2，新版被 SQLite 独占检查挡住并退出 1。停止原版后，新版恢复为 1 个 worker。

未知版本 99、混合结构、坏文件、只读 DB、只读目录及只读目录中的缺文件均拒绝启动。已有文件的主文件 SHA-256 前后相同，缺文件用例未生成主库。Redis 真实补传 v0 历史行及一条已提交、未 checkpoint 的 WAL 行，两个不同 ID 各被投递一次。测试对照中只复制主文件会遗漏 WAL 行。此实验在本地隔离卷执行。

真实 RabbitMQ 场景中，正常确认直发未写入 SQLite。无绑定时 mandatory returned 后保留原消息。限长队列拒绝产生真实 NACK，SQLite 保留原消息。两种故障解除后均按原 Gateway 消息 ID 补传，成功后清除暂存行。

停启 RabbitMQ 应用时，Gateway 将断线时的合成输入写入暂存，Broker 恢复后重投成功。回环中继拦截 Broker 的 BASIC.ACK，旁路记录证明 Broker 已收，正式发布路径报确认超时并暂存，解除拦截后以相同 ID 补传。对已有积压，收到真实 RabbitMQ 投递后在 SQLite 批量删除前 SIGKILL，进程退出 137，重开核对 21 行仍在。重启后以同 ID 补传并读空本地积压。这里的稳定身份是同一暂存消息 ID。

最终独立矩阵 **10/10 通过**，观察了 **31 次**真实 Gateway 子进程，运行器退出 0。每次命令、Spring 装配、进程码、SQL 和 Broker 证据在 `real-final/remediation/`，包括 `matrix.json`、`*-process.json`、`*-snapshot.json`、`*-delivery.json`、`confirm-loss-deliveries.json`、`crash-before-delete-deliveries.json`。环境及两个构件摘要见 `real-final/environment.json`。首轮 `real-first/` 的 10 组中有 8 组失败，合成库夹具提交后未显式关闭 SQLite，导致新用户连接上的独占检查报 BUSY，也干扰了 WAL 对照和主文件摘要检查。修复夹具连接生命周期后全量通过，生产源码在两轮间相同。

## 删除语义、回执与 02B-2

旧普通消息处理仍包含默认七天过期删除、永久拒绝后跳过并删除、坏 JSON 跳过并删除。原测试 `testDrain_expired`、`testDrain_expiredBeforeSend`、`testDrain_rejectedSkipped`、`testDrain_badRecordDiscarded` 保留并执行。成功发送后如在删除前退出，同一 Gateway 消息 ID 可重投。Redis RecordId 和 RabbitMQ Confirm 都只证明各自消息总线的相应结果。未引入事件类别、关键计量暂存库或 INGEST 证明 RPC。设备 L2 ACK 的 C-04 提交条件、VWCP 和 G/I 不变。协议回复使用现有实现，工程回归覆盖其消息选路与普通回执。没有执行真实设备 L2 ACK 验证。[C-04](../architecture/standards/iot-ack-and-durability.md)

02B-2 需另行审批关键事件分类、持久化、交接责任、SQLite 版本迁移与隔离、超期及永久失败保留、正式 INGEST 证明和恢复对账。现有普通消息行为不能证明关键计量的端到端可靠交接。

## 工程回归

回归在 `/workspace/eng002f02b1` 的独立副本执行。默认与 IoT 两次构建均先运行工具链，再执行 `mvn -B -ntp -C clean install -Dmaven.test.skip=true`（自定义 settings 和缓存）。只有 IoT 临时副本打开入口并使用 `-pl :yudao-dependencies,:yudao-module-iot-core,:yudao-module-iot-biz,:yudao-module-iot-gateway -am`。定向测试独立运行，以下计数均为实际执行。

| 验证 | 结果 |
| --- | --- |
| 默认 Reactor | 44 项，退出 0，构建时跳过测试。1,149 个项目 class 全为 major 61 |
| 隔离 IoT Reactor | 23 项，退出 0，构建时跳过测试。1,786 个项目 class 全为 major 61 |
| ENG-002B | 有效 POM 与依赖树审计退出 0，23 项边界测试通过 |
| ENG-002C | 45 项隔离检查和 9 项 JUnit 通过，退出 0 |
| ENG-002D | 原有 40 项授权验证通过，退出 0 |
| ENG-002E | 46 项可靠消息验证通过，退出 0 |
| ENG-002F-02A | 使用审核基线原构件执行 A/B/C/D 的 5、9、9、6 项，共 29 项通过，初跑与最终复跑均退出 0 |
| Gateway 定向 JUnit | 原有 23 项及 **7 项新增**。另执行 50 项设备消息服务及 20 项 HTTP/MQTT 上行用例，合计 **100 项通过**，0 失败、错误或跳过 |
| 最小启动 | Server 52.417 秒、Gateway 12.860 秒就绪，停止进程均退出 143 |
| Server 新旧装配 | 02A 基线与本轮各取 67 条 Bean 关系，排序比较退出 0 |

新增 JUnit 覆盖同 JVM 争抢、未知/混合结构字节保护、软/硬链接、数据库替换拒发、锁文件身份替换、worker 仍在执行期间保持锁。

原始证据包含 `default-build.log`、`iot-build.log`、`evidence/`、`boundaries.log`、`database/`、`d.log`、`messaging/`、`02a/`、`02a-final/`、`gateway-final-tests.log`、`{server,gateway}-smoke.log`、`graph02a/{legacy,server}-edges.txt` 及对应退出码。依赖审计对实际两个 Reactor 构建副本运行 `check-sanitation-boundaries.py`。定向命令如下。

```bash
mvn -B -ntp -C -s "$dev/maven-settings.xml" "-Dmaven.repo.local=$r/m2-iot" \
  -pl :yudao-module-iot-core,:yudao-module-iot-gateway -am \
  -Dtest=IotRabbitMQMessageBusConfirmTest,IotDeviceMessageSpoolServiceImplTest,IotDeviceMessageSpoolStoreTest,IotDeviceMessageServiceImplTest,IotHttpUpstreamHandlerTest,IotMqttUpstreamHandlerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## 部署、回滚与开放风险

上线前先冻结接收和补传，停用旧实例、自动重启策略及共享同卷的副本，独立确认没有旧进程访问文件。用经过验证的 SQLite 一致性备份保留 v0 账本及 WAL 提交，记录行数、身份和摘要，查明历史文件版本后启动唯一新版实例。用实测版本和部署形态复验一 Bean 一 worker、第二实例拒绝、历史记录重放及容量和告警。禁止靠旁路标记文件存在推断已持锁。生产部署必须审批后执行。

回滚先停接收、关闭 worker、验证 SQLite 和旁路锁释放，再做一致性备份及对账。旧版暂存开启会恢复原阻断，过渡只能使用已批准的停接收方案，或在普通消息专用环境按审批关闭暂存。新版运行期间有新增或投递时不得用旧备份覆盖现有历史文件，应按已接收、已发布及未交接记录前向修复。原 v0 结构保留，未启动任何正式数据库迁移。

BLOCK-001、BLOCK-003、BLOCK-007 和 DEC-006、DEC-016 保持开放。BLOCK-001 仍需实际部署的停旧、单所有者、运维卷及拓扑验证，存量一致性、未确认关键数据保全及正式交接完整性，以及人工签署。真实设备、跨主机、物理掉电、物理磁盘故障、生产容量与实际生产库恢复未执行。
