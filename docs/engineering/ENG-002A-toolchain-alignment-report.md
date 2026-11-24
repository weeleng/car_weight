# ENG-002A JDK 21 工程工具链对齐验证

起始基线 `75070c4b207fcea964340ea57357748c625731b8`，已核对 `origin/master-jdk17`。该提交合并 ENG-001B-03（PR #14）。本任务为工作包 A 的工具链、构建和隔离运行验证授权。[技术基线](../architecture/standards/technology-baseline.md)、[实施差距与治理](../architecture/reviews/implementation-gap-register.md)和[生产门槛](../architecture/reviews/production-gate-approval-matrix.md)提供验收边界。

## 修改前盘点

| 位置 | 修改前工具链、编译参数、命令和产物 | 差距、整改、风险及验证 |
| --- | --- | --- |
| [根 POM](../../pom.xml)及[BOM](../../yudao-dependencies/pom.xml) | `java.version=17`，compiler 3.14.0 用 source/target 17、无 release，继承现有注解处理器及 `-parameters`。Maven/JDK 由调用者提供，Spring Boot 3.5.16 | JDK 21 编译时 source/target 不限制 Java 17 API。换成 release 17，风险为 Java 21 API 引用或处理器兼容，双 Reactor 构建、有效 POM、实参及探针检验 |
| [GitHub Actions](../../.github/workflows/maven.yml) | 唯一 workflow。用 POM 的 java.version 选 Temurin 17，Maven 取 runner PATH，默认 Reactor `mvn -B package --file pom.xml -Dmaven.test.skip=true` | 显式 JDK 21、Maven 3.9.12，并保留缓存、触发及打包行为。依赖下载、运行器和权限需远程实证 |
| [Server POM](../../yudao-server/pom.xml)及[唯一 Dockerfile](../../yudao-server/Dockerfile) | Server 重打包 `target/yudao-server.jar`；Dockerfile 单阶段 Temurin 17 JRE，复制该 JAR 为 `/yudao-server/app.jar` | JRE 改 21，保留路径、环境、端口和 CMD。验证镜像内 JVM、JAR、入口和隔离启动 |
| [唯一 Jenkinsfile](../../script/jenkins/Jenkinsfile) | 构建工具 `jdk17`、`maven3`，按 `-pl "$APP_MODULE" -am` 干净打包，跳过测试。归档 Server JAR，SSH 远端用 app.env 的 JAVA_HOME 或 PATH | 工具名切换，并校验实际工具和远端运行 Java 21。管理员需配置同名工具及目标运行时；部署前校验失败会阻止发布 |
| [IoT 聚合 POM](../../yudao-module-iot/pom.xml)、[Core](../../yudao-module-iot/yudao-module-iot-core/pom.xml)、[Biz](../../yudao-module-iot/yudao-module-iot-biz/pom.xml)及[Gateway](../../yudao-module-iot/yudao-module-iot-gateway/pom.xml) | 默认根 Reactor 聚合 BOM、framework、Server、System、Infra。IoT 默认不参与，Server 的 IoT Biz、Pay、Member、BPM、Report 为注释。Gateway 普通 JAR 之外用 Spring Boot repackage 生成 exec；无专用 Dockerfile | 用临时副本取消根 IoT 模块注释及独立 Reactor 验证，Server 装配不变。两种 JAR 各查 Manifest，隔离最小启动 |
| [Server Compose](../../script/docker-compose/docker-compose.yml)及其他 Compose | Server 构建上下文为 Server 模块，依赖 MySQL 8.4 与 Redis。LiveKit PoC、MP Gateway、SQL 工具 Compose 为其他用途 | 保持 Compose、依赖版本及拓扑。用独立容器网络验证，不执行生产编排 |
| Wrapper、插件与辅助脚本 | 无 Maven Wrapper、根构建无 Enforcer 或 Toolchains。另有独立的 SQL 补丁测试 POM 和 [旧部署脚本](../../script/shell/deploy.sh)，后者在部署主机用 PATH 的 Java | 锁定 CI Maven 下载和实际版本校验，保持原辅助部署脚本，使用主机 Java 21 是调用方前提 |
| 测试配置 | Surefire 3.5.3。System、Infra 等有既有测试 profile；IoT Biz 有测试 profile，Core/Gateway 三个指定测试类均存在 | 打包跳过测试编译和执行，另跑三类定向测试并读 XML。保留全部测试和断言 |

