# ADR-026 厨余垃圾专题地图

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`f378d3f33d4b96f50eac15e3c361d012bd3b9891`
- 编号、标题及设计要点来自 ENG-001B-02C-03，候选映射与历史批准原文待核验。

## 决策来源

ENG-001B-02C-03 提供专题视图、授权及性能候选方案。已只读核查 [SseSenderApiImpl.java](../../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/api/sse/SseSenderApiImpl.java)、[application.yaml](../../../yudao-server/src/main/resources/application.yaml)，仓库具备 SSE 发送入口，当前基础配置关闭 SSE。[DeptDataPermissionRule.java](../../../yudao-framework/yudao-spring-boot-starter-biz-data-permission/src/main/java/cn/iocoder/yudao/framework/datapermission/core/rule/dept/DeptDataPermissionRule.java)、[TenantContextHolder.java](../../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/context/TenantContextHolder.java)、[TenantUtils.java](../../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/util/TenantUtils.java)、[application.yaml](../../../yudao-server/src/main/resources/application.yaml)提供通用上下文和部门数据权限，专题地图跨项目授权、指标及联动尚待实现。

## 背景

监管、运营及必要大屏需要查看厨余垃圾从产生到接收的真实业务状态及异常。专题地图是业务信息视图，各领域保有事实。

## 决策

- 候选图层包括行政区、项目服务范围、主体与服务点、公共收集点、车辆、线路、实际轨迹、中转站、处理设施、围栏及异常告警位置。展示授权范围内的主体类型、项目、服务点、状态、收运和历史统计，多服务点不得重复累计主体总量。
- 车辆视图包含位置、时间、来源、质量、设备连接、作业任务、运输状态及最近有效更新时间。显式区分实时、延迟、离线及无有效定位。
- 待清运、作业中、已收运、运输中、已到厂、已接收和异常待处理均为候选显示状态。从正式业务记录或明确映射获取，各对象保留独立状态机。
- 候选统计按行政区、项目、时间、主体类型、服务点、车辆、线路及设施汇总，覆盖收运及接收重量、有效主体数、已服务站点数、完成率、异常数、在线情况与重量差异。热力与聚合可展示产生分布、收运量、主体密度、覆盖和异常分布，区分实测、估算及聚合，跨图层、服务点与批次去重。
- 查询按真实关系追溯主体、服务点、任务、车辆、RFID、正式称重、轨迹、批次、交付及异常。无法确认关系时显示未知或待核验。
- 监管按项目、区域、业务类别和时间范围显式授权。服务端检查聚合、热力、轨迹、导出和详情下钻。公开地图及大屏默认不展示个人信息和详细联系方式。
- 候选性能方案为可视范围查询、空间索引、点聚合、分级加载、按需轨迹、统计缓存及异步更新。WebSocket、SSE、轮询分别评估。允许可辨识的最终一致性，显示更新时间。
- GIS 归 `sanitation-gis`，统计聚合拟归 `sanitation-analysis`。车辆、主体、作业、交付和告警由领域经公开契约供给。

## 适用范围

厨余专题地图图层、统计、轨迹、状态展示及政府和运营查看。

## 约束与禁止事项

禁止把围栏进入当作收运和处置完成、把最后有效位置无条件标为实时、把收运量当作总产生量或用距离猜测业务关系。禁止定义独立报表口径、一次加载全量数据、绕过授权或持久化、直接访问内部 Mapper、DO 或 Biz、复制正式主数据。地图可视范围不得当作授权范围。

## 备选方案及取舍

实时聚合可提高新鲜度但增加负载。按需和缓存视图可控，但需标明数据时间并避免空域及跨租户聚合泄漏。

## 与其他 ADR/ACR 的关系

遵循 [ADR-003](ADR-003-saas-tenancy.md)、[ADR-004](ADR-004-organization-and-project.md)、[ADR-005](ADR-005-user-and-permissions.md)的权限，[ADR-009](ADR-009-gis-architecture.md)、[ADR-010](ADR-010-coordinate-system.md)、[ADR-011](ADR-011-geofencing.md)的空间规范及 [ADR-014](ADR-014-gnss-and-tracking.md)的轨迹和质量边界。统计口径关联候选 [ADR-025](README.md#adr-025)，更新方案关联候选 [ADR-035](README.md#adr-035)、[ADR-036](README.md#adr-036)，正文均待导入。遵循 C-02、C-03、C-04、C-05、[C-06](../standards/module-boundaries.md)，出处见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。

## 验证要求

后续覆盖多图层坐标、多服务点统计、离线与延迟提示、状态映射、重量防重、热力来源、权限隔离、跨项目监管、大量点位、轨迹、下钻及供应商异常降级。

## 待确认事项

P0 图层与展示权限、刷新周期、定位有效窗口、统计口径、热力算法及来源、推送和性能方案待确认。

## 批准记录

草案待人工审查。历史批准原文、版本、批准人、日期及证据待取得。本次仅核查源码和文档，未执行运行验收。
