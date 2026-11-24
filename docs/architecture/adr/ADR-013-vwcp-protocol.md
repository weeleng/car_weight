# ADR-013 VWCP 设备通信协议

## 状态与版本

- 状态：`Proposed`
- 版本：草案 v0.1
- 起始基线：`65af9b997071ff3a0e63c289b5ce33fd191d2696`
- 编号、标题及历史设计要点由 ENG-001B-02B-02 提供，候选映射及历史批准原文待核验。

## 决策来源

ENG-001B-02B-02 提供硬件、协议参数及重复事件处理的拟议要点。[ADR-012 接入草案](ADR-012-iot-ingestion.md)与 [C-04](../standards/iot-ack-and-durability.md)、[C-05](../standards/transaction-boundaries.md)界定持久化和确认边界。

## 背景

控制板拟采用 STM32G0B1RET6、12～36V 车载供电、四路 HX711、三个 ISO15693 RFID 读卡器、三路数字输入、RS485、BLE、经典蓝牙、GNSS 及本地离线存储。具体实现待硬件设计确认。

## 决策

形态 A 通过 RS485 将控制板接入 Android 车载终端，形态 B 通过蓝牙接入手持 PDA。两种都是正式部署形态，由各自终端上传。终端转发原始事件身份，切换、重连和重传不得引发重复称重或计费。

| 项目 | 拟议参数 |
| --- | --- |
| 帧头、校验、字节序 | AA 55、CRC16、Little Endian |
| RS485 串口 | 115200、8N1 |
| 时间、重量 | Unix Epoch 毫秒、有符号 INT32 克 |
| 硬件 UID、RFID UID | 12 字节、8 字节 |
| 事件身份 | 硬件 UID + Epoch + Sequence |
| 离线容量 | 16 MiB 环形存储设计目标 |

事件身份中的 Epoch 与 Unix Epoch 时间戳分别处理。控制板重启、断电或切换终端须保留原事件身份，恢复后以该身份补传。

| INGEST 结果 | 拟议 L2 ACK 处理 |
| --- | --- |
| 原始事件及对应 Outbox 已在同一本地事务提交 | 可生成成功 L2 ACK |
| 相同身份、相同有效载荷，原事务已提交 | 可重发成功 L2 ACK，不重复创建原始事件或 Outbox |
| 相同身份、不同载荷 | 记录必要异常证据，保留原事件，禁止按普通重复事件返回成功 ACK |
| 事务未成功提交 | 禁止成功 L2 ACK |

成功 ACK 丢失后，设备沿用原事件身份重传。未收到成功 L2 ACK 的称重事件禁止被静默删除或覆盖。存储满载须有明确异常状态。

## 适用范围

设备、两种上传终端、Gateway 与 INGEST 的协议及补传边界。

## 约束与禁止事项

禁止远程控制垃圾车液压举升或倾倒动作。CRC16 仅用于传输校验，禁止用于身份认证或加密。禁止变更事件身份绕过去重。终端本地接收、SQLite 暂存、Redis 缓存及 RabbitMQ Publisher Confirm 均不能替代服务器成功 L2 ACK。RabbitMQ 与 SQLite 双暂存冲突仍为生产启用阻断，当前 Gateway 完整 VWCP 能力待验证。参见 [Gateway 分析](../../engineering/iot-gateway-analysis.md)及[历史验证状态](../../engineering/validation-status.md)。

## 备选方案及取舍

终端生成身份便于各自上传，但会破坏跨终端去重。拟统一使用控制板事件身份。提前确认可缩短等待，但不能满足 INGEST 提交条件。

## 与其他 ADR/ACR 的关系

延续 ADR-012、C-04、C-05，时间和重量遵循 [C-02](../standards/time-conventions.md)、[C-03](../standards/weight-conventions.md)。称重与标签语义见 [ADR-015](ADR-015-weighing-model.md)、[ADR-016](ADR-016-rfid-identification.md)，约定来源见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。

## 验证要求

后续覆盖 CRC 正确与错误帧、半包、粘包、截断和非法长度。验证重复事件、同身份不同载荷、ACK 丢失、断电重启、Epoch/Sequence 生命周期、Android/PDA 切换、离线补传、存储满载、事务失败及提交后发送 ACK 前崩溃。16 MiB 容量与耐久性尚未验证。

## 待确认事项

CRC16 多项式、初始值、反转、覆盖范围，帧长度、命令码、字段偏移、最大报文长度，ACK 状态码、载荷一致性判断、重传协议、Epoch/Sequence 生命周期及已确认事件回收策略待专项确认。

## 批准记录

本草案待人工审查。历史批准原文、版本、证据、批准人及日期待取得。本文所列后续验证均未在本次任务中运行。