## 实际变更与证据位置

- [根 POM](../../pom.xml)以 `maven.compiler.release=${java.version}` 取代 source/target，仍为 17。执行 Maven 的 JDK 独立由工具链指定。保留 compiler 3.14.0、原注解处理器和参数。
- [Actions 工作流](../../.github/workflows/maven.yml)继续使用现有 checkout、setup-java、触发条件及 Maven 缓存。设 Temurin 21，从 Maven Central 安装 Maven 3.9.12，以实际发行包 SHA512 校验。保持默认 Reactor 打包和原测试跳过范围。
- [Server Dockerfile](../../yudao-server/Dockerfile)由 `eclipse-temurin:17-jre` 改为 `eclipse-temurin:21-jre`。启动仍展开原 JVM 与应用参数并执行同一 JAR；以 `exec` 让 JVM 接收容器的终止信号。保留目录、时区、端口和环境变量。
- [Jenkinsfile](../../script/jenkins/Jenkinsfile)使用候选管理工具名 `jdk21`、`maven-3.9.12`。构建前校验实际版本，远端部署前确认所选 Java 主版本为 21，保留原部署阶段、路径、凭据引用、启动参数及回滚材料。管理员需在实际 Jenkins 配置工具，并供部署主机使用 Java 21。
- [工具链检查脚本](../../script/shell/check-build-toolchain.sh)从 PATH 读取实际 `java`、`javac`、`mvn`，核对 Maven 所用 JVM。用法为 `bash script/shell/check-build-toolchain.sh`，校验通过退出 0，版本不符退出 1，命令缺失或失败直接返回错误。
- 当前报告与[历史构建报告](jdk21-build-report.md)分别登记。本次验证日志、构件、Surefire XML 及验证辅助脚本位于云工作区 `/workspace/car-weight-eng002a-validation`，未提交构件或日志。

构建使用干净的 Git 导出副本，源码提交 `bdac8d58fb781685da6deaafc00864128e0efe77`。最终工具配置提交 `457cb2ba7c48b87cea6c301ec79a21859970cd92` 仅进一步调整 Dockerfile 的进程启动语句。复验镜像使用此 Dockerfile 及同一已验哈希的 Server JAR。构建后的报告编辑独立于两个导出副本。工作区原有未跟踪文件未纳入 Git 导出或提交。

## 实际工具链、构建与有效 POM

宿主默认仅找到 Debian Java 21 运行时，`javac -version` 返回 127。Adoptium API 下载路径返回 HTTP 403，改用可访问的官方 Docker 镜像。执行 `docker pull eclipse-temurin:21-jdk`、`docker create` 和 `docker cp <容器>:/opt/java/openjdk "$d/jdk"`，JDK 提取退出 0。所用镜像 digest 为 `sha256:8ad118b146aa92da815b576441acd6b42b38236797ad0b6edc8c200b85bb0734`。构建时实际值如下。

| 检查 | 实际值及退出码 |
| --- | --- |
| `java -version` | Eclipse Temurin OpenJDK 21.0.13.1+2 LTS，0 |
| `javac -version` | 21.0.13.1，0 |
| `mvn -version` | Apache Maven 3.9.12（`848fbb4bf2d427b72bdb2471c22fced7ebd9a7a1`），Maven JVM 为 Eclipse Adoptium 21.0.13.1，0 |
| JAVA_HOME | `/workspace/car-weight-eng002a-validation/jdk`。 Maven 位于 `/workspace/car-weight-dev-jdk17/apache-maven-3.9.12/bin` |
| `help:effective-pom` | Server 有效 POM 显示 `java.version=17`、`maven.compiler.release=17`、compiler 3.14.0、Spring Boot 3.5.16、原有 `-parameters`，0 |

