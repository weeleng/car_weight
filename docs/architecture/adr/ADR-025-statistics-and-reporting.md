# ADR-025 统计分析与报表

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`e010b328ccca9c89c2f52841414e9b1d9843d045`
- 编号、标题及设计要点来自 ENG-001B-02C-04，候选映射与历史批准原文待核验。

## 决策来源

ENG-001B-02C-04 提供报告候选范围。已只读核查 [ExcelUtils.java](../../../yudao-framework/yudao-spring-boot-starter-excel/src/main/java/cn/iocoder/yudao/framework/excel/core/util/ExcelUtils.java)、[PageResult.java](../../../yudao-framework/yudao-common/src/main/java/cn/iocoder/yudao/framework/common/pojo/PageResult.java)、[BaseMapperX.java](../../../yudao-framework/yudao-spring-boot-starter-mybatis/src/main/java/cn/iocoder/yudao/framework/mybatis/core/mapper/BaseMapperX.java)、[DeptDataPermissionRule.java](../../../yudao-framework/yudao-spring-boot-starter-biz-data-permission/src/main/java/cn/iocoder/yudao/framework/datapermission/core/rule/dept/DeptDataPermissionRule.java)、[TenantContextHolder.java](../../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/context/TenantContextHolder.java)、[TenantRedisCacheManager.java](../../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/redis/TenantRedisCacheManager.java)，框架具备通用 Excel 读写、分页、部门数据权限、租户上下文及适用的租户缓存机制。通用能力仍需接入环卫指标和导出授权。根 POM 的 report 模块和 Server 对该模块的依赖均未纳入默认装配。

## 背景

主体、服务点、车辆、作业、批次和设施各有正式业务数据。报表、运营大屏、政府监管及地图应使用一致的指标与权限。

## 决策

- 由 `sanitation-analysis` 提供统计。通过公开 API、业务事件或获准的只读数据契约读取正式事实，遵循 C-05 的逻辑库和事务边界。定义共享指标编码、名称、业务定义、来源、对象、公式、单位、时间口径、维度、纳入及排除条件、更新时间、版本和适用权限。报表、地图、大屏及第三方统计共用定义。
- 区分原始称重、正式有效收运、审核、计费、设施接收净重及审核交付重量。按 C-03 的明确单位和精确十进制汇总，不跨计量环节直接相加。240L 的组合记录与有效子桶只按确定粒度取一次。无法独立归属的组合重量可保留于适用车辆或批次统计，禁止随意分给商户。
- 区分事件发生、接收、持久化、业务确认、计费期间和统计生成时间。日、周、月按明确业务时区计算，默认 Asia/Shanghai，遵循 C-02。保留迟到补传、版本和重算的可辨识性。
- 分列计划、下发、已接收、完成、取消和异常终止任务数，分别计计划站点和实际完成站点。任务完成率与站点服务完成率使用各自明确的分母。围栏到达及车辆在线率不替代正式作业完成。
- P0 优先评估 MySQL 范围查询、索引、分页、必要汇总表、Quartz 周期任务、增量聚合及 Redis 短期缓存。增量以 Outbox/Inbox 与幂等消费保证可恢复。允许可辨识的最终一致性，提供更新时间、更新状态与来源，支持重算及核对。财务结果对应正式账单及调整。
- Excel 导出复用框架接口，后续加入权限校验、条件快照、大数据异步任务与状态、授权文件下载、有效期、脱敏、审计及公式注入防护。监管统计按获授权项目、区域、类别和时间执行，聚合、下钻、导出和地图一致。

### 22 项 P0 候选重建清单

本清单根据任务材料重建，历史批准原文待核验。

| 编号 | 类别 | 报表 |
| --- | --- | --- |
| 1 | 基础业务 | 垃圾产生责任主体统计 |
| 2 | 基础业务 | 服务点及服务覆盖统计 |
| 3 | 基础业务 | 项目基础运营统计 |
| 4 | 收运计量 | 垃圾收运重量日报 |
| 5 | 收运计量 | 垃圾收运重量月报 |
| 6 | 收运计量 | 按垃圾类别收运重量统计 |
| 7 | 收运计量 | 按行政区域收运重量统计 |
| 8 | 收运计量 | 按垃圾产生责任主体收运重量统计 |
| 9 | 收运计量 | 按服务点收运重量统计 |
| 10 | 车辆作业 | 车辆收运量统计 |
| 11 | 车辆作业 | 清运线路执行统计 |
| 12 | 车辆作业 | 作业任务完成情况统计 |
| 13 | 车辆作业 | 站点服务完成情况统计 |
| 14 | 车辆作业 | 车辆运行里程及作业时长统计 |
| 15 | 末端处置 | 运输批次交付统计 |
| 16 | 末端处置 | 处置设施接收重量统计 |
| 17 | 末端处置 | 收运与交付重量差异统计 |
| 18 | 异常监管 | 称重及 RFID 异常统计 |
| 19 | 异常监管 | 设备运行及故障统计 |
| 20 | 异常监管 | 告警与异常处理情况统计 |
| 21 | 合同财务 | 垃圾处理费计费及账单统计 |
| 22 | 合同财务 | 发票及费用结算状态统计 |

## 适用范围

运营、政府监管、专题地图、大屏和外部统计接口的查询与导出。

## 约束与禁止事项

禁止跨模块读取内部 Mapper、DO、Biz，禁止越过逻辑库边界、用缓存代替正式事实、按数据库默认时区分期或为统计修正原始记录。禁止将收运量等同总产生量、独立页面重定义公式、用聚合或差值泄露未获授权数据。文件通用权限不能替代报表授权，禁止全量租户越权导出。P1 支付未启用时不得生成虚假支付成功状态。

## 备选方案及取舍

P0 优先复用 MySQL，按负载比较按需查询与汇总。ClickHouse、Elasticsearch、独立仓库及大数据平台均非 P0 必需，按实际规模、性能和成本复评。

## 与其他 ADR/ACR 的关系

时间、重量、事务和模块遵循 [C-02](../standards/time-conventions.md)、[C-03](../standards/weight-conventions.md)、[C-05](../standards/transaction-boundaries.md)、[C-06](../standards/module-boundaries.md)。对齐 [ADR-015](ADR-015-weighing-model.md)、[ADR-020](ADR-020-operation-planning-and-dispatch.md)、[ADR-021](ADR-021-collection-operation-execution.md)、[ADR-023](ADR-023-disposal-facility-and-delivery.md)、[ADR-024](ADR-024-contract-and-billing.md)及 [ADR-026](ADR-026-kitchen-waste-thematic-map.md)。证据见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。

## 验证要求

覆盖父子防重、收运及交付、跨日跨月、迟到和重算、重复消费、各完成率、地图和财务对账、跨租户监管、异步导出与文件越权、缓存更新和失效。

## 待确认事项

报表名称、字段、筛选、展示与最终 P0 范围，历史重算、报表修订、结账调整及性能容量待确认。

## 批准记录

草案待人工审查。历史批准原文、版本、批准人、日期及证据待取得。本次仅核查源码与文档，未执行运行验收。
