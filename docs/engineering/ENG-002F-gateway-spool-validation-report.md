# ENG-002F-02A Gateway 双暂存独立验证实施报告

## 基线与变更范围

| 项目 | 实际值 |
| --- | --- |
| 开发仓库 | `weeleng/car_weight` |
| 目标分支基线 | `ff2182d4fe628899f00d122071accd6b02451348` |
| 实际起始及已审核设计 | `1105ada6dfb0ac07dbf5bf8fa03c89c0758c93f7` |
| 独立验证工程提交 | `a454e2c071013be08616ecb1f8b577fdb5f8cea6` |
| 交付分支 | `engineering/eng-002f` |

读取 [AGENTS](../../AGENTS.md)、[技术基线](../architecture/standards/technology-baseline.md)、[工作流](codex-workflow.md)、[验证矩阵](validation-matrix.md)和[已审核设计](ENG-002F-gateway-spool-remediation-design.md)。执行 `git status --short -b`、`git ls-remote origin refs/heads/master-jdk17 refs/heads/engineering/eng-002f`、按引用 fetch、`git rev-parse HEAD origin/engineering/eng-002f origin/master-jdk17` 和 `git merge-base --is-ancestor`。任务分支与已审核设计一致，主分支为其祖先。工作区原有未跟踪环境基线报告保留。ENG-002D-02R 继续独立跟踪。[GitHub 比较](https://github.com/weeleng/car_weight/compare/master-jdk17...engineering/eng-002f?expand=1)

02A 新增本报告和独立工程，共 19 个文件。下表列出 18 个验证工程文件。

| 位置 | 文件 |
| --- | --- |
| `script/gateway-spool/validation/` | `README.md`、`pom.xml`、`run-lab.py` |
| `fixtures/` | `GatewayProbe.java`、`LegacyShapes.java`、`gateway.py`、`run_support.py` |
| `src/main/java/cn/iocoder/sanitation/spool/` | `FileOwnership.java`、`LegacyMigration.java`、`Sqlite.java`、`Spool.java`、`Ingest.java` |
| `src/test/java/cn/iocoder/sanitation/spool/` | `GatewayTest.java`、`HistoryTest.java`、`BrokerTest.java`、`SpoolTest.java`、`LabTest.java`、`Child.java` |

交付前逐项核对文件范围、文档内相对链接、常见凭证模式和 class major。正式 Gateway、IoT Core、Maven Reactor、部署配置、数据库迁移、ENG-002E 以及 ADR/ACR 未变。独立测试类和候选实现均在验证工程内。旧 Gateway 的设备 L2 ACK 提交条件及生产开关未变。BLOCK-001、BLOCK-003、BLOCK-007 和 DEC-006、DEC-016 保持开放。[独立工程入口](../../script/gateway-spool/validation/README.md)

## 环境、执行命令及证据

OpenJDK Temurin **21.0.13.1+2**、Maven **3.9.12**，编译 **release 17**。Gateway 来自本轮隔离 IoT Reactor 的原版 exec JAR，SHA-256 为 `e51472bd5fc17ac5b8d5e650702879b6f952a7c78a2ebb318633d5a8671e6789`。观察器只读取 Bean 工厂、Spring Boot Condition Evaluation Report、执行器和线程，并通过两个原实现的建库方法生成历史夹具。

SQLite JDBC **3.49.1.0**，实际引擎 **3.49.1**。独立 POM 还使用 AMQP Java Client **5.25.0**、Jackson Databind **2.21.4**、JUnit Jupiter **5.12.2**、Surefire **3.5.4**。真实隔离 Broker 为 RabbitMQ **4.2.9** 和 Redis **7.4.12**。Redis 使用 `appendonly=yes`、`appendfsync=always`，关闭定时 RDB（`save=""`）。Gateway 只接入内部容器网络，RPC 指向容器内未监听地址。AMQP 测试端口只映射到宿主回环，Redis 不映射到宿主。未连接生产系统或设备。

```bash
r=/workspace/eng002f02a
dev=/workspace/car-weight-dev-jdk17
export JAVA_HOME=/workspace/car-weight-eng002a-validation/jdk
export PATH="$JAVA_HOME/bin:$dev/apache-maven-3.9.12/bin:$PATH"
export MAVEN_OPTS=-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts
python3 script/gateway-spool/validation/run-lab.py --evidence-dir "$r/lab-final" \
  --gateway "$r/iot/yudao-module-iot/yudao-module-iot-gateway/target/yudao-module-iot-gateway-exec.jar" \
  -- -s "$dev/maven-settings.xml" "-Dmaven.repo.local=$r/m2-iot"
```

组内执行 `mvn -B -ntp -f script/gateway-spool/validation/pom.xml test -Dtest=<组测试类>`，使用上述 settings 和仓库缓存，报告写入独立目录。完整参数、JUnit 计数与退出码见 `$r/lab-final/{A,B,C,D}/result.json`，XML、日志、过程快照和失败恢复结果同目录。有效 POM、依赖树、命令和退出码见 `lab-final/{effective-pom.xml,dependency-tree.json,dependencies-result.json}`。镜像摘要、构件 SHA-256 及工具链原始输出见 `lab-final/environment.json`。证据留在隔离工作区。源码级断言和 Docker 子场景数量不计入 JUnit 总数。

| 组 | 范围 | JUnit | 失败 | 错误 | 跳过 | Maven 退出码 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| A | 原版 Gateway 装配 | 5 | 0 | 0 | 0 | 0 |
| B | 文件所有权、历史库和迁移 | 9 | 0 | 0 | 0 | 0 |
| C | 真实 RabbitMQ/Redis 与候选交接 | 9 | 0 | 0 | 0 | 0 |
| D | 关键保护、状态机、证据和压测 | 6 | 0 | 0 | 0 | 0 |
| 合计 | 最终完整运行 | **29** | 0 | 0 | 0 | 0 |

最终运行器及有效 POM、依赖树核验均退出 0。A 组包括 14 次实际 Gateway 子进程观察。

## A 组：原版 Gateway 装配

原版 jar 通过 `PropertiesLauncher` 运行原 Gateway 应用。每个子进程保留完整命令、匹配的 Spring Boot 条件、Bean 实例及依赖关系、实际补传线程、SQL/启动报错和退出码。重点结果如下。

| 子场景 | 实测及证据 |
| --- | --- |
| Redis 和 RabbitMQ 各关闭暂存 | 两个 Bean 均可实例化，无补传线程。就绪后主动停止为 143 |
| Redis 开启且 SQLite 文件不存在 | 成功建库并就绪。两个 Bean 共享暂存属性和文件，各一补传线程 |
| RabbitMQ 开启 | RabbitMQ 条件命中，S1 打开文件和建立执行器。S2 初始化因只支持 Redis 拒绝，进程退出 1，未就绪。属于预期基线故障 |
| 已有只读文件、只读目录、坏库 | 以只读挂载作为可重复的无写权限来源，分别验证打开或初始化受阻及 `SQLITE_NOTADB`。原版可就绪而两个执行器均未运行。坏库字节保留 |
| 未知 schema、缺失和错误总线值 | 版本 99 被旧版打开，未自动更改版本。缺值走 Local。非法值无可装配的总线，退出 1 |
| 共享文件、旧版绕过接管锁及恢复 | 候选进程持锁时，两个原版进程仍各带两个补传线程启动。原版进程收到 SIGKILL 退出 137，使用同文件的后续进程成功就绪 |
| 历史暂存补传 | 向实际 Redis Stream 写出同一历史 ID 6 次。夹具内有 3 行相同 ID，低速补传用于放大两个旧执行器的重复窗口 |

A 的证据位于 `lab-final/A/*-process.json`、`*-command.json`、同名 JVM 日志和 `historical-stream.json`。证明旧程序可绕过协议外的 OS 锁，部署中需独立 fencing 和停旧验收。

## B 组：文件接管、历史库及迁移

候选接管器先确认 canonical path 和路径/文件身份，再持有独占 OS 文件锁；同 JVM 和另一 JVM 的争抢均被拒绝。接管进程被 SIGKILL 后，锁文件仍存在，新进程可获得实际锁。覆盖正常释放、软链接以及获锁后数据库 inode 替换的拒绝。文件锁只覆盖遵守同一协议的本地进程。

原 S1 和 S2 通过 SQLite JDBC 创建同样的 `iot_device_message_spool` 表及时间索引，`user_version=0`，WAL 模式，列为 `id/spool_time/size/message`。行摘要和 schema 指纹可对照。夹具覆盖空库、积压、重复 ID、无 ID、坏 JSON、未知和混合 schema、坏文件、只读、真实锁竞争及页数上限 FULL。迁移数据保留原行 ID、时间、长度、原始字节和 SHA-256；重复、无身份及坏 JSON 带原因隔离，不丢弃源行。

依据稳定源标识和完整源清单记录批次。源文件以只读方式打开，目标使用单事务，目标 schema 及版本须匹配。子进程在真实 SQLite 写入、尚未 checkpoint 时退出 37，主文件副本仅见 1 行，SQLite 原生 backup 的 2 行清单与源一致。目标迁移事务中止退出 38，重开后没有新 schema 或残留映射，重试完成。重复导入不会重置 PUBLISHED 行。相同来源清单发生变化时拒绝接管，保留现有目标与源数据，交由前向修复。证据见 `lab-final/B/{s1,s2,backlog-source,backlog-quarantine,wal-before-migration,wal-consistent-backup,sqlite-errors,takeover}.txt`。

## C 组：RabbitMQ 可靠交接与 Redis Streams

- 真实 RabbitMQ 确认了可路由 ACK、无绑定时的 ACK + returned 312、限长队列拒绝时的原生 NACK，以及不存在 exchange 时的 Channel 关闭。后验队列检查失败与 SQLite 触发器阻断状态写入时保留原载荷，租约到期后重新领取。
- 回环中继阻断发布端接收 Broker 帧，由旁路消费证明 Broker 已收。关闭中继连接后以原消息 ID 重投。真实 Broker 停止时本地可写入 30 条候选记录，启动后确认原持久消息可达。
- 候选进程收到 Broker Confirm 后在本地写状态或清理前退出 41。重启同 ID 再投，合成消费事务回滚后重投，最终只生成一份事实和一条对应意图。另一夹具强制模拟先删除本地记录再遇下游回滚，先将失败副本写入实际 Broker 并收到确认，再 ACK 原投递。故障域和责任转移依赖该保全步骤。
- 真实 Redis 的 XADD 返回 RecordId，无订阅者时消息仍在流中。消费者组领取后，消费子进程退出 42，记录仍待确认。另一消费者 XCLAIM 后 XACK，重复 ACK 返回 0。容器停止时命令失败，同一 AOF 卷重启后观测到原 RecordId。候选唯一接管者补传一条，第二领取被拒绝。该观察仅适用于本轮 AOF 配置。

C 组保存 `rabbit-confirms.txt`、`rabbit-state-fault.txt`、`rabbit-confirm-loss.txt`、`rabbit-recovery.txt`、`rabbit-restart.txt`、`rabbit-after-cleanup.txt`、`rabbit-throughput.txt`、`redis-stream.txt` 和 `redis-single-owner.txt`。C 的持久队列和消费者均为验证夹具。未联动真实 Gateway 在 RabbitMQ 开启暂存时运行候选逻辑。

## D 组：关键事件、证据、容量及恢复

- 合成 KEY_MEASUREMENT 先写入。提交前子进程退出 39 时记录不可查，提交后退出 40 时 PENDING 和载荷仍在。ORDINARY_TELEMETRY 可按试验策略清理。UNKNOWN 进入隔离。关键记录超过七天仍保留。逻辑满额与模拟低盘剩余拒绝新增，夹具上游仍保管原事件。真实只读及受控 SQLite FULL 保全既有记录。
- SQLite 短事务运行 PENDING、PUBLISHING、RETRY_WAIT、BROKER_CONFIRMED、HANDOFF_COMPLETE 和 QUARANTINED 转移。owner、attemptId、version 和租约用于 fencing。旧 attempt、迟到回调、重复回调不覆盖现有事实。重试耗尽后仍保留原载荷。真实网络等待在 SQLite 事务外执行。
- 合成 INGEST 独立事务写 raw 与 outbox。SQLite 暂存成功和 Broker 确认不能据以清理。T1 回滚、错误来源、错误事件 ID 或提交 ID 被拒绝。模拟证明传输丢失后，按稳定 ID 重查成功提交。载荷冲突保留原事实及冲突数据。登记证据后才清理本地关键载荷，身份及摘要留作对账，重复证明和回放无二次清理。

一次隔离容量采样使用 4 个写线程、同一文件及 240 条 512 字节记录，其中 120 条构造为 14 天积压。40 次逐条写平均 2.389 ms，120 条单事务批写 13.451 ms。并发采样未触发 BUSY 重试，另有 B 组竞争用例真实得到 BUSY。恢复前 WAL 为 877,592 字节，主库及 sidecar 合计 959,512 字节，TRUNCATE checkpoint 后主库为 192,512 字节。240 条本地模拟 T1 交接用 0.692 秒，约 346.6 条/秒。C 组独立发送 60 条 512 字节持久消息，平均 Confirm 0.797 ms，含 SQLite 调度的补传约 234.2 条/秒。B 组异常退出后新 JVM 取得锁并正常退出用约 88.6 ms。真实设备数为 0。环境和样本不支持生产容量承诺。

这些测量及保留证据见 `lab-final/{D/capacity.txt,C/rabbit-throughput.txt,B/takeover.txt,D/proof.txt,D/leases.txt,D/rejected.txt}`。

## 工程回归

基于已审核设计 SHA 归档默认及 IoT 副本。仅在 IoT 副本打开聚合入口，运行指定 Core、Biz、Gateway 及依赖。构建前执行工具链脚本。默认和 IoT 的 clean install 均明确跳过测试，定向测试另行执行。运行记录位于 `/workspace/eng002f02a`。

| 回归 | 实测 |
| --- | --- |
| 默认 Reactor | 44 项，`clean install -Dmaven.test.skip=true` 退出 0。1,149 个项目 class，全部 major 61 |
| 独立 IoT Reactor | 23 项，`-pl :yudao-dependencies,:yudao-module-iot-core,:yudao-module-iot-biz,:yudao-module-iot-gateway -am clean install -Dmaven.test.skip=true` 退出 0。1,783 个项目 class，全部 major 61 |
| ENG-002B | 17 份有效 POM 和实际依赖树，边界检查 0，检查器 23 项用例通过 |
| ENG-002C | 45 项隔离检查和 9 项 JUnit 通过。首轮 Flyway 依赖 TLS 证书链失败，使用既有 C 的缓存及系统信任库复验，退出 0 |
| ENG-002D | 在隔离 MySQL 8.4.13 执行原有 40 项，退出 0 |
| ENG-002E | 隔离 MySQL/RabbitMQ 四组 21 / 14 / 7 / 4，合计 46 项通过，0 失败、错误或跳过 |
| Gateway 原有定向 | Confirm 2、SQLite Store 4、Spool 17，共 23 项，0 失败、错误或跳过 |
| Server 与 Gateway 最小启动 | 新版 Server 36.931 秒、Gateway 18.678 秒就绪，主动停止均退出 143。隔离服务为 MySQL/Redis，Gateway 最小启动使用 Local。执行 JAR 分别含 258 和 212 个内嵌库，均未装配 02A 或 E 验证工程 |
| 新旧 Server Bean 关系 | 隔离启动后各 67 条观察关系，排序 diff 退出 0。抽样范围见 `graph/{legacy,server}-edges.txt` |

回归执行入口与产物包括 `run-build.sh`、`evidence.sh`、`smoke.sh`、`graph/graph.sh`，边界命令调用 `script/shell/check-sanitation-boundaries.py --root "$r/default" --mode default --iot-root "$r/iot" --evidence-dir "$r/evidence" --default-repo "$r/m2-default" --iot-repo "$r/m2-iot" --jdeps "$JAVA_HOME/bin/jdeps"`。C、D、E 分别运行既有的 `run_lab.py`、`run-mysql.py`、`run-lab.py`。从副本实际执行的三个环境回归命令如下。

```bash
cd "$r/cd"
python3 script/database/validation/run_lab.py --evidence-dir "$r/database-retry" -- \
  -s "$dev/maven-settings.xml" -Dmaven.repo.local=/workspace/car-weight-eng002c02-regression/m2-default
python3 script/authorization/validation/run-mysql.py \
  -s "$dev/maven-settings.xml" "-Dmaven.repo.local=$r/m2-iot"
python3 script/messaging/validation/run-lab.py --evidence-dir "$r/messaging" -- \
  -s "$dev/maven-settings.xml" "-Dmaven.repo.local=$r/m2-iot"
```

实际日志和退出码在 `default-build.*`、`iot-build.*`、`boundaries.*`、`boundary-tests.*`、`c-retry.*`、`d.*`、`messaging/`、`gateway-tests.*`、`*-smoke.log` 和 `graph/edges.*`。

## 故障复验、残余风险与 02B 前置条件

首轮回归命令生成器保留了旧的路径后缀，两个 Reactor 初次调用没有进入副本。修正路径后构建、定向测试与依赖检查通过。独立验证首轮 A/B/D 实际通过 5/9/6 项。C 的 9 项出现 2 个失败、1 个错误。RabbitMQ 已启动且恢复持久队列，重启后映射端口变化导致测试连旧端口，影响重启、随后依赖 Broker 的状态检查和子进程恢复。读取当前容器端口后，C 的 9 项定向复验通过，端口变化原始记录见 `lab-second/C/rabbit-restart.txt`。

需要 02B 人工批准正式 Gateway Bean 互斥、跨进程唯一接管、旧实例 fencing、版本迁移和恢复、容量水位、分类及可靠交接前置证据，并在真实 Gateway 装配中重测。正式 T1 确认通道、设备身份和 L2 ACK 继续由 DEC-006、ENG-002G/I 审批。生产启动配置、迁移运行授权及故障域需要另审。

未执行生产历史库迁移、真实设备和 L2 ACK 回传、物理断电、真实磁盘损坏、物理满盘、跨主机及共享文件系统 HA、生产负载长期积压、Redis RDB 单独恢复模式、Gateway 修复版接入及全仓测试。候选前向修复验证了目标版本拒绝覆盖和重复导入，正式数据修复计划仍待审批。02A 的验证范围仅为现有装配基线及隔离候选实验。BLOCK-001、BLOCK-003、BLOCK-007 和 DEC-006、DEC-016 继续开放。
