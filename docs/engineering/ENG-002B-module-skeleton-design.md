# ENG-002B-01 环卫模块骨架详细设计

仓库 `weeleng/car_weight`。从 `origin/master-jdk17` 的 `8be402141bd9b1bcc8d6281ed15e756a4dd10b7b` 派生 `engineering/eng-002b`。基线包含 ENG-002A（PR #15，合入的任务提交 `38ca0b03c890bde709a051c9e4f4c2e5b79a60e2`）。

本文件提交用于人工审批。第一阶段为只读核查和设计，第二阶段以批准的设计 SHA 和独立实施授权为前提。

## 1. 仓库核查

| 项目 | 基线实际情况及设计依据 |
| --- | --- |
| 根 POM 与 BOM | 根为 `cn.iocoder.boot:yudao:${revision}`，`revision=2026.10-SNAPSHOT`，导入同 revision 的 `yudao-dependencies` BOM。继承 compiler release 17、现有注解处理器及 flatten 插件。根直接聚合 BOM、framework、Server、System、Infra，framework 直接聚合 16 个子模块。加根 POM 后静态计数为 22，与 ENG-002A 的历史实测一致。 [根 POM](../../pom.xml) · [BOM](../../yudao-dependencies/pom.xml) · [框架 POM](../../yudao-framework/pom.xml) · [ENG-002A 验证](ENG-002A-toolchain-alignment-report.md) |
| Server 装配 | 声明的有效项目内直接依赖为 System、Infra 和 protection starter。IoT Biz、member、pay、BPM、report 的条目保持注释。Server 独立从可执行 JAR 运行，Spring 只扫描 classpath 上的候选组件。 [Server POM](../../yudao-server/pom.xml) |
| Spring 扫描 | `YudaoServerApplication` 扫描 `${yudao.info.base-package}.server` 和 `${yudao.info.base-package}.module`，默认 base-package 为 `cn.iocoder.yudao`。拟用的 `cn.iocoder.yudao.module.sanitation` 位于该范围内，故必须依靠 Server classpath 和空骨架双重控制，后续不能仅凭改包名管理装配。 [启动类](../../yudao-server/src/main/java/cn/iocoder/yudao/server/YudaoServerApplication.java) · [应用配置](../../yudao-server/src/main/resources/application.yaml) |
| System、Infra 与 framework | System、Infra 各自将业务接口和实现打在单一 JAR。System 依赖 Infra，且两者依赖技术 starters。framework 有 common、Web、Security、MyBatis、Redis、MQ、tenant、job、test 等，starter 可继续带入其他实现。例如 MQ starter 依赖 Redis starter，test starter 依赖 MyBatis/Redis。因此按实际传递树核对，骨架阶段不引用这些 starters。 [System](../../yudao-module-system/pom.xml) · [Infra](../../yudao-module-infra/pom.xml) · [framework](../../yudao-framework/pom.xml) · [MQ starter](../../yudao-framework/yudao-spring-boot-starter-mq/pom.xml) · [test starter](../../yudao-framework/yudao-spring-boot-starter-test/pom.xml) |
| IoT 独立边界 | IoT 聚合在根 POM 默认注释。聚合含 Core、Biz、Gateway；Biz 使用 Core 和 System，Gateway 使用 Core 和协议/运行依赖，Core 使用 common 与 MQ starter。Gateway POM 生成普通 JAR 和带 `exec` classifier 的可执行 JAR。Gateway 启动类位于 `cn.iocoder.yudao.module.iot.gateway`，使用自身默认组件扫描。 [IoT POM](../../yudao-module-iot/pom.xml) · [Core](../../yudao-module-iot/yudao-module-iot-core/pom.xml) · [Biz](../../yudao-module-iot/yudao-module-iot-biz/pom.xml) · [Gateway](../../yudao-module-iot/yudao-module-iot-gateway/pom.xml) · [接入分析](iot-gateway-analysis.md) |
| 建置及测试 | 现有 Actions 使用 Temurin 21、Maven 3.9.12 和默认 Reactor 打包，保留既有 push/manual 触发。包路径采用 `cn.iocoder.yudao.module.<业务域>`，Java 测试在各自模块的 `src/test/java`，有 JUnit/Surefire 和部分模块的测试 profile。三个 Gateway 定向测试仍在 Core/Gateway。骨架检查工具的测试使用 Python 标准库，独立于这些业务测试。 [现有 workflow](../../.github/workflows/maven.yml) · [工具链检查](../../script/shell/check-build-toolchain.sh) · [System 测试配置](../../yudao-module-system/src/test/resources/application-unit-test.yaml) · [验证矩阵](validation-matrix.md) |

