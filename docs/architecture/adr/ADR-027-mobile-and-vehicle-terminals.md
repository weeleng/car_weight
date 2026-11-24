# ADR-027 移动端与车载终端

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`e010b328ccca9c89c2f52841414e9b1d9843d045`
- 编号、标题及设计要点来自 ENG-001B-02C-04，候选映射与历史批准原文待核验。

## 决策来源

ENG-001B-02C-04 提供终端与离线候选设计。已只读核查 [IotDeviceApiImpl.java](../../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/remote/IotDeviceApiImpl.java)、[IotDeviceServiceImpl.java](../../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/service/device/IotDeviceServiceImpl.java)、[YudaoWebSocketAutoConfiguration.java](../../../yudao-framework/yudao-spring-boot-starter-websocket/src/main/java/cn/iocoder/yudao/framework/websocket/config/YudaoWebSocketAutoConfiguration.java)、[application.yaml](../../../yudao-server/src/main/resources/application.yaml)，现有 IoT Gateway 有设备查询、认证及注册 RPC，WebSocket 配置为开启、本地发送，SSE 关闭。IoT 未纳入默认 Reactor。上述能力不能证明串口、蓝牙、移动离线或项目权限已实现。

## 背景

现场可用车载机或手持机独立作业。重连、换终端、断电和离线撤权均需保留真实设备历史。

## 决策

| 形态 | 拟议技术及边界 |
| --- | --- |
| Android 车载主控机 | Android 原生及 Kotlin，经 RS485 连接控制板 |
| 手持 PDA | uni-app 界面及必要 Android Kotlin 通信服务，经 BLE 或经典蓝牙连接控制板 |
| 微信小程序 | 获授权轻量查询或服务，功能及 P0 范围待确认 |
| 管理端 | 沿用 Web 路线 |

- Android 与 PDA 不要求同时部署或在线，蓝牙属于正式业务通信。控制板至终端遵循 VWCP，终端至平台的网络契约独立。分别识别人员、终端、控制板、车辆和任务，换终端保留原始事件身份。
- 候选功能包括登录、车辆设备关联、任务接收及确认、站点、RFID、称重、设备状态、定位、异常说明、离线状态、补传进度及必要完成确认。现场审核、主体补录及变更按业务授权。
- 分别记录控制板生成、终端接收、本地保存、上传、INGEST 事务提交、设备成功 L2 ACK、正式业务处理。服务器确认须可靠关联并传回对应控制板事件，断电、退出或断连须保留尚未获确认事件。
- 终端缓存授权内的有效任务、站点、字典、设备配置、人工操作、待转发或确认事件及同步状态。SQLite 或其他 Android 本地库为候选。同步分别处理原始事件补传、人工操作、任务和基础数据下行、配置版本。事件保留身份及载荷，人工操作独立幂等，任务和配置保留适用范围及版本，迟到按历史关系处理。
- 离线授权须限定范围和期限。用户撤权、终端遗失、车辆重分配、设备解绑、任务取消或项目失效可能要联网后才获知。重连分别核验人工操作和真实历史设备事件。
- 优先控制板 GNSS，无有效点时按当前形态回退 Android 或 PDA。保留定位来源、时间、坐标系及质量，禁止补传时当前位置覆盖历史。评估前台服务、后台限制、串口蓝牙权限、重连、网络切换、进程终止、重启、版本及日志。远程配置要求授权、版本和审计，区分应用与固件升级。
- 评估 Keystore、TLS、本地加密、日志脱敏、退出、解绑撤权、遗失处理及安装包完整性。

## 适用范围

车载及手持作业、终端与控制板通信、离线缓存同步及管理和轻量门户。

## 约束与禁止事项

禁止伪造 L2 ACK 或用终端保存、SQLite 替代 MySQL INGEST 提交。终端 SQLite 与 Gateway 双暂存是不同边界，后者仍为生产启用阻断。禁止拿终端序列号或登录账号替代控制板 UID，禁止用最后写入覆盖解决全部冲突、以撤权删除真实原始事件，或默认允许驾驶员修正正式重量和金额。禁止远程机械控制、APK 硬编码服务端或共享永久凭据。小程序不承担关键设备通信和后台离线转发。

## 备选方案及取舍

纯跨端实现便于复用界面，硬件通信和保活仍需验证原生能力。离线缓存提高连续性，但需明确时效、清理、授权和冲突边界。

## 与其他 ADR/ACR 的关系

遵循 [ADR-012](ADR-012-iot-ingestion.md)、[ADR-013](ADR-013-vwcp-protocol.md)及 [C-04](../standards/iot-ack-and-durability.md)，定位见 [ADR-010](ADR-010-coordinate-system.md)、[ADR-014](ADR-014-gnss-and-tracking.md)，离线匹配见 [ADR-021](ADR-021-collection-operation-execution.md)。身份及保护见 [ADR-030](ADR-030-personal-information-and-data-protection.md)、[ADR-031](ADR-031-authentication-and-access-security.md)。跨模块遵循 C-06，证据见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。

## 验证要求

覆盖两类独立作业、RS485 与蓝牙断连、断网断电重启、进程终止、重复上传、ACK 丢失和未确认事件保留、离线同步版本冲突、撤权与解绑、遗失、定位切换以及空间不足。

## 待确认事项

终端硬件兼容、GATT/SPP、分包重组及云端协议，缓存容量、加密、期限与满载策略、授权及后台恢复、远程配置与小程序范围待专项确认。

## 批准记录

草案待人工审查。历史批准原文、版本、批准人、日期及证据待取得。本次仅核查源码与文档，未执行运行验收。
