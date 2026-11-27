# ENG-002F-02B-2B 关键计量事件可靠交接独立验证

## 1. 基线与范围

仓库 `weeleng/car_weight`。目标分支 `master-jdk17` 实际 SHA `4f414b21ea64f770e5a5172d592c3ce81d9ee0a9`，为任务分支 `engineering/eng-002f-handoff` 的祖先。任务分支起始及远程一致，均为 `81f9db3162e9c5746d37ea28eadadcbfd034528a`。执行 `git status --short -b`、`git ls-remote`、按引用 fetch、`git rev-parse` 与 `git merge-base --is-ancestor`。保留未跟踪环境基线文件。PR #20 合并构建复查为 Success，工作流使用 `-Dmaven.test.skip=true`。[已审核设计](ENG-002F-key-event-handoff-design.md) · [Actions](https://github.com/weeleng/car_weight/actions/runs/45961630079)

独立验证工程实施 SHA 为 `e6951b9e45e1eb67849f97ed5f4dcbf95b2b77e3`。本报告单独提交，远程交付分支包含实现与报告。

仅新增本报告及 `script/gateway-spool/validation/key-handoff/` 子目录。正式 Gateway、IoT Core、Server、根及模块 POM、配置、迁移、VWCP、L2 ACK、E 与 02A 的测试和审批状态均未变。[独立工程](../../script/gateway-spool/validation/key-handoff/README.md)

| 位置 | 文件 |
| --- | --- |
| 子目录入口 | `README.md`、`pom.xml`、`run-lab.py`、`check-gateway-compatibility.py` |
| `fixtures/` | `GatewaySchemaWitness.java` |
| `src/main/resources/` | `candidate.sql` |
| `src/main/java/lab/handoff/` | `Db.java`、`Migration.java`、`Owner.java`、`Spool.java`、`Ingest.java` |
| `src/test/java/lab/handoff/` | `LabTest.java`、`Child.java`、`SchemaTest.java`、`StateTest.java`、`ProofTest.java`、`BrokerTest.java`、`RecoveryTest.java` |

## 2. 环境、入口和结果

隔离 x86_64，5 个逻辑 CPU。Temurin 21.0.13.1+2、Maven 3.9.12、release 17、SQLite JDBC 3.49.1.0、AMQP Java Client 5.25.0。实际 SQLite 引擎和 RabbitMQ 版本见最终证据。RabbitMQ 仅在宿主回环动态映射端口，使用本轮随机凭据及合成 vhost。T1 合成事务位于另一个 SQLite 库。正式 T1 的数据库与装配不在该实现中。

```bash
r=/workspace/eng002f02b2b
dev=/workspace/car-weight-dev-jdk17
export JAVA_HOME=/workspace/car-weight-eng002a-validation/jdk
export PATH="$JAVA_HOME/bin:$dev/apache-maven-3.9.12/bin:$PATH"
export MAVEN_OPTS=-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts
python3 script/gateway-spool/validation/key-handoff/run-lab.py --evidence-dir "$r/final" \
  -- -s "$dev/maven-settings.xml" "-Dmaven.repo.local=$r/m2-iot"
python3 script/gateway-spool/validation/key-handoff/check-gateway-compatibility.py \
  --gateway "$r/iot/yudao-module-iot/yudao-module-iot-gateway/target/yudao-module-iot-gateway-exec.jar" \
  --evidence-dir "$r/final"
```

| 组 | 内容 | 执行 | 失败 | 错误 | 跳过 | 退出码 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| A | SQLite Schema、外键、历史升级 | 6 | 0 | 0 | 0 | 0 |
| B | 分类、身份、先落盘、状态及租约 | 5 | 0 | 0 | 0 | 0 |
| C | 真实 RabbitMQ、合成 T1、证明与清理 | 11 | 0 | 0 | 0 | 0 |
| D | 关停、容量、接管及双终端 | 5 | 0 | 0 | 0 | 0 |
| 合计 | 提交后完整复跑 | **27** | 0 | 0 | 0 | 0 |

