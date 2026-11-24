# ENG-002B-02 环卫九模块骨架实施报告

- 主分支基线为 `8be402141bd9b1bcc8d6281ed15e756a4dd10b7b`。`git fetch origin master-jdk17 engineering/eng-002b` 后用 `git ls-remote` 核对远程引用。
- 任务实际从 `3127644391e6e1def49d63bff75cdfe322b67700` 继续实施。同一 SHA 为[已审核设计](ENG-002B-module-skeleton-design.md)，且为任务分支祖先。
- 人工授权依据为 ENG-002B-02 任务正文，范围为默认 Reactor 方案 A、7 个聚合 POM、15 个空 JAR、既定包名、六条自身 API 依赖与检查器。DEC-011 所有权及任何运行装配继续等待独立授权。
- Maven 干净导出和测试基于骨架提交 `8a63864591cec97aec1fd048df3972e48735667b`。含完整检查器的实施 SHA 为 `8ee606c12fc82bfed36cd70067ad2f5ce28380d7`，其源码差异仅含检查器与夹具。最终报告提交是任务分支交付点，其 SHA 随 Git 交付记录提供。

## 变更范围及构件

根 POM 仅增加 `<module>sanitation-modules</module>`。新增[聚合 README](../../sanitation-modules/README.md)、下表 22 个 POM、[检查器](../../script/shell/check-sanitation-boundaries.py)、[检查器夹具](../../script/tests/test_sanitation_boundaries.py)及本报告，共 27 个变更文件。六对 API/Biz 使用 `cn.iocoder.yudao.module.sanitation.<domain>.api` 或 `.biz`，其他包遵循设计。骨架未创建 Java 源码。下表路径相对仓库根，JAR 位于相应目录的 `target/`，构件名为 `<artifactId>-2026.10-SNAPSHOT.jar`。

| POM 目录 | 包装 | JAR 大小 / 项目 class |
| --- | --- | --- |
| `sanitation-modules` | pom | — |
| `sanitation-modules/sanitation-analysis-biz` | jar | 1702 字节 / 0 |
| `sanitation-modules/sanitation-base` | pom | — |
| `sanitation-modules/sanitation-billing` | pom | — |
| `sanitation-modules/sanitation-common` | jar | 1656 字节 / 0 |
| `sanitation-modules/sanitation-device` | pom | — |
| `sanitation-modules/sanitation-event` | pom | — |
| `sanitation-modules/sanitation-gis` | pom | — |
| `sanitation-modules/sanitation-integration-biz` | jar | 1720 字节 / 0 |
| `sanitation-modules/sanitation-operation` | pom | — |
| `sanitation-modules/sanitation-base/sanitation-base-api` | jar | 1676 字节 / 0 |
| `sanitation-modules/sanitation-base/sanitation-base-biz` | jar | 1724 字节 / 0 |
| `sanitation-modules/sanitation-billing/sanitation-billing-api` | jar | 1698 字节 / 0 |
| `sanitation-modules/sanitation-billing/sanitation-billing-biz` | jar | 1746 字节 / 0 |
| `sanitation-modules/sanitation-device/sanitation-device-api` | jar | 1690 字节 / 0 |
| `sanitation-modules/sanitation-device/sanitation-device-biz` | jar | 1739 字节 / 0 |
| `sanitation-modules/sanitation-event/sanitation-event-api` | jar | 1683 字节 / 0 |
| `sanitation-modules/sanitation-event/sanitation-event-biz` | jar | 1731 字节 / 0 |
| `sanitation-modules/sanitation-gis/sanitation-gis-api` | jar | 1667 字节 / 0 |
| `sanitation-modules/sanitation-gis/sanitation-gis-biz` | jar | 1715 字节 / 0 |
| `sanitation-modules/sanitation-operation/sanitation-operation-api` | jar | 1708 字节 / 0 |
| `sanitation-modules/sanitation-operation/sanitation-operation-biz` | jar | 1756 字节 / 0 |

POM 的父 GAV 沿用 `cn.iocoder.boot` 与根 `${revision}`，`relativePath=../pom.xml`。实际环卫 compile 边如下，API、common、analysis-biz、integration-biz 无项目依赖。有效 POM 和实际解析树确认各边均为普通 JAR、无 classifier、非 optional、版本 `2026.10-SNAPSHOT`。

