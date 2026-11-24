# ENG-001B-02D 全局架构一致性审核

审核对象为 `weeleng/car_weight` 的 `origin/master-jdk17`，实际起始提交 `1ae32653e15518879e74cd1f038b7635b2e977b8`，任务分支 `docs/eng-001b-02d`。结论针对该源码基线及本次文字修订。

## 证据与总体结论

- 任务授权和 [AGENTS.md](../../../AGENTS.md)决定改动范围。[架构目录](../README.md)、[工作流](../../engineering/codex-workflow.md)和[验证矩阵](../../engineering/validation-matrix.md)约束实施与验收。C-01 至 C-06 的已确认约束逐列见[规范矩阵](standards-compliance-matrix.md)。
- [模板](../adr/ADR-TEMPLATE.md)、[索引](../adr/README.md)、[证据矩阵](../adr/decision-evidence-matrix.md)及 ADR-001 至 ADR-040 正文逐项阅读核对；正文候选方案用于交叉比对。源码、配置、POM 和 SQL 只读证据及其启用边界见[实施差距](implementation-gap-register.md)。
- [ACR 索引](../acr/README.md)和[ACR-001 核对记录](../acr/ACR-001-reconciliation.md)说明历史证据缺口。核对记录的索引状态是其任务当时的快照。本次保留该记录及状态。40 份正文仍为 Proposed，当前草案覆盖缺口为 0，历史批准原文核验数为 0。
- 逐项文档审核为 2 项通过、33 项有条件通过、5 项待确认。编号、标题、索引、模板与引用满足本次文档验收。3 项实质不一致为源码/配置与目标间的差距，见[冲突登记](open-decision-register.md#冲突判定)。候选正文间尚无需要改写正式规范的已证实冲突，18 项待决事项仍须人工处理。
- A 工具链对齐、G 协议专项，以及 B/C 等无最终选型承诺的设计可在分别授权后启动。业务实现按批准的契约和[实施依赖路线](implementation-dependency-roadmap.md)推进。生产就绪未验证，7 项生产验收门槛按各自范围生效，其中双暂存为既有阻断，其余由人工核定。

## 40 项逐项结论

| ADR | 结论 | 依据及后续事项 |
| --- | --- | --- |
| [ADR-001 系统总体架构](../adr/ADR-001-system-architecture.md) | 通过 | 决策：模块化单体、独立 Gateway；C-06 公开 API/事件（C-06 同列适用）。 |
| [ADR-002 技术选型](../adr/ADR-002-technology-selection.md) | 有条件通过 | 决策/待确认：JDK 21 与 Java 17 区分；CI 交接 GAP-001；Flyway 待选 DEC-006。 |
| [ADR-003 SaaS 多租户架构](../adr/ADR-003-saas-tenancy.md) | 有条件通过 | 决策：共享隔离、显式监管授权；C-02 历史作用域缺往返及重叠验证 DEC-007；跨链路见 DEC-008。 |
| [ADR-004 组织与项目管理](../adr/ADR-004-organization-and-project.md) | 有条件通过 | 决策/待确认：区分组织项目与行政区，关系有效期和范围重叠 DEC-007/008。 |
| [ADR-005 用户与权限管理](../adr/ADR-005-user-and-permissions.md) | 有条件通过 | 决策/待确认：历史授权有效期，服务端默认拒绝；Token 和附件 GAP-003/004。 |
| [ADR-006 数据库架构](../adr/ADR-006-database-architecture.md) | 有条件通过 | 决策/约束：三库、类型和本地事务；C-04 仅记录提交，确认流程见 ADR-012；物理部署 DEC-006。 |
| [ADR-007 数据字典与基础编码](../adr/ADR-007-dictionary-and-coding.md) | 有条件通过 | 决策/待确认：历史字典版本及租户覆盖；版本/缓存失效及迁移 DEC-008。 |
| [ADR-008 API 规范](../adr/ADR-008-api-conventions.md) | 有条件通过 | 决策/待确认：ISO 8601、精确十进制（C-03 约束公开契约所表达的重量），金额线格式及幂等期限 DEC-012/017。 |
| [ADR-009 GIS 平台架构](../adr/ADR-009-gis-architecture.md) | 有条件通过 | 决策/关系：轨迹数据边界、GIS 适配；C-04 转引接入及双暂存，位置事件不自动产生正式称重；DEC-009。 |
| [ADR-010 坐标体系与坐标转换](../adr/ADR-010-coordinate-system.md) | 有条件通过 | 决策/待确认：原始、转换及业务坐标分离，定位时间与转换版本的联动待确认 DEC-009。 |
| [ADR-011 电子围栏与空间范围管理](../adr/ADR-011-geofencing.md) | 有条件通过 | 决策/约束：围栏不是业务完成，C-04 仅沿用接入边界；漂移与阈值 DEC-010。 |
| [ADR-012 IoT 接入架构](../adr/ADR-012-iot-ingestion.md) | 有条件通过 | 决策/约束：两条提交后独立路径与消费提交后确认；协议、凭据和实现未定 DEC-001/003、BLOCK-001/002。 |
| [ADR-013 VWCP 设备通信协议](../adr/ADR-013-vwcp-protocol.md) | 待确认 | 决策/待确认：Epoch 与 Unix 毫秒分离，有符号 INT32 克，三种重复结果；C-05 仅定义 INGEST，协议及存储 DEC-001～004。 |
| [ADR-014 GNSS 定位与轨迹管理](../adr/ADR-014-gnss-and-tracking.md) | 有条件通过 | 决策/待确认：TRACK 幂等、原始与计算坐标分开；C-03 称重时间关联、C-05 TRACK 恢复契约未定 DEC-006/009。 |
| [ADR-015 称重业务模型](../adr/ADR-015-weighing-model.md) | 有条件通过 | 决策/待确认：原始不覆盖、父子防重；C-05 唯一键和 C-06 业务对象归属 DEC-005/006/011。 |
| [ADR-016 RFID 识别与绑定](../adr/ADR-016-rfid-identification.md) | 有条件通过 | 决策/待确认：有效时间、无默认归属；C-05 绑定写入幂等契约与 C-06 主数据边界 DEC-007/011。 |
| [ADR-017 资产与设备管理](../adr/ADR-017-asset-and-device-management.md) | 有条件通过 | 决策/待确认：版本化校准与历史安装；C-02 仅部分表达现有 LocalDateTime（VAL-001），C-04 接入身份委托 ADR-013（DEC-002）。 |
| [ADR-018 商户及垃圾产生责任主体管理](../adr/ADR-018-merchant-and-waste-producer.md) | 有条件通过 | 决策/待确认：主体、商户和服务点分离；C-05 历史快照持久化待定 DEC-007/011。 |
| [ADR-019 清运线路管理](../adr/ADR-019-collection-route-management.md) | 有条件通过 | 决策/待确认：模板/版本隔离；C-03 重量统计口径、C-05 版本发布与计划快照待定 DEC-010/014。 |
| [ADR-020 作业计划与车辆调度](../adr/ADR-020-operation-planning-and-dispatch.md) | 有条件通过 | 决策/待确认：计划与任务及下发状态分离；C-05 唯一键与周期触发 DEC-011。 |
| [ADR-021 清运作业执行](../adr/ADR-021-collection-operation-execution.md) | 有条件通过 | 决策：未匹配仍接入、父子防重、公开 API；任务与计费前置条件 DEC-011/012。 |
| [ADR-022 异常事件与告警管理](../adr/ADR-022-event-and-alarm-management.md) | 有条件通过 | 决策/待确认：异常与告警状态独立；C-05 去重、规则版本及状态机未定 DEC-010/011。 |
| [ADR-023 末端处置设施与垃圾交付](../adr/ADR-023-disposal-facility-and-delivery.md) | 有条件通过 | 决策：P0 人工、运输批次与交付数据及重量边界；差异阈值与计费闸门 DEC-011/012。 |
| [ADR-024 合同与计费](../adr/ADR-024-contract-and-billing.md) | 待确认 | 决策/待确认：C-02 依有效时间但截止与账期未定；精确重量、消息确认与账单分离；计费/税费 DEC-012/013。 |
| [ADR-025 统计分析与报表](../adr/ADR-025-statistics-and-reporting.md) | 有条件通过 | 决策及22项清单：计量粒度、时间、权限与读契约；最终指标 DEC-014。 |
| [ADR-026 厨余垃圾专题地图](../adr/ADR-026-kitchen-waste-thematic-map.md) | 有条件通过 | 决策：已授权专题图层、收运非产生总量、去重；阈值和来源 DEC-009/010/014。 |
| [ADR-027 移动端与车载终端](../adr/ADR-027-mobile-and-vehicle-terminals.md) | 待确认 | 决策/待确认：C-02 离线时间与定位、C-03 展示和转发、C-04 缓存满载与终端协议待确认 DEC-001/003/004。 |
| [ADR-028 第三方系统集成](../adr/ADR-028-third-party-integration.md) | 有条件通过 | 决策：外部确认与本地事务分离、P0 发票/P1 支付；第三方准入 DEC-013、BLOCK-006。 |
| [ADR-029 网络安全等级保护三级建设与安全合规](../adr/ADR-029-mlps-and-security-compliance.md) | 有条件通过 | 决策/待确认：等保建设目标；C-05 强制审计失败策略与 C-06 部署责任边界 DEC-015，VAL-006。 |
| [ADR-030 个人信息与数据保护](../adr/ADR-030-personal-information-and-data-protection.md) | 有条件通过 | 决策/待确认：最小化、历史保护；C-05 归档、删除协调与对象存储范围 DEC-015。 |
| [ADR-031 身份认证与访问安全](../adr/ADR-031-authentication-and-access-security.md) | 有条件通过 | 决策/待确认：C-02 Token 期限与撤销时效、C-04 用户和设备身份分离；GAP-003、BLOCK-004。 |
| [ADR-032 安全审计与操作追溯](../adr/ADR-032-security-audit-and-traceability.md) | 有条件通过 | 决策：关键审计证据随事务，180天候选；审计失败与防篡改 DEC-015。 |
| [ADR-033 密码体系与 IoT 设备安全](../adr/ADR-033-cryptography-and-iot-security.md) | 待确认 | 决策/待确认：UID 和 CRC 只作标识和差错检测；C-05 重放状态与原始事务耦合待确认 DEC-002/003。 |
| [ADR-034 国产化及信创兼容性](../adr/ADR-034-domestic-platform-compatibility.md) | 有条件通过 | 决策：兼容测试不得改变六项规范；兼容矩阵与产品/版本证据 VAL-006。 |
| [ADR-035 系统容量与性能指标](../adr/ADR-035-capacity-and-performance.md) | 有条件通过 | 决策/待确认：C-01 环境规格、C-02 窗口和频率、C-06 各模块资源与契约未定 DEC-016、VAL-005。 |
| [ADR-036 缓存与异步处理架构](../adr/ADR-036-cache-and-asynchronous-processing.md) | 有条件通过 | 决策：Redis 辅助、投递确认加路由、消费提交后确认；BLOCK-001、DEC-006/008。 |
| [ADR-037 高可用与灾难恢复](../adr/ADR-037-high-availability-and-disaster-recovery.md) | 待确认 | 决策/待确认：C-02 恢复点与测量窗口；C-05 恢复对账及重放边界，S0 范围见 DEC-016、BLOCK-007。 |
| [ADR-038 系统部署与环境管理](../adr/ADR-038-deployment-and-environment-management.md) | 有条件通过 | 来源/约束：C-01 工具链配置差距 GAP-001，部署形态和数据库迁移见 DEC-006/016。 |
| [ADR-039 系统可观测性与运维监控](../adr/ADR-039-observability-and-operations-monitoring.md) | 有条件通过 | 决策/待确认：ACK 三阶段与监控分层；C-05 对账口径涉及待选指标及关联键 DEC-016。 |
| [ADR-040 AI 辅助开发与工程管理](../adr/ADR-040-ai-assisted-development-and-engineering.md) | 通过 | 决策/验证：六规范均纳入分支、变更及证据要求；分支保护实况 VAL-002。 |

## 六项规范与专项结论

| 规范 | 结论与需验证证据 |
| --- | --- |
| C-01 | 有条件通过。正式 OpenJDK 21 构建和运行、Java 17 编译级别、Maven 3.9.12、Spring Boot 3.5.16、MySQL 8.4 保持。实际 Actions 构建、Jenkins 工具和 Docker JRE 仍有 GAP-001；Redis/RabbitMQ 路线与现有默认启用状态分开。 |
| C-02 | 有条件通过。设备 Unix Epoch 毫秒、Java Instant、UTC 语义 DATETIME(3)、含偏移量 ISO 8601、默认 Asia/Shanghai。区分设备事件、服务器接收、数据库持久化、审核与业务计费期间。历史安装及 RFID 按可信有效时间，时钟异常走异常核验。轨迹、作业及报表明确时间字段和时区后才可汇总。VAL-001 需 JDBC、ORM、MySQL 会话、容器/JVM 与 API 往返。 |
| C-03 | 有条件通过。VWCP 有符号 INT32 克，业务 BIGINT 克，正式 DECIMAL(12,3) 千克和 API 精确十进制；BigDecimal 按确定比例换算。保留原始值，区分毛、皮、净、审核及计费，父子计量防重。舍入、异常与双桶独立计量待批准 DEC-005/012。 |
| C-04 | 有条件通过。只有鉴权且符合接入条件、INGEST 原始事件及 Outbox 同事务提交后才允许生成成功 L2 ACK。同身份同载荷可重新确认已提交事实，异载荷核验。ACK 与 Relay 自提交后独立推进。终端、Gateway 内存/SQLite、Redis、MQ 或业务处理均不替代提交。ACK 分段、未确认保护和生产双暂存仍有 DEC-001～004、BLOCK-001～003。 |
| C-05 | 有条件通过。INGEST、BUSINESS、TRACK 的本地边界及禁止跨库原子事务、外键保持。RabbitMQ 至少一次，业务事务提交后 Consumer ACK。稳定身份及数据库唯一键承担幂等，Redis 锁只协调，迁移版本化。外调失败不破坏本地提交，灾备需跨库对账和受控重放。DEC-006、VAL-003、BLOCK-007。 |
| C-06 | 有条件通过。保留九个逻辑模块、候选 API/Biz 拆分、独立 Gateway、公开契约及单向依赖。禁止跨模块内部 Mapper、DO、Biz 及私表，禁止 Maven 循环。数据归属及物理结构经 DEC-011 审批。 |

## 容量、灾备与监控核对

ADR-035 的候选量化目标为 300 辆车、1,000 台 Android/PDA、3,000 台 IoT 设备、100,000 个责任主体、200,000 个服务点、300,000 个业务空间点位、3,000 作业任务/日、150,000 正式称重记录/日。管理端 200 并发、小程序 500 并发；IoT 持续 300、突发 1,000 条/秒；第三方集成持续 500、突发 2,000 条/秒；外部 API 200 QPS。常规读、写、地图、Outbox 的 P95 候选上限分别为 500 ms、800 ms、1.5 秒和 2 秒。用户并发、QPS、空间点及轨迹样本分别计量；原始消息可含心跳、定位、补传，与正式称重区分。第三方输入/输出、持续/突发时长、报文大小和频率、数据分布、资源、延迟分段和失效率待确定。Outbox 测本地提交至可靠发布确认，独立于 L2 和正式称重端到端延迟。

ADR-035 与 ADR-037 均保留 S0 RPO＝0、RTO≤30 分钟，限明确故障域及持久化条件。S1 业务 RPO≤5 分钟、RTO≤1 小时。单机提交、数据库跨节点持久化、复制确认和切换分别核验，测试 Fencing、防脑裂、MQ/Redis 降级、多 Gateway 实例、设备补传、备份和跨库恢复。适用故障域、ACK 测量端点、不同阶段数据分级及第三方副作用对账留待 DEC-016，禁止为性能提前 ACK。

ADR-038 的 SaaS、基础私有化、高可用私有化分开验收。基础 Compose 单机有单点。环境/密钥、版本化迁移、应用回滚、协议兼容、终端及固件升级分别检查。ADR-039 的 Prometheus、Grafana、Loki、OpenTelemetry 路线仍为候选。观察 JVM、MySQL、Redis、RabbitMQ、Gateway、L2 生成/终端接收/控制板确认、补传、Outbox/Inbox、业务一致性、认证、外调、备份与恢复。避免在常规指标中使用 UID 或事件 ID 等高基数标签。可靠审计证据与普通运行日志分开保存和授权。报告无运行监控或吞吐承诺。

## 术语表

| 术语 | 采用口径 |
| --- | --- |
| 控制板 | 采样、生成原始事件及维护本地未确认记录的硬件 |
| Android 车载终端 | 独立 RS485 转发和作业终端 |
| PDA | 独立蓝牙转发和作业终端 |
| IoT Gateway | 独立云端设备接入部署及可靠确认协调 |
| IoT 设备 | 有单独管理生命周期的受管设备，统计须声明是否包括终端及配件 |
| 硬件 UID | 控制板稳定硬件标识，协议候选 12 字节，非认证凭据 |
| 设备业务 ID | 应用分配的业务标识，设备档案及关系使用 |
| 设备事件身份 | 原始去重身份，候选包含硬件 UID、Epoch、Seq。Epoch 与 Unix 时间戳语义分开 |
| 消息 ID | 一次异步业务投递的稳定身份，Outbox/Inbox 可关联原始事件 |
| 原始称重事件 | 设备原始事实，INGEST 持久化且不被修正覆盖 |
| 正式称重记录 | 经业务规则形成的正式事实，可从原始事件溯源 |
| 审核重量 | 经授权审核确认的重量，保留原值及修正历史 |
| 计费重量 | 计价资格通过后按业务规则使用的重量 |
| 责任主体 | 具体业务责任的主体，合同和收费责任按授权及规则确认 |
| 产废主体 | 垃圾产生相关主体，可同时为责任主体，无默认租户身份 |
| 商户 | 商业经营主体或档案角色，可能产生垃圾，缴费方另有定义 |
| 实际缴费/物业代缴主体 | 发生付款或受托代缴的一方，责任归属仍按受批规则 |
| 服务点 | 项目约定提供服务的位置或业务关系 |
| 收集点 | 实际集中交接或收集的位置，可服务多个服务点 |
| 处置设施 | 中转或末端处置接收设施，其运营租户和交付角色独立 |
| 作业任务 | 指派及执行的计划实例 |
| 运输批次 | 按车辆收运与交付匹配的一批运输事实，可关联多任务、记录或交付 |
| L2 ACK | INGEST 原始事件和 Outbox 同事务成功提交的设备确认 |
| Publisher Confirm | RabbitMQ 对发布的确认，路由结果另外检查 |
| Consumer ACK | 消费者完成本地业务事务后的 broker 确认 |
| Outbox | 与本地事实一起持久化的待可靠投递记录 |
| Inbox | 与本地业务一起提交的消费去重记录 |
| INGEST / BUSINESS / TRACK | 分别归接入存证、业务事实与轨迹的三个逻辑数据库 |
| 租户 | SaaS 隔离边界及经营/授权主体载体 |
| 项目 | 运营与授权作用域，与组织及租户有关联和历史 |
| 行政区划 | 法定或数据来源中的行政地域及版本 |
| 服务区域 | 项目约定的服务空间范围及版本，独立于行政区划 |
| 组织 / 部门 | 机构及内部层级，单独管理任职与协作关系 |

## 修订记录

全量检索 40 份正文发现 12 份文件的过期引用，共 20 处链接。前次交付曾写“13 份”并列出 12 份，本次以解析基线正文得到的 12 份为准。每个引用已改为实际草案链接，标明 Proposed、待批准。仅改关联段落，每项保留草案版本。本仓库模板未定义此类修订的升版规则。

| 修订正文 | 替换目标及原因 |
| --- | --- |
| ADR-004 | ADR-009、010、011 已有 GIS、坐标和围栏正文 |
| ADR-005 | ADR-031、032、033 已有认证、审计及设备安全正文 |
| ADR-014 | ADR-010 已有坐标转换正文 |
| ADR-018 | ADR-030 已有隐私及数据保护正文 |
| ADR-019 | ADR-020、021、022 已有调度、执行及异常正文 |
| ADR-021 | ADR-027 已有终端同步正文 |
| ADR-023 | ADR-028 已有第三方集成正文 |
| ADR-026 | ADR-025、035、036 已有统计、性能与缓存正文 |
| ADR-029 | ADR-038 已有部署拓扑草案 |
| ADR-030 | ADR-033 已有密码及设备安全正文 |
| ADR-031 | ADR-033 已有设备安全正文 |
| ADR-035 | ADR-037 已有恢复候选正文 |

架构目录更新草案状态和审核入口。其余 28 份正文、ADR 索引条目、证据矩阵条目及正式规范未修改。历史原文及真实设计待确认项仍保留。

## 实际验证与未执行项

| 实际命令或检查 | 结果 |
| --- | --- |
| `git ls-remote origin refs/heads/master-jdk17`、`git fetch origin master-jdk17`、`git rev-parse origin/master-jdk17` | 退出码 0，远程与本地跟踪基线吻合。 |
| `rg -n '待导入|待补齐|尚未形成正文|待建立|仅有索引|待确认引用' docs/architecture/adr/ADR-[0-9]*.md` | 基线搜索退出码 0，找到 12 份正文；关联段共 20 个过期索引链接。修订后退出码 1，无匹配。ADR 模板的原文占位提示仍有效。 |
| `git diff --check`、`git diff --cached --check`、`git diff --cached --name-only`、`git status --short --branch` | 退出码 0。暂存仅含六份新报告、12 份 ADR 和架构目录，差异无空白错误，保留原有未跟踪工程文档。 |
| `python /tmp/eng02d_validate.py` | 退出码 0。临时静态脚本确认 40 份模板、标题、索引、Proposed 状态和审批证据文字；240 个矩阵单元、889 个相对链接及其中 41 个锚点；20 类故障、22 项报表、9 模块、19 工作包及问题编号。比较各章和受保护文件，检查任务范围及常见凭证模式，均通过。该脚本只用于本次静态验收。 |
| `gh api repos/weeleng/car_weight/branches/master-jdk17 --jq .protected` | 退出码 1，GitHub 返回 Forbidden，目标分支保护及 Ruleset 仍待有权人员确认。 |

本次未运行 Maven 构建、运行测试、功能或安全扫描、压测、真硬件联调、真实第三方接入、生产部署、备份恢复或故障注入。既有报告的历史证据维持原范围。
