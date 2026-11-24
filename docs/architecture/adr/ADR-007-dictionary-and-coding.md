# ADR-007 数据字典与基础编码

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`ecd6ed655cec214a0ed945b6e5f2e21d3dc71ee6`
- 编号、标题和设计要点来自 ENG-001B-02C-02，候选映射与历史批准原文待核验。

## 决策来源

ENG-001B-02C-02 提供拟议编码治理规则。已只读核查以下路径。

- [DictTypeController.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/dict/DictTypeController.java)、[DictDataController.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/dict/DictDataController.java)、[DictTypeServiceImpl.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/dict/DictTypeServiceImpl.java)、[DictDataServiceImpl.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/dict/DictDataServiceImpl.java)提供类型、数据的管理接口和服务层校验，字典值按类型校验唯一性。简表接口返回启用项，供管理端缓存。
- [DictTypeDO.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/dict/DictTypeDO.java)、[DictDataDO.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/dict/DictDataDO.java)、[TenantDatabaseInterceptor.java](../../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/db/TenantDatabaseInterceptor.java)显示类型和数据均标注 `@TenantIgnore`，现有查询不能直接作为租户级覆盖方案。
- [DictDataApiImpl.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/api/dict/DictDataApiImpl.java)、[DictFrameworkUtils.java](../../../yudao-framework/yudao-spring-boot-starter-excel/src/main/java/cn/iocoder/yudao/framework/dict/core/DictFrameworkUtils.java)、[CacheUtils.java](../../../yudao-framework/yudao-common/src/main/java/cn/iocoder/yudao/framework/common/util/cache/CacheUtils.java)、[README.md](../../../yudao-ui/yudao-ui-admin-vue3/README.md)显示后端可按类型读取字典，Excel starter 的进程内缓存以类型为键，配置一分钟后按访问触发异步刷新，提供显式清理入口。核查未发现字典写入直接调用该清理入口。前端 README 指向独立源码仓库，当前检出可确认简表 HTTP 契约，实际前端存储和刷新逻辑待核验。

## 背景

多个模块需要共享概念及稳定编码。租户配置、协议版本、计价规则和历史统计具有不同的变更权限和生效语义。

## 决策

- 优先复用框架字典。分别管理平台公共字典、环卫行业基础字典、租户可配置字典、项目业务配置、程序状态机与协议枚举、独立业务主数据。
- 候选分类包括垃圾类别、垃圾产生主体、服务点、车辆、称重机构、设备、作业、异常、告警等级、处置设施、定位来源、围栏及清运线路类型。编码和值留待详细设计。
- 垃圾类别支持城市生活垃圾分类及获准扩展。分别表达行业标准分类、地方管理分类、项目服务类别、设备上报类别及计费适用类别，明确分类映射与计价规则关联。
- 编码保持稳定语义，展示名称按授权调整。已被引用的编码支持停用和必要版本追溯。政府统计、合同和计费保留必要的历史语义。公共、租户和项目配置各自明确归属，查询、缓存与刷新落实租户隔离。

## 适用范围

公共与环卫基础编码、配置归属、缓存和历史追溯。

## 约束与禁止事项

禁止以普通字典承载所有配置、把设备枚举等同完整业务分类、把单价存入字典标签、重赋已用编码语义或随意删除。租户私有字典不得改变公共协议和业务约束。设备命令码、ACK 状态码、称重审核、作业、账单、发票的状态转换和安全权限编码不得由字典任意修改。展示名称变更不授予状态转换权限。

## 备选方案及取舍

在公共表中直接覆盖租户值虽然易于复用接口，却会降低隔离与历史可追溯性。拟保留公共机制，租户覆盖和独立主数据另行建模并验证缓存边界。

## 与其他 ADR/ACR 的关系

租户隔离见 [ADR-003](ADR-003-saas-tenancy.md)，协议枚举见 [ADR-013](ADR-013-vwcp-protocol.md)，计价规则见 [ADR-024](ADR-024-contract-and-billing.md)。遵循 [C-06](../standards/module-boundaries.md)，出处见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。

## 验证要求

后续覆盖公共查询、租户隔离、缓存刷新、编码冲突、停用、历史追溯、标签变更、非法状态转换和垃圾类别与计价规则的关联。

## 待确认事项

分类编码与映射、历史版本模型、租户覆盖设计、前端刷新及多节点缓存一致性待确认。

## 批准记录

草案待人工审查。历史批准原文、版本、批准人、日期及证据待取得。本次仅核查源码及文档，未运行功能或供应商验证。
