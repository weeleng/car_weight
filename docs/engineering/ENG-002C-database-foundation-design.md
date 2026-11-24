# ENG-002C-01 三逻辑数据库及迁移体系详细设计

目标分支 `master-jdk17`，起始 SHA `ef9fe64c65a60ee2f924a55157bb6f3aab7942ba`，任务分支 `engineering/eng-002c`。基线包含 ENG-002A 和 ENG-002B（PR #15、#16）。本文供人工选择数据库基础设施方案。只读核查和隔离实验使用本基线及已记录的工具链。本阶段提交范围为本文。

依据 [C-01 技术基线](../architecture/standards/technology-baseline.md)、[C-02 时间](../architecture/standards/time-conventions.md)、[C-03 重量](../architecture/standards/weight-conventions.md)、[C-04 L2 ACK](../architecture/standards/iot-ack-and-durability.md)、[C-05 事务](../architecture/standards/transaction-boundaries.md)、[C-06 模块](../architecture/standards/module-boundaries.md)及 [工作包 C](../architecture/reviews/implementation-entry-gates.md)。[ADR-006](../architecture/adr/ADR-006-database-architecture.md)、[ADR-012](../architecture/adr/ADR-012-iot-ingestion.md)、[ADR-014](../architecture/adr/ADR-014-gnss-and-tracking.md)、[ADR-036](../architecture/adr/ADR-036-cache-and-asynchronous-processing.md)、[ADR-037](../architecture/adr/ADR-037-high-availability-and-disaster-recovery.md)均为 Proposed。DEC-006 及相关 DEC 保持开放。

## 现有数据库工程与源码证据

| 项目及定位 | 实际行为 | 与目标的关系、风险及处理建议 |
| --- | --- | --- |
| [BOM](../../yudao-dependencies/pom.xml)、[MyBatis starter POM](../../yudao-framework/yudao-spring-boot-starter-mybatis/pom.xml)、[Server 构件报告](ENG-002B-module-skeleton-report.md) | Server Maven 实际依赖树含 Connector/J **9.7.0**、MyBatis **3.5.19**、MyBatis-Plus **3.5.18.1**、Druid **1.2.28**、dynamic-datasource **4.6.0**；Spring JDBC 6.2.19。驱动版本由 Boot BOM 管理，其他版本也有仓库 BOM 声明。实验连接元数据复核了 Connector/J | 保持版本。Druid 在既有 Server 隔离日志中初始化 `master`，路由器加载两个数据源；不是三逻辑库运行证据。新池选择仍须审批 |
| [Server application](../../yudao-server/src/main/resources/application.yaml)、[local](../../yudao-server/src/main/resources/application-local.yaml)、[dev](../../yudao-server/src/main/resources/application-dev.yaml) | 默认 local，动态源 `primary: master`。示例 master/slave 指向同一 RuoYi Schema，配置 dynamic.druid；未声明 `strict`。4.6.0 属性默认 strict=false。两 Profile 的活动 JDBC URL 使用 `serverTimezone=Asia/Shanghai`，未显式强制会话为 UTC | 对新环卫源使用独立配置、显式目录和失败关闭。保留既有应用行为以便对照。保留旧路由时需专项验证启用 strict 后的兼容性，严禁环卫入口走旧路由回退 |
| [事务基础配置](../../yudao-framework/yudao-spring-boot-starter-mybatis/src/main/java/cn/iocoder/yudao/framework/datasource/config/YudaoDataSourceAutoConfiguration.java)、[OAuth2TokenServiceImpl](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/oauth2/OAuth2TokenServiceImpl.java) | `YudaoDataSourceAutoConfiguration` 启用 CGLIB 事务代理，未声明每逻辑库专属事务管理器。Boot 单候选 DataSource 自动配置会使用 JDBC 本地事务管理器，其对象是活动 DataSource；其创建条件需随新增 bean 验证。现有方法用 `rollbackFor=Exception.class`、显式 `noRollbackFor` 和 `MANDATORY` | 默认管理器与路由器的连接绑定不代表目标 Schema。新域显式绑定同一池、factory 与 manager，保留 legacy 的原异常/传播语义 |
| 4.6.0 路由与 [IoT](../../yudao-module-iot/yudao-module-iot-biz/pom.xml) | `DynamicDataSourceAnnotationInterceptor` 设置/清理线程路由栈，`DynamicDatasourceAopProperties.order` 缺省 `Integer.MIN_VALUE`。IoT Biz 有基于 `@DS` 的 TDengine、IoTDB、QuestDB 组合注解，默认 Server 未装配 IoT Biz | AOP 先于新事务取连接可路由，已绑定连接不因后来的注解更换。自调用、线程切换和未知 key 均需负向案例，不能靠注解名称证明事务正确 |
| [TenantServiceImpl](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/tenant/TenantServiceImpl.java)、[PermissionServiceImpl](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/permission/PermissionServiceImpl.java)及 4.6.0 反编译 | 现有租户/权限方法使用 `@DSTransactional`；IoT Biz 的 `IotProductServiceImpl` 也使用。库的 `ConnectionFactory.notify` 遍历连接，`ConnectionProxy.notify` 对各 JDBC 连接执行 commit 或 rollback，未提供跨库两阶段原子提交保证 | 上游既有用法独立保留。新增环卫源禁止 `@DSTransactional`、XA/Seata 与任何跨逻辑库原子假设 |
| [MyBatis 自动配置](../../yudao-framework/yudao-spring-boot-starter-mybatis/src/main/java/cn/iocoder/yudao/framework/mybatis/config/YudaoMybatisAutoConfiguration.java) | `@MapperScan` 按总包根扫描 `@Mapper`，factory 走 auto-config；`MybatisPlusInterceptor` 含分页、锁定读，还有通用 SQL 注入器及 MetaObjectHandler | 三套 factory 必须显式绑各自 mapper 范围及 XML 资源。必须解决旧扫描覆盖新包、Bean 重名、auto-config 因新增候选退让及插件漏装问题，先验真实 Bean 图 |
| [租户拦截器配置](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/config/YudaoTenantAutoConfiguration.java)、[租户表处理](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/db/TenantDatabaseInterceptor.java)、[数据权限配置](../../yudao-framework/yudao-spring-boot-starter-biz-data-permission/src/main/java/cn/iocoder/yudao/framework/datapermission/config/YudaoDataPermissionAutoConfiguration.java) | 配置启用 tenant。TenantLineInnerInterceptor 和 DataPermissionInterceptor 注入共享 MyBatisPlusInterceptor；前者读取租户上下文，有忽略逻辑和实体元数据判断；后者依已登记规则改写 SQL | 新 factory 按授权配置独立拦截器链、必隔离表 allowlist 及元数据。没有上下文、规则或未登记表须经负向验证。JDBC、系统作业及迁移跨过 Mapper 插件时需显式边界。DEC-008 保留 |
| [基础 DO](../../yudao-framework/yudao-spring-boot-starter-mybatis/src/main/java/cn/iocoder/yudao/framework/mybatis/core/dataobject/BaseDO.java)、[IoT 设备 DO](../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/dal/dataobject/device/IotDeviceDO.java)、[Jackson 配置](../../yudao-framework/yudao-spring-boot-starter-web/src/main/java/cn/iocoder/yudao/framework/jackson/config/YudaoJacksonAutoConfiguration.java)、[上游 SQL](../../sql/mysql/ruoyi-vue-pro.sql) | 常见 `LocalDateTime`，如 create/update 和设备在线/活跃字段。既有 Jackson 为 LocalDateTime 注册 Epoch 毫秒序列化。上游多为 `DATETIME`、部分默认 CURRENT_TIMESTAMP，无统一毫秒/UTC 契约 | 对新环卫绝对时间采用单独 mapper/DTO 类型策略。不得据类型断言旧数据实际时区，不全局替换上游字段、处理器或 Jackson |
| [主 SQL](../../sql/mysql/ruoyi-vue-pro.sql)、[Quartz SQL](../../sql/mysql/quartz.sql)、[Compose](../../script/docker-compose/docker-compose.yml) | MySQL 8.4 入口在空数据卷按文件名初始化主 SQL 和 Quartz SQL，旧卷不重新应用。主 SQL 含 DROP/CREATE 和初始数据。Compose 的 MYSQL_USER 向 RuoYi Schema 供应用连接，master/slave 可用同一注入账号；还有独立初始化管理凭据 | 空库引导与版本升级分开。三库需要独立迁移和按表权限，入口脚本不可作为已有卷升级机制。不在设计文档记录配置值或密码 |
| 迁移工具扫描 | Server 实际依赖树无 Flyway 或 Liquibase。仓库另有 DM Flowable Liquibase 补丁和隔离测试 POM，默认 Server 未装配 BPM。Boot BOM 管理相应候选版本；这与运行启用有别 | 不沿用 DM 补丁。候选工具只在独立迁移步骤或独立验证工具中引入，避免 Boot 启动隐式迁移 |
| [Server POM](../../yudao-server/pom.xml)、[Gateway POM](../../yudao-module-iot/yudao-module-iot-gateway/pom.xml)、[RPC 客户端](../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/remote/IotDeviceApiImpl.java)、[Gateway 分析](iot-gateway-analysis.md) | Server 装配 System/Infra，依上述 JDBC 栈。独立 Gateway 通过共享 IoT 契约和 RPC/消息接入业务，源码有 SQLite JdbcTemplate/单连接 spool，未在当前装配接入三库 | Gateway 不用 BUSINESS/TRACK 凭据或正式 Mapper；未来 L2 ACK 以批准的 INGEST 服务提交证据为条件，SQLite/RabbitMQ 不替代此边界。BLOCK-001 保持开放 |

