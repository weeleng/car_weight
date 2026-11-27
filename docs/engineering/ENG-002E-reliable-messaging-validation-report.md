# ENG-002E-02 Outbox / Inbox 隔离验证实施报告

## 1. 基线、交付与边界

| 项目 | 完整 SHA |
| --- | --- |
| `origin/master-jdk17` | `d9230a63521df3f4f8f53a061f6c070954fa8d7f` |
| 实际起始及已审核设计 | `70c26ce00b374a5258ff1a887eccb6c8833b0f69` |
| 实施与验证提交 | `866231819b33a9174a86260e58d3609b4c5d0917` |

检查 `git status --short --branch`、`git ls-remote origin refs/heads/master-jdk17 refs/heads/engineering/eng-002e`、`git fetch --no-tags origin refs/heads/master-jdk17:refs/remotes/origin/master-jdk17`、`git rev-parse HEAD origin/master-jdk17` 及祖先关系。实际主分支和任务分支与指定基线一致。按 [详设](ENG-002E-reliable-messaging-design.md)第 18 节方案 A 实施。工作区原有未跟踪环境报告保留在提交外。

| 路径（相对仓库根） | 新增文件 |
| --- | --- |
| `script/messaging/validation/` | `README.md`、`pom.xml`、`run-lab.py` |
| 同目录 `fixtures/` | `ingest/schema.sql`、`business/schema.sql`、`track/schema.sql` |
| 同目录 `src/main/java/cn/iocoder/sanitation/messaging/` | `Broker.java`、`Consumer.java`、`Database.java`、`Protocol.java`、`Recovery.java`、`Relay.java`、`Store.java` |
| 同目录 `src/test/java/cn/iocoder/sanitation/messaging/` | `ConsumerTest.java`、`CrashConsumer.java`、`LabFixture.java`、`PublishTest.java`、`RecoveryTest.java`、`TransactionTest.java` |
| `docs/engineering/` | 本报告 |

共 20 个新增文件，实施提交 19 个，报告独立交付。原有 Java、POM、迁移、运行配置、Server/Gateway 装配、Gateway 暂存及模块依赖边界保持基线。GitHub 对主分支的比较另包含前一阶段的详设。

## 2. 运行条件、命令与证据

- **工具链。** Temurin OpenJDK 21.0.13.1、Maven 3.9.12、编译 release 17。独立验证主类 20 个 class 均为 major 61。实际有效依赖树中 Spring Rabbit/AMQP 3.2.12、RabbitMQ Java Client 5.25.0、Spring JDBC/Tx 6.2.19、MySQL Connector/J 9.7.0、Jackson Databind 2.21.4、JUnit Jupiter 5.12.2。
- **实际服务。** MySQL 8.4.13，镜像 `mysql@sha256:8c233bad33d520a7aae1c1e619c3a9775f4a1ba2d463d692fada04ec7f40be00`。RabbitMQ 4.2.9，镜像 `rabbitmq@sha256:d15c432b200a107c9602b9aafe6499b5ac7a56d870d367b53a7922f6f04e14df`。两个独立暂时容器，三个回环动态端口，随机账号凭据及独立 vhost `probe`。可复核节点身份、MySQL UUID、catalog、隔离标识、Broker 协议版本及集群名。三个 MySQL Schema 各有账号与事务管理器，跨 Schema 权限负例通过。执行器清理前检查本次实验标签，结束后无本任务容器遗留。
- **拓扑。** 临时 direct durable exchange `probe-v1`，路由 `fact` 同时绑定 durable Classic `probe-classic` 和单节点 Quorum `probe-quorum`。默认以 PERSISTENT 发布。管理 API 通过独立的临时回环端口核查类型、durable 和预期绑定，另设无绑定、错误版本、有界 Classic 与死信目标缺失的测试夹具。

```bash
r=/workspace/eng002e02-regression
dev=/workspace/car-weight-dev-jdk17
export JAVA_HOME=/workspace/car-weight-eng002a-validation/jdk
export PATH="$JAVA_HOME/bin:$dev/apache-maven-3.9.12/bin:$PATH"
python3 script/messaging/validation/run-lab.py --evidence-dir "$r/final" -- \
  -s "$dev/maven-settings.xml" "-Dmaven.repo.local=$r/m2-iot"
```