已核查当前树没有 `sanitation-modules` 或九模块正式构件。继续遵循 OpenJDK 21 构建和运行、Maven 3.9.12、Java 17 编译及 API、Spring Boot 3.5.16、MySQL 8.4、现有 dependencyManagement 与模块化单体。独立 Gateway 边界保持。设计依据为 [C-01](../architecture/standards/technology-baseline.md)、[C-06](../architecture/standards/module-boundaries.md)、[C-05](../architecture/standards/transaction-boundaries.md)、[上下游依赖映射](module-mapping.md)、[跨 ADR 依赖](../architecture/reviews/cross-adr-dependency-matrix.md)和[工作包启动条件](../architecture/reviews/implementation-entry-gates.md)。

## 2. 建议 Maven 树、坐标与包

下列目录名与各自的 artifactId 同名。除仓库根已有 `yudao` 外，新增 **22 个 POM**（7 个聚合 POM，15 个 JAR 模块）。

```text
pom.xml                                  # yudao，现有根
sanitation-modules/
  pom.xml                                # sanitation-modules，pom
  sanitation-common/
    pom.xml                              # sanitation-common，jar
  sanitation-base/
    pom.xml                              # sanitation-base，pom
    sanitation-base-api/pom.xml           # jar
    sanitation-base-biz/pom.xml           # jar
  sanitation-gis/
    pom.xml                              # sanitation-gis，pom
    sanitation-gis-api/pom.xml            # jar
    sanitation-gis-biz/pom.xml            # jar
  sanitation-device/
    pom.xml                              # sanitation-device，pom
    sanitation-device-api/pom.xml         # jar
    sanitation-device-biz/pom.xml         # jar
  sanitation-operation/
    pom.xml                              # sanitation-operation，pom
    sanitation-operation-api/pom.xml      # jar
    sanitation-operation-biz/pom.xml      # jar
  sanitation-billing/
    pom.xml                              # sanitation-billing，pom
    sanitation-billing-api/pom.xml        # jar
    sanitation-billing-biz/pom.xml        # jar
  sanitation-analysis-biz/
    pom.xml                              # sanitation-analysis-biz，jar
  sanitation-event/
    pom.xml                              # sanitation-event，pom
    sanitation-event-api/pom.xml          # jar
    sanitation-event-biz/pom.xml          # jar
  sanitation-integration-biz/
    pom.xml                              # sanitation-integration-biz，jar
```

`sanitation-analysis` 和 `sanitation-integration` 为逻辑域，其 Maven 路径直接使用唯一的 `-biz` JAR，避免引入多余聚合或 API。 `sanitation-modules` 的父为根 `yudao`，common、六个域聚合和两个单 Biz JAR 的父为 `sanitation-modules`，六对 API/Biz 的父为各自域聚合。每层父坐标用 `groupId=cn.iocoder.boot`、`version=${revision}`、`relativePath=../pom.xml` 并验证其解析至实际直接父路径。全部新增项目继承 groupId、version、Java release、BOM 和 flatten 配置。自身 API 依赖版本写 `${revision}`，不增加新的 BOM 或子树 revision。聚合 `modules` 表达 Reactor 包含关系，域模块的运行边在各 JAR 的 `dependencies` 明确声明。

候选包根为 `cn.iocoder.yudao.module.sanitation`。common 使用 `.common`，六对 API/Biz 分别使用 `.<domain>.api` 与 `.<domain>.biz`，分析和集成为 `.analysis.biz` 和 `.integration.biz`。API 只发布接口、请求/响应 DTO、公开值类型及所属领域的版本化事件。Biz 实现统一置于 `.biz` 子树下，未来按需使用 `.biz.api`（适配公开 API）、`.biz.controller`、`.biz.service`、`.biz.dal`、`.biz.convert`、`.biz.framework`、`.biz.job`、`.biz.mq`。测试将来使用各自 JAR 的常规 Maven 测试目录。分隔包前缀便于识别跨域内部访问，也覆盖了 `.biz.api` 中的实现类。

