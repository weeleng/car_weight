# UPSTREAM-001 验证状态

源码提交为 `2ff45ae44a28f483e40ba63315bd0a03c30859c4`。开发分支原基线为 `ff61dd24e741a2ca54b3ca9e718bcea9c60e0365`。

| 项目 | 状态 | 证据或范围 |
| --- | --- | --- |
| 上游关系、快进 | 已验证 | 官方领先 47 个提交，开发端独有 0，独立同步分支快进。 |
| 默认 JDK 21 构建 | 通过 | 22 个 Reactor 项目，退出 0，跳过测试。 |
| IoT Core、Biz、Gateway 与依赖 | 通过 | 临时 IoT Reactor 干净打包退出 0，跳过测试。 |
| 定向测试 | 通过 | 23 项通过，覆盖 RabbitMQ confirm 测试及两个 spool 测试类。完整测试套件未运行。 |
| Gateway 可执行 JAR | 已验证 | exec 包结构校验和最小 Spring 上下文启动通过；本地 Redis 可用，Local 总线，spool 和 TCP 关闭。 |
| TCP 生产监听、设备连接与 VWCP 协议 | 已查源码，待实测 | 尚需设备或测试夹具，校验分帧、重连、鉴权、回执和背压。 |
| RabbitMQ 实际 broker | 待实测 | 需要验证路由失败、confirm 超时、重投、死信、消费失败及业务事务间的关系。 |
| SQLite 启用与双服务竞争 | 源码风险，生产启用阻断 | 两个服务共用开关及路径，其中一个只接受 Redis。需进行不同总线的启用验证和崩溃恢复验证。 |
| Biz 与 Gateway 端到端 | 待实测 | 设备 RPC、消息契约、时序落库及租户隔离仍需联调。 |
| L2 ACK 与 Outbox/Inbox 兼容性 | 待设计核对 | 冻结 ADR 与 ACR-001 未出现在此检出。按已批准契约确认 ACK 时点和去重键，再运行丢卷、重复及事务回滚场景。 |
| 九模块方案 | 已记录 | 尚无批准后的 Maven 模块、实现或生产资源。 |

[同步报告](upstream-sync-report.md) · [构建报告](jdk21-build-report.md) · [Gateway 分析](iot-gateway-analysis.md) · [九模块映射](module-mapping.md)
