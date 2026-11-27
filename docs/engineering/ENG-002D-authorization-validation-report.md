# ENG-002D-02 项目级授权与权限隔离验证报告

## 基线与范围

| 版本 | 完整 SHA |
| --- | --- |
| 主分支基线 | `e5a4dbd434a570a14c47195b6fa77debd6d70a44` |
| 实际起始及获批设计 | `468b4c51f3be2529655fded443988921acf9039e` |
| 实际实施及验证提交 | `c3cd3fc61a4ed960d480f60d1e3edf623210b179` |

执行 `git status --short --branch`、`git ls-remote origin refs/heads/master-jdk17 refs/heads/engineering/eng-002d`、`git fetch --no-tags origin master-jdk17 engineering/eng-002d`、`git rev-parse HEAD origin/master-jdk17`，核对远程两分支与任务指定值一致。依据 [AGENTS.md](../../AGENTS.md) 和[获批设计](ENG-002D-tenant-project-authorization-design.md)第 16 节执行方案 A。实施和报告分别提交。工作区原有未跟踪报告留在提交外。

| 目录 | 本任务新增文件 |
| --- | --- |
| `script/authorization/validation/` | `README.md`、`pom.xml`、`run-mysql.py` |
| 同目录 `probes/` | `TenantProbe.java`、`check-source.py`、`run-tenant-probe.py` |
| 同目录 `src/main/java/cn/iocoder/sanitation/validation/` | `Authorization.java`、`Execution.java`、`History.java`、`Observations.java`、`PrivateFiles.java` |
| 同目录 `src/test/java/cn/iocoder/sanitation/validation/` | `Fixtures.java`、`PolicyTest.java`、`ExecutionTest.java`、`DomainTest.java`、`MySqlScopeTest.java` |
| `docs/engineering/` | 本报告 |

共 17 个新增文件。与主分支比较另包含第一阶段设计。原有 Java、POM、SQL、CI、Server/Gateway 装配、15 个环卫空 JAR 结构及边界检查器均保持基线。

## 工具链与执行证据

Temurin OpenJDK **21.0.13.1**、Maven **3.9.12**、Java release **17**。独立工程实际解析 Spring Web/Test **6.2.19**、Spring Security Core/Crypto **6.5.11**、Servlet API **6.0.0**、JUnit Jupiter **5.12.2**（Platform **1.12.2**）、MySQL Connector/J **9.7.0**。SQL 容器使用 MySQL **8.4.13**。仓库根执行以下命令，Maven 参数指向开发 settings 及隔离 Maven 仓库。[验证工程说明](../../script/authorization/validation/README.md)

```bash
r=/workspace/car-weight-eng002d02-regression
dev=/workspace/car-weight-dev-jdk17
a=script/authorization/validation
flags=(-s "$dev/maven-settings.xml" "-Dmaven.repo.local=$r/m2-default")
bash script/shell/check-build-toolchain.sh
python3 "$a/run-mysql.py" "${flags[@]}"
mvn -B -ntp "${flags[@]}" -f "$a/pom.xml" clean verify
mvn -B -ntp "${flags[@]}" -f "$a/pom.xml" test -Dgroups=negative
mvn -B -ntp "${flags[@]}" -f "$a/pom.xml" test -Dgroups=positive
mvn -B -ntp "${flags[@]}" -f "$a/pom.xml" org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree
python3 "$a/probes/check-source.py"
python3 "$a/probes/check-source.py" --inventory
python3 "$a/probes/run-tenant-probe.py" "$r/default/yudao-server/target/yudao-server.jar" "$r/final/probe"
```

实际退出码、依赖树、各运行的 Maven 日志及独立保存的 Surefire XML 在 `$r/final/`。每个命令退出 0，TenantProbe 子进程的预期非零详见下节。

## 测试结果

| 执行组 | 实际测试数 | 失败 / 错误 / 跳过 | 退出 |
| --- | ---: | --- | --- |
| 默认独立工程 clean verify | 38 | 0 / 0 / 0 | 0 |
| MySQL 包装运行 | 40（前述 38 项及 2 项实际 JDBC 用例） | 0 / 0 / 0 | 0 |
| 默认负向用例重跑 | 32 | 0 / 0 / 0 | 0 |
| 默认正向用例重跑 | 6 | 0 / 0 / 0 | 0 |

合计 40 个不同测试实例，正向 7、负向 33。默认运行按标签排除 2 个 MySQL 用例，已在包装运行补齐。重跑不重复计数。