运行器先执行现有工具链检查，再依次为 A/B/C/D 执行独立 `mvn -B -ntp -f script/messaging/validation/pom.xml verify -Dtest=<对应类>`，最后执行 Maven Dependency Plugin 3.8.1 的 `tree`。也可以用 `--group` 单独运行。准确命令、退出码和数量在各组的 `result.json`，完整运行退出码在 `final.exit`。XML、脱敏容器尾日志、事务快照、Broker 状态、计划和差异证据保留于 `/workspace/eng002e02-regression/final/`。[验证工程说明](../../script/messaging/validation/README.md)

## 3. 独立 Maven 验收

| 组 | 类 | Tests | Failures / Errors / Skipped | 退出 |
| --- | --- | ---: | --- | --- |
| A MySQL 事务及幂等 | TransactionTest | 6 | 0 / 0 / 0 | 0 |
| B Relay 与 RabbitMQ 发布 | PublishTest | 7 | 0 / 0 / 0 | 0 |
| C 消费提交、ACK 及身份 | ConsumerTest | 7 | 0 / 0 / 0 | 0 |
| D 保全、重放、TRACK 与对账 | RecoveryTest | 4 | 0 / 0 / 0 | 0 |

共 24 个 JUnit 测试实例，按实际 XML 统计。断言、矩阵场景及故障子进程不计入额外测试数。两个预期子进程实际退出 37（消费提交后、ACK 前）及 38（Relay 领取提交后、发送前），父测试退出 0。依赖树退出 0。

### A. INGEST T1 与 BUSINESS T2

- T1 使用真实 InnoDB 事务。原始、意图两行在提交前经另一连接均不可见，接口在提交证明之后才返回可生成成功 L2 ACK 的状态。原始 INSERT 失败、Outbox INSERT 失败、应用异常、rollback-only 均无半提交。并发同身份同载荷返回一个提交和一个已核验重复。同身份异载荷落入冲突夹具，原始摘要保留；不同身份相同载荷分别保存。模拟提交结果未知时禁止成功状态，独立事务查证原始及意图后允许按重复处理。原始存在但意图缺失单列异常。
- T2 使用候选唯一键 `(consumer_id,producer,message_id)`，业务键另有唯一约束。Inbox、事实和后续 Outbox 同事务验证。关闭实际事务绑定 JDBC 连接使 commit 失败，另测应用异常、Outbox 失败和 rollback-only，均无事实或 Inbox 残留。模拟返回结果未知后同身份重放仅保留一份事实及后续意图；同 ID 异载荷及不支持的版本拒绝，异消息同业务键不重复新增。
- 未实现真实 INGEST 设备链路。测试中的 T1 eligibility 是合成判定，不包含编码、发送、收件或控制板释放。未知提交由“提交后隐去结果”注入，尚未做数据库 commit 报文截断时序实验。

### B. Relay、Confirm、returned 与持久队列

- 两个 Relay 使用实际 MySQL SKIP LOCKED 与短事务领取，保存 READ COMMITTED 和默认 REPEATABLE READ 的选择计划、尝试、版本及状态。两种隔离均拿到不同记录。真实子 JVM 领取后退出，租约过期后取回原消息 ID 和新 attemptId，旧版本/重复更新返回零行。迟到 Confirm 的 fence 以合成回调调用实际 SQL 验证。实际发布路径断言事务未活动，确认后的独立 NOWAIT 探针可取对应行锁。
- 专用 RabbitTemplate 使用 CorrelationData(attemptId)、correlated confirms、returns 和 mandatory。真实路由得到 ACK，空绑定得到 ACK 与 returned，满队列的 Classic reject-publish 得到 Broker NACK，不存在的 exchange 关闭 Channel 并失败。预期的 Quorum 绑定移除时仍可经 Classic 路由得到 ACK，但 Relay 拒绝标记 PUBLISHED。拓扑版本不匹配同样拒绝。没有以固定等待推断“无 returned”。
- 回环代理在 Broker 已接纳时截住传输并断开底层 TCP。发布者失去确认，原逻辑 ID 再投后读取两条真实 Broker 投递。另一次在正常确认后停止 MySQL 容器，数据库状态更新失败，原记录留在 PUBLISHING，卷恢复后新 attempt 以相同 ID 再发。Classic 与单节点 Quorum 的持久消息均在同卷有序重启后读回。该证据只覆盖单节点及当前拓扑约束。
- 同一行在确认期间的生产拓扑变更仍有竞态。前、后两次检查和 mandatory 只能提供各自时点的证据。生产部署需独立的拓扑变更与停发约束。