已阅读 [ENG-002A 报告](ENG-002A-toolchain-alignment-report.md)、[ENG-002B 设计](ENG-002B-module-skeleton-design.md)、[骨架 README](../../sanitation-modules/README.md)、[验收矩阵](validation-matrix.md)、[开放决策](../architecture/reviews/open-decision-register.md)、[批准卡](../architecture/reviews/decision-approval-packets.md)、[差距登记](../architecture/reviews/implementation-gap-register.md)与[生产门槛](../architecture/reviews/production-gate-approval-matrix.md)。

## 三逻辑库与 RuoYi 归属

| 逻辑库 | 候选 Schema | 职责及禁止事项 |
| --- | --- | --- |
| INGEST | `sanitation_ingest` | 接入原始事件、存证、同事务 Outbox 和接入幂等证据。保留原始载荷，拒绝由审核、计费或主体归属改写 |
| BUSINESS | `sanitation_business` | 正式称重、清运、运输、审核、计费、Inbox 与必要业务 Outbox。具体领域对象及持有者待 DEC-011 |
| TRACK | `sanitation_track` | 轨迹侧 GNSS 原始点、车辆轨迹、最新有效位置、空间证据。组合接入消息中的原始载荷仍由 INGEST 保存，TRACK 独立持久化可追溯的原始点和后续投影。坐标、SRID、来源、迟到更新及保留待 DEC-009。不向 INGEST 回写称重事实 |

| 比较 | A，原 RuoYi + 3 环卫 Schema | B，RuoYi 现有 Schema 归入 BUSINESS + INGEST/TRACK |
| --- | --- | --- |
| 侵入性与连接池 | 保留 legacy 数据源和原初始化，新增三套显式环卫池。四个 Schema、三个环卫逻辑库 | 物理三 Schema，BUSINESS 内共享 RuoYi 表与环卫表。可保留独立角色，但存在共享资源和默认池误用 |
| 租户/权限与事务 | 复用上游公开授权接口。历史授权快照、幂等与正式业务留在所属逻辑库，各本地事务独立 | 查询表物理方便，容易绕过项目授权、内部 Mapper 和 C-06。需要表级授权、扫描与事务边界隔离，避免共享事务成为默认 |
| 迁移与上游升级 | 三套 Flyway 历史与上游空库 SQL 分开，RuoYi 升级由原流程负责 | 需要同 Schema 表所有权、前缀、迁移触达许可和既有数据审计；上游版本覆盖风险更高 |
| 账号、备份及恢复 | 四个保护对象可分别授予角色，逐 Schema 恢复，再做跨边界对账 | 共享 BUSINESS 恢复会同时影响 RuoYi 账号、租户与环卫事实，进一步拆分要重做权限、迁移及恢复 |
| SaaS、私有化和后续拆分 | 初期可共实例，按负载或运维要求移动任一环卫 Schema。新增运维对象，但边界清晰 | 初期物理管理对象少；后续独立扩容或隔离时要拆共享 Schema 和相关调用 |

