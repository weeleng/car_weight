# ENG-002F-02A Gateway 暂存隔离验证

独立 Maven 工程使用 OpenJDK 21、Maven 3.9.12、release 17、SQLite JDBC 3.49.1.0、RabbitMQ Java Client 5.25.0 和 JUnit 5.12.2。根 Reactor 不包含本工程。原构件由隔离 IoT Reactor 构建，构件来源和 SHA-256 进入证据。实验使用合成数据与独立 Docker 资源，禁止传入生产配置、凭证、真实设备或生产数据库。

## 执行

从仓库根目录执行。Docker 应能运行 RabbitMQ 4.2、Redis 7.4 和 Temurin 21 JRE 镜像。建议预拉取镜像并保留报告记录的镜像摘要。选用空证据目录，支持多次用 `--group A` 等选取测试组。环境工具链检查由运行器执行。

```bash
python3 script/gateway-spool/validation/run-lab.py \
  --gateway /absolute/path/to/yudao-module-iot-gateway-exec.jar \
  --evidence-dir /absolute/path/to/new-run \
  -- -s /absolute/path/to/maven-settings.xml
```

A 的原版 Gateway 容器仅连接内部网络，依赖指向本轮合成 Redis/RabbitMQ，RPC 指到容器本地无服务端口。C 的 RabbitMQ 只在宿主回环动态映射端口，重启后读取实际端口。Redis 仅通过本轮容器网络和受控 `docker exec` 操作。运行器创建随机凭据，按运行标签收集日志并清理容器/网络。证据含组内 Maven 命令、Surefire XML、退出码、构件与镜像版本、日志、SQL 摘要和 Broker 状态，保留在指定目录。日志只用于本地审查。

| 组 | 范围 |
| --- | --- |
| A | 使用原 exec JAR 和只读装配观察器。测试启动开关、文件和权限、未知版本、共享文件、SIGKILL 后恢复与旧补传重复。RabbitMQ 开启暂存的预期退出为 1 |
| B | 用原 S1/S2 创建 JDBC 历史形态，校验 OS 锁、SQLite 快照和真实进程中止后的 WAL/迁移恢复。候选迁移目标与源分离，以经审核的稳定来源标识及清单标记批次 |
| C | 实际 RabbitMQ Confirm、returned、NACK、确认中断、重启及同 ID 重投，实际 Redis Streams pending、重投和恢复。配合候选 SQLite 状态和合成消费事务检查副作用 |
| D | 候选关键事件先落盘、保留、容量、短事务、fencing 与失效状态，独立合成 T1 证据及登记。正文摘要可保留，关键载荷仅在核验证据登记后清理 |

进程预期故障码由 JUnit 断言，Maven 报告分别统计实际测试、失败、错误和跳过。压力结果基于小样本及一次隔离运行。文件锁属于协作机制，不能约束未获同一锁的旧版进程。迁移器和 T1 证明仅供隔离验证。