实际命令（在相应导出副本中执行）如下。

```bash
d=/workspace/car-weight-eng002a-validation
dev=/workspace/car-weight-dev-jdk17
export JAVA_HOME="$d/jdk"
export PATH="$JAVA_HOME/bin:$dev/apache-maven-3.9.12/bin:$PATH"
export MAVEN_OPTS="-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
args=(-B -ntp -C -s "$dev/maven-settings.xml" -Dmaven.repo.local="$dev/m2")
(cd "$d/default" && mvn "${args[@]}" clean package -Dmaven.test.skip=true)
(cd "$d/iot" && mvn "${args[@]}" \
  -pl :yudao-module-iot-core,:yudao-module-iot-biz,:yudao-module-iot-gateway \
  -am clean package -Dmaven.test.skip=true)
(cd "$d/default" && mvn "${args[@]}" -pl :yudao-server \
  org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom \
  "-Doutput=$d/server-effective-pom.xml")
(cd "$d/iot" && mvn "${args[@]}" \
  -pl :yudao-module-iot-core,:yudao-module-iot-gateway -am \
  -Dtest=IotRabbitMQMessageBusConfirmTest,IotDeviceMessageSpoolServiceImplTest,IotDeviceMessageSpoolStoreTest \
  -Dsurefire.failIfNoSpecifiedTests=false test)
```

用 `git archive HEAD | tar -x -C "$d/default"` 和同样方式生成 `"$d/iot"`。后者仅将根 POM 的 IoT 聚合模块从注释中移出。差异留在 `iot-pom.diff`。两个 Server POM 完全相同，正式默认 Reactor 和 Server 业务装配仍原样。

| 构建 | 结果 | 用时 | 测试范围 |
| --- | --- | --- | --- |
| 默认 Reactor 干净打包 | 22 项 SUCCESS，退出 0 | 47.618 秒 | 跳过测试编译和执行 |
| 隔离 IoT Reactor 干净打包 | 22 项 SUCCESS，退出 0，包括 Core、Biz、Gateway 及所需依赖 | 56.107 秒 | 跳过测试编译和执行 |
| 指定三类测试 | 退出 0，按 Surefire XML 核验 23 项通过 | 18.073 秒 | 仅指定测试，完整套件未执行 |

以下为两次实际 Reactor 汇总及生成的项目 class 数量。表中“—”表示该 Reactor 未选择该项，0 对应参与构建但无 class 的聚合或 BOM。独立 IoT Reactor 的三份 JAR 均位于各模块的 `target/`，另有 Gateway exec JAR。默认 Server 可执行包为 `default/yudao-server/target/yudao-server.jar`。

