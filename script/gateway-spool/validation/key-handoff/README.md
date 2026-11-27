# ENG-002F-02B-2B 关键事件交接独立验证

本工程位于正式 Maven Reactor 之外。用 JDK 21、Maven 3.9.12、Java release 17、SQLite JDBC 3.49.1.0、AMQP Java Client 5.25.0 和 JUnit 5.12.2。C 组仅连接本轮容器分配到宿主回环的 RabbitMQ。原件、租户、设备、时钟和证明均为合成夹具。

从仓库根目录执行，证据目录必须为尚不存在的新目录。可重复传入 `--group A` 等选项。省略时执行全部测试。

```bash
python3 script/gateway-spool/validation/key-handoff/run-lab.py \
  --evidence-dir /absolute/path/to/evidence \
  -- -s /absolute/path/to/maven-settings.xml
```

若有在隔离 IoT Reactor 构建的正式 Gateway exec JAR，可核验正式 v0 校验器对 A 组合成文件的行为。

```bash
python3 script/gateway-spool/validation/key-handoff/check-gateway-compatibility.py \
  --gateway /absolute/path/to/yudao-module-iot-gateway-exec.jar \
  --evidence-dir /absolute/path/to/evidence
```

| 组 | 夹具 |
| --- | --- |
| A | v0 表保留、一致性备份、未 checkpoint WAL、候选版本 2002、双向证据外键、进程中止恢复和前向修复门禁 |
| B | 已批准分类的合成输入、先写盘、真实 SQLite 状态事务、稳定身份、代际与单调租期 |
| C | RabbitMQ ACK/returned/NACK/失败、重复与重启，以及独立 SQLite 的 T1、权威查证、证据事务与清理 |
| D | 任务准入关闭、跨进程接管、隔离容量与基准测量、Android/PDA 合成切换及 ACK 丢失 |

候选迁移仅应用于合成文件。实验要求旧程序已经停机，旁路锁只对参与锁协议的进程有效。目录中的 DDL、版本、事件字段、消息摘要、权威读取、ACK 概念和审计结构均是试验。使用通知触发权威查证并允许无通知时主动查证。来源可信来自测试内独立 T1 存储，不定义真实 RPC、Topic、安全协议、设备能力或生产容量。
