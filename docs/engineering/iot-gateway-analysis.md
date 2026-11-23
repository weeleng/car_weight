# IoT Gateway 专项分析

源码基线为 `2ff45ae44a28f483e40ba63315bd0a03c30859c4`。以下为源码核查，执行结果见 [构建报告](jdk21-build-report.md)。

## 启动、TCP 和二进制扩展

Gateway 是独立 Spring Boot 应用，入口为 `yudao-module-iot/yudao-module-iot-gateway/src/main/java/cn/iocoder/yudao/module/iot/gateway/IotGatewayServerApplication.java`，POM 配置了 `repackage` 并附加 `exec` classifier。同模块 `src/main/resources/application.yaml` 默认使用 `local` profile 和 Redis 总线，TCP 示例 `tcp-json` 默认关闭，端口为 8091。Gateway 通过配置的 RPC URL 调用业务端接口。

Gateway 包 `protocol` 下的 `IotProtocolManager` 管理已启用协议实例。 `tcp/IotTcpProtocol` 创建 Vert.x NetServer 并应用连接数和空闲超时配置。 `tcp/manager/IotTcpConnectionManager` 按 socket 和设备维护索引，重复登录置换旧连接，注销时按 socket 条件清除反向索引。 `tcp/handler/upstream/IotTcpUpstreamHandler` 执行认证和连接清理，将阻塞业务分派给每连接有序 worker，积压可触发断开。

二进制扩展有两层。 `IotTcpFrameCodecFactory` 选择分隔符、长度字段或定长的 `IotTcpFrameCodec`，处理流的分帧和封帧。结构化模式由 `IotMessageSerializerManager` 选择 JSON 或 `IotBinarySerializer`。设备认证声明 raw 模式后，`IotDeviceMessageServiceImpl.sendRawMessage` 对一帧执行产品解析脚本的 decode。下行经过 `encodeDownstreamCommand` 后再封帧。复杂协议若超出已有拆帧模型，需要单独的帧编解码器或协议适配器。TCP 普通业务上报成功路径没有即时成功回复，透传上报失败路径也不能直接当作项目 ACK 规则。

## RabbitMQ 与 Core/Biz 契约

Core 的 `messagebus/config/IotMessageBusAutoConfiguration` 按 `yudao.iot.message-bus.type` 和类路径选择实现。 `IotRabbitMQMessageBus` 为订阅组创建持久队列并以 routing key 绑定 topic exchange。确认发送使用独立 mandatory template，需要 correlated publisher confirm 和 returns。正 ACK 且无 return 表示 broker 接收并路由。超时仍可能已接收，重试可能重复。消费者处理后手动 ACK，只有首次投递因 `ConfirmException` 失败才重新入队，普通异常直接 NACK 且不重新入队。生产队列策略、异常恢复和死信处理尚待实测。

Core 的 `mq/message/IotDeviceMessage` 和 `mq/producer/IotDeviceMessageProducer` 定义共享消息。上行 topic 为 `iot_device_message`，按 gateway serverId 定向的下行 topic 为 `iot_device_message_<serverId>`。消息含 id、requestId、method、deviceId、tenantId、serverId、reportTime、params/data、code/msg、raw、spoolTime。Biz 的 `mq/consumer/device/IotDeviceMessageSubscriber` 在租户上下文查设备并调用业务处理。补传使用受限的原接收时刻更新最后上报时间，避免把旧信号解释为在线或改变路由。Gateway 的 `service/device/remote/IotDeviceApiImpl` 通过 RPC 使用共享设备契约。Gateway 和 Biz 之间没有直接 Maven 依赖。

## SQLite 暂存与生产边界

`service/device/message/IotDeviceMessageServiceImpl` 注入 `service/device/spool/IotDeviceMessageSpoolService`，实际实现为 `IotDeviceMessageSpoolServiceImpl`。它仅对支持的协议中属性上报、事件上报、批量属性上报走确认直发／降级暂存，使用单连接 SQLite 文件、WAL、synchronous=FULL。积压时后续合格消息入队，定时按递增 ID 限速补发，确认后删除。默认开关关闭，文件为 `data/iot-gateway-spool.db`，默认 2 GB 逻辑消息配额、7 天保留、每秒 200 条。满额拒绝、过期删除，逻辑配额不能代替物理磁盘监控。进程重启可重新打开文件，丢卷或磁盘损坏超出机制保障范围。broker 接收与本地删除间存在重复窗口，多实例也没有全局有序保证。

另有 `service/device/message/spool/IotDeviceMessageSpoolService`，也是 `@Service`，其 `@PostConstruct` 使用同一开关和文件，且只接受 Redis。源码表明 RabbitMQ、Kafka 或 RocketMQ 开启暂存时会触发该服务的校验异常。Redis 模式的两个补传线程有竞争同一文件及重复发送的风险。实际失败栈、竞争频率、进程中止恢复及满额行为暂未运行验证。上线前须审查上游修复或由项目审批决定如何处理。

## 与项目可靠性设计的边界

- VWCP 的帧格式、鉴权、相关标识与设备交互由专用适配器负责。使用本地现有协议作参考，独立验证粘包、拆包、重连及重复序列。仓库中未找到冻结的 40 个 ADR 和 ACR-001 文本，尚无法逐条确认兼容性。
- 将本地写盘、broker publisher confirm、broker consumer ACK、设备 L2 ACK、业务事务提交当作不同状态记录。若项目约定 L2 ACK 在持久接受后返回，直接复用现有 TCP 成功路径不会履行该协议。最终 ACK 时点需要以冻结设计为准。
- SQLite 补传提供网关本地缓冲，业务 Outbox 应随业务事务落库，Inbox 应以稳定业务键去重并与消费事务绑定。broker 超时重试、重放、丢卷及普通异常不重入队，都需要按项目契约验证。当前消息 id/requestId 应经契约核对后才可用于幂等键。
- Gateway 的 Milo client/server、SQLite JDBC、Spring JDBC 为编译期直接依赖，已在验证的 exec JAR 中发现，即使相关运行开关关闭仍随包部署。Spring Rabbit 是 Gateway 的可选直接依赖，Maven optional 影响下游传递，与本应用是否打入 JAR 是两回事。Biz 的 PostgreSQL JDBC 用于 QuestDB PGWire。VWCP 专用部署需单独评审所需协议、驱动、镜像体积和维护面，避免引入未使用组件。