- **授权与缓存，21 项。** 来源与目标租户、项目、主体、资源、操作、数据窗和授权版本共同限定许可。项目成员以绑定主体的授予记录建模，项目角色以不可变操作集合建模。用例覆盖同租户两项目、显式监管、功能权限交集、新建项目、租户类型与能力分离、管理员、空授予、停用主体/租户/项目、过期和设备身份。权威版本先于缓存复核，通知丢失和旧结果并发回填仍拒绝，高风险操作无法使用陈旧允许结果。角色缩小、重新启用和可信时钟分别验证。缓存和版本存储是同进程夹具，尚未测量 Redis、多节点或撤权 SLA。
- **HTTP 与异步，8 项。** Spring Mock Servlet 实际调用 `OncePerRequestFilter`，从测试 Principal 提取身份，检查实际 Spring SecurityContextHolder 与合成租户、ignore、项目和独立审计 ThreadLocal。用例含未经认证的请求头、失败后清理、MQ 与 Quartz 信封范围不匹配、人员命令撤权、服务继续处理已登记的系统事实、嵌套和同一 worker 复用。排队取消未建立上下文。运行中超时及取消后，worker 保持执行，任务真实退出后，在同一线程核查所有载体清理。未启动 RabbitMQ Broker 或 Quartz 调度器，也未装配真实 RuoYi HTTP 认证链。
- **历史、地图、文件和导出，9 项。** 半开区间、并发重叠、停用、换卡/板、迟到和不可信时钟均有断言。历史匹配与当前对象许可分开判断，旧版本保留。地图、聚合、下钻、排序、分页共用对象范围。附件按业务对象当前归属、项目及时间授权，未授权和不存在对外行为一致，另覆盖跨租户/项目、归属变更和过期。导出在创建、执行和下载时重新授权，覆盖撤权、分块下载、暂存清理与阶段审计。输出仅能经受控接口访问。暂存、审计均在内存，本轮没有真实签名服务、文件流或反向代理。
- **实际 MySQL 查询，2 项。** JDBC 在建临时表前核对 MySQL 版本、动态回环端口、报告标识、实例 UUID 与 Schema。SQL 绑定租户、项目及请求与许可时间范围，然后聚合、分页或下钻。获准分组仅 `a:1:2:5`、`b:1:3:6`（网格、数量、分子、分母）。下钻和分页命中预期行，混合其他租户同项目号、本租户不同项目和出窗对象均被排除。空范围绑定恒假条件，累计为 `0:0:0`。JDBC 探针未接入正式 Mapper、生产 MyBatis-Plus 或 Server。

## TenantIgnore 与现有 RuoYi 边界

