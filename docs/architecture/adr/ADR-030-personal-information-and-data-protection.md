# ADR-030 个人信息与数据保护

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`e010b328ccca9c89c2f52841414e9b1d9843d045`
- 编号、标题及设计要点来自 ENG-001B-02C-04，候选映射与历史批准原文待核验。

## 决策来源

ENG-001B-02C-04 提供分类、保护和合规核查范围。已只读核查 [ApiAccessLogFilter.java](../../../yudao-framework/yudao-spring-boot-starter-web/src/main/java/cn/iocoder/yudao/framework/apilog/core/filter/ApiAccessLogFilter.java)、[OperateLogDO.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/logger/OperateLogDO.java)、[FileController.java](../../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/controller/admin/file/FileController.java)、[FileServiceImpl.java](../../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/service/file/FileServiceImpl.java)、[FileDO.java](../../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/dal/dataobject/file/FileDO.java)、[application-local.yaml](../../../yudao-server/src/main/resources/application-local.yaml)。访问日志有参数和响应脱敏入口，local 配置关闭访问日志。文件支持上传、下载、预签名及记录管理，FileDO 使用 `@TenantIgnore`，通用访问控制不能作为附件业务归属的证据。

## 背景

部署地区、主体角色、授权场景和数据可识别性影响适用要求。需专项核查网络安全法、数据安全法、个人信息保护法、网络数据安全管理规定及适用行业和地方要求。法律依据、认证与留存期限待正式审查。

## 决策

- 候选分类包括公开基础、企业内部、个人及敏感个人信息、财税、安全凭据、原始事件、定位轨迹、运行和审计数据。按用途、敏感度和可识别性识别姓名、手机、证件、地址、驾驶员、车辆人员关系、轨迹、自然人商户与联系人、照片及日志。社会信用代码按主体与可识别性判断。
- 明确目的和最小必要采集。SaaS 平台、运营方、监管方及设施的处理者、受托方、提供方和接收方角色按实际关系确定，记录处理目的、依据、告知、适用授权或同意及合同责任。
- 按角色、租户、项目和对象控制字段、轨迹和下载。评估手机、证件脱敏、敏感查询审计、导出和公开展示限制。轨迹查询须有授权项目、时间及用途，批量导出单独授权和审计。
- 作业照片、交付凭证、报表及发票附件落实业务归属，下载重新授权。评估私有存储、短期签名、类型和大小限制、恶意文件防护、有效期及下载审计。评估 TLS、存储及字段加密、分离与轮换密钥、备份加密和移动端保护。
- 分别制定原始事件、正式称重、财务、轨迹、联系人、附件、导出、审计及备份的保留策略。依法需要保留时评估限制访问、解除关联或匿名化。按适用要求支持查询、更正、补充、删除请求、撤回同意、处理限制与投诉记录。
- 第三方共享明确目的、必要范围、依据、接收方及审计。专项核查 SaaS 与私有化的存储区域、运维访问、外部服务与跨境要求。非生产和 AI 辅助开发优先使用模拟或适当脱敏数据，评估重新识别风险。

## 适用范围

业务、设备、文件、审计与非生产数据的采集、使用、共享、留存和删除。

## 约束与禁止事项

禁止基于表名划级、为了潜在用途无条件采集证件或生物识别信息、按租户类型自动判断处理责任。脱敏不替代授权。公开地图及大屏不得默认展示完整电话、证件或可识别详细轨迹。禁止以文件 ID 或 URL 代替业务授权，禁止明文日志记录凭据。原始事实防覆盖不构成永久保存要求，禁止直接删除历史称重或财务事实应付个人信息请求，也不得承诺立即物理删除全部数据。禁止默认向境外服务开放生产数据，禁止将生产个人信息、轨迹、财务或凭据提交公开 Git，或未经批准发送给外部 AI 服务。

## 备选方案及取舍

统一脱敏便于操作但缺少用途与角色差异。按分类、用途和业务归属组合访问与保留规则，需同步验证日志、导出和备份。

## 与其他 ADR/ACR 的关系

租户及项目遵循 [ADR-003](ADR-003-saas-tenancy.md)、[ADR-004](ADR-004-organization-and-project.md)、[ADR-005](ADR-005-user-and-permissions.md)，原始事实边界见 [ADR-006](ADR-006-database-architecture.md)，地图见 [ADR-026](ADR-026-kitchen-waste-thematic-map.md)。共享及认证见 [ADR-028](ADR-028-third-party-integration.md)、[ADR-031](ADR-031-authentication-and-access-security.md)，密码体系候选 [ADR-033](ADR-033-cryptography-and-iot-security.md)正文为 Proposed，待批准。遵循 C-05、C-06，出处见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。

## 验证要求

覆盖字段权限及脱敏、跨租户监管、导出、文件越权、轨迹批量审计、日志泄露、移动端保护、保留删除、非生产与第三方共享审计。法律合规及等保均尚待专项核验。

## 待确认事项

分类分级、各方责任和依据、留存删除时限、对象存储、审计、密码体系、数据主体流程及跨境方案待确认。

## 批准记录

草案待人工审查。历史批准原文、版本、批准人、日期及证据待取得。本次仅核查源码与文档，未执行运行验收。