```text
sanitation-base-biz      -> sanitation-base-api
sanitation-billing-biz   -> sanitation-billing-api
sanitation-device-biz    -> sanitation-device-api
sanitation-event-biz     -> sanitation-event-api
sanitation-gis-biz       -> sanitation-gis-api
sanitation-operation-biz -> sanitation-operation-api
```

## 构建、采证与检查命令

使用各自的干净 `git archive` 副本 `default/`、`iot/`，Maven 本地仓库分别为 `m2-default` 与 `m2-iot`。第三方缓存从 ENG-002A 验证环境复制，项目构件由本次 `clean install` 生成。隔离 IoT 副本唯一 POM 差异为在根临时取消 IoT 模块注释。主检出及 Server POM 保持原样。完整日志、归档、有效 POM、依赖树和测试 XML 保存在 `/workspace/car-weight-eng002b-validation`，验证用临时结果未提交。

实际运行 Temurin OpenJDK 21.0.13.1+2、`javac` 21.0.13.1、Apache Maven 3.9.12，Maven 自身 JVM 为 21.0.13.1。有效 POM 的 `maven.compiler.release` 为 17，现有 compiler 3.14.0 保持，编译日志含 `javac [release 17]`。

```bash
d=/workspace/car-weight-eng002b-validation
dev=/workspace/car-weight-dev-jdk17
export JAVA_HOME=/workspace/car-weight-eng002a-validation/jdk
export PATH="$JAVA_HOME/bin:$dev/apache-maven-3.9.12/bin:$PATH"
export MAVEN_OPTS=-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts
# kind 取 default 或 iot，先在对应干净副本执行。
cd "$d/$kind"
args=(-B -ntp -C -s "$dev/maven-settings.xml" "-Dmaven.repo.local=$d/m2-$kind")
bash script/shell/check-build-toolchain.sh

# 默认副本
mvn "${args[@]}" clean install -Dmaven.test.skip=true

# 隔离副本
mvn "${args[@]}" -pl :yudao-module-iot-core,:yudao-module-iot-biz,:yudao-module-iot-gateway \
  -am clean install -Dmaven.test.skip=true
mvn "${args[@]}" -pl :yudao-dependencies install -Dmaven.test.skip=true

# 每个环卫 JAR 和 Server 的 default POM，另在 iot 副本运行 Gateway POM
e="$d/evidence/$kind"
mvn "${args[@]}" -f "$pom" \
  org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree \
  "-Doutput=$e/$artifact-effective.xml" -DoutputType=json "-DoutputFile=$e/$artifact-tree.json"

# Gateway 定向测试在 iot 副本执行
mvn "${args[@]}" -pl :yudao-module-iot-core,:yudao-module-iot-gateway -am \
  -Dtest=IotRabbitMQMessageBusConfirmTest,IotDeviceMessageSpoolServiceImplTest,IotDeviceMessageSpoolStoreTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

# 在交付检出执行
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s script/tests -p test_sanitation_boundaries.py -v
python3 script/shell/check-sanitation-boundaries.py --root "$d/default" --mode default \
  --iot-root "$d/iot" --evidence-dir "$d/evidence" --default-repo "$d/m2-default" \
  --iot-repo "$d/m2-iot" --jdeps "$JAVA_HOME/bin/jdeps"
```

| 项目 | 实际结果 |
| --- | --- |
| 工具链检查 × 2 | 退出 0 |
| 默认 Reactor | 44 个项目全部成功，退出 0，用时 46.148 秒。安装 15 个新增 JAR、7 个新增聚合 POM，上游 22 项均保留 |
| 隔离 IoT Reactor | XML 全量可见 48 项，`-pl/-am` 实际构建 22 项，全部成功，退出 0，用时 53.497 秒。IoT Core、Biz、Gateway 在列 |
| Maven 证据 | 成功生成 17 份有效 POM、17 棵实际解析依赖树，退出 0。第一次 Gateway 单 POM 诊断退出 1，因为隔离选定 Reactor 未安装 BOM；在同一 IoT 副本补装同 revision BOM 退出 0，重新采证成功 |
| 边界检查器 | 退出 0。检查模块和父 POM、artifactId、直接及传递边、Profile/scope/optional、DFS 无环、有效与实际解析、运行 JAR、Java 源及字节码。退出 1 表示违规，2 表示证据缺失或不可解析 |
| 检查器夹具 | 23 项均通过，退出 0。正例涵盖最小图、未来白名单与批准范围的区别、Server 原有装配及合法 API 引用。负例涵盖非法依赖与引用、两点/多点环、Bridge 绕过、Server/Gateway 装配、字节码及退出 1/2；夹具全部在临时目录 |
| 字节码 | 默认和 IoT 各查 1,149 和 1,783 个项目 class，均为 major 61。用 JDK 21 `jdeps` 解析 Server 和 Gateway 的项目类引用，无未解析引用。15 个环卫 JAR 均无 class，环卫字节码引用检查登记不适用 |

