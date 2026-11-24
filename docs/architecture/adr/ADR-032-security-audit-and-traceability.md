# ADR-032 安全审计与操作追溯

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`b3fd4cea75c2b4fe305271eda970df5688765d72`
- 编号、标题、设计及量化指标取自 ENG-001B-02C-05，候选映射与历史批准原文待核验。

## 决策来源

ENG-001B-02C-05 提供审计候选。已只读核查 [LoginLogServiceImpl.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/logger/LoginLogServiceImpl.java)、[OperateLogServiceImpl.java](../../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/logger/OperateLogServiceImpl.java)、[ApiAccessLogFilter.java](../../../yudao-framework/yudao-spring-boot-starter-web/src/main/java/cn/iocoder/yudao/framework/apilog/core/filter/ApiAccessLogFilter.java)、[GlobalExceptionHandler.java](../../../yudao-framework/yudao-spring-boot-starter-web/src/main/java/cn/iocoder/yudao/framework/web/core/handler/GlobalExceptionHandler.java)、[ApiAccessLogSanitizeTest.java](../../../yudao-framework/yudao-spring-boot-starter-web/src/test/java/cn/iocoder/yudao/framework/apilog/core/ApiAccessLogSanitizeTest.java)、[DesensitizeTest.java](../../../yudao-framework/yudao-spring-boot-starter-web/src/test/java/cn/iocoder/yudao/framework/desensitize/core/DesensitizeTest.java)、[application-local.yaml](../../../yudao-server/src/main/resources/application-local.yaml)。已有登录与操作日志写入、API 访问日志过滤和异常日志异步入口。访问日志可采集查询、请求和响应并调用脱敏，异常路径也构造脱敏参数。local 关闭访问日志。脱敏与日志测试文件存在，未在本次执行，不能据此确认全部日志安全。

## 背景

运行日志、API 访问和异常、登录认证、操作、安全审计、业务历史、设备原始事件和第三方调用记录具有不同用途及保留策略。日志清理不得删除业务存证。

## 决策

- 审计覆盖登录退出与失败、角色和项目监管授权、设备注册绑定及解除、车辆控制板关联、校准及配置、RFID 变更、称重修正及异常确认、计价合同账单、发票与红冲、敏感查询导出、第三方凭据变更、安全及运维和恢复。
- 候选模型记录事件 ID、类型、发生、接收、持久化时间、主体类型及 ID、租户及项目、对象类型及 ID、操作结果、失败原因、来源、追踪和事件关联、必要变更前后值、原因、审批及完整性信息。时间遵循 C-02，字段最小化。
- 称重修正保留原正式重量、修正值、原因、人员与时点、原始事件、材料、复核、账单影响及财务调整。RFID 变更保留原新主体、UID、有效起止、原因、人员及范围，历史按事件当时关系追溯。
- 区分同事务业务审计、Outbox 异步转发的安全审计、普通日志。关键操作在所属逻辑库本地事务写入审计，或同事务写入可重建证据的 Outbox。转发可复核、重试及幂等，外部平台故障不丢失已提交证据。
- 候选防篡改为追加式存储、权限分离、独立归档、哈希链、周期性校验、独立可信校验点、WORM、备份与恢复验证。哈希链单独使用无法防止有权重写整库的管理员。另评估密钥隔离、外部存证及独立锚点。
- 安全审计日志候选至少 180 天。依据法律、等保、合同、目的、类别、隐私和成本确定正式保留，财税、存证和原始事件另定。查询与导出独立授权，按角色和作用域限制安全管理员、审计、运维、企业及监管人员，导出留痕。

## 适用范围

高风险业务审计、认证、操作、设备安全事件、导出、运维及第三方追溯。

## 约束与禁止事项

禁止无条件记录完整请求响应、全量字段、密码、完整 Access/Refresh Token、设备或第三方密钥、私钥及不必要个人信息。禁止按当前 RFID 绑定改写历史归属，禁止无调整记录修改账单。禁止依赖远程日志构成跨库原子事务或仅依赖异步日志注解。普通业务管理员不得默认删除审计。180 天为工程候选，不是所有数据的统一法定期限。

## 备选方案及取舍

普通异步日志适于观察，高风险操作需要本地证据或可重建事件。追加存储和独立校验增强验证能力，需付出保留、权限分离和恢复演练成本。

## 与其他 ADR/ACR 的关系

遵循 [C-02](../standards/time-conventions.md)、C-03、[C-05](../standards/transaction-boundaries.md)、[C-06](../standards/module-boundaries.md)。关联 [ADR-015](ADR-015-weighing-model.md)、[ADR-016](ADR-016-rfid-identification.md)、[ADR-024](ADR-024-contract-and-billing.md)、[ADR-029](ADR-029-mlps-and-security-compliance.md)、[ADR-030](ADR-030-personal-information-and-data-protection.md)。证据见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。

## 验证要求

后续核验失败登录、权限、称重、RFID、账单红冲、校准、高风险操作写入失败、重投、篡改检测、越权、导出、归档恢复和日志凭据泄露。

## 待确认事项

必须审计操作清单、失败时拒绝策略、防篡改实现、独立存证、脱敏覆盖和正式分期保留待核验。

## 批准记录

草案待人工审查，历史批准原文、版本、批准人、日期及证据待取得。本次只读核查源码、配置和本地工具版本，未运行安全、合规、功能或性能验收。