**建议 A**。保留现有 RuoYi 数据库、表和数据，新增三个环卫 Schema，基础部署总计四个 Schema。用户、部门、租户等通过公开接口或受授权的数据契约读取，只保存稳定引用及需要的审计快照。禁止直接访问上游 Mapper/DO、复制或迁移上游基础表、跨逻辑库或跨 RuoYi 边界外键。命名待批准。

### 物理部署

| 形态 | 取舍与运维 |
| --- | --- |
| 初期建议同一个 MySQL 8.4 实例，不同 Schema/用户/池 | 成本低、连接路径简单，同实例可保留一致快照。共享 CPU、内存、buffer pool、IO、磁盘、binlog 和主节点故障域。每个池有独立预算与超时，总数按 Server 实例数 × 池配额估算，另给迁移、备份及管理预留。衡量 TRACK 峰值与 INGEST 写负载对业务的影响 |
| 各逻辑库可分别落独立 MySQL 8.4 实例或集群 | 可独立扩容、减小故障及权限影响面。连接、复制、监控、网络和备份运维开销更高。恢复点可能分离，需跨库对账、补写及重放，集群 SLA 由故障域实测确定 |

以每逻辑库独立 JDBC URL、Schema、权限角色及事务工厂实现物理解耦，SQL 与业务契约只识别逻辑库。域间通过 API 或事件相连。RPO/RTO、连接池上限及实例规格由 DEC-016 定版。共享实例自身为单一故障域。

## 数据源、Mapper 和事务

| 方案 | 优势 | 失效模式及代价 |
| --- | --- | --- |
| 复用 dynamic-datasource，环卫 Mapper 使用 `@DS` | 延用路由器、Druid 和已有 MyBatis 自动配置。工厂和配置较少 | AOP 先后顺序、默认 key、线程上下文、自调用及已绑定连接决定真实目标；即使 strict 也无法替换已绑定连接。全局 mapper 扫描与插件共享，容易误路由，业务审计成本较高 |
| 为三逻辑库显式 DataSource、SqlSessionFactory/Template 和命名 TransactionManager | 从 mapper/连接到本地事务均可审计，支持逐库权限、验证、配置变更和失败关闭。保留 RuoYi 原主源 | 额外池及 factory、类型处理器和拦截器装配。新增 bean 可能改变 Boot/MyBatis 自动配置选择，需完整对照原 RuoYi Bean 图及框架插件行为 |
| 独立 JdbcTemplate + 显式 manager 作为验证阶段实现 | 用最少组件复验 JDBC、命名 manager 与权限，便于保留 Server 配置 | 仅覆盖基础设施。后续业务 Mapper、租户和数据权限仍须使用显式工厂方案或另经批准 |

**建议显式方案**。RuoYi 保留自身动态源和事务，环卫三个 factory 通过具体对象引用关联唯一 DataSource；各 mapper、XML、`SqlSessionTemplate` 和命名本地 `JdbcTransactionManager` 对应同一池。业务入口显式选 manager（候选 `ingestTxManager`、`businessTxManager`、`trackTxManager`）或使用绑定的 `TransactionTemplate`，不靠 `@Primary` 或当前线程猜库。候选沿用现有 Druid 1.2.28，分别设置连接容量、超时及关闭策略。通过池连接校验预期 catalog 与会话设置，缺失或不符则阻止该功能就绪。故意放错 URL 的测试必须触发拒绝并证明没有 legacy 写入。对遗留 dynamic 路由启用 strict 需要先清点现有 key/调用并独立回归；环卫任何路径上都禁止未知 key 回退。

实施时显式保留 legacy 的 DataSource、SqlSessionFactory、SqlSessionTemplate 和默认 manager 的限定/主候选语义。先检查 `@ConditionalOnMissingBean` 与 `@ConditionalOnSingleCandidate` 的生效结果，必要时单独配置 legacy 等效 bean。拆分上游广域 `@MapperScan` 与环卫标记/包的扫描范围（不重叠），每个 mapper 至多装配一次。新 factory 需显式设置 Mapper XML、MyBatis-Plus 基础注入器/填充策略、type handlers、方言、分页、锁定读、租户与数据权限链；每个 session factory 使用隔离插件实例并固定顺序。不能因缺少表元数据、规则或上下文而无声忽略安全条件。INGEST/TRACK 的设备和数据归属规则必须明示；运维账号及不含租户的基础设施表使用经审核、逐表例外，禁止全局关闭。正式生产装配另行授权。

- 代理入口先验证租户及逻辑库上下文，再启事务。同库 `REQUIRED` 可加入已知相同 manager，方法内 checked 异常配置回滚，不能吞掉异常后当成功。谨慎使用同库 `REQUIRES_NEW`，例如批准的独立运维事务，不能用于拆开必原子的两次写入。
- 检查已有事务归属，拒绝在某逻辑库事务内启动另一库写事务或切换 Mapper。命名 manager 本身不禁止嵌套不同 manager，入口守卫、同库资源绑定和静态边界检查共同执行。跨库置于事务之外，以提交后的事件和后续独立本地事务衔接。
- 自调用不经过代理，事务入口由独立 Bean 或显式模板执行。异步/MQ/Quartz 不能继承上一个线程的路由或租户状态；从可信信封/作业身份重建、校验、处理，`finally` 清理。禁止从非可信事件按任意库名选池，租户缺失拒绝或隔离。DEC-008 决定项目授权、缓存和异步上下文，DEC-015 决定审计保留与防篡改取证。
- 负向验证覆盖先开 master 事务再调用 INGEST、同 Bean 自调用、未注册名称/缺失 DataSource、注入错误 catalog、同线程异步重用、错误 mapper / transaction manager、跨库调用和没有 tenant 过滤的方法。

