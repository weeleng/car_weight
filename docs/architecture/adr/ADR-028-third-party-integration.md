# ADR-028 第三方系统集成

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`e010b328ccca9c89c2f52841414e9b1d9843d045`
- 编号、标题及设计要点来自 ENG-001B-02C-04，候选映射与历史批准原文待核验。

## 决策来源

ENG-001B-02C-04 提供第三方候选范围。已只读核查 [PayNotifyController.java](../../../yudao-module-pay/src/main/java/cn/iocoder/yudao/module/pay/controller/admin/notify/PayNotifyController.java)、[PayNotifyServiceImpl.java](../../../yudao-module-pay/src/main/java/cn/iocoder/yudao/module/pay/service/notify/PayNotifyServiceImpl.java)、[PayOrderServiceImpl.java](../../../yudao-module-pay/src/main/java/cn/iocoder/yudao/module/pay/service/order/PayOrderServiceImpl.java)、[IotRabbitMQMessageBus.java](../../../yudao-module-iot/yudao-module-iot-core/src/main/java/cn/iocoder/yudao/module/iot/core/messagebus/core/rabbitmq/IotRabbitMQMessageBus.java)、[TenantRabbitMQInitializer.java](../../../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/mq/rabbitmq/TenantRabbitMQInitializer.java)、[pom.xml](../../../pom.xml)、[pom.xml](../../../yudao-server/pom.xml)。现有 Pay 由渠道客户端解析通知，按渠道租户处理订单，并有异步通知任务及记录。RabbitMQ 实现具备发布确认及消费确认入口。默认 Reactor 未装配 Pay 和 IoT。渠道签名、重试及可信接收的具体行为须按实际接入核验，现有代码不能证明已接入政府或财税生产系统。

## 背景

监管、城管、设施、地磅、开票、支付、地图和短信通知各有独立接口和故障域。核心业务需要可靠交付、明确结果与对账能力。

## 决策

- 通用适配归 `sanitation-integration`。GIS 厂商由 `sanitation-gis`，设备由独立 Gateway，财务规则由 `sanitation-billing` 负责。领域通过公开契约交换数据。
- 按 ADR-008 明确版本、请求响应、业务和外部身份、租户项目授权、时间、重量单位、坐标系、错误、幂等与追踪标识及来源。外部编码显式映射。监管交换可含档案、车辆设备、正式称重、任务执行、轨迹、交付、异常及授权报表，字段、频率、格式和推送方式以真实接口授权为准。
- 业务记录与 Outbox 在同一本地事务提交。提交后异步投递，创建或关联推送任务，调用第三方并记录响应，按契约确认、重试或人工核验。Outbox、Publisher Confirm、HTTP 200 和第三方业务成功分别记录。
- 使用稳定请求身份、有限重试、超时及限流，评估指数退避，支持失败任务、人工重推、状态查询和对账。财务接口超时且幂等性未知时先查外部状态或人工核验。
- 回调校验来源、完整性、防重放、格式和业务关联，执行幂等、状态转换校验及审计。租户来源须由可信关系确认。回调 ACK 按各接口定义，与本地可靠接收语义一致。
- P0 保留完整电子发票候选，包括申请、请求、状态查询、结果、文件、红冲或红字、异常和对账。P1 在线支付候选可评估现有 Pay 复用，未启用时按实际缴费方式确认。地磅 P0 为人工录入及接收确认，自动接入待评估外部设备、车辆、计量及交付身份。
- 分接口评估 HTTPS、OAuth2 客户端凭据、API Key、HMAC、mTLS、IP 白名单、时间戳和 Nonce、凭据轮换与审计。对故障明确标记和恢复，限制线程、连接及消费资源耗用。

## 适用范围

监管和业务系统的推送、回调、对账与故障隔离。

## 约束与禁止事项

不得让政府、地图、财税、支付或通知故障阻断已鉴权原始称重的 INGEST 持久化。禁止在 INGEST 原始事件与 Outbox 事务中调用外部服务、无限重试、未核验状态重复开票、按 HTTP 成功记开票或生成虚假支付。回调 ACK 不替代设备 L2 ACK。禁止默认共享完整个人或设备原始数据、让地磅覆盖商户正式称重、覆盖内部编码或重复维护正式主数据。IP 白名单不能作为通用唯一认证。凭据禁止入库到 Git。

## 备选方案及取舍

同步外调反馈直接但会扩大故障域。可靠异步和状态查询可降低事务耦合，代价是维护推送状态、幂等和对账。

## 与其他 ADR/ACR 的关系

遵循 [C-05](../standards/transaction-boundaries.md)及 C-06，[ADR-008](ADR-008-api-conventions.md)、[ADR-009](ADR-009-gis-architecture.md)、[ADR-023](ADR-023-disposal-facility-and-delivery.md)、[ADR-024](ADR-024-contract-and-billing.md)约束契约及职责。[ADR-030](ADR-030-personal-information-and-data-protection.md)、[ADR-031](ADR-031-authentication-and-access-security.md)约束数据及认证。C-04 的双暂存阻断继续适用。出处见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。

## 验证要求

覆盖正常、超时和不可用、HTTP 成功但业务失败、重复与无效来源回调、重放、任务恢复、开票及地磅防重、编码映射、跨租户越权、外调不阻断接入、人工重推和对账。

## 待确认事项

实际接口、授权和凭据、回调确认及重试参数、财税平台资质、发票类型及业务规则、P1 支付与地磅自动接入待确认。

## 批准记录

草案待人工审查。历史批准原文、版本、批准人、日期及证据待取得。本次仅核查源码与文档，未执行运行验收。
