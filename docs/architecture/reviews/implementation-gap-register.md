# 当前实现差距、生产阻断与待验证项

基线 `1ae32653e15518879e74cd1f038b7635b2e977b8`。下列状态均来自静态核查。“默认装配”或“配置启用”指源码与 纳入版本管理的配置的选择，运行环境、外部覆盖和实际连通性未验证。条目在人工验收前保持开放。

## 目标与当前实现

| 目标架构 | 当前实现及定位 | 状态、差异与验证 |
| --- | --- | --- |
| C-01 / ADR-002、038 工具链 | [根 POM](../../../pom.xml) 中 `java.version` 为 17，派生 compiler source/target；[根 POM](../../../pom.xml)与 [BOM](../../../yudao-dependencies/pom.xml)均声明 Spring Boot 3.5.16。[Actions](../../../.github/workflows/maven.yml)读取根 POM 的 Java 版本，未锁定 Maven 3.9.12，打包跳过测试。[Dockerfile](../../../yudao-server/Dockerfile)使用 Temurin 17 JRE。[Jenkinsfile](../../../script/jenkins/Jenkinsfile)指定 `jdk17`、`maven3`，打包跳过测试 | 编译目标和 Spring Boot 声明一致；构建 JDK、运行 JRE 及 Maven 固定版本存在差距 GAP-001。按实际环境记录 Maven/JDK，验证默认/IoT Reactor、产物级别和镜像运行 |
| C-01 / ADR-006、038 MySQL 与部署 | [Compose](../../../script/docker-compose/docker-compose.yml)包含 MySQL 8.4、Redis、Server 和前端，无独立 Gateway 或 RabbitMQ；[MySQL 初始化](../../../sql/mysql/ruoyi-vue-pro.sql)、[Quartz SQL](../../../sql/mysql/quartz.sql)为上游脚本。默认 `local` 与 Druid 见 [application.yaml](../../../yudao-server/src/main/resources/application.yaml)、[application-local.yaml](../../../yudao-server/src/main/resources/application-local.yaml) | 单机示例、版本声明及默认 profile 配置存在。三逻辑库、版本化迁移与 HA 目标尚未核实落地，见 DEC-006、BLOCK-003/007 |
| C-01/06 / ADR-001 Reactor 和 Server | [根 POM](../../../pom.xml)默认聚合 dependencies、framework、server、system、infra。[Server POM](../../../yudao-server/pom.xml)装配 system、infra。member、pay、bpm、report 可见于树中，对应 Reactor 和装配入口为注释 | 默认构建和默认装配可静态确认；四模块存在但未默认启用。支付/会员不构成 P0 发票能力，BPM/报表是可评估的现有组件 |
| C-01/06 / ADR-012 IoT | [IoT 聚合](../../../yudao-module-iot/pom.xml)包含 biz、core、gateway；Biz 与 Gateway 各依赖 Core。[Gateway POM](../../../yudao-module-iot/yudao-module-iot-gateway/pom.xml)打包 exec，入口 `IotGatewayServerApplication`。IoT 聚合及 Server 的 Biz 依赖默认注释 | 实现存在但未进默认 Reactor，独立进程配置仍须验证。上游 IoT 业务消息不提供项目 VWCP/INGEST 提交证明 |
| C-04 / ADR-012、036 双暂存 | [Gateway 配置](../../../yudao-module-iot/yudao-module-iot-gateway/src/main/resources/application.yaml)选择 Redis、暂存默认关闭。`IotDeviceMessageSpoolServiceImpl.init` 与 `service.device.message.spool.IotDeviceMessageSpoolService.init` 读取同一启用开关及文件。前者检查总线是否可确认，后者仅接受 Redis | 已知冲突 BLOCK-001。在 RabbitMQ 启用暂存时后者拒绝初始化。SQLite 配额和过期清理也不能作为未确认事件保护。可追溯源码路径见 [Gateway 分析](../../engineering/iot-gateway-analysis.md) |
| C-04/05 / ADR-012、036 RabbitMQ | `IotRabbitMQMessageBus` 的专用 Confirm 发送路径设 mandatory 并检查 confirms 和 returned，订阅回调成功后 `basicAck`，异常 `basicNack` 按类型判断是否重排。普通消息发送路径与确认路径有别 | 已有总线实现，Gateway 默认选择 Redis。未核实项目 Relay 状态、BUSINESS 本地提交之后回调返回、死信重放或三库链路，见 BLOCK-003、VAL-003 |
| C-05/06 / ADR-003、007、036 Redis | `TenantRedisCacheManager#getCache` 按租户给缓存名加后缀并留有豁免；`TenantIgnoreAspect` 有绕过和上下文恢复；`TenantJobAspect` 遍历租户执行。OAuth2 有 Redis DAO；字典有本地缓存；BOM 有锁相关依赖 | 框架已有实现；项目、政府跨项目范围、异步和缓存失效覆盖未核实 DEC-008、GAP-002。源码入口见 [ADR-036 决策来源](../adr/ADR-036-cache-and-asynchronous-processing.md) |
| C-05/06 / ADR-005、031 Token 与权限 | `TokenAuthenticationFilter` 调用 Token 校验；`OAuth2TokenServiceImpl#getAccessToken` 先 Redis、再访问令牌表、再对同一输入查未过期 Refresh Token，成功可转换并缓存。`removeAccessToken(String)` 先查访问令牌，找不到直接返回；其他撤销路径有清理访问/刷新令牌及缓存的实现 | 源码确认兼容回退与撤销路径差异。撤销范围、已产生兼容缓存、并发回填、订阅和跨节点生效须验证。正式上线见 GAP-003、BLOCK-004 |
| C-06 / ADR-005、025、030、031 附件 | Infra 管理端 `FileController#getFileContent` 使用 `@PermitAll` 和 `@TenantIgnore`，按存储配置和路径读流；文件列表及删除依赖独立权限 | 通用读取入口和对象级授权要求存在差异。反代、对象桶及签名 URL 策略未核验。敏感附件生产禁用该通用契约见 GAP-004、BLOCK-005 |
| C-02 / ADR-014、017、038 时间 | `IotDeviceDO` 在线、离线和活跃时间为 `LocalDateTime`；数据库和时区契约另需联合核查 | 类型差异及行为未核实 VAL-001；Java 类型无法独立证明实际 UTC 存储正确或错误 |
| C-01/05 / ADR-020、036 Quartz | [local](../../../yudao-server/src/main/resources/application-local.yaml)排除 QuartzAutoConfiguration，即使存在 `auto-startup`；[dev](../../../yudao-server/src/main/resources/application-dev.yaml)声明启动，项目调度须独立验证 | 源码和配置存在，默认 local 未启用 Quartz 自动配置。待验证集群触发、租户上下文和任务唯一约束 VAL-004 |
| C-01/06 / ADR-039 监控 | [监控 starter](../../../yudao-framework/yudao-spring-boot-starter-monitor/pom.xml)含 Actuator/Micrometer 等；[local](../../../yudao-server/src/main/resources/application-local.yaml)声明管理端点及 Druid 监控；[日志配置](../../../yudao-server/src/main/resources/logback-spring.xml)含 SkyWalking 相关配置。源码存在 confirm、spool、Token 等测试 | 配置/依赖及测试源码存在，端点实效和权限、Prometheus/Grafana/Loki/OpenTelemetry 堆栈、端到端指标和审计均未验证 VAL-005/006 |
| C-06 / ADR-001、021 九逻辑模块 | [模块映射](../../engineering/module-mapping.md)及根和 Server POM | 目标业务模块与项目实体在本基线尚未落实为正式模块。只读审核不装配现有可选模块，详见 DEC-011 和[实施路线](implementation-dependency-roadmap.md) |

