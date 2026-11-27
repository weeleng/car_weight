# ENG-002E-02 独立可靠消息验证

本工程在默认 Reactor 之外，使用三个合成 Schema、RabbitMQ 临时 vhost 和合成消息。正式部署、真实设备 ACK、外部业务副作用与恢复承诺需另行验收。

## 执行

使用 JDK 21、Maven 3.9.12、Python 3 和本机 Docker Unix Socket。运行器固定 MySQL 8.4.13 和 RabbitMQ 4.2.9 镜像摘要，使用随机凭据、临时节点和三个动态回环端口。确认标签及本次节点身份后才操作容器。证据目录应为新目录。

```bash
python3 script/messaging/validation/run-lab.py --evidence-dir /tmp/eng002e-full
python3 script/messaging/validation/run-lab.py --evidence-dir /tmp/eng002e-relay --group B
```

可重复传入 `--group A/B/C/D`，每组选独立的 JUnit 类。可在 `--` 后追加本机 Maven settings 和缓存参数。各组运行 `mvn verify`，Java 编译目标为 release 17。不要将该工程直接接入现有数据库或 Broker。缺少隔离运行器环境时，数据库/Broker 测试会失败关闭。

| 组 | 测试与证据 |
| --- | --- |
| A | 真 MySQL InnoDB T1/T2、原始冲突、Inbox 和业务幂等、回滚与未知结果 |
| B | Relay 领取及租约 SQL、Spring AMQP 确认和真实 Broker 路由、断连、重启 |
| C | 原 Channel 手动 ACK、子 JVM 中止及实际重投、合成异步授权 |
| D | 本库失败保全、受控重放、TRACK 和按稳定身份对账 |

每组目录留存 `maven.log`、Surefire XML、带命令和计数的 `result.json`，以及场景生成的库行快照、Broker 结果、差异或子进程日志。根目录另有真实镜像、版本、容器状态日志、工具链和有效依赖证据。消费者与 Relay 的故障注入子进程分别预期退出 37、38。脚本结束移除仅带本次标签的容器及匿名卷。

## 验证限制

- Relay 评估基于真实 MySQL 数据库时间和 Spring AMQP Confirm/returned。拓扑查询会在发送前后检查两个预期绑定，检查与 Broker 发送之间仍有竞态窗口。
- 失败处置先确认本地失败记录成功提交，再发送拒绝。测试 DLX 不可用时从失败记录恢复。消费失败调度、指数退避参数和 RabbitMQ 死信传输的生产方案须另行审批。
- 同一 MySQL 容器的三个 Schema 使用独立账号与本地事务。对账使用合成恢复水位和实际 SQL 行。本工程没有实施物理备份恢复、跨故障域 HA 或外部系统效果核对。
- 异步模型以合成注册表和受限服务身份复核消息范围。其结果适用于当前模型，不构成 ENG-002D-02R 或正式用户撤权验收。
