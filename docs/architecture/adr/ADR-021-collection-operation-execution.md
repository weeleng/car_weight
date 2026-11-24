# ADR-021 清运作业执行

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`f378d3f33d4b96f50eac15e3c361d012bd3b9891`
- 编号、标题及设计要点来自 ENG-001B-02C-03，候选映射与历史批准原文待核验。

## 决策来源

ENG-001B-02C-03 提供现场作业候选流程。[ADR-013](ADR-013-vwcp-protocol.md)提供双终端及事件身份边界。已只读核查 [FileController.java](../../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/controller/admin/file/FileController.java)、[FileServiceImpl.java](../../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/service/file/FileServiceImpl.java)、[FileDO.java](../../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/dal/dataobject/file/FileDO.java)，框架有文件上传、预签名与文件记录管理。FileDO 标注 `@TenantIgnore`，作业证据需在业务层校验租户、项目及访问权限，完成规则待项目实现。

## 背景

现场作业会出现终端离线、未知 RFID、任务变更和迟到事件。接入存证、正式称重、任务完成、交付及计费需要独立状态。

## 决策

- Android 和 PDA 各可独立作业。候选正常流程为接收任务、确认车辆和人员、开始作业、到站、识别责任主体、举升称重、上传原始事件、形成正式称重、确认站点结果、继续下一站或运输交付、完成任务。该流程不构成原始事件接收的先决条件。
- 作业关联任务、车辆、控制板、人员、终端、服务点、RFID、责任主体、项目和线路版本。沿用控制板产生的原始事件身份，业务任务 ID 独立。任务匹配考虑有效事件时间、车辆身份、设备历史安装、项目历史归属、任务有效期及服务点和 RFID 绑定。无法唯一匹配时异常核验。
- 660L 单桶和 240L 双桶遵循 C-03，区分原始重量、毛重、皮重、净重、审核重量和计费重量。只有重量及责任主体可独立确认的子桶才可独立计费。未知 RFID、归属冲突和异常重量保留原始事件并核验。
- 成功 L2 ACK 仅在 INGEST 原始事件及其 Outbox 同一本地事务完成提交后可发出。同身份同载荷按 ADR-013 重传规则处理。同身份不同载荷保留冲突依据及原记录。任务、RFID、审核、计费与处置结果均不阻塞这一确认。
- 区分已授权临时任务、未计划但真实称重、无法匹配事件及人工补充关系。无计划称重能否计费由项目规则和 ADR-024 确认。站点结果可含多桶完成、无垃圾、商户停业、拒绝服务或无法到达。
- 离线补传遵循 ADR-013。终端可按授权和有效期缓存必要任务及业务数据，恢复后去重、按历史归属匹配并核验状态。
- 作业证据可关联原始称重、RFID、定位、围栏、人工记录、照片或附件及异常说明。P0 无需视频或 AI 识别，附件缺失的完成条件按业务确认。
- 执行归 `sanitation-operation`，设备、GIS、主体及计费分别使用 `sanitation-device`、`sanitation-gis`、`sanitation-base`、`sanitation-billing` 的公开 API 或事件契约。

## 适用范围

任务执行、称重与 RFID 证据、异常匹配及离线连续性。

## 约束与禁止事项

禁止均分组合重量、父子重复计费、用任务 ID 替代原始身份、按当前配置覆盖历史或创建虚假计划。禁止因任务未匹配丢弃已鉴权的真实原始事件。到站、RFID 成功、原始事件持久化、正式称重、站点完成、任务完成及垃圾交付分别记录。终端本地保存、RabbitMQ Publisher Confirm、SQLite 或 Redis 均不得替代 INGEST 提交证据。禁止跨模块读取内部 Mapper、DO 或 Biz。

## 备选方案及取舍

同步等待任务和归属可简化正常流程，但会扩大接入事务并阻塞真实事件留存。拟先按 C-04 持久化，后续异步匹配和异常核验。

## 与其他 ADR/ACR 的关系

遵循 [ADR-012](ADR-012-iot-ingestion.md)、ADR-013、[ADR-015](ADR-015-weighing-model.md)、[ADR-016](ADR-016-rfid-identification.md)、[ADR-018](ADR-018-merchant-and-waste-producer.md)、[ADR-020](ADR-020-operation-planning-and-dispatch.md)及 [ADR-024](ADR-024-contract-and-billing.md)。终端同步关联候选 [ADR-027](ADR-027-mobile-and-vehicle-terminals.md)，正文为 Proposed，待批准。遵循 C-02、[C-03](../standards/weight-conventions.md)、[C-04](../standards/iot-ack-and-durability.md)、C-05、C-06，出处见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。RabbitMQ 与 SQLite 双暂存冲突仍为生产启用阻断。

## 验证要求

后续覆盖计划清运、两类终端独立作业、连续多桶、240L 双桶独立归属、未知 RFID、重传、离线补传、迟到匹配、无计划称重、无垃圾、取消、执行中换车，以及存证与计费分离。

## 待确认事项

任务匹配唯一性、异常完成、无计划计费、附件条件、终端缓存和冲突同步待确认。

## 批准记录

草案待人工审查。历史批准原文、版本、批准人、日期及证据待取得。本次仅核查源码和文档，未执行运行验收。