可复核的源码检索入口为 `rg --files | rg '/(OAuth2TokenServiceImpl|TokenAuthenticationFilter|FileController|TenantRedisCacheManager|TenantIgnoreAspect|TenantJobAspect|IotDeviceDO|IotRabbitMQMessageBus|IotDeviceMessageSpoolServiceImpl|IotDeviceMessageSpoolService)\.java$'`。重点方法及类在上表记录；上游历史构建另见 [JDK21 构建报告](../../engineering/jdk21-build-report.md)。

## 问题登记

状态除特别说明均为开放，表中最后一列为是否需人工决定及何时需要 ACR。P0-BLOCKER 只限制所列生产功能或形态。

| 编号与类型 | 优先级、范围及风险 | 证据及关联 | 处理方案、建议阶段及角色 | 验收条件 | 人工／ACR |
| --- | --- | --- | --- | --- | --- |
| GAP-001 工具链差距 | P0-DESIGN，影响正式工程基线和发布产物 | 上表工具链，ADR-002/038/040，C-01 | A 平台工程，将构建和镜像运行对齐至 OpenJDK 21，保留目标 17，固定 Maven 3.9.12，配置变更另开任务 | 两个 Reactor、版本证据、JAR 编译级别和镜像验收 | 是／改变技术基线才需要 |
| GAP-002 租户上下文差距 | P1-IMPLEMENTATION，项目、政府跨项目和异步隔离 | 上表 Redis，ADR-003～005/036，C-05/06 | D/E 身份与数据安全，采用显式作用域、限制绕过、线程结束清理 | 跨租户、跨项目、并发缓存、Job/消费者异常隔离 | 是／偏离现有边界才需要 |
| GAP-003 Refresh Token 兼容撤销 | P1-IMPLEMENTATION，登录、报表、WebSocket、用户停用及撤销 | 上表 Token，ADR-005/031，C-06 | D/R 身份与安全，评估收窄兼容用途或移除路径，同步处理订阅与缓存 | 获批路由/客户端边界，退出、撤销、停用、刷新、跨节点、并发及长连接测试 | 是／如改变已批准身份要求则评估 |
| GAP-004 通用下载隔离 | P1-IMPLEMENTATION，敏感附件、报告和导出，可能未验证资源授权 | 上表附件，ADR-025/030/031，C-06 | D/R 接入与安全，敏感对象走鉴权授权和短效资源访问，分离公开内容并限制直接存储访问 | 未登录、跨租户/跨项目、链接过期和撤权、审计测试 | 是／改变既有权限边界才需要 |
| BLOCK-001 RabbitMQ 双暂存 | P0-BLOCKER，RabbitMQ + Gateway 暂存生产配置；启动与恢复存在冲突 | 上表双暂存，ADR-012/013/027/036/037，C-01/04 | F Gateway/消息，明确唯一接管实现、历史 SQLite 数据处理、完整性、容量和失败恢复 | 真实 RabbitMQ 专项覆盖单一接管、路由、确认、失败重启、积压重放、容量及业务去重；L2 ACK 仍取决于 INGEST | 是／改变持久化保证需 ACR |
| BLOCK-002 设备安全及 ACK | P0-BLOCKER，正式设备接入与释放离线事件；过早或伪造确认可丢数据 | ADR-012/013/027/033，C-04，DEC-001～004 | G～I 固件、终端、安全及 Gateway，定版协议并运行双模式联调 | 两种实机形态，失败窗口及末端证据，认证、重放及离线恢复通过 | 是／改变 C-04 需 ACR |
| BLOCK-003 可靠数据事务闭环 | P0-BLOCKER，生产原始称重与业务副作用 | ADR-006/012/036，C-04/05，DEC-006、VAL-003 | C/E/I 数据库及消息建设本地事务、版本迁移和去重 | 原始+Outbox、Inbox+业务+必要 Outbox 原子性，路由、并发、失败回滚及重放验收 | 是／改变事务边界需 ACR |
| BLOCK-004 会话撤销保证 | P0-BLOCKER，依赖高风险操作、监管角色或终端撤销的生产入口 | GAP-003，ADR-005/029/031，C-06 | D/R 身份和安全，明确 Refresh Token 使用和撤销延迟边界 | 停用及撤销在批准窗口内全链路拒绝，高风险入口及订阅均有验证 | 是／视已批准身份边界 |
| BLOCK-005 私有附件授权 | P0-BLOCKER，生产敏感证明、财务附件与统计导出，不影响公开附件 | GAP-004，ADR-023/025/030/031，C-06 | D/R 安全与业务，采用显式授权的私有读取 | 直连、存储、签名、导出和撤权均符合租户/项目边界 | 是／变更授权边界时评估 |
| BLOCK-006 财务正式启用 | P0-BLOCKER，自动计费及对外全电发票，有不可逆业务副作用 | ADR-015/024/028/032，C-03/05，DEC-005/012/013 | O/P 财务、计量、集成，先批准计费规则，后做沙箱和财务验收 | 独立权重、税费、重复、外部状态不确定、冲销及对账验收 | 是／变更正式业务范围或约束需评估 |
| BLOCK-007 HA 与容灾承诺 | P0-BLOCKER，仅限制高可用形态发布和 S0/S1 对外承诺 | ADR-035/037/038，C-04/05，DEC-016 | S 数据库与 SRE，按故障域演练复制、切换、防脑裂和受控恢复 | 已确认事件可对账，真实 RPO/RTO 及适用故障域留证 | 是／降低可靠性要求需 ACR |
| VAL-001 时间往返 | P1-IMPLEMENTATION，时间字段、历史关系、统计与账期 | ADR-006/008/014/017/038，C-02 | C/R 数据及验证，在 MySQL 8.4 验证 JDBC、ORM、会话、容器/JVM 及 API | 同一 Instant、UTC DATETIME(3) 和带偏移量 API 保留毫秒，覆盖非 UTC 会话/展示、迟到及无效设备时间 | 否／现有规范内验证 |
| VAL-002 分支保护 | P0-DESIGN，工程治理待核实；保护状态未知 | `gh api repos/weeleng/car_weight/branches/master-jdk17 --jq .protected` 返回 Forbidden。ADR-040、AGENTS、C-01 | A 仓库管理者只读收集分支保护及 Ruleset 证据并决定执行方式 | 有权来源确认 PR、审查、CI、直推和强推保护 | 是／无需 |
| VAL-003 真实投递与消费 | P1-IMPLEMENTATION，确定确认语义及恢复 | ADR-012/036/037、MQ 代码，C-04/05 | E/I 消息与数据，真 broker 测路由、confirm、重排、死信、业务事务 | 按故障矩阵证明消息状态、重试及副作用唯一 | 否／按批准契约验证 |
| VAL-004 Quartz 和缓存 | P1-IMPLEMENTATION，任务触发、并发和撤权 | ADR-007/020/036，C-05/06 | D/L 平台，确认目标 profile、任务持久化/集群与 TTL | 重复触发、故障重进、上下文隔离、缓存失效 | 否／按批准设计验证 |
| VAL-005 容量及指标 | P1-IMPLEMENTATION，容量承诺和告警覆盖 | ADR-035/037/039，C-01/02/04/05/06 | R/S 性能 SRE，先确定样本/时段/Broker/网络/负载剖面，后压测、故障和监控 | 真实指标、延迟边界、错误率、耐久、对账、告警降级与低基数 | 否／阈值改为承诺需人工批准 |
| VAL-006 安全合规与信创 | P1-IMPLEMENTATION，合规、供应链与适配声明 | ADR-029/030/032～034/039，C-01/02/06 | R 安全合规，评估等保三级建设（定级、备案、测评证据）、保留、脱敏、加密备份、具体信创产品版本及端点权限 | 专项书面结论、批准场景、产品/版本兼容性及独立测评证据 | 是／范围变化时评估 |

P0-BLOCKER 共 7 项，分别限制 Gateway 指定配置、设备接入、可靠数据事务、关键会话授权、私有附件、自动计费及开票、高可用形态及承诺。BLOCK-001 延续已确认的生产阻断，其余为本次审核建议的分功能生产验收门槛，须由人工确认。下游测试、真实硬件、第三方沙箱、运行环境权限、数据库高可用及灾备演练本次均未验证。
