# 九模块依赖映射方案

上游源码基线为 `2ff45ae44a28f483e40ba63315bd0a03c30859c4`。上游依赖来自 POM，九模块部分为架构建议。尚无项目模块编译或运行验证。

## 上游模块关系

- 根 POM 默认聚合 system、infra、server、framework 和 BOM。IoT 聚合及 server 对 iot-biz 的依赖均需显式启用。
- `yudao-module-iot` 聚合 core、biz、gateway。biz → core、system 及基础设施 starters。gateway → core、Vert.x 和协议驱动。core → common 与项目 MQ starter。Gateway 通过设备 RPC 和消息总线与 Biz 通信。
- `yudao-server` 装配业务实现。Gateway 的可执行 POM 独立打包，两个进程分开部署。

## 建议 Maven 结构

在批准后设置 `sanitation-modules` 聚合，每个逻辑模块有独立目录。表中的箭头由调用方指向被依赖方。

| 逻辑模块 | 建议打包 | 允许的主要项目依赖 |
| --- | --- | --- |
| sanitation-common | 单一基础 JAR | 无项目业务依赖。仅共享值类型与技术原语。 |
| sanitation-base | api + biz | common，biz 可通过上游 system/infra 的公开能力管理主数据。 |
| sanitation-gis | api + biz | common、base-api，biz 负责 GIS 提供商适配。 |
| sanitation-device | api + biz | common、base-api，biz 的适配层可消费 IoT Core 契约。 |
| sanitation-operation | api + biz | base-api、gis-api、device-api，统一作业编排。 |
| sanitation-billing | api + biz | operation-api 的已确认事实、base-api 的必要查询。 |
| sanitation-analysis | 单独 biz | 各生产者 API 的版本化事件，使用独立读模型。若已有同步调用方，再提取 analysis-api。 |
| sanitation-event | api + biz | common；api 仅共享事件封装，biz 为 Outbox 发布和 Inbox 基础设施，避开领域服务回调。 |
| sanitation-integration | 单独 biz | 所需领域 API 和 event-api。若外部接口需另行复用契约，再提取 integration-api。 |

表中需要拆分 API/Biz 的是 base、gis、device、operation、billing 和 event。API 只包含 DTO、接口或版本化事件，依赖 common；实现只在各模块 biz。各业务模块可依赖 event-api。命令、事件和查询契约归拥有领域，event-api 不收罗业务 DTO。所有业务实现由 `yudao-server` 最终装配。

## 依赖禁区与循环检查

| 可能的循环 | 建议切断方式 |
| --- | --- |
| operation → billing → operation | billing 消费已确认作业事实，operation 对账单结果使用订阅或查询读模型。 |
| operation → device → operation | device 发布设备事实，operation 订阅；设备接入只理解设备契约。 |
| base ↔ gis | GIS 负责坐标与地图，base 仅持稳定标识；需要联合查询时由上层组装。 |
| event ↔ 领域 Biz | 发布器通过事件信封和技术端口工作，领域所有者实现事务发布与消费。 |
| integration ↔ 领域 Biz，analysis ↔ 领域 Biz | 外联及分析单向依赖领域契约，生产者不调用外联或分析的实现。 |

禁止跨模块依赖对方的 Biz JAR、Mapper、DataObject 或私有数据表。跨模块读取走 API 或读模型，跨模块异步变更走带版本的领域事件。同步 API 只对必要的事务边界开放。

专用 Gateway 可在单独 Maven 启动模块中装配 VWCP 适配器，仅依赖 IoT Core、必要的设备契约以及传输基础设施。它负责连接、帧处理、会话、项目认可的协议 ACK 及入口持久缓冲。设备主数据、称重业务、结算、业务 Outbox/Inbox 由各业务模块拥有。Gateway 禁止依赖任何 sanitation-*-biz 或跨进程写业务库。
