# ADR-008 API 规范

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 编号与标题为 ENG-001B-02B-01 提供的候选映射，原始 ADR 正文尚未核验。历史版本与完整批准原文待取得。
- 本次起始基线：`3f831747a7fe0c72b8906366caa26f0145884788`

## 决策来源

ENG-001B-02B-01 提供接口拟议规则。[C-02](../standards/time-conventions.md)、[C-03](../standards/weight-conventions.md)和[C-06](../standards/module-boundaries.md)提供跨模块约束。当前 [BOM](../../../yudao-dependencies/pom.xml)管理 OpenAPI 相关依赖，框架已有 [CommonResult](../../../yudao-framework/yudao-common/src/main/java/cn/iocoder/yudao/framework/common/pojo/CommonResult.java)和 [PageResult](../../../yudao-framework/yudao-common/src/main/java/cn/iocoder/yudao/framework/common/pojo/PageResult.java)。

## 背景

业务接口需要复用框架已有行为，同时避免时间歧义、十进制精度损失、越权访问和重复写入。

## 决策

- 业务 API 遵循现有 RuoYi-Vue-Pro 项目约定，使用 OpenAPI 3 描述。
- 时间采用带时区信息的 ISO 8601。正式重量以精确十进制表达，单位千克。金额使用精确十进制类型。
- 写入接口按业务语义实施幂等控制。权限结合用户角色、项目范围及业务主体授权。
- 外部 API 与内部模块公开 API 分开管理契约。版本管理保持兼容性，破坏性变更须经审批。

## 适用范围

外部 HTTP API 和内部模块公开 API 的契约边界。设备协议见 ADR-012 草案。

## 约束与禁止事项

正式重量及金额不得使用二进制浮点。优先复用当前框架支持，经源码核对后确定返回契约。不得自定义与现有调用方冲突的返回结构或绕过授权。

## 备选方案及取舍

沿用框架契约可降低现有调用方迁移成本。另建契约可独立演进，但增加兼容及维护成本。对外部调用者独立版本化，内部跨模块交互复用公开接口或事件。

## 与其他 ADR/ACR 的关系

适用 C-02、C-03、[C-05](../standards/transaction-boundaries.md)的幂等边界和 C-06。拟与 ADR-001 的公开接口组织及 ADR-006 数据类型衔接。[ACR-001 核对记录](../acr/ACR-001-reconciliation.md)提供约定出处。

## 验证要求

后续核对 OpenAPI 契约与实际请求响应，测试时间及时区往返、重量和金额精度、角色和数据范围权限、重试幂等及契约兼容。本次未执行接口测试。

## 待确认事项

API 版本策略、统一错误码、分页协议、金额序列化、请求签名、幂等键格式和有效期待确认。

## 批准记录

- 本草案审批：待审查。
- 历史批准版本、批准人、日期和可核验的批准证据：待取得。