第二阶段建议只创建 POM 和一份聚合 README，由 Maven 生成空 JAR。Maven 可能发出空 JAR 提示，应验证实际归档内容及数量。Git 无需保存空源目录。保留包命名方案即可，暂不创建 `package-info.java`、空服务或占位类。空 JAR 没有项目 class 时，环卫类 major 校验登记不适用。

## 3. Reactor 接入与 Server 运行装配

| 选项 | 默认 Reactor 及 CI | Server 与后续启用 | 构建及回滚成本 |
| --- | --- | --- | --- |
| A，根默认 `modules` 增加 `sanitation-modules` | 静态预计 22→44 项，15 个新增 JAR 参与默认生命周期。现有默认构建工作流可覆盖新增骨架。依赖白名单校验需单独执行或经授权接入 | Server POM、组件扫描及启动配置保持原样。通过依赖树和 JAR 检查确认运行包内没有任何环卫构件 | 额外执行 POM 解析、生命周期与空 JAR 打包，实际用时第二阶段测量。回滚为撤回任务提交或移除聚合入口后按范围清理 |
| B，根增加显式 `sanitation` Profile | 默认预计 22 项。 `-Psanitation` 预计 44 项，现有默认 workflow 不覆盖骨架。需要单独执行，导入 Profile 可能影响 IDE 构建视图 | 与 A 一样保持 Server 与 Gateway 独立，启用 Profile 只影响构建集合 | 默认构建用时不受新增模块直接影响，显式构建有额外成本。回滚需清理 Profile 和各调用处的启用参数 |

推荐 **A**，让骨架在现有默认生命周期持续可构建，实际耗时使用同一环境相同参数的基线/任务对照。B 作为待批备选，若选 B 则检查器、构建与 CI 覆盖范围都以批准的 Profile 为准。任务分支没有现有 push 自动触发保障，远程 CI 结果需有实际运行记录。

两案都保持 Server 的 direct/effective/resolved 依赖及运行包现状，继续保持 IoT、会员、支付、BPM、报表原有装配状态。以后如需装配环卫模块，需另行批准 Server 依赖选择和功能启用，并核对组件扫描、配置及启动副作用。 `-pl :yudao-server -am` 在本方案下不应选择新增的环卫 JAR。

## 4. 依赖白名单

表中简写 `common` 指 `sanitation-common`，`x-api` 指 `sanitation-x-api`。左列 `-api` 集合为 base、gis、device、operation、billing、event。白名单只表达 Maven 直接边上限，实际新增边需有已批准的契约和必要性。批准骨架时建议每个拆分 Biz 只添加自身 API 的普通 compile 范围依赖。其余候选边留待后续按需审查。

| 消费方 | 候选允许的环卫目标 | 第二阶段最小直接依赖 | 后续可按需评审的直接依赖 |
| --- | --- | --- | --- |
| common | 无 | 无 | 无 |
| base-api、gis-api、device-api、operation-api、billing-api、event-api（各自） | common | 无 | common，仅实际用到公开共享值时 |
| base-biz | base-api、common、event-api | base-api | common、event-api |
| gis-biz | gis-api、base-api、common、event-api | gis-api | base-api、common、event-api |
| device-biz | device-api、base-api、common、event-api | device-api | base-api、common、event-api |
| operation-biz | operation-api、base-api、gis-api、device-api、common、event-api | operation-api | base-api、gis-api、device-api、common、event-api |
| billing-biz | billing-api、operation-api、base-api、common、event-api | billing-api | operation-api、base-api、common、event-api |
| event-biz | event-api、common | event-api | common |
| analysis-biz | base-api、gis-api、device-api、operation-api、billing-api、event-api、common | 无 | 仅分析用例需要的公开 API/事件及 common |
| integration-biz | base-api、gis-api、device-api、operation-api、billing-api、event-api、common | 无 | 仅外联用例所需公开 API/事件及 common |
| 聚合 POM、Server、Gateway、其他上游模块 | 本阶段无环卫项目依赖 | 无 | 另审，Server 按运行装配专门审批，Gateway 限设备公开契约 |

补充约束如下。