实际 SQLite 引擎 3.49.1，RabbitMQ 4.2.9。正式 Gateway v0 校验探针另退出 0，证据见 `final/gateway-compatibility/{check.log,jar.sha256}`。

根证据目录 `/workspace/eng002f02b2b`。`final/environment.json` 保存 Git SHA、子目录文件 SHA-256 和工具版本。各组 `result.json` 分别保存实际 Maven 命令、退出码和 XML 统计。JUnit XML、Maven 日志、子进程故障码、数据库行/状态、消息身份、SQLite 文件摘要、Broker 日志、迁移映射和查询记录保留在该目录。用例数由 Surefire XML 统计，故障断言数量无需折算。

## 3. 候选 Schema、v0 与恢复

方案 B 在同一合成 SQLite 文件保留 v0 普通表及时间索引，新建 `key_event_spool`、`key_handoff_evidence`、`key_spool_quarantine`。候选版本为 2002。辅助试验表用于所有者世代、独立普通配额、清理审计。明确启用外键，写连接使用 WAL 文件与 synchronous FULL。保留原始 Event/Message 唯一键、可迟延领取索引以及同一行证据的复合引用。

单向引用比较用一组最小对照表。证据指向原行时，数据库仍允许没有证据的完成行。采用双向引用及完成状态 CHECK 后，缺失证据、错行引用及未交接清空载荷均失败，引用仍存在时也无法删除证据。证明内容与原行身份、租户、设备、意图、摘要在候选应用事务内核验，摘要检测用于合成变更，不能替代将来安全认证。

实际 v0 DDL 生成旧 SQLite 夹具。保留原表和原字节，同时把历史身份未核实的四行映射至隔离表，含重复旧 ID、重复载荷、无 ID、坏 JSON。记录源卷、原行号、迁移批次和摘要，未生成正式 Event ID。未 checkpoint WAL 的子进程退出 41，一致性 backup 保留两行，主文件副本仅见一行。迁移子进程在事务内退出 42，重开仍为 v0、无候选表，重试通过。未知版本、混合结构、损坏文件拒绝且主文件摘要一致，源备份走只读 SQL。重复迁移不会重置新状态。候选已接收或部分发布时拒绝以旧备份覆盖，新事实需前向对账；本轮不执行备份替换。

正式 Gateway v0 校验通过反射调用实际构件内的 `SpoolStartupValidator.validate`，打开的是独立合成文件。验收证据见 A 的映射、WAL 快照及兼容探针。现存普通表留作兼容来源。本候选不启动生产普通 worker 或实际执行旧版本升级窗口。迁移前停旧由外部 fencing 保证，旁路锁保护合作进程。

## 4. 关键保全、状态与身份

依据受信测试入口提供的枚举分流。KEY_MEASUREMENT 的本地事务先提交，后领取并进行网络 I/O。ORDINARY_TELEMETRY 独立预算和清理。UNKNOWN 有界隔离或明确拒绝。客户端 priority 无分类权限。测试同身份同载荷重用，异载荷保存冲突及原始事实，不同身份同载荷独立，跨租户输入拒绝。候选摘要对原字节，并以合成命名空间与身份绑定，正式算法和字段仍需 G/I。

候选状态包括 PENDING、PUBLISHING、RETRY_WAIT、BROKER_CONFIRMED、WAITING_INGEST、HANDOFF_COMPLETE、QUARANTINED。SQLite 短事务按 generation、owner、attempt、version 与单调时基 CAS。到期、迟到、重复及上一所有者回调检查。UTC 历史时间与 lease 的进程内单调 elapsed 分离，重启先推进世代并重新安排未完成 attempt。回拨和前跳用合成时钟覆盖。写前进程 43 未提交，提交后进程 44 保留 PENDING 及原字节。永久拒绝、坏载荷、预算耗尽和未知传输结果均无关键清理许可。网络等待不处在候选写事务内。

