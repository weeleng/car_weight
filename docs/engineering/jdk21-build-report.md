# JDK 21 构建与 Gateway 打包报告

源码提交为 `2ff45ae44a28f483e40ba63315bd0a03c30859c4`。工具链为 Debian OpenJDK 21.0.13、Apache Maven 3.9.12。编译目标仍为 Java 17，Spring Boot 为 3.5.16。

## 实际命令

在仓库根目录以 `git archive HEAD | tar -x -C "$d/default"` 和 `git archive HEAD | tar -x -C "$d/iot"` 分别导出源码，`d=/workspace/car-weight-upstream-validation`。只在 IoT 导出副本的根 POM 取消注释 `<module>yudao-module-iot</module>`，保留 Server 对 IoT Biz 的注释。构建独立于开发检出目录，未修改受 Git 跟踪的 POM。

```bash
d=/workspace/car-weight-upstream-validation
dev=/workspace/car-weight-dev-jdk17
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
export MAVEN_OPTS="${MAVEN_OPTS:-} -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
mvn="$dev/apache-maven-3.9.12/bin/mvn"
args=(-B -ntp -C -s "$dev/maven-settings.xml" -Dmaven.repo.local="$dev/m2")
"$mvn" -version
(cd "$d/default" && "$mvn" "${args[@]}" clean package -Dmaven.test.skip=true)
(cd "$d/iot" && "$mvn" "${args[@]}" \
  -pl :yudao-module-iot-core,:yudao-module-iot-biz,:yudao-module-iot-gateway \
  -am clean package -Dmaven.test.skip=true)
(cd "$d/iot" && "$mvn" "${args[@]}" \
  -pl :yudao-module-iot-core,:yudao-module-iot-gateway -am \
  -Dtest=IotRabbitMQMessageBusConfirmTest,IotDeviceMessageSpoolServiceImplTest,IotDeviceMessageSpoolStoreTest \
  -Dsurefire.failIfNoSpecifiedTests=false test)
```

## 构建与测试结果

| 检查 | 退出码 | 关键日志与覆盖范围 |
| --- | --- | --- |
| 默认 Reactor 干净打包 | 0 | `BUILD SUCCESS`，22 个 Reactor 项目，28.432 秒。跳过测试编译和运行。 |
| IoT Reactor 干净打包 | 0 | `BUILD SUCCESS`，22 个选定项目，39.188 秒。IoT Core、Biz、Gateway 和所需依赖均为 `SUCCESS`。跳过测试编译和运行。 |
| 三个定向测试类 | 0 | `BUILD SUCCESS`，16.513 秒。RabbitMQ confirm 2 项、SQLite store 4 项、spool service 17 项，共 23 项通过，0 失败、0 错误、0 跳过。完整测试套件未运行。 |
| Gateway exec JAR 结构核验 | 0 | Spring Boot JarLauncher、正确的 Start-Class 和入口 class major version 61。 |
| Gateway 最小启动 | 就绪检查 0，主动终止 143 | `Started IotGatewayServerApplication in 5.238 seconds`。使用 Local 总线、关闭 spool，复用本地 Redis 的独立逻辑库，协议监听维持默认的禁用状态。 |

完整日志和各命令退出文件保存在本次云工作区 `/workspace/car-weight-upstream-validation/`。日志中的代表性证据如下。

```text
default.log             yudao-server ....................................... SUCCESS
iot.log                 yudao-module-iot-core .............................. SUCCESS
iot.log                 yudao-module-iot-biz ............................... SUCCESS
iot.log                 yudao-module-iot-gateway ........................... SUCCESS
iot.log                 Attaching repackaged archive ...-exec.jar with classifier exec
targeted-tests.log      Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
targeted-tests.log      Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
gateway-exec-jar-verified.log  Class major version: 61
gateway-exec-smoke-retry.log   Started IotGatewayServerApplication in 5.238 seconds
```

## Gateway 可执行包核验

Gateway POM 的 repackage 附加 `exec` classifier。普通 JAR 首次用 `java -jar` 检查时返回 1，日志为 `no main manifest attribute`，随后选用 exec 包。第一次直接匹配 manifest 字段的断言返回 1，原因是长 Start-Class 字段按 JAR 规范折行。用 Python `zipfile.ZipFile` 展开续行后通过，入口字节码由 `struct.unpack_from('>H', cls, 6)` 验证。包中实际包含 Spring Rabbit 3.2.12、Milo client/server preview、SQLite JDBC 3.49.1.0 和 Spring JDBC 6.2.19。

第一次实跑 exec JAR 的命令使用 Local 总线并关闭暂存，但 Redisson 仍尝试连接默认 Redis 6379，退出 1。日志同时显示 RocketMQ 无法写入默认用户主目录。重试改用可写目录及已有本地 Redis 服务。下列命令启动后以日志中的 `Started IotGatewayServerApplication` 判定 Spring 上下文就绪，再向进程发送 TERM。

```bash
cd /workspace/car-weight-upstream-validation
g=iot/yudao-module-iot/yudao-module-iot-gateway/target/yudao-module-iot-gateway-exec.jar
/usr/lib/jvm/java-21-openjdk-amd64/bin/java -Duser.home="$PWD/smoke-home" -jar "$g" \
  --spring.main.web-application-type=none --spring.data.redis.port=36317 \
  --spring.data.redis.database=15 --yudao.iot.message-bus.type=local \
  --yudao.iot.gateway.spool.enabled=false
```

该运行验证覆盖可执行入口和最小 Spring 上下文。RabbitMQ broker、协议收发、RPC 联调及 SQLite 双服务的整机行为仍未验证。
