# ADR-002 技术选型

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 编号与标题为 ENG-001B-02B-01 提供的候选映射，原始 ADR 正文尚未核验。历史版本与完整批准原文待取得。
- 本次起始基线：`3f831747a7fe0c72b8906366caa26f0145884788`

## 决策来源

ENG-001B-02B-01 提供目标选型及候选范围。[C-01](../standards/technology-baseline.md)、[历史构建报告](../../engineering/jdk21-build-report.md)、[根 POM](../../../pom.xml)、[BOM](../../../yudao-dependencies/pom.xml)和 [Compose 配置](../../../script/docker-compose/docker-compose.yml)提供仓库证据。

## 背景

现有仓库基于 RuoYi-Vue-Pro，POM 采用 Spring Boot 3.5.16 和 Java 17 编译目标，Compose 声明 MySQL 8.4 与 Redis，仓库中有 RabbitMQ 支持、Quartz starter 和 Flowable 依赖管理。历史报告验证了 JDK 21 下的构建及 Gateway 最小启动。当前 CI 按 POM 选择 JDK 17。组件存在、目标选型和生产启用分别核对。

## 决策

| 类别 | 拟议选型 |
| --- | --- |
| 目标工具链与框架 | OpenJDK 21 构建与运行，Maven 3.9.12，Java 编译目标暂保 17，Spring Boot 3.5.16，RuoYi-Vue-Pro 模块化单体 |
| 目标数据与消息组件 | MySQL 8.4、Redis、RabbitMQ |
| 基础部署 | Docker，IoT Gateway 独立部署 |
| 待核验的候选能力 | Flyway 用于数据库版本迁移，需核验仓库集成；MQTT 用于设备及第三方消息接入，范围待确认；天地图、高德等 GIS 适配以接口隔离；Quartz 和 Flowable 优先评估复用现有框架能力 |

## 适用范围

开发、构建、运行和部署技术选择及候选能力评估。

## 约束与禁止事项

Kafka、Nacos、Elasticsearch 和 Kubernetes 不列为 P0 必需组件。版本和依赖调整须经批准。RabbitMQ 与 SQLite 双暂存服务冲突仍为生产启用阻断。本次不引入依赖或修改 POM 和 CI。

## 备选方案及取舍

保留 JDK 17 构建可沿用当前 CI，项目目标使用 JDK 21 并保留 Java 17 编译目标。先复用框架组件可减少维护面，额外平台组件待出现明确需求再评估。版本化迁移是约束，具体迁移工具需结合存量库与部署流程验证。

## 与其他 ADR/ACR 的关系

[C-01](../standards/technology-baseline.md)是目标技术基线，[C-05](../standards/transaction-boundaries.md)约束迁移和事务。拟支撑 ADR-001、ADR-006 和 ADR-012。[ACR-001 核对记录](../acr/ACR-001-reconciliation.md)记录 JDK 21 决策要点，完整原文待核验。

## 验证要求

后续核验目标版本和实际字节码，分别构建默认与 IoT Reactor。按目标环境验证 MySQL、Redis、RabbitMQ 及 Docker 部署。对候选组件先核对已有依赖与启用配置，再执行相应集成验证。本次未运行这些检查。

## 待确认事项

Flyway 集成、MQTT 接入范围、GIS 供应商、Quartz 和 Flowable 使用范围以及 CI 迁移计划待确认。

## 批准记录

- 本草案审批：待审查。
- 历史批准版本、批准人、日期和可核验的批准证据：待取得。