| 模块 | 默认结果 / class | IoT 结果 / class |
| --- | --- | --- |
| `yudao-dependencies` | SUCCESS / 0 | — |
| `yudao` | SUCCESS / 0 | SUCCESS / 0 |
| `yudao-framework` | SUCCESS / 0 | SUCCESS / 0 |
| `yudao-common` | SUCCESS / 77 | SUCCESS / 77 |
| `yudao-spring-boot-starter-web` | SUCCESS / 76 | SUCCESS / 76 |
| `yudao-spring-boot-starter-security` | SUCCESS / 18 | SUCCESS / 18 |
| `yudao-spring-boot-starter-mybatis` | SUCCESS / 44 | SUCCESS / 44 |
| `yudao-spring-boot-starter-redis` | SUCCESS / 5 | SUCCESS / 5 |
| `yudao-spring-boot-starter-elasticsearch` | SUCCESS / 1 | — |
| `yudao-spring-boot-starter-mq` | SUCCESS / 16 | SUCCESS / 16 |
| `yudao-spring-boot-starter-job` | SUCCESS / 10 | SUCCESS / 10 |
| `yudao-spring-boot-starter-test` | SUCCESS / 13 | SUCCESS / 13 |
| `yudao-spring-boot-starter-biz-tenant` | SUCCESS / 34 | SUCCESS / 34 |
| `yudao-spring-boot-starter-websocket` | SUCCESS / 64 | SUCCESS / 64 |
| `yudao-spring-boot-starter-monitor` | SUCCESS / 8 | SUCCESS / 8 |
| `yudao-spring-boot-starter-protection` | SUCCESS / 29 | — |
| `yudao-spring-boot-starter-biz-ip` | SUCCESS / 5 | SUCCESS / 5 |
| `yudao-spring-boot-starter-excel` | SUCCESS / 24 | SUCCESS / 24 |
| `yudao-spring-boot-starter-biz-data-permission` | SUCCESS / 15 | SUCCESS / 15 |
| `yudao-module-infra` | SUCCESS / 237 | SUCCESS / 237 |
| `yudao-module-system` | SUCCESS / 471 | SUCCESS / 471 |
| `yudao-server` | SUCCESS / 2 | — |
| `yudao-module-iot` | — | SUCCESS / 0 |
| `yudao-module-iot-core` | — | SUCCESS / 96 |
| `yudao-module-iot-biz` | — | SUCCESS / 364 |
| `yudao-module-iot-gateway` | — | SUCCESS / 206 |

## Java 17 字节码和 API 边界

执行 `python "$d/verify-artifacts.py" "$d"`，最终退出 0。检查两个 Reactor 所有产生 class 的模块的 `target/classes`，默认 19 模块共 1,149 个 class，IoT 19 模块共 1,783 个 class，全部为 major 61。复查 Server 及 Gateway 两种 JAR 内项目 class。扫描排除第三方 `BOOT-INF/lib`、独立的 Spring Boot Loader 及 Multi-Release 版本区。

两个 Reactor 的每个常规 Java 编译步骤均记录 `javac [release 17]`。另用临时源文件引用 Java 21 引入的 `java.util.SequencedCollection`。同一个 `javac` 不加 `--release` 退出 0，使用 `--release 17` 时在预期的符号解析点退出 1。探针未进入项目或测试源码。

## 实际构件

| 构件（相对构建副本） | 字节数 | SHA-256 |
| --- | ---: | --- |
| `default/yudao-server/target/yudao-server.jar` | 163096042 | `eaf41083f69e68b36d30ce9c096a5f782e000c0972426e8079879e4b2229b4a9` |
| `iot/.../yudao-module-iot-gateway/target/yudao-module-iot-gateway.jar` | 550625 | `500ac6787793f34a294fd3d18b8d91ee46de4b780a42ac29563fbe18d65f16bd` |
| `iot/.../yudao-module-iot-gateway/target/yudao-module-iot-gateway-exec.jar` | 158458213 | `8385aafa86925e85926f3b40ee4ba2bab7ccacbb38038e0e383d80f2f10727f6` |

普通 Gateway JAR 的 Manifest 不含 Main-Class、Start-Class，根目录下含 206 个项目 class。exec JAR 解析 Manifest 时展开续行后，Main-Class 为 `org.springframework.boot.loader.launch.JarLauncher`，Start-Class 为 `cn.iocoder.yudao.module.iot.gateway.IotGatewayServerApplication`。其 `BOOT-INF/classes` 内对应的 206 个项目 class 全为 61，入口 class 和 JarLauncher 均在包中。 `BOOT-INF/lib` 共 212 个 JAR，确认了 Spring Boot 3.5.16、Spring Rabbit 3.2.12、Yudao Milo client/server preview、SQLite JDBC 3.49.1.0、Spring JDBC 6.2.19 及 Vert.x Core 4.5.36。初次验证脚本按无 `yudao-` 前缀查找 Milo 文件，退出 1；按实际构件名更正脚本后复查退出 0，构件不变。Server 的 Start-Class 为 `cn.iocoder.yudao.server.YudaoServerApplication`。

