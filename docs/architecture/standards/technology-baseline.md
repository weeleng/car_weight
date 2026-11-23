# C-01 技术基线

## 适用范围

开发工具链、Java 编译目标、后端架构及 IoT Gateway 部署。

## 已确认约束

工程基线依据 `weeleng/car_weight` 的 `master-jdk17` 提交 `fe211d0333db30d3d260fbc4533682a51a750bbf` 和 ENG-001A 的技术要求建立。ENG-001B-01 从 `8e3165ae630d7eabe30cf28ebafff6fefe293a95` 开始，复核 C-01 的正式工程目标。

| 项目 | 基线 | 源码或验证依据 |
| --- | --- | --- |
| JDK 运行与构建工具链 | OpenJDK 21 | 既有 [JDK 21 构建报告](../../engineering/jdk21-build-report.md) 记录了上游源码上的验证。 |
| Maven | 3.9.12 | 同上。 |
| Java 编译级别 | 暂时保持 17 | [根 POM](../../../pom.xml) 的 `java.version` 和派生的编译参数。工作 JDK 版本与产物编译级别分别记录。 |
| Spring Boot | 3.5.16 | [根 POM](../../../pom.xml)及[依赖 BOM](../../../yudao-dependencies/pom.xml)。 |
| MySQL | 8.4 | 仓库的 [Compose 配置](../../../script/docker-compose/docker-compose.yml)声明 `mysql:8.4`。配置声明，实际兼容性需验证。 |
| 官方上游 | [YunaiV/ruoyi-vue-pro 的 master-jdk17](https://github.com/YunaiV/ruoyi-vue-pro/tree/master-jdk17) | 开发分支使用 `weeleng/car_weight` 的 `master-jdk17`。 |
| 开发仓库 | `weeleng/car_weight` | 开发基线分支为 `master-jdk17`。 |
| 应用架构 | 现有 RuoYi-Vue-Pro 模块化单体 | `yudao-server` 装配业务模块。跨模块接口与内部实现的约束见 [AGENTS.md](../../../AGENTS.md)。 |
| IoT Gateway | 独立部署 | [Gateway POM](../../../yudao-module-iot/yudao-module-iot-gateway/pom.xml) 的 `exec` 构件。根 POM 默认未启用 IoT Reactor。 |

OpenJDK 21 用于运行构建工具与目标环境中的应用。Java 编译目标暂时保持 17。现有 Maven CI 根据 POM 的 Java 版本选择 JDK 17，见 [Maven CI](../../../.github/workflows/maven.yml)。

## 禁止事项

未经批准不得修改 POM、CI、编译目标、技术版本或架构边界。RabbitMQ 与 SQLite 双暂存服务冲突仍为生产启用阻断。

## 验证要求

记录 OpenJDK 与 Maven 实际版本，分别验证默认 Reactor 与受影响的 IoT Reactor，检查产物的 Java 编译目标及 Gateway 独立构件。涉及数据库时在 MySQL 8.4 上验证。历史构建证据与本次任务的实际验证结果分开记录。

## 来源与待核验内容

来源为 ENG-001A、ENG-001B-01 提供的 C-01 及上表所列仓库证据。OpenJDK 21 是本任务所提供的最新正式技术决策，完整批准正文和当前部署环境的运行验证仍待取得。详见 [ACR-001 核对记录](../acr/ACR-001-reconciliation.md)。
