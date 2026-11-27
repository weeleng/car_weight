# ENG-002D-01 多租户、项目组织与基础权限体系详细设计

状态为候选设计，待人工审批。范围限于工作包 D 的详细设计和隔离探针。DEC-007、DEC-008、BLOCK-004、BLOCK-005 保持开放。

## 1. 基线与架构依据

仓库 `weeleng/car_weight`，任务分支 `engineering/eng-002d`，从 `origin/master-jdk17` 实际起点 `e5a4dbd434a570a14c47195b6fa77debd6d70a44` 派生。执行 `git fetch --no-tags origin refs/heads/master-jdk17:refs/remotes/origin/master-jdk17`、`git ls-remote origin refs/heads/master-jdk17 refs/heads/engineering/eng-002d`、`git rev-parse HEAD`。远程与任务指定 SHA 一致，前置 PR #15、#16、#17 的工程结果位于该基线。工作区已有未跟踪环境报告保留在提交之外。

2026-11-26（Asia/Shanghai）核查 PR #17 合并后的 Actions。API 返回 Forbidden，GitHub 网页返回 HTTP 200，标题对应合并提交 `e5a4dbd`，页面状态为 **Success**，Job ID `139741786640`，网页总用时 5 分 35 秒。基线 [Maven 工作流](../../.github/workflows/maven.yml) 使用 JDK 21、Maven 3.9.12，执行 `mvn -B -ntp -C package --file pom.xml -Dmaven.test.skip=true`。该证据覆盖历史默认构建，本轮未执行完整 Reactor 或 CI 验收。[Actions 45770524540](https://github.com/weeleng/car_weight/actions/runs/45770524540)

| 依据 | 本设计引用的范围 |
| --- | --- |
| [AGENTS](../../AGENTS.md)、[架构目录](../architecture/README.md)、[Codex 工作流](codex-workflow.md)、[验证矩阵](validation-matrix.md) | 独立分支、文档交付和证据要求 |
| [C-01](../architecture/standards/technology-baseline.md)、[C-02](../architecture/standards/time-conventions.md)、[C-05](../architecture/standards/transaction-boundaries.md)、[C-06](../architecture/standards/module-boundaries.md) | JDK 21 / Java release 17，绝对时间 UTC，本地事务、公开 API 及事件边界 |
| [实施入口](../architecture/reviews/implementation-entry-gates.md)、[审批包](../architecture/reviews/decision-approval-packets.md)、[开放决策](../architecture/reviews/open-decision-register.md)、[差距](../architecture/reviews/implementation-gap-register.md)、[生产门槛](../architecture/reviews/production-gate-approval-matrix.md) | DEC-007/008 候选，GAP-002/003/004 和 BLOCK-004/005 的适用范围 |
| [ENG-002B 设计](ENG-002B-module-skeleton-design.md)、[ENG-002B 报告](ENG-002B-module-skeleton-report.md)、[模块骨架](../../sanitation-modules/README.md) | 44 项目、15 个空 JAR、六条自身 API 依赖、候选边未启用 |
| [ENG-002C 设计](ENG-002C-database-foundation-design.md)、[ENG-002C 报告](ENG-002C-database-foundation-report.md) | 三库独立验证和现有 RuoYi Bean 图，正式环卫数据源装配待批准 |

通过 [ADR 索引](../architecture/adr/README.md) 核对以下 **Proposed** 草案正文，历史批准原文待核验。

- [ADR-003](../architecture/adr/ADR-003-saas-tenancy.md)、[ADR-004](../architecture/adr/ADR-004-organization-and-project.md)、[ADR-005](../architecture/adr/ADR-005-user-and-permissions.md)、[ADR-007](../architecture/adr/ADR-007-dictionary-and-coding.md) 提供租户、组织、角色及编码的候选约束。
- [ADR-016](../architecture/adr/ADR-016-rfid-identification.md)、[ADR-017](../architecture/adr/ADR-017-asset-and-device-management.md)、[ADR-018](../architecture/adr/ADR-018-merchant-and-waste-producer.md)、[ADR-021](../architecture/adr/ADR-021-collection-operation-execution.md) 为历史绑定、责任主体和作业授权提供候选场景。
- [ADR-025](../architecture/adr/ADR-025-statistics-and-reporting.md)、[ADR-026](../architecture/adr/ADR-026-kitchen-waste-thematic-map.md)、[ADR-031](../architecture/adr/ADR-031-authentication-and-access-security.md)、[ADR-036](../architecture/adr/ADR-036-cache-and-asynchronous-processing.md) 提供统计、地图、安全、缓存和异步候选场景。

相邻审批范围依照开放决策登记。DEC-002 保留设备认证、生命周期、补传和事件身份的验证及审批。DEC-011 保留领域所有权及状态机。DEC-014 保留 22 项正式指标口径。DEC-015 保留关键审计、保留期限和防篡改实现。DEC-017 保留错误码、版本及外部线格式。本文的候选操作、字段和内部错误分类可用于隔离夹具。

## 2. RuoYi 源码核查

本表是对现有源码、配置和历史构件的核查。证据链接指向实际仓库文件。默认 Server 装配 System、Infra，IoT 与其他可选模块的代码另行标明。安全版本依据 Boot 3.5.16 BOM、历史 Server JAR 内 `spring-security-{config,core,crypto,web}-6.5.11.jar` 交叉确认。