## Gateway 定向测试

实际 Surefire XML 位于 IoT 导出副本的 Core 和 Gateway `target/surefire-reports/TEST-*.xml`。本次计数与历史对照相同。

| 执行类 | 总计 | 通过 | 失败 | 错误 | 跳过 | 类耗时 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| IotRabbitMQMessageBusConfirmTest | 2 | 2 | 0 | 0 | 0 | 1.200 秒 |
| IotDeviceMessageSpoolServiceImplTest | 17 | 17 | 0 | 0 | 0 | 1.834 秒 |
| IotDeviceMessageSpoolStoreTest | 4 | 4 | 0 | 0 | 0 | 0.443 秒 |

## Docker、Gateway 与 Server 最小运行

实际 Docker Engine 和客户端均为 29.9.2。拉取 `eclipse-temurin:21-jre` 成功，观察到 tag 的 digest 为 `sha256:d77e090ae1f6970c047b443c6f0a6c1358b0426d44432bb5a7753408cd43de0d`。仓库 Dockerfile 保留 tag 选择，报告记录此次拉取的 digest。测试网络用 `docker network create --internal eng002a-validation` 建立，无端口映射。Redis 为隔离 Redis 7.4，`redis-cli ping` 返回 PONG。Server 使用同网络内 MySQL 8.4.13。首次绑定初始化 SQL 失败，MySQL 进程退出 1，日志显示 rootless 挂载读权限不足。重建实验容器，以 `docker exec -i` 和测试数据库账户导入仓库两份 SQL，导入均退出 0，查询确认 59 张表。生成的临时密码只在工作区权限受控的测试 env 文件中，运行时注入。镜像构建上下文只含 Dockerfile 和 Server JAR。

Server 的镜像命令如下。

```bash
docker build --network none -t car-weight-eng002a:local "$d/docker-server"
docker image inspect --format '{{.Id}} {{.Config.WorkingDir}} {{json .Config.Cmd}} {{json .Config.ExposedPorts}}' car-weight-eng002a:local
docker run --rm --network none --entrypoint java car-weight-eng002a:local -version
docker run --rm --network none --entrypoint sh car-weight-eng002a:local \
  -c 'test -r /yudao-server/app.jar && sha256sum /yudao-server/app.jar'
docker run -d --name eng002a-server-final --network eng002a-validation \
  --env-file "$d/server.env" car-weight-eng002a:local
docker stop --timeout 20 eng002a-server-final
docker wait eng002a-server-final
```

最终镜像构建、检查及 Java 版本查询均退出 0。镜像 ID 为 `sha256:0bb7f46b503dd608d5892874599b1eba1fd20785db6b69ae4d3984ab8bac8fe8`，实际 JVM 为 Temurin 21.0.13.1+2，目录 `/yudao-server`，端口声明 48080，启动语句 `exec java ${JAVA_OPTS} -jar app.jar $ARGS`，镜像内 JAR 与验证构件 SHA-256 一致。Server 用 local profile、隔离主从数据源（指向同一实验库）和 Redis DB 14，运行参数明确关闭 local 配置的 mock 鉴权。首次保留旧 shell 启动形式时 Spring 启动成功（11.405 秒），停止命令超时后退出 137 且 OOMKilled=false。调整为 exec 后重建镜像，`Started YudaoServerApplication in 13.791 seconds`，就绪退出码 0。主动 Docker stop 后进程退出 143，日志确认 Tomcat 优雅关闭及两个数据源关闭。未发送业务请求。

Gateway 无现有 Dockerfile，本次用同一 Temurin 21 JRE 基础镜像直接运行 Gateway exec JAR，命令如下。

