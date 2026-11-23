# C-04 IoT ACK 与持久化

## 适用范围

从设备上报、INGEST 接收、L2 ACK 到 RabbitMQ 投递与故障恢复。

## 已确认约束

- L2 ACK 仅表示原始 INGEST 事件及其 Outbox 记录已在同一本地事务中成功提交。正式称重、责任主体归属和计费有各自的业务状态。
- ACK 丢失时设备可能重传。服务器基于事件身份做幂等处理。事件身份包含硬件 UID、Epoch、序列号等已批准字段，此处的 Epoch 属于事件身份，设备时间的表示见[时间规范](time-conventions.md)。
- RabbitMQ 消息按至少一次投递处理，消费方必须容忍重复。
- RPO=0 只能在对应故障域以及数据库持久化和高可用条件验证后声明。
- 现有 Gateway 的 RabbitMQ 与 SQLite 双暂存服务冲突仍为生产启用阻断，详见 [Gateway 分析](../../engineering/iot-gateway-analysis.md)。

## 禁止事项

不得在可靠持久化成功之前发送成功 L2 ACK，不得用 RabbitMQ 发布确认、SQLite 暂存或 Redis 缓存替代已提交的 INGEST 事务证据。

## 验证要求

测试事务失败、提交前中断、提交后 ACK 丢失及设备重传，检查成功 L2 ACK 的时点和原始事件、Outbox 的幂等结果。验证 RabbitMQ 重复投递。RPO=0 需要按目标故障域验证持久化和高可用。解除暂存阻断需要在实际 RabbitMQ 配置下验证启用、补传和故障恢复。

## 来源与待核验内容

来源为 ENG-001B-01 提供的 C-04。[事务边界](transaction-boundaries.md)定义相关本地提交点。完整事件身份字段、Epoch 语义及取值、协议编码、ACK 超时与重试参数待核验。