| 项 | 源码行为与可复用能力 | 环卫差距、风险及候选措施 | 证据 |
| --- | --- | --- | --- |
| 01 过滤链 | `filterChain` 无状态，将 Token 过滤器置于用户名密码过滤器前，`@PermitAll`/配置例外之外要求认证 | 入口认证可复用。新入口要同时检查方法及对象级权限。`application-local.yaml` 启用 mock 登录，部署形态需验证不加载该能力及不暴露匿名例外 | [YudaoWebSecurityConfigurerAdapter](../../yudao-framework/yudao-spring-boot-starter-security/src/main/java/cn/iocoder/yudao/framework/security/config/YudaoWebSecurityConfigurerAdapter.java)、[YudaoSecurityAutoConfiguration](../../yudao-framework/yudao-spring-boot-starter-security/src/main/java/cn/iocoder/yudao/framework/security/config/YudaoSecurityAutoConfiguration.java)、[TokenAuthenticationFilter](../../yudao-framework/yudao-spring-boot-starter-security/src/main/java/cn/iocoder/yudao/framework/security/core/filter/TokenAuthenticationFilter.java) |
| 02 登录和 Token | `login/authenticate` 校验验证码、口令及状态，签发 OAuth2 Token，另有短信、扫码及社交登录入口。过滤器调用 Token API 并核对可判定的 userType | `getAccessToken` 按 Redis、访问令牌表、未过期刷新令牌回退，回填兼容缓存。按字符串登出找不到访问令牌即返回。新高风险入口候选使用 access-only 校验，兼容、停用、跨节点回填和长连接专项回归，保持 BLOCK-004 | [AdminAuthServiceImpl](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/auth/AdminAuthServiceImpl.java)、[TokenAuthenticationFilter](../../yudao-framework/yudao-spring-boot-starter-security/src/main/java/cn/iocoder/yudao/framework/security/core/filter/TokenAuthenticationFilter.java)、[OAuth2TokenServiceImpl](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/oauth2/OAuth2TokenServiceImpl.java)、[OAuth2AccessTokenRedisDAO](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/redis/oauth2/OAuth2AccessTokenRedisDAO.java) |
| 03 认证主体 | `LoginUser` 持有 id、userType、原租户、scopes、用户信息、到期及访问租户/临时 context。通过 SecurityContext 访问 | 只投影服务端身份及必要会话标识。可变 context 和 visit 租户不能承担项目授权，日志勿记录原始 Token | [LoginUser](../../yudao-framework/yudao-spring-boot-starter-security/src/main/java/cn/iocoder/yudao/framework/security/core/LoginUser.java)、[SecurityFrameworkUtils](../../yudao-framework/yudao-spring-boot-starter-security/src/main/java/cn/iocoder/yudao/framework/security/core/util/SecurityFrameworkUtils.java) |
| 04 租户识别 | `TenantContextWebFilter` 先读取请求租户并在 finally 清理，`TenantContextHolder` 有租户和 ignore 两个 TTL 状态 | 当前上下文可给现有 RuoYi 路径使用。环卫缺少可信上下文即拒绝，防止遗留 ignore 或异步继承 | [TenantContextWebFilter](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/web/TenantContextWebFilter.java)、[TenantContextHolder](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/context/TenantContextHolder.java) |
| 05 SQL 拦截 | `YudaoTenantAutoConfiguration` 插入 `TenantLineInnerInterceptor`，handler 取必需租户，配置、ignore 标志、注解及未登记表影响覆盖 | 使用已登记普通表的过滤能力，环卫 SQL 仍必须加显式 tenant/project 限制，验证 JOIN、聚合与原生 SQL | [YudaoTenantAutoConfiguration](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/config/YudaoTenantAutoConfiguration.java)、[TenantDatabaseInterceptor](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/db/TenantDatabaseInterceptor.java) |
| 06 请求头及切换 | `tenant-id` 与登录主体租户比对，未给可回取主体租户。MVC `visit-tenant-id` 经 `system:tenant:visit` 可切换，完成后恢复 Holder，外层过滤器清理 | 访问拦截器本身不重新验证目标租户有效性或项目范围，且主体含访问租户字段。环卫忽略通用切换结果，用显式授权和目标租户复核 | [WebFrameworkUtils](../../yudao-framework/yudao-spring-boot-starter-web/src/main/java/cn/iocoder/yudao/framework/web/core/util/WebFrameworkUtils.java)、[TenantSecurityWebFilter](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/security/TenantSecurityWebFilter.java)、[TenantVisitContextInterceptor](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/web/TenantVisitContextInterceptor.java) |
| 07 租户状态 | `validTenant` 检查缺失、停用、过期，Framework Service 有本地异步刷新缓存，忽略 URL 不做相同检查 | 复用租户真值来源，普通缓存不能作为撤权 SLA 依据，高风险核查主体与目标的当前状态 | [TenantServiceImpl](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/tenant/TenantServiceImpl.java)、[TenantFrameworkServiceImpl](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/service/TenantFrameworkServiceImpl.java) |
| 08 `TenantIgnore` | 方法/类由 Aspect 临时置 ignore 并恢复，DO 注解经元数据识别，Controller 注解可进入租户忽略 URL 收集 | 逐项盘点见第 7 节。只容许审批后的基础设施例外 | [TenantIgnoreAspect](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/aop/TenantIgnoreAspect.java)、[YudaoTenantAutoConfiguration](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/config/YudaoTenantAutoConfiguration.java) |
| 09 忽略配置 | 基线 `yudao.tenant.enable: true`，`ignore-urls` 有 `/jmreport/*`，`ignore-visit-urls` 有 profile 和 auth 范围，`ignore-tables` 空，handler 还内置 DUAL | 统计注解、配置及实际装配的并集，防止 Profile 或路径通配扩大豁免 | [Server application.yaml](../../yudao-server/src/main/resources/application.yaml#L370)、[TenantProperties](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/config/TenantProperties.java) |
| 10 部门组织 | `DeptDO` 部门父子关系及状态，`AdminUserDO` 有 deptId，`DeptServiceImpl` 核查层级及子部门 | 复用组织身份与人员信息。多项目人员、独立生命周期及项目授权由环卫建模 | [DeptDO](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/dept/DeptDO.java)、[AdminUserDO](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/user/AdminUserDO.java)、[DeptServiceImpl](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/dept/DeptServiceImpl.java) |
| 11 用户角色 | `assignUserRole` 更新用户与角色关联，读取在权限判断中剔除停用角色 | 复用功能入口权限。项目成员角色独立，并校验当前状态及授权版本 | [UserRoleDO](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/permission/UserRoleDO.java)、[PermissionServiceImpl](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/permission/PermissionServiceImpl.java) |
| 12 角色菜单 | `assignRoleMenu` 管理角色菜单授予，菜单码经 Permission Service 判定，超级管理员可通过功能判断 | 菜单用于导航与功能门槛。平台超级管理员也要走项目/对象校验 | [RoleMenuDO](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/permission/RoleMenuDO.java)、[MenuDO](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/permission/MenuDO.java)、[PermissionServiceImpl](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/permission/PermissionServiceImpl.java) |
| 13 方法级权限 | `@EnableMethodSecurity` 与 `@PreAuthorize` 调用 `@ss.hasPermission`，另有角色及 scope 判断 | 校验须位于可代理入口及领域执行点。测试自调用、后台入口及访问租户路径 | [YudaoSecurityAutoConfiguration](../../yudao-framework/yudao-spring-boot-starter-security/src/main/java/cn/iocoder/yudao/framework/security/config/YudaoSecurityAutoConfiguration.java)、[SecurityFrameworkServiceImpl](../../yudao-framework/yudao-spring-boot-starter-security/src/main/java/cn/iocoder/yudao/framework/security/core/service/SecurityFrameworkServiceImpl.java)、[UserController](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/user/UserController.java) |
| 14 数据权限拦截 | `@DataPermission` 控制规则选择，`DataPermissionRuleHandler` 拼接已注册规则，自动配置装配 MyBatis-Plus 数据权限拦截 | 保留现有规则，对环卫对象使用显式项目谓词。核查未注册表、禁用注解、更新删除及自定义查询的覆盖 | [YudaoDataPermissionAutoConfiguration](../../yudao-framework/yudao-spring-boot-starter-biz-data-permission/src/main/java/cn/iocoder/yudao/framework/datapermission/config/YudaoDataPermissionAutoConfiguration.java)、[DataPermissionRuleFactoryImpl](../../yudao-framework/yudao-spring-boot-starter-biz-data-permission/src/main/java/cn/iocoder/yudao/framework/datapermission/core/rule/DataPermissionRuleFactoryImpl.java)、[DataPermissionRuleHandler](../../yudao-framework/yudao-spring-boot-starter-biz-data-permission/src/main/java/cn/iocoder/yudao/framework/datapermission/core/db/DataPermissionRuleHandler.java) |
| 15 部门数据权限 | `getDeptDataPermission` 合并角色的全量、指定部门、本部门、子部门、本人范围。`DeptDataPermissionRule` 仅命中注册表及适用主体，结果放入 LoginUser context | 保留 RuoYi 部门规则。环卫对象如适用部门约束，部门和项目谓词取交集。角色可见部门不授予项目 | [PermissionServiceImpl](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/permission/PermissionServiceImpl.java)、[DeptDataPermissionRule](../../yudao-framework/yudao-spring-boot-starter-biz-data-permission/src/main/java/cn/iocoder/yudao/framework/datapermission/core/rule/dept/DeptDataPermissionRule.java)、[DataPermissionConfiguration](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/framework/datapermission/config/DataPermissionConfiguration.java) |
| 16 Redis 缓存键 | Spring Cache 管理器为适用 cache 加当前租户后缀。ignore、缺失租户及豁免缓存使用原名。现有用户角色、权限菜单等使用用户 ID 或权限码，Token DAO 独立键 | `ignore-caches` 含 `user_role_ids`、`permission_menu_ids`、`oauth_client` 等。项目范围使用专门的双租户、版本和操作键，禁止无范围退化 | [TenantRedisCacheManager](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/redis/TenantRedisCacheManager.java)、[OAuth2AccessTokenRedisDAO](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/redis/oauth2/OAuth2AccessTokenRedisDAO.java)、[缓存配置](../../yudao-server/src/main/resources/application.yaml#L378) |
| 17 权限缓存刷新 | `assignUserRole`、`assignRoleMenu`、角色状态及范围更新走 `@CacheEvict`。批量菜单角色项清理与单键失效并存 | 复用现有失效入口作为身份兼容证据，仍需证明提交后版本检查、并发回填、通知丢失和多节点撤权 | [PermissionServiceImpl](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/permission/PermissionServiceImpl.java)、[RoleServiceImpl](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/permission/RoleServiceImpl.java)、[MenuServiceImpl](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/permission/MenuServiceImpl.java) |
| 18 MQ | Redis 发送侧写头，消费前建立、消费后清理。Rabbit 初始化器给 RabbitTemplate 挂发布处理器。租户 starter 还提供覆盖 Spring 同名类的 InvocableHandlerMethod，消息头存在时调用 TenantUtils.execute，缺头时直接执行。IoT 可选总线另有独立订阅者及确认流程 | 消息头传播不提供身份鉴别，替代类在不同加载顺序及直接 Channel 消费中的覆盖需验证。环卫消费者必须以服务身份及持久业务归属重建范围 | [TenantRedisMessageInterceptor](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/mq/redis/TenantRedisMessageInterceptor.java)、[TenantRabbitMQInitializer](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/mq/rabbitmq/TenantRabbitMQInitializer.java)、[TenantRabbitMQMessagePostProcessor](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/mq/rabbitmq/TenantRabbitMQMessagePostProcessor.java)、[InvocableHandlerMethod](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/org/springframework/messaging/handler/invocation/InvocableHandlerMethod.java#L121)、[IotRabbitMQMessageBus](../../yudao-module-iot/yudao-module-iot-core/src/main/java/cn/iocoder/yudao/module/iot/core/messagebus/core/rabbitmq/IotRabbitMQMessageBus.java) |
| 19 Quartz | `JobHandlerInvoker` 调 handler，`@TenantJob` 切面从框架服务取租户列表，经 `parallelStream` 与 `TenantUtils.execute` 执行并汇总 | 租户遍历可供现有任务参考。环卫任务逐次检查状态和项目，隔离异常、重试与执行主体 | [JobHandlerInvoker](../../yudao-framework/yudao-spring-boot-starter-job/src/main/java/cn/iocoder/yudao/framework/quartz/core/handler/JobHandlerInvoker.java)、[TenantJobAspect](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/job/TenantJobAspect.java)、[TenantUtils](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/util/TenantUtils.java) |
| 20 线程池 | SecurityContext 有 TTL 策略，Spring 异步配置给 `ThreadPoolTaskExecutor` 与 `SimpleAsyncTaskExecutor` 增设 TTL decorator | 任意 Executor、第三方回调和长期任务不保证覆盖，TTL 可带出可变主体及 ignore。环卫用最小任务凭据重建并清理 | [YudaoAsyncAutoConfiguration](../../yudao-framework/yudao-spring-boot-starter-job/src/main/java/cn/iocoder/yudao/framework/quartz/config/YudaoAsyncAutoConfiguration.java)、[TransmittableThreadLocalSecurityContextHolderStrategy](../../yudao-framework/yudao-spring-boot-starter-security/src/main/java/cn/iocoder/yudao/framework/security/core/context/TransmittableThreadLocalSecurityContextHolderStrategy.java) |
| 21 文件 | Infra 上传、预签名、建档提供通用存储能力。列表与删除有菜单权限。下载按 configId/path 取流，带 PermitAll/TenantIgnore；S3 有签名或公开路径配置 | 通用路径无环卫对象绑定，私有证据使用隔离存储、受控下载及重新授权。部署存储、反代和凭证可达性待验证，保持 BLOCK-005 | [FileController](../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/controller/admin/file/FileController.java)、[FileServiceImpl](../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/service/file/FileServiceImpl.java)、[S3FileClient](../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/framework/file/core/client/s3/S3FileClient.java)、[FileDO](../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/dal/dataobject/file/FileDO.java) |
| 22 审计 | 登录成功/失败等记录，`@LogRecord` 通过 `LogRecordServiceImpl`，另有 HTTP access/error 日志及异步存储路径 | 复用诊断入口。关键授权变更与证据同所属事务或保存可重建记录，读审计有独立权限，密钥与完整文件地址不入日志 | [AdminAuthServiceImpl](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/auth/AdminAuthServiceImpl.java)、[LogRecordServiceImpl](../../yudao-framework/yudao-spring-boot-starter-security/src/main/java/cn/iocoder/yudao/framework/operatelog/core/service/LogRecordServiceImpl.java)、[OperateLogServiceImpl](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/logger/OperateLogServiceImpl.java)、[ApiAccessLogFilter](../../yudao-framework/yudao-spring-boot-starter-web/src/main/java/cn/iocoder/yudao/framework/apilog/core/filter/ApiAccessLogFilter.java) |
| 23 TOTP/MFA | System/Security Java 定向搜索 `totp/mfa/多因素/二次认证` 无匹配，现有登录实现见口令、验证码及可选登录流程 | 尚未确认已装配 TOTP 或动作级 step-up。候选外部 IdP 或单独受控实现，短信登录及验证码不能作为已验证高风险二次认证证据 | [AdminAuthServiceImpl](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/auth/AdminAuthServiceImpl.java)、[AuthController](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/auth/AuthController.java) |
| 24 导出和水印 | 用户导出使用功能权限、用户列表查询与 ExcelUtils 写响应。Server YAML 的 water-mark 为验证码设置，其他可选 AI 水印用途不同 | 复用格式生成。未见该导出路径具有动作级身份水印、执行与下载复核或项目任务模型，环卫需独立实施与验证 | [UserController](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/user/UserController.java)、[ExcelUtils](../../yudao-framework/yudao-spring-boot-starter-excel/src/main/java/cn/iocoder/yudao/framework/excel/core/util/ExcelUtils.java)、[验证码配置](../../yudao-server/src/main/resources/application.yaml#L111) |

## 3. 租户类型与能力

推荐复用 RuoYi 租户身份、有效期、启停、套餐与基础用户管理，在 BUSINESS 设候选能力扩展。使用稳定技术租户 ID 关联，能力允许并集，操作仍取权限交集。扩展未配置、失效或读取失败时拒绝相应环卫操作。启停、到期、重新启用均按状态及版本重新校验，重新启用不自动恢复已撤销授权。

| 候选类型 | 可配置能力 | 独立授权要求 |
| --- | --- | --- |
| 运营型 | 运营项目、作业、资产使用及内部结算 | 通过本租户项目成员资格、功能权限和对象范围执行 |
| 监管型 | 监管查看、审计申请、有限监管操作候选 | 显式跨租户授权，审查数据类型、操作、项目、时间和目标有效性 |
| 处置型 | 在获授权范围内收运交接、处置确认和对应资料 | 受控交付关联及项目授予，财务和原始计量另行授权 |
| 综合型 | 同法人拥有多种职责，配置多个能力，可按部门和项目分工 | 按实际角色、操作和目标重新授权，财务和审核可实施职责分离 |

直接在 RuoYi 租户表加字段会耦合上游迁移，单用套餐可隐藏功能但不适合表达法人多责、失效及授权依据。推荐独立扩展及多能力配置，不能修改租户核心结构。多个运营与监管主体可通过项目协议关联，独立法人对同一项目的权利各自记载。可在合规要求下为同法人设置多个技术租户，数据与角色按各租户隔离。租户管理员只管理自身组织及授权上限内的项目权限，平台管理员管理租户、配置及申请审批流程。跨租户业务读写需独立、限时和审计的授予，平台运维默认无全量读取。

## 4. 项目与部门

| 维度 | A 独立项目 | B 部门即项目 | C 独立项目＋受控部门映射 |
| --- | --- | --- | --- |
| 生命周期与停用 | 独立开工、停用、关闭，保存历史 | 与组织调岗、部门删除同步，冲突大 | 项目独立，映射可失效 |
| 多项目人员 | 支持成员多对多及作用域角色 | 用户主要部门难表达多项目 | 仍按成员明确授予，映射辅助导入 |
| 部门关系 | 组织与项目由独立维度表达 | 复用部门树，业务地理与管理层级易混用 | 可明确部门职责或委派入口 |
| 隔离及监管 | 以项目所有租户和显式项目集合校验 | 部门和租户边界难表达监管授权 | 映射不产生监管权限，仍需项目授权 |
| 合同及运营关系 | 项目独立关联可更新的运营主体及合同 | 需补额外实体，历史口径难关联 | 同 A，部门用于协助办理 |
| 历史与升级 | 可版本化，RuoYi 升级影响小 | 部门变更影响历史且依赖升级行为 | 独立主体稳定，另需映射兼容与失效测试 |

推荐 **A**。确认存在组织协助录入、筛选或委派需求后可引入 **C**，映射需审核、有效期和撤销，部门变更不隐式授予项目。项目稳定标识和所属租户由服务端确定。主体所在租户与项目所属租户应分别存储及验证。跨租户人员不得凭本租户成员记录进入别的租户项目。项目停用或关闭后禁止新增作业、派单和默认变更，有历史查询资格的用户可读取所授权时段，经授权的纠错或归档处理走专项流程。

| 候选概念 | 身份和边界 |
| --- | --- |
| Project | 所属租户、稳定编号、状态、生命周期。候选编号唯一域另审 |
| ProjectMember | 人员主体、原租户、项目、成员有效期与状态。多项目记录独立 |
| ProjectRole | 项目作用域角色或可复用角色模板，授予地和适用范围明确 |
| ProjectRolePermission | 角色到业务资源种类/操作的映射，版本可追溯 |
| ProjectGrant | 同租户成员/角色授予或有批准依据的特定协作授权，约束项目、操作与时效 |
| RegulatoryGrant | 显式跨租户监管凭据，独立审批和撤销，见第 6 节 |

## 5. 授权模型、角色和对象操作

集中服务生成上下文（DEC-008 A）便于统一策略、撤权版本和审计，依赖统一组件及真值读取的可用性。由各领域嵌入统一策略库（B）可本地执行，但跨领域聚合易出现版本、默认值和范围偏差。推荐 **集中定义和计算，领域最终校验**。P0 候选是在模块化单体中使用集中策略组件。每个领域在 SQL 和状态改变处用最小作用域执行，任何入口均不得只凭菜单或已算范围绕开对象查验。

1. 验证可信认证主体、认证等级和会话状态。
2. 检查主体及目标租户的有效性与能力。
3. 通过功能权限。人员取 RuoYi 功能许可与项目许可交集，后台和外部身份用经批准的服务功能权限替代人员菜单。
4. 计算显式项目范围及数据时间窗。项目角色、个别授予、显式拒绝和授权版本由策略组件解析，超范围即拒绝。
5. 领域按服务端归属数据同时限制租户、项目、对象和数据时间，防止直接 ID 越权。部门规则若适用再取交集。
6. 依据业务状态、操作约束、有效期和职责分离作最终判定。高风险写入复核版本并在可用的同库事务内控制并发。

候选内部接口语义 `decide(可信身份, 操作, 目标选择器, requestId, 可信时钟)` → `Denied(内部原因)` 或 `Allowed(受控作用域)`。作用域包括主体类型及 ID、原租户、目标租户、允许的项目集合、业务类型及操作、数据时间窗、授权 ID 与版本、租户/会话/策略版本、鉴权时间、有效上界、审计关联 ID。单次请求或工作单元创建，控制传递，不能由 HTTP/MQ JSON 直接反序列化成为授权。项目集合为空视为拒绝或固定空集，不能移除谓词。客户端 tenantId/projectId/roleId 只做选择请求，业务写入归属由服务端锁定或重读。

| 内部原因 | 候选对外语义与行为 |
| --- | --- |
| 缺身份上下文 | 需要认证，服务任务缺凭据时拒绝并告警 |
| 无权限或对象不存在 | 对对象入口返回等效不可见结果，未授权请求不暴露对象是否存在。统一错误码与线格式待 DEC-017 |
| 授权已过期或撤销 | 拒绝，记录精确内部理由；仅获准管理者可查看授权状态 |
| 权限服务不可用 | 失败关闭，可按审批定义可重试结果。部分范围读取失败不返回混合、扩大或推测的结果 |

### 5.1 候选角色模板

下表是权限申请上限。实际许可仍取功能、项目、对象、时间、状态和需要的审批交集。平台或租户管理员不因管理身份自动获得业务读取。任何未列能力默认拒绝。

| 简称 | 角色 | 基本作用域与限制 |
| --- | --- | --- |
| 平 | 平台管理员 | 平台、租户元数据及平台授权流程。无默认业务读权限 |
| 租 | 租户管理员 | 自租户管理及项目委派流程 |
| 项 | 项目管理员 | 管理指定项目及受托权限，不得扩权或自批 |
| 调 | 调度员 | 项目作业、派工及所需资产 |
| 驾 | 驾驶员 | 本人受派的任务和相关最小数据 |
| 计 | 计量审核员 | 审核相关事实，原始计量保留 |
| 财 | 财务人员 | 获授权项目及合同/账单，职责分离 |
| 监 | 监管人员 | 显式范围内查看，导出需独立授予 |
| 处 | 处置人员 | 交付点或指定项目的交接 |
| 运 | 运维人员 | 已授予设备与平台运行元数据，业务读取另审 |
| 审 | 审计人员 | 获授权审计记录、必要证据及最小字段 |

### 5.2 对象 × 操作矩阵

「—」表示无默认候选。Q 查询，C 创建，M 修改，X 删除或停用，A 分配，V 审核，E 导出，G 授权，R 撤权。历史及证据对象 X 优先指受控停用、逻辑作废或清理流程。原始事实记录不可覆盖。角色简称表达候选能力，财务与业务状态机保留 DEC-011。

| 对象 | Q | C | M | X | A | V | E | G | R |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 项目 | 租 项 监 | 租 | 租 项 | 租 | 项 | 租 | 项 监 | 租 项 | 租 项 |
| 车辆 | 项 调 驾 监 | 项 | 项 | 项 | 调 | 项 | 项 监 | — | — |
| 设备 | 项 运 监 | 项 运 | 项 运 | 项 | 项 运 | 项 | 项 监 | — | — |
| 垃圾桶 | 项 调 驾 监 | 项 | 项 | 项 | 调 | 项 | 项 监 | — | — |
| RFID 标签 | 项 运 调 监 | 项 运 | 项 运 | 项 | 项 运 | 项 | 项 监 | — | — |
| 清运任务 | 项 调 驾 监 | 调 | 调 驾 | 调 | 调 | 项 | 项 监 | — | — |
| 称重记录 | 项 驾 计 财 监 | — | 计 | — | 项 | 计 | 计 财 监 | — | — |
| 运输批次 | 项 调 驾 处 监 | 调 | 调 | 调 | 调 | 项 | 项 监 | — | — |
| 交付记录 | 项 调 处 监 | 处 | 处 | — | 调 | 项 处 | 项 监 | — | — |
| 合同 | 项 财 监 | 财 | 财 | 财 | 财 | 财 | 财 | — | — |
| 账单 | 财 监 | 财 | 财 | — | 财 | 财 | 财 | — | — |
| 车辆轨迹 | 项 调 驾 监 | — | — | — | — | — | 项 监 | — | — |
| 统计报表 | 项 财 监 | — | — | — | — | — | 项 财 监 | — | — |
| 附件 | 项 驾 计 财 监 处 审 | 项 驾 计 财 处 | 项 计 财 | 项 财 | — | 计 财 | 项 财 监 审 | — | — |
| 导出文件 | 项 财 监 审 | 项 财 监 审 | — | 项 财 审 | — | 财 审 | 项 财 监 审 | — | — |
| 审计记录 | 审 | — | — | — | — | — | 审 | — | — |

项目授权/撤权由受托人执行。跨租户监管授权属于双边受控审批流程，不能由一个项目管理员自行扩大。附件和导出继承业务范围并要求额外操作权限，不能通过重新关联扩大可见性。称重记录 M 仅候选审核修正，原始采集由服务身份处理。对合同、账单、人员高风险授权可要求发起人与审批人不同。

## 6. 跨租户监管授权

推荐逐项目明确授予。按受控范围动态授予能覆盖大规模运营，但范围计算依赖版本化规则，新项目、地理边界和组织变更可能误扩权。初期新建项目均需显式审批。若后续采用动态范围，建议使用审批时规则及冻结项目清单，扩张走再次审批。自动纳入新项目须专项批准及可验证的审计。

授权包含授予主体、被授人员/服务/组、原租户、目标租户、项目集合、资源种类和可做操作、数据时间区间与授权生效/失效、依据与审批人、当前版本与撤销状态。创建时核对授权人可委派的上限和目标项目归属。变更另存版本，区分有效数据时间窗和可行使权限时段，开放仅经审批并在生效后。缩小、撤销、失效和租户/项目状态改变应立即使源版本失效，记录执行人、依据、旧/新版本和审计关联，按第 8 节传播。授权列表和审批管理本身也有限制。

监管查看通常只读，默认不修改运营租户原始计量或财务数据。若需要监管业务操作，另定义单独命令、对象状态、法定依据和审批。列表、地图、统计、下钻、导出及附件都基于同一受控作用域，字段脱敏、数据最小化和跨域组合也在服务端执行。

## 7. TenantIgnore 清单与治理

检索 `@TenantIgnore`、`TenantUtils.executeIgnore`、直接 `setIgnore(true)` 与 `@InterceptorIgnore(tenantLine...)`。以下按用途列出基线正式源码命中。带编号的证据指向具体调用。测试命中位于 HRM 薪资模板、UReport2 文件及安全过滤器测试，不会进入正式装配。核心组件判断未知 MyBatis 表时可跳过租户谓词，核查 SQL 映射关系与注解同样重要。

| 实际位置 | 用途及基础设施依赖 | 输入、权限与风险 |
| --- | --- | --- |
| [TenantIgnoreAspect L38](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/aop/TenantIgnoreAspect.java#L38)、[TenantUtils L99](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/util/TenantUtils.java#L99)、[TenantUtils L116](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/util/TenantUtils.java#L116)、[TenantSecurityWebFilter L105](../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/security/TenantSecurityWebFilter.java#L105) | 上游通用方法/类绕过及缺租户的允许 URL | Aspect 和工具恢复旧状态。过滤器受 URL 匹配所触发，依赖外层清理。此层无对象权限，不能接收客户端忽略开关 |
| [TenantController L46](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/tenant/TenantController.java#L46)、[TenantController L56](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/tenant/TenantController.java#L56)、[TenantController L66](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/tenant/TenantController.java#L66)、[AppTenantController L35](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/app/tenant/AppTenantController.java#L35) | 租户发现、可选租户与域名查找，现有登录流程依赖 | 名称、域名来自外部，简单列表读取系统数据。只允许必要租户元数据，防枚举；不允许项目记录通过同一入口 |
| [CaptchaController L30](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/captcha/CaptchaController.java#L30)、[CaptchaController L40](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/captcha/CaptchaController.java#L40) | 登录前验证码生成与校验 | 外部请求、无业务对象权限，保留验证码用途并单独验证限流 |
| [SmsCallbackController L28](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/sms/SmsCallbackController.java#L28)、[SmsCallbackController L38](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/sms/SmsCallbackController.java#L38)、[SmsCallbackController L49](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/sms/SmsCallbackController.java#L49)、[SmsCallbackController L58](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/sms/SmsCallbackController.java#L58) | 四个上游短信状态回调 | 接收外部报文，按渠道关联并核实供应商校验与幂等。对象保护按短信业务验证，无环卫数据 |
| [OAuth2AccessTokenMapper L20](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/mysql/oauth2/OAuth2AccessTokenMapper.java#L20)、[OAuth2RefreshTokenMapper L23](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/mysql/oauth2/OAuth2RefreshTokenMapper.java#L23)、[TokenCleanJob L33](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/job/token/TokenCleanJob.java#L33) | 按凭据查身份与系统清理，现有认证依赖 | 查询值来自令牌，任务内部参数。查找与对象授权分离。兼容回退及撤权单列 GAP-003 |
| [ErrorLogCleanJob L34](../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/job/logger/ErrorLogCleanJob.java#L34)、[AccessLogCleanJob L34](../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/job/logger/AccessLogCleanJob.java#L34)、[JobLogCleanJob L33](../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/job/job/JobLogCleanJob.java#L33)、[ApiErrorLogServiceImpl L48](../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/service/logger/ApiErrorLogServiceImpl.java#L48)、[ApiAccessLogServiceImpl L44](../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/service/logger/ApiAccessLogServiceImpl.java#L44) | 日志维护与无租户请求记录，属于现有运维基础设施 | 清理为任务参数，写入可包含外部请求衍生值。读权限、脱敏和保留独立治理，审计证据不能依赖全局维护任务 |
| [ConfigDO](../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/dal/dataobject/config/ConfigDO.java)、[CodegenColumnDO](../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/dal/dataobject/codegen/CodegenColumnDO.java)、[CodegenTableDO](../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/dal/dataobject/codegen/CodegenTableDO.java)、[DataSourceConfigDO](../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/dal/dataobject/db/DataSourceConfigDO.java)、[JobDO](../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/dal/dataobject/job/JobDO.java)、[JobLogDO](../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/dal/dataobject/job/JobLogDO.java) | Infra 全局配置、代码生成和调度元数据，供上游基础设施使用 | 注解在表映射层，无请求或对象授权能力。间接接受管理入口的配置/ID，需依赖相应管理权限，尤其数据源与调度元数据 |
| [FileController L105](../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/controller/admin/file/FileController.java#L105)、[FileDO](../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/dal/dataobject/file/FileDO.java)、[FileContentDO](../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/dal/dataobject/file/FileContentDO.java)、[FileConfigDO](../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/dal/dataobject/file/FileConfigDO.java) | Infra 通用文件下载及全局存储数据 | 下载直接接收配置 ID 与路径，无租户、项目或业务对象权限。公开内容限定用途，私有内容迁移至独立授权通道 |
| [DictTypeDO](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/dict/DictTypeDO.java)、[DictDataDO](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/dict/DictDataDO.java)、[TenantDO](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/tenant/TenantDO.java)、[TenantPackageDO](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/tenant/TenantPackageDO.java)、[MenuDO](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/permission/MenuDO.java)、[OAuth2ClientDO](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/oauth2/OAuth2ClientDO.java) | 全局字典、租户与权限目录，提供现有管理基础 | 表层不验证对象，查询或变更依赖调用入口权限，避免用共享字典和菜单存租户/项目私有授予 |
| [MailAccountDO](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/mail/MailAccountDO.java)、[MailLogDO](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/mail/MailLogDO.java)、[MailTemplateDO](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/mail/MailTemplateDO.java)、[NotifyTemplateDO](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/notify/NotifyTemplateDO.java)、[SmsChannelDO](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/sms/SmsChannelDO.java)、[SmsCodeDO](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/sms/SmsCodeDO.java)、[SmsLogDO](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/sms/SmsLogDO.java)、[SmsTemplateDO](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/sms/SmsTemplateDO.java) | 全局通信通道、模板、校验与日志 | 外部收件方、状态和请求可间接影响记录。现有系统权限只管理通信，屏蔽敏感内容，模板和日志不作为项目权限数据 |
| [IoTDeviceApiImpl L117](../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/api/device/IoTDeviceApiImpl.java#L117)、[IoTDeviceApiImpl L164](../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/api/device/IoTDeviceApiImpl.java#L164)、[IoTDeviceApiImpl L228](../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/api/device/IoTDeviceApiImpl.java#L228)、[IoTDeviceApiImpl L291](../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/api/device/IoTDeviceApiImpl.java#L291) | 可选 IoT Gateway 配置查询与节点保活，最后一项为网关级状态 | 接收网关身份或节点请求。须分别校验服务身份、设备或配置范围；保活不能扩大为数据权限。无环卫领域数据 |
| [IotDeviceServiceImpl L123](../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/service/device/IotDeviceServiceImpl.java#L123)、[IotDeviceServiceImpl L293](../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/service/device/IotDeviceServiceImpl.java#L293)、[IotDeviceServiceImpl L300](../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/service/device/IotDeviceServiceImpl.java#L300)、[IotDeviceServiceImpl L818](../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/service/device/IotDeviceServiceImpl.java#L818)、[IotProductServiceImpl L192](../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/service/product/IotProductServiceImpl.java#L192)、[IotThingModelServiceImpl L158](../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/service/thingmodel/IotThingModelServiceImpl.java#L158)、[IotThingModelServiceImpl L469](../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/service/thingmodel/IotThingModelServiceImpl.java#L469) | 可选 IoT 设备与产品运行、缓存查找及失效 | 接收内部 ID、产品键/设备名和设备流程衍生输入。全局键与缓存失效依赖业务唯一性。调用方还需校验注册归属，缓存命中不能授权业务对象 |
| [IotOpcUaServerConfigServiceImpl L74](../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/service/opcuaserver/IotOpcUaServerConfigServiceImpl.java#L74)、[IotSceneRuleServiceImpl L166](../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/service/rule/scene/IotSceneRuleServiceImpl.java#L166)、[IotSceneRuleServiceImpl L223](../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/service/rule/scene/IotSceneRuleServiceImpl.java#L223) | 可选 IoT 配置及场景规则，允许运行路径定位归属 | 配置/场景 ID 进入读取，须核对调用方及匹配设备/租户。按 ID 读出规则后仍有越权风险，严禁迁移为环卫通用查询 |
| [IotDeviceMessageMapper L20](../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/dal/tdengine/IotDeviceMessageMapper.java#L20)、[IotDevicePropertyMapper L22](../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/dal/tdengine/IotDevicePropertyMapper.java#L22)、[IotDeviceMessageIoTDBMapper L21](../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/dal/iotdb/IotDeviceMessageIoTDBMapper.java#L21)、[IoTDBSystemMapper L20](../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/dal/iotdb/IoTDBSystemMapper.java#L20)、[IotDevicePropertyIoTDBMapper L24](../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/dal/iotdb/IotDevicePropertyIoTDBMapper.java#L24)、[IotDeviceMessageQuestDBMapper L23](../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/dal/questdb/IotDeviceMessageQuestDBMapper.java#L23)、[IotDevicePropertyQuestDBMapper L24](../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/dal/questdb/IotDevicePropertyQuestDBMapper.java#L24) | 可选 IoT 方言解析绕过，部分同时跳过数据权限 | 正式调用方必须负责范围。命中配置兼容标志并不证明查询带租户谓词，不可直接套用 TRACK |
| [PayNotifyController L66](../../yudao-module-pay/src/main/java/cn/iocoder/yudao/module/pay/controller/admin/notify/PayNotifyController.java#L66)、[PayNotifyController L88](../../yudao-module-pay/src/main/java/cn/iocoder/yudao/module/pay/controller/admin/notify/PayNotifyController.java#L88)、[PayNotifyController L110](../../yudao-module-pay/src/main/java/cn/iocoder/yudao/module/pay/controller/admin/notify/PayNotifyController.java#L110)、[MpOpenController L46](../../yudao-module-mp/src/main/java/cn/iocoder/yudao/module/mp/controller/admin/open/MpOpenController.java#L46)、[MpOpenController L71](../../yudao-module-mp/src/main/java/cn/iocoder/yudao/module/mp/controller/admin/open/MpOpenController.java#L71)、[ImRtcLiveKitController L51](../../yudao-module-im/src/main/java/cn/iocoder/yudao/module/im/controller/admin/rtc/ImRtcLiveKitController.java#L51)、[AiImageController L138](../../yudao-module-ai/src/main/java/cn/iocoder/yudao/module/ai/controller/admin/image/AiImageController.java#L138) | 可选第三方支付、公众号、RTC、AI 回调 | 外部报文/签名/路径，依赖各集成的签名与事项关联。不能凭回调自报 ID 授予项目，签名、关联和反重放需按入口验收 |
| [MpAccountServiceImpl L71](../../yudao-module-mp/src/main/java/cn/iocoder/yudao/module/mp/service/account/MpAccountServiceImpl.java#L71)、[MpAccountServiceImpl L98](../../yudao-module-mp/src/main/java/cn/iocoder/yudao/module/mp/service/account/MpAccountServiceImpl.java#L98)、[MpAccountServiceImpl L164](../../yudao-module-mp/src/main/java/cn/iocoder/yudao/module/mp/service/account/MpAccountServiceImpl.java#L164) | 可选公众号账号全局处理 | 接收上游或内部账户标识，须单独核对账号及租户归属，禁止扩展到环卫账户授权 |
| [PmsKnowledgeDocumentShareController L88](../../yudao-module-pms/src/main/java/cn/iocoder/yudao/module/pms/controller/admin/kb/interaction/PmsKnowledgeDocumentShareController.java#L88)、[AiChatMessageServiceImpl L317](../../yudao-module-ai/src/main/java/cn/iocoder/yudao/module/ai/service/chat/AiChatMessageServiceImpl.java#L317)、[JmReportTokenServiceImpl L110](../../yudao-module-report/src/main/java/cn/iocoder/yudao/module/report/framework/jmreport/core/service/JmReportTokenServiceImpl.java#L110) | 可选分享访问、聊天引用查找、报表 Token 解析 | 外部分享凭据、会话关联对象及 Token。分别依赖自己的校验链，直接设置 ignore 需复查清理。不能搬用到私有证据或跨租户报表 |
| [FmsSubjectTemplateDO](../../yudao-module-fms/src/main/java/cn/iocoder/yudao/module/fms/dal/dataobject/config/FmsSubjectTemplateDO.java)、[FmsReportTemplateDO](../../yudao-module-fms/src/main/java/cn/iocoder/yudao/module/fms/dal/dataobject/report/FmsReportTemplateDO.java)、[HrmSalaryOptionTemplateDO](../../yudao-module-hrm/src/main/java/cn/iocoder/yudao/module/hrm/dal/dataobject/salary/config/HrmSalaryOptionTemplateDO.java)、[HrmSalarySlipTemplateServiceImpl L295](../../yudao-module-hrm/src/main/java/cn/iocoder/yudao/module/hrm/service/salary/slip/HrmSalarySlipTemplateServiceImpl.java#L295) | 可选领域的全局模板或默认模板 | 通过模板和管理入口间接输入，SQL 例外不产生对象授权。领域模板不得扩展为环卫授权目录 |

表中的可选模块未纳入默认 Reactor。现有调用各自服务其上游场景，风险栏记录源代码可见的授权边界或后续验证要求。该盘点不构成任何现有可选模块的生产验收。

候选白名单记录路径、方法、用途、允许的表、输入来源、权限前置条件、审计、负责人、复核期限与负向测试。基线例外保留在库存中。新环卫代码禁止未审批的 TenantIgnore、全局 Mapper、仅靠注解关闭数据权限、用户控制 ignore 或查询失败后扩大范围。静态检查同时覆盖 AOP、自调用、通配 URL、有效配置和原生 SQL，动态测试验证异常后清理及空集合固定拒绝。

跨租户读优先按已批准的目标租户组分段，在剩余时间及同一作用域内逐租户执行，保留标准租户线并附加 project/type/time。合并阶段以准许结果集为输入，拒绝部分失败的敏感聚合。受控多租户 SQL 如确有性能需要，应通过专用 Access Adapter 接收强类型受控范围，条件在 mapper 中绑定并有独立负例。任何新拦截器、受控 mapper 例外或更改原 `visit-tenant-id` 行为需专项审批，回归登录、系统与可选模块、缓存、Quartz、日志及升级兼容。

## 8. 权限缓存与撤权

- 专用缓存键包含环境、主体类型和稳定 ID、所属租户、目标租户、项目或规范化授权集合指纹、资源/操作、适用数据时间窗口、授权版本、会话/租户/策略版本。范围内决策的附加条件也要参与键或在命中后独立校验。禁止仅以 userId 缓存跨租户结果。短期负缓存同样分域，来源失败单独计量。
- 正缓存有效期取审批 TTL 与会话、授予、租户、决策有效上界的最小值，使用可信时钟处理边界。高风险动作推荐读取权威状态及版本，短期缓存仅用于可证明撤权窗口内的低风险读。具体 TTL、撤权 SLA、时钟容差、并发重试和负缓存参数留待审批。
- 授权事务更新状态和单调版本，提交后失效或通知。旧版本命中不能授权，读穿回填要复核版本，避免撤销与并发回填时旧值胜出。同一 BUSINESS 写事务能锁定授权状态时使用行锁或版本条件；RuoYi 会话状态和 TRACK 读执行不能假定跨库原子性，敏感操作在发布或下载前复查，剩余竞态按待批准撤权窗口处理。
- 通知丢失时依靠权威版本校验、过期及对账。Redis 不可用仅在权威存储可用且可完整重新决定时受控回源，采用并发合并与限流防穿透。Redis 与权威存储均不可用、上下文缺失或版本无法确认时失败关闭。审计内部原因、版本和延迟，不写入令牌或完整文件地址。多节点撤权要证明跨请求、订阅、导出和对象下载生效。

## 9. HTTP、RabbitMQ、Quartz、线程池上下文

| 入口 | 可信上下文和执行 | 异常、取消与重试 |
| --- | --- | --- |
| HTTP | 从已验证的人员/服务会话投影最小身份，确定所属租户。目标租户及项目作为选择器逐次授权；在请求作用域写入不可变结果 | 在 finally 清理项目、租户、ignore、SecurityContext 及审计上下文。异步 dispatch 使用同样的入口边界。拦截身份缺失和异常传播 |
| RabbitMQ 用户命令 | 生产者身份用于来源审计，消费者使用受限服务身份，并基于持久业务命令引用重建发起人、版本、对象归属。对高风险操作重查当前会话和授权 | 撤权后拒绝或进入受控终态，留可审计原因。网络或来源失败走审批的重试；重投重新决定 |
| RabbitMQ 系统事件／原始设备事实 | 校验可信服务发布来源、消息类型、持久记录及数据归属。消息字段是声明，关联权威记录后才设执行范围。已可靠接受的原始事件继续按系统职责存证或处理 | 用户撤权不静默丢弃原始事件。归属冲突转隔离或人工。按 C-05 执行本地 Inbox/Outbox 及提交后确认，ENG-002E 定义可靠转投。每次取出清理上下文 |
| Quartz | 每个任务有登记的服务身份、目标租户、允许操作与显式项目清单。每次触发及每个范围批次复核有效性和项目 | 项目未知或停用、身份不符则记录拒绝。任务切换时清空上下文，重试重新授权，禁止直接信任 Job 参数或无界遍历 |
| 线程池／回调 | 显式传不可变工作单引用、受限身份引用和关联 ID。执行线程内重建所需范围，检查残留并按约定恢复合法嵌套范围 | 测试正常、用户异常、提交失败、排队取消、运行取消/超时及同线程复用。中断要配合任务停止，调用方超时本身不会清除仍在运行的 worker。外层 finally 清除状态，禁止长期复制整个 LoginUser、MDC 或 ignore |

## 10. 历史有效关系候选契约

记录带版本的关系键、源与目标稳定标识、当时所有者、有效区间 `[from,to)`、登记/修订时间、变更人/服务、原因、替代关系及适用状态。以 `Instant` 与 UTC 毫秒表达，区分事件发生、接收、持久化和记录修订四类时间。活动区间结束保留旧关系。候选修正通过有审计的追加版本表达，历史解析能重现原判定及后续修正。

| 关系 | 候选唯一性与约束 |
| --- | --- |
| 车辆—项目 | 对同一车辆和承担的运营职责约束重叠，共享用途需明确批准，记录归属窗口 |
| 设备—车辆 | 同一安装槽及设备在互斥安装关系内不可重叠。换板结束旧硬件关系并登记新设备，有效边界不改原事件身份 |
| 读卡器—设备 | 按设备及读卡通道管理，保留更换、复用及物理位置证据，通道与双桶适用规则待专项审批 |
| 垃圾桶—责任主体 | 默认按互斥的责任类型检查重叠，责任主体与实际项目另校验，争议进人工 |
| RFID 标签—垃圾桶 | 同有效时间同一标签不得绑定多个冲突桶，桶及责任主体可有多个经核验标签。换卡及失效保留旧记录 |
| 项目—运营主体 | 保存合同/职责及有效期。同项目多个主体仅按已审定职责隔离，不混为同一计费归属 |
| 人员—项目 | 可同时具有不同项目的有效成员关系。同项目同角色的重复或冲突授权须并发校验，历史资格不能推出当前许可 |

互斥键在同所属库事务以稳定锁定记录或等效序列化方法检查区间相交，避免先查后插并发穿透。空或逆序区间拒绝。迟到及离线上传优先用可信事件时刻查历史版本。时钟质量不足、未绑定、已停用且无历史匹配、重叠及一对多歧义转人工或待定，保存原始事实。禁止以当前绑定覆盖事件归属，当前访问还需按访问时的授权及其数据时间范围过滤。RFID 清缓存动作、新鲜窗口、双桶及计费规则仍受 DEC-007 等专项制约。

## 11. 地图、报表与聚合授权

点位、最新位置、历史轨迹、聚合和下钻采用同一授权选择器。最新位置按获准数据区间在数据库内取值，先过滤租户、项目、数据类型和时间，再计算最新点或地图网格。轨迹分片需保留其项目归属时段并去除其他时段的数据。地图边界、车队筛选、分页游标和下钻对象 ID 只能收窄范围。前端隐藏图层不能代替后端查询条件。

报表明细、汇总、分组与下钻共享受控查询。按租户、项目、时间及对象粒度提供可过滤读模型。全平台预聚合总量若不能安全拆回获授权范围，禁止用于项目或监管查询。数量、分母、空区域、排序和缓存同样应源于受控输入。查询缓存包含作用域和版本，同项目群以外禁止拼接或从全量差分推导。小样本抑制、字段脱敏和分组边界待审批。DEC-014 约束正式 22 项指标的粒度、分母、统计时区、状态及修正规则。

## 12. 导出与私有附件

### 导出

1. 创建时校验查询及导出操作、范围、格式和高风险确认，持久化发起人、规范化筛选、授权版本与任务生命周期。选择条件只能收窄。
2. 后台以受限服务身份加载任务，重新核查发起人当前授权与租户和项目状态。执行范围是原批准范围与当前范围的交集。若范围缩小，候选采取整体中止并提示重新申请，防止交付与审批范围不符的文件。批量生成期间按批次检查版本，发布前再次核验。撤权、失败或取消时停止发布，清理半成品与后续重试，记录原因。异步本地证据通过所属事务保存。
3. 产物按私有附件处理，下载时再次检验生成范围、对象状态、时效、当前下载权限及版本。客户端不能凭任务 ID、历史授权或旧 URL 持续读取。对于长下载，比较每次打开时复核与中途执行检查的成本，候选高风险使用代理、分块重检和中止；已发字节无法撤回。
4. 文件关联任务编号、发起人、生成时间和受控标识。候选按格式嵌入可见水印，机器可读格式采用约定的标记或审计清单，不破坏字段或泄露他人身份。按 DEC-015 保留下载及拒绝审计。文件期限、临时介质加密、回收和失败重试待审批。

### 私有附件

服务端创建不可猜测的稳定文件 ID，按业务对象、项目、租户、用途和保留等级登记独立的对象键。上传前从授权范围内的真实业务对象取得归属，限制大小、类型及上传授权，分段和直传完成时确认存储键、上传事项及内容状态，孤儿文件由受控流程回收。不能用用户提供的 URL 或路径作为对象权限凭证。附件下载按文件 ID 解析所属业务对象及租户项目，独立检查附件、对象和下载操作，记录拒绝及成功。删除与保留、法律保全、审计访问和对象物理删除流程另审。

| 下载方案 | 优点 | 风险与适用条件 |
| --- | --- | --- |
| 应用鉴权代理 | 每次请求可查当前授权，能核对范围、审计和中止长下载 | 占用应用出站带宽与连接，需验证 Range、限流、取消、缓存与反代策略。推荐高风险证据初期使用 |
| 短期签名 URL | 对象存储直接传输，降低应用负载 | 签发后凭链接可在有效期内访问，应用撤权通常无法立即使既有签名失效。只在明确接受风险和短有效期后使用，避免日志、Referer 及缓存泄露，需实测存储策略 |

私有 bucket 或隔离存储路径禁止匿名访问及经 Infra 通用下载别名访问。签名的资源及方法需固定，禁止通配权限。上线前验证匿名、直接路径、跨租户、跨项目、撤权、过期和审计。永久公开 URL 禁止用于私有称重证据、财务或导出产物。BLOCK-004 与 BLOCK-005 继续开放。

## 13. 身份与高风险操作

| 身份 | 最小权限边界 |
| --- | --- |
| 人员 | RuoYi 认证与会话，项目授权和必要二次认证。管理员、服务台和平台运维按角色职责隔离 |
| Android 车载终端 | 独立终端身份绑定车辆/项目及终端能力，驾驶员登录主体另算，不默认有下载、审核或财务权限 |
| PDA | 单独终端绑定与人员登录，可获批准的扫描或交付任务权限，遗失设备按撤销流程隔离 |
| STM32 设备 | 只允许注册和协议接入相关能力，生命周期、轮换、补传及识别依 DEC-002，不具备用户业务许可 |
| 后台服务 | 独立登记的执行身份和操作范围，按命令或事实重建权限，授权变更可追溯 |
| 外部系统 | 独立客户端、合作方和项目范围，契约、签名、重放控制及兼容按 DEC-017 审批，禁止复用内部服务或人员口令 |

跨租户授权、项目授权变更、监管撤销、大批量导出、私有证据下载、财务审核、安全配置变更均应候选列为高风险。建议跨租户授权、关键财务或安全配置采用独立发起与复核，重大导出及证据访问按数据等级要求近期二次认证并绑定具体操作。撤权不应被原授予人单方阻塞，可由批准的应急身份先收窄、补审。现有 System/Security 源码未确认 TOTP 流程。评估接入 IdP 的受验证等级、动作绑定与会话撤销，或单独批准 TOTP 等实现，核对注册、密钥保护、恢复、设备丢失和重放。审批前不选择算法、时限或因子强制比例。

关键审计候选字段含请求/事件关联 ID、发起人和执行服务、会话级别、源/目标、范围、动作、授权版本、决策结果、变更前后摘要和原因。授权状态变更与本地审计证据或 Outbox 同事务记录。高风险操作的证据不可可靠保存时，候选失败关闭或据专项审批执行应急授权。加密、独立校验、保留、脱敏和法定删除规则由 DEC-015 决定。

## 14. 领域、依赖及数据库

| 领域 | 候选职责与边界 |
| --- | --- |
| sanitation-common | 无业务状态的通用 ID、时间及最小值类型。无授权仓储或对其他领域的反向依赖 |
| sanitation-base | 项目、成员、授权及策略公共契约候选，Biz 处理生命周期和决策，API 暴露受控语义 |
| sanitation-device | 车辆、设备、桶、标签等候选对象范围与历史关系的最终校验 |
| sanitation-operation | 作业、称重、运输与交付的对象权限和状态校验 |
| sanitation-billing | 合同与账单权限、作用域及职责分离，实际所有权待 DEC-011 |
| sanitation-gis | 轨迹、点位和地图授权映射，TRACK 独立读取 |
| sanitation-analysis | 按允许项目和数据时间运算的读模型、分析汇总及下钻 |
| sanitation-event | 执行身份和最小事件上下文契约，可靠处理与传播由 ENG-002E 设计 |
| sanitation-integration | 外部系统身份映射、服务权限和外部契约，外部调用不得绕过领域授权 |

RuoYi 原 Schema 保留用户、角色、菜单、部门、租户及会话真值。BUSINESS 候选保存项目和授权、能力、历史业务关系及本地审计，引用稳定外部 ID。通过 [AdminUserApi](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/api/user/AdminUserApi.java)、[DeptApi](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/api/dept/DeptApi.java)、[PermissionApi](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/api/permission/PermissionApi.java) 及 [TenantCommonApi](../../yudao-framework/yudao-common/src/main/java/cn/iocoder/yudao/framework/common/biz/system/tenant/TenantCommonApi.java) 等现有公开边界评估身份适配。适配层对准实际 API 的状态与作用域能力，补足项需审批，不从 base 访问 System 的 DO、Mapper 或内部服务。TRACK 保存轨迹，若需历史范围读模型，以附有版本的受控引用或对象归属实现。INGEST 保存原事实和自己的 Outbox，不用于人员项目授权决策。禁止跨库外键、跨库 `@DSTransactional` 或假设 RuoYi 与 BUSINESS 原子提交。

BUSINESS 授权变更与其版本及必要证据同本地事务提交，提交后的失效通知不能先于提交。RuoYi 用户停用与权限变化先在自己的事务生效，高风险复核分别读取两边权威状态。跨库事件投递、回补、对账和告警留给 ENG-002E。投影落后或无法核对版本时关闭敏感入口。禁止以数据源不可用为由回退到 master。

候选依赖边按 [ENG-002B 第 4 节](ENG-002B-module-skeleton-design.md#4-依赖白名单) 与 [检查器](../../script/shell/check-sanitation-boundaries.py) 专项审阅。优先考虑 `base-api → common`，以及 `device-biz/gis-biz/operation-biz/billing-biz/analysis-biz/integration-biz → base-api` 中有明确调用方的子集。各 API 仅向下共享无状态契约，同步依赖优先单向。现有候选表中 event-biz 只允许自身 API 和 common。优先让事件执行器持有通用身份和路由，由受控调用方处理领域权限；若确认需新增 event-biz → base-api 或其他领域 API，属于超出现行候选表的专项审批项。领域向事件契约的方向必须验图，避免 base 反向引用事件 Biz 或业务 Biz。现有允许边与新增候选边应逐条列入实施审批，检查器保持当前最小图。任何允许接入 System 适配、Server 装配或项目读取依赖，都需有效 POM、真实依赖树、循环和源码/字节码证据。Gateway 不依赖环卫 Biz。

## 15. 隔离实验与局限

在工作区 `/workspace/eng002d01-evidence` 执行。JDK 使用本机 OpenJDK 21.0.13.1。使用历史 ENG-002C 回归 Server JAR 的 `BOOT-INF/lib/` 执行 Java 探针。通过 `git diff --quiet a012f9f60e0d03785fecb9135fe498834166b2fe HEAD -- yudao-framework yudao-module-system yudao-module-infra yudao-dependencies yudao-server pom.xml` 确认对应生产源码相同，退出 0。本轮未重新打包 Server。Server 历史 JAR 不带 AMQP 依赖，发布端探针初次编译失败。复验 classpath 仅补隔离 IoT 历史 Maven 缓存中的 spring-amqp JAR，保留 `java-amqp-first.log`。探针和输出留在仓库外。

```bash
d=/workspace/eng002d01-evidence
jdk=/workspace/car-weight-eng002a-validation/jdk
amqp_jar=$(cat "$d/amqp-jar.txt")
tenant_jar=$(cat "$d/tenant-jar.txt")
"$jdk/bin/java" -cp "$tenant_jar:$d/BOOT-INF/lib/*:$amqp_jar" "$d/TenantProbe.java"
python3 "$d/scope_probe.py"
```

| 实验 | 实际代码／预期 | 结果与局限 |
| --- | --- | --- |
| 租户过滤、未知表与无上下文 | MyBatis-Plus `parserSingle`、真实 `AdminUserDO`、`TenantDatabaseInterceptor`。已注册表应有限制，未知表暴露缺口，无租户应拒绝 | 三项断言通过。没有执行 MySQL 查询或 MockMvc |
| TenantIgnore | CGLIB 代理、实际切面和 Spring BeanFactory，异常前应看到 ignore、异常后恢复 | 两项断言通过。早期探针把初始化异常误纳入捕获，复核发现缺少预期标记。收窄断言后发现 BeanFactory 缺失，补最小 Spring context 后重跑成功 |
| 线程复用及异常 | TTL 包装、同一个 worker、业务异常，再次执行应无租户/ignore 残留 | 两项断言通过。取消、超时、提交拒绝及全部 SecurityContext/MDC 仍待验证 |
| MQ | 实际 Rabbit 发布后处理器写 `tenant-id`。把租户 starter 明确放在探针 classpath 首位，确认其覆盖的 InvocableHandlerMethod 从 Spring 消息取 Header 并恢复 Holder | 两项断言通过。未启动 RabbitMQ、Quartz，未验证应用启动时的类加载优先级、Broker 消费鉴权、重投和 ACK |
| 部门与项目、错误项目、聚合 | Python SQLite 内存查询，合成数据加入不同租户、部门及项目。先过滤再聚合，未知项目应为空 | 两项断言通过。未验证正式 MP 规则、MySQL 方言、GIS 或统计读模型 |
| 权限键及撤权 | 合成字典，改目标租户、项目、操作应 miss，权威版本变更拒绝旧值，到期拒绝 | 三项断言通过。未运行 Redis、多实例及撤权网络故障 |
| 附件对象归属 | SQLite 内存查询将文件绑定到受控业务对象，只返回范围内关联 | 一项断言通过。未启动 HTTP、对象存储、代理、真实签名或下载 |

最终 Java 探针 9 项断言、Python 合成探针 6 项断言，两个命令退出 0。日志分别为 `java-consumer.log` 和 `scope.log`，两次早期 Java 记录为 `java-first.log` 与 `java-second.log`。该实验覆盖源码组件级和合成行为，生产阻断及实施验收仍以真实装配证据为准。

## 16. ENG-002D-02 实施方案、拟变更文件

| 方案 | 交付和影响 | 进入条件 |
| --- | --- | --- |
| A 仅隔离权限基础设施验证，推荐 | 在 `script/authorization/validation/` 中新增隔离工具说明、候选模型/策略夹具、测试与执行脚本。验证权限交集、对象保护、撤销、可信上下文、TenantIgnore 检查、时间关系及地图/附件/导出。独立测试工程如需 POM 放在该目录且不纳入 Reactor。新增 `docs/engineering/ENG-002D-authorization-validation-report.md`。没有 Server/生产数据库装配变化，现有 Maven 最小图可保留 | 批准本设计中的选定范围和使用的隔离依赖、验收矩阵。独立测试可用内存或已授权隔离库，版本化正式迁移另审 |
| B 正式但未装配组件 | `sanitation-modules/sanitation-base/sanitation-base-api/src/main/java/` 增候选成熟契约，`sanitation-base-biz/src/{main,test}/java/` 增策略和测试，先用测试适配器。不进 Server，避免经自动配置或组件扫描隐式装配。只在明确批准边时修改相应模块 POM、检查器规则及测试 | 需批准稳定契约、领域所有权、依赖调整及护栏，证实 Server、Gateway 构件、默认 Reactor 均符合基线。涉及真实 RuoYi API 装配，单独评估身份适配与取数权威性 |

最小交付优先 A。实现输入拒绝、可信上下文生命周期、项目角色和监管范围、缓存键及版本语义、历史归属按事件时间和时钟质量判定，保留关系版本、导出和附件的三个时点检查，以及合成漏数据场景。正式项目或授权表、版本化迁移、Server 入口和生产 MyBatis-Plus 装配须另行书面审批。依赖至 Base 的候选边在将有实际使用时逐条批准。实施报告应记录实测与未执行、失败后复验及残余风险。

## 17. 第二阶段验收矩阵

以下方案和条件均供审批。S 为隔离策略/仓储合成夹具，H 为带可控测试身份的 Spring HTTP/SQL 组合，M 为隔离 MySQL 8.4，R 为多实例 Redis/权威版本，Q 为真实隔离 RabbitMQ 或 Quartz，F 为隔离对象存储及代理。每行要求保存脱敏输入、基线/工具版本、命令和退出码，并补充最后一列的证据。未在本轮执行的目标均保持待验证。第 15 节的组件/合成探针可作局部设计证据。

| 场景 | 方法和实际执行条件 | 预期及证据 |
| --- | --- | --- |
| 同租户同项目 | S/H，用户有功能和对象许可 | 允许，保存决策和对象行 |
| 同租户多项目 | S/H，两个显式成员关系及第三项目对照 | 只显示两个，保存分页、对象集合 |
| 显式跨租户监管 | H/M，目标租户、项目、范围、时段都批准 | 仅读准许字段/数据，保存授权版本和访问审计 |
| 地图查询 | H/M，混合项目点位和历史窗口 | 点位、最新点和轨迹只来自许可范围，留 SQL 与图层明细 |
| 统计聚合 | H/M，合成受控和未授权行 | 总量、分组和下钻同范围，留预期计数与真实 SQL |
| 合法导出 | H/F，创建及执行下载均已批准 | 三段决策、受控行数、可辨识水印与下载审计 |
| 合法附件 | H/F，有效上传事项及关联对象 | 绑定证据、下载内容和对象级审计一致 |
| 历史有效关系 | S/M，前后两组关系、切换时间及迟到事实 | 校验半开窗口、关停历史及当前访问，留关系版本及 UTC 时间 |
| 同租户不同项目 | H，同组织但仅授权一个项目 | 另一项目拒绝或不可见，留结果及 SQL 作用域 |
| 跨租户越权 | H/M，无目标授予 | 无业务数据，留拒绝审计和报错对照 |
| 项目 ID 篡改 | H，用其他项目号和直接对象号 | 无跨界行或写副作用，留对象快照 |
| 租户 ID 篡改 | H，改 tenant-id/visit-tenant-id/入参 | 作用域不扩大，比较请求与服务端决策 |
| 无项目授权 | S/H，角色有菜单但无项目授予 | 默认拒绝，留功能判断与项目判断 |
| 项目停用 | H/M，允许历史与新增同场测试 | 停止新增作业，历史要单独获准，留写入计数及历史读证据 |
| 租户停用 | H/R，初始会话及缓存曾有效 | 主体和目标无新增允许，留版本与撤权延迟 |
| 授权过期 | S/H，可信时钟越过结束点 | 边界时拒绝，留时钟与 grant 版本 |
| 授权撤销 | H/R，多实例持有旧版本及漏失效通知 | 符合批准撤权窗口，不回填旧允许，留并发时序 |
| 权限缓存过期 | R，时间控制与真实键 | 按权威版本重查或拒绝，留 miss 与重算状态 |
| Redis 不可用 | R/H，断缓存并分别保留/断开权威库 | 仅完整权威回源可授权，留故障及拒绝证据 |
| 空权限范围 | S/H/M，空集合或范围交集为空 | 空结果/拒绝，留 SQL 确认过滤未移除 |
| TenantIgnore 绕过 | H/M，代理路径、直接 Mapper、未登记表对照 | 环卫无无界查询，留静态白名单及负例输出 |
| 未授权对象直接 ID | H，已存在的跨项目对象和不存在 ID 对照 | 对外不可区分，留统一外部响应及不同内部原因 |
| 地图下钻越权 | H/M，地图分组与非法详情 ID | 拒绝或空，无额外元数据，留一致作用域 |
| 聚合泄露 | H/M，含未许项目且测试小组/差分 | 受控查询、计数及分母不含未授权行，留查询计划及结果 |
| 导出期间撤权 | H/R/F，任务已排队或正在生成 | 中止发布并回收，留三时点授权、状态与临时对象清理 |
| 下载期间撤权 | H/R/F，长下载及代理/签名分别测试 | 拒绝新请求，代理按批准窗口中止。签名剩余有效期需显式验收，留传输时序 |
| 附件直接 URL 越权 | H/F，匿名、旧 URL、错误租户及通用 Infra 路径 | 不获得私有内容，留 bucket/代理可达性与审计 |
| MQ 范围伪造 | Q/H，投递不匹配目标的测试消息和重复消息 | 无越权副作用，用户命令重验，可靠原始事实保留，留消费与业务事务证据 |
| Quartz 错误租户 | Q/H，传未知或他租户参数 | 拒绝或无效，留任务运行及下个租户上下文快照 |
| 线程池残留 | S/H，同一 worker，覆盖正常、异常、取消、超时与重用 | 无上一用户、租户、角色及 ignore，留线程 ID 和快照 |
| 历史关系重叠 | M，并发插入同互斥键重叠窗口 | 一项拒绝，留锁/版本及存储历史 |
| 当前绑定覆盖历史 | S/M，换板、换卡、迟到或时钟不可信 | 解析到历史关系或待人工，原事件未改写，留前后判定 |

| 工程回归 | 执行条件与方式 | 预期及证据 |
| --- | --- | --- |
| 默认 44 项 Reactor | JDK21 / Maven3.9.12，隔离干净构建 | 退出 0，保存 Reactor 摘要和跳过标记 |
| Java release 17 | 检查项目 class header 及有效 POM | major 61，空 JAR 标记不适用，保存统计 |
| Server 原租户/权限 | 隔离 RuoYi MySQL/Redis、比较 Bean 图并运行身份及权限回归 | System 现有功能正常，保留数据源装配和定向测试 XML |
| Gateway 独立构建 | 在隔离副本启用原 IoT，打包 exec | 独立 JAR，Server 无 Gateway，保存清单和有效依赖树 |
| Gateway 定向测试 | 三个原测试类，按 ENG-002B/C 命令运行 | XML 记录原 23 项测试的实际数量与失败/跳过 |
| ENG-002B 依赖边界 | 当前检查器加真实有效 POM/依赖树/字节码 | 通过已批准依赖边，保存退出码和反例证据 |
| ENG-002C 数据库约束 | 独立 MySQL、分 schema 连接及事务检查，脚本见前置报告 | 错误 catalog、跨库事务、权限应拒绝，无 master 回退，保存移植及隔离证据 |

## 18. 风险及人工审批

| 风险或未决 | 决策与证据要求 |
| --- | --- |
| GAP-002，项目范围在缓存、任务或查询分叉 | DEC-008，批准权限策略、撤权目标、消费上下文及负向测试。验证抽样之外的所有入口 |
| GAP-003 / BLOCK-004，刷新令牌兼容与长连接撤销 | 批准 access-only 路径及兼容影响，复核停用、缓存回填、退出、订阅和跨节点传播。当前仍开放 |
| GAP-004 / BLOCK-005，私有证据和导出被通用路径或直链读取 | 批准私有 bucket/代理、签名使用边界、临时产物与撤权窗口。在目标部署验证匿名、跨租户和审计。当前仍开放 |
| DEC-007，迟到或不可信时间、换板与读卡器重叠 | 批准关系键和历史修正流程，RFID 清缓存、新鲜窗口、双桶自动归属及计费保留专项 |
| DEC-002/011/014/015/017 | 身份生命周期、领域权威数据、指标、审计及公开线格式保持候选边界，需要各工作包决定 |

下列审批项均为待人工审查。审批记录填写实际批准人、日期、依据 SHA、同意的精确范围、限制、证据与复核方式。任何局部批准只授权其明确实施内容。DEC-007/008 以及相关生产门槛保持开放。

| 人工审批事项 | 待选择或确认 | 批准记录 |
| --- | --- | --- |
| 租户类型及能力模型 | RuoYi 身份＋BUSINESS 扩展、多能力及停用语义 | 待填 |
| 项目独立实体方案 | A；何时容许部门映射 C | 待填 |
| 项目角色与 RuoYi 角色组合 | 功能与项目许可交集、职责分离、平台管理边界 | 待填 |
| 集中授权服务 | 集中策略和领域最终校验、失败关闭、上下文契约 | 待填 |
| 监管范围和生命周期 | 显式逐项目、审批变更撤销、新项目及写权限 | 待填 |
| TenantIgnore 白名单 | 例外负责人、审计方式及受控查询模式 | 待填 |
| 缓存键及撤权一致性 | 键维、版本真值、TTL、SLA、通知丢失及多实例 | 待填 |
| 异步可信上下文 | HTTP、MQ、Quartz、线程传递与清理，用户/服务/设备执行差异 | 待填 |
| 历史有效关系 | 半开区间、关系互斥、迟到、纠错和不可信时间 | 待填 |
| 私有附件及导出 | 代理与签名边界、授权时点、水印与清理 | 待填 |
| 高风险二次认证 | IdP 或 TOTP 候选、复核者及应急撤销 | 待填 |
| 模块及库所有权 | base 及各领域归属、公开契约、候选依赖和本地事务 | 待填 |
| ENG-002D-02 实施范围 | 优先 A；正式库表、装配及新增白名单单独决定 | 待填 |

## 19. 文档交付检查

执行 Git 差异范围和空白检查、相对 Markdown 目标与行号锚点校验、24 项源码编号检查、16 类对象矩阵列数检查、租户例外库存源码匹配及常见凭证模式扫描。191 处链接经检查，其中 Actions 外链已实际获取。13 项审批记录均待填写。提交仅包含本设计文档。
