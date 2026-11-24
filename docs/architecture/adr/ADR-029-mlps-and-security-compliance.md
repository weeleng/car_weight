# ADR-029 网络安全等级保护三级建设与安全合规

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`b3fd4cea75c2b4fe305271eda970df5688765d72`
- 编号、标题、设计及量化指标取自 ENG-001B-02C-05，候选映射与历史批准原文待核验。

## 决策来源

ENG-001B-02C-05 提供候选建设目标。已只读核查 [YudaoWebSecurityConfigurerAdapter.java](../../../yudao-framework/yudao-spring-boot-starter-security/src/main/java/cn/iocoder/yudao/framework/security/config/YudaoWebSecurityConfigurerAdapter.java)、[YudaoSecurityAutoConfiguration.java](../../../yudao-framework/yudao-spring-boot-starter-security/src/main/java/cn/iocoder/yudao/framework/security/config/YudaoSecurityAutoConfiguration.java)、[OAuth2TokenServiceImpl.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/oauth2/OAuth2TokenServiceImpl.java)、[TenantSecurityWebFilter.java](../../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/security/TenantSecurityWebFilter.java)、[TenantDatabaseInterceptor.java](../../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/db/TenantDatabaseInterceptor.java)、[DeptDataPermissionRule.java](../../../yudao-framework/yudao-spring-boot-starter-biz-data-permission/src/main/java/cn/iocoder/yudao/framework/datapermission/core/rule/dept/DeptDataPermissionRule.java)、[FileController.java](../../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/controller/admin/file/FileController.java)、[FileDO.java](../../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/dal/dataobject/file/FileDO.java)、[application.yaml](../../../yudao-server/src/main/resources/application.yaml)、[application-local.yaml](../../../yudao-server/src/main/resources/application-local.yaml)。现有框架具备 Spring Security、Token、BCrypt 密码散列、租户过滤和部门数据范围、文件上传下载。基线开启租户设置，API 加密默认关闭，local 提供 mock 登录配置，FileDO 标注 `@TenantIgnore`。部署时需核验有效配置及附件业务权限。代码与配置不能证明等保符合性或运行覆盖率。

## 背景

SaaS、基础私有化及高可用私有化的定级对象、网络边界与控制责任可能不同。等级保护三级作为建设目标，定级、备案、测评与整改由运营主体按实际项目确定。

## 决策

- 正式阶段核查网络安全法、数据安全法、个人信息保护法、网络数据安全管理规定、GB/T 22239-2019、GB/T 25070-2019、GB/T 28448-2019 及适用密码与行业要求。标准正文、适用版本和有效性须从权威来源核实。
- 逻辑候选区域包括互联网、Web/API、IoT、核心应用、数据库与中间件、运维、日志审计、备份恢复。评估 HTTPS、防火墙、最少端口、分区、入侵检测和防护、限流、恶意流量防护、受控运维入口、堡垒机及基线。Gateway 外部接入和内部服务分别建立信任边界。
- 采用独立身份、最小权限、特权账号管理、管理员保护、生命周期、失败处理、会话、必要 MFA 和运维授权。监管访问须明示项目与数据授权。
- 评估对象级授权、租户隔离、输入校验、SQL 注入及 XSS 防护、附件安全、脱敏、传输和存储保护、完整性、备份与生命周期。审计覆盖认证、管理、高风险业务、设备、导出、运维及第三方。
- 运营候选机制包括资产清单、安全基线、漏洞扫描与整改、补丁及依赖、应急响应、备份演练、账号复核、运维审批、供应商及变更。P0 保留必要安全基础，具体交付与测评独立确认。

| 责任范围 | 候选责任及证据 |
| --- | --- |
| 软件团队 | 认证、授权、数据保护、接入及审计设计，提供代码、配置及验证证据 |
| 基础设施、云及网络设备 | 安全分区、网络防护、入口控制、存储及备份配置，提供环境与演练证据 |
| 运营主体及安全产品供应方 | 定级和测评统筹、人员制度、漏洞处置及产品控制，提供授权和运维记录 |

正式矩阵逐项记录控制项、适用部署、责任方、验证方法、证据、当前状态与待整改项。各方分工以项目部署和合同确认。

## 适用范围

SaaS、基础私有化、高可用私有化及独立 Gateway 部署。

## 约束与禁止事项

MySQL、Redis、RabbitMQ 管理端和内部服务不得默认暴露至互联网。内网地址、共享超级管理员及单一安全组件不得替代身份、授权和整体建设。已鉴权事件依 C-04 持久化，远程审计故障不得无条件阻断。强制审计操作需定义失败策略。禁止为吞吐关闭必要认证、租户隔离和完整性校验，日志禁止明文凭据和不必要敏感数据。当前没有定级、备案、逐条符合性或测评结论。

## 备选方案及取舍

逻辑分区适合统一描述控制点，物理隔离程度需按部署和风险确定。以责任和验收证据评估外置与软件控制。

## 与其他 ADR/ACR 的关系

关联 [ADR-003](ADR-003-saas-tenancy.md)、[ADR-004](ADR-004-organization-and-project.md)、[ADR-005](ADR-005-user-and-permissions.md)、[ADR-030](ADR-030-personal-information-and-data-protection.md)、[ADR-031](ADR-031-authentication-and-access-security.md)、[ADR-032](ADR-032-security-audit-and-traceability.md)、[ADR-033](ADR-033-cryptography-and-iot-security.md)。拓扑关联候选 [ADR-038](ADR-038-deployment-and-environment-management.md)，正文为 Proposed，待批准。遵循 C-01、[C-04](../standards/iot-ack-and-durability.md)、C-05、[C-06](../standards/module-boundaries.md)，证据见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。

## 验证要求

后续验证网络分区、端口、身份及特权、租户项目对象越权、附件、敏感数据、非法设备、审计访问、漏洞整改、配置、备份恢复与应急演练。

## 待确认事项

实际定级与备案、权威标准版本、物理网络、第三方责任、测评整改证据及强制审计失败策略待确定。

## 批准记录

草案待人工审查，历史批准原文、版本、批准人、日期及证据待取得。本次只读核查源码、配置和本地工具版本，未运行安全、合规、功能或性能验收。
