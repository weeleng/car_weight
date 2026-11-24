# ADR-033 密码体系与 IoT 设备安全

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`b3fd4cea75c2b4fe305271eda970df5688765d72`
- 编号、标题、设计及量化指标取自 ENG-001B-02C-05，候选映射与历史批准原文待核验。

## 决策来源

ENG-001B-02C-05 提供候选防护边界。已只读核查 [IotTcpProtocol.java](../../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/protocol/tcp/IotTcpProtocol.java)、[IotTcpUpstreamHandler.java](../../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/protocol/tcp/handler/upstream/IotTcpUpstreamHandler.java)、[IotDeviceApiImpl.java](../../../yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/service/device/remote/IotDeviceApiImpl.java)、[IotDeviceServiceImpl.java](../../../yudao-module-iot/yudao-module-iot-biz/src/main/java/cn/iocoder/yudao/module/iot/service/device/IotDeviceServiceImpl.java)、[application.yaml](../../../yudao-module-iot/yudao-module-iot-gateway/src/main/resources/application.yaml)。源码具备 TCP 服务、设备认证调用及注册路径，Gateway 示例 TCP 未启用，总线配置为 Redis。现有实现尚未验证完整 VWCP、逐设备密钥管理或生产安全。

## 背景

独立 Android RS485 与 PDA 蓝牙两种形态须防非法设备、终端转发篡改、历史重放、配置及固件替换和凭据撤销后的持续接入。ADR-013 候选不变，包括 STM32G0B1RET6、12 字节 UID、4 路 HX711、3 路 ISO15693、3 路数字输入、RS485、BLE 和经典蓝牙、GNSS、帧头 AA55、CRC16、小端、Unix Epoch 毫秒、INT32 克与 16 MiB 环形暂存。

## 决策

- 分别管理硬件 UID、业务设备 ID、认证凭据、Android/PDA、用户、车辆和 Gateway 服务身份。分别评估控制板至 Android RS485、至 PDA 蓝牙、终端至云、Gateway 至平台及第三方至平台信任边界。连接、配对或 TLS 只覆盖各自链路或身份，控制板事件来源另行验证。
- 优先评估每设备独立凭据。候选为独立对称密钥、HMAC-SHA256、AES-CMAC、挑战应答、设备证书及适用国密。根据 MCU 资源、固件复杂度、带宽、密钥保护与合规选型。
- 优先评估由控制板生成可验证完整性信息。保护候选范围包括协议版本、消息类型、UID、Epoch、Seq、事件时间、原始重量、RFID 和设备状态。终端原样转发受保护载荷，附加定位、用户和任务有独立来源与信任属性。消息认证不证明物理计量准确。
- 按 `硬件 UID + Epoch + Seq` 区分合法离线补传、网络重试、已提交事件重复、同身份不同载荷、非法重放及计数异常。同身份同载荷且已经提交的合法重试可幂等成功。同身份异载荷隔离取证，禁止覆盖或普通成功 ACK。评估 Epoch 持久化、Seq 单调与回绕、复位、换板、长期离线、轮换、处理记录和状态恢复。
- 凭据流程涵盖生产登记、UID、独立凭据生成与安全注入、注册激活、安装、版本与轮换、解绑、报废和撤销。按实际硬件评估 Flash 读保护、调试接口、固件区、密钥隔离、完整性、安全启动与外置安全芯片。
- 解绑、失窃、车辆调拨和撤销时区分新事件、撤销前真实历史事件、已持久化、待上传和不明来源。新事件不能无条件接受撤销凭据，历史事件按专项的认证、隔离、人工核验处理。
- 成功 L2 ACK 以 INGEST 原始事件与 Outbox 同一事务提交为前提。评估 ACK 的身份与完整性保护及明确事件关联，防止替换、伪造或重放导致释放未确认事件。
- 校准、换算、模式、RFID、通信、定位和安全配置应授权、版本化并审计。评估配置完整性、回退约束及恢复。分别验证固件、Android/PDA 和 Gateway 更新的来源、签名、版本、防降级、失败恢复与未确认事件保留。
- 评估 SM2、SM3、SM4、适用国密 TLS、密钥管理和商用密码应用安全性评估。错误分类覆盖格式、CRC、认证、消息完整性、撤销、重放、身份冲突、提交失败及正常重复，分别设计拒绝、隔离、审计、限流和重试。

## 适用范围

控制板、移动转发、Gateway、平台及外部边界的认证、完整性和生命周期。

## 约束与禁止事项

UID 和 RFID UID 为标识，CRC16 为差错检测，均不得用于密码学认证。禁止共享永久设备密钥、自制未经审查算法、明文密钥入 Git/固件/APK/日志、仅以事件时间判断重放、仅凭连接确认可信或将不明事件计费。认证通过、终端保存、Publisher Confirm 和 SQLite 均不能替代 L2 ACK；双暂存冲突仍为生产启用阻断。禁止远程危险机械控制、改写历史、升级清除未确认事件或假定已有安全芯片与安全 Bootloader。

## 备选方案及取舍

逐设备对称方案需安全注入和轮换，证书方案便于体系化信任但增加 MCU 与运维成本。选型前需要威胁模型、合规和性能对比。Flash 读保护与硬件安全模块分别评估。

## 与其他 ADR/ACR 的关系

对齐 [ADR-013](ADR-013-vwcp-protocol.md)、[ADR-012](ADR-012-iot-ingestion.md)、[ADR-017](ADR-017-asset-and-device-management.md)、[ADR-027](ADR-027-mobile-and-vehicle-terminals.md)、[ADR-031](ADR-031-authentication-and-access-security.md)及 [ADR-029](ADR-029-mlps-and-security-compliance.md)。遵循 C-02、[C-04](../standards/iot-ack-and-durability.md)、C-05、[C-06](../standards/module-boundaries.md)，证据见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。

## 验证要求

后续覆盖非法接入、独立认证、UID 伪造、称重/RFID/转发篡改、重放、离线和同身份冲突、Epoch/Seq、重启、轮换撤销与解绑、ACK 关联与丢失重试、非法配置、升级失败、MCU 运算和安全吞吐。

## 待确认事项

最终算法、密钥长度、字节规范、保护范围、认证码、协议升级、重放状态、凭据注入权限、历史接收、ACK 安全、MCU/PCB 能力及适用密码评估待确认。

## 批准记录

草案待人工审查，历史批准原文、版本、批准人、日期及证据待取得。本次只读核查源码、配置和本地工具版本，未运行安全、合规、功能或性能验收。