- 显式禁止任何 API → Biz、任何 Biz → 其他 Biz、common → 任一环卫域、Gateway → 任一环卫 Biz 或 Server。禁止通过 optional、test、provided、Profile、传递依赖等路径规避边界。测试夹具独立，正式环卫 POM 在骨架阶段连测试依赖也无需新增。
- 候选范围内各 Biz 可以通过 API 或事件获取必要公开契约。跨域 API → API 的例外默认拒绝，需明确公共契约的所有者、版本及必要性，复查解析依赖链和循环；不得把业务 DTO 归入 common 或 event-api 以避开限制。event-api 只预留通用技术事件封装，事件定义仍由各业务域持有，event-biz 禁止回调或依赖领域 Biz。
- 业务功能阶段才审查实际外部依赖、上游适配、Bridge 传递项、analysis/integration 提取 API 和 Gateway 设备协议契约。审查影响作用域、包边界、版本和依赖图。骨架无需 GIS SDK、MQ、Redis、MyBatis、数据库连接池或新构建插件。改变 C-06 正式边界需走适用的架构审批流程。

### 上游访问及 Gateway

上游可复用的公开接口例如 System 的 [AdminUserApi](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/api/user/AdminUserApi.java)、[DeptApi](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/api/dept/DeptApi.java) 与 Infra 的 [FileApi](../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/api/file/FileApi.java)、[ConfigApi](../../yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/api/config/ConfigApi.java)。同一 `api` 目录可能同时包含接口和 `ApiImpl`，例如 [AdminUserApiImpl](../../yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/api/user/AdminUserApiImpl.java)。Java 可见以及所在 JAR 可解析只表明能够链接。项目策略仍然要求受审核的接口和 DTO 白名单，禁止使用上游实现、DAL、DO、内部 Service 或直接查对方表。必要时由获批准的适配层隔离上游 ABI，复用 framework 技术能力及已有版本管理。骨架阶段不调用 System/Infra。