两次干净 `install` 使用 `-Dmaven.test.skip=true`。定向测试及 Python 夹具另行执行。源码检查识别词法引用，字节码覆盖实际链接的类。反射、运行时绑定、通配 import 最终符号和没有 class 证据的生成代码仍需按功能审查。

## Server 与 Gateway 回归

- **Server。** Git 证实 Server POM、扫描入口和运行配置未变。有效 POM 有 4 项直接依赖，其中现有项目内依赖为 System、Infra 与 protection starter。实际依赖树有 273 个解析节点，未发现环卫构件。可执行 JAR 的 258 个内嵌运行库与 ENG-002A 构件按库文件名逐项对比一致，未见新增或删除。Server JAR SHA-256 为 `55157f704f0c68f50a6b0e7cb20319a15d1e4a8f28ffe432b767c4f82d08e6da`。
- **Gateway。** 有效 POM 14 个直接声明、实际依赖树 287 个解析节点，无环卫依赖或 Server 依赖。普通 JAR 与 exec JAR 各含 206 个项目 class，exec 保留 JarLauncher、Gateway Start-Class、BOOT-INF/classes 与 212 个运行库。exec JAR SHA-256 为 `a56e38f333e7f88ba2963fc6a5c71201b33291cf942009b0a5ae04b991d55440`。
- **实际运行。** 执行 `bash /workspace/car-weight-eng002b-validation/smoke.sh`，退出 0。Server 实际镜像由现有 Dockerfile 与当前 JAR 构建，Temurin 21，接入独立 Docker 内网的 MySQL 8.4.13 与 Redis 7.4。测试库使用现有初始化 SQL 导入，未改变仓库 SQL。Server 12.372 秒就绪。Gateway 使用同一 Java 21 JRE 直接启动 exec JAR，Local 总线、spool 关闭、隔离 Redis，4.927 秒就绪。无宿主端口映射，两者就绪检查均退出 0，主动 Docker stop 后均退出 143。测试容器、内网和临时凭证已清理。

| Gateway 定向类 | 通过 / 失败 / 错误 / 跳过 | 类耗时 |
| --- | --- | ---: |
| IotRabbitMQMessageBusConfirmTest | 2 / 0 / 0 / 0 | 1.283 秒 |
| IotDeviceMessageSpoolServiceImplTest | 17 / 0 / 0 / 0 | 1.799 秒 |
| IotDeviceMessageSpoolStoreTest | 4 / 0 / 0 / 0 | 0.498 秒 |

Maven 定向测试退出 0，共 23 项，17.702 秒。计数依据本次 Surefire XML，覆盖现有单元测试范围。

## 回滚及后续限制

若需回滚，在获授权的独立分支逆序撤回本报告、`8ee606c12fc82bfed36cd70067ad2f5ce28380d7`、`8a63864591cec97aec1fd048df3972e48735667b`，然后复核默认 Reactor 恢复 22 项及 Server/Gateway 装配。设计提交可保留作为历史。此次无迁移及业务数据变更。

[DEC-011](../architecture/reviews/decision-approval-packets.md#dec-011) 继续开放。环卫业务对象、API、事件、数据库表及 Server 启用均等待独立授权。[BLOCK-001](../architecture/reviews/production-gate-approval-matrix.md#block-001) 保持开放。未执行整仓测试套件、真实设备、RabbitMQ 与 SQLite 双暂存联调、生产部署或远程 CI。后续 CI 可以评审接入本地检查器与证据生成步骤。本阶段验收适用于 Maven 骨架、依赖与隔离回归。
