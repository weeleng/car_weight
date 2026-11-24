# ADR-031 身份认证与访问安全

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`e010b328ccca9c89c2f52841414e9b1d9843d045`
- 编号、标题及设计要点来自 ENG-001B-02C-04，候选映射与历史批准原文待核验。

## 决策来源

ENG-001B-02C-04 提供认证和会话候选约束。已只读核查：

- [AuthController.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/auth/AuthController.java)、[AppAuthController.java](../../../yudao-module-member/src/main/java/cn/iocoder/yudao/module/member/controller/app/auth/AppAuthController.java)、[AdminAuthServiceImpl.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/auth/AdminAuthServiceImpl.java)、[MemberAuthServiceImpl.java](../../../yudao-module-member/src/main/java/cn/iocoder/yudao/module/member/service/auth/MemberAuthServiceImpl.java)、[OAuth2OpenController.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/oauth2/OAuth2OpenController.java)、[OAuth2TokenServiceImpl.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/oauth2/OAuth2TokenServiceImpl.java)、[OAuth2AccessTokenRedisDAO.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/redis/oauth2/OAuth2AccessTokenRedisDAO.java)、[OAuth2AccessTokenDO.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/oauth2/OAuth2AccessTokenDO.java)、[OAuth2RefreshTokenDO.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/oauth2/OAuth2RefreshTokenDO.java)。后台及会员分别提供登录、退出、刷新入口。Token 服务生成 UUID 字符串，MySQL 持久化 Access Token 和 Refresh Token，Redis 缓存访问检查结果；刷新和退出路径存在。源码中有以有效刷新令牌回退访问检查的兼容路径，适用入口与撤销一致性待实测。现有 Token 使用不透明值，完整 OAuth2/OIDC 互操作性待核验。会员模块未纳入默认 Reactor。
- [YudaoWebSecurityConfigurerAdapter.java](../../../yudao-framework/yudao-spring-boot-starter-security/src/main/java/cn/iocoder/yudao/framework/security/config/YudaoWebSecurityConfigurerAdapter.java)、[TokenAuthenticationFilter.java](../../../yudao-framework/yudao-spring-boot-starter-security/src/main/java/cn/iocoder/yudao/framework/security/core/filter/TokenAuthenticationFilter.java)、[TenantSecurityWebFilter.java](../../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/security/TenantSecurityWebFilter.java)、[PermissionServiceImpl.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/permission/PermissionServiceImpl.java)、[YudaoWebAutoConfiguration.java](../../../yudao-framework/yudao-spring-boot-starter-web/src/main/java/cn/iocoder/yudao/framework/web/config/YudaoWebAutoConfiguration.java)、[ApiEncryptProperties.java](../../../yudao-framework/yudao-spring-boot-starter-web/src/main/java/cn/iocoder/yudao/framework/encrypt/config/ApiEncryptProperties.java)、[XssProperties.java](../../../yudao-framework/yudao-spring-boot-starter-web/src/main/java/cn/iocoder/yudao/framework/xss/config/XssProperties.java)、[application.yaml](../../../yudao-server/src/main/resources/application.yaml)、[application-local.yaml](../../../yudao-server/src/main/resources/application-local.yaml)。Spring Security 配置接入 Token 过滤器、授权规则和 CORS，当前禁用 CSRF。租户过滤和权限服务提供基础能力。API 加密默认关闭，local 有 mock 认证配置；生产配置与适用安全策略须专项验证。
- [IotDeviceApiImpl.java](../../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/remote/IotDeviceApiImpl.java)、[IotDeviceServiceImpl.java](../../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/service/device/IotDeviceServiceImpl.java)、[YudaoWebSocketAutoConfiguration.java](../../../yudao-framework/yudao-spring-boot-starter-websocket/src/main/java/cn/iocoder/yudao/framework/websocket/config/YudaoWebSocketAutoConfiguration.java)、[application.yaml](../../../yudao-server/src/main/resources/application.yaml)。已有设备注册及认证、Gateway 调用和 WebSocket 装配部分，设备 UID、防重放、终端可信绑定及订阅生命周期均需项目验证。

## 背景