Gateway 沿现有 IoT Reactor 独立打包、无新增环卫依赖。未来设备协议需要独立批准的公开契约，禁止 Gateway 依赖任一环卫 Biz 或 `yudao-server`。不改变消息确认、RabbitMQ、SQLite spool、L2 ACK 与 INGEST 事务语义，BLOCK-001 继续开放。 [生产门槛](../architecture/reviews/production-gate-approval-matrix.md#block-001)

## 5. DEC-011 所有权与实施阻断

| 候选职责或文档证据 | 待专项批准及受限内容 |
| --- | --- |
| 作业称重、审核与去重，ADR-015；资产、设备归属及产废主体，ADR-017/018 | 正式称重、重量修正、设备和作业标识、历史归属、未匹配事件的处理者及幂等粒度待定。禁止提前建立实体和跨域 API |
| 线路版本、派单、清运执行，ADR-019/020/021 | 作业任务的最终状态机、迟到、临时任务、取消、未匹配及人工修正待定。禁止提前引入流程接口、状态枚举和调度/服务实现 |
| 异常及告警、运输批次与交付，ADR-022/023 | 批次、交付、设施主数据及修正权限的所有者待定。异常触发由领域订阅还是上层编排待审，event-biz 禁止反向调用领域 Biz |
| 合同、账单、发票，ADR-024；可靠消息，ADR-012/036 | 财务账单与计费前置条件、领域事件发布及消费责任、具体 Outbox/Inbox 合同待定。骨架禁止加入事件 DTO、队列、消费者和数据库表 |

依据 [DEC-011 审批卡](../architecture/reviews/decision-approval-packets.md#dec-011)、[ADR-015](../architecture/adr/ADR-015-weighing-model.md)、[ADR-021](../architecture/adr/ADR-021-collection-operation-execution.md)、[ADR-022](../architecture/adr/ADR-022-event-and-alarm-management.md)、[ADR-023](../architecture/adr/ADR-023-disposal-facility-and-delivery.md)、[ADR-024](../architecture/adr/ADR-024-contract-and-billing.md)及[可靠消息草案](../architecture/adr/ADR-036-cache-and-asynchronous-processing.md)。现提议的空模块和自身 API 依赖不要求先确定具体业务对象的所有权。若评审要求骨架同时发布任一业务契约或先固定事件责任，该部分单独阻断，等待 DEC-011 等对应专项决定。DEC-011 继续开放。

## 6. 自动检查设计

拟使用 Python 标准库及 JDK 工具，实现单入口检查器 `script/shell/check-sanitation-boundaries.py`，由 `script/tests/test_sanitation_boundaries.py` 使用标准库 `unittest` 测试。使用固定的审批后模块清单和有向边策略。检查器输入包括仓库根、获批 Reactor 模式和证据目录。计划命令为 `python3 script/shell/check-sanitation-boundaries.py --root . --mode default|sanitation --evidence-dir "$evidence"`，其中模式二选一。退出 0 表示所有要求的检查完成且合规，1 表示违规，2 表示参数、文件、构建证据或解析错误，证据缺失时返回 2。证据根目录按 `default/` 与 `iot/` 分放，两套证据使用相同起始代码和对应临时 Reactor 设置。输出检查类别、消费方、目标坐标或 class、来源路径、依赖链及范围。多个违规可一并报告。

| 检查层次 | 输入、规则与证据 |
| --- | --- |
| POM 静态结构 | 用 `xml.etree.ElementTree` 解析命名空间、直接 modules、parent、packaging、properties、dependencies 和 Profile。按规范化相对路径遍历获批 Reactor，校验 22 个新增 POM 唯一 artifactId、父 GAV/路径、双层聚合归属、revision 和拆分结构。验证默认或 Profile 的启用范围、Server 现有装配与 Gateway POM。检查未激活 Profile 中是否声明受限边，报告对应 Profile。依赖管理和 BOM 只做版本来源核查，不画作业务依赖边。无法安全解析的插值或 Profile 证据不完整时返回 2。 |
| 直接依赖图 | 有效坐标以 groupId/artifactId 定位，仍逐节点核对版本、type/classifier、scope 和 optional。按运行期或编译期的真实消费方建立有向图，聚合和继承边单列，检查白名单及六条骨架最小边。对直接模块图用 DFS 三色标记记录成环路径，可定位两点及多跳环，同时继续报告相关白名单违规。不得只比较字符串或依赖 Maven 成功才判无环。 |
| 有效 POM | 用 Maven help 插件查验各环卫 JAR、Server 与隔离副本中的 Gateway。逐模块记录 JDK/Maven、Profile、提交和实际输出，核对继承/插值后的 release、group/version、包装、插件和直接依赖。有效 POM 与静态定义不同的地方要解释，包括继承、Profiles 和 dependency management。 |
| 实际解析依赖树 | 先在验证用 Maven 本地仓库安装本次 clean 产物，避免旧 SNAPSHOT 被读取，再用 dependency 插件导出完整 JSON。解析实际选择的节点及父子路径，包含 runtime/compile 及用于识别绕过的 test、provided、optional 证据。核对每个环卫模块解析出的环卫坐标均落在已批准的可达集合中，禁止任何 Biz 经公开 API、中间构件或上游库进入其他环卫消费者。保留完整边路径，覆盖从外部/上游构件引入的间接依赖；静态允许边也要通过此关。检查 Server 默认和 Gateway 隔离树均无环卫构件，Gateway 无 Server。 |
| Server / Gateway 可执行归档 | 解开 Manifest 续行，检查 Main-Class、Start-Class、BOOT-INF/lib 和 BOOT-INF/classes，核对实际运行包不含环卫 JAR 或包前缀。与同基线、同工具链生成的 Server 依赖清单或有效装配比对，既有 System、Infra、protection starter 允许保留；不因此放行任何新增业务 Biz。 |
| Java 源码 | 根据 Maven 模块及包声明归属，将注释与字面量同代码区分，检查 import、静态和通配 import、可识别全限定名以及同包共享的风险。禁止跨域引用 `.biz`、Mapper、DO、ServiceImpl、API 实现。API 模块只可发布允许的公开包且不得引用 controller、DAL、数据库驱动和持久化框架。Gateway 不得使用领域 Biz 包。上游 API 白名单按类型逐项批准，不能用 `.api.*` 直接放行 `ApiImpl`。 |
| 字节码和源码限制 | 使用 JDK 21 的 `jdeps -verbose:class -filter:none --multi-release 17` 在已解析编译依赖类路径上检查项目输出，按 Maven 构件和包归属核对字节码引用。也以 Python 读取项目 class 文件头的魔数与 major，只有项目自身类需为 61，第三方、多版本条目和 Boot Loader 分列。无项目 class 时明确登记不适用。静态扫描无法完整处理类型解析、生成代码、反射、运行时装配、通配最终符号、字符串中的类加载或 reload 场景。对于缺失解析的字节码需给出缺口，不以成功假定。抽查后续正式功能、生成源和运行配置。 |

拖入依赖树的 optional 及未选版本可能被 Maven 省略，故静态、有效 POM、完整选中树三份证据必须结合使用。测试和签核材料不得修改生产 POM 以造违规。骨架为空时，当前通过仅证明获批构建与依赖关系，后续真实代码继续执行上述检查。

### 正向与负向测试

在 `tempfile.TemporaryDirectory` 中生成独立 POM、有效 XML、解析树 JSON 及最小 Java 夹具。每个样例独立调用检查入口，核对返回码、违规类别、来源及路径。用合成报告时明确它验证解析和规则，阶段二仍用真实 Maven 输出复验。Fixture 不进入正式运行模块。

| 类型 | 夹具 | 预期结果 |
| --- | --- | --- |
| 正向 | 六个 Biz 各依赖自身 API，其他骨架无边；额外用独立未来规则夹具验证允许的公开 API 和 common | 0，前者符合骨架最小图；后者只验证白名单语义 |
| 正向 | Server 延续 System、Infra 和 protection starter，Gateway 仅延续原技术/IoT 依赖 | 0，既有 Server 装配边由消费者用途规则单独允许 |
| 负向 | API → 自身 Biz，或以 optional/provided 声明其他 Biz | 1，API 不得依赖任何 Biz |
| 负向 | base-biz → operation-biz | 1，跨域内部实现 |
| 负向 | common → device-api | 1，common 反向领域依赖 |
| 负向 | 同一 fixture 的 A → B → A | 1，指出闭合路径及任何白名单违规 |
| 负向 | A → B → C → A | 1，给出完整间接循环 |
| 负向 | Gateway → device-biz 或 Server | 1，违反独立装配边界 |
| 负向 | operation-biz → 公开或上游 Bridge → billing-biz，直接边无明显违规 | 1，实际解析树显示完整穿透路径 |
| 负向 | 跨域 import/静态 import 或全限定引用其他域的 Mapper、DO、ServiceImpl，或 API 引用数据持久化包 | 1，定位源路径、API/Biz 角色和目标类型 |
| 负向 | Server 仍保留既有依赖，但传递树或 BOOT-INF 中增加环卫 Biz | 1，既有装配例外不得扩展 |
| 错误输入 | 删除一份必需报告，或让坐标、树、模块文件不可解析 | 2，报告缺证据或输入错误 |

## 7. 第二阶段拟变更与命令

批准前，以下均为拟实施项。建议最小文件范围如下。

| 位置 | 预计变更 |
| --- | --- |
| 仓库根 `pom.xml` | A 只在既有 modules 新增一行。B 仅增加获批的 Profile 和该聚合入口。保留 revision、BOM、release 及依赖版本。 |
| `sanitation-modules/` | 新增树中全部 22 个 POM 及 `sanitation-modules/README.md`。README 记录构件、包前缀和边界。 |
| 验证 | 新增 `script/shell/check-sanitation-boundaries.py` 和 `script/tests/test_sanitation_boundaries.py`。夹具由测试动态生成，不进入正式构件。 |
| 交付 | 新增 `docs/engineering/ENG-002B-module-skeleton-report.md`，注明真正的批准记录及设计/实现 SHA。 |
| 保留项 | Server POM、IoT POM/实现、现有业务文件、ENG-002A 工具链配置、ADR、C-01 至 C-06、ACR、DEC/BLOCK 均保持原样。CI 验证步骤单列建议，接入动作另行批准。 |

在获准实施的干净 Git 导出副本执行。以下命令以方案 A 为例；若批方案 B，默认构建仍单独验证 22 项，涉及环卫的命令加 `-Psanitation`。使用现有 JDK 21、Maven 3.9.12，`audit_repo` 为本次隔离仓库，外部依赖可来自现有缓存。可以预热第三方构件，需确保本次项目坐标重新安装，不将旧 SNAPSHOT 当作结果。

```bash
bash script/shell/check-build-toolchain.sh
args=(-B -ntp -C "-Dmaven.repo.local=$audit_repo")
mvn "${args[@]}" clean install -Dmaven.test.skip=true
python3 -m unittest discover -s script/tests -p test_sanitation_boundaries.py
# evidence 为绝对路径。对每个环卫 JAR 及 Server 设 set_name=default，
# 在隔离 IoT 副本对 Gateway 重跑两个诊断命令并设 set_name=iot。
mkdir -p "$evidence/$set_name"
mvn "${args[@]}" -f "$module/pom.xml" \
  org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom \
  "-Doutput=$evidence/$set_name/$artifact-effective.xml"
mvn "${args[@]}" -f "$module/pom.xml" \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree \
  -DoutputType=json "-DoutputFile=$evidence/$set_name/$artifact-tree.json"
python3 script/shell/check-sanitation-boundaries.py \
  --root . --mode default --evidence-dir "$evidence"
jar tf yudao-server/target/yudao-server.jar
```

上述诊断插件只用 Maven 命令调用，不写入项目 POM，不更改现有构建插件版本。若验证环境无法解析诊断插件或其依赖，记录实际结果，依赖树验收保持待验。 `install` 包含 clean compile/package 生命周期，便于逐 POM 使用本次构件获取依赖树。应记录默认 Reactor 实际数量、15 个 JAR、7 个聚合关系、空 JAR 的项目 class 数及原有模块的 major 61。Server 最小启动使用隔离 MySQL 8.4 和 Redis，环境不足时记录未执行。

Gateway 使用另外一个干净副本，按 ENG-002A 仅在该副本取消根 POM 的 IoT 聚合注释，保留 Server 对 IoT Biz 的注释。与默认验证使用独立证据目录，临时修改不提交。

```bash
mvn "${args[@]}" \
  -pl :yudao-module-iot-core,:yudao-module-iot-biz,:yudao-module-iot-gateway \
  -am clean install -Dmaven.test.skip=true
mvn "${args[@]}" \
  -pl :yudao-module-iot-core,:yudao-module-iot-gateway -am \
  -Dtest=IotRabbitMQMessageBusConfirmTest,IotDeviceMessageSpoolServiceImplTest,IotDeviceMessageSpoolStoreTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
jar tf yudao-module-iot/yudao-module-iot-gateway/target/yudao-module-iot-gateway-exec.jar
```

提取 Gateway 的有效 POM、实际解析树及 Surefire XML。核对三个指定测试类均实跑，记录测试计数、退出码及未运行范围。检查 Gateway 保持独立、无环卫依赖，并保留 BLOCK-001。若未来获准接入 CI，建议在不改变触发和权限情况下，加先行静态检查与打包后证据验证步骤。远程 CI 以实际结果为准。 [工程工作流](codex-workflow.md)

### 回滚

阶段一仅单个设计文档提交，可在获授权分支通过 `git revert <设计提交>` 回滚。若阶段二已实施，优先按反向顺序回滚骨架、根 Reactor 入口、验证及报告变更，再在隔离仓库重做默认构建和 Server/Gateway 依赖检查。方案 B 同步清理获批的 Profile 调用；方案 A 确认默认 Reactor 恢复基线集合。正式数据库、运行数据及部署无需因空骨架回滚而迁移。

## 8. 本阶段验证记录与人工审批

已执行 `git fetch origin master-jdk17`、`git rev-parse origin/master-jdk17`、`git show -s --format='%H %P %s'` 核实起始 SHA 与合并来源。使用 Git 路径清单、XML 结构读取及源码检索核对 POM、Server 扫描和模块边界。`git diff --check`、`git diff --cached --check` 和 Python 标准库静态验收均退出 0。确认暂存仅新增本设计，22 个现有 Reactor 项目、拟增 7 个聚合 POM 和 15 个 JAR、6 条自身 API 依赖及 6 项待审批记录相符，42 个相对链接（含 2 个锚点）有效，常见凭证模式无匹配。未执行 Maven 构建、依赖树解析、检查器测试、CI、Server/Gateway 运行或生产验证。设计中的规模和规则均待第二阶段实际取证。

| 审批项 | 状态 |
| --- | --- |
| Maven 聚合结构与 artifactId | 待批准 |
| API/Biz 拆分 | 待批准 |
| 默认 Reactor 接入方式 | 待批准 |
| Java 包命名 | 待批准 |
| 依赖白名单及检查策略 | 待批准 |
| 第二阶段实施 | 待授权 |
| 批准内容、条件、设计 SHA、批准角色与时间、证据 | 待人工填写 |

人工作出决定后按[审批记录模板](../architecture/reviews/architecture-approval-record-template.md)登记范围。DEC-011、ACR-001、ADR 及生产启用门槛继续按原记录管理。