## 5. RabbitMQ、T1 与证据清理

真实 RabbitMQ 得到正向 Confirm、无绑定时的 ACK + returned 312、限长队列真实 NACK、缺失 exchange 时的 Channel 关闭。删除预期绑定后被动队列检查失败，保留原载荷。Broker 已确认而 SQLite 触发器阻止状态更新时，本地仍为 PUBLISHING，租约到期后重领。实际停启 Broker 应用期间，回环连接失败，本地关键写入仍可提交，同卷应用重启后读到原持久投递。

回环代理丢失发布端确认，旁路见 Broker 已收到，恢复后两次投递使用同一 Message ID。另有子 JVM 在真实确认后以 48 退出，本地未更新 Broker 状态，新 owner 继续同身份投递。两次消费对应合成 T1 只留下 1 条 raw 和 1 条 Outbox。所有仅有 Broker 结果的路径继续保留关键原件，收到正确权威 T1 后才允许登记和清理。见 `final/C/C-{routes,confirm-loss,state-write-failed,restart,crash-and-dedup}.txt` 与子进程日志。

合成 T1 在独立 SQLite 原子提交 raw 与完整单个 Outbox 意图。左连接核验原始完整性、内外身份、摘要、预期意图。回滚和提交前进程退出留零行；已提交但响应/通知均丢失后从权威事务重查。通知先于 Gateway Broker 状态可按权威证据完成。错误发行者、租户、设备、Event/Message ID、摘要、意图与版本、同 proofId 异内容、空的滞后读取、超时和不可用均不能推进未交接状态。

| 模式 | 候选判断 |
| --- | --- |
| A 只查 | 可重启重查，查询有延迟及压力 |
| B 只通知 | 低延迟，丢通知时无独立补偿 |
| C 受控响应 | 请求窗口快速，成功后丢响应仍需权威查证 |
| D 通知与主动查证 | 本轮实际夹具。通知触发权威查询，丢失时独立查证同一身份。没有新增正式连接协议 |

本地在同一 SQLite 事务核验当前原行、登记证据、推进版本、解除租约及标记 HANDOFF_COMPLETE。通知重复无二次写入，旧回调不降级。登记前进程 45 回滚，登记后进程 46 保留待清理载荷。清理及归档另起事务，注入失败时两边都保留，成功后保留证据及摘要，可识别重复上传。经过清理后权威查询失败不改已有审计状态；修改证据内容会阻止清理。七天保留、永久拒绝和坏载荷均不清除无证明关键记录。正式 T1、证明认证及设备成功 ACK 的批准权归 G/I。

## 6. 容量、关停及双端模拟

独立压力样本为 4 个合成设备并发、96 条记录、每条 8 KiB、事件年龄模拟 20 天。写入 0.181 秒。恢复前 WAL 为 2,826,352 字节，checkpoint 后主文件 966,656 字节、WAL 为 0。SQLite dbstat 的关键主表为 839,680 字节、证据表 36,864 字节、隔离表 4,096 字节，due、lease 与两个关键唯一索引各 4,096 字节。轮转本地补传及 T1 模拟约 111.4 条/秒，权威查询及证据登记约 558.7 条/秒。独立真实 RabbitMQ 样本 32 条、每条 2 KiB，含 SQLite 调度的确认速率约 114.1 条/秒。无 T1 证据，32 条仍均保留。数据来自 `final/D/D-capacity.txt` 和 `final/C/C-broker-rate.txt`。

独立关键和普通逻辑配额、普通高水拒绝、关键满额、可重复模拟物理水位及清理不驱逐关键事实已验证。测试轮转调度用于检查四设备进展。正式公平性、滞回与上游背压需生产链路验收，物理磁盘空间不足通过输入阈值模拟。

