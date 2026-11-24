# ADR-034 国产化及信创兼容性

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`b3fd4cea75c2b4fe305271eda970df5688765d72`
- 编号、标题、设计及量化指标取自 ENG-001B-02C-05，候选映射与历史批准原文待核验。

## 决策来源

ENG-001B-02C-05 提供信创候选范围。已只读核查 [pom.xml](../../../pom.xml)、[pom.xml](../../../yudao-dependencies/pom.xml)、[Dockerfile](../../../yudao-server/Dockerfile)、[docker-compose.yml](../../../script/docker-compose/docker-compose.yml)、[maven.yml](../../../.github/workflows/maven.yml)、[ruoyi-vue-pro.sql](../../../sql/mysql/ruoyi-vue-pro.sql)、[quartz.sql](../../../sql/mysql/quartz.sql)、[pom.xml](../../../yudao-module-iot/yudao-module-iot-gateway/pom.xml)。MySQL 初始化脚本使用 InnoDB、AUTO_INCREMENT 等 MySQL 语法，Compose 声明 MySQL 8.4 与 Redis。Dockerfile 基镜像为 Temurin 17 JRE，CI 依 POM 选 JDK 17 并跳过测试，和 C-01 的 JDK 21 构建及运行目标存在配置差异。历史 [JDK 21 构建证据](../../engineering/jdk21-build-report.md)仅覆盖其记录环境。

## 背景

信创兼容需逐产品、版本和指令集评估。当前工程基线继续使用 RuoYi-Vue-Pro、Spring Boot 3.5.16、JDK 21、Java 17 目标、Maven 3.9.12、MySQL 8.4、Redis、RabbitMQ、Docker。

## 决策

| 类别 | 候选平台与范围 | 当前证据状态 |
| --- | --- | --- |
| CPU | x86-64、ARM64、鲲鹏、飞腾、海光、兆芯、龙芯及指定平台，验证指令集、镜像、JDK 与原生依赖 | 候选组合未评估 |
| 操作系统 | 常规 Linux、openEuler、银河麒麟、统信及指定系统，验证容器、网络、文件系统、服务、对时、字体、字符集、TLS、密码库和加固 | 指定国产系统未评估 |
| JDK | OpenJDK 21 发行版，验证 Java 17 字节码、Spring Boot、JVM 参数、GC、TLS、密码提供者、JDBC、JNI 及容器内存识别 | 仅有历史环境的构建和最小运行证据 |
| 数据库 | MySQL 8.4 基线，达梦、金仓、openGauss、OceanBase 适用模式、TiDB 或指定产品作为候选 | 脚本及配置可查，替代库未评估 |
| 中间件 | Redis、RabbitMQ 和适用替代，验证缓存、过期、租户隔离、锁、持久化、发布与消费确认、重试、死信、重复和恢复 | 候选组合未评估，双暂存冲突仍阻断生产启用 |
| GIS 与报表 | JTS、空间索引和坐标库、Excel/PDF、中文字体、图像处理、文件与浏览器，区分纯 Java 和 JNI 或系统能力 | 候选组合未评估 |
| 终端 | 按 ADR-027 保留 Android/PDA；其他国产移动系统须重新验证串口、蓝牙、后台、本地数据库、定位、安全存储、同步和升级 | 替代系统未评估 |

兼容矩阵逐项记录平台版本、CPU、OS、JDK、数据库、中间件、容器、范围、实测、问题、适配与认证状态。状态区分未评估、理论兼容、基础运行、专项功能、性能验证及实际取得认证。

## 适用范围

服务器、容器、数据库、中间件、终端及 GIS、报表的信创评估。

## 约束与禁止事项

P0 不强制全部替代适配。保持统一持久化边界，优先标准 SQL，识别方言并按需适配、使用版本化迁移及专项测试。禁止取消唯一约束、事务、必要索引或可靠提交。禁止把 ARM64、MySQL 模式或接口兼容等同于实测语义，禁止降低 JDK 基线、更换本任务数据库或中间件、宣称已取得适配认证。

## 备选方案及取舍

围绕现有基线隔离方言可降低迁移成本，完全通用化可能压缩可靠性与性能。按实际招采目标建立适配矩阵。

## 与其他 ADR/ACR 的关系

对齐 [C-01](../standards/technology-baseline.md)、[C-02](../standards/time-conventions.md)、[C-03](../standards/weight-conventions.md)、[C-04](../standards/iot-ack-and-durability.md)、[C-05](../standards/transaction-boundaries.md)、[C-06](../standards/module-boundaries.md)和 [ADR-002](ADR-002-technology-selection.md)。GIS 关联 [ADR-009](ADR-009-gis-architecture.md)、[ADR-010](ADR-010-coordinate-system.md)、[ADR-011](ADR-011-geofencing.md)，终端遵循 [ADR-027](ADR-027-mobile-and-vehicle-terminals.md)。证据见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。

## 验证要求

在 x86-64、ARM64 及指定平台验证实际运行、容器、字符集、JDBC/MyBatis-Plus、SQL 方言、主键、唯一键、DECIMAL、BIGINT/INT、UTC、JSON、分页、批量写、事务隔离与行锁、Outbox 并发和 Inbox 幂等、迁移分区、空间索引、消息确认、Excel、备份恢复和性能对比。同时核验 INGEST 持久化、重量精度与逻辑库边界。

## 待确认事项

实际平台型号与版本、兼容范围、镜像及 CI 的 JDK 21 对齐、原生依赖及专项测试和认证计划待确认。

## 批准记录

草案待人工审查，历史批准原文、版本、批准人、日期及证据待取得。本次只读核查源码、配置和本地工具版本，未运行安全、合规、功能或性能验收。
