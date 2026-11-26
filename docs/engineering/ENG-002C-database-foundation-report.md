# ENG-002C-02 三逻辑数据库及迁移体系实施报告

## 交付范围与版本

| 项目 | SHA / 范围 |
| --- | --- |
| 目标分支 `origin/master-jdk17` | `ef9fe64c65a60ee2f924a55157bb6f3aab7942ba` |
| 任务分支 `engineering/eng-002c` 实际起点与已审核设计 | `a012f9f60e0d03785fecb9135fe498834166b2fe` |
| 本次所验证的实现提交 | `8f00753f1777218450931f6467f6f247cf6a475f` |

实际执行 `git fetch origin`、`git ls-remote origin refs/heads/master-jdk17 refs/heads/engineering/eng-002c`、`git merge-base --is-ancestor ef9fe64c65a60ee2f924a55157bb6f3aab7942ba a012f9f60e0d03785fecb9135fe498834166b2fe`，核对 [AGENTS.md](../../AGENTS.md) 和 [获批设计](ENG-002C-database-foundation-design.md)。主分支与指定基线一致。表中记录实现提交，报告随其后的独立文档提交交付。

实施提交新增 16 个文件，另新增本报告。与设计提交比较，全部为新增文件。目标分支到任务分支的差异额外包含原有设计文件。根 POM、Server/Gateway 源码及 POM、BOM、既有 SQL、运行配置、CI 和 ENG-002B 检查器保持原提交内容。工作区原有的未跟踪环境报告不纳入变更。

| 目录 | 新增文件 |
| --- | --- |
| `db/migration/{ingest,business,track}/` | 各一份 `README.md`。目录无可执行 SQL |
| `script/database/bootstrap/` | `bootstrap.py`、`environment.example`、`README.md` |
| `script/database/migration-runner/` | `pom.xml`、`README.md`、`src/main/java/cn/iocoder/sanitation/tools/{Runner,Target}.java` |
| `script/database/validation/` | `README.md`、`run_lab.py`、`fixtures/{ingest,business,track}/V0001__probe_tables.sql`、`src/test/java/cn/iocoder/sanitation/tools/DatabaseTest.java` |
| `docs/engineering/` | `ENG-002C-database-foundation-report.md` |

## 初始化、权限和 Runner

