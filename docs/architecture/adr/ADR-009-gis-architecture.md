# ADR-009 GIS 平台架构

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`ecd6ed655cec214a0ed945b6e5f2e21d3dc71ee6`
- 编号、标题和设计要点来自 ENG-001B-02C-02，候选映射与历史批准原文待核验。

## 决策来源

ENG-001B-02C-02 提供 GIS 能力和候选选型。已只读核查 [AreaController.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/ip/AreaController.java)、[AreaUtils.java](../../../yudao-framework/yudao-spring-boot-starter-biz-ip/src/main/java/cn/iocoder/yudao/framework/ip/core/utils/AreaUtils.java)、[area.csv](../../../yudao-framework/yudao-spring-boot-starter-biz-ip/src/main/resources/area.csv)，现有区域树由 CSV 加载，接口可查询区域树及按 IP 查区域，属于行政区域基础能力。另核查 [TradeConfigController.java](../../../yudao-module-mall/yudao-module-trade/src/main/java/cn/iocoder/yudao/module/trade/controller/admin/config/TradeConfigController.java)、[application-local.yaml](../../../yudao-server/src/main/resources/application-local.yaml)、[application-dev.yaml](../../../yudao-server/src/main/resources/application-dev.yaml)、[pom.xml](../../../yudao-dependencies/pom.xml)、[pom.xml](../../../yudao-module-iot/yudao-module-iot-biz/pom.xml)，现有独立商城场景有腾讯地图配置读取；在检查的直接依赖声明中未发现 JTS、GeoTools 或 Proj4J 声明。未解析完整传递依赖，也未验证前端地图 SDK。现有区域与配置入口不足以证明具备统一 GIS、围栏或清运线路规划。

## 背景

环卫应用需统一地图展示、正逆地理编码、行政区划、坐标转换、距离与空间关系、电子围栏、清运路径规划、轨迹展示、空间统计和厨余垃圾地图。

## 决策

- `sanitation-gis` 通过公开 API 或明确事件契约提供 GIS 能力。适配层隔离厂商请求响应。统一契约表达输入坐标和坐标系、正逆地理编码请求、规划请求与结果、距离、预计耗时、供应商、请求及结果状态、来源与版本。按供应商提供真实能力范围。
- 天地图为默认地图服务候选，高德为开发及路径规划候选，百度为可扩展候选。底图、规划和地理编码可分别选择来源。
- JTS 为 Java 基础平面几何候选，用于点与多边形关系、相交、包含、有效性及范围计算。地理距离、面积及投影采用适用算法和空间参考系。MySQL 8.4 空间类型、索引和查询为候选。
- BUSINESS 保存行政区引用、项目空间范围、服务点、围栏及线路等业务空间对象。TRACK 保存正式定位与轨迹。INGEST 保存设备原始定位和事件。
- 供应商超时、不可用或配额不足须报告明确状态。可用符合授权条件的已有空间数据降级。地图服务异常不得阻断原始称重事件在 INGEST 持久化。已有有效业务依据的事件可继续后续处理。

## 适用范围

GIS 能力、供应商适配、空间计算与逻辑数据归属。

## 约束与禁止事项

禁止跨模块调用内部 Mapper、DO 或 Biz，GIS 也不得反向依赖调度、称重或计费内部实现。禁止跨库原子事务、伪造地理编码或规划结果、以角度差当作米制距离。前端地图密钥和服务端凭据分别管理，禁止硬编码公开仓库。许可与地理信息合规按供应商与用途核查，覆盖缓存、转换、展示和第三方传输。技术候选不构成商用授权。

## 备选方案及取舍

单一厂商接口上线快，但业务受其坐标系、限额及能力约束。统一适配可按能力拆分厂商，需额外验证契约和降级。JTS 应用侧与 MySQL 数据库侧分别验证精度及查询性能。

## 与其他 ADR/ACR 的关系

遵循 [C-06](../standards/module-boundaries.md)、[ADR-006](ADR-006-database-architecture.md)、[ADR-012](ADR-012-iot-ingestion.md)及 [ADR-014](ADR-014-gnss-and-tracking.md)。坐标、围栏与线路见 [ADR-010](ADR-010-coordinate-system.md)、[ADR-011](ADR-011-geofencing.md)、[ADR-019](ADR-019-collection-route-management.md)。C-04 的 RabbitMQ 与 SQLite 双暂存生产启用阻断继续适用。约定来源见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。

## 验证要求

后续覆盖供应商适配、坐标系、超时与配额、空间计算、非法几何、缓存隔离、地图异常降级和原始称重持久化。JTS、空间索引及查询性能尚未验证。

## 待确认事项

供应商能力、许可、配额、费用及数据使用限制，缓存有效期、切换与降级条件，地理计算和空间索引兼容性待确认。

## 批准记录

草案待人工审查。历史批准原文、版本、批准人、日期及证据待取得。本次仅核查源码及文档，未运行功能或供应商验证。
