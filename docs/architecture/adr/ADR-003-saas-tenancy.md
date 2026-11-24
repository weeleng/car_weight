# ADR-003 SaaS 多租户架构

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`e7c025f623191928633dfc843c070e38d1cb4bca`
- 编号、标题和设计要点来自 ENG-001B-02C-01，候选映射与历史批准原文待核验。

## 决策来源

ENG-001B-02C-01 提供租户、监管和部署方案。已只读检查以下仓库源码及配置。

- [TenantController.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/tenant/TenantController.java)、[TenantContextHolder.java](../../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/context/TenantContextHolder.java)、[TenantContextWebFilter.java](../../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/web/TenantContextWebFilter.java)、[TenantSecurityWebFilter.java](../../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/security/TenantSecurityWebFilter.java)、[TenantVisitContextInterceptor.java](../../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/web/TenantVisitContextInterceptor.java)。现有租户管理 API 可复用。请求过滤器读取租户标识并清理上下文，登录用户经过租户身份匹配检查。访问租户拦截器另以框架权限控制上下文切换，该机制本身未证明存在项目级监管授权。
- [TenantDatabaseInterceptor.java](../../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/db/TenantDatabaseInterceptor.java)、[YudaoTenantAutoConfiguration.java](../../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/config/YudaoTenantAutoConfiguration.java)、[TenantBaseDO.java](../../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/db/TenantBaseDO.java)、[application.yaml](../../../yudao-server/src/main/resources/application.yaml)。服务端基线配置启用多租户，MyBatis-Plus 通过 `TenantLineInnerInterceptor` 使用上下文租户。配置、注解、忽略上下文及未识别表可影响拦截覆盖，业务查询仍需验证。
- [TenantUtils.java](../../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/util/TenantUtils.java)、[TenantRedisCacheManager.java](../../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/redis/TenantRedisCacheManager.java)、[TenantRedisMessageInterceptor.java](../../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/mq/redis/TenantRedisMessageInterceptor.java)、[TenantRabbitMQInitializer.java](../../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/mq/rabbitmq/TenantRabbitMQInitializer.java)、[TenantJobAspect.java](../../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/job/TenantJobAspect.java)。具备上下文执行与恢复、按租户区分适用缓存、Redis 消息拦截、RabbitMQ 发布侧处理器和租户任务切面。任意线程池、缓存键及项目消费者的覆盖范围仍待验证。

## 背景

同一平台服务环卫运营企业、政府监管机构和垃圾处理设施运营单位。租户可承担多个获授权业务角色，这些分类复用同一技术租户模型。垃圾产生责任主体默认属于业务档案范围。

## 决策

- 初始 SaaS 采用共享应用、共享数据库和租户标识隔离。C-05 的 INGEST、BUSINESS、TRACK 逻辑边界继续适用，共享方案的物理数据库部署待专项确定。
- 分别界定平台管理、租户数据、组织管理、项目业务及跨租户监管授权。每条业务数据应有明确归属。服务端查询、写入、导出、统计、缓存、异步任务和消息消费均落实租户上下文。
- 政府监管只访问明确获授权的运营项目范围，授权不改变原数据归属。默认禁止跨租户写入。跨租户业务协同需另行定义操作权限、业务边界与审计。
- 服务端按可信设备身份、注册关系及有效业务绑定确定 IoT 数据归属。设备转移、车辆调拨或项目变更保留已接收事件身份。无法确认归属时按接入鉴权和异常存证规则处理，未认证设备的行为由设备安全专项确定。
- 支持 SaaS 共享、基础私有化及高可用私有化。私有化复用租户与项目数据模型。

## 适用范围

服务端租户隔离、政府监管、设备归属及 SaaS 与私有化部署。

## 约束与禁止事项

禁止以客户端指定的租户标识作为可信授权，禁止用组织、项目或商户 ID 代替租户标识。禁止只依赖前端过滤、为普通监管忽略租户过滤或授予平台超级管理员权限。缓存须保留必要租户范围，异步处理须保留归属。禁止按当前设备绑定改写历史归属或将无可信归属的事件自动写入其他租户正式业务。

## 备选方案及取舍

独立实例或物理数据库隔离能提供更强隔离，但增加部署和运维成本。初始共享方案复用租户机制，并须按执行链路核验隔离。专属部署由专项评估。

## 与其他 ADR/ACR 的关系

延续 [ADR-001](ADR-001-system-architecture.md)与 [ADR-006](ADR-006-database-architecture.md)的部署和逻辑库边界。按 [ADR-012](ADR-012-iot-ingestion.md)与 [ADR-013](ADR-013-vwcp-protocol.md)分离接入提交、成功 L2 ACK 和设备原始身份，RabbitMQ 与 SQLite 双暂存冲突仍为生产启用阻断。项目和权限分别见 [ADR-004](ADR-004-organization-and-project.md)、[ADR-005](ADR-005-user-and-permissions.md)。适用 C-01、C-02、C-04、C-05、C-06，出处见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。

## 验证要求

后续覆盖跨租户查询、写入、导出、统计、缓存、异步消息、设备归属、监管授权撤销与私有化兼容性。核验请求结束与任务结束后上下文清理，以及绕过 MyBatis 默认路径时的授权边界。

## 待确认事项

物理隔离、独立实例、专属部署与监管授权数据模型待确认。项目级监管、设备归属及全链路租户隔离未完成运行验证。

## 批准记录

本草案待人工审查，历史批准原文、版本、证据、批准人及日期待取得。本次仅核查源码与文档，未执行运行验收。
