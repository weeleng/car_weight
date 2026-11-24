# ADR-004 组织与项目管理

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`e7c025f623191928633dfc843c070e38d1cb4bca`
- 编号、标题和设计要点来自 ENG-001B-02C-01，候选映射与历史批准原文待核验。

## 决策来源

ENG-001B-02C-01 提供项目、组织协同及授权建议。已读取 [DeptController.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/dept/DeptController.java)、[DeptDataPermissionRule.java](../../../yudao-framework/yudao-spring-boot-starter-biz-data-permission/src/main/java/cn/iocoder/yudao/framework/datapermission/core/rule/dept/DeptDataPermissionRule.java)，现有部门 CRUD 和部门数据范围可复用。环卫项目、运营范围和监管授权属于本项目设计。

## 背景

运营企业可管理多个项目，项目协作及调拨随时间变化。部门树、行政边界与实际服务范围表达不同事实。

## 决策

- 分别建模租户、组织、部门、环卫运营项目、行政区域和作业服务范围。优先复用组织及部门管理的适用能力，项目作为独立业务对象。
- 项目逻辑属性包含标识、名称、运营责任主体、所属租户、服务行政区域、作业服务范围、垃圾类别、有效期、状态、关联组织及授权监管机构。物理字段待设计。
- 车辆、设备、人员、商户服务关系、作业任务、称重记录和计费规则均可关联项目。允许多项目运营及多组织协作，须明确数据归属和各方权限。项目与组织不强制一对一，跨租户协作依照显式授权。
- 保存归属关系的必要有效期和变更历史。迟到事件按有效事件时间和历史关系解析，时间不可信时异常核验。已有称重与作业记录保持原项目，历史账单按当时关系和规则追溯。
- 政府可经授权监管一个或多个项目，范围可限定行政区域、数据类别、操作类型和有效期。授权、撤销及变更须审计。项目服务范围可关联 GIS 空间对象。

## 适用范围

组织与项目档案、关联历史、跨组织协同、监管授权及 GIS 关联。

## 约束与禁止事项

禁止用部门代替项目、用组织树代替行政区或服务范围、以协作隐式突破租户隔离。禁止把监管权限当作运营权限、将历史记录自动迁移或随当前配置重算历史账单。坐标转换方案留待专项确认。

## 备选方案及取舍

沿用部门树可减少建模，但无法表达跨组织运营与独立有效期。拟保留项目模型，并复用适用的部门能力。

## 与其他 ADR/ACR 的关系

租户与权限边界见 [ADR-003](ADR-003-saas-tenancy.md)及 [ADR-005](ADR-005-user-and-permissions.md)。设备和主体服务关系见 [ADR-017](ADR-017-asset-and-device-management.md)、[ADR-018](ADR-018-merchant-and-waste-producer.md)。行政区、电子围栏、作业区坐标系及空间计算关联 [ADR-009](ADR-009-gis-architecture.md)、[ADR-010](ADR-010-coordinate-system.md)、[ADR-011](ADR-011-geofencing.md)候选依赖，正文为 Proposed，待批准。适用 C-02、C-05、C-06，出处见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。

## 验证要求

后续覆盖多项目运营、车辆跨项目调拨、人员跨项目授权、监管范围限制、停用项目、迟到事件和历史归属，以及跨项目越权访问。

## 待确认事项

关系有效期与重叠规则、协作方权限、监管范围粒度及 GIS 空间方案待确认。

## 批准记录

本草案待人工审查，历史批准原文、版本、证据、批准人及日期待取得。本次仅核查源码与文档，未执行运行验收。
