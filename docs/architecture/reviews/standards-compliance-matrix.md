# C-01 至 C-06 交叉审核矩阵

审核基线为 `1ae32653e15518879e74cd1f038b7635b2e977b8`。矩阵评价草案与规范的文本关系。“一致”表示该草案明确覆盖其范围内的相关约束。“部分覆盖”表示只覆盖该列的部分适用要求，或将实质实现留给另一草案。源码偏差见[实施差距](implementation-gap-register.md)，编号可在两份登记表查找。

| ADR | [C-01](../standards/technology-baseline.md) | [C-02](../standards/time-conventions.md) | [C-03](../standards/weight-conventions.md) | [C-04](../standards/iot-ack-and-durability.md) | [C-05](../standards/transaction-boundaries.md) | [C-06](../standards/module-boundaries.md) | 证据或适用边界 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| [ADR-001 系统总体架构](../adr/ADR-001-system-architecture.md) | 一致 | 不适用 | 不适用 | 不适用 | 不适用 | 一致 | 决策：模块化单体、独立 Gateway；C-06 公开 API/事件（C-06 同列适用）。 |
| [ADR-002 技术选型](../adr/ADR-002-technology-selection.md) | 部分覆盖 | 不适用 | 不适用 | 不适用 | 部分覆盖 | 不适用 | 决策/待确认：JDK 21 与 Java 17 区分；CI 交接 GAP-001；Flyway 待选 DEC-006。 |
| [ADR-003 SaaS 多租户架构](../adr/ADR-003-saas-tenancy.md) | 一致 | 部分覆盖 | 不适用 | 一致 | 一致 | 一致 | 决策：共享隔离、显式监管授权；C-02 历史作用域缺往返及重叠验证 DEC-007；跨链路见 DEC-008。 |
| [ADR-004 组织与项目管理](../adr/ADR-004-organization-and-project.md) | 不适用 | 部分覆盖 | 不适用 | 不适用 | 一致 | 一致 | 决策/待确认：区分组织项目与行政区，关系有效期和范围重叠 DEC-007/008。 |
| [ADR-005 用户与权限管理](../adr/ADR-005-user-and-permissions.md) | 不适用 | 部分覆盖 | 不适用 | 不适用 | 不适用 | 一致 | 决策/待确认：历史授权有效期，服务端默认拒绝；Token 和附件 GAP-003/004。 |
| [ADR-006 数据库架构](../adr/ADR-006-database-architecture.md) | 不适用 | 一致 | 一致 | 部分覆盖 | 一致 | 一致 | 决策/约束：三库、类型和本地事务；C-04 仅记录提交，确认流程见 ADR-012；物理部署 DEC-006。 |
| [ADR-007 数据字典与基础编码](../adr/ADR-007-dictionary-and-coding.md) | 不适用 | 不适用 | 不适用 | 不适用 | 部分覆盖 | 一致 | 决策/待确认：历史字典版本及租户覆盖；版本/缓存失效及迁移 DEC-008。 |
| [ADR-008 API 规范](../adr/ADR-008-api-conventions.md) | 不适用 | 一致 | 一致 | 不适用 | 一致 | 一致 | 决策/待确认：ISO 8601、精确十进制（C-03 约束公开契约所表达的重量），金额线格式及幂等期限 DEC-012/017。 |
| [ADR-009 GIS 平台架构](../adr/ADR-009-gis-architecture.md) | 不适用 | 一致 | 不适用 | 部分覆盖 | 一致 | 一致 | 决策/关系：轨迹数据边界、GIS 适配；C-04 转引接入及双暂存，位置事件不自动产生正式称重；DEC-009。 |
| [ADR-010 坐标体系与坐标转换](../adr/ADR-010-coordinate-system.md) | 不适用 | 部分覆盖 | 不适用 | 不适用 | 一致 | 一致 | 决策/待确认：原始、转换及业务坐标分离，定位时间与转换版本的联动待确认 DEC-009。 |
| [ADR-011 电子围栏与空间范围管理](../adr/ADR-011-geofencing.md) | 不适用 | 一致 | 不适用 | 部分覆盖 | 一致 | 一致 | 决策/约束：围栏不是业务完成，C-04 仅沿用接入边界；漂移与阈值 DEC-010。 |
| [ADR-012 IoT 接入架构](../adr/ADR-012-iot-ingestion.md) | 不适用 | 一致 | 一致 | 一致 | 一致 | 一致 | 决策/约束：两条提交后独立路径与消费提交后确认；协议、凭据和实现未定 DEC-001/003、BLOCK-001/002。 |
| [ADR-013 VWCP 设备通信协议](../adr/ADR-013-vwcp-protocol.md) | 不适用 | 一致 | 一致 | 一致 | 部分覆盖 | 不适用 | 决策/待确认：Epoch 与 Unix 毫秒分离，有符号 INT32 克，三种重复结果；C-05 仅定义 INGEST，协议及存储 DEC-001～004。 |
| [ADR-014 GNSS 定位与轨迹管理](../adr/ADR-014-gnss-and-tracking.md) | 不适用 | 一致 | 部分覆盖 | 不适用 | 部分覆盖 | 一致 | 决策/待确认：TRACK 幂等、原始与计算坐标分开；C-03 称重时间关联、C-05 TRACK 恢复契约未定 DEC-006/009。 |
| [ADR-015 称重业务模型](../adr/ADR-015-weighing-model.md) | 不适用 | 一致 | 一致 | 不适用 | 部分覆盖 | 部分覆盖 | 决策/待确认：原始不覆盖、父子防重；C-05 唯一键和 C-06 业务对象归属 DEC-005/006/011。 |
| [ADR-016 RFID 识别与绑定](../adr/ADR-016-rfid-identification.md) | 不适用 | 一致 | 一致 | 不适用 | 部分覆盖 | 部分覆盖 | 决策/待确认：有效时间、无默认归属；C-05 绑定写入幂等契约与 C-06 主数据边界 DEC-007/011。 |
| [ADR-017 资产与设备管理](../adr/ADR-017-asset-and-device-management.md) | 不适用 | 部分覆盖 | 一致 | 部分覆盖 | 一致 | 一致 | 决策/待确认：版本化校准与历史安装；C-02 仅部分表达现有 LocalDateTime（VAL-001），C-04 接入身份委托 ADR-013（DEC-002）。 |
| [ADR-018 商户及垃圾产生责任主体管理](../adr/ADR-018-merchant-and-waste-producer.md) | 不适用 | 一致 | 一致 | 不适用 | 部分覆盖 | 一致 | 决策/待确认：主体、商户和服务点分离；C-05 历史快照持久化待定 DEC-007/011。 |
| [ADR-019 清运线路管理](../adr/ADR-019-collection-route-management.md) | 不适用 | 一致 | 部分覆盖 | 不适用 | 部分覆盖 | 一致 | 决策/待确认：模板/版本隔离；C-03 重量统计口径、C-05 版本发布与计划快照待定 DEC-010/014。 |
| [ADR-020 作业计划与车辆调度](../adr/ADR-020-operation-planning-and-dispatch.md) | 不适用 | 一致 | 一致 | 不适用 | 部分覆盖 | 一致 | 决策/待确认：计划与任务及下发状态分离；C-05 唯一键与周期触发 DEC-011。 |
| [ADR-021 清运作业执行](../adr/ADR-021-collection-operation-execution.md) | 不适用 | 一致 | 一致 | 一致 | 一致 | 一致 | 决策：未匹配仍接入、父子防重、公开 API；任务与计费前置条件 DEC-011/012。 |
| [ADR-022 异常事件与告警管理](../adr/ADR-022-event-and-alarm-management.md) | 不适用 | 一致 | 一致 | 一致 | 部分覆盖 | 一致 | 决策/待确认：异常与告警状态独立；C-05 去重、规则版本及状态机未定 DEC-010/011。 |
| [ADR-023 末端处置设施与垃圾交付](../adr/ADR-023-disposal-facility-and-delivery.md) | 不适用 | 一致 | 一致 | 不适用 | 一致 | 一致 | 决策：P0 人工、运输批次与交付数据及重量边界；差异阈值与计费闸门 DEC-011/012。 |
| [ADR-024 合同与计费](../adr/ADR-024-contract-and-billing.md) | 不适用 | 部分覆盖 | 一致 | 一致 | 一致 | 不适用 | 决策/待确认：C-02 依有效时间但截止与账期未定；精确重量、消息确认与账单分离；计费/税费 DEC-012/013。 |
| [ADR-025 统计分析与报表](../adr/ADR-025-statistics-and-reporting.md) | 不适用 | 一致 | 一致 | 不适用 | 一致 | 一致 | 决策及22项清单：计量粒度、时间、权限与读契约；最终指标 DEC-014。 |
| [ADR-026 厨余垃圾专题地图](../adr/ADR-026-kitchen-waste-thematic-map.md) | 不适用 | 一致 | 一致 | 不适用 | 一致 | 一致 | 决策：已授权专题图层、收运非产生总量、去重；阈值和来源 DEC-009/010/014。 |
| [ADR-027 移动端与车载终端](../adr/ADR-027-mobile-and-vehicle-terminals.md) | 不适用 | 部分覆盖 | 部分覆盖 | 部分覆盖 | 不适用 | 一致 | 决策/待确认：C-02 离线时间与定位、C-03 展示和转发、C-04 缓存满载与终端协议待确认 DEC-001/003/004。 |
| [ADR-028 第三方系统集成](../adr/ADR-028-third-party-integration.md) | 不适用 | 一致 | 一致 | 一致 | 一致 | 一致 | 决策：外部确认与本地事务分离、P0 发票/P1 支付；第三方准入 DEC-013、BLOCK-006。 |
| [ADR-029 网络安全等级保护三级建设与安全合规](../adr/ADR-029-mlps-and-security-compliance.md) | 一致 | 不适用 | 不适用 | 一致 | 部分覆盖 | 部分覆盖 | 决策/待确认：等保建设目标；C-05 强制审计失败策略与 C-06 部署责任边界 DEC-015，VAL-006。 |
| [ADR-030 个人信息与数据保护](../adr/ADR-030-personal-information-and-data-protection.md) | 不适用 | 一致 | 不适用 | 不适用 | 部分覆盖 | 一致 | 决策/待确认：最小化、历史保护；C-05 归档、删除协调与对象存储范围 DEC-015。 |
| [ADR-031 身份认证与访问安全](../adr/ADR-031-authentication-and-access-security.md) | 不适用 | 部分覆盖 | 不适用 | 一致 | 不适用 | 一致 | 决策/待确认：C-02 Token 期限与撤销时效、C-04 用户和设备身份分离；GAP-003、BLOCK-004。 |
| [ADR-032 安全审计与操作追溯](../adr/ADR-032-security-audit-and-traceability.md) | 不适用 | 一致 | 一致 | 不适用 | 一致 | 一致 | 决策：关键审计证据随事务，180天候选；审计失败与防篡改 DEC-015。 |
| [ADR-033 密码体系与 IoT 设备安全](../adr/ADR-033-cryptography-and-iot-security.md) | 不适用 | 一致 | 不适用 | 一致 | 部分覆盖 | 一致 | 决策/待确认：UID 和 CRC 只作标识和差错检测；C-05 重放状态与原始事务耦合待确认 DEC-002/003。 |
| [ADR-034 国产化及信创兼容性](../adr/ADR-034-domestic-platform-compatibility.md) | 一致 | 一致 | 一致 | 一致 | 一致 | 一致 | 决策：兼容测试不得改变六项规范；兼容矩阵与产品/版本证据 VAL-006。 |
| [ADR-035 系统容量与性能指标](../adr/ADR-035-capacity-and-performance.md) | 部分覆盖 | 部分覆盖 | 一致 | 一致 | 一致 | 部分覆盖 | 决策/待确认：C-01 环境规格、C-02 窗口和频率、C-06 各模块资源与契约未定 DEC-016、VAL-005。 |
| [ADR-036 缓存与异步处理架构](../adr/ADR-036-cache-and-asynchronous-processing.md) | 不适用 | 一致 | 不适用 | 一致 | 一致 | 一致 | 决策：Redis 辅助、投递确认加路由、消费提交后确认；BLOCK-001、DEC-006/008。 |
| [ADR-037 高可用与灾难恢复](../adr/ADR-037-high-availability-and-disaster-recovery.md) | 不适用 | 部分覆盖 | 不适用 | 一致 | 部分覆盖 | 一致 | 决策/待确认：C-02 恢复点与测量窗口；C-05 恢复对账及重放边界，S0 范围见 DEC-016、BLOCK-007。 |
| [ADR-038 系统部署与环境管理](../adr/ADR-038-deployment-and-environment-management.md) | 部分覆盖 | 一致 | 不适用 | 不适用 | 一致 | 一致 | 来源/约束：C-01 工具链配置差距 GAP-001，部署形态和数据库迁移见 DEC-006/016。 |
| [ADR-039 系统可观测性与运维监控](../adr/ADR-039-observability-and-operations-monitoring.md) | 不适用 | 一致 | 不适用 | 一致 | 部分覆盖 | 一致 | 决策/待确认：ACK 三阶段与监控分层；C-05 对账口径涉及待选指标及关联键 DEC-016。 |
| [ADR-040 AI 辅助开发与工程管理](../adr/ADR-040-ai-assisted-development-and-engineering.md) | 一致 | 一致 | 一致 | 一致 | 一致 | 一致 | 决策/验证：六规范均纳入分支、变更及证据要求；分支保护实况 VAL-002。 |