平台、企业、监管、设施、驾驶员与现场人员、会员和小程序用户，与 Android/PDA、控制板、Gateway、第三方客户端、内部服务及任务属于不同身份域。

## 决策

- 优先复用框架账号、Token、登录退出、会话、RBAC 及数据权限。认证后服务端校验租户、组织、岗位、项目、资源、操作及数据范围。政府监管通过显式范围授权，政务身份接入以真实接口为准。
- 分别管理人员登录、终端注册和绑定、车辆及控制板关联、任务授权，支持解绑、撤销、重注册。控制板硬件 UID 用于标识，CRC16 用于差错检测。终端转发须有可信身份与授权关系，设备密钥、防重放由专项设计。
- 第三方采用独立客户端身份及最小授权，可评估 OAuth2 客户端凭据、API Key、HMAC、mTLS。Gateway 与平台有明确服务身份，后台及异步执行明确租户上下文、服务权限与来源。
- 定义签发、过期、刷新、主动退出及撤销，覆盖改密、停用、权限变更、终端解绑和泄露。评估登录限频、验证码、初始密码修改、密码复杂度、异常登录、MFA 和管理员保护。按风险平衡现场作业连续性。
- 用户角色授权、跨项目监管、设备注册和解绑、RFID 变更、称重修正、计价、账单、红冲、敏感导出、第三方凭据及安全配置使用独立权限与审计。二次认证、双人复核及审批留待确认。
- 按场景核查 CORS、CSRF、XSS、SQL 注入防护、对象级授权、文件上传、Token 传输、安全头、HTTPS、WebSocket/SSE 认证。订阅和消息授权须处理权限变化及撤销。
- 未授权请求按安全策略处理，已通过接入鉴权的真实设备事件按 C-04 持久化。任务缺失、RFID 异常或用户后续撤权不直接否定原始事实。身份失败、解绑后补传及历史处理由设备安全专项确定。

## 适用范围

人员账号、移动终端、设备和服务、外部客户端、会话撤销及操作授权。

## 约束与禁止事项

禁止用管理员账号混建各身份、只靠菜单隐藏或信任客户端租户、项目、角色。禁止以 TenantIgnore、超级管理员或内网位置代替授权。UID、CRC16 或终端登录均不能独立证明设备可信。禁止宽泛开放跨域与凭据传输、把握手当作永久订阅授权。禁止硬编码生产密码与接口密钥、共享永久设备凭据、明文日志记录完整认证请求或 Token、公开 URL 传递长期凭据及提交生产私钥。禁止绕过设备鉴权或把业务审核设为成功 L2 ACK 前置。现有跨节点撤销时效待验证。

## 备选方案及取舍

独立认证系统可增加定制空间但会重复会话和权限维护。先复用框架，分别补齐人员、终端、设备与第三方的授权边界。针对高风险操作强化认证，避免常规称重反复完整登录。

## 与其他 ADR/ACR 的关系

遵循 [ADR-003](ADR-003-saas-tenancy.md)、[ADR-004](ADR-004-organization-and-project.md)、[ADR-005](ADR-005-user-and-permissions.md)、[ADR-013](ADR-013-vwcp-protocol.md)、[ADR-027](ADR-027-mobile-and-vehicle-terminals.md)、[ADR-028](ADR-028-third-party-integration.md)及 [ADR-030](ADR-030-personal-information-and-data-protection.md)。成功 ACK 见 [C-04](../standards/iot-ack-and-durability.md)。设备安全候选 [ADR-033](ADR-033-cryptography-and-iot-security.md)正文为 Proposed，待批准。遵循 C-06，出处见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。

## 验证要求

覆盖登录、过期与刷新、退出、停用和权限变化、终端解绑、设备身份伪造、第三方撤权和重放、跨租户及对象越权、政府授权、防暴力登录、WebSocket/SSE 订阅越权、高风险操作、日志脱敏、任务租户上下文及接入鉴权和 ACK 边界。

## 待确认事项

有效期、刷新及并发策略、撤销生效时延、密码和 MFA、政务身份、设备安全、回调认证、订阅复核与生产 Web 安全配置待确认。

## 批准记录

草案待人工审查。历史批准原文、版本、批准人、日期及证据待取得。本次仅核查源码与文档，未执行运行验收。
