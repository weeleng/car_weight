# ADR-038 系统部署与环境管理

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`699e700603d119a2e43c7ec877b8ece5029bf9ff`
- 编号、标题及设计取自 ENG-001B-02C-06，候选映射与历史批准原文待核验。

## 决策来源

ENG-001B-02C-06 提供部署候选。已只读核查 [pom.xml](../../../pom.xml)、[pom.xml](../../../yudao-server/pom.xml)、[pom.xml](../../../yudao-module-iot/yudao-module-iot-gateway/pom.xml)、[IotGatewayServerApplication.java](../../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/IotGatewayServerApplication.java)、[Dockerfile](../../../yudao-server/Dockerfile)、[docker-compose.yml](../../../script/docker-compose/docker-compose.yml)、[maven.yml](../../../.github/workflows/maven.yml)、[Jenkinsfile](../../../script/jenkins/Jenkinsfile)、[ruoyi-vue-pro.sql](../../../sql/mysql/ruoyi-vue-pro.sql)、[quartz.sql](../../../sql/mysql/quartz.sql)和 [application.yaml](../../../yudao-module-iot/yudao-module-iot-gateway/src/main/resources/application.yaml)、[application-local.yaml](../../../yudao-server/src/main/resources/application-local.yaml)。根 Reactor 默认聚合依赖、框架、Server、System、Infra。Server 默认装配 System、Infra，会员、支付、BPM、报表、IoT Biz 依赖为注释。IoT 聚合默认注释，Core/Biz/Gateway 代码与独立 Gateway 入口、exec 构件存在。Compose 示例包含单机 Server、Web、MySQL 8.4、Redis，未部署独立 Gateway 或 RabbitMQ。GitHub Actions 按 POM 选 JDK 17，打包跳过测试。Dockerfile 使用 Temurin 17 JRE。Jenkinsfile 声明 jdk17 与 maven3 工具，包含构建、归档、部署阶段，现有构建命令跳过测试。未核验工具实际版本或 Jenkins 服务运行。

## 背景

目标保持 JDK 21 构建及运行、Java 17 编译目标、Maven 3.9.12、Spring Boot 3.5.16、MySQL 8.4、Redis、RabbitMQ、模块化单体和独立 IoT Gateway。源码存在、参与 Reactor、Server 装配及运行分别取证。

## 决策

| 形态 | 候选部署 |
| --- | --- |
| SaaS | 共享应用及独立 Gateway，支持业务多实例、统一版本监控和备份、租户配置隔离、项目监管授权及容量扩展 |
| 基础私有化 | Docker/Compose、独立数据库与配置、文件或对象存储、基础监控、备份和升级说明，明示单点及恢复限制 |
| 高可用私有化 | 多主机容器、应用与 Gateway 多实例、负载均衡、高可用库和消息、必要 Redis 高可用、独立监控备份、安全分区及切换演练 |

- Docker 是长期生产路线候选，单机 Compose 只描述基础部署。Kubernetes 非 P0 强制，k3s 仅作开发测试候选，生产编排引入需独立 ADR/ACR。部署组件可含 Web、反代、业务应用、独立 Gateway、库、缓存、MQ、必要 MQTT、文件、监控和备份。九个 sanitation 模块仍保留模块化单体边界。
- 划分本地、共享开发、测试、集成、预生产和生产。合并环境需保留隔离。默认、环境、租户、项目、设备配置与凭据分别管理，评估 Profile、环境变量、外部配置、受控 Secret 及密钥管理。
- 镜像可重复且锁定版本及 Digest，记录来源与依赖检查，配置非 root、最小权限、健康、限制、日志及持久目录。禁止以 latest 单独标识生产版本。
- GitHub Actions 保留仓库校验，Jenkins 为 CI/CD 候选。候选流水线固定 SHA、核对工具链、编译与测试、迁移、依赖和安全检查、镜像、测试制品及部署、集成验证、发布记录和人工生产批准。触发及职责分工待定。
- Flyway 为版本迁移候选，分别处理 INGEST、BUSINESS、TRACK 的版本、校验和、顺序、迁移权限、备份、失败、大表、启动竞争和兼容性。候选停机、滚动或蓝绿升级需关联应用、库、消息、VWCP、Android/PDA、配置版本与兼容范围。先扩展后收缩；镜像回滚不能自动回滚库或外部副作用。
- Gateway 和终端升级检查停机、未处理及未确认事件、重连、终端缓存、固件兼容、协议协商和恢复。按 ADR-033 验证升级来源与完整性。
- 落实外内端口、库、消息、运维、监控和防火墙边界。时间检查对时、容器、JVM、MySQL 会话、JDBC、UTC 存储、Asia/Shanghai 与设备时钟。交付镜像、前端制品、清单、模板、迁移、版本、依赖、安全、验证、升级回滚、恢复及已知问题。

## 适用范围

生产形态、环境分级、容器、构建发布、迁移及升级回滚。

## 约束与禁止事项

域名不能作唯一租户隔离。禁止向基础部署无条件承诺高可用，禁止提交生产凭据、以手改容器作为配置流程、默认向互联网暴露库及 MQ 管理端、编辑已执行历史迁移或假定全部 DDL 可事务回滚。禁止清除未确认称重。JDK 21 是目标，JDK 17 CI 和镜像现状待对齐，不视作完成。跨模块遵循 C-06。

## 备选方案及取舍

Compose 成本低但有单点。多主机提供切换能力，需外部设施与演练。编排平台按团队运维及实际收益再选。

## 与其他 ADR/ACR 的关系

依据 [C-01](../standards/technology-baseline.md)、[C-02](../standards/time-conventions.md)、[C-05](../standards/transaction-boundaries.md)、[C-06](../standards/module-boundaries.md)及 [ADR-001](ADR-001-system-architecture.md)、[ADR-002](ADR-002-technology-selection.md)、[ADR-029](ADR-029-mlps-and-security-compliance.md)、[ADR-033](ADR-033-cryptography-and-iot-security.md)、[ADR-034](ADR-034-domestic-platform-compatibility.md)、[ADR-035](ADR-035-capacity-and-performance.md)、[ADR-037](ADR-037-high-availability-and-disaster-recovery.md)。来源见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。

## 验证要求

验证 JDK 21 与 Java 17 产物、镜像、三形态和独立 Gateway、配置凭据、迁移兼容、滚动升级、终端及 Gateway 保护、回滚恢复和时间往返。实际服务器规格依据 ADR-035 实测。

## 待确认事项

JDK 21 配置对齐、Jenkins 与 Actions 分工、Flyway 实施、拓扑规模、端口域名证书、密钥和镜像治理、版本兼容及回滚流程待验。

## 批准记录

草案待人工审查，历史批准原文、版本、批准人、日期及证据待取得。本次仅核查源码、配置与文档，未执行运行验收。
