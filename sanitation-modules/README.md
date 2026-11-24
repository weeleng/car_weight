# 环卫 Maven 骨架

默认 Reactor 聚合九个逻辑领域，包含 7 个聚合 POM 和 15 个空 JAR。common 为基础 JAR，base、gis、device、operation、billing、event 各拆分 API 与 Biz，analysis 和 integration 各保留一个 Biz。构件继承仓库统一 revision、BOM 和 Java 17 release。

包根为 `cn.iocoder.yudao.module.sanitation`。预留 `.common`、`.<domain>.api`、`.<domain>.biz`。API 预留公开契约，Biz 内部实现按领域隔离。本骨架的六条直接依赖均为各 Biz 到自身 API。候选白名单及专项审批见[详细设计](../docs/engineering/ENG-002B-module-skeleton-design.md)。Server 与 Gateway 的运行装配保持原样。DEC-011 和 BLOCK-001 继续开放。

使用 JDK 21 和 Maven 3.9.12。有效 POM、依赖树、构件和类分析证据的生成方法见[实施报告](../docs/engineering/ENG-002B-module-skeleton-report.md)。
