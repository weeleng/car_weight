# ADR-017 资产与设备管理

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`e7c025f623191928633dfc843c070e38d1cb4bca`
- 编号、标题和设计要点来自 ENG-001B-02C-01，候选映射与历史批准原文待核验。

## 决策来源

ENG-001B-02C-01 提供车辆资产、设备绑定和校准方案。已只读检查 [IotDeviceDO.java](../../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/dal/dataobject/device/IotDeviceDO.java)、[IotDeviceController.java](../../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/controller/admin/device/IotDeviceController.java)、[IotDeviceServiceImpl.java](../../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/service/device/IotDeviceServiceImpl.java)、[IotDeviceApiImpl.java](../../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/remote/IotDeviceApiImpl.java)、[IotDeviceMessageSubscriber.java](../../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/mq/consumer/device/IotDeviceMessageSubscriber.java)、[pom.xml](../../../yudao-server/pom.xml)、[根 POM](../../../pom.xml)。IoT Biz 已有带租户数据基类的设备模型，含产品、设备名、序列号、网关、运行状态及固件字段，并提供设备管理、注册、认证和网关关联。Gateway 有远程设备 API，Biz 订阅者按消息租户进入上下文。当前默认 Reactor 未启用 IoT，服务代码与生产验收分别取证。

## 背景

设备更换、调拨与重新校准会改变当前配置。称重业务需要回溯当时的设备身份、安装及校准版本。

## 决策

- 管理清运车辆、称重控制板、传感器、RFID 读卡器、Android 车载终端、手持 PDA、GNSS 及必要配套设备。按维护需要区分独立资产、组件和配置记录。
- 车辆档案含内部唯一标识、车牌号、车型、租户、运营项目、状态、称重机构、关联设备及必要技术和维保信息。支持 660L 单桶及 240L 双桶。
- 将设备业务档案 ID 与稳定硬件 UID 分离，事件身份沿用 ADR-013。档案可表达硬件身份、型号、硬件和固件版本、通信能力、租户、当前绑定、运行状态及安装维护记录。
- 记录车辆与控制板、终端、传感器和读卡器的安装历史及生效时间，覆盖初装、更换、调拨、项目变更、拆除、维修和重装。
- 版本化记录称重模式、传感器型号与量程、通道映射、校准参数、时间、结果及相关操作。四路 HX711、500kg 和 800kg 传感器为拟议硬件。历史称重可追溯当时的设备和校准版本。
- 分别表达资产管理、连接、故障、车辆运营与配置状态。设备离线与资产退役分别处理，在线也需要功能检查。

## 适用范围

车辆与设备资产、历史安装、校准配置和运行状态。

## 约束与禁止事项

车牌号不作为唯一且不可变的内部主键。禁止当前绑定改写历史事件或重新校准覆盖原始重量。P0 无需独立垃圾桶资产档案，缺少档案不得阻断 RFID 或称重。设备接入认证、密钥及证书留待安全专项。

## 备选方案及取舍

仅保存当前归属和校准配置维护简单，但无法解释迟到称重或维修后的历史数据。拟保存有效期和版本，并按维护价值选择资产粒度。

## 与其他 ADR/ACR 的关系

可信归属和项目历史见 [ADR-003](ADR-003-saas-tenancy.md)、[ADR-004](ADR-004-organization-and-project.md)。身份和称重见 [ADR-013](ADR-013-vwcp-protocol.md)、[ADR-015](ADR-015-weighing-model.md)。标签业务归 [ADR-016](ADR-016-rfid-identification.md)及 [ADR-018](ADR-018-merchant-and-waste-producer.md)。适用 C-02、C-03、C-05、C-06，出处见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。

## 验证要求

后续覆盖设备更换、跨项目调拨、重复硬件身份、设备退役、历史安装与校准版本、跨租户绑定以及无垃圾桶档案的正常称重。

## 待确认事项

资产、组件划分，配置参数格式、校准算法及验收标准、下发协议、心跳周期、离线阈值、告警策略与设备认证方案待确认。现有设备字段与 C-02 的时间要求在项目落地时需复核，例如现有设备运行时间字段使用 LocalDateTime。

## 批准记录

本草案待人工审查，历史批准原文、版本、证据、批准人及日期待取得。本次仅核查源码与文档，未执行运行验收。