关停测试同时持有发布、证明主动查询、通知处理及清理角色的任务票据。停止后拒绝新应用读写及新权威查询，允许已准入工作完成短事务。所有票据未释放时第二实例不能接管，网络未知保留。独立子进程持锁，另一 JVM 拒绝退出 49，原进程中止退出 137 后，新 owner 读取同一原行。B 组验证继任世代拒绝旧 Attempt。真实 Gateway 1R 关闭行为另由回归覆盖。

双端夹具以同一合成 SQLite 模拟板上持久容量。Android RS485 与 PDA 蓝牙转发使用同一事件身份，模拟重启重开载荷、切端、同载荷重复和异载荷冲突。延迟或丢失合成 ACK 时板保留原件，匹配权威 T1 后模拟转发并释放。未测试正式协议、两端设备存储及实际 BLE/RS485。

## 7. 工程回归及故障记录

| 检查 | 实际结果 |
| --- | --- |
| 默认 Reactor | 44 项，`clean install -Dmaven.test.skip=true`，退出 0。1,149 个项目 class 均为 major 61 |
| 隔离 IoT Reactor | 23 项，`clean install -Dmaven.test.skip=true`，退出 0。1,786 个项目 class 均为 major 61 |
| Gateway 定向及协议 | 107 项，0 失败、错误或跳过，退出 0 |
| ENG-002E | 46 项通过，退出 0 |
| ENG-002F-02A | 原始 Gateway 构件 A/B/C/D 29 项通过，退出 0 |
| ENG-002F-02B-1 | 10/10，31 次实际 Gateway 子进程观察，退出 0 |
| 最小启动 | Server 88.410 秒，Gateway 39.309 秒就绪，主动停止退出 143，脚本退出 0 |
| ENG-002B | 实际有效 POM 及依赖树审计退出 0，边界检查器 23 项通过 |
| ENG-002C | 45 项隔离检查及 9 项 JUnit 通过，退出 0 |
| ENG-002D | 40 项授权验证通过，退出 0 |

回归使用任务基线的独立归档副本，IoT 入口只在隔离副本启用。命令和 Maven 日志在 `run-build.sh`、`evidence.sh`、`gateway107.log`，最小启动和 02A/02B 专项证据在 `smoke.sh`、`02a/`、`real/`。边界、三库、授权及 E 的输出分别为 `boundaries.log`、`boundary-tests.log`、`database/`、`d.log` 和 `messaging/`，各子任务退出码在根目录。两次 Reactor 跳过测试，JUnit 和真实运行专项单独执行。

首轮测试编译因夹具调用漏传原字节失败。修复后，第二轮 SQLite JDBC 的 busy_timeout PRAGMA 返回结果集，原 executeUpdate 不适用。改用 execute 并区分更新计数后，A/B/D 独立复验通过。RabbitMQ 试跑曾因默认用户的 CLI 与 Broker 启动期 cookie 文件争用触发 eacces。改为等待 cookie 就绪并以 rabbitmq 用户执行诊断、停启命令。上述故障日志分别保留在 `first/`、`second/`、`third/`、`broker-run/`、`sqlite-run/`。

## 8. 剩余边界和 02B-2C 准入

需要 G 审批正式事件身份、分类、终端存储及 ACK，H 交付黄金向量，I 批准生产 T1 完整意图、可见性、真实性与证明接口。02B-2C 另需批准有限 Gateway 实施与正式 SQLite 迁移方案，再执行新旧装配、等效真实 RabbitMQ、真实 MySQL、双形态真机、容量、断电及跨主机故障验收。证据摘要、静态 T1 数据一致性、代际和性能结果仅适用于隔离夹具，未测试真正的 COMMIT 网络截断或声明 RPO=0。BLOCK-001/002/003/007 与 DEC-001～004/006/016 继续开放。[GitHub 比较](https://github.com/weeleng/car_weight/compare/master-jdk17...engineering/eng-002f-handoff?expand=1)