库存扫描已跟踪 Java/YAML。实际输出 84 处 Java 位置（19 处 Controller、65 处其他源码）及 4 个配置位置。基线 `ignore-urls` 含 `/jmreport/*`，访问租户豁免含 profile/auth，`ignore-tables` 为空，`ignore-caches` 有 9 项。逐项调用理由见获批设计第 7 节。实际路径及行号见 `final/inventory.log`。[Server 配置](../../yudao-server/src/main/resources/application.yaml#L370)

| 检查 | 触发与复验证据 | 生产影响及候选修复 |
| --- | --- | --- |
| TenantLine 元数据 | 真实 Server 构件解析已登记表时加入租户字段，无租户时拒绝，未登记合成表则无谓词。真实 CGLIB 切面启用 ignore 并在异常后恢复。正常组件探针 6 个断言通过，退出 0。负例将租户谓词设为合成 SQL 必须满足的断言，按预期失败并退出 1。包装脚本核对指定错误后退出 0 | 地图、报表、监管、原生 SQL 和新 Mapper 均需显式范围。新增或变更生产拦截逻辑需单独批准。[TenantDatabaseInterceptor](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/db/TenantDatabaseInterceptor.java) |
| Token 兼容及会话撤销 | 源码探针确认 Redis、访问令牌表、刷新令牌兼容及回填顺序。按字符串删除访问令牌时，访问记录查不到会在删除刷新令牌前返回。未发送真实凭据及运行撤销链路 | 适用高风险操作、监管和订阅。候选按客户端专项评估 access-only、兼容缓存竞态与长连接，修改上游须另行批准。[OAuth2TokenServiceImpl](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/oauth2/OAuth2TokenServiceImpl.java#L118) |
| Infra 通用下载 | 源码探针确认 PermitAll、TenantIgnore 和按存储配置/路径获取流，方法没有项目对象校验。私有对象负例在合成代理执行，未请求既有通用入口 | 私有证明和导出应通过受控通道。对目标部署验匿名直连、签名、撤销和审计，修改 Infra 须另行批准。[FileController](../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/controller/admin/file/FileController.java#L103) |

`check-source.py` 的三项源代码断言退出 0，证实观测到的边界，相关生产门槛保持开放。

## 工程回归与 CI

生产源码从获批设计 SHA 导出至 `$r/default`、`$r/iot` 和 `$r/database-source`，实施提交只在根 Reactor 外新增独立工程。临时 IoT 副本打开原 IoT 聚合。各实际命令见 `run-build.sh`、`evidence.sh`、`smoke.sh`、`graph/graph.sh`。主要命令如下。

```bash
# maven_flags 含 -B -ntp -C、settings 与副本专用 Maven 库
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
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s script/tests -p test_sanitation_boundaries.py -v
# 在 database-source 副本内
python3 script/database/validation/run_lab.py --evidence-dir "$r/database" -- \
  -s "$dev/maven-settings.xml" "-Dmaven.repo.local=/workspace/car-weight-eng002c02-regression/m2-default"
```

| 核查 | 实际结果 |
| --- | --- |
| 默认 Reactor | 工具链检查与干净构建均退出 0。44 个项目，54.655 秒。15 个环卫 JAR 均生成且无 class |
| 隔离 IoT Reactor | 退出 0。23 个项目，约 67 秒，含 BOM、Core、Biz、Gateway |
| Gateway 定向测试 | 退出 0。三个 Surefire XML 确认 RabbitMQ Confirm 2、Spool Store 4、Spool Service 17，共 23 项，失败、错误、跳过均为 0 |
| ENG-002B | 17 份有效 POM、17 棵实际 JSON 依赖树。边界实证检查退出 0，独立检查器夹具 23 项通过 |
| ENG-002C | 实验退出 0，45 项隔离检查达到预期，另有 9 项 Spring/JDBC/MyBatis JUnit，失败、错误、跳过均为 0。包含跨库事务拒绝、合成 ACK 及失败迁移历史 |
| 字节码与装配 | 默认 1,149、IoT 1,783、独立授权工程 34 个项目 class 均为 major 61。Server 和 Gateway exec 分别有 258、212 个内嵌库，均无环卫和独立验证工具，Server 包无 Gateway |
| 最小启动 | MySQL 8.4.13、Redis 7.4 和 JDK 21 隔离环境。Server 18.911 秒、Gateway 7.534 秒就绪。Gateway Local 总线，spool 关闭。就绪检查退出 0，主动停止退出 143 |
| 原权限相关 Bean 图 | PropertiesLauncher 只读探针分别运行当前及保留的 ENG-002C Server。各 67 条记录，按 Bean 名称、类型和依赖排序后 `cmp` 退出 0。覆盖认证过滤链、Token 过滤器与服务、登录、权限、TenantIgnore、数据权限、原数据源、事务与 Mapper。证据为抽样装配对照 |

两次 clean install 显式跳过测试。定向测试另行执行。PR #17 对应主分支历史构建显示 Success，工作流跳过测试。[Actions 历史记录](https://github.com/weeleng/car_weight/actions/runs/45770524540)

现有工作流的自动 push 仅覆盖指定主分支，任务分支未执行远程 CI。[工作流](../../.github/workflows/maven.yml)

## 失败、待验收和回滚

- 第一次独立验证执行 35 项。加入独立文件过期、租户类型与能力、排队取消检查并加强 Docker 目标固定、SQL 参数化后，按本报告实际 XML 复验。TenantLine 合成负例保留退出 1 及指定断言失败。ENG-002C 迁移中断和漂移负例保留各自非零退出及失败历史。最终目标测试未出现非预期失败。
- 本轮未运行全仓测试套件、真实 Redis 多节点撤权、真实 RabbitMQ/Quartz 权限执行、生产 RuoYi 会话撤销、正式 MyBatis-Plus 项目 Mapper、对象存储、动态签名和反向代理。未确定生产审计保留、TTL、撤权 SLA 和跨进程事务。Server 冒烟中的 Redis 仅用于旧装配启动。
- DEC-007、DEC-008、GAP-002/003/004、BLOCK-004/005 保持开放。生产实施须另行批准正式表及装配、身份适配、权威版本与撤权目标、历史关系契约、私有对象部署和关键审计。
- 回滚使用反向提交撤销实施及报告，保留原设计。隔离容器、会话临时表和匿名卷均由执行器清理。工作区证据位于 `/workspace/car-weight-eng002d02-regression`。

提交范围、文本空白、Markdown 目标与行号和新增文件常见凭证模式已检查。