### C. 消费提交、ACK 与服务身份

- 明确本地 TransactionTemplate，`afterCommit` 仅标记提交完成，外层在返回且标记为真时于原 Channel 对当前 deliveryTag 使用 `basicAck(tag,false)`。真实 Broker 验证正常提交后队列取空，回滚后关闭 Channel 由 Broker 重投，新 Channel 再处理。
- 子 JVM 在 T2 提交后、发 ACK 前真实 halt(37)。重新投递携带 redelivered 标记，读到同摘要 Inbox，只留一份事实及一份业务 Outbox。另在提交后主动断开 Channel，使 ACK 失败，再由新 Channel 处理。同一消息的两个 Broker 副本在两个 Channel/消费者实例并发处理，只有一个正式事实。
- MySQL 容器不可用时，失败保全无法创建本地事务，不处置 Broker 成功 ACK 或失败拒绝。Channel 关闭后再投，数据库恢复后才持久化失败责任。
- 合成持久工作单由受限服务执行。用户撤权重新检查，不能从 headers 获得跨租户/项目身份，系统原始事实在原用户撤权后仍处理。异常后复核线程清理，独立重放需授权。这是与 ENG-002D 一致的合成策略，未接入正式授权源。ENG-002D-02R 仍独立待补。

### D. 失败记录、受控重放、TRACK 与对账

- 失败责任先在 BUSINESS 以原始字节与摘要提交，再对 Broker 发送失败拒绝。模拟格式错误、版本不兼容和业务异常，真实 DLX 缺失时仍可从本地失败账本核查原字节。累计失败上限保留 DEAD，手工恢复核验已登记记录、发起人、审批人与范围；状态更新失败后可按 Inbox 核验再复核，计划由正式恢复工具单独验收。
- Relay 测试短期退避且未来到期前不能领取，失败三次保留原始行与 DEAD 消息。夹具参数仅服务测试。重放审计包含旧事件及消息 ID、原载荷与摘要、逻辑 consumer、申请人、审批人、原因、时刻、新 attemptId、结果及对账。经真 Broker 重发，已完成的 Inbox 去重。没有通过删除消费证据执行业务修正，正式修正命令须另行审批。
- TRACK 在独立 Schema 和事务连接上回滚，不影响已提交 INGEST。失败后重投、同消息去重、迟到点追加但不覆盖最新点均通过。对账在实际 SQL 行上构造合成恢复点，输出 Event ID、Message ID、consumerId 和业务键（缺证据项保留空值），区分 INGEST 或 BUSINESS 恢复较早、Broker 早或晚、设备 ACK 未观测、未消费、缺事实、缺后续 Outbox 及摘要冲突。使用独立稳定键与合成水位，未运行物理恢复。

## 4. 既有工程回归

从获批设计提交归档出隔离默认及 IoT 副本。在 IoT 副本临时打开聚合入口。生产源码在设计与实施提交之间无差异。实际命令和退出码保留在 `/workspace/eng002e02-regression` 根目录的 `run-build.sh`、`evidence.sh`、`smoke.sh`、`graph/graph.sh` 及同名日志。