```bash
iot="$d/iot/yudao-module-iot/yudao-module-iot-gateway/target"
docker run -d --name eng002a-gateway --network eng002a-validation \
  --mount "type=bind,source=$iot/yudao-module-iot-gateway-exec.jar,target=/validation/gateway-exec.jar,readonly" \
  --entrypoint java eclipse-temurin:21-jre \
  -Duser.home=/tmp/eng002a-gateway-home -jar /validation/gateway-exec.jar \
  --spring.main.web-application-type=none --spring.data.redis.host=redis \
  --spring.data.redis.port=6379 --spring.data.redis.database=15 \
  --yudao.iot.message-bus.type=local --yudao.iot.gateway.spool.enabled=false
docker stop --timeout 20 eng002a-gateway
docker wait eng002a-gateway
```

Gateway 日志报告 Java 21.0.13.1，协议监听保持源码的默认禁用状态，`Started IotGatewayServerApplication in 5.211 seconds`，就绪退出码 0。主动 stop 后进程退出 143。验证覆盖最小 Spring 上下文、独立运行构件及隔离 Redis。真实协议、RabbitMQ 及 SQLite 双暂存运行链路待工作包 F 专项验收。

## CI、Jenkins 和交付范围

- Actions 配置解析使用 PyYAML BaseLoader，判断原触发条件、现有 actions、Temurin 21、缓存及权限字段。提取 Maven 安装步骤用 `bash -eo pipefail` 本地实际执行，下载、SHA512、解压和 PATH 均通过，随后运行同一工具链检查脚本退出 0。构建步骤做 Bash 语法检查，默认干净构建证据见上文。远程 Actions 执行及仓库/组织访问权限另行记录。
- Jenkins 远程 shell 片段提取后用 `bash -n` 检查，通过。用临时 app.env 运行其 Java 预检函数，Temurin 21 退出 0，JDK 17 按预期退出 1。共享构建检查给 JDK 17 同样退出 1。未执行真实 Jenkins Job、SSH 部署或远端工具配置。管理员需配置 `jdk21`、`maven-3.9.12`，验证 JVM 与目标服务器实际运行环境。
- 核对从起始 SHA 至交付的文件清单和 Git 空白，核对 POM 差异仅修改 compiler release 属性且版本、模块装配不变。常见凭证模式检查仅针对变更内容。C-01 及架构报告按任务保持原样，引用其中的旧 CI 配置时应按其历史基线理解。

## 验收、待验证项及回滚

**工作包 A 的本地核心验收通过。** JDK 21、Maven 3.9.12、默认及隔离 IoT Reactor、Java 17 产物与 API 边界、Gateway exec 和 23 项指定测试均有实测证据。最小运行及 Server 容器验证通过。GAP-001 的配置及本地验证已覆盖，远程 CI 和实际部署的全链路尚未验收。VAL-002 仓库分支保护和 Ruleset 由有权限人员核验，未改仓库设置。Spring Boot 3.5.16、MySQL 8.4、模块化单体及独立 Gateway 边界保持。完整测试套件、远端 Jenkins、生产部署、真实设备和消息链路未执行。BLOCK-001 保持开放，生产启用须经独立专项。

如需临时回滚工具配置，在另行授权的独立回滚分支按逆序执行 `git revert 457cb2ba7c48b87cea6c301ec79a21859970cd92 bdac8d58fb781685da6deaafc00864128e0efe77` 后复验，再提交人工审查。回滚将恢复 POM 的 source/target 17、Actions 的构建 JDK 选择及 runner Maven、旧镜像与 Jenkins 工具名称和部署检查。若只需恢复运行制品，可使用上一个已验镜像或 JAR，并核对 JVM 与启动参数。major 61 和 Java 17 API 边界提供项目类编译兼容证据，实际依赖及旧运行环境需单独验收。数据库和业务模块没有迁移变更。本任务的正式目标运行环境继续为 Java 21，旧环境仅可作为授权应急回滚。