- 真实 MySQL 8.4.13 隔离实例初始化 `sanitation_ingest`、`sanitation_business`、`sanitation_track`。只在本机 Docker Unix endpoint、指定容器标签、实例 `report_host` 和动态回环端口满足条件时可执行。独立 Runner 与验证数据源每次物理连接核对 MySQL 版本、隔离标识、实例 UUID、精确 catalog、账号及 UTC 会话。三个库的连接均显式配置，缺少目标即拒绝。
- 临时初始化管理员建库并配置每库独立的迁移、运行、审计、备份、恢复角色与账号。新账号使用环境注入的随机凭证。重复初始化成功，保持现有密码和额外人工授权，生产中的漂移治理尚未审批。运行及审计初始无表权限。专用夹具开关仅赋予运行 SELECT/INSERT 和审计 SELECT，备份与恢复在单独账号验证。测试实证运行账号无法跨库查询、DDL、更新或删除模拟原始行。迁移角色缺少 DROP，真实权限不足导致非零退出并留下失败历史。
- Runner 在 Reactor 外固定 Flyway OSS Core/MySQL **11.20.3** 和 Connector/J **9.7.0**。实际运行类路径还包含 Jackson annotations 2.21、core 2.21.4、databind 2.19.1、protobuf-java 4.31.1。Core 与 MySQL JAR SHA-256 分别为 `71dc5aba396aa4636f29f2124fe715fa799e6fd8126ce1bcdf162b4166efab4b` 和 `eb5050577ae271d93f8e0f1f0c3908e959447e0ea0d51a200eb0de448b04a07a`。官方父 POM 声明 Apache-2.0，Core JAR 内含对应 `META-INF/LICENSE.txt`。本次 MySQL 8.4.13 所测 validate/migrate 功能可在无商业授权配置下执行。部署发行及传递组件许可证审查仍须按上线流程完成。[Flyway 11.20.3 父 POM](https://repo.maven.apache.org/maven2/org/flywaydb/flyway-parent/11.20.3/flyway-parent-11.20.3.pom)
- 仅接受 `validate` 或 `migrate`、三个逻辑库之一以及显式迁移根。validate 允许尚未执行的新版本，拒绝校验和漂移、缺失脚本和失败历史。migrate 先校验、执行、再严格校验。三个 catalog 各写各的 `flyway_schema_history`。无自动 baseline、repair、outOfOrder 或应用启动自动迁移。`clean` 和非隔离环境标签实测退出 2。数据库或迁移拒绝退出 1，成功退出 0。

复现命令从仓库根执行。实际测试的容器、临时凭证注入、三个库的命令及负例由 `run_lab.py` 编排。手工入口使用同一配置，变量名见 [环境模板](../../script/database/bootstrap/environment.example)。

```bash
bash script/shell/check-build-toolchain.sh
r=/workspace/car-weight-eng002c02-regression
lab=/workspace/car-weight-eng002c02-lab
dev=/workspace/car-weight-dev-jdk17
python3 script/database/validation/run_lab.py --evidence-dir "$lab/final" -- \
  -s "$dev/maven-settings.xml" "-Dmaven.repo.local=$r/m2-default"

python3 script/database/bootstrap/bootstrap.py --container "$container"
mvn -f script/database/migration-runner/pom.xml -DskipTests package \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies \
  -DincludeScope=runtime "-DoutputDirectory=$runtime_libs"
java -cp "script/database/migration-runner/target/classes:$runtime_libs/*" \
  cn.iocoder.sanitation.tools.Runner validate ingest db/migration
java -cp "script/database/migration-runner/target/classes:$runtime_libs/*" \
  cn.iocoder.sanitation.tools.Runner migrate ingest "$fixture_root"
```

## 迁移实测

最终实验进程退出 0，`checks.tsv` 记录 **45 项均达到预期**，包括预期非零退出的负例。合成版本只出现在专用测试目录和临时工作副本。

| 实例和库 | 迁移历史及操作结果 |
| --- | --- |
| 主实例 INGEST | 空库 validate、首次 V0001、重跑应用 0。两个进程并发 V0001→V0002，均正常退出，合计只应用 1 次。V0001、V0002 成功。V0003 在 CREATE 后、后续语句执行中断开真实连接，保留 DDL 与 `success=0` 历史，重跑被拒 |
| 主实例 BUSINESS | 空库、首次 V0001、重跑应用 0。先由备份角色取得清洁快照，再触发 V0002 权限不足。残留 DDL 在场，历史成功标记依次为 1、0，migrate 退出 1，第二次执行仍拒绝 |
| 主实例 TRACK | 空库、首次 V0001、重跑应用 0。其他库升级时，本库仍处于 V0001 |
| 第二实例 BUSINESS | 恢复角色导入合成快照，检查恢复行和历史，人工确认使用修正后的合成 V0002，状态依次为 1、1。主实例失败历史保留 |
| 第二实例 TRACK | 独立 MySQL 进程与实例 UUID，独立 V0001 历史和存储。验证应用显式切换连接并写入定位点，主实例查不到该行 |

改写已执行脚本及移除脚本分别让 validate 退出 1。恢复原文件后校验通过。并发、校验和、权限不足、中断和恢复均未修改源实例失败记录。具体版本及成功标记留在 `final/*-history.tsv`，保留迁移日志与错误输出。

## 显式数据源、事务、时间与重量

隔离 Spring 上下文使用三套独立 Druid 池、分别绑定的 `ingestTxManager`、`businessTxManager`、`trackTxManager`，以及 MyBatis 3.5.19 显式 factory/template 和测试 Mapper。JUnit **9 项通过，失败、错误、跳过均为 0**。其中时区场景在同一 JVM 中分别切换默认时区 UTC 和 Asia/Shanghai 后执行，结束恢复时区。

- 核对三套池、catalog 和事务管理器绑定。数据库不可用、未知逻辑库、错误 catalog、错误 Mapper/事务组合都拒绝。已绑定其他逻辑库的事务时，`REQUIRED` 和 `REQUIRES_NEW` 均拒绝。检查同线程事务完成、回滚后上下文清理与后续重用。
- INGEST 的原始事件与 Outbox 一起提交，在不同写入阶段抛错均整体回滚，唯一键并发写入只保留一组。BUSINESS 的 Inbox、正式记录夹具、业务 Outbox 一起提交，分别注入失败验证回滚，同键重放不产生重复副作用。两个流程中模拟 ACK 只记录于成功提交后。TRACK 验证独立事务、定位点 Mapper 和独立实例。
- JDBC 显式转换 `Instant` 与 UTC `DATETIME(3)`，校验 MySQL 会话 UTC。JDBC 与 MyBatis 均往返保留毫秒，测试 Jackson ISO 8601 DTO 序列化与反序列化。时间结论适用于测试映射及 DTO。
- 覆盖有符号 INT32 克和 BIGINT 克的上下界、正负值、`DECIMAL(12,3)` 千克精度、`BigDecimal.valueOf(grams, 3)`，并验证数据库拒绝超范围整数及十进制值。夹具使用整数或精确小数列。遵循 [C-02 时间](../architecture/standards/time-conventions.md)、[C-03 重量](../architecture/standards/weight-conventions.md)、[C-04 ACK 与持久化](../architecture/standards/iot-ack-and-durability.md)及 [C-05 事务](../architecture/standards/transaction-boundaries.md)。

## 工程回归与旧 Server Bean 图

回归源码由获批设计提交导出到两个隔离目录。本实现增加的工具、文档和夹具均在默认 Reactor 外，现有模块源码与最终实现提交逐文件一致。IoT 副本仅在临时根 POM 打开 IoT 聚合入口。JDK 为 Temurin OpenJDK 21.0.13.1，Maven 为 3.9.12，Java release 17。两次安装均显式跳过测试，Gateway 定向测试独立执行。

```bash
# 两个隔离副本分别执行。maven_flags 含 -B -ntp -C、settings 及各自本地仓库参数
bash script/shell/check-build-toolchain.sh
mvn "${maven_flags[@]}" clean install -Dmaven.test.skip=true
mvn "${maven_flags[@]}" -pl :yudao-dependencies,:yudao-module-iot-core,:yudao-module-iot-biz,:yudao-module-iot-gateway \
  -am clean install -Dmaven.test.skip=true
mvn "${maven_flags[@]}" -pl :yudao-module-iot-core,:yudao-module-iot-gateway -am \
  -Dtest=IotRabbitMQMessageBusConfirmTest,IotDeviceMessageSpoolServiceImplTest,IotDeviceMessageSpoolStoreTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
python3 script/shell/check-sanitation-boundaries.py --root "$r/default" --mode default \
  --iot-root "$r/iot" --evidence-dir "$r/evidence" --default-repo "$r/m2-default" \
  --iot-repo "$r/m2-iot" --jdeps "$JAVA_HOME/bin/jdeps"
```

| 检查 | 结果 |
| --- | --- |
| 默认 Reactor | 44 项全部成功，退出 0，56.062 秒。15 个环卫空 JAR 均生成，7 个新增聚合 POM 关系通过审计 |
| 隔离 IoT Reactor | 实际 23 项全部成功，退出 0，约 67 秒。显式选入 BOM，与 Core、Biz、Gateway 及依赖一起安装 |
| Gateway 定向测试 | 退出 0。解析三个 `target/surefire-reports/TEST-*.xml`，RabbitMQ Confirm 2 项、SQLite Spool Store 4 项、Spool Service 17 项，共 23 项，失败、错误、跳过均为 0 |
| 依赖与产物 | Maven Help 3.5.1、Dependency Plugin 3.8.1 生成 17 份 effective POM 和 17 棵 JSON 树。与 ENG-002B 对照证据逐字节相同的 Server 树有 273 个节点，Gateway 树有 287 个节点。无新增 Flyway、环卫或 Gateway 装配到 Server，无新增环卫依赖到 Gateway。Server 和 Gateway exec JAR 分别包含 258 和 212 个内嵌库，逐项检查均无 Flyway 或环卫库。独立 Gateway exec JAR 可运行 |
| ENG-002B 检查器 | 实际证据模式退出 0。JDK 21 `jdeps` 审计项目类引用，默认/IoT 检查的 1,149/1,783 个项目 class 均为 major 61。15 个空 JAR 的字节码检查为不适用 |
| 最小启动 | 隔离 Docker 内网中的 MySQL 8.4.13、Redis 7.4 和 JDK21 下，现有 Server 19.754 秒就绪，Gateway 12.108 秒就绪。Gateway 使用 Local 总线、关闭 spool。就绪检查退出 0，容器主动停止后退出 143 |
| 旧 RuoYi Bean 图 | 用临时只读挂载探针和 Spring Boot PropertiesLauncher，在 `ApplicationReadyEvent` 读取当前 Server 与保留的 ENG-002B Server 镜像。排序后 `cmp` 退出 0，两份各 58 行，包含 54 个 MapperFactoryBean 及其依赖。两端均为 `DynamicRoutingDataSource`，保持原 `master` 和 `slave`。原 `JdbcTransactionManager` 使用该数据源，MyBatis-Plus 创建原 factory/template，未出现新环卫事务管理器。探针在隔离副本执行，未加入 Server JAR |

验证应用使用原生 MyBatis 的测试 Mapper。旧装配对照验证了真实 Server Bean 图中的数据源、事务与 Mapper 绑定。正式环卫 Mapper、MyBatis-Plus 扫描、拦截器与租户规则组合仍待单独批准并验证。静态依赖检查的反射及动态绑定覆盖限制沿用 [ENG-002B 报告](ENG-002B-module-skeleton-report.md)。

## 失败、复验与未执行事项

| 早期结果 | 修复或复验 |
| --- | --- |
| 首次隔离验证 Maven/JUnit 退出 1，9 项中 1 项错误。只在获取连接检查不足，Spring 在跨库嵌套路径先挂起原同步状态 | 将校验提前到具名事务管理器 `doGetTransaction`，在数据源层保留错误 Mapper 保护。复验覆盖 REQUIRED/REQUIRES_NEW，`attempt2` 41 项，最终新增 4 项后为 45 项 |
| 首次回归包装脚本工作目录后缀重复，退出 2 | 修正隔离脚本路径，重新执行两次 clean install，两次均退出 0 |
| Bean 图首选路径收到 HTTP 200、应用体代码 404。首次本地探针包装启动因 shell 函数声明错误退出 1 | 改用容器实际 Spring 上下文，修正函数及监听器，两个探针均运行至就绪并取得一致 Bean 图 |
| 迁移内容漂移、脚本缺失、权限不足和中断各产生预期失败 | 保留失败历史及 DDL 现场，只在第二实例恢复隔离快照。最终达到预期的负例不计为 JUnit 失败 |

本次核心验收门槛在隔离验证与回归范围内通过。证据保留于工作区 `/workspace/car-weight-eng002c02-lab/{attempt1,attempt2,final}` 和 `/workspace/car-weight-eng002c02-regression`，包括命令脚本、退出码、MySQL 历史、Maven 日志、XML、依赖树和 Bean 快照。临时测试表、账号、容器和本机诊断探针不作为正式部署交付。

尚未执行完整仓库测试套件、远程 CI、生产 MySQL 部署、真实设备 ACK、真实 RabbitMQ 消费确认与重传、生产流量及故障域 RPO 验证。版本化正式业务迁移、生产部署授权、TLS/凭据、物理拆分的数据迁移与正式环卫 MyBatis-Plus 装配另行审批。DEC-006 的实施限于本次基础设施。DEC-008、DEC-009、DEC-011、DEC-015、DEC-016 和 BLOCK-001/003/007 继续开放。

交付前执行 `git diff --check`、Git 新增文件范围校验、报告相对链接检查、常见密钥模式扫描及 JAR 清单检查，均通过。

## 回滚

代码回滚以反向提交撤销实现和报告，保留原设计提交。现有 Reactor、Server 和 Gateway 无新增装配。隔离实验可移除本轮创建的容器及其匿名卷。对任何已有持久数据，先停止迁移并保留快照、日志和历史，由授权人员评审前向修复或从可信快照恢复。夹具已实证在 MySQL 隐式提交后可留下部分 DDL，禁止用自动 repair 或清理失败历史作为恢复方式。