## 模块与数据库访问矩阵（候选）

每行的本地表权限仅随该域既定用例申请。拥有一个 Schema 不意味着同库业务模块可以互读表。

| 领域 | 拟直接访问及所有权 | 公开 API / 事件 | 禁止及待决 |
| --- | --- | --- | --- |
| common | 无 | 共享值类型 | 禁止 JDBC、Mapper、仓库及跨库连接 |
| base | BUSINESS 基础档案候选，限本域记录 | 调用上游用户/部门/租户公开 API，提供已批准基础契约 | 不读 RuoYi 内部表或 Mapper，数据归属与历史绑定待批 |
| device | INGEST 接入、原始事件及其同事务记录的候选入口；设备业务档案另审 | 提供接入提交结果，发布可追溯事件 | 不访问计费/审核表；装配位置、身份和归属待 DEC-001～003、011 |
| gis | TRACK 持有定位、轨迹、最新有效点候选 | 订阅定位输入和公开车辆关联，输出授权轨迹读模型 | 不改原始称重；坐标/SRID/迟到规则 DEC-009 |
| operation | BUSINESS 作业、称重、审核及批次候选 | 订阅接入事件，读取经批准的历史关联接口，发布领域事实 | 不读原始库或计费表；所有权、待匹配、状态机 DEC-011 |
| billing | BUSINESS 条件计费、账单的本域数据候选 | 消费正式业务事实，按公开规则/接口取得输入 | 禁止 INGEST 原始记录直接计费；账单和计费规则待 DEC-011/012 |
| event | 通过所属域的本地技术适配器支持 INGEST、BUSINESS 的 Outbox/Inbox，需分别装配 | event-api 只放通用技术封装。每个本地写操作由业务宿主的 manager 管理；Relay 按库领取已提交记录 | 无三库通用连接或任意 Mapper 访问。角色、路由、重试、发布消费边界由 ENG-002E 与 DEC-011 审批 |
| analysis | 读授权读模型，必要时维护获批的独立投影，所属库待定 | 公开读契约或版本化事件 | 禁跨库 JOIN 及直接扫业务私表；报表口径 DEC-014 |
| integration | 维护获批外联状态的 BUSINESS 本域候选记录 | 只用公开 API 与事件发送/接收，稳定幂等键及对账 | 不持有全库连接，不在 INGEST 或 BUSINESS 本地事务内同步等待远端业务 |
| 独立 Gateway | 当前不新增三库凭据，无直接 Mapper 权限 | 未来调用独立批准的接入契约取得 INGEST 持久提交证据 | 无 RuoYi/BUSINESS/TRACK 直连，禁止业务 Biz 依赖。BLOCK-001 保持开放 |

## 数据库账号与最小权限

账号和 role 名为建议，使用环境密钥或受控密钥服务注入，按部署单独发放、轮换、审计和撤销。连接来源受网络及 TLS 策略约束。池角色没有默认全局授权。

| 账号/角色 | 范围及最小能力 | 限制 |
| --- | --- | --- |
| 初始化管理角色 | 受控初始化时创建 Schema/账户/角色、赋予该 Schema 对象权限；平台按需短时授权 | 应用无该凭据。初始化完成后撤销临时权限，不提供全局 ALL，管理权限由平台单独审核 |
| INGEST 迁移 | `sanitation_ingest`，允许历史表与获批脚本所需的 CREATE/ALTER/INDEX 与 DML，特定删除或 DROP 按次授予 | 无其他 Schema、无授权转授。迁移账号退出应用运行池 |
| BUSINESS 迁移 | 同上限 BUSINESS，具备版本历史必要的 DML | 对 RuoYi 表和其他 Schema 无权 |
| TRACK 迁移 | 同上限 TRACK，空间 DDL、分区按另行批准 | 无跨库 FK、无 RuoYi 或事实源写入权 |
| INGEST 接入运行 | 原始记录 INSERT 和必要的幂等查询 SELECT；同事务创建对应 Outbox 的 INSERT，最小查询确认事实 | 无原始 UPDATE/DELETE，无 DDL。修正只产生可追溯的后续记录。合规清理工作另设按次授权、审计和保留规则 |
| BUSINESS 运行 | 按本域表给业务所需 SELECT/INSERT/UPDATE，Inbox 唯一键与必要 Outbox INSERT | 默认无 DDL、无 RuoYi/其他库；永久删除逐业务批准 |
| TRACK 运行 | 按表 INSERT/SELECT，最新点投影可在批准条件下 UPDATE，轨迹保留/清理独立账号 | 默认无 DDL，无 INGEST/BUSINESS，迟到点不能无条件改写有效最新点 |
| Relay / 投影辅助 | 按所属库和专用表授予最小 SELECT/UPDATE 或必要 INSERT | Relay 不获原始更新/删除或其他 Schema 凭据，最终模型待 ENG-002E |
| RuoYi 运行 | 继续现有 Schema，未来独立收敛权限 | 无环卫 Schema，禁止 root 运行应用 |
| 备份和恢复 | 备份读角色只读及所选快照/binlog 工具确需的权限。恢复角色限目标隔离实例/Schema、短时受审 DDL/DML 和恢复凭据 | 无永久共享写角色。生产恢复单独批准，工具的 RELOAD、PROCESS、REPLICATION CLIENT、LOCK TABLES 等逐项验证，不预授 |
| 审计只读 | 最小审计视图/表的 SELECT，按用途脱敏，访问留痕 | 无业务写或 DDL，表级授权无法替代项目行级授权 |

同一 JVM 持有多个池的凭据时，数据库 grant 隔离不了该 JVM 进程失守或错误 Bean 注入，仍要通过模块依赖、具名工厂、同库事务校验、独立凭据治理和日志稽核防误用。测试逐项验证三库运行账号允许本域和拒绝跨 Schema、DDL、原始 UPDATE/DELETE；用有权限的隔离管理角色证明跨 Schema FK 即便在同实例技术可行也由迁移检查拦截（查询 `information_schema.KEY_COLUMN_USAGE`），仅依 grant 不能替代结构检查。

