# 技术基线

工程基线依据 `weeleng/car_weight` 的 `master-jdk17` 提交 `fe211d0333db30d3d260fbc4533682a51a750bbf` 和 ENG-001A 的技术要求。

| 项目 | 基线 | 源码或验证依据 |
| --- | --- | --- |
| JDK | OpenJDK 21 | 既有 [JDK 21 构建报告](../../engineering/jdk21-build-report.md) 记录了上游源码上的验证。 |
| Maven | 3.9.12 | 同上。 |
| Java 编译级别 | 暂时保持 17 | [根 POM](../../../pom.xml) 的 `java.version` 和派生的编译参数。工作 JDK 版本与产物编译级别分别记录。 |
| Spring Boot | 3.5.16 | [根 POM](../../../pom.xml)及[依赖 BOM](../../../yudao-dependencies/pom.xml)。 |
| MySQL | 8.4 | 仓库的 [Compose 配置](../../../script/docker-compose/docker-compose.yml)声明 `mysql:8.4`。本任务未运行该数据库或验证迁移。 |
| 官方上游 | [YunaiV/ruoyi-vue-pro 的 master-jdk17](https://github.com/YunaiV/ruoyi-vue-pro/tree/master-jdk17) | 开发分支使用 `weeleng/car_weight` 的 `master-jdk17`。 |
| 应用架构 | 现有 RuoYi-Vue-Pro 模块化单体 | `yudao-server` 装配业务模块。跨模块接口与内部实现的约束见 [AGENTS.md](../../../AGENTS.md)。 |
| IoT Gateway | 独立部署 | [Gateway POM](../../../yudao-module-iot/yudao-module-iot-gateway/pom.xml) 的 `exec` 构件。根 POM 默认未启用 IoT Reactor。 |

当前 Maven CI 从 POM 读取 Java 版本，因此现有 CI 选择 JDK 17。JDK 21 是工作与验证基线，本任务保留现有 CI、POM 及源码。RabbitMQ 与 SQLite 双暂存服务冲突继续列为生产启用阻断。