```bash
bash "$r/run-build.sh" default
bash "$r/run-build.sh" iot
bash "$r/evidence.sh" default
bash "$r/evidence.sh" iot
python3 script/shell/check-sanitation-boundaries.py --root "$r/default" --mode default \
  --iot-root "$r/iot" --evidence-dir "$r/evidence" --default-repo "$r/m2-default" \
  --iot-repo "$r/m2-iot" --jdeps "$JAVA_HOME/bin/jdeps"
# ENG-002C/D 在 $r/cd 副本运行
python3 script/database/validation/run_lab.py --evidence-dir "$r/database" -- \
  -s "$dev/maven-settings.xml" "-Dmaven.repo.local=/workspace/car-weight-eng002c02-regression/m2-default"
python3 script/authorization/validation/run-mysql.py -s "$dev/maven-settings.xml" \
  -Dmaven.repo.local=/workspace/car-weight-eng002d02-regression/m2-default
bash "$r/smoke.sh"
bash "$r/graph/graph.sh"
```

| 核查 | 实际结果 |
| --- | --- |
| 工具链与默认 Reactor | `clean install -Dmaven.test.skip=true`，44 个项目成功，约 61 秒，退出 0 |
| 隔离 IoT Reactor | `-pl :yudao-dependencies,:yudao-module-iot-core,:yudao-module-iot-biz,:yudao-module-iot-gateway -am clean install -Dmaven.test.skip=true`，23 个项目成功，约 75 秒，退出 0 |
| Java release 17 | 默认 1,149、IoT 1,783 个项目 class 均为 major 61。15 个环卫空 JAR 骨架保持。Server、独立 Gateway exec 分别 258、212 个内嵌运行库，无可靠消息验证工程或环卫 Biz |
| ENG-002B | 17 份实际有效 POM、17 棵实际依赖树，边界审计退出 0 |
| ENG-002C | 数据库隔离 45 项检查达到预期，另有 9 项 JUnit，失败/错误/跳过均为 0，退出 0 |
| ENG-002D | 原有授权验证 40 项，含 2 项 MySQL 用例，失败/错误/跳过均为 0，退出 0 |
| Gateway 定向测试 | 读取 3 个 Surefire XML。Confirm 2、Spool Store 4、Spool Service 17，共 23 项，失败/错误/跳过均为 0，退出 0 |
| Server / Gateway | 隔离 MySQL 8.4.13、Redis 7.4 和 JDK 21，Server 18.697 秒、Gateway 7.793 秒就绪。Gateway 使用 Local 总线，spool 关闭。主动停止退出 143。比较前置 Server 与本轮运行的 67 条抽样权限及数据库 Bean 关系，排序后 cmp 退出 0 |

两次 Reactor 干净安装跳过测试，定向测试和数据库实验单独执行。任务分支未触发远程 CI。PR #18 主分支历史 Actions 为 Success，使用跳过测试的默认构建。[历史 Actions](https://github.com/weeleng/car_weight/actions/runs/45923957432) · [工作流](../../.github/workflows/maven.yml)

## 5. 故障复验、未执行项与回滚

第一次单组 A 执行 6 项，1 项失败，退出 1。实际关闭 JDBC 后 JdbcTransactionManager 将 commit 失败翻译为 DataAccessResourceFailureException，早期断言预期为 TransactionException。将断言收窄为实际翻译类型后复验，事务无部分写入。随后的中间版四组 22 项通过，增加真实数据库不可用时的消费失败保全和并发消费者场景后，最终四组 24 项全部通过。原始失败和复验日志分别位于 `first`、`full-attempt`、`final`。预期的子进程退出、配置拒绝、事务失败和连接断开不计为测试失败。

未执行真实设备 ACK 链、正式 Server/Gateway 消费装配、原生数据库提交确认帧丢失、正式消费失败调度器与 DLX 保障、多节点 Quorum 选举/故障、完整仓库测试、物理备份恢复及真实外部副作用核对。生产负载、持久性参数、容量阈值、跨库同一物理实例故障风险和消息链恢复目标仍需实证。真 Broker + 独立合成 Schema 通过支持进一步实施验证，当前证据不足以关闭 DEC-006 或 BLOCK-003。BLOCK-001 与 BLOCK-007 及 ENG-002D-02R 各自继续跟踪。

正式实施须单独批准契约键、版本迁移、归属与模块边界、最小权限拓扑、L2 ACK 链路、受控重放与审计、恢复和回滚准入。回滚以反向提交撤销独立工程和报告，保留获批设计。现场排查只使用本轮隔离日志，临时数据随已核验标签的容器及匿名卷清理。差异范围、文档链接、Git 空白及常见凭证模式已经检查。