## 版本化迁移

| 方案 | 适配性及成本 |
| --- | --- |
| 现有手工 SQL | 可保留 RuoYi 空库引导，操作简单。缺少统一版本、校验和、并发锁及可审计升级；对已有库运行含 DROP 的脚本有数据风险 |
| Flyway | SQL 原生、每 Schema 独立历史表与校验和，支持版本/顺序验证。开源 Java 方案需要 `org.flywaydb:flyway-core`、同版本 `flyway-mysql` 和 Connector/J 9.7.0。Boot 3.5.16 BOM 管理 11.7.2，隔离实测通过基础操作但出现 MySQL 8.4 未测试支持警告。11.20.3 用相同 JDBC 驱动复验通过且未出现该警告，生产支持矩阵与该版本的更完整回归仍待核验 |
| Liquibase | XML/YAML/SQL changeLog、历史与锁表，跨数据库抽象可选。Boot BOM 管理 4.31.1；`liquibase-core` 加 Connector/J，兼容性和回滚仍需真实 MySQL 8.4 验证，XML/抽象及自定义规则的维护面更大。历史 DM 补丁不作为本项目选型依据 |

建议在 **独立迁移 runner** 先提审 Flyway **11.20.3 候选**。Java 21 运行 runner，Boot 3.5.16 应用 classpath 不改变，Connector/J 沿用 9.7.0。独立工具固定依赖、发版制品和校验和，校验 Flyway Core/MySQL 同版本，任何纳入正式构建或更新 BOM 的请求另审。发行父 POM 将两个 Flyway OSS 构件标为 Apache-2.0，Liquibase 4.31.1 POM 同样声明 Apache-2.0。正式交付查 SBOM、许可证/NOTICE、Connector/J 再分发义务、版本支持条款和是否误用专有扩展。团队版 dry-run、Undo、商业连接器或支持 SLA 无隐式授权，也不是基础方案依赖。 [Flyway 父 POM](https://repo.maven.apache.org/maven2/org/flywaydb/flyway-parent/11.20.3/flyway-parent-11.20.3.pom) · [Liquibase 构件元数据](https://repo.maven.apache.org/maven2/org/liquibase/liquibase-core/4.31.1/liquibase-core-4.31.1.pom)

独立迁移步骤适用于开发、测试和生产。开发者显式运行隔离实例上的命令，CI 使用临时空库和 N-1 仓储验证，生产由获批准的单次部署任务串行调用，使用独立迁移凭据并限制网络。应用启动自动迁移缩短开发步骤，却要求运行身份持有 DDL 或路由迁移凭据，多实例锁竞争及启动失败影响可用性，默认应用流程不采用。应用就绪检查只校验自身所需 Schema 版本和兼容窗口，运行账号无需修改历史。

- 建议版本目录置于仓库顶层，由独立 runner 显式配置 filesystem 路径 `db/migration/ingest/`、`db/migration/business/`、`db/migration/track/`。每次调用只接受对应 URL/schema/角色/location，对预期 catalog 先校验。分别设置 defaultSchema/schemas 白名单，各 Schema 内保留自己的 `flyway_schema_history`。初始化账号负责创建空 Schema，生产 runner 不动态建 Schema。
- 建议每逻辑库单调整数 `V0001__create_<subject>.sql`，ASCII 小写与下划线，版本在该库唯一。三库编号独立，发布清单记录每个目标版本、适用应用版本、部署顺序、依赖和原始 Git SHA。并行开发冲突在分支合并前重编号，仅限未执行脚本。默认禁止 outOfOrder；已发布文本（含标准编码与换行）、校验和和历史不可原地改写。使用 CI 针对三套目录检索重复版本，并实际 `validate`。
- 对空库先初始化账户再 `validate → migrate → validate`，N-1 升级用受控前一版本快照。重复运行不重跑已成功版本，新版本应用数应为 0。部署任务每 Schema 至多一个，另验证 Flyway 数据库锁、等待和超时行为。禁止启用 clean、自动 baseline、自动 repair 或通过占位脚本绕过失败。
- RuoYi 现有 SQL 只在授权的新空 Schema 初始化。若将来需要为已有上游库建立 baseline，另提审批并确认实际表、索引、字符集、租户/权限、数据统计、初始数据差异和归档 SHA。必须用备份副本证明选择的基线版本能够正确升级，经审核后手动 baseline；不得盲标最新版。
- 可按独立单语句 DDL 或显式数据批次拆分迁移。MySQL 8.4 多类 DDL 有隐式提交，InnoDB 支持原子 DDL 的单语句崩溃回滚不提供多语句或同脚本 DML 的整体回滚。建表、变更和回填分阶段；锁时长、表大小、可用 ALTER 算法、binlog 积压及超时先在代表性副本试验。
- 扩展/兼容优先（新增可空列/对象 → 验证旧版可读写 → 新代码双兼容与可审计回填 → 切读/写 → 对账 → 另批收缩）。故障时停止后续迁移和发布，保留日志及历史，核查结构与数据实际落点。以审查过的前向修复/补偿或恢复到验证备份处理；未有结构复核和批准前不手改历史。Flyway repair 只调整历史记录和校验和，无结构恢复作用。破坏性 DDL 须独立审批、预计停机/锁评估、实际恢复演练及复核，应用镜像回滚不恢复数据库。

## 时间、重量、标识及存储

### UTC 和 JDBC

| 时间语义 | 使用方式 |
| --- | --- |
| 原始设备时间、设备事件时间 | 接入保留原始 Epoch 毫秒和质量/有效性。业务绝对时间以 `Instant` 表达，事件 Epoch 毫秒与身份 Epoch 分别记录。不可信时间保留并进入核验 |
| 服务端接收时间、持久化记录时间 | 接收入口打点；记录时间可在入库时按 UTC 明确设置。二者均为 `Instant` 语义和毫秒精度 |
| 实际提交成功时点 | 以本地事务成功返回或正确配置的事务同步回调为可靠性边界。存储在事务内的时钟字段只表示记录时间，不作为提交成功证据。若需提交时间遥测，单独打点且不替代持久化 |
| 审核、计费及展示 | 审核与计费事件各自为绝对时间；项目账期按明示业务时区划分。API 输出 ISO 8601 带 `Z` 或偏移量，显示默认 Asia/Shanghai |

每个新池明确候选 `connectionTimeZone=UTC`、`forceConnectionTimeZoneToSession=true`、`preserveInstants=true`，具体组合及连接池会话初始化/复用行为再验。通过 `SELECT @@session.time_zone`、连接借还和新连接检测确认，配置注释不算实证。TLS 与密钥另按环境约束，隔离实验的禁用 TLS 参数不用于生产。现有 `serverTimezone` 是连接时区属性的别名，单设属性不等同于将 MySQL 会话设 UTC。

绝对时间列使用 `DATETIME(3)`，按约定存 UTC 日历值，该类型不附带时区。候选 type handler 在写入时以 `LocalDateTime.ofInstant(instant, ZoneOffset.UTC)` 经 JDBC `setObject`，读 `getObject(..., LocalDateTime.class)` 后显式 `toInstant(UTC)`。应用生成时间规范到毫秒，设备原始整数毫秒原样保留，亚毫秒处理及合法范围在详细设计注明。避免用 JVM 默认区或隐式 `Timestamp` 转换实现。若使用数据库默认时间，须明确验证 UTC 的生成行为与毫秒精度。API 使用新响应 DTO 的显式 ISO 转换，复验共享 Jackson 设置。覆盖 UTC 与 Asia/Shanghai JVM、会话 +00:00/+08:00 的往返、日期边界、毫秒与空值，并单测 MyBatis 映射及 API 序列化；旧 RuoYi 时间行为独立保留。

### 重量

协议的有符号 INT32 克范围为 `-2147483648…2147483647`，校验协议解码；业务 `BIGINT` 有符号克，范围 `-9223372036854775808…9223372036854775807`。正式值 `DECIMAL(12,3)` 千克有 9 位整数与 3 位小数，范围 `-999999999.999…999999999.999` 千克，即 `±999999999999` 克。业务重量可超出正式类型。先比较有符号克上下界（避免 `abs(Long.MIN_VALUE)`），用 `BigDecimal.valueOf(grams, 3)` 精确缩放及 JDBC Decimal 映射，超范围显式拒绝正式写入/进入经批准的异常流程并保留原值。禁止截断、饱和或浮点代替正式重量。负值处置、合法量程、舍入与计费仍待专项，不通过类型设计定版。

### 标识、字符集与引擎

- 主键可沿用应用生成或库内自增方案，评审吞吐、唯一范围、迁移和不透明性后选择。租户和项目 ID 为授权数据及跨库引用，不跟随客户端声明直接信任。跨库键记录源/租户语义，应用核对类型和有效期并对账，无跨库 FK。
- 原始身份候选为硬件 UID + Epoch + Seq，二进制原值尽量用 VARBINARY/固定长度 BINARY（长度和填充语义先核验）按字节比较，保留前导零。消息 ID 与设备身份不同，在各库配合稳定业务幂等键及必要唯一约束。全键字段长度、大小写、回绕、密钥撤销历史接收与相同身份异载荷待 DEC-001～003；本轮无最终唯一键 DDL。异载荷原始记录保护和人工核验留后续专项。
- 三库建议 InnoDB、utf8mb4。人读文本候选 `utf8mb4_0900_ai_ci`，按业务决定大小写/重音搜索；代码和标识应明确二进制或区分大小写的排序规则。校验大小写、尾空格、规范化和索引长键，避免在上游现有 `utf8mb4_unicode_ci` 表之间隐式拼接/跨库比较。无需修改上游字符集和排序规则。
- 验证目标 SQL 模式至少含严格模式（如 `STRICT_TRANS_TABLES`）与 `NO_ENGINE_SUBSTITUTION`，同时评估零日期、聚合和回滚行为。禁用静默截断，校验 JDBC 警告或错误。明确 MySQL `lower_case_table_names` 和环境初始化差异，全平台采用一致的小写 Schema/表命名。

## 本地可靠事务约束

- **INGEST。** 原始事实与对应 Outbox 用同一池、同一名义事务管理器及同一个本地事务写入。失败全回滚，无成功 L2 ACK。提交后才能生成成功 L2 ACK。丢失线上 ACK 后，须依据稳定身份、同载荷和已提交证据幂等重发，异载荷保留冲突，不改历史。终端缓冲、SQLite、Redis、RabbitMQ Publisher Confirm 均不替代接入提交。
- **BUSINESS。** Inbox、正式业务及必要 Outbox 在同一个本地事务提交。消费者在事务真正成功后才 Consumer ACK。至少一次投递，使用业务幂等键和数据库唯一约束；对提交结果不确定的分支，以可核验证据和重试规则处理。
- **TRACK。** 单独池和本地事务，位置补建走批准契约或事件。不依赖与 INGEST/BUSINESS 同步原子提交。校验最新有效点规则与恢复由 DEC-009/016 评审。
- 正式 Outbox/Inbox 表、消息状态机、Relay、重试、死信、受控重放等留待 ENG-002E。数据库基础设施验收证明同库连接、事务和权限行为，不发布或确认真实消息。

## 备份、协调恢复与高可用

| 方式 | 一致性及恢复要求 |
| --- | --- |
| 同实例全量 | 对 InnoDB 使用经过验证的一致快照，记录起点 GTID/binlog、版本、三库历史与 RuoYi 同步边界。快照期间控制 DDL，备份分离于实例故障域，加密并定期在隔离实例还原 |
| 分 Schema 备份 | 缩小单库恢复范围及访问权限，分别保留 schema history、对象和数据统计。以不同连接分时备份会产生恢复点差，记录时间线及后续对账水位 |
| Binlog/PITR | 全量备份加连续、可校验的 binlog/GTID 序列，恢复到经授权时点。核对目的 Schema 筛选、DDL、过滤策略和跨库输入，对已坏事件隔离处理；不将跨 Schema 版本简单对齐到相同整数 |
| 独立实例备份 | 独立备份、密钥、保留和恢复窗口。发布点记录各实例的可比较业务水位、备份点、已提交/待发/已处理标识。无法依单一 GTID 表达跨实例一致恢复 |
| 协调恢复后的对账 | 先暂停写及事件转发，分库还原并核验，保留已提交未确认事件，按稳定身份对比 INGEST、Outbox、BUSINESS Inbox/正式称重及财务副作用。在授权下受控重放，先查外部结果再决定重试。TRACK 的重建/保留/迟到走 DEC-009/016 |

服务器本地事务提交与跨节点持久化有独立证明条件。单实例故障域可能同时影响四个 Schema，独立实例也需实际复制和隔离演练。已提交而未获设备确认的事件可能发生重传，须保留事件身份与恢复水位，不能仅以发送过 ACK 的记录作为核验基准。安全审计依据 DEC-015；保护范围、ACK 阶段分母、TRACK 级别、RPO/RTO 均由 DEC-016 审批，BLOCK-007 尚未关闭。

## ENG-002C-02 最小实施提案

按批准版本推进配置模板、按范围的初始化/权限、三套版本目录、独立迁移 runner、显式 DataSource 与本地事务验证；优先用临时 JVM 配置或隔离测试 profile 验证计划组装，记录老 RuoYi Bean 图与路由不变。生产/正式 Server 装配的任何变更需列明其必要性并取得相应授权。合成表在临时实例创建，以 `probe_` 命名、与正式迁移目录分离，在验收结束后销毁。不得生成正式称重、车辆、垃圾桶、轨迹或 Outbox/Inbox 业务表。

| 位置（拟新增） | 拟实施内容 |
| --- | --- |
| `db/migration/{ingest,business,track}/` | 每库说明和已批准的基础迁移，空目录可用 README 保留。合成测试 SQL 存放在隔离夹具或临时目录 |
| `script/database/bootstrap/` | 环境参数化的 Schema/角色初始化及权限最小化脚本，明确隔离环境保护，无生产凭据 |
| `script/database/migration-runner/` | 非默认 Reactor 的独立工具、版本锁定、运行文档与验证入口，显式 validate/migrate 单库选项 |
| `script/database/validation/` | MySQL 8.4 隔离环境、配置模板、具名连接工厂/manager 验证夹具、时间/重量/权限/迁移回归 |
| `docs/engineering/ENG-002C-database-foundation-report.md` | 阶段二批准证据、实际命令/退出码、范围、迁移恢复举证和剩余风险 |

ENG-002B 的[依赖检查器](../../script/shell/check-sanitation-boundaries.py)固定默认 44 项和隔离可见 48 项，环卫 JAR 只有六条、自身 API compile 边，无管理以外的新外部依赖。上述独立工具不进入根 Reactor，不装配 Server/Gateway，不改变白名单。若后续批准正式环卫数据访问，最小的规则调整候选仅限经批准的**本域 Biz**使用现有 MyBatis starter 或单独批准的基础设施适配，逐个审查 Connector/J、Druid 和传递依赖，按实际 Maven 坐标、作用域和消费者缩小允许面。禁止 API/common 引入持久化，禁止 Gateway/Server 任意新增环卫运行边。若批准增加根 Reactor 项目或 Server 装配，检查器清单、项目数、实际树白名单、BOOT-INF 断言必须同步更新，增加本域合法、API 持久化、未经准入运行边及 Bridge 隐藏依赖的正负夹具。每一项策略调整需与功能审批绑定，现有检查不得关闭或绕过。

### 阶段二验收矩阵（待授权执行）

| 类别 | 操作及断言 | 证据 |
| --- | --- | --- |
| 环境、连接与独立部署 | `SELECT VERSION(), DATABASE(), @@session.time_zone` 核对 MySQL 8.4、三目录、三个角色与各池。重复同样测试，把其中一库改为独立 MySQL 容器，仅调整对应 URL，另一库销毁时跨库误回退不得发生 | 实例、连接元数据、Schema/角色清单及错误原因；容器均隔离 |
| 账号与结构 | 用迁移账号完成本库操作，运行账号拒绝 DDL、跨库访问、改删原始事实。批准的只读账号仅具 SELECT。作跨库 FK 夹具验证扫描规则和 CI 拒绝 | GRANTS 和预期错误码、`information_schema.KEY_COLUMN_USAGE` 前后差异 |
| 迁移 | 每库独立空库初始化并二次迁移（0 待执行），从 N-1 至 N；修改已执行文件校验失败；同库两个进程并发演练锁/超时，模拟中断与非原子多语句失败，验证结构真实状态和恢复/前修；校验三套历史、版本冲突和迁移/运行账号隔离 | migrate/validate 日志、版本与校验和、历史表、DDL 残留及恢复前后结构/数据。锁与失败证据独立留档 |
| INGEST 本地事务 | 合成原始和模拟 Outbox 同池、同连接完成，任一步骤主动抛错均无半条结果。并发重复键仅允许一条合成事实，无重复副作用。记录提交返回和可发送 ACK 的模拟信号的先后 | 查询前后行计数、并发结果、已提交证据。模拟 ACK 只用于边界断言 |
| BUSINESS 本地事务 | 合成 Inbox、业务事实及必要模拟业务 Outbox 成功提交。任一步骤失败回滚，重复消费不重复业务结果。模拟 Consumer ACK 仅在提交成功后产生 | 本地事务日志、唯一约束、行数与 ACK 模拟事件 |
| TRACK 和误路由 | TRACK 单独事务，误传 catalog、未配置源、错误 manager、已绑定 legacy/其他库、跨逻辑库事务、自调用、线程复用和缺租户全部负向验证。硬性禁止跨库 FK，原始事实保持不变 | 具名 Bean 图、连接标识、失败前后各库行数、源与目标的负例 |
| 时间 | UTC/Asia/Shanghai JVM 与 +00:00/+08:00 会话组合，真实 MySQL/JDBC/ORM 往返，毫秒、日期正负偏移、空值和越界；业务 API ISO 8601 带偏移，业务展示时区独立 | 会话变量、实际存储值、Instant 相等断言、序列化金样 |
| 重量 | INT32 下/上界、零值、正/负号；BIGINT 上下界；DECIMAL(12,3) 正负边界、精确克/千克，超范围拒绝并保留原始，禁浮点 | 对照断言与严格模式 SQL 错误，计费未纳入 |
| 工程回归 | 现有工具链脚本、默认 44 项干净 Reactor、major 61 及 release 17、隔离 MySQL/Redis Server 最小启动、隔离 IoT Core/Biz/Gateway 与 exec、Gateway 三类定向测试及 ENG-002B 检查器真实解析树 | 构建命令、退出码、Surefire XML、JAR/源码范围、边界审计、就绪日志 |

真实 RabbitMQ 可靠消息、实体设备 L2 ACK、端到端账务对账、生产拓扑及恢复目标另立验收。本轮和模拟测试均不关闭 BLOCK-003、BLOCK-007 或 BLOCK-001。

## 本轮实际取证和实验

- `git fetch origin master-jdk17`、`git rev-parse origin/master-jdk17`、`git show -s --format='%H %P %s'` 核对 SHA 与 PR #16 合入，退出 0。通过 `rg` 检索配置、Java、POM、SQL；解析 ENG-002B 留存的 Maven JSON 依赖树及已验证 Server 启动日志；用 Temurin 21 的 `javap -c -p` 检查 dynamic-datasource 4.6.0 的属性、advisor 和连接通知。Flyway/Liquibase POM 版本和许可证元数据用 Maven Central POM 核对。
- `docker run -d --name eng002c01-probe --publish 127.0.0.1::3306 --env-file "$lab/mysql.env" mysql:8.4` 创建独立实例，动态宿主端口仅监听回环。试验账号及非业务 Schema 通过 `docker exec -i` 创建。没有生产地址或数据。完成后 `docker rm -fv eng002c01-probe` 退出 0，删除临时凭据，日志和临时文件未提交。
- 隔离 Java 探针用缓存的 Spring JDBC 6.2.19、dynamic-datasource 4.6.0、Connector/J 9.7.0。实际 `javac -proc:none --release 17`、`java -Duser.timezone=Asia/Shanghai ... Probe` 和 UTC 变体。两次最终退出 0，每种 JVM 时区各 **14 条通过**。新事务按 @DS 选 INGEST，已绑定 master 的外层 REQUIRED 继续用 master，自调用走主库，strict 未知路由拒绝。显式单源 `JdbcTransactionManager` 验证两对合成表同时提交、第二写重复键时回滚第一写。SELECT 跨库、CREATE、原始表 UPDATE/DELETE 均拒绝（1142）。DATETIME(3)/BIGINT/Decimal 在两个会话时区往返，严格 Decimal 溢出错误 1264。DDL 实验显示前置 DML 隐式提交。前两轮探针各退出 1，分别为夹具漏加 MyBatis 类路径，以及手动 advisor 缺少基础设施 Bean role 修饰。纠正临时夹具后复验，上游源码未修改。该探针复制 AOP 顺序和事务绑定机制，实际 Server 多厂装配仍待验证。
- 独立临时 Maven POM 使用 `mvn -B -ntp -C -s <既有设置> -Dmaven.repo.local=<临时缓存> org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies`，以同版本 Flyway Core/MySQL 和 Connector/J 编译并执行两组 `FlywayProbe`。对 MySQL 8.4.13 的两个不同合成 Schema，两版本均退出 0，验证空库、重复 0、N-1→N、校验及拒绝校验和漂移。11.7.2 多次提示 MySQL 8.4 超出已测试支持范围；11.20.3 同流程无该警告。此结果限定在 OSS Java API 小样，不代替生产支持、锁并发与失败恢复验证。
- 本轮 `git diff --check`、`git diff --cached --check` 和静态验收均退出 0。静态验收核对仅新增本文件、14 个设计章节、9 项待审批、48 个有效相对链接及常见凭证模式。
- 没有执行项目 Maven 构建或远程 CI、完整应用及 MyBatis 多工厂集成、API 序列化、独立实例并行、迁移并发/中断恢复、真实消息 ACK、真实设备、生产备份恢复及部署。实验不验证容量、合规或任何 RPO/RTO。

## 风险、待决及人工审批

| 项目 | 拟批准范围及保留条件 |
| --- | --- |
| RuoYi 原有数据库归属 | 方案 A，保留旧 Schema；待人工批准 |
| 初期物理部署 | 同实例多 Schema，保留独立实例配置能力，无跨节点保证；待人工批准 |
| Schema 命名 | `sanitation_ingest`、`sanitation_business`、`sanitation_track`；待人工批准 |
| 数据源及事务管理 | 旧动态源与三套具名本地组合；真实装配、tenant 上下文及扫描失败关闭待验；待人工批准 |
| 数据库账号及权限 | 角色分离、按表收敛和原始追加；保留策略由 DEC-015；待人工批准 |
| 迁移工具及版本 | 独立 OSS Flyway 11.20.3 候选，支持/许可证及迁移验收明确；待人工批准 |
| 迁移执行方式 | 独立步骤，生产单库单次获准执行；待人工批准 |
| 迁移目录及版本规则 | 三套 history、分库版本、校验和、受控恢复；待人工批准 |
| ENG-002C-02 实施范围 | 配置、角色、三套迁移工具与隔离验证，合成表专用，正式业务对象与运行装配另款审批；待授权 |
| 批准记录 | 决策编号、设计 SHA、范围、条件、批准角色、时间、证据待人工填写 |

主要剩余风险为旧应用自动配置退让、初期实例资源共享、Flyway 版本支持矩阵、权限表粒度和数据恢复点差异。DEC-006 可分范围批复数据库部署、迁移及连接/事务原则。稳定消息契约、可靠重试和恢复策略保持开放，并继续跟踪 DEC-008/009/011/015/016。依据[审批模板](../architecture/reviews/architecture-approval-record-template.md)登记实际决定。
