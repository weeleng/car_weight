# ENG-002C 隔离验收

用 JDK 21 和 Maven 3.9.12，准备本地 Docker 的 `mysql:8.4` 镜像，然后运行以下命令，证据目录需为空。

```bash
python3 script/database/validation/run_lab.py --evidence-dir /tmp/eng002c02-results
```

已有 Maven settings 或隔离缓存时，可在 `--` 后传 Maven 参数。脚本创建两套单独的 MySQL 容器，用随机密码及回环动态端口，结束时移除自建容器和匿名卷。输出 `checks.tsv`、迁移历史及 Maven/JUnit 日志。合成表来自本目录的 fixtures，从未进入正式迁移目录。

Spring 测试通过独立上下文验证 Druid、具名事务管理器、MyBatis factory/template、跨库阻断、UTC 映射及模拟 ACK。运行时只装配夹具，不启动正式 Server，不创建正式 Mapper。测试包括移到独立实例后的 TRACK 连接与事实隔离，迁移失败的恢复使用第二实例的合成快照。