## 6. ENG-002E-02R 合并前补充审查

上一轮审核交付及本轮起始为 `16ff3321b3744186356983f0563bdbaa1305c798`。检查 `git status --short --branch`、`git fetch --no-tags origin refs/heads/master-jdk17:refs/remotes/origin/master-jdk17 refs/heads/engineering/eng-002e:refs/remotes/origin/engineering/eng-002e` 和 `git rev-parse HEAD origin/master-jdk17 origin/engineering/eng-002e`。主分支仍是 `d9230a63521df3f4f8f53a061f6c070954fa8d7f`。本轮实施提交 `e9bbd35b0378b38b4a34e56ec22dc5facb07a6b0`，报告独立提交。变更为独立验证工程的 `Broker.java`、`Protocol.java`、`Relay.java`、`Store.java`、`LabFixture.java`、`PublishTest.java`、`TransactionTest.java` 及本报告。保留原工作区未跟踪环境报告。现有 SQL 夹具唯一约束、生产代码和装配保持原样。

### Relay 分类与安全状态

| 证据 | 结果 |
| --- | --- |
| 管理端口连接失败，实际 RabbitMQ 管理服务返回 HTTP 401，或受控 HTTP 返回无法解释的拓扑结构 | `TOPOLOGY_UNKNOWN`。连接失败保留 IOException 因果，HTTP 状态和缺失响应结构走专用运行时异常。测试核对发前未投递、SQL 未标记 PUBLISHED |
| 有效拓扑已查证，但 AMQP 发布连接失败/确认超时 | `TRANSPORT_UNKNOWN`。复验重现真实发布连接不可用，SQL 保持可重试，无确认时不标记 PUBLISHED |
| Broker Confirm 已成功，但后验拓扑连接失效 | `TOPOLOGY_UNKNOWN`，SQL 未标记 PUBLISHED。Broker 已投递由独立 Channel 读取验证 |
| Confirm 后数据库停机，或后验拓扑无法确认且结果写入时数据库停机 | 返回带原 messageId、attemptId、请求失败分类及原异常的 `DB_UPDATE_UNKNOWN`。不再发起第二次状态写入。数据库恢复后查证原行仍为 PUBLISHING，保留原字节与身份，失败原因尚未持久化 |
| 过期回收、旧回调、重复回调 | 由租约和 version/attempt/owner 条件守卫。回收后使用新 attempt 保留原 ID，旧回调更新零行，同尝试重复回调无副作用。同 ID 的两份 Broker 投递及原字节可核对 |
| 第三次租约过期与预算耗尽同时发生 | 原 Outbox 变为 DEAD，失败原因为 `LEASE_EXHAUSTED`，无第四次领取、无投递、原始事件仍在 |
| 探针返回另一 Broker 标识 | 原样抛出不变量异常，PUBLISHING 等待租约处理。无普通重试记录、无投递、无 PUBLISHED |

`DB_UPDATE_UNKNOWN` 是写入方观测状态。它无法证明数据库尚未提交，也不等于已经写进 Outbox 的失败原因。测试停止实际 MySQL 容器，验证数据库无法开始结果更新。实际更新已发送但数据库提交确认报文丢失仍待验证。

### T1 单意图复核

从夹具原始 `e-<id>` 派生预期 `m-<id>`。重复确认前在同一读事务核验原始 ID 与原始字节，再查找同一事件或预期消息 ID 匹配的意图。只允许一个符合契约的意图。逐项校验 Outbox 外层事件和消息 ID、JSON 内层事件和消息 ID、生产者、信封版本、拓扑版本、序列化 payload 的 SHA-256 以及内层原始字节。

新增 **15 项 T1 JUnit**，包括正常重复、12 种缺失、错配或损坏意图，另有原始载荷冲突及错误请求消息 ID。负向用例逐一核查不可成功 L2 ACK、原始字节和摘要不变，且原始 ACK 观测状态未被改写。内部 ID、版本及字节损坏时会重算外层摘要以证明单靠哈希一致不足。既有唯一约束不变。此规则只适用于测试单意图，正式多意图契约和真设备 ACK 继续独立审批。

