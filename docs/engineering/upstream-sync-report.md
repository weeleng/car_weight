# UPSTREAM-001 上游同步报告

被检查的上游源码提交为 `2ff45ae44a28f483e40ba63315bd0a03c30859c4`。2026-11-23（Asia/Shanghai）重新查询时，本地 `master-jdk17` 与开发远程 `origin/master-jdk17` 均为 `ff61dd24e741a2ca54b3ca9e718bcea9c60e0365`，官方 `upstream/master-jdk17` 与此前检查一致。

## 同步证据

```bash
git ls-remote origin refs/heads/master-jdk17
git ls-remote https://github.com/YunaiV/ruoyi-vue-pro.git refs/heads/master-jdk17
git remote add -t master-jdk17 upstream https://github.com/YunaiV/ruoyi-vue-pro.git
git fetch --no-tags origin master-jdk17
git fetch --no-tags upstream
git rev-parse HEAD origin/master-jdk17 upstream/master-jdk17
git merge-base --is-ancestor HEAD upstream/master-jdk17
git rev-list --left-right --count upstream/master-jdk17...HEAD
git switch -c sync/upstream-master-jdk17
git merge --ff-only upstream/master-jdk17
```

祖先检查退出 0，对比结果为官方 47、开发独有 0。独立分支快进至完整官方提交，提交历史保留，未产生合并冲突。Java 目标和 Spring Boot 版本未变化，可继续在隔离目录打包。分支上的后续提交只包含本任务的五份报告。原有 ENV-001 报告仍是工作区中的独立文件。冻结文档未被本任务修改。

## 47 个提交的相关影响

通过 `git log --format='%h %s' master-jdk17..HEAD -- pom.xml yudao-dependencies/pom.xml yudao-module-iot` 与同区间的 `git diff` 核对路径。IoT 三个子模块合计变更 196 个文件，增加 20,787 行、删除 491 行。

| 范围 | 源码核查 |
| --- | --- |
| 根 POM、Server POM | Java 17 和 Spring Boot 3.5.16 保持；根聚合中的 IoT、Server 中的 iot-biz 依赖仍为注释。 |
| 依赖 BOM | `8207ea71d7` 引入芋道 Milo BOM `1.2.0-RC3-preview-20261123`，影响 Maven 模型解析；Milo 客户端及服务端由 Gateway 直接引入。 |
| Core | `4b36b748b1`、`fbc250ce16` 的离线补传改动涉及消息模型、确认发送能力及多种消息总线；测试依赖改用项目 test starter。 |
| Biz | `3f376551d1` 增加 QuestDB 及 PostgreSQL JDBC 驱动，`423a048546` 修订消息示例和 OTA 范围校验。补传处理区分历史消息与实时在线、路由状态，并涉及批量上报。 |
| Gateway | `14a41a77ae`、`1e3367dfab`、`71a8ca5edf`、`d0d3c332ac`、`4c798d6b38` 分别涉及 OPC UA 接入及调整、Modbus 下行等。补传引入 SQLite / Spring JDBC，RabbitMQ 路径声明可选的 Spring Rabbit。 |

离线暂存存在两套同时注册的服务，属于生产启用阻断。同步分支只用于审查，停止进一步的 IoT 集成与生产启用。风险和运行证据分别见 [Gateway 分析](iot-gateway-analysis.md) 和 [验证状态](validation-status.md)。
