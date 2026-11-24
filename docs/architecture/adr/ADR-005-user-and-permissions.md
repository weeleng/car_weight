# ADR-005 用户与权限管理

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`e7c025f623191928633dfc843c070e38d1cb4bca`
- 编号、标题和设计要点来自 ENG-001B-02C-01，候选映射与历史批准原文待核验。

## 决策来源

ENG-001B-02C-01 提供项目级权限、终端身份、高风险操作和审计要求。已只读检查 [UserController.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/user/UserController.java)、[DeptController.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/dept/DeptController.java)、[RoleController.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/permission/RoleController.java)、[MenuController.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/permission/MenuController.java)、[PermissionController.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/permission/PermissionController.java)、[PermissionServiceImpl.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/permission/PermissionServiceImpl.java)、[DeptDataPermissionRule.java](../../../yudao-framework/yudao-spring-boot-starter-biz-data-permission/src/main/java/cn/iocoder/yudao/framework/datapermission/core/rule/dept/DeptDataPermissionRule.java)、[DataPermissionRuleHandler.java](../../../yudao-framework/yudao-spring-boot-starter-biz-data-permission/src/main/java/cn/iocoder/yudao/framework/datapermission/core/db/DataPermissionRuleHandler.java)、[SecurityFrameworkServiceImpl.java](../../../yudao-framework/yudao-spring-boot-starter-security/src/main/java/cn/iocoder/yudao/framework/security/core/service/SecurityFrameworkServiceImpl.java)。源码包含用户、部门、角色、菜单管理、角色菜单与用户角色分配、接口权限校验及按注册规则生成的部门或本人 SQL 数据范围。项目级授权与跨租户监管仍需业务设计和验证。

## 背景

菜单或接口权限与资源可见范围分别判断。运营人员、监管人员、设备终端和外部用户需要不同操作范围。

## 决策

- 复用 RuoYi-Vue-Pro 的用户、角色、菜单、权限标识及部门数据权限，在服务端叠加项目级权限与显式监管授权。校验区分用户、租户、角色功能、组织范围、项目范围、跨租户监管和具体资源条件，采用 RBAC 与业务数据范围结合，最小权限、默认拒绝。
- 候选角色包括平台管理员、企业租户管理员、企业运营、项目管理、调度、清运驾驶员、称重审核、财务及计费、政府监管、垃圾处理设施工作人员和商户端用户。角色编码可配置。
- 可授予一个或多个项目的查询、作业、车辆及设备、商户、称重审核、计费、统计与导出权限。项目可见性不授予财务、设备配置或称重修正权限。
- RFID 重绑、设备归属变更、人工修正称重、未知主体补录、计价调整、账单更正、发票红冲、跨租户监管授权和批量导出，各须独立权限及审计。
- 管理 Web、监管 Web、Android 车载终端、PDA 和商户端遵循统一授权原则。人员认证与设备认证分离。
- 关键审计项包括操作用户、时间、所属租户及项目、对象、操作类型、必要变更前后内容、结果和请求追踪标识。

## 适用范围

业务 API、项目及资源授权、用户与设备身份和关键操作审计。

## 约束与禁止事项

禁止以菜单可见性替代服务端授权，禁止将设备通信许可扩大为业务管理权限。高风险操作须经过独立校验。双人复核、审批流及职责分离待安全专项确认。

## 备选方案及取舍

仅沿用 RBAC 和部门过滤会缺少项目与资源上下文。拟复用通用角色机制，并在服务端增加项目、监管和资源条件，测试权限组合与撤销传播。

## 与其他 ADR/ACR 的关系

项目及租户边界见 [ADR-004](ADR-004-organization-and-project.md)与 [ADR-003](ADR-003-saas-tenancy.md)，接口授权遵循 [ADR-008](ADR-008-api-conventions.md)。用户认证、Token 生命周期、设备安全关联候选 [ADR-031](README.md#adr-031)和 [ADR-033](README.md#adr-033)，审计存储、保留和防篡改关联候选 [ADR-032](README.md#adr-032)，正文均待导入。适用 C-01、C-02、C-06，出处见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。

## 验证要求

后续覆盖角色、项目和跨租户越权、监管只读、导出限制、设备与用户身份混用、权限撤销和高风险审计。

## 待确认事项

权限标识、继承、组合与角色配置，人员和设备认证、Token 生命周期、审批机制及审计存储、保留和防篡改方案待确认。

## 批准记录

本草案待人工审查，历史批准原文、版本、证据、批准人及日期待取得。本次仅核查源码与文档，未执行运行验收。