### 实际运行及复验记录

使用 JDK 21.0.13.1、Maven 3.9.12、MySQL 8.4.13、RabbitMQ 4.2.9，沿用第 2 节固定镜像摘要和独立运行器。实际执行命令如下。

```bash
r=/workspace/eng002e02r-regression
dev=/workspace/car-weight-dev-jdk17
export JAVA_HOME=/workspace/car-weight-eng002a-validation/jdk
export PATH="$JAVA_HOME/bin:$dev/apache-maven-3.9.12/bin:$PATH"
python3 script/messaging/validation/run-lab.py --evidence-dir "$r/lab" -- \
  -s "$dev/maven-settings.xml" "-Dmaven.repo.local=$r/m2-iot"
python3 script/messaging/validation/run-lab.py --evidence-dir "$r/final" -- \
  -s "$dev/maven-settings.xml" "-Dmaven.repo.local=$r/m2-iot"
```

首轮 A/C/D 通过，B 有 5 项错误，运行器退出 1。Broker 重启后的首次 AMQP 握手与相邻测试遇到 EOF，时段与容器启动日志重合。补充测试端监听就绪探针后重新从新容器运行四组。原始 XML、Broker 重启日志和退出码保留在 `$r/lab` 与 `lab.exit`。

| 复验组 | 原有 | 新增 | 实际测试 | 失败 / 错误 / 跳过 | 退出 |
| --- | ---: | ---: | ---: | --- | --- |
| A 事务与幂等 | 6 | 15 | 21 | 0 / 0 / 0 | 0 |
| B Relay 与 Broker | 7 | 7 | 14 | 0 / 0 / 0 | 0 |
| C 消费及 ACK | 7 | 0 | 7 | 0 / 0 / 0 | 0 |
| D 恢复与对账 | 4 | 0 | 4 | 0 / 0 / 0 | 0 |
| 总计 | 24 | 22 | 46 | 0 / 0 / 0 | 0 |

`$r/final` 保存各组实际命令、Surefire XML、分类行快照、有效依赖树及容器日志。独立工程验证 23 个项目 class，均为 major 61。预期故障的退出码仍按原场景分别校验。报告不把断言计为 JUnit 数量。

### 工程回归、剩余范围与回滚

工程回归源自已审核交付 SHA 的隔离归档，生产源码与本轮实施提交一致。执行第 4 节相同的构建、采证、检查及隔离运行命令，目录为 `$r`。默认构建及 IoT 构建跳过完整套件，定向测试和实际环境验证另行执行。

| 检查 | 本轮结果 |
| --- | --- |
| 默认 Reactor | 44 个项目成功，约 62 秒，退出 0，1,149 个项目 class 为 major 61 |
| IoT Reactor | 23 个项目成功，约 71 秒，退出 0，1,783 个项目 class 为 major 61 |
| Gateway | 三个 Surefire XML 合计 23 项通过，0 失败/错误/跳过。独立构件及最小启动通过 |
| ENG-002B | 17 份实际有效 POM 和依赖树，边界审计退出 0，检查器单元用例 23 项通过 |
| ENG-002C | 45 项隔离检查及 9 项 JUnit 通过，退出 0 |
| ENG-002D | 原有 40 项通过，退出 0。ENG-002D-02R 继续独立 |
| Server / Gateway | Server 27.628 秒就绪，Gateway 13.185 秒就绪。Server 前后各 67 条抽样 Bean 关系排序后完全一致。Server 和 Gateway JAR 分别保留 258 和 212 个内嵌运行库，均无独立验证工程。启动后主动停止退出 143 |

未执行远程任务分支 CI、真实设备 ACK、生产拓扑变更时的跨系统一致性、多节点 Quorum HA、真实数据库提交响应截断、物理备份恢复和完整仓库测试。本轮没有新增生产上线依据，DEC-006 和 BLOCK-003 继续开放。生产接管仍需正式契约、迁移、权限与运行装配审批。回滚以反向提交撤销本轮七个验证文件及报告的变更。隔离环境证据留在 `/workspace/eng002e02r-regression`，现有用户环境报告未纳入差异。
